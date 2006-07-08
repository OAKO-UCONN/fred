package freenet.node;

import freenet.io.comm.DMT;
import freenet.io.comm.DisconnectedException;
import freenet.io.comm.Message;
import freenet.io.comm.MessageFilter;
import freenet.io.comm.NotConnectedException;
import freenet.io.comm.RetrievalException;
import freenet.io.xfer.AbortedException;
import freenet.io.xfer.BlockReceiver;
import freenet.io.xfer.PartiallyReceivedBlock;
import freenet.keys.CHKBlock;
import freenet.keys.CHKVerifyException;
import freenet.keys.NodeCHK;
import freenet.support.HexUtil;
import freenet.support.Logger;
import freenet.support.ShortBuffer;

/**
 * @author amphibian
 * 
 * Handle an incoming insert request.
 * This corresponds to RequestHandler.
 */
public class InsertHandler implements Runnable {


    static final int DATA_INSERT_TIMEOUT = 10000;
    
    final Message req;
    final Node node;
    final long uid;
    final PeerNode source;
    final NodeCHK key;
    final long startTime;
    private double closestLoc;
    private short htl;
    private CHKInsertSender sender;
    private byte[] headers;
    private BlockReceiver br;
    private Thread runThread;
    private boolean sentSuccess;
    
    PartiallyReceivedBlock prb;
    
    InsertHandler(Message req, long id, Node node, long startTime) {
        this.req = req;
        this.node = node;
        this.uid = id;
        this.source = (PeerNode) req.getSource();
        this.startTime = startTime;
        key = (NodeCHK) req.getObject(DMT.FREENET_ROUTING_KEY);
        htl = req.getShort(DMT.HTL);
        closestLoc = req.getDouble(DMT.NEAREST_LOCATION);
        double targetLoc = key.toNormalizedDouble();
        double myLoc = node.lm.getLocation().getValue();
        if(PeerManager.distance(targetLoc, myLoc) < PeerManager.distance(targetLoc, closestLoc))
            closestLoc = myLoc;
    }
    
    public String toString() {
        return super.toString()+" for "+uid;
    }
    
    public void run() {
        try {
        	realRun();
        } catch (Throwable t) {
            Logger.error(this, "Caught "+t, t);
        } finally {
            Logger.minor(this, "Exiting InsertHandler.run() for "+uid);
            node.unlockUID(uid);
        }
    }

    private void realRun() {
        runThread = Thread.currentThread();
        
        // FIXME implement rate limiting or something!
        // Send Accepted
        Message accepted = DMT.createFNPAccepted(uid);
        try {
			source.send(accepted, null);
		} catch (NotConnectedException e1) {
			Logger.minor(this, "Lost connection to source");
			return;
		}
        
        // Source will send us a DataInsert
        
        MessageFilter mf;
        mf = MessageFilter.create().setType(DMT.FNPDataInsert).setField(DMT.UID, uid).setSource(source).setTimeout(DATA_INSERT_TIMEOUT);
        
        Message msg;
        try {
            msg = node.usm.waitFor(mf, null);
        } catch (DisconnectedException e) {
            Logger.normal(this, "Disconnected while waiting for DataInsert on "+uid);
            return;
        }
        
        Logger.minor(this, "Received "+msg);
        
        if(msg == null) {
        	try {
        		if(source.isConnected() && startTime > (source.timeLastConnected()+Node.HANDSHAKE_TIMEOUT*4))
        			Logger.error(this, "Did not receive DataInsert on "+uid+" from "+source+" !");
        		Message tooSlow = DMT.createFNPRejectedTimeout(uid);
        		source.sendAsync(tooSlow, null, 0, null);
        		Message m = DMT.createFNPInsertTransfersCompleted(uid, true);
        		source.sendAsync(m, null, 0, null);
        		prb = new PartiallyReceivedBlock(Node.PACKETS_IN_BLOCK, Node.PACKET_SIZE);
        		br = new BlockReceiver(node.usm, source, uid, prb, null);
        		prb.abort(RetrievalException.NO_DATAINSERT, "No DataInsert");
        		br.sendAborted(RetrievalException.NO_DATAINSERT, "No DataInsert");
        		return;
        	} catch (NotConnectedException e) {
    			Logger.minor(this, "Lost connection to source");
    			return;
        	}
        }
        
        // We have a DataInsert
        headers = ((ShortBuffer)msg.getObject(DMT.BLOCK_HEADERS)).getData();
        // FIXME check the headers
        
        // Now create an CHKInsertSender, or use an existing one, or
        // discover that the data is in the store.

        // From this point onwards, if we return cleanly we must go through finish().
        
        prb = new PartiallyReceivedBlock(Node.PACKETS_IN_BLOCK, Node.PACKET_SIZE);
        if(htl > 0)
            sender = node.makeInsertSender(key, htl, uid, source, headers, prb, false, closestLoc, true);
        br = new BlockReceiver(node.usm, source, uid, prb, null);
        
        // Receive the data, off thread
        
        Runnable dataReceiver = new DataReceiver();
        Thread t = new Thread(dataReceiver, "InsertHandler$DataReceiver for UID "+uid);
        t.setDaemon(true);
        t.start();

        if(htl == 0) {
            canCommit = true;
        	msg = DMT.createFNPInsertReply(uid);
        	sentSuccess = true;
        	try {
				source.send(msg, null);
			} catch (NotConnectedException e) {
				// Ignore
			}
            finish();
            return;
        }
        
        // Wait...
        // What do we want to wait for?
        // If the data receive completes, that's very nice,
        // but doesn't really matter. What matters is what
        // happens to the CHKInsertSender. If the data receive
        // fails, that does matter...
        
        // We are waiting for a terminal status on the CHKInsertSender,
        // including REPLIED_WITH_DATA.
        // If we get transfer failed, we can check whether the receive
        // failed first. If it did it's not our fault.
        // If the receive failed, and we haven't started transferring
        // yet, we probably want to kill the sender.
        // So we call the wait method on the CHKInsertSender, but we
        // also have a flag locally to indicate the receive failed.
        // And if it does, we interrupt.
        
        boolean receivedRejectedOverload = false;
        
        while(true) {
            synchronized(sender) {
                try {
                	if(sender.getStatus() == CHKInsertSender.NOT_FINISHED)
                		sender.wait(5000);
                } catch (InterruptedException e) {
                    // Cool, probably this is because the receive failed...
                }
            }
            if(receiveFailed) {
                // Cancel the sender
                sender.receiveFailed(); // tell it to stop if it hasn't already failed... unless it's sending from store
                // Nothing else we can do
                finish();
                return;
            }
            
            if((!receivedRejectedOverload) && sender.receivedRejectedOverload()) {
            	receivedRejectedOverload = true;
            	// Forward it
            	Message m = DMT.createFNPRejectedOverload(uid, false);
            	try {
					source.send(m, null);
				} catch (NotConnectedException e) {
					Logger.minor(this, "Lost connection to source");
					return;
				}
            }
            
            int status = sender.getStatus();
            
            if(status == CHKInsertSender.NOT_FINISHED) {
                continue;
            }

//            // FIXME obviously! For debugging load issues.
//        	if(node.myName.equalsIgnoreCase("Toad #1") &&
//        			node.random.nextBoolean()) {
//        		// Maliciously timeout
//        		Logger.error(this, "Maliciously timing out: was "+sender.getStatusString());
//        		sentSuccess = true;
//        		return;
//        	}
        	
            // Local RejectedOverload's (fatal).
            // Internal error counts as overload. It'd only create a timeout otherwise, which is the same thing anyway.
            // We *really* need a good way to deal with nodes that constantly R_O!
            if(status == CHKInsertSender.TIMED_OUT ||
            		status == CHKInsertSender.GENERATED_REJECTED_OVERLOAD ||
            		status == CHKInsertSender.INTERNAL_ERROR) {
                msg = DMT.createFNPRejectedOverload(uid, true);
                try {
					source.send(msg, null);
				} catch (NotConnectedException e) {
					Logger.minor(this, "Lost connection to source");
					return;
				}
                // Might as well store it anyway.
                if(status == CHKInsertSender.TIMED_OUT ||
                		status == CHKInsertSender.GENERATED_REJECTED_OVERLOAD)
                	canCommit = true;
                finish();
                return;
            }
            
            if(status == CHKInsertSender.ROUTE_NOT_FOUND || status == CHKInsertSender.ROUTE_REALLY_NOT_FOUND) {
                msg = DMT.createFNPRouteNotFound(uid, sender.getHTL());
                try {
					source.send(msg, null);
				} catch (NotConnectedException e) {
					Logger.minor(this, "Lost connection to source");
					return;
				}
                canCommit = true;
                finish();
                return;
            }
            
            if(status == CHKInsertSender.SUCCESS) {
            	msg = DMT.createFNPInsertReply(uid);
            	sentSuccess = true;
            	try {
					source.send(msg, null);
				} catch (NotConnectedException e) {
					Logger.minor(this, "Lost connection to source");
					return;
				}
                canCommit = true;
                finish();
                return;
            }
            
            // Otherwise...?
            Logger.error(this, "Unknown status code: "+sender.getStatusString());
            msg = DMT.createFNPRejectedOverload(uid, true);
            try {
				source.send(msg, null);
			} catch (NotConnectedException e) {
				// Ignore
			}
            finish();
            return;
        }
	}

	private boolean canCommit = false;
    private boolean sentCompletion = false;
    private Object sentCompletionLock = new Object();
    
    /**
     * If canCommit, and we have received all the data, and it
     * verifies, then commit it.
     */
    private void finish() {
    	Logger.minor(this, "Finishing");
        maybeCommit();
        
        Logger.minor(this, "Waiting for completion");
        // Wait for completion
        boolean sentCompletionWasSet;
        synchronized(sentCompletionLock) {
        	sentCompletionWasSet = sentCompletion;
        	sentCompletion = true;
        }
        
        if((sender != null) && (!sentCompletionWasSet)) {
        	while(true) {
        		synchronized(sender) {
        			if(sender.completed()) {
        				break;
        			}
        			try {
        				sender.wait(10*1000);
        			} catch (InterruptedException e) {
        				// Loop
        			}
        		}
        	}
        	boolean failed = sender.anyTransfersFailed();
        	Message m = DMT.createFNPInsertTransfersCompleted(uid, failed);
        	try {
        		source.sendAsync(m, null, 0, null);
        		Logger.minor(this, "Sent completion: "+failed+" for "+this);
        	} catch (NotConnectedException e1) {
        		Logger.minor(this, "Not connected: "+source+" for "+this);
        		// May need to commit anyway...
        	}
        }
    }
    
    private void maybeCommit() {
        Message toSend = null;
        
        synchronized(this) { // REDFLAG do not use synch(this) for any other purpose!
        	if(prb == null || prb.isAborted()) return;
            try {
                if(!canCommit) return;
                if(!prb.allReceived()) return;
                CHKBlock block = new CHKBlock(prb.getBlock(), headers, key);
                node.store(block);
                Logger.minor(this, "Committed");
            } catch (CHKVerifyException e) {
                Logger.error(this, "Verify failed in InsertHandler: "+e+" - headers: "+HexUtil.bytesToHex(headers), e);
                toSend = DMT.createFNPDataInsertRejected(uid, DMT.DATA_INSERT_REJECTED_VERIFY_FAILED);
            } catch (AbortedException e) {
            	Logger.error(this, "Receive failed: "+e);
            	// Receiver thread will handle below
            }
        }
        if(toSend != null) {
            try {
                source.sendAsync(toSend, null, 0, null);
            } catch (NotConnectedException e) {
                // :(
                Logger.minor(this, "Lost connection in "+this+" when sending FNPDataInsertRejected");
            }
        }
	}

	/** Has the receive failed? If so, there's not much more that can be done... */
    private boolean receiveFailed;

    public class DataReceiver implements Runnable {

        public void run() {
            Logger.minor(this, "Receiving data for "+InsertHandler.this);
            try {
                br.receive();
                Logger.minor(this, "Received data for "+InsertHandler.this);
                maybeCommit();
            } catch (RetrievalException e) {
                receiveFailed = true;
                runThread.interrupt();
                Message msg = DMT.createFNPDataInsertRejected(uid, DMT.DATA_INSERT_REJECTED_RECEIVE_FAILED);
                try {
                    source.send(msg, null);
                } catch (NotConnectedException ex) {
                    Logger.error(this, "Can't send "+msg+" to "+source+": "+ex);
                }
                Logger.minor(this, "Failed to retrieve: "+e, e);
                return;
            } catch (Throwable t) {
                Logger.error(this, "Caught "+t, t);
            }
        }

        public String toString() {
        	return super.toString()+" for "+uid;
        }
        
    }

}

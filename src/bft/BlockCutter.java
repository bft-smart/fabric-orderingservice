/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package bft;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectInputStream;
import java.util.LinkedList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.hyperledger.fabric.protos.common.Common;


/**
 *
 * @author joao
 */
public class BlockCutter {
    
    private LinkedList<byte[]> pendingBatch;
       
    private long PreferredMaxBytes = 0;
    private long MaxMessageCount = 0;
    private int  pendingBatchSizeBytes = 0;
    
    private Log logger;

    public BlockCutter(byte [] bytes) {
        
        logger = LogFactory.getLog(BlockCutter.class);
        
        pendingBatch = new LinkedList<>();
        setBatchParms(bytes);
    }
    
    public List<byte[][]> ordered(byte [] env) throws IOException {
       
        LinkedList batches = new LinkedList<>();
        int messageSizeBytes = messageSizeBytes(env);
        
        //TODO: implement stuff for isolated envelops (see original golang BlockCutter code)
        
        if (/*committer.Isolated() || TODO: implement it*/ messageSizeBytes > PreferredMaxBytes) {

		/*if (committer.Isolated()) { TODO: implement it
			logger.debug("Found message which requested to be isolated, cutting into its own batch");
		} else {
			logger.debug("The current message, with " + messageSizeBytes + " bytes, is larger than the preferred batch size of " + PreferredMaxBytes + " bytes and will be isolated.");
		}*/

		// cut pending batch, if it has any messages
		if (pendingBatch.size() > 0) {
			batches.add(cut());
		}

		// create new batch with single message
		pendingBatch.add(env);
                pendingBatchSizeBytes += messageSizeBytes;
                
                batches.add(cut());

		return batches;
	}
        

        //int messageSizeBytes = (env != null ? env.length : 0);
        
        boolean messageWillOverflowBatchSizeBytes = pendingBatchSizeBytes+ messageSizeBytes > PreferredMaxBytes;
        
        if (messageWillOverflowBatchSizeBytes) {
            
            logger.debug("The current message, with " + messageSizeBytes + " bytes, will overflow the pending batch of " + pendingBatchSizeBytes + " bytes.");
            logger.debug("Pending batch would overflow if current message is added, cutting batch now.");
            
            batches.add(cut());
        }
        
        logger.debug("Enqueuing message into batch");
	pendingBatch.add(env);
	pendingBatchSizeBytes += messageSizeBytes;
        
        if (pendingBatch.size() >= MaxMessageCount) {
            
            logger.debug("Batch size met, cutting batch");
            batches.add(cut());
	}
        
        return batches;
    }
    
    public byte[][] cut() {
        byte[][] batch = new byte[pendingBatch.size()][];
        if (batch.length > 0) pendingBatch.toArray(batch);
	pendingBatch.clear();
	pendingBatchSizeBytes = 0;
	return batch;
    }
            
    private int messageSizeBytes(byte[] bytes) throws IOException {
        
        Common.Envelope env = Common.Envelope.parseFrom(bytes);
	return env.getPayload().size() + env.getSignature().size();
    }
    
    private void setBatchParms(byte[] bytes) {
        try {
            ByteArrayInputStream bis = new ByteArrayInputStream(bytes);
            ObjectInput in = new ObjectInputStream(bis);
            PreferredMaxBytes =  in.readLong();
            MaxMessageCount =  in.readLong();
            in.close();
            bis.close();
 
            logger.info("Read PreferredMaxBytes: " + PreferredMaxBytes);
            logger.info("Read MaxMessageCount: " + MaxMessageCount);
            
        } catch (IOException ex) {
            Logger.getLogger(BFTNode.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
}

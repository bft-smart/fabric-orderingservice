/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package bft;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectInputStream;
import java.io.ObjectOutput;
import java.io.ObjectOutputStream;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
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
    
    private Map<String, List<byte[]>> pendingBatches;
       
    private long PreferredMaxBytes = 0;
    private long MaxMessageCount = 0;
    private Map<String, Integer>  pendingBatchSizeBytes;
    
    private Log logger;

    public BlockCutter() { //used for state transfer
        
        logger = LogFactory.getLog(BlockCutter.class);
        
        pendingBatches = new TreeMap<>();
        pendingBatchSizeBytes = new TreeMap<>();
        
    }
    
    public BlockCutter(byte [] bytes) {
        
        logger = LogFactory.getLog(BlockCutter.class);
        
        pendingBatches = new TreeMap<>();
        pendingBatchSizeBytes = new TreeMap<>();
        
        setBatchParms(bytes);
    }
    
    public List<byte[][]> ordered(String channel, byte [] env, boolean isolated) throws IOException {
       
        if (pendingBatches.get(channel) == null) {
            pendingBatches.put(channel, new LinkedList<>());
            pendingBatchSizeBytes.put(channel, 0);
        }
        
        LinkedList batches = new LinkedList<>();
        int messageSizeBytes = messageSizeBytes(env);
                
        if (isolated ||  messageSizeBytes > PreferredMaxBytes) {

		if (isolated) {
			logger.debug("Found message which requested to be isolated, cutting into its own batch");
		} else {
			logger.debug("The current message, with " + messageSizeBytes + " bytes, is larger than the preferred batch size of " + PreferredMaxBytes + " bytes and will be isolated.");
		}

		// cut pending batch, if it has any messages
		if (pendingBatches.get(channel).size() > 0) {
			batches.add(cut(channel));
		}

		// create new batch with single message
		pendingBatches.get(channel).add(env);
                pendingBatchSizeBytes.put(channel, (pendingBatchSizeBytes.get(channel) + messageSizeBytes));
                
                batches.add(cut(channel));

		return batches;
	}
        

        //int messageSizeBytes = (env != null ? env.length : 0);
        
        boolean messageWillOverflowBatchSizeBytes = pendingBatchSizeBytes.get(channel) + messageSizeBytes > PreferredMaxBytes;
        
        if (messageWillOverflowBatchSizeBytes) {
            
            logger.debug("The current message, with " + messageSizeBytes + " bytes, will overflow the pending batch of " + pendingBatchSizeBytes + " bytes.");
            logger.debug("Pending batch would overflow if current message is added, cutting batch now.");
            
            batches.add(cut(channel));
        }
        
        logger.debug("Enqueuing message into batch");
	pendingBatches.get(channel).add(env);
	pendingBatchSizeBytes.put(channel, (pendingBatchSizeBytes.get(channel) + messageSizeBytes));
        
        if (pendingBatches.get(channel).size() >= MaxMessageCount) {
            
            logger.debug("Batch size met, cutting batch");
            batches.add(cut(channel));
	}
        
        return batches;
    }
    
    public byte[][] cut(String channel) {
        
        if (pendingBatches.get(channel) == null) pendingBatches.put(channel, new LinkedList<>());
        
        byte[][] batch = new byte[pendingBatches.get(channel).size()][];
        if (batch.length > 0) pendingBatches.get(channel).toArray(batch);
	pendingBatches.get(channel).clear();
	pendingBatchSizeBytes.put(channel, 0);
        
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
    
    public byte[] serialize() throws IOException {

        //serialize pending envelopes
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        ObjectOutput o = new ObjectOutputStream(b);   
        o.writeObject(pendingBatches);
        o.flush();
        byte[] envs = b.toByteArray();
        b.close();
        o.close();
        
        //serialize pending batch sizes
        b = new ByteArrayOutputStream();
        o = new ObjectOutputStream(b);
        o.writeObject(pendingBatchSizeBytes);
        o.flush();
        byte[] sizes = b.toByteArray();
        b.close();
        o.close();
            
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
                
        out.writeInt(envs.length);
        out.write(envs);

        out.flush();
        bos.flush();
        
        out.writeInt(sizes.length);
        out.write(sizes);

        out.flush();
        bos.flush();
      
        out.writeLong(PreferredMaxBytes);
        out.writeLong(MaxMessageCount);

        out.flush();
        bos.flush();
            
        out.close();
        bos.close();
        return bos.toByteArray();

    }
    
    public void deserialize(byte[] bytes) throws IOException, ClassNotFoundException {
                
        ByteArrayInputStream bis = new ByteArrayInputStream(bytes);
        DataInputStream in = new DataInputStream(bis);
                
        byte[] envs = new byte[0];
        int n = in.readInt();

        if (n > 0) {

            envs = new byte[n];
            in.read(envs);

        }
        
        byte[] sizes = new byte[0];
        n = in.readInt();

        if (n > 0) {

            sizes = new byte[n];
            in.read(sizes);

        }
        
        PreferredMaxBytes = in.readLong();
        MaxMessageCount = in.readLong();
        
        in.close();
        bis.close();
 
        ByteArrayInputStream b = new ByteArrayInputStream(envs);
        ObjectInput i = null;
            
        i = new ObjectInputStream(b);
        this.pendingBatches = (Map<String,List<byte[]>>) i.readObject();
        i.close();
        b.close();
        
        b = new ByteArrayInputStream(sizes);
            
        i = new ObjectInputStream(b);
        this.pendingBatchSizeBytes = (Map<String,Integer>) i.readObject();
        i.close();
        b.close();
    }
}

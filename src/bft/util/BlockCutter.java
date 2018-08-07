/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package bft.util;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.hyperledger.fabric.protos.common.Common;


/**
 *
 * @author joao
 */
public class BlockCutter {
    
    //These maps comprise the state of the blockcutter relevant to the state machine
    private Map<String, List<byte[]>> pendingBatches;       
    private Map<String, Long> PreferredMaxBytes;
    private Map<String, Long> MaxMessageCount;
    private Map<String, Integer> pendingBatchSizeBytes;
    
    private static Logger logger;

    private BlockCutter() { //used for state transfer
                
        pendingBatches = new TreeMap<>();
        pendingBatchSizeBytes = new TreeMap<>();       
        PreferredMaxBytes = new TreeMap<>();
        MaxMessageCount = new TreeMap<>();
        
        logger = LoggerFactory.getLogger(BlockCutter.class);
    }
    
    @Override
    public BlockCutter clone() {
        
        BlockCutter clone = new BlockCutter();
        
        Map<String, List<byte[]>> clonePendingBatches = new TreeMap<>();
        pendingBatches.forEach((channel, envelopes) ->{
        
            List<byte[]> clonedEnvs = new LinkedList<>();
            clonedEnvs.addAll(envelopes);
            
            clonePendingBatches.put(channel, clonedEnvs);
        });
        
        Map<String, Long> clonedPreferredMaxBytes = new TreeMap<>();
        clonedPreferredMaxBytes.putAll(PreferredMaxBytes);
        
        Map<String, Long> clonedMaxMessageCount = new TreeMap<>();
        clonedMaxMessageCount.putAll(MaxMessageCount);

        Map<String, Integer> clonedPendingBatchSizeBytes = new TreeMap<>();
        clonedPendingBatchSizeBytes.putAll(pendingBatchSizeBytes);
        
        clone.pendingBatches = clonePendingBatches;
        clone.PreferredMaxBytes = clonedPreferredMaxBytes;
        clone.MaxMessageCount = clonedMaxMessageCount;
        clone.pendingBatchSizeBytes = clonedPendingBatchSizeBytes;
        
        return clone;
    }
    
    public static BlockCutter getInstance() {
        
        return new BlockCutter();
    }
    
    /*public BlockCutter(byte [] bytes) {
        
        logger = LogFactory.getLog(BlockCutter.class);
        
        pendingBatches = new TreeMap<>();
        pendingBatchSizeBytes = new TreeMap<>();
        
        setBatchParms(bytes);
    }*/
    
    
    public List<byte[][]> ordered(String channel, byte [] env, boolean isolated) throws IOException {
       
        if (pendingBatches.get(channel) == null) {
            pendingBatches.put(channel, new LinkedList<>());
            pendingBatchSizeBytes.put(channel, 0);
        }
        
        LinkedList batches = new LinkedList<>();
        int messageSizeBytes = messageSizeBytes(env);
                
        if (isolated ||  messageSizeBytes > PreferredMaxBytes.get(channel)) {

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
        
        boolean messageWillOverflowBatchSizeBytes = pendingBatchSizeBytes.get(channel) + messageSizeBytes > PreferredMaxBytes.get(channel);
        
        if (messageWillOverflowBatchSizeBytes && pendingBatches.get(channel).size() > 0) {
            
            logger.debug("The current message, with " + messageSizeBytes + " bytes, will overflow the pending batch of " + pendingBatchSizeBytes + " bytes.");
            logger.debug("Pending batch would overflow if current message is added, cutting batch now.");
            
            batches.add(cut(channel));
        }
        
        logger.debug("Enqueuing message into batch");
	pendingBatches.get(channel).add(env);
	pendingBatchSizeBytes.put(channel, (pendingBatchSizeBytes.get(channel) + messageSizeBytes));
        
        if (pendingBatches.get(channel).size() >= MaxMessageCount.get(channel)) {
            
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
            
    private int messageSizeBytes(byte[] bytes) {
        
        try {
            
            Common.Envelope env = Common.Envelope.parseFrom(bytes);
            return env.getPayload().size() + env.getSignature().size();
            
        } catch (Exception e) {
            
            logger.warn("Invalid envelope, using native 'length' attribute");
            return bytes.length;
        }
    }
    
    public void setBatchParms(String channel, long preferredMaxBytes, long maxMessageCount) {

        PreferredMaxBytes.put(channel, preferredMaxBytes);
        MaxMessageCount.put(channel, maxMessageCount);

        logger.info("Updated PreferredMaxBytes: " + PreferredMaxBytes);
        logger.info("Updated MaxMessageCount: " + MaxMessageCount);

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
            
        //serialize max bytes
        b = new ByteArrayOutputStream();
        o = new ObjectOutputStream(b);
        o.writeObject(PreferredMaxBytes);
        o.flush();
        byte[] max = b.toByteArray();
        b.close();
        o.close();
        
        //serialize message count
        b = new ByteArrayOutputStream();
        o = new ObjectOutputStream(b);
        o.writeObject(MaxMessageCount);
        o.flush();
        byte[] count = b.toByteArray();
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

        out.writeInt(max.length);
        out.write(max);

        out.flush();
        bos.flush();
        
        out.writeInt(count.length);
        out.write(count);

        out.flush();
        bos.flush();
            
        out.close();
        bos.close();
        return bos.toByteArray();

    }
    
    public static BlockCutter deserialize(byte[] bytes) throws IOException, ClassNotFoundException {
                
        
        BlockCutter instance = getInstance();
        
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
        
        byte[] max = new byte[0];
        n = in.readInt();

        if (n > 0) {

            max = new byte[n];
            in.read(max);

        }
        
        byte[] count = new byte[0];
        n = in.readInt();

        if (n > 0) {

            count = new byte[n];
            in.read(count);

        }
        
        in.close();
        bis.close();
 
        ByteArrayInputStream b = new ByteArrayInputStream(envs);
        ObjectInput i = null;
            
        i = new ObjectInputStream(b);
        instance.pendingBatches = (Map<String,List<byte[]>>) i.readObject();
        i.close();
        b.close();
        
        b = new ByteArrayInputStream(sizes);
        i = new ObjectInputStream(b);
        instance.pendingBatchSizeBytes = (Map<String,Integer>) i.readObject();
        i.close();
        b.close();
        
        b = new ByteArrayInputStream(max);
        i = new ObjectInputStream(b);
        instance.PreferredMaxBytes = (Map<String,Long>) i.readObject();
        i.close();
        b.close();
        
        b = new ByteArrayInputStream(count);
        i = new ObjectInputStream(b);
        instance.MaxMessageCount = (Map<String,Long>) i.readObject();
        i.close();
        b.close();
        
        return instance;
    }
}

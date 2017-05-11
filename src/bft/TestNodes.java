/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package bft;

import bftsmart.communication.client.ReplyListener;
import bftsmart.tom.AsynchServiceProxy;
import bftsmart.tom.RequestContext;
import bftsmart.tom.core.messages.TOMMessage;
import bftsmart.tom.core.messages.TOMMessageType;
import com.google.protobuf.ByteString;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutput;
import java.io.ObjectOutputStream;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import org.hyperledger.fabric.protos.common.Common;

/**
 *
 * @author joao
 */
public class TestNodes {
    
    
    public static void main(String[] args) throws Exception{

        if(args.length < 6) {
            System.out.println("Use: java TestNodes <init ID> <num clients> <delay> <envelope payload size> <add signature?> <block batch>");
            System.exit(-1);
        }      
        
        int initID = Integer.parseInt(args[0]);
        int clients = Integer.parseInt(args[1]);
        int delay = Integer.parseInt(args[2]);
        int batch = Integer.parseInt(args[5]);
        
        AsynchServiceProxy proxy = new AsynchServiceProxy(initID, BFTNode.BFTSMART_CONFIG_FOLDER);
        proxy.getCommunicationSystem().setReplyReceiver((TOMMessage tomm) -> {
                // do nothing
            });
        
        
        int reqId = proxy.invokeAsynchRequest(serializeBatchParams(batch), null, TOMMessageType.ORDERED_REQUEST);
        proxy.cleanAsynchRequest(reqId);
        reqId = proxy.invokeAsynchRequest(createGenesisBlock().toByteArray(), null, TOMMessageType.ORDERED_REQUEST);
        proxy.cleanAsynchRequest(reqId);
            
        Random rand = new Random(System.nanoTime());
        byte[] payload = new byte[Integer.parseInt(args[3])];
        
        rand.nextBytes(payload);
        
        byte[] signature;
        
        if (Boolean.parseBoolean(args[4])) {
            
            signature = new byte[72];
            rand.nextBytes(signature);
            
        } else {
            signature = new byte[0];
        }
        
        Common.Envelope.Builder builder = Common.Envelope.newBuilder();
        
        builder.setPayload(ByteString.copyFrom(payload));
        builder.setSignature(ByteString.copyFrom(signature));
        
        Common.Envelope env = builder.build();
        
        ExecutorService executor = Executors.newCachedThreadPool();
        

        for (int i = 0; i < clients; i++) {
        
            executor.execute(new ProxyThread(i + initID + 1, env.toByteArray(), delay));
        
        }
    }
    
    public static byte[] serializeBatchParams(int batch) throws IOException {

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ObjectOutput out = new ObjectOutputStream(bos);
        out.writeLong(524288);
        out.writeLong(batch);
        out.flush();
        bos.flush();
        out.close();
        bos.close();
        return bos.toByteArray();
    }
    
    public static Common.Block createGenesisBlock() throws NoSuchAlgorithmException, NoSuchProviderException {
        
        //initialize
        Common.BlockHeader.Builder blockHeaderBuilder = Common.BlockHeader.newBuilder();
        Common.BlockData.Builder blockDataBuilder = Common.BlockData.newBuilder();
        Common.BlockMetadata.Builder blockMetadataBuilder = Common.BlockMetadata.newBuilder();
        Common.Block.Builder blockBuilder = Common.Block.newBuilder();
                
        //create header
        blockHeaderBuilder.setNumber(0);
        blockHeaderBuilder.setPreviousHash(ByteString.EMPTY);
        blockHeaderBuilder.setDataHash(ByteString.EMPTY);
        
        //create metadata
        int numIndexes = Common.BlockMetadataIndex.values().length;
        for (int i = 0; i < numIndexes; i++) blockMetadataBuilder.addMetadata(ByteString.EMPTY);

        //crete block
        blockBuilder.setHeader(blockHeaderBuilder.build());
        blockBuilder.setMetadata(blockMetadataBuilder.build());
        blockBuilder.setData(blockDataBuilder.build());
        
        return blockBuilder.build();
    }
    
    private static class ProxyThread implements Runnable {
        
        int id;
        byte[] env;
        int delay;
        AsynchServiceProxy proxy;
        
        private int lastSeqNumber = -1;
        private final Lock inputLock;
        private final Condition windowAvailable;
        private boolean windowFull = false;
        
        public ProxyThread (int id, byte[] env, int delay) {
            this.id = id;
            this.env = env;
            this.delay = delay;
            this.proxy = new AsynchServiceProxy(this.id, BFTNode.BFTSMART_CONFIG_FOLDER);
            
            this.inputLock = new ReentrantLock();
            this.windowAvailable = inputLock.newCondition();
            
            /*this.proxy.getCommunicationSystem().setReplyReceiver((TOMMessage tomm) -> {
                //do nothing
            });*/

        }

        @Override
        public void run() {
                

                while (true) {
                
                                        
                    if (((this.lastSeqNumber+1) % BFTNode.REQUEST_WINDOW) == 0 && this.lastSeqNumber > 0) windowFull = true;
                    
                    this.lastSeqNumber = proxy.invokeAsynchRequest(this.env, (RequestContext rc, TOMMessage tomm) -> {
                        
                        System.out.println("Client " + id + " received reply from replicas");
                        
                        this.inputLock.lock();
                        
                        if (rc.getReqId() == lastSeqNumber && windowFull) {
                            
                            System.out.println("Window of client " + id + " is available");
                            windowFull = false;
                            this.windowAvailable.signalAll();
                        }
                        this.inputLock.unlock();
                            
                    }, TOMMessageType.ORDERED_REQUEST);
                    
                    
                    this.inputLock.lock();
                    
                    if (windowFull) {
                        
                        System.out.println("Window of client " + id + " is full");
                        this.windowAvailable.awaitUninterruptibly();
                        
                    }
                    
                    this.inputLock.unlock();
                    
                    proxy.cleanAsynchRequest(this.lastSeqNumber);
                    
                    try {
                        if (this.delay > 0) Thread.sleep(this.delay);
                    } catch (InterruptedException ex) {
                        ex.printStackTrace();
                    }
                }
            }
        
    }
}

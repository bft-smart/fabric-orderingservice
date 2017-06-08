/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package bft;

import bftsmart.tom.AsynchServiceProxy;
import bftsmart.tom.RequestContext;
import bftsmart.tom.core.messages.TOMMessage;
import bftsmart.tom.core.messages.TOMMessageType;
import bftsmart.tom.util.Storage;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutput;
import java.io.ObjectOutputStream;
import java.nio.ByteBuffer;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.util.List;
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
                    
        ExecutorService executor = Executors.newCachedThreadPool(); 
        
        for (int i = 0; i < clients; i++) {
        
            executor.execute(new ProxyThread(i + initID, Integer.parseInt(args[3]),Boolean.parseBoolean(args[4]), delay, (i == 0 ? proxy : null)));
        
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
        int payloadSize;
        boolean addSig;
        int delay;
        AsynchServiceProxy proxy;
        BlockThread thread;
        
        private int lastSeqNumber = -1;
        private final Lock inputLock;
        private final Condition windowAvailable;
        private boolean windowFull = false;
        private Random rand;
        private Storage latency = null;
        private ProxyReplyListener listener;
         
        public ProxyThread (int id, int payloadSize, boolean addSig, int delay, AsynchServiceProxy proxy) {
            this.id = id;
            this.payloadSize = payloadSize;
            this.addSig = addSig;
            this.delay = delay;
            
            this.inputLock = new ReentrantLock();
            this.windowAvailable = inputLock.newCondition();
            
            this.proxy = (proxy != null ? proxy : new AsynchServiceProxy(this.id, BFTNode.BFTSMART_CONFIG_FOLDER));
            this.listener = new ProxyReplyListener(this.proxy.getViewManager(), (RequestContext rc, TOMMessage tomm) -> {
                        
                        //System.out.println("Client " + id + " received reply from replicas");
                        
                        this.inputLock.lock();
                        
                        if (rc.getReqId() == this.lastSeqNumber && this.windowFull) {
                            
                            System.out.println("Window of client " + id + " is available");
                            this.windowFull = false;
                            this.windowAvailable.signalAll();
                        }
                        this.inputLock.unlock();
                                                
                    });
            this.proxy.getCommunicationSystem().setReplyReceiver(this.listener);
            
            
            rand = new Random(System.nanoTime());
            
            
            this.thread = new BlockThread(this.id, this.listener);
            this.thread.start();
            
            /*this.proxy.getCommunicationSystem().setReplyReceiver((TOMMessage tomm) -> {
                //do nothing
            });*/

        }

        @Override
        public void run() {
                

                while (true) {
                
                                        
                    if (((this.lastSeqNumber+1) % BFTNode.REQUEST_WINDOW) == 0 && this.lastSeqNumber > 0) windowFull = true;
                    
                    int size = Math.max(Integer.BYTES + Long.BYTES, this.payloadSize);                    

                    ByteBuffer buff = ByteBuffer.allocate(size);
                    
                    buff.putInt(this.id);
                    buff.putLong(System.nanoTime());
                    
                    while (buff.hasRemaining()) {
                        
                        buff.put((byte) this.rand.nextInt());
                    }
                    
                    byte[] signature;

                    if (this.addSig) {

                        signature = new byte[72];
                        rand.nextBytes(signature);

                    } else {
                        signature = new byte[0];
                    }

                    Common.Envelope.Builder builder = Common.Envelope.newBuilder();

                    builder.setPayload(ByteString.copyFrom(buff.array()));
                    builder.setSignature(ByteString.copyFrom(signature));

                    Common.Envelope env = builder.build();
            
                    this.lastSeqNumber = proxy.invokeAsynchRequest(env.toByteArray(), null, TOMMessageType.ORDERED_REQUEST);
                    
                    
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
    
    static class BlockThread extends Thread {

        private int id;
        private Storage latency = null;
        private ProxyReplyListener listener;

        public BlockThread(int id, ProxyReplyListener listener) {

            this.id = id;
            this.latency = new Storage(100000);
            this.listener = listener;

        }

        public void run() {

            while (true) {

                Common.Block block = this.listener.getNext();

                List<ByteString> data = block.getData().getDataList();

                for (ByteString env : data) {

                    try {

                        ByteBuffer payload = ByteBuffer.wrap(Common.Envelope.parseFrom(env).getPayload().toByteArray());

                        if (this.id == payload.getInt()) {

                            long time = payload.getLong();

                            latency.store(System.nanoTime() - time);

                            if (latency.getCount() == 100000) {

                                System.out.println("[latency] " + this.id + " // Average = " + this.latency.getAverage(false) / 1000 + " (+/- "+ this.latency.getDP(false) +") us ");
                                System.out.println("[latency] " + this.id + " // Median  = " + this.latency.getPercentile(0.5) / 1000 + " us ");
                                System.out.println("[latency] " + this.id + " // 90th p  = " + this.latency.getPercentile(0.9) / 1000 + " us ");
                                System.out.println("[latency] " + this.id + " // 95th p  = " + this.latency.getPercentile(0.95) / 1000 + " us ");
                                System.out.println("[latency] " + this.id + " // Max     = " + this.latency.getMax(false) / 1000 + " us ");

                                latency.reset();
                            }
                        }

                    } catch (Exception ex) {
                        ex.printStackTrace();
                    }
                }
            }
        }

    }
}

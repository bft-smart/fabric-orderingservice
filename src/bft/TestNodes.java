/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package bft;

import bftsmart.tom.AsynchServiceProxy;
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
import org.hyperledger.fabric.protos.common.Common;

/**
 *
 * @author joao
 */
public class TestNodes {
    
    
    public static void main(String[] args) throws Exception{

        if(args.length < 4) {
            System.out.println("Use: java TestNodes <init ID> <num clients> <envelope payload size> <add signature?>");
            System.exit(-1);
        }      
        
        int initID = Integer.parseInt(args[0]);
        int clients = Integer.parseInt(args[1]);
        
        
        AsynchServiceProxy proxy = new AsynchServiceProxy(initID, BFTNode.BFTSMART_CONFIG_FOLDER);
        proxy.getCommunicationSystem().setReplyReceiver((TOMMessage tomm) -> {
                proxy.cleanAsynchRequest(tomm.getSequence());
            });
        
        
        proxy.invokeAsynchRequest(serializeBatchParams(), null, TOMMessageType.ORDERED_REQUEST);
        proxy.invokeAsynchRequest(createGenesisBlock().toByteArray(), null, TOMMessageType.ORDERED_REQUEST);
            
        Random rand = new Random(System.nanoTime());
        byte[] payload = new byte[Integer.parseInt(args[2])];
        
        rand.nextBytes(payload);
        
        byte[] signature;
        
        if (Boolean.parseBoolean(args[3])) {
            
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
        
            executor.execute(new ProxyThread(i + initID + 1, env.toByteArray()));
        
        }
    }
    
    private static byte[] serializeBatchParams() throws IOException {

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ObjectOutput out = new ObjectOutputStream(bos);
        out.writeLong(524288);
        out.writeLong(10);
        out.flush();
        bos.flush();
        out.close();
        bos.close();
        return bos.toByteArray();
    }
    
    private static Common.Block createGenesisBlock() throws NoSuchAlgorithmException, NoSuchProviderException {
        
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
        AsynchServiceProxy proxy;

        
        public ProxyThread (int id, byte[] env) {
            this.id = id;
            this.env = env;
            this.proxy = new AsynchServiceProxy(this.id, BFTNode.BFTSMART_CONFIG_FOLDER);
            
            this.proxy.getCommunicationSystem().setReplyReceiver((TOMMessage tomm) -> {
                this.proxy.cleanAsynchRequest(tomm.getSequence());
            });

        }

        @Override
        public void run() {
                

                while (true) {
                
                    proxy.invokeAsynchRequest(this.env, null, TOMMessageType.ORDERED_REQUEST);
                }
            }
        
    }
}

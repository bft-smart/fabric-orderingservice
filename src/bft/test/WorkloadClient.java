/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package bft.test;

import bft.BFTNode;
import bft.util.BFTCommon;
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
import java.util.logging.Level;
import java.util.logging.Logger;
import org.hyperledger.fabric.protos.common.Common;

/**
 *
 * @author joao
 */
public class WorkloadClient {
    
    
    public static void main(String[] args) throws Exception{

        if(args.length < 4) {
            System.out.println("Use: java WorkloadClient <frontend ID> <channel ID> <num clients> <envelope payload size>");
            System.exit(-1);
        }      
        
        int frontendID = Integer.parseInt(args[0]);
        String channelID = args[1];
        int clients = Integer.parseInt(args[2]);
        
        
        AsynchServiceProxy proxy = new AsynchServiceProxy(frontendID, BFTNode.DEFAULT_CONFIG_FOLDER);
        proxy.getCommunicationSystem().setReplyReceiver((TOMMessage tomm) -> {
                // do nothing
            });
        
        
        // request latest reply sequence from the ordering nodes
        int reqId = proxy.invokeAsynchRequest(BFTCommon.assembleSignedRequest(proxy.getViewManager().getStaticConf().getRSAPrivateKey(), "SEQUENCE", "", new byte[]{}), null, TOMMessageType.ORDERED_REQUEST);
        proxy.cleanAsynchRequest(reqId);
            
        Random rand = new Random(System.nanoTime());
        byte[] payload = new byte[Integer.parseInt(args[3])];
        
        rand.nextBytes(payload);
        
        Common.Envelope.Builder builder = Common.Envelope.newBuilder();
        
        builder.setPayload(ByteString.copyFrom(payload));
        builder.setSignature(ByteString.copyFrom(new byte[0]));
        
        Common.Envelope env = builder.build();
        
        ExecutorService executor = Executors.newCachedThreadPool();
        

        for (int i = 0; i < clients; i++) {
        
            executor.execute(new ProxyThread(i + frontendID + 1, channelID, env.toByteArray()));
        
        }
    }
    
    private static class ProxyThread implements Runnable {
        
        int id;
        String channelID;
        byte[] env;
        AsynchServiceProxy proxy;

        
        public ProxyThread (int id, String channelID, byte[] env) {
            this.id = id;
            this.channelID = channelID;
            this.env = env;
            this.proxy = new AsynchServiceProxy(this.id, BFTNode.DEFAULT_CONFIG_FOLDER);
            
            this.proxy.getCommunicationSystem().setReplyReceiver((TOMMessage tomm) -> {
                //do nothing
            });

        }

        @Override
        public void run() {
                

                while (true) {
                
                    try {
                        
                        int reqId = proxy.invokeAsynchRequest(BFTCommon.serializeRequest("REGULAR", this.channelID, this.env), null, TOMMessageType.ORDERED_REQUEST);
                        proxy.cleanAsynchRequest(reqId);
                        
                    } catch (IOException ex) {
                        
                        ex.printStackTrace();
                    }
                }
            }
        
    }
}

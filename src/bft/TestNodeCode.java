/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package bft;

import bftsmart.tom.MessageContext;
import bftsmart.tom.core.messages.TOMMessageType;
import java.util.Arrays;
import java.util.Random;

/**
 *
 * @author joao
 */
public class TestNodeCode {
    
    private static BFTNode node;
    private static Random rand;
    
    public static void main(String[] args) throws Exception{

        if(args.length < 5) {
            System.out.println("Use: java BFTNode <thread pool size> <certificate key file> <private key file> <batch size> <env size>");
            System.exit(-1);
        }
        
        System.out.print("Launching node...");
        
        node = new BFTNode(0, Integer.parseInt(args[0]), args[1], args[2], new int[] {1001});
        
        int batchSize = Integer.parseInt(args[3]);
        int envSize =Integer.parseInt(args[4]);
        rand = new Random(System.nanoTime());
        
        //Generate pool of batches
        System.out.print("Generating " + TestSignatures.NUM_BATCHES + " batches with " + batchSize + " envelopes each... ");
        byte[][][] batches = new byte[TestSignatures.NUM_BATCHES][batchSize][];
        for (int i = 0; i < TestSignatures.NUM_BATCHES; i++) {

            for (int j = 0; j < batchSize; j++) {

                batches[i][j] = new byte[envSize];

                rand.nextBytes(batches[i][j]);


            }
        }
        
        System.out.println(" done!");
        

        MessageContext msgCtx = new MessageContext(1001, -1, TOMMessageType.ORDERED_REQUEST, -1, 0, -1, -1, null, -1, rand.nextInt(10), rand.nextLong(), -1, -1, -1, null, null, false);

        node.executeSingle(TestNodes.serializeBatchParams(), msgCtx);
        
        msgCtx = new MessageContext(1001, -1, TOMMessageType.ORDERED_REQUEST, -1, 1, -1, -1, null, -1, rand.nextInt(10), rand.nextLong(), -1, -1, -1, null, null, false);
        
        node.executeSingle(TestNodes.createGenesisBlock().toByteArray(), msgCtx);

        while (true) {
            
            msgCtx = new MessageContext(-1, -1, TOMMessageType.ORDERED_REQUEST, -1, -1, -1, -1, null, -1, rand.nextInt(10), rand.nextLong(), -1, -1, -1, null, null, false);
            
            byte[][] envs = batches[rand.nextInt(batches.length)];
            
            node.executeSingle(envs[rand.nextInt(envs.length)], msgCtx);
        }
    }
}

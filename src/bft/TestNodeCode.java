/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package bft;

import bftsmart.tom.MessageContext;
import bftsmart.tom.core.messages.TOMMessage;
import bftsmart.tom.core.messages.TOMMessageType;
import bftsmart.tom.leaderchange.CertifiedDecision;
import java.util.LinkedList;
import java.util.Random;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 *
 * @author joao
 */
public class TestNodeCode {
    
    private static BFTNode node;
    private static Random rand;
    private static WorkerThread worker;
    
    public static void main(String[] args) throws Exception{

        if(args.length < 6) {
            System.out.println("Use: java BFTNode <thread pool size> <certificate key file> <private key file> <batch size> <env size> <delay>");
            System.exit(-1);
        }
        
        System.out.print("Launching node...");
        
        node = new BFTNode(0, Integer.parseInt(args[0]), args[1], args[2], new int[] {1001});
        worker = new WorkerThread();
        worker.start();
        
        int batchSize = Integer.parseInt(args[3]);
        int envSize =Integer.parseInt(args[4]);
        int delay =Integer.parseInt(args[5]);
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
            
            /*byte[][] envs = batches[rand.nextInt(batches.length)];

            msgCtx = new MessageContext(-1, -1, TOMMessageType.ORDERED_REQUEST, -1, -1, -1, -1, null, -1, rand.nextInt(10), rand.nextLong(), -1, -1, -1, null, null, false);
                        
            node.executeSingle(envs[rand.nextInt(envs.length)], msgCtx);*/
            
            int consensusBatch = rand.nextInt(49) + 1;
            int proposeBatch = rand.nextInt(399) + 1;
            
            int[] cons = new int[consensusBatch];
            int[] regencies = new int[consensusBatch];
            int[] leaders = new int[consensusBatch];
            CertifiedDecision[] decisions = new CertifiedDecision[consensusBatch];
            TOMMessage[][] requests = new TOMMessage[consensusBatch][proposeBatch];
            
            for (int i = 0; i < consensusBatch; i++) {
                
                cons[i] = i;
                regencies[i] = 0;
                leaders[i] = 0;
                decisions[i] = new CertifiedDecision();
                
                for (int j = 0; j < proposeBatch; j++) {
                    
                    byte[][] envs = batches[rand.nextInt(batches.length)];
                    
                    TOMMessage tomm = new TOMMessage(-1, -1, -1, envs[rand.nextInt(envs.length)], 0);
                    tomm.numOfNonces = rand.nextInt(10);
                    tomm.seed = rand.nextLong();
            
                    requests[i][j] = tomm;
                }

            }
            
            //node.replica.receiveMessages(cons, regencies, leaders, decisions, requests);
            worker.input(cons, regencies, leaders, decisions, requests);
            
            Thread.sleep(delay);
        }
    }
    
    public static class TestTuple {
        
        public int consId[];
        public int regencies[];
        public int leaders[];
        public CertifiedDecision[] cDecs;
        public TOMMessage[][] requests;
        
        public TestTuple(int consId[], int regencies[], int leaders[], CertifiedDecision[] cDecs, TOMMessage[][] requests) {
            
            this.consId = consId;
            this.regencies = regencies;
            this.leaders = leaders;
            this.cDecs = cDecs;
            this.requests = requests;
            
        }
    }
    
    public static class WorkerThread extends Thread {
        
        private LinkedBlockingQueue<TestTuple>  input;
        
        private final Lock inputLock;
        private final Condition notEmptyInput;
        
        public WorkerThread() {
            
            this.input = new LinkedBlockingQueue<>();
            
            this.inputLock = new ReentrantLock();
            this.notEmptyInput = inputLock.newCondition();
        }
        
        
        public void input(int consId[], int regencies[], int leaders[], CertifiedDecision[] cDecs, TOMMessage[][] requests) throws InterruptedException {
            
            this.inputLock.lock();
            
            this.input.put(new TestTuple(consId, regencies, leaders, cDecs, requests));
            
            this.notEmptyInput.signalAll();
            this.inputLock.unlock();
        }
        
        public void run() {
            
            while (true) {
                
                LinkedList<TestTuple> list = new LinkedList<>();

                this.inputLock.lock();

                if(this.input.isEmpty()) {
                    this.notEmptyInput.awaitUninterruptibly();

                }
                this.input.drainTo(list);
                this.inputLock.unlock();

                for (TestTuple tuple : list) {

                    /*for (TOMMessage[] requests : tuple.requests) {
                        
                        for (TOMMessage request : requests) {

                            MessageContext msgCtx = new MessageContext(-1, 0, TOMMessageType.ORDERED_REQUEST, -1, -1, -1, -1, null, -1, request.numOfNonces, request.seed, -1, -1, -1, null, null, false);

                            node.executeSingle(request.getContent(), msgCtx);

                        }
                        
                    }*/

                                    
                    node.replica.receiveMessages(tuple.consId, tuple.regencies, tuple.leaders, tuple.cDecs, tuple.requests);
                }
            }
        }
    }
}

/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package bft.test;

import bft.util.BFTCommon;
import bft.util.ECDSAKeyLoader;
import bft.util.ProxyReplyListener;
import bftsmart.communication.client.ReplyListener;
import bftsmart.tom.AsynchServiceProxy;
import bftsmart.tom.RequestContext;
import bftsmart.tom.core.messages.TOMMessage;
import bftsmart.tom.core.messages.TOMMessageType;
import bftsmart.tom.util.KeyLoader;
import com.google.protobuf.ByteString;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Security;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.util.Arrays;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.LineIterator;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.hyperledger.fabric.protos.common.Common;
import org.hyperledger.fabric.protos.msp.Identities;
import org.hyperledger.fabric.sdk.exception.CryptoException;
import org.hyperledger.fabric.sdk.helper.Config;
import org.hyperledger.fabric.sdk.security.CryptoPrimitives;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author joao
 */
public class WorkloadClient {
    
    private static Logger logger;
    private static Logger loggerLatency;
    private static String configDir;
    private static CryptoPrimitives crypto;
    
    private static ProxyReplyListener proxy = null;
    
    //arguments
    private enum TxType {
        
        random, unsigned, signed
    }
    
    private static int frontendID;
    private static String channelID;
    private static int clients;
    private static int envSize;
    private static int txsPerWorker;
    private static TxType txType;
    private static int delay;
    
    // Own MSP artifacts
    private static String mspid = null;
    private static PrivateKey privKey = null;
    private static X509Certificate certificate = null;
    private static byte[] serializedCert = null;
    private static Identities.SerializedIdentity ident;
    
    //timestamps for latencies
    private static Map<Integer,Long> timestamps;

    
    public static void main(String[] args) throws Exception{

        if(args.length < 7) {
            System.out.println("Use: java bft.test.WorkloadClient <frontend ID> <channel ID> <num workers> <payload size> <txs per worker> <random|unsigned|signed> <delay (ms)>");
            System.exit(-1);
        }      
        
        configDir = BFTCommon.getBFTSMaRtConfigDir("WORKLOAD_CONFIG_DIR");
        
        if (System.getProperty("logback.configurationFile") == null)
            System.setProperty("logback.configurationFile", configDir + "logback.xml");
        
        Security.addProvider(new BouncyCastleProvider());
        
        logger = LoggerFactory.getLogger(WorkloadClient.class);
        loggerLatency = LoggerFactory.getLogger("latency");
                
        WorkloadClient.crypto = new CryptoPrimitives();
        WorkloadClient.crypto.init();
        BFTCommon.init(WorkloadClient.crypto);
                
        frontendID = Integer.parseInt(args[0]);
        channelID = args[1];
        clients = Integer.parseInt(args[2]);
        envSize = Integer.parseInt(args[3]);
        txsPerWorker = Integer.parseInt(args[4]);
        txType = TxType.valueOf(args[5]);
        delay = Integer.parseInt(args[6]);
        
        loadConfig();
        
       
        timestamps = new ConcurrentHashMap<>(); 
        
        if (proxy != null) {
            
            int reqId = proxy.invokeAsynchRequest(BFTCommon.assembleSignedRequest(proxy.getViewManager().getStaticConf().getPrivateKey(),
                    frontendID,  "SEQUENCE", "", new byte[]{}), null, TOMMessageType.ORDERED_REQUEST);
            proxy.cleanAsynchRequest(reqId);
            new ProxyThread().start();
        }
        
        ExecutorService executor = Executors.newCachedThreadPool();

        for (int i = 0; i < clients; i++) {
        
            executor.execute(new WorkerThread(i + frontendID + 1));
        
        }
        
    }
    
    private static void loadConfig() throws IOException, CertificateException, NoSuchAlgorithmException, InvalidKeySpecException {
        
        LineIterator it = FileUtils.lineIterator(new File(WorkloadClient.configDir + "node.config"), "UTF-8");
        
        Map<String,String> configs = new TreeMap<>();
        
        while (it.hasNext()) {
        
            String line = it.nextLine();
            
            if (!line.startsWith("#") && line.contains("=")) {
            
                String[] params = line.split("\\=");
                
                configs.put(params[0], params[1]);
            
            }
        }
        
        it.close();
        
        ECDSAKeyLoader loader = new ECDSAKeyLoader(frontendID, configDir, crypto.getProperties().getProperty(Config.SIGNATURE_ALGORITHM));
        
        mspid = configs.get("MSPID");
        privKey = loader.loadPrivateKey();
        certificate = loader.loadCertificate();
        serializedCert = BFTCommon.getSerializedCertificate(certificate);
        ident = BFTCommon.getSerializedIdentity(mspid, serializedCert);
        
        String[] IDs = configs.get("RECEIVERS").split("\\,");
        int[] recvs = Arrays.asList(IDs).stream().mapToInt(Integer::parseInt).toArray();
        
        for (int o : recvs) {
            if (o == frontendID) {
                
                proxy = new ProxyReplyListener(frontendID, configDir, loader, Security.getProvider("BC"));
                
                break;
            }
        }
    }
    
    private static class WorkerThread implements Runnable {
        
        int id;
        AsynchServiceProxy worker;
        long count;
        
        Random rand = new Random(System.nanoTime());
        
        public WorkerThread (int id) {
            this.id = id;
            
            this.worker = new AsynchServiceProxy(this.id, configDir, new KeyLoader() {
                @Override
                public PublicKey loadPublicKey(int i) throws IOException, NoSuchAlgorithmException, InvalidKeySpecException, CertificateException {
                    return null;
                }

                @Override
                public PublicKey loadPublicKey() throws IOException, NoSuchAlgorithmException, InvalidKeySpecException, CertificateException {
                    return null;
                }

                @Override
                public PrivateKey loadPrivateKey() throws IOException, NoSuchAlgorithmException, InvalidKeySpecException {
                    return null;
                }

                @Override
                public String getSignatureAlgorithm() {
                    return null;
                }
                
            }, Security.getProvider("BC"));            
            this.count = 0;            
        }

        @Override
        public void run() {
                

                while (this.count < txsPerWorker) {
                
                    try {
                        
                                                
                        int hash = 7;
                        hash = 31 * hash + this.id;
                        hash = 31 * hash + Long.hashCode(this.count);
                        
                        int size = Math.max(envSize, Integer.BYTES);
                        
                        ByteBuffer buffer = ByteBuffer.allocate(size);
                        
                        buffer.putInt(hash);
                        
                        while (buffer.remaining() > 0) {
                            buffer.put((byte) rand.nextInt());
                        }
                                       
                        
                        byte[] array = buffer.array();
                        
                        byte[] req = array;
                        
                        if (txType != TxType.random) {
                            
                            byte[] nonce = new byte[10];
                            rand.nextBytes(nonce);

                            Common.SignatureHeader sigHeader = BFTCommon.createSignatureHeader(ident.toByteArray(), nonce);

                            Common.Envelope.Builder env = BFTCommon.makeUnsignedEnvelope(ByteString.copyFrom(array),
                                    sigHeader.toByteString(), Common.HeaderType.MESSAGE, 0, channelID, 0,System.currentTimeMillis()).toBuilder();

                            /*Common.Payload.Builder payload = Common.Payload.parseFrom(env.getPayload()).toBuilder();
                            Common.Header.Builder header = payload.getHeader().toBuilder();

                            header.setSignatureHeader(sigHeader.toByteString());
                            payload.setHeader(header);
                            env.setPayload(payload.build().toByteString());*/

                            if (txType == TxType.signed) {

                                byte[] sig = crypto.sign(privKey, env.getPayload().toByteArray());

                                env.setSignature(ByteString.copyFrom(sig));                        
                            } else {

                                env.setSignature(ByteString.EMPTY);
                            }

                            req = env.build().toByteArray();
                        }
                        
                        timestamps.put(hash, System.currentTimeMillis());
                        
                        this.worker.invokeAsynchRequest(BFTCommon.serializeRequest(this.id, "REGULAR", channelID, req), new ReplyListener(){

                            private int replies = 0;

                            @Override
                            public void reset() {

                                replies = 0;
                            }

                            @Override
                            public void replyReceived(RequestContext rc, TOMMessage tomm) {

                                if (Arrays.equals(tomm.getContent(), "ACK".getBytes())) replies++;

                                double q = Math.ceil((double) (worker.getViewManager().getCurrentViewN() + worker.getViewManager().getCurrentViewF() + 1) / 2.0);

                                if (replies >= q) {
                                        worker.cleanAsynchRequest(rc.getOperationId());
                                }
                            }

                        }, TOMMessageType.ORDERED_REQUEST);
                        
                        //this.worker.cleanAsynchRequest(reqId);
                        
                        logger.debug("[{}]Sent envelope #{} with {} bytes", this.id, this.count, req.length);

                        this.count++;
                                                
                        if (delay > 0) {
                            
                            Thread.sleep(delay);
                        }
                        
                    } catch (CryptoException | IOException ex) {
                        
                        logger.error("Failed to send payload to nodes", ex);
                    } catch (InterruptedException ex) {
                        
                        logger.error("Interruption while sleeping", ex);
                    } 
                }
                logger.info("[{}] finished", this.id);
            }
        
    }
    
    private static class ProxyThread extends Thread {
        
        public void run() {
            
            while (true) {
                
                try {
                    Map.Entry<String,Common.Block> reply = proxy.getNext();

                    Common.BlockData data = reply.getValue().getData();

                    for (ByteString env : data.getDataList()) {
                        
                        byte[] req = env.toByteArray();
                        
                        if (txType != TxType.random) {
                        
                            Common.Payload payload = Common.Payload.parseFrom(Common.Envelope.parseFrom(env).getPayload());
                            req = payload.getData().toByteArray();
                        
                        }
                        
                        ByteBuffer buffer = ByteBuffer.wrap(req);
                        
                        int hash = buffer.getInt();
                        
                        Long ts = timestamps.remove(hash);
                       
                        if (ts != null)
                            loggerLatency.info("block#" + reply.getValue().getHeader().getNumber() + "\t" + (System.currentTimeMillis() - ts));
                        else logger.debug("Envelope with latency id " + hash + " at block#" + reply.getValue().getHeader().getNumber() + " not for me");
                    }
                    
                    logger.debug("Finished processing block#" + reply.getValue().getHeader().getNumber());
                } catch (Exception ex) {
                    
                    logger.error("Failed to fetch latency result", ex);
                }
            
            }
        }
    }
}

/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package bft;

import bft.util.ProxyReplyListener;
import bft.util.BFTCommon;
import bft.util.ECDSAKeyLoader;
import bftsmart.communication.client.ReplyListener;
import bftsmart.tom.AsynchServiceProxy;
import bftsmart.tom.RequestContext;
import bftsmart.tom.core.messages.TOMMessage;
import bftsmart.tom.core.messages.TOMMessageType;
import bftsmart.tom.util.KeyLoader;
import com.etsy.net.JUDS;
import com.etsy.net.UnixDomainSocket;
import com.etsy.net.UnixDomainSocketServer;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Security;
import java.security.cert.CertificateException;
import java.security.spec.InvalidKeySpecException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import org.apache.commons.io.FileUtils;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.hyperledger.fabric.protos.common.Common;
import org.hyperledger.fabric.protos.common.Configtx;
import org.hyperledger.fabric.protos.orderer.Configuration;
import org.hyperledger.fabric.sdk.exception.CryptoException;
import org.hyperledger.fabric.sdk.exception.InvalidArgumentException;
import org.hyperledger.fabric.sdk.helper.Config;
import org.hyperledger.fabric.sdk.security.CryptoPrimitives;

/**
 *
 * @author joao
 */
public class BFTProxy {

    private static UnixDomainSocketServer recvServer = null;
    private static ServerSocket sendServer = null;
    private static DataInputStream is;
    private static UnixDomainSocket recvSocket = null;
    private static Socket sendSocket = null;
    private static ExecutorService executor = null;
    private static Map<String, DataOutputStream> outputs;
    private static Map<String, Timer> timers;

    private static Map<String, Long> BatchTimeout;
    private static int frontendID;
    private static int nextID;

    private static ProxyReplyListener sysProxy;
        
    private static Logger logger;
    private static String configDir;
    private static CryptoPrimitives crypto;
    
    //measurements
    private static final int interval = 10000;
    private static long envelopeMeasurementStartTime = -1;
    private static final long blockMeasurementStartTime = -1;
    private static final long sigsMeasurementStartTime = -1;
    private static int countEnvelopes = 0;
    private static final int countBlocks = 0;
    private static final int countSigs = 0;

    public static void main(String args[]) throws ClassNotFoundException, IllegalAccessException, InstantiationException, CryptoException, InvalidArgumentException, NoSuchAlgorithmException, NoSuchProviderException, IOException {
            
        if(args.length < 3) {
            System.out.println("Use: java bft.BFTProxy <proxy id> <pool size> <send port>");
            System.exit(-1);
        }    
        
        int pool = Integer.parseInt(args[1]);
        int sendPort = Integer.parseInt(args[2]);
        
        Path proxy_ready = FileSystems.getDefault().getPath(System.getProperty("java.io.tmpdir"), "hlf-proxy-"+sendPort+".ready");
            
        Files.deleteIfExists(proxy_ready);
        
        configDir = BFTCommon.getBFTSMaRtConfigDir("FRONTEND_CONFIG_DIR");
        
        if (System.getProperty("logback.configurationFile") == null)
            System.setProperty("logback.configurationFile", configDir + "logback.xml");
        
        Security.addProvider(new BouncyCastleProvider());
                
        BFTProxy.logger = LoggerFactory.getLogger(BFTProxy.class);
        
        frontendID = Integer.parseInt(args[0]);
        nextID = frontendID + 1;
        
        crypto = new CryptoPrimitives();
        crypto.init();
        BFTCommon.init(crypto);
        
        ECDSAKeyLoader loader = new ECDSAKeyLoader(frontendID, configDir, crypto.getProperties().getProperty(Config.SIGNATURE_ALGORITHM));
        sysProxy = new ProxyReplyListener(frontendID, configDir, loader, Security.getProvider("BC"));        
        
        try {
            
            logger.info("Creating UNIX socket...");
            
            Path socket_file = FileSystems.getDefault().getPath(System.getProperty("java.io.tmpdir"), "hlf-pool-"+sendPort+".sock");
            
            Files.deleteIfExists(socket_file);
            
            recvServer = new  UnixDomainSocketServer(socket_file.toString(), JUDS.SOCK_STREAM, pool);
            sendServer = new ServerSocket(sendPort);

            FileUtils.touch(proxy_ready.toFile()); //Indicate the Go component that the java component is ready

            logger.info("Waiting for local connections and parameters...");
            
            recvSocket = recvServer.accept();
            is = new DataInputStream(recvSocket.getInputStream());

            executor = Executors.newFixedThreadPool(pool);

            for (int i = 0; i < pool; i++) {
                
                UnixDomainSocket socket = recvServer.accept();
                                
                executor.execute(new ReceiverThread(socket, nextID));
                
                nextID++;
            }
            
            outputs = new TreeMap<>();
            timers = new TreeMap<>();
            BatchTimeout = new TreeMap<>();
                        
            // request latest reply sequence from the ordering nodes
            sysProxy.invokeAsynchRequest(BFTCommon.assembleSignedRequest(sysProxy.getViewManager().getStaticConf().getPrivateKey(), frontendID, "SEQUENCE", "", new byte[]{}), null, TOMMessageType.ORDERED_REQUEST);
                                   
            new SenderThread().start();
                        
            logger.info("Java component is ready");

            while (true) { // wait for the creation of new channels
            
                sendSocket = sendServer.accept();
                                
                DataOutputStream os = new DataOutputStream(sendSocket.getOutputStream());

                String channel = readString(is);
                                
                //byte[] bytes = readBytes(is);
                
                outputs.put(channel, os);

                BatchTimeout.put(channel, readLong(is));

                logger.info("Read BatchTimeout: " + BatchTimeout.get(channel));
               
                //sysProxy.invokeAsynchRequest(BFTUtil.assembleSignedRequest(sysProxy.getViewManager().getStaticConf().getRSAPrivateKey(), "NEWCHANNEL", channel, bytes), null, TOMMessageType.ORDERED_REQUEST);
                
                Timer timer = new Timer();
                timer.schedule(new BatchTimeout(channel), (BatchTimeout.get(channel) / 1000000));
                timers.put(channel, timer);
                
                logger.info("Setting up system for new channel '" + channel + "'");

                nextID++;
            
            }

        } catch (IOException e) {
            
            logger.error("Failed to launch frontend", e);
            System.exit(1);
        }
    }
    
    private static String readString(DataInputStream is) throws IOException {
        
        byte[] bytes = readBytes(is);
        
        return new String(bytes);
        
    }
    
    private static boolean readBoolean(DataInputStream is) throws IOException {
        
        byte[] bytes = readBytes(is);
        
        return bytes[0] == 1;
        
    }

    private static byte[] readBytes(DataInputStream is) throws IOException {
        
        long size = readLong(is);

        logger.debug("Read number of bytes: " + size);

        byte[] bytes = new byte[(int) size];

        is.read(bytes);

        logger.debug("Read all bytes!");

        return bytes;

    }
    
    private static long readLong(DataInputStream is) throws IOException {
        byte[] buffer = new byte[8];

        is.read(buffer);

        //This is for little endian
        //long value = 0;
        //for (int i = 0; i < by.length; i++)
        //{
        //   value += ((long) by[i] & 0xffL) << (8 * i);
        //}
        //This is for big endian
        long value = 0;
        for (int i = 0; i < buffer.length; i++) {
            value = (value << 8) + (buffer[i] & 0xff);
        }

        return value;
    }

    private static long readInt() throws IOException {

        byte[] buffer = new byte[4];
        long value = 0;

        is.read(buffer);

        for (int i = 0; i < buffer.length; i++) {
            value = (value << 8) + (buffer[i] & 0xff);
        }

        return value;

    }
            
    private static synchronized void resetTimer(String channel) {
        
        if (timers.get(channel) != null) timers.get(channel).cancel();
        Timer timer = new Timer();
        timer.schedule(new BatchTimeout(channel), (BatchTimeout.get(channel) / 1000000));
        timers.put(channel, timer);
    }
    
            
    private static class ReceiverThread extends Thread {
        
        private int id;
        private UnixDomainSocket recv;
        private DataInputStream input;
        private AsynchServiceProxy out;
        
        public ReceiverThread(UnixDomainSocket recv, int id) throws IOException {
                        
            this.id = id;
            this.recv = recv;
            this.input = new DataInputStream(this.recv.getInputStream());
                        
            this.out = new AsynchServiceProxy(this.id, configDir, new KeyLoader() {
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
            
        }
        
        public void run() {
            
            String channelID;
            boolean isConfig;
            byte[] env; 
            while (true) {
                

                try {
                    
                    channelID = readString(this.input);
                    isConfig = readBoolean(this.input);
                    env = readBytes(this.input);
                                
                    resetTimer(channelID);

                    logger.debug("Received envelope" + Arrays.toString(env) + " for channel id " + channelID + (isConfig ? " (type config)" : " (type normal)"));

                    this.out.invokeAsynchRequest(BFTCommon.serializeRequest(this.id, (isConfig ? "CONFIG" : "REGULAR"), channelID, env), new ReplyListener(){

                        private int replies = 0;

                        @Override
                        public void reset() {

                            replies = 0;
                        }

                        @Override
                        public void replyReceived(RequestContext rc, TOMMessage tomm) {

                            if (Arrays.equals(tomm.getContent(), "ACK".getBytes())) replies++;

                            double q = Math.ceil((double) (out.getViewManager().getCurrentViewN() + out.getViewManager().getCurrentViewF() + 1) / 2.0);

                            if (replies >= q) {
                                    out.cleanAsynchRequest(rc.getOperationId());
                            }
                        }

                    }, TOMMessageType.ORDERED_REQUEST);
                
                
                } catch (IOException ex) {
                    logger.error("Error while receiving envelope from Go component", ex);
                    continue;
                }
                
                if (envelopeMeasurementStartTime == -1) {
                    envelopeMeasurementStartTime = System.currentTimeMillis();
                }

                countEnvelopes++;

                if (countEnvelopes % interval == 0) {

                    float tp = (float) (interval * 1000 / (float) (System.currentTimeMillis() - envelopeMeasurementStartTime));
                    logger.info("Throughput = " + tp + " envelopes/sec");
                    envelopeMeasurementStartTime = System.currentTimeMillis();

                }
        
                //byte[] reply = proxy.invokeOrdered(bytes);

                //by = new byte[8];
                //by[0] = (byte) ((byte) value>>56);
                //by[1] = (byte) ((byte) value>>48);
                //by[2] = (byte) ((byte) value>>40);
                //by[3] = (byte) ((byte) value>>32);
                //by[4] = (byte) ((byte) value>>24);
                //by[5] = (byte) ((byte) value>>16);
                //by[6] = (byte) ((byte) value>>8);
                //by[7] = (byte) ((byte) value>>0);

            }
        }
    }
    private static class SenderThread extends Thread {
        
        public void run() {

            while (true) {

                Map.Entry<String,Common.Block> reply = sysProxy.getNext();
                
                logger.info("Received block # " + reply.getValue().getHeader().getNumber());
                
                if (reply != null) {

                    try {
                        String[] params = reply.getKey().split("\\:");
                        
                        boolean isConfig = Boolean.parseBoolean(params[1]);
                        
                        if (isConfig) { //if config block, make sure to update the timeout in case it was changed
                            
                            Common.Envelope env = Common.Envelope.parseFrom(reply.getValue().getData().getData(0));
                            Common.Payload payload = Common.Payload.parseFrom(env.getPayload());
                            Common.ChannelHeader chanHeader = Common.ChannelHeader.parseFrom(payload.getHeader().getChannelHeader());

                            if (chanHeader.getType() == Common.HeaderType.CONFIG_VALUE) {
                            
                                Configtx.ConfigEnvelope confEnv = Configtx.ConfigEnvelope.parseFrom(payload.getData());
                                Map<String,Configtx.ConfigGroup> groups = confEnv.getConfig().getChannelGroup().getGroupsMap();
                                Configuration.BatchTimeout timeout = Configuration.BatchTimeout.parseFrom(groups.get("Orderer").getValuesMap().get("BatchTimeout").getValue());

                                Duration duration = BFTCommon.parseDuration(timeout.getTimeout());
                                BatchTimeout.put(params[0], duration.toNanos());
                            
                            }
                        }
                        
                        byte[] bytes = reply.getValue().toByteArray();
                        DataOutputStream os = outputs.get(params[0]);
                        
                        os.writeLong(bytes.length);
                        os.write(bytes);
                        
                        os.writeLong(1);
                        os.write(isConfig ? (byte) 1 : (byte) 0);                    
                        
                    } catch (IOException ex) {
                        logger.error("Error while sending block to Go component", ex);
                    }
                }
            }
        }

    }
    
    private static class BatchTimeout extends TimerTask {

        String channel;
        
        BatchTimeout(String channel) {
            
            this.channel = channel;
        }
        
        @Override
        public void run() {
            
            try {
                int reqId = sysProxy.invokeAsynchRequest(BFTCommon.assembleSignedRequest(sysProxy.getViewManager().getStaticConf().getPrivateKey(), frontendID, "TIMEOUT", this.channel,new byte[0]), null, TOMMessageType.ORDERED_REQUEST);
                sysProxy.cleanAsynchRequest(reqId);
                
                Timer timer = new Timer();
                timer.schedule(new BatchTimeout(this.channel), (BatchTimeout.get(channel) / 1000000));
                
                timers.put(this.channel, timer);
            } catch (IOException ex) {
                logger.error("Failed to send envelope to nodes", ex);
            }

        }
    
    }
}

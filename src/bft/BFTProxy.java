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
import com.etsy.net.JUDS;
import com.etsy.net.UnixDomainSocket;
import com.etsy.net.UnixDomainSocketServer;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.ObjectOutput;
import java.io.ObjectOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.hyperledger.fabric.protos.common.Common;

/**
 *
 * @author joao
 */
public class BFTProxy {

    /**
     * @param args the command line arguments
     */
    private static UnixDomainSocketServer recvServer = null;
    private static ServerSocket sendServer = null;
    private static DataInputStream is;
    private static DataOutputStream os;
    private static UnixDomainSocket recvSocket = null;
    private static Socket sendSocket = null;
    private static ReceiverThread[] recvPool = null;
    private static ExecutorService executor = null;
    private static Map<String, DataOutputStream> outputs;
    private static Map<String, Timer> timers;

    private static long PreferredMaxBytes = 0;
    private static long MaxMessageCount = 0;
    private static long BatchTimeout = 0;
    private static int initID;
    private static int nextID;

    private static ProxyReplyListener sysProxy;
        
    private static Log logger;
    
    //measurements
    private static int interval = 10000;
    private static long envelopeMeasurementStartTime = -1;
    private static long blockMeasurementStartTime = -1;
    private static long sigsMeasurementStartTime = -1;
    private static int countEnvelopes = 0;
    private static int countBlocks = 0;
    private static int countSigs = 0;

    public static void main(String args[]) {

        if(args.length < 3) {
            System.out.println("Use: java BFTNode <proxy id> <pool size> <send port>");
            System.exit(-1);
        }    
        
        BFTProxy.logger = LogFactory.getLog(BFTProxy.class);
        initID = Integer.parseInt(args[0]);
        nextID = initID + 1;
        
        sysProxy = new ProxyReplyListener(initID, BFTNode.DEFAULT_CONFIG_FOLDER);
        
        int pool = Integer.parseInt(args[1]);
        int sendPort = Integer.parseInt(args[2]);
        
        try {
            
            logger.info("Creating UNIX socket...");
            
            Path p = FileSystems.getDefault().getPath(System.getProperty("java.io.tmpdir"), "hlf-pool.sock");
            
            Files.deleteIfExists(p);
            
            recvServer = new  UnixDomainSocketServer(p.toString(), JUDS.SOCK_STREAM, pool);
            sendServer = new ServerSocket(sendPort);

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
                        
            PreferredMaxBytes = readInt();

            logger.info("Read PreferredMaxBytes: " + PreferredMaxBytes);

            MaxMessageCount = readInt();

            logger.info("Read MaxMessageCount: " + MaxMessageCount);
            
            BatchTimeout = readLong(is);

            logger.info("Read BatchTimeout: " + BatchTimeout);
            
            sysProxy.invokeAsynchRequest(serializeBatchParams(), null, TOMMessageType.ORDERED_REQUEST);
                        
            boolean isSysChannel = true;
           
            new SenderThread().start();

            while (true) { // wait for the creation of new channels
            
                sendSocket = sendServer.accept();
                                
                DataOutputStream os = new DataOutputStream(sendSocket.getOutputStream());

                String channel = readString(is);
                                
                byte[] bytes = readBytes(is);
                
                outputs.put(channel, os);
               
                sysProxy.invokeAsynchRequest(serializeRequest("NEWCHANNEL", channel, bytes), null, TOMMessageType.ORDERED_REQUEST);
                
                Timer timer = new Timer();
                timer.schedule(new BatchTimeout(channel), (BatchTimeout / 1000000));
                timers.put(channel, timer);

                isSysChannel = false;
                
                logger.info("Setting up system for new channel '" + channel + "'");

                nextID++;
            
            }

        } catch (IOException e) {
            e.printStackTrace();
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
    
    static private byte[] serializeBatchParams() throws IOException {

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ObjectOutput out = new ObjectOutputStream(bos);
        out.writeLong(PreferredMaxBytes);
        out.writeLong(MaxMessageCount);
        out.flush();
        bos.flush();
        out.close();
        bos.close();
        return bos.toByteArray();
    }
            
    private static synchronized void resetTimer(String channel) {
        
        if (timers.get(channel) != null) timers.get(channel).cancel();
        Timer timer = new Timer();
        timer.schedule(new BatchTimeout(channel), (BatchTimeout / 1000000));
        timers.put(channel, timer);
    }
    
    private static byte[] serializeRequest(String type, String channelID, byte[] payload) throws IOException {

        ByteArrayOutputStream bos = new ByteArrayOutputStream(type.length() + channelID.length() + payload.length);
        DataOutput out = new DataOutputStream(bos);

        out.writeUTF(type);
        out.writeUTF(channelID);
        out.writeInt(payload.length);
        out.write(payload);

        bos.flush();

        bos.close();

        return bos.toByteArray();

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
            this.out = new AsynchServiceProxy(this.id, BFTNode.DEFAULT_CONFIG_FOLDER);
            
        }
        
        public void run() {
            
            String id;
            boolean isConfig;
            byte[] env; 
            while (true) {
                

                try {
                    
                    id = readString(this.input);
                    isConfig = readBoolean(this.input);
                    env = readBytes(this.input);
                
                    //logger.debug("Received envelope at connection #" + this.id);
                
                    resetTimer(id);

                    //CommonProtos.Envelope env = CommonProtos.Envelope.parseFrom(bytes);
                    logger.debug("Received envelope" + Arrays.toString(env) + " for channel id " + id + (isConfig ? " (type config)" : " (type normal)"));

                    this.out.invokeAsynchRequest(serializeRequest((isConfig ? "CONFIG" : "REGULAR"), id, env), new bftsmart.communication.client.ReplyListener(){

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
                    Logger.getLogger(BFTProxy.class.getName()).log(Level.SEVERE, null, ex);
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
                
                if (reply != null) {

                    try {
                        String[] params = reply.getKey().split("\\:");
                        
                        byte[] bytes = reply.getValue().toByteArray();
                        DataOutputStream os = outputs.get(params[0]);
                        
                        os.writeLong(bytes.length);
                        os.write(bytes);
                        
                        os.writeLong(1);
                        os.write(Boolean.parseBoolean(params[1]) ? (byte) 1 : (byte) 0);                    
                        
                    } catch (IOException ex) {
                        ex.printStackTrace();
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
                int reqId = sysProxy.invokeAsynchRequest(serializeRequest("TIMEOUT", this.channel,new byte[0]), null, TOMMessageType.ORDERED_REQUEST);
                sysProxy.cleanAsynchRequest(reqId);
                
                Timer timer = new Timer();
                timer.schedule(new BatchTimeout(this.channel), (BatchTimeout / 1000000));
                
                timers.put(this.channel, timer);
            } catch (IOException ex) {
                ex.printStackTrace();
            }

        }
    
    }
}

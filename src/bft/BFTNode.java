/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package bft;

import bft.util.BFTCommon;
import bft.util.MSPManager;
import bft.util.BlockCutter;
import bft.util.ECDSAKeyLoader;
import bftsmart.reconfiguration.util.TOMConfiguration;
import bftsmart.tom.MessageContext;
import bftsmart.tom.ServiceReplica;
import bftsmart.tom.core.messages.TOMMessage;
import bftsmart.tom.server.defaultservices.DefaultRecoverable;
import bftsmart.tom.server.defaultservices.DefaultReplier;
import com.google.protobuf.ByteString;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectInputStream;
import java.io.ObjectOutput;
import java.io.ObjectOutputStream;
import java.nio.ByteBuffer;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.PrivateKey;
import java.security.Security;
import java.security.SignatureException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.LineIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.hyperledger.fabric.sdk.exception.CryptoException;
import org.hyperledger.fabric.sdk.exception.InvalidArgumentException;
import org.hyperledger.fabric.sdk.security.CryptoPrimitives;
import org.hyperledger.fabric.protos.common.Common;
import org.hyperledger.fabric.protos.common.Configtx;
import org.hyperledger.fabric.protos.msp.Identities;
import org.hyperledger.fabric.protos.orderer.Configuration;
import org.hyperledger.fabric.sdk.helper.Config;

/**
 *
 * @author joao
 */
public class BFTNode extends DefaultRecoverable {

    private static String configDir;

    private final int id;
    private final CryptoPrimitives crypto;
    private final Logger logger;
    private final Logger loggerThroughput;
    private final TOMConfiguration replicaConf;
    private ServiceReplica replica = null;

    // Own MSP artifacts
    private String mspid = null;
    private PrivateKey privKey = null;
    private X509Certificate certificate = null;
    private byte[] serializedCert = null;
    private Identities.SerializedIdentity ident;
    
    //envelope validation stuff
    private boolean bothSigs;
    private boolean envValidation;
    private boolean filterMalformed;
    private long timeWindow;

    //signature thread stuff
    private int parallelism;
    LinkedBlockingQueue<BlockWorkerThread> queue;
    private ExecutorService executor = null;
    private int blocksPerThread;
    private int sigIndex = 0;
    private BlockWorkerThread currentSST = null;

    //measurements
    private int interval;
    private long envelopeMeasurementStartTime = -1;
    private long blockMeasurementStartTime = -1;
    private int countEnvelopes = 0;
    private int countBlocks = 0;
    
    //used to avoid the block worker threads from accessing a null pointer in 'replica'
    private final Lock replicaLock;
    private final Condition replicaReady;

    //these attributes are the state of this replicated state machine
    private BlockCutter blockCutter;
    private int sequence = 0;
    private MSPManager mspManager = null;
    private Map<String,Common.BlockHeader> lastBlockHeaders;
    
    // these are treated as SMR state, but so far they are updated only at replica start up or upon system channel creation.
    private String sysChannel = "";
    private Common.Block sysGenesis;
    private Set<Integer> receivers;

    
    public BFTNode(int id) throws IOException, InvalidArgumentException, CryptoException, NoSuchAlgorithmException, NoSuchProviderException, InterruptedException, ClassNotFoundException, IllegalAccessException, InstantiationException, CertificateException, InvalidKeySpecException {

        this.replicaLock = new ReentrantLock();
        this.replicaReady = replicaLock.newCondition();

        this.id = id;
        
        this.crypto = new CryptoPrimitives();
        this.crypto.init();
        BFTCommon.init(crypto);
        
        ECDSAKeyLoader loader = new ECDSAKeyLoader(this.id, this.configDir, this.crypto.getProperties().getProperty(Config.SIGNATURE_ALGORITHM));
        this.replicaConf = new TOMConfiguration(this.id, this.configDir, loader, Security.getProvider("BC"));
        
        loadConfig();

        privKey = loader.loadPrivateKey();
        certificate = loader.loadCertificate();
        serializedCert = BFTCommon.getSerializedCertificate(certificate);
        ident = BFTCommon.getSerializedIdentity(mspid, serializedCert);        
        
        this.logger = LoggerFactory.getLogger(BFTNode.class);
        this.loggerThroughput = LoggerFactory.getLogger("throughput");

        this.queue = new LinkedBlockingQueue<>();
        this.executor = Executors.newWorkStealingPool(this.parallelism);

        for (int i = 0; i < parallelism; i++) {

            this.executor.execute(new BlockWorkerThread(this.queue));
        }
        
        this.sequence = 0;
        this.lastBlockHeaders = new TreeMap<>();

        //this.lastConfigs = new TreeMap<>();
        //this.chanConfigs = new TreeMap<>();

        this.blockCutter = BlockCutter.getInstance();
                
        logger.info("Starting up ordering node with system channel " + this.sysChannel);
        logger.info("This is the signature algorithm: " + this.crypto.getProperties().getProperty(Config.SIGNATURE_ALGORITHM));
        logger.info("This is the hash algorithm: " + this.crypto.getProperties().getProperty(Config.HASH_ALGORITHM));
        
        this.mspManager = MSPManager.getInstance(mspid, sysChannel);
                
        this.replica = new ServiceReplica(this.id, this.configDir, this, this, null,
                
            new DefaultReplier() {

                @Override
                public void manageReply(TOMMessage tomm, MessageContext mc) {

                    // send reply only if it is one of the clients from the connection pool or if it is the first message of the proxy
                    if (!receivers.contains(tomm.getSender()) || (receivers.contains(tomm.getSender()) && tomm.getSequence() == 0)) {

                        super.manageReply(tomm, mc);

                    }
                }

            }, loader, Security.getProvider("BC"));
                
        this.replicaLock.lock();
        this.replicaReady.signalAll();
        this.replicaLock.unlock();
        
    }
    
    private void loadConfig() throws IOException, CertificateException {
        
        LineIterator it = FileUtils.lineIterator(new File(this.configDir + "node.config"), "UTF-8");
        
        Map<String,String> configs = new TreeMap<>();
        
        while (it.hasNext()) {
        
            String line = it.nextLine();
            
            if (!line.startsWith("#") && line.contains("=")) {
            
                String[] params = line.split("\\=");
                
                configs.put(params[0], params[1]);
            
            }
        }
        
        it.close();
        
        mspid = configs.get("MSPID");
        parallelism = Integer.parseInt(configs.get("PARELLELISM"));
        blocksPerThread = Integer.parseInt(configs.get("BLOCKS_PER_THREAD"));
        bothSigs = Boolean.parseBoolean(configs.get("BOTH_SIGS"));
        envValidation = Boolean.parseBoolean(configs.get("ENV_VALIDATION"));
        interval = Integer.parseInt(configs.get("THROUGHPUT_INTERVAL"));
        filterMalformed = Boolean.parseBoolean(configs.get("FILTER_MALFORMED"));
        
        FileInputStream input = new FileInputStream(new File(configs.get("GENESIS")));
        sysGenesis = Common.Block.parseFrom(IOUtils.toByteArray(input));
        
        Common.Envelope env = Common.Envelope.parseFrom(sysGenesis.getData().getData(0));
        Common.Payload payload = Common.Payload.parseFrom(env.getPayload());
        Common.ChannelHeader chanHeader = Common.ChannelHeader.parseFrom(payload.getHeader().getChannelHeader());
        sysChannel = chanHeader.getChannelId();
        
        String window = configs.get("TIME_WINDOW");
        timeWindow = BFTCommon.parseDuration(window).toMillis();
        
        String[] IDs = configs.get("RECEIVERS").split("\\,");
        int[] recvs = Arrays.asList(IDs).stream().mapToInt(Integer::parseInt).toArray();
        
        this.receivers = new TreeSet<>();
        for (int o : recvs) {
            this.receivers.add(o);
        }
                
    }
    
    public static void main(String[] args) throws Exception {

        if (args.length < 1) {
            System.out.println("Use: java bft.BFTNode <processId>");
            System.exit(-1);
        }
        
        configDir = BFTCommon.getBFTSMaRtConfigDir("NODE_CONFIG_DIR");
        
        if (System.getProperty("logback.configurationFile") == null)
            System.setProperty("logback.configurationFile", configDir + "logback.xml");
                
        Security.addProvider(new BouncyCastleProvider());
        
        new BFTNode(Integer.parseInt(args[0]));
    }

    @Override
    public void installSnapshot(byte[] state) {
        
        try {
            
            ByteArrayInputStream bis = new ByteArrayInputStream(state);
            DataInputStream in = new DataInputStream(bis);
            
            sysChannel = in.readUTF();
            sequence = in.readInt();
            
            receivers = new TreeSet<Integer>();
            int n = in.readInt();
            
            for (int i = 0; i < n; i++) {
                
                receivers.add(new Integer(in.readInt()));
            }
            
            byte[] headers = new byte[0];
            n = in.readInt();
            
            if (n > 0) {
                
                headers = new byte[n];
                in.read(headers);
                
            }
            
            byte[] sysBytes = new byte[0];
            n = in.readInt();
            
            if (n > 0) {
                
                sysBytes = new byte[n];
                in.read(sysBytes);
                
            }
            
            n = in.readInt();
            
            if (n > 0) {
            
                byte[] b = new byte[n];
                in.read(b);
                            
                blockCutter = BlockCutter.deserialize(b);
            }
            
            n = in.readInt();
            
            if (n > 0) {
            
                byte[] b = new byte[n];
                in.read(b);
                            
                mspManager = MSPManager.deserialize(mspid, sysChannel, b);
            }
            
            //deserialize headers
            ByteArrayInputStream b = new ByteArrayInputStream(headers);
            ObjectInput i = new ObjectInputStream(b);
            
            this.lastBlockHeaders = (Map<String,Common.BlockHeader>) i.readObject();
            i.close();
            b.close();
                        
            //deserialize system genesis block
            b = new ByteArrayInputStream(sysBytes);
            i = new ObjectInputStream(b);
            this.sysGenesis = (Common.Block) i.readObject();
            
            i.close();
            b.close();
                        
        } catch (Exception ex) {
            
            logger.error("Failed to install snapshot", ex);
        }
    }

    @Override
    public byte[] getSnapshot() {
        
        try {
            
            //serialize block headers
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            ObjectOutput o = new ObjectOutputStream(b);   
            o.writeObject(lastBlockHeaders);
            o.flush();
            byte[] headers = b.toByteArray();
            b.close();
            o.close();
            
            //serialize system genesis block
            b = new ByteArrayOutputStream();
            o = new ObjectOutputStream(b);   
            o.writeObject(sysGenesis);
            o.flush();
            byte[] sysBytes = b.toByteArray();
            b.close();
            o.close();
            
            //serialize receivers
            Integer[] orderers;
            
            if (this.receivers != null) {
                orderers = new Integer[this.receivers.size()];
                this.receivers.toArray(orderers);
            } else {
                orderers = new Integer[0];
            }
                        
            //serialize block cutter
            byte[] blockcutter = (blockCutter != null ? blockCutter.serialize() : new byte[0]);
            
            //serialize MSP manager
            byte[] mspmanager = (mspManager != null ? mspManager.serialize() : new byte[0]);
            
            //concatenate bytes
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bos);
            
            out.writeUTF(sysChannel);
            out.writeInt(sequence);
            
            out.writeInt(orderers.length);
            
            out.flush();
            bos.flush();
            
            for (int i = 0; i < orderers.length; i++) {
                
                out.writeInt(orderers[i].intValue());
                
                out.flush();
                bos.flush();
                
            }
            
            out.writeInt(headers.length);
            if (headers.length > 0) out.write(headers);
            out.flush();
            bos.flush();
            
            out.writeInt(sysBytes.length);
            if (sysBytes.length > 0) out.write(sysBytes);
            out.flush();
            bos.flush();
            
            out.writeInt(blockcutter.length);
            if (blockcutter.length > 0) out.write(blockcutter);
            out.flush();
            bos.flush();
            
            out.writeInt(mspmanager.length);
            if (mspmanager.length > 0) out.write(mspmanager);
            out.flush();
            bos.flush();
            
            out.close();
            bos.close();
            
            return bos.toByteArray();
            
        } catch (IOException ex) {
            
            logger.error("Failed to fetch snapshot", ex);
            
        }
        return new byte[0];
    }

    @Override
    public byte[][] appExecuteBatch(byte[][] commands, MessageContext[] msgCtxs, boolean fromConsensus) {

        byte[][] replies = new byte[commands.length][];
        for (int i = 0; i < commands.length; i++) {
            if (msgCtxs != null && msgCtxs[i] != null) {

                replies[i] = executeSingle(commands[i], msgCtxs[i], fromConsensus);
                
            }
        }

        return replies;
    }
    
    private void waitforReplica() throws InterruptedException {
        
        while (replica == null) {
                            
            replicaLock.lock();
            replicaReady.await(1000, TimeUnit.MILLISECONDS);
            replicaLock.lock();
        }
    }
    
    private byte[] executeSingle(byte[] command, MessageContext msgCtx, boolean fromConsensus) {
            
        //make sure system channel is created, since we cannot do it at start up due to not having a timestamp yet
        //this is only done once
        if (mspManager.getChanConfig(this.sysChannel) == null) {
            
            MSPManager mspManager = this.mspManager.clone();
            BlockCutter blockCutter = this.blockCutter.clone();
            Map<String,Common.BlockHeader> lastBlockHeaders = new TreeMap<>();
            lastBlockHeaders.putAll(this.lastBlockHeaders);
                
            try {
            
                newChannel(mspManager, blockCutter, lastBlockHeaders, this.sysChannel, this.sysGenesis, msgCtx.getTimestamp());
                
            } catch (Exception ex) {
                
                //TODO: handle error
                logger.error("Failed to create new channel", ex);
                return new byte[0];
            }
            
            this.mspManager = mspManager;
            this.blockCutter = blockCutter;
            this.lastBlockHeaders = lastBlockHeaders;
        }
        
        BlockCutter blockCutter = this.blockCutter.clone();
        
        MSPManager mspManager = null;
        Map<String,Common.BlockHeader> lastBlockHeaders = new TreeMap<>();
        
        try {
            
            if (receivers.contains(msgCtx.getSender())) {

                BFTCommon.RequestTuple tuple = BFTCommon.deserializeSignedRequest(command);
                if (tuple.id != msgCtx.getSender() || !BFTCommon.verifyFrontendSignature(msgCtx.getSender(),
                        replicaConf.getPublicKey(msgCtx.getSender()), tuple)) return new byte[0];

                if (tuple.type.equals("GETSVIEW")) {

                    logger.info("A proxy is trying to fetch the most current view");
                    return new byte[0];
                }

                if (tuple.type.equals("SEQUENCE")) {

                    byte[][] reply = new byte[2][];
                    reply[0] = "SEQUENCE".getBytes();
                    reply[1] = ByteBuffer.allocate(4).putInt(this.sequence).array();

                    return BFTCommon.serializeContents(reply);
                }

                if (tuple.type.equals("TIMEOUT")) {


                    logger.info("Purging blockcutter for channel " + tuple.channelID);

                    byte[][] batch = blockCutter.cut(tuple.channelID);

                    if (batch.length > 0) {
                        
                        mspManager = this.mspManager.clone();
                        lastBlockHeaders.putAll(this.lastBlockHeaders);

                        processEnvelopes(batch, blockCutter, mspManager, lastBlockHeaders, msgCtx, fromConsensus, tuple.channelID, false);
                        
                        //if we are here, we can atomically update the replica state
                        this.mspManager = mspManager;
                        this.lastBlockHeaders = lastBlockHeaders;
                    } 

                    this.blockCutter = blockCutter;
                    
                    return new byte[0];

                }

                return new byte[0];
            }
        
            if (envelopeMeasurementStartTime == -1) {
                envelopeMeasurementStartTime = System.currentTimeMillis();
            }

            countEnvelopes++;

            if (countEnvelopes % interval == 0) {

                float tp = (float) (interval * 1000 / (float) (System.currentTimeMillis() - envelopeMeasurementStartTime));
                loggerThroughput.info("envelopes/second\t" + tp);
                envelopeMeasurementStartTime = System.currentTimeMillis();

            }

            logger.debug("Envelopes received: " + countEnvelopes);
        
            BFTCommon.RequestTuple tuple = BFTCommon.deserializeRequest(command);
            boolean isConfig = tuple.type.equals("CONFIG");

            logger.debug("Received envelope" + Arrays.toString(tuple.payload) + " for channel id " + tuple.channelID + (isConfig ? " (type config)" : " (type normal)"));

            try { //filter malformed envelopes, if option is enabled
                
                Common.Envelope.parseFrom(tuple.payload);
            
            } catch (Exception e) {
                
                if (filterMalformed) {
                    
                    logger.info("Discarding malformed envelope");
                    return new byte[0];
                }
            }
            
            List<byte[][]> batches = blockCutter.ordered(tuple.channelID, tuple.payload, isConfig);
            
            if (batches.size() > 0) {

                mspManager = this.mspManager.clone();
                lastBlockHeaders.putAll(this.lastBlockHeaders);

                for (int i = 0; i < batches.size(); i++) {
                    processEnvelopes(batches.get(i), blockCutter, mspManager, lastBlockHeaders, msgCtx, fromConsensus, tuple.channelID, isConfig);
                }

                //if we are here, we can atomically update the replica state
                this.mspManager = mspManager;            
                this.lastBlockHeaders = lastBlockHeaders;
            
            }
            
            this.blockCutter = blockCutter;

        } catch (Exception ex) {
            
            //TODO: handle error
            logger.error("Failed to process envelope", ex);
            return new byte[0];
            
        }
        
        return "ACK".getBytes();

    }
    
    private void newChannel(MSPManager mspManager, BlockCutter blockCutter, Map<String,Common.BlockHeader> lastBlockHeaders, String channelID, Common.Block genesis, long timestamp) throws BFTCommon.BFTException {
        
        if (channelID != null && genesis != null && lastBlockHeaders.get(channelID) == null) {
                    
            logger.info("Creating channel " + channelID);
            
            long preferredMaxBytes = -1;
            long maxMessageCount = -1;
        
            try{
                
                Configtx.ConfigEnvelope conf = BFTCommon.extractConfigEnvelope(genesis);
                
                Configuration.BatchSize batchSize = BFTCommon.extractBachSize(conf.getConfig());
                
                preferredMaxBytes = batchSize.getPreferredMaxBytes();
                maxMessageCount = batchSize.getMaxMessageCount();
            
                mspManager.newChannel(channelID, genesis.getHeader().getNumber(), conf, timestamp);
                
                
            } catch (BFTCommon.BFTException ex) {
            
                throw ex;

            } catch (Exception ex) {

                throw new BFTCommon.BFTException("Failed to update channel " + channelID + ": " + ex.getLocalizedMessage());
            }
            
            // if we are here, we will successfully atomically update the channel
            if (preferredMaxBytes > -1 && maxMessageCount > -1) {
                
                lastBlockHeaders.put(channelID, genesis.getHeader());
                blockCutter.setBatchParms(channelID, preferredMaxBytes , maxMessageCount);
            }
            
            logger.info("New channel ID: " + channelID);
            logger.info("Genesis header number: " + lastBlockHeaders.get(channelID).getNumber());
            //logger.info("Genesis header previous hash: " + Hex.encodeHexString(lastBlockHeaders.get(channelID).getPreviousHash().toByteArray()));
            logger.info("Genesis header data hash: " + Hex.encodeHexString(lastBlockHeaders.get(channelID).getDataHash().toByteArray()));
            //logger.info("Genesis header ASN1 encoding: " + Arrays.toString(BFTCommon.encodeBlockHeaderASN1(lastBlockHeaders.get(channelID))));
        }
    }
    
    private void updateChannel(MSPManager mspManager, BlockCutter blockCutter, String channel, long number, Configtx.ConfigEnvelope newConfEnv, Configtx.Config newConfig, long timestamp) throws BFTCommon.BFTException {
        
        long preferredMaxBytes = -1;
        long maxMessageCount = -1;
            
        try {
            
            logger.info("Updating state for channel " + channel);
            
            Map<String,Configtx.ConfigGroup> groups = newConfig.getChannelGroup().getGroupsMap();

            Configuration.BatchSize batchsize = Configuration.BatchSize.parseFrom(groups.get("Orderer").getValuesMap().get("BatchSize").getValue());

            preferredMaxBytes = batchsize.getPreferredMaxBytes();
            maxMessageCount = batchsize.getMaxMessageCount();
        
            mspManager.updateChannel(channel, number, newConfEnv, newConfig, timestamp);
            
            
        } catch (BFTCommon.BFTException ex) {
            
            throw ex;
            
        } catch (Exception ex) {
            
            throw new BFTCommon.BFTException("Failed to update channel " + channel + ": " + ex.getLocalizedMessage());
        }

        // if we are here, we will successfully atomically update the channel
        if (preferredMaxBytes > -1 && maxMessageCount > -1) {
            blockCutter.setBatchParms(channel, preferredMaxBytes , maxMessageCount);
        }

        logger.info("Latest block with config update for "+ channel +": #" + number);

    }

    private void processEnvelopes(byte[][] batch, BlockCutter blockCutter, MSPManager mspManager, Map<String,Common.BlockHeader> lastBlockHeaders, MessageContext msgCtx, boolean fromConsensus, String channel, boolean isConfig) throws BFTCommon.BFTException {
                
        Common.Block block = null;
                
        try {
            
            block = BFTCommon.createNextBlock(lastBlockHeaders.get(channel).getNumber() + 1, crypto.hash(BFTCommon.encodeBlockHeaderASN1(lastBlockHeaders.get(channel))), batch);
                        
            if (isConfig) {
                
                Common.Envelope env = Common.Envelope.parseFrom(block.getData().getData(0));
                Common.Payload payload = Common.Payload.parseFrom(env.getPayload());
                Configtx.ConfigUpdateEnvelope confEnv = Configtx.ConfigUpdateEnvelope.parseFrom(payload.getData());
                
                Configtx.ConfigUpdate confUpdate = Configtx.ConfigUpdate.parseFrom(confEnv.getConfigUpdate());
                boolean isConfigUpdate = confUpdate.getChannelId().equals(channel);
                
                if (isConfigUpdate) {
                    
                    mspManager.verifyPolicies(channel, false, confUpdate.getReadSet(), confUpdate.getWriteSet(), confEnv, msgCtx.getTimestamp());
                    
                    if (envValidation) mspManager.validateEnvelope(env, channel, msgCtx.getTimestamp(), timeWindow);
                        
                    logger.info("Reconfiguration envelope for channel "+ channel +" is valid, generating configuration with readset and writeset");

                    Configtx.Config newConfig = mspManager.generateNextConfig(channel, confUpdate.getReadSet(), confUpdate.getWriteSet());
                    Configtx.ConfigEnvelope newConfEnv = BFTCommon.makeConfigEnvelope(newConfig, env);
                                        
                    //The fabric codebase inserts the lastupdate structure into a signed envelope. I cannot do this here because the signatures
                    //are different for each replica, thus producing different blocks. Even if I modified the frontend to be aware of this corner
                    //case just like it is done for block signatures, envelopes are supposed to contain only one signature rather than a set of them.
                    //My solution was to simply not sign the envelope. At least until Fabric v1.2, the codebase seems to accept unsigned envelopes.
                    Common.Envelope newEnvelope = BFTCommon.makeUnsignedEnvelope(newConfEnv.toByteString(), ByteString.EMPTY, Common.HeaderType.CONFIG, 0, channel, 0, 
                            msgCtx.getTimestamp());
                    
                    block = BFTCommon.createNextBlock(block.getHeader().getNumber(), block.getHeader().getPreviousHash().toByteArray(), new byte[][] { newEnvelope.toByteArray() });
                    
                    updateChannel(mspManager, blockCutter, channel, block.getHeader().getNumber(), newConfEnv, newConfig, msgCtx.getTimestamp());
                    
                }
                
                else if (!isConfigUpdate && channel.equals(sysChannel)) {
                    
                    mspManager.verifyPolicies(channel, true, confUpdate.getReadSet(), confUpdate.getWriteSet(), confEnv, msgCtx.getTimestamp());
                    
                    // We do not perform envelope validation for the envelope that is going to be appended to the system channel, since it is created by
                    // its own organization/msp. The fabric codebase does re-apply the filters and hence authenticates the envelope to perform a sanity
                    // check, but the the prime reason for re-applying the filters is to check that the size of the envelope is still within the size
                    // limit. Furthermore, since this ordering service cannot sign the envelope, it should not validate it either (see comments below
                    // regarding why the envelope is not signed)
                    //if (envValidation) mspManager.validateEnvelope(env, channel, msgCtx.getTimestamp(), timeWindow);
                    
                    logger.info("Orderer transaction envelope for system channel is valid, generating genesis with readset and writeset for channel "+ confUpdate.getChannelId());
                    
                    Configtx.Config newConfig = mspManager.newChannelConfig(channel,confUpdate.getReadSet(),confUpdate.getWriteSet());
                    Configtx.ConfigEnvelope newConfEnv = BFTCommon.makeConfigEnvelope(newConfig, env);
                    
                    //The fabric codebase inserts the lastupdate structure into a signed envelope. We cannot do this here because the signatures
                    //are different for each replica, thus producing different blocks. Even if I modified the frontend to be aware of this corner
                    //case just like it is done for block signatures, envelopes are supposed to contain only one signature rather than a set of them.
                    //My solution was to simply not sign the envelope. At least until Fabric v1.2, the codebase seems to accept unsigned envelopes.
                    Common.Envelope envelopeClone = BFTCommon.makeUnsignedEnvelope(newConfEnv.toByteString(), ByteString.EMPTY, Common.HeaderType.CONFIG, 0, confUpdate.getChannelId(), 0, 
                            msgCtx.getTimestamp());
                    
                    Common.Block genesis = BFTCommon.createNextBlock(0, null, new byte[][]{envelopeClone.toByteArray()});
                    
                    envelopeClone = BFTCommon.makeUnsignedEnvelope(envelopeClone.toByteString(), ByteString.EMPTY, Common.HeaderType.ORDERER_TRANSACTION, 0, channel, 0, 
                            msgCtx.getTimestamp());
                    
                    block = BFTCommon.createNextBlock(block.getHeader().getNumber(), block.getHeader().getPreviousHash().toByteArray(), new byte[][] { envelopeClone.toByteArray() });

                    newChannel(mspManager, blockCutter, lastBlockHeaders, confUpdate.getChannelId(), genesis, msgCtx.getTimestamp());
                    
                } else {
                    
                    String msg = "Envelope contained channel creation request, but was submitted to a non-system channel (" + channel + ")";
                    logger.info(msg);
                    throw new BFTCommon.BFTException(msg);
                }
            }
            
            //optimization to parellise signatures and sending
            if (fromConsensus) { //if this is from the state transfer, there is no point in signing and sending yet again

                if (sigIndex % blocksPerThread == 0) {

                    if (currentSST != null) {
                        currentSST.input(null, null, -1, null, false, null);
                    }

                    currentSST = this.queue.take(); // fetch the first SSThread that is idle

                }

                currentSST.input(block, msgCtx, this.sequence, channel, isConfig, mspManager);
                sigIndex++;
            }
            
            lastBlockHeaders.put(channel, block.getHeader());
            
        } catch (Exception ex) {
            
            //TODO: Handle error
            
            if (ex instanceof BFTCommon.BFTException) throw (BFTCommon.BFTException) ex;
            BFTCommon.BFTException e = new BFTCommon.BFTException("Error while processing envelopes.");
            e.addSuppressed(ex);
            throw e;
            
        } //finally {

        //TODO: this will need a finally statement once error replies are implemented
        this.sequence++; // increment the sequence number that frontends expect
                 
        //}
                       
    }
       
    @Override
    public byte[] appExecuteUnordered(byte[] command, MessageContext msgCtx) {
        return new byte[0];
    }

    private class BlockWorkerThread implements Runnable {

        private LinkedBlockingQueue<BFTCommon.BFTTuple> input;

        LinkedBlockingQueue<BlockWorkerThread> queue;

        private final Lock inputLock;
        private final Condition notEmptyInput;

        BlockWorkerThread(LinkedBlockingQueue<BlockWorkerThread> queue) throws NoSuchAlgorithmException, NoSuchProviderException, InterruptedException {

            this.queue = queue;

            this.input = new LinkedBlockingQueue<>();

            this.inputLock = new ReentrantLock();
            this.notEmptyInput = inputLock.newCondition();

            this.queue.put(this);
        }

        public void input(Common.Block block, MessageContext msgContext, int seq, String channel, boolean config, MSPManager clonedManager) throws InterruptedException {

            this.inputLock.lock();

            this.input.put(BFTCommon.getBFTTuple(block, msgContext, seq, channel, config, clonedManager));

            this.notEmptyInput.signalAll();
            this.inputLock.unlock();

        }

        @Override
        public void run() {

            while (true) {

                try {

                    LinkedList<BFTCommon.BFTTuple> list = new LinkedList<>();

                    this.inputLock.lock();

                    if (this.input.isEmpty()) {
                        this.notEmptyInput.await();

                    }
                    this.input.drainTo(list);
                    this.inputLock.unlock();

                    for (BFTCommon.BFTTuple tuple : list) {

                        if (tuple.sequence == -1) {
                            this.queue.put(this);
                            break;
                        }

                        if (blockMeasurementStartTime == -1) {
                            blockMeasurementStartTime = System.currentTimeMillis();
                        }

                        //validate envelopes in block and discard invalid ones (if validation is enabled)
                        if (envValidation && !tuple.config) { // if it is a configuration envelope, evaluation was already performed at the SMR thread
                            
                            Common.BlockData.Builder filtered = Common.BlockData.newBuilder();
                            List<ByteString> envs = tuple.block.getData().getDataList();
                            
                            for (ByteString env : envs) {
                                
                                try {
                                    
                                    Common.Envelope e = null;
                                    
                                    try {
                                        
                                        e = Common.Envelope.parseFrom(env);
                                        
                                    } catch (Exception ex) {
                                        
                                        logger.warn("Malformed envelope, discarding");
                                        continue;
                                    }
                                    
                                    tuple.clonedManager.validateEnvelope(e, tuple.channelID, tuple.msgContext.getTimestamp(), timeWindow);
                                    filtered.addData(env);
                               
                                } catch (BFTCommon.BFTException ex) {
                                    
                                    logger.info("Envelope validation failed, discarding.");
                                }
        
                            }
                            
                            tuple.block = tuple.block.toBuilder().setData(filtered).build();
                        }
                        
                        logger.debug("Disseminating block containing " + tuple.block.getData().getDataCount() + " envelopes");

                        //create signatures
                        Common.Metadata blockSig = BFTCommon.createMetadataSignature(privKey, ident.toByteArray(), tuple.msgContext.getNonces(), null, tuple.block.getHeader());
                        Common.Metadata configSig = null;
                        
                        Common.LastConfig.Builder last = Common.LastConfig.newBuilder();
                            last.setIndex(tuple.clonedManager.getLastConfig(tuple.channelID));
                            
                        if (bothSigs) {
                            
                            configSig = BFTCommon.createMetadataSignature(privKey, ident.toByteArray(), tuple.msgContext.getNonces(), last.build().toByteArray(), tuple.block.getHeader());
                        } else {
                            
                              Common.MetadataSignature.Builder dummySig = 
                                    Common.MetadataSignature.newBuilder().setSignature(ByteString.EMPTY).setSignatureHeader(ByteString.EMPTY);
                            
                            configSig = Common.Metadata.newBuilder().setValue(last.build().toByteString()).addSignatures(dummySig).build();
                            
                        }
                        countBlocks++;

                        if (countBlocks % interval == 0) {

                            float tp = (float) (interval * 1000 / (float) (System.currentTimeMillis() - blockMeasurementStartTime));
                            loggerThroughput.info("blocks/second\t" + tp);
                            blockMeasurementStartTime = System.currentTimeMillis();

                        }

                        //serialize contents
                        byte[][] contents = new byte[5][];
                        contents[0] = tuple.block.toByteArray();
                        contents[1] = blockSig.toByteArray();
                        contents[2] = configSig.toByteArray();
                        contents[3] = tuple.channelID.getBytes();
                        contents[4] = new byte[] { (tuple.config ? (byte) 1 :(byte) 0) };

                        byte[] serialized = BFTCommon.serializeContents(contents);

                        // send contents to the orderers
                        TOMMessage reply = new TOMMessage(id,
                                tuple.msgContext.getSession(),
                                tuple.sequence, //change sequence because the message is going to be received by all clients, not just the original sender
                                tuple.msgContext.getOperationId(),
                                serialized,
                                tuple.msgContext.getViewID(),
                                tuple.msgContext.getType());

                        waitforReplica(); //avoid a null pointer exception caused by the replica attribute
                        
                        int[] clients = replica.getServerCommunicationSystem().getClientsConn().getClients();

                        List<Integer> activeOrderers = new LinkedList<>();

                        for (Integer c : clients) {
                            if (receivers.contains(c)) {

                                activeOrderers.add(c);

                            }
                        }

                        int[] array = new int[activeOrderers.size()];
                        for (int i = 0; i < array.length; i++) {
                            array[i] = activeOrderers.get(i);
                        }
                        
                        replica.getServerCommunicationSystem().send(array, reply);

                    }

                } catch (NoSuchAlgorithmException | NoSuchProviderException | InvalidKeyException | SignatureException | IOException | CryptoException | InterruptedException | BFTCommon.BFTException ex) {
                    logger.error("Failed to generate block", ex);
                }
            }

        }
    }
}

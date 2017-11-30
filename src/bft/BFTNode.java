/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package bft;

import bftsmart.tom.MessageContext;
import bftsmart.tom.ServiceReplica;
import bftsmart.tom.core.messages.TOMMessage;
import bftsmart.tom.server.defaultservices.DefaultRecoverable;
import bftsmart.tom.server.defaultservices.DefaultReplier;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectInputStream;
import java.io.ObjectOutput;
import java.io.ObjectOutputStream;
import java.io.StringWriter;
import java.security.InvalidKeyException;
import java.security.KeyPair;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.PrivateKey;
import java.security.SignatureException;
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
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.LineIterator;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.ASN1OutputStream;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemWriter;
import org.hyperledger.fabric.sdk.exception.CryptoException;
import org.hyperledger.fabric.sdk.exception.InvalidArgumentException;
import org.hyperledger.fabric.sdk.security.CryptoPrimitives;
import org.hyperledger.fabric.protos.common.Common;
import org.hyperledger.fabric.protos.msp.Identities;

/**
 *
 * @author joao
 */
public class BFTNode extends DefaultRecoverable {

    public final static String DEFAULT_CONFIG_FOLDER = "./config/";

    private int id;
    private ServiceReplica replica = null;
    private CryptoPrimitives crypto;
    private Log logger;
    private String configFolder;

    // MSP stuff
    private String mspid = null;
    private PrivateKey privKey = null;
    private X509CertificateHolder certificate = null;
    private byte[] serializedCert = null;
    private Identities.SerializedIdentity ident;

    //signature thread stuff
    private int parallelism;
    LinkedBlockingQueue<SignerSenderThread> queue;
    private ExecutorService executor = null;
    private int blocksPerThread;
    private int sigIndex = 0;
    private SignerSenderThread currentSST = null;

    //measurements
    private int interval = 10000;
    private long envelopeMeasurementStartTime = -1;
    private long blockMeasurementStartTime = -1;
    private long sigsMeasurementStartTime = -1;
    private int countEnvelopes = 0;
    private int countBlocks = 0;
    private int countSigs = 0;
    
    //used to avoid the signing threads from accessing a null pointer in 'replica'
    private Lock replicaLock;
    private Condition replicaReady;

    //these attributes are the state of this replicated state machine
    private Set<Integer> receivers;
    private BlockCutter blockCutter;
    private int sequence = 0;
    private long lastConfig = 0;
    private Map<String,Common.BlockHeader> lastBlockHeaders;
    private String sysChannel = "";

    public BFTNode(int id, String configFolder) throws IOException, InvalidArgumentException, CryptoException, NoSuchAlgorithmException, NoSuchProviderException, InterruptedException, ClassNotFoundException, IllegalAccessException, InstantiationException {

        this.replicaLock = new ReentrantLock();
        this.replicaReady = replicaLock.newCondition();

        this.id = id;
        this.configFolder = (configFolder != null ? configFolder : BFTNode.DEFAULT_CONFIG_FOLDER);

        loadConfig();
        
        this.crypto = new CryptoPrimitives();
        this.crypto.init();
        
        this.logger = LogFactory.getLog(BFTNode.class);

        this.queue = new LinkedBlockingQueue<>();
        this.executor = Executors.newWorkStealingPool(this.parallelism);

        for (int i = 0; i < parallelism; i++) {

            this.executor.execute(new SignerSenderThread(this.queue));
        }
        
        this.sequence = 0;
        this.lastBlockHeaders = new TreeMap<>();
                
        //logger.info("This is the signature algorithm: " + this.crypto.getSignatureAlgorithm());
        //logger.info("This is the hash algorithm: " + this.crypto.getHashAlgorithm());
        
        this.replica = new ServiceReplica(this.id, this.configFolder, this, this, null, new NoopReplier());
        
        this.replicaLock.lock();
        this.replicaReady.signalAll();
        this.replicaLock.unlock();
        
    }

    private String extractMSPID(byte[] bytes) {
        try {
            Common.Envelope env = Common.Envelope.parseFrom(bytes);
            Common.Payload payload = Common.Payload.parseFrom(env.getPayload());
            Common.SignatureHeader sigHeader = Common.SignatureHeader.parseFrom(payload.getHeader().getSignatureHeader());
            Identities.SerializedIdentity ident = Identities.SerializedIdentity.parseFrom(sigHeader.getCreator());
            return ident.getMspid();
            
            
        } catch (InvalidProtocolBufferException ex) {
            //ex.printStackTrace();
            return null;
        }
        
    }
    
    private void loadConfig() throws IOException {
        
        LineIterator it = FileUtils.lineIterator(new File(this.configFolder + "node.config"), "UTF-8");
        
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
        privKey = getPemPrivateKey(configs.get("PRIVKEY"));
        certificate = getCertificate(configs.get("CERTIFICATE"));
        serializedCert = getSerializedCertificate(certificate);
        ident = getSerializedIdentity(mspid, serializedCert);
        parallelism = Integer.parseInt(configs.get("PARELLELISM"));
        blocksPerThread = Integer.parseInt(configs.get("BLOCKS_PER_THREAD"));
        
        String[] IDs = configs.get("RECEIVERS").split("\\,");
        int[] receivers = Arrays.asList(IDs).stream().mapToInt(Integer::parseInt).toArray();
        
        this.receivers = new TreeSet<>();
        for (int o : receivers) {
            this.receivers.add(o);
        }
                
    }
    
    public static void main(String[] args) throws Exception {

        if (args.length < 1) {
            System.out.println("Use: java BFTNode <processId> [<config folder>]");
            System.exit(-1);
        }
        String configFolder = null;
        if (args.length > 1) configFolder = args[1];
        
        new BFTNode(Integer.parseInt(args[0]),configFolder);
    }

    @Override
    public void installSnapshot(byte[] state) {
        
        try {
            
            ByteArrayInputStream bis = new ByteArrayInputStream(state);
            DataInputStream in = new DataInputStream(bis);
            
            sysChannel = in.readUTF();
            sequence = in.readInt();
            lastConfig = in.readLong();
            
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
            
            blockCutter = null;
            n = in.readInt();
            
            if (n > 0) {
            
                byte[] b = new byte[n];
                in.read(b);
                            
                blockCutter = new BlockCutter();
                blockCutter.deserialize(b);
            }
            
            //deserialize headers
            ByteArrayInputStream b = new ByteArrayInputStream(headers);
            ObjectInput i = new ObjectInputStream(b);
            
            this.lastBlockHeaders = (Map<String,Common.BlockHeader>) i.readObject();
            i.close();
            b.close();
            
        } catch (IOException ex) {
            ex.printStackTrace();
        } catch (ClassNotFoundException ex) {
            ex.printStackTrace();
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
            
            //concatenate bytes
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bos);
            
            out.writeUTF(sysChannel);
            out.writeInt(sequence);
            out.writeLong(lastConfig);
            
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
            
            out.writeInt(blockcutter.length);
            if (blockcutter.length > 0) out.write(blockcutter);
            out.flush();
            bos.flush();
            
            out.close();
            bos.close();
            
            return bos.toByteArray();
            
        } catch (IOException ex) {
            
            ex.printStackTrace();
            
        }
        return new byte[0];
    }

    @Override
    public byte[][] appExecuteBatch(byte[][] commands, MessageContext[] msgCtxs, boolean fromConsensus) {

        byte[][] replies = new byte[commands.length][];
        for (int i = 0; i < commands.length; i++) {
            if (msgCtxs != null && msgCtxs[i] != null) {

                try {
                    replies[i] = executeSingle(commands[i], msgCtxs[i], fromConsensus);
                } catch (IOException ex) {
                    ex.printStackTrace();
                }
            }
        }

        return replies;
    }

    private byte[] executeSingle(byte[] command, MessageContext msgCtx, boolean fromConsensus) throws IOException {

        if (receivers.contains(msgCtx.getSender())) {
            
            if (msgCtx.getSequence() == 0) {

                if (blockCutter == null) {
                    
                    logger.info("Initializing blockcutter");
                    
                    blockCutter = new BlockCutter(command);
                }

                return new byte[0];
            }
                       
            if (Arrays.equals("GETVIEW".getBytes(), command)) {
                
                logger.info("A proxy is trying to fetch the most current view");
                return new byte[0];
            }
            
            RequestTuple tuple = deserializeRequest(command);

            if (tuple.type.equals("NEWCHANNEL")) {
                                             
                if (tuple.channelID != null && lastBlockHeaders.get(tuple.channelID) == null) {

                    Common.BlockHeader header = null;

                    header = Common.BlockHeader.parseFrom(tuple.payload);
                
                    lastBlockHeaders.put(tuple.channelID, header);

                    logger.info("New channel ID: " + tuple.channelID);
                    logger.info("Genesis header number: " + lastBlockHeaders.get(tuple.channelID).getNumber());
                    logger.info("Genesis header previous hash: " + Arrays.toString(lastBlockHeaders.get(tuple.channelID).getPreviousHash().toByteArray()));
                    logger.info("Genesis header data hash: " + Arrays.toString(lastBlockHeaders.get(tuple.channelID).getDataHash().toByteArray()));
                    logger.info("Genesis header ASN1 encoding: " + Arrays.toString(encodeBlockHeaderASN1(lastBlockHeaders.get(tuple.channelID))));
                    
                    if (msgCtx.getSequence() == 1) {

                        logger.info("Setting system channel to " + tuple.channelID);

                        sysChannel = tuple.channelID;
                    }
                }
                
                return new byte[0];
            }
            
            if (tuple.type.equals("TIMEOUT") && (blockCutter != null)) {
                
                
                logger.info("Purging blockcutter for channel " + tuple.channelID);

                byte[][] batch = blockCutter.cut(tuple.channelID);

                if (batch.length > 0) {

                    assembleAndSend(batch, msgCtx, fromConsensus, tuple.channelID, false);
                }
                
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
            logger.info("Throughput = " + tp + " envelopes/sec");
            envelopeMeasurementStartTime = System.currentTimeMillis();

        }

        logger.debug("Envelopes received: " + countEnvelopes);

        List<byte[][]> batches = null;
                    
        RequestTuple tuple = deserializeRequest(command);
        boolean isConfig = tuple.type.equals("CONFIG");

        logger.debug("Received envelope" + Arrays.toString(tuple.payload) + " for channel id " + tuple.channelID + (isConfig ? " (type config)" : " (type normal)"));

        String mspid = extractMSPID(tuple.payload);
        if (mspid != null && this.mspid.equals(mspid)) {
        
            batches = blockCutter.ordered(tuple.channelID, tuple.payload, isConfig);

            if (batches != null) {

                for (int i = 0; i < batches.size(); i++) {
                    assembleAndSend(batches.get(i), msgCtx, fromConsensus, tuple.channelID, isConfig);
                }

            }
            
        }
        else {
            logger.info((mspid == null ? "Malformed envelope" : "Envelope's MSPID is unknown")+", discarding");
        }
        
        return "ACK".getBytes();

    }

    private void assembleAndSend(byte[][] batch, MessageContext msgCtx, boolean fromConsensus, String channel, boolean config) {
        try {

            if (blockMeasurementStartTime == -1) {
                blockMeasurementStartTime = System.currentTimeMillis();
            }
            
            Common.Block block = createNextBlock(lastBlockHeaders.get(channel).getNumber() + 1, crypto.hash(encodeBlockHeaderASN1(lastBlockHeaders.get(channel))), batch);

            if (config) lastConfig = block.getHeader().getNumber();
                
            countBlocks++;

            if (countBlocks % interval == 0) {

                float tp = (float) (interval * 1000 / (float) (System.currentTimeMillis() - blockMeasurementStartTime));
                logger.info("Throughput = " + tp + " blocks/sec");
                blockMeasurementStartTime = System.currentTimeMillis();

            }

            lastBlockHeaders.put(channel, block.getHeader());
            
            if ((lastBlockHeaders.get(channel).getNumber() % 100) == 0)
                 System.out.println("[" + channel + "] Genesis header hash for header #" + lastBlockHeaders.get(channel).getNumber() + ": " + Arrays.toString(lastBlockHeaders.get(channel).getDataHash().toByteArray()));
            
            //optimization to parellise signatures and sending
            if (fromConsensus) { //if this is from the state transfer, there is no point in signing and sending yet again
                
                if (sigIndex % blocksPerThread == 0) {

                    if (currentSST != null) {
                        currentSST.input(null, null, -1, null, false, -1);
                    }

                    currentSST = this.queue.take(); // fetch the first SSThread that is idle

                }

                currentSST.input(block, msgCtx, this.sequence, channel, config, lastConfig);
                sigIndex++;
            }
            
            //Runnable SSThread = new SignerSenderThread(block, msgCtx, this.sequence);
            //this.executor.execute(SSThread);
            this.sequence++; // because of parelisation, I need to increment the sequence number in this method
                       
            //standard code for sequential signing and sending (with debbuging prints)
            /*CommonProtos.Metadata blockSig = createMetadataSignature(("BFT-SMaRt::"+id).getBytes(), msgCtx.getNonces(), null, lastBlockHeader);
            byte[] dummyConf= {0,0,0,0,0,0,0,1}; //TODO: find a way to implement the check that is done in the golang code
            CommonProtos.Metadata configSig = createMetadataSignature(("BFT-SMaRt::"+id).getBytes(), msgCtx.getNonces(), dummyConf, lastBlockHeader);

            sendToOrderers(block, blockSig, configSig, msgCtx);*/
        } catch (NoSuchAlgorithmException | NoSuchProviderException | IOException | InterruptedException ex) {
            Logger.getLogger(BFTNode.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    /*private void sendToOrderers(Common.Block block, Common.Metadata blockSig, Common.Metadata configSig, MessageContext msgCtx) throws IOException {

        byte[][] contents = new byte[3][];
        contents[0] = block.toByteArray();
        contents[1] = blockSig.toByteArray();
        contents[2] = configSig.toByteArray();

        TOMMessage reply = null;
        reply = new TOMMessage(id,
                msgCtx.getSession(),
                sequence, //change sequence because the message is going to be received by all clients, not just the original sender
                msgCtx.getOperationId(),
                serializeContents(contents),
                replica.getReplicaContext().getCurrentView().getId(),
                msgCtx.getType());

        if (reply == null) {
            return;
        }

        int[] clients = replica.getReplicaContext().getServerCommunicationSystem().getClientsConn().getClients();

        replica.getReplicaContext().getServerCommunicationSystem().send(clients, reply);

        sequence++;
    }*/

    private byte[] serializeContents(byte[][] contents) throws IOException {

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);

        out.writeInt(contents.length);

        out.flush();
        bos.flush();
        for (int i = 0; i < contents.length; i++) {

            out.writeInt(contents[i].length);

            out.write(contents[i]);

            out.flush();
            bos.flush();
        }

        out.close();
        bos.close();
        return bos.toByteArray();

    }

    private byte[] encodeBlockHeaderASN1(Common.BlockHeader header) throws IOException {

        //convert long to byte array
        //ByteArrayOutputStream bos = new ByteArrayOutputStream();
        //ObjectOutput out = new ObjectOutputStream(bos);
        //out.writeLong(header.getNumber());
        //out.flush();
        //bos.flush();
        //out.close();
        //bos.close();
        //byte[] number = bos.toByteArray();
        // encode the header in ASN1 format
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ASN1OutputStream asnos = new ASN1OutputStream(bos);

        asnos.writeObject(new ASN1Integer((int) header.getNumber()));
        //asnos.writeObject(new DERInteger((int) header.getNumber()));
        asnos.writeObject(new DEROctetString(header.getPreviousHash().toByteArray()));
        asnos.writeObject(new DEROctetString(header.getDataHash().toByteArray()));
        asnos.flush();
        bos.flush();
        asnos.close();
        bos.close();

        byte[] buffer = bos.toByteArray();

        //Add golang idiosyncrasies
        byte[] bytes = new byte[buffer.length + 2];
        bytes[0] = 48; // no idea what this means, but golang's encoding uses it
        bytes[1] = (byte) buffer.length; // length of the rest of the octet string, also used by golang
        for (int i = 0; i < buffer.length; i++) { // concatenate
            bytes[i + 2] = buffer[i];
        }

        return bytes;
    }

    private Common.Block createNextBlock(long number, byte[] previousHash, byte[][] envs) throws NoSuchAlgorithmException, NoSuchProviderException {

        //initialize
        Common.BlockHeader.Builder blockHeaderBuilder = Common.BlockHeader.newBuilder();
        Common.BlockData.Builder blockDataBuilder = Common.BlockData.newBuilder();
        Common.BlockMetadata.Builder blockMetadataBuilder = Common.BlockMetadata.newBuilder();
        Common.Block.Builder blockBuilder = Common.Block.newBuilder();

        //create header
        blockHeaderBuilder.setNumber(number);
        blockHeaderBuilder.setPreviousHash(ByteString.copyFrom(previousHash));
        blockHeaderBuilder.setDataHash(ByteString.copyFrom(crypto.hash(concatenate(envs))));

        //create metadata
        int numIndexes = Common.BlockMetadataIndex.values().length;
        for (int i = 0; i < numIndexes; i++) {
            blockMetadataBuilder.addMetadata(ByteString.EMPTY);
        }

        //create data
        for (int i = 0; i < envs.length; i++) {
            blockDataBuilder.addData(ByteString.copyFrom(envs[i]));
        }

        //crete block
        blockBuilder.setHeader(blockHeaderBuilder.build());
        blockBuilder.setMetadata(blockMetadataBuilder.build());
        blockBuilder.setData(blockDataBuilder.build());

        return blockBuilder.build();
    }

    private Common.Metadata createMetadataSignature(byte[] creator, byte[] nonce, byte[] plaintext, Common.BlockHeader blockHeader) throws NoSuchAlgorithmException, NoSuchProviderException, InvalidKeyException, SignatureException, IOException, CryptoException {

        Common.Metadata.Builder metadataBuilder = Common.Metadata.newBuilder();
        Common.MetadataSignature.Builder metadataSignatureBuilder = Common.MetadataSignature.newBuilder();
        Common.SignatureHeader.Builder signatureHeaderBuilder = Common.SignatureHeader.newBuilder();

        signatureHeaderBuilder.setCreator(ByteString.copyFrom(creator));
        signatureHeaderBuilder.setNonce(ByteString.copyFrom(nonce));

        Common.SignatureHeader sigHeader = signatureHeaderBuilder.build();

        metadataSignatureBuilder.setSignatureHeader(sigHeader.toByteString());

        byte[][] concat = {plaintext, sigHeader.toByteString().toByteArray(), encodeBlockHeaderASN1(blockHeader)};

        //byte[] sig = sign(concatenate(concat));
        byte[] sig = crypto.sign(privKey, concatenate(concat));

        logger.debug("Signature for block #" + blockHeader.getNumber() + ": " + Arrays.toString(sig) + "\n");

        //parseSig(sig);
        metadataSignatureBuilder.setSignature(ByteString.copyFrom(sig));

        metadataBuilder.setValue((plaintext != null ? ByteString.copyFrom(plaintext) : ByteString.EMPTY));
        metadataBuilder.addSignatures(metadataSignatureBuilder);

        return metadataBuilder.build();
    }

    private byte[] concatenate(byte[][] bytes) {

        int totalLength = 0;
        for (byte[] b : bytes) {
            if (b != null) {
                totalLength += b.length;
            }
        }

        byte[] concat = new byte[totalLength];
        int last = 0;

        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] != null) {
                for (int j = 0; j < bytes[i].length; j++) {
                    concat[last + j] = bytes[i][j];
                }

                last += bytes[i].length;
            }

        }

        return concat;
    }

    /*private void parseSig(byte[] sig) throws IOException {
        
        ASN1InputStream input = new ASN1InputStream(sig);

        ASN1Primitive p;
        while ((p = input.readObject()) != null) {
            ASN1Sequence asn1 = ASN1Sequence.getInstance(p);

            ASN1Integer r = ASN1Integer.getInstance(asn1.getObjectAt(0));
            ASN1Integer s = ASN1Integer.getInstance(asn1.getObjectAt(1));
            

            logger.info("r (int): " + r.getValue().toString());
            logger.info("s (int): " + s.getValue().toString());
            
            logger.info("r (bytes): " + Arrays.toString(r.getValue().toByteArray()));
            logger.info("s (bytes): " + Arrays.toString(s.getValue().toByteArray()));
        }
        
    }
    
    public static byte[] sha256(byte[] bytes) throws NoSuchAlgorithmException, NoSuchProviderException {
        
        MessageDigest digestEngine = MessageDigest.getInstance("SHA-256","SUN");
                
        return digestEngine.digest(bytes);
    }
    
    private byte[] sign(byte[] text) throws NoSuchAlgorithmException, InvalidKeyException, SignatureException, NoSuchProviderException, CryptoException {
        
        Signature signEngine = Signature.getInstance("SHA256withECDSA", BouncyCastleProvider.PROVIDER_NAME);
        signEngine.initSign(privKey);
        signEngine.update(text);
        return signEngine.sign();
        
    }
    
    private boolean verify(byte[] text, byte[] signature) throws NoSuchAlgorithmException, InvalidKeyException, SignatureException, NoSuchProviderException, PEMException {
        
        PublicKey k = new JcaPEMKeyConverter().getPublicKey(certificate.getSubjectPublicKeyInfo());
        Signature signEngine = Signature.getInstance("ECDSA", BouncyCastleProvider.PROVIDER_NAME);
        signEngine.initVerify(k);
        signEngine.update(text);
        return signEngine.verify(signature);
    }*/
    private PrivateKey getPemPrivateKey(String filename) throws IOException {

        BufferedReader br = new BufferedReader(new FileReader(filename));

        PEMParser pp = new PEMParser(br);
        Object obj = pp.readObject();
        
        pp.close();
        br.close();
        
        if (obj instanceof PrivateKeyInfo) {
        
            PrivateKeyInfo keyInfo = (PrivateKeyInfo) obj;
            return (new JcaPEMKeyConverter().getPrivateKey(keyInfo));
        
        } else {
            
            PEMKeyPair pemKeyPair = (PEMKeyPair) obj;

            KeyPair kp = new JcaPEMKeyConverter().getKeyPair(pemKeyPair);
            return kp.getPrivate();

        }

    }
    
    private X509CertificateHolder getCertificate(String filename) throws IOException {

        BufferedReader br = new BufferedReader(new FileReader(filename));
        PEMParser pp = new PEMParser(br);
        X509CertificateHolder ret = (X509CertificateHolder) pp.readObject();
        
        br.close();
        pp.close();
        
        return ret;
    }
    
    private byte[] getSerializedCertificate(X509CertificateHolder certificate) throws IOException {
        
        PemObject pemObj = (new PemObject("", certificate.getEncoded()));

        StringWriter strWriter = new StringWriter();
        PemWriter writer = new PemWriter(strWriter);
        writer.writeObject(pemObj);

        writer.close();
        strWriter.close();

        return strWriter.toString().getBytes();
        
    }

    private Identities.SerializedIdentity getSerializedIdentity(String Mspid, byte[] serializedCert) {

        Identities.SerializedIdentity.Builder ident = Identities.SerializedIdentity.newBuilder();
        ident.setMspid(Mspid);
        ident.setIdBytes(ByteString.copyFrom(serializedCert));
        return ident.build();
    }

    private RequestTuple deserializeRequest(byte[] request) throws IOException {
        
        ByteArrayInputStream bis = new ByteArrayInputStream(request);
        DataInput in = new DataInputStream(bis);
        
        String type = in.readUTF();
        String channelID = in.readUTF();
        int l = in.readInt();
        byte[] payload = new byte[l];
        in.readFully(payload);
      
        bis.close();
        
        return new RequestTuple(type, channelID, payload);
        
    }
            
    @Override
    public byte[] appExecuteUnordered(byte[] command, MessageContext msgCtx) {
        return new byte[0];
    }

    private class SignerSenderThread implements Runnable {

        private LinkedBlockingQueue<BFTTuple> input;

        LinkedBlockingQueue<SignerSenderThread> queue;

        private final Lock inputLock;
        private final Condition notEmptyInput;

        SignerSenderThread(LinkedBlockingQueue<SignerSenderThread> queue) throws NoSuchAlgorithmException, NoSuchProviderException, InterruptedException {

            this.queue = queue;

            this.input = new LinkedBlockingQueue<>();

            this.inputLock = new ReentrantLock();
            this.notEmptyInput = inputLock.newCondition();

            this.queue.put(this);
        }

        public void input(Common.Block block, MessageContext msgContext, int seq, String channel, boolean config, long lastConfig) throws InterruptedException {

            this.inputLock.lock();

            this.input.put(new BFTTuple(block, msgContext, seq, channel, config, lastConfig));

            this.notEmptyInput.signalAll();
            this.inputLock.unlock();

        }

        @Override
        public void run() {

            while (true) {

                try {

                    LinkedList<BFTTuple> list = new LinkedList<>();

                    this.inputLock.lock();

                    if (this.input.isEmpty()) {
                        this.notEmptyInput.await();

                    }
                    this.input.drainTo(list);
                    this.inputLock.unlock();

                    for (BFTTuple tuple : list) {

                        if (tuple.sequence == -1) {
                            this.queue.put(this);
                            break;
                        }

                        if (sigsMeasurementStartTime == -1) {
                            sigsMeasurementStartTime = System.currentTimeMillis();
                        }

                        //create signatures
                        Common.Metadata blockSig = createMetadataSignature(ident.toByteArray(), tuple.msgContext.getNonces(), null, tuple.block.getHeader());

                        Common.LastConfig.Builder last = Common.LastConfig.newBuilder();
                        last.setIndex(tuple.lastConf);
                        
                        Common.Metadata configSig = createMetadataSignature(ident.toByteArray(), tuple.msgContext.getNonces(), last.build().toByteArray(), tuple.block.getHeader());

                        countSigs++;

                        if (countSigs % interval == 0) {

                            float tp = (float) (interval * 1000 / (float) (System.currentTimeMillis() - sigsMeasurementStartTime));
                            logger.info("Throughput = " + tp + " sigs/sec");
                            sigsMeasurementStartTime = System.currentTimeMillis();

                        }

                        //serialize contents
                        byte[][] contents = new byte[5][];
                        contents[0] = tuple.block.toByteArray();
                        contents[1] = blockSig.toByteArray();
                        contents[2] = configSig.toByteArray();
                        contents[3] = tuple.channelID.getBytes();
                        contents[4] = new byte[] { (tuple.config ? (byte) 1 :(byte) 0) };

                        byte[] serialized = serializeContents(contents);

                        // send contents to the orderers
                        TOMMessage reply = new TOMMessage(id,
                                tuple.msgContext.getSession(),
                                tuple.sequence, //change sequence because the message is going to be received by all clients, not just the original sender
                                tuple.msgContext.getOperationId(),
                                serialized,
                                tuple.msgContext.getViewID(),
                                tuple.msgContext.getType());

                        while (replica == null) {
                            
                            replicaLock.lock();
                            replicaReady.await(1000, TimeUnit.MILLISECONDS);
                            replicaLock.lock();
                        }
                        
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

                } catch (NoSuchAlgorithmException | NoSuchProviderException | InvalidKeyException | SignatureException | IOException | CryptoException | InterruptedException ex) {
                    Logger.getLogger(BFTNode.class.getName()).log(Level.SEVERE, null, ex);
                }
            }

        }
    }

    private class BFTTuple {

        Common.Block block = null;
        MessageContext msgContext = null;
        int sequence = -1;
        String channelID = null;
        boolean config = false;
        long lastConf = -1;

        BFTTuple(Common.Block block, MessageContext msgCtx, int sequence, String channelID, boolean config, long lastConf) {

            this.block = block;
            this.msgContext = msgCtx;
            this.sequence = sequence;
            this.channelID = channelID;
            this.config = config;
            this.lastConf = lastConf;
        }
    }
    
    private class RequestTuple {

        String type = null;
        String channelID = null;
        byte[] payload = null;

        RequestTuple(String type, String channelID, byte[] payload) {

            this.type = type;
            this.channelID = channelID;
            this.payload = payload;

        }
    }

    private class NoopReplier extends DefaultReplier {

        @Override
        public void manageReply(TOMMessage tomm, MessageContext mc) {
            
            if (!receivers.contains(tomm.getSender()))
                super.manageReply(tomm, mc);
        }

    }
}

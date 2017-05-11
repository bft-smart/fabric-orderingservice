/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package bft;

import bftsmart.tom.MessageContext;
import bftsmart.tom.ReplicaContext;
import bftsmart.tom.ServiceReplica;
import bftsmart.tom.core.messages.TOMMessage;
import bftsmart.tom.server.Replier;
import bftsmart.tom.server.defaultservices.DefaultRecoverable;
import bftsmart.tom.util.Storage;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.StringWriter;
import java.security.InvalidKeyException;
import java.security.KeyPair;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.PrivateKey;
import java.security.SignatureException;
import java.util.AbstractMap.SimpleEntry;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.ASN1OutputStream;
import org.bouncycastle.asn1.DEROctetString;
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
    
    public final static String BFTSMART_CONFIG_FOLDER = "./config/";
    
    private int id;
    private String Mspid;
    public ServiceReplica replica = null;
    private PrivateKey privKey = null;
    private X509CertificateHolder certificate = null;
    private byte[] serializedCert = null;
    private Identities.SerializedIdentity ident;
    private CryptoPrimitives crypto;
    private Log logger;
    public static final int REQUEST_WINDOW = 10000;
    
    //signature thread stuff
    private int paralellism;
    private LinkedBlockingQueue<SignerSenderThread> queue;
    private ExecutorService executor = null;
    //private BlockThread blockThread;
    private final int SIG_LIMIT = 1000;
    private int sigIndex = 0;
    private SignerSenderThread currentSST = null;
    
    //measurements
    private int interval = 100000;
    private long envelopeMeasurementStartTime = -1;
    private long blockMeasurementStartTime = -1;
    private long sigsMeasurementStartTime = -1;
    private int countEnvelopes = 0;
    private int countBlocks = 0;
    private int countSigs = 0;

    //these attributes are the state of this replicated state machine
    private Set<Integer> orderers;
    private BlockCutter blockCutter;
    private int sequence = 0;
    private Common.BlockHeader lastBlockHeader; //blockchain related

    
    public BFTNode(int id, int parallelism, String certFile, String keyFile, int[] orderers) throws IOException, InvalidArgumentException, CryptoException, NoSuchAlgorithmException, NoSuchProviderException, InterruptedException {
        this.id = id;
    	this.replica = new ServiceReplica(this.id, this.BFTSMART_CONFIG_FOLDER, this, this, null, new FlowControlReplier());
                
        this.crypto = new CryptoPrimitives();
        this.crypto.init();
 
        this.logger = LogFactory.getLog(BFTNode.class);
                
        this.privKey = getPemPrivateKey(keyFile);
        parseCertificate(certFile);
        this.Mspid = "DEFAULT";
        this.ident = getSerializedIdentity();
        
        
        this.paralellism = parallelism;
        this.queue = new LinkedBlockingQueue<>();
        this.executor = Executors.newWorkStealingPool(this.paralellism);
        
        for (int i = 0 ; i < parallelism; i++) {
            
            this.executor.execute(new SignerSenderThread(this.queue));
        }
        
        //this.blockThread = new BlockThread();
        //this.blockThread.start();

        this.orderers = new TreeSet<>();
        for (int o : orderers) {
            this.orderers.add(o);
        }

        logger.info("This is the signature algorithm: " + this.crypto.getSignatureAlgorithm());
        logger.info("This is the hash algorithm: " + this.crypto.getHashAlgorithm());

    }

    public static void main(String[] args) throws Exception{

        if(args.length < 5) {
            System.out.println("Use: java BFTNode <processId> <thread pool size> <certificate key file> <private key file> <proxy IDs>");
            System.exit(-1);
        }      
        
        String[] IDs = args[4].split("\\,");
        int[] orderers = Arrays.asList(IDs).stream().mapToInt(Integer::parseInt).toArray();
        new BFTNode(Integer.parseInt(args[0]), Integer.parseInt(args[1]), args[2], args[3], orderers);
        
        //new BFTNode(0);
    }

    @Override
    public void installSnapshot(byte[] state) {
        //nothing
    }

    @Override
    public byte[] getSnapshot() {
        return new byte[0];
    }

    @Override
    public byte[][] appExecuteBatch(byte[][] commands, MessageContext[] msgCtxs) {
        
        
        byte [][] replies = new byte[commands.length][];
        for (int i = 0; i < commands.length; i++) {
            if(msgCtxs != null && msgCtxs[i] != null) {
                
                
            
                replies[i] = executeSingle(commands[i], msgCtxs[i]);
            }
        }
        
        return replies;
    }

    public byte[] executeSingle(byte[] command, MessageContext msgCtx)  {
                
        
        if (command.length == 0 && blockCutter != null && orderers.contains(msgCtx.getSender())) {
            
            byte[][] batch = blockCutter.cut();

            if (batch.length > 0) {

                assembleAndSend(batch, msgCtx);
            }
            
            logger.debug("Purging blockcutter");

            return new byte[0];
        }
        
        if (msgCtx.getSequence() == 0 && orderers.contains(msgCtx.getSender())) {
            
            if (blockCutter == null) blockCutter = new BlockCutter(command);
            
            return new byte[0];

        }
        
        if (msgCtx.getSequence() == 1 && orderers.contains(msgCtx.getSender())) {
            
            if (lastBlockHeader != null) return new byte[0]; 
            
            Common.BlockHeader header = null;
            try {
                header = Common.BlockHeader.parseFrom(command);
                lastBlockHeader = header;

                
                logger.info("Genesis header number: " + lastBlockHeader.getNumber());
                logger.info("Genesis header previous hash: " + Arrays.toString(lastBlockHeader.getPreviousHash().toByteArray()));
                logger.info("Genesis header data hash: " + Arrays.toString(lastBlockHeader.getDataHash().toByteArray()));
                logger.info("Genesis header ASN1 encoding: " + Arrays.toString(encodeBlockHeaderASN1(lastBlockHeader)));
                
            } catch (InvalidProtocolBufferException ex) {
                ex.printStackTrace();
            } catch (IOException ex) {
                ex.printStackTrace();
            }
            
            return new byte[0];
        }
         
        msgCtx.getFirstInBatch().executedTime = System.nanoTime();
        
        if (envelopeMeasurementStartTime == -1) envelopeMeasurementStartTime = System.currentTimeMillis();
        
        
        countEnvelopes++;
                   
        if(countEnvelopes % interval == 0) {
            
            float tp = (float)(interval*1000/(float)(System.currentTimeMillis()-envelopeMeasurementStartTime));
            logger.info("Throughput = " + tp +" envelopes/sec");
            envelopeMeasurementStartTime = System.currentTimeMillis();

        }
                
        logger.debug("Envelopes received: " + countEnvelopes);
        

        /*try {
            if (blockCutter != null) blockThread.input(command, msgCtx);
        } catch (InterruptedException ex) {
            ex.printStackTrace();
        }*/
        
        List<byte[][]> batches = null;
        try {
            batches = blockCutter.ordered(command);
        } catch (IOException ex) {
            ex.printStackTrace();
        }
                
        if (batches == null) return new byte[0];
        
        for (int i = 0; i < batches.size(); i++) {
            assembleAndSend(batches.get(i), msgCtx);
        }
        return new byte[0];
        
    }
    
    private void assembleAndSend(byte[][] batch, MessageContext msgCtx) {
        try {
               
            
            if (blockMeasurementStartTime == -1) {
                blockMeasurementStartTime = System.currentTimeMillis();
            }
            
            Common.Block block = createNextBlock(lastBlockHeader.getNumber() + 1, crypto.hash(encodeBlockHeaderASN1(lastBlockHeader)), batch);
            

            countBlocks++;

            if (countBlocks % interval == 0) {

                float tp = (float) (interval * 1000 / (float) (System.currentTimeMillis() - blockMeasurementStartTime));
                logger.info("Throughput = " + tp + " blocks/sec");
                blockMeasurementStartTime = System.currentTimeMillis();

            }
            
            this.lastBlockHeader = block.getHeader();

            //optimization to parellise signatures and sending
            
            if (sigIndex % SIG_LIMIT == 0) {
            
                if (currentSST != null) currentSST.input(null, null, -1);
                
                currentSST = this.queue.take(); // fetch the first SSThread that is idle
            
            } 

            currentSST.input(block, msgCtx, this.sequence);
            sigIndex++;

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
    
    private void sendToOrderers(Common.Block block, Common.Metadata blockSig, Common.Metadata configSig, MessageContext msgCtx) throws IOException {
        
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

            if (reply == null) return;

            int[] clients = replica.getReplicaContext().getServerCommunicationSystem().getClientsConn().getClients();

            replica.getReplicaContext().getServerCommunicationSystem().send(clients, reply);
            
            sequence++;
    }
    
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
                
        //Add golang idiocracies
        byte[] bytes = new byte[buffer.length+2];
        bytes[0] = 48; // no idea what this means, but golang's encoding uses it
        bytes[1] = (byte) buffer.length; // length of the rest of the octet string, also used by golang
        for (int i = 0; i < buffer.length; i++) { // concatenate
            bytes[i+2] = buffer[i];
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
        for (int i = 0; i < numIndexes; i++) blockMetadataBuilder.addMetadata(ByteString.EMPTY);
        
        //create data
        for (int i = 0; i < envs.length; i++)
            blockDataBuilder.addData(ByteString.copyFrom(envs[i]));

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
                
        byte[][] concat  = {plaintext, sigHeader.toByteString().toByteArray(), encodeBlockHeaderASN1(blockHeader)};
        
        //byte[] sig = sign(concatenate(concat));
        byte[] sig = crypto.ecdsaSignToBytes(privKey, concatenate(concat));
        
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
        //Security.addProvider(new BouncyCastleProvider());
        PEMParser pp = new PEMParser(br);
        PEMKeyPair pemKeyPair = (PEMKeyPair) pp.readObject();
                
        KeyPair kp = new JcaPEMKeyConverter().getKeyPair(pemKeyPair);
        pp.close();
        br.close();
                        
        return kp.getPrivate();
        //samlResponse.sign(Signature.getInstance("SHA1withRSA").toString(), kp.getPrivate(), certs);
                
    }
    
    private void parseCertificate(String filename) throws IOException {
                
            BufferedReader br = new BufferedReader(new FileReader(filename));
            PEMParser pp = new PEMParser(br);
            this.certificate = (X509CertificateHolder) pp.readObject();

            PemObject pemObj = (new PemObject("", this.certificate.getEncoded()));

            StringWriter strWriter = new StringWriter();
            PemWriter writer = new PemWriter(strWriter);
            writer.writeObject(pemObj);
            
            writer.close();
            strWriter.close();
            
            this.serializedCert = strWriter.toString().getBytes();
            
            logger.info("Certificate: " + new String(this.serializedCert));
            
    }
    
    private Identities.SerializedIdentity getSerializedIdentity() {
        
                Identities.SerializedIdentity.Builder ident = Identities.SerializedIdentity.newBuilder();
                ident.setMspid(Mspid);
                ident.setIdBytes(ByteString.copyFrom(serializedCert));
                return ident.build();
    }
    
    @Override
    public byte[] appExecuteUnordered(byte[] command, MessageContext msgCtx) {
        return new byte[0];
    }

    /*private class BlockThread extends Thread {
        
        private LinkedBlockingQueue<Entry<byte[],MessageContext>> envelopes;
        
        private final Lock envelopesLock;
        private final Condition notEmptyQueue;
        
        BlockThread () {
            
            this.envelopes = new LinkedBlockingQueue<>();
            
            this.envelopesLock = new ReentrantLock();
            this.notEmptyQueue = envelopesLock.newCondition();
                }
                        
        void input (byte[] envelope, MessageContext msgCtx) throws InterruptedException {
            
            this.envelopesLock.lock();
            
            this.envelopes.put(new SimpleEntry(envelope, msgCtx));
            
            this.notEmptyQueue.signalAll();
            this.envelopesLock.unlock();
        }
        
        public void run() {
            
            while(true) {
            
                List<byte[][]> batches = null;
                LinkedList<Entry<byte[],MessageContext>> queuedEnvs = new LinkedList();
                try {

                    this.envelopesLock.lock();
                    if(this.envelopes.isEmpty()) {
                        this.notEmptyQueue.await();
                    }
                    this.envelopes.drainTo(queuedEnvs);
                    this.envelopesLock.unlock();
                    
                    for (int i = 0; i < queuedEnvs.size(); i++) {
                        
                        if (queuedEnvs.get(i).getKey().length == 0 &&
                                orderers.contains(queuedEnvs.get(i).getValue().getSender())) {
            
                            byte[][] batch = blockCutter.cut();

                            if (batch.length > 0) {

                                assembleAndSend(batch, queuedEnvs.get(i).getValue());
                            }

                            logger.debug("Purging blockcutter");

                            continue;
                        }
                        
                    
                        batches = blockCutter.ordered(queuedEnvs.get(i).getKey());
                        
                        for (int j = 0; j < batches.size(); j++) {
                            
                            assembleAndSend(batches.get(j), queuedEnvs.get(i).getValue());
                        }
                    }

                } catch (IOException | InterruptedException ex) {
                    ex.printStackTrace();
                }

                
            }
        }
    }*/
    
    private class SignerSenderThread implements Runnable {
        
        private LinkedBlockingQueue<BFTTuple>  input;
        
        private LinkedBlockingQueue<SignerSenderThread> queue;
        
        private final Lock inputLock;
        private final Condition notEmptyInput;

        SignerSenderThread(LinkedBlockingQueue<SignerSenderThread>  queue) throws NoSuchAlgorithmException, NoSuchProviderException, InterruptedException {
            
            this.queue = queue;
            
            this.input = new LinkedBlockingQueue<>();
            
            this.inputLock = new ReentrantLock();
            this.notEmptyInput = inputLock.newCondition();
            
            this.queue.put(this);
        }

        public void input(Common.Block block, MessageContext msgContext, int seq) throws InterruptedException {
            
            this.inputLock.lock();
            
            this.input.put(new BFTTuple(block, msgContext, seq));
            
            this.notEmptyInput.signalAll();
            this.inputLock.unlock();
            
        }

        @Override
        public void run() {
            
            while (true) {
                
                try {
                    
                    LinkedList<BFTTuple> list = new LinkedList<>();
                    
                    this.inputLock.lock();
                    
                    if(this.input.isEmpty()) {
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

                        /*countSigs++;
                        if (countSigs % interval == 0) {

                            float tp = (float) (interval * 1000 / (float) (System.currentTimeMillis() - sigsMeasurementStartTime));
                            logger.info("Throughput = " + tp + " sigs/sec");
                            sigsMeasurementStartTime = System.currentTimeMillis();

                        }*/

                        byte[] dummyConf = {0, 0, 0, 0, 0, 0, 0, 1}; //TODO: find a way to implement the check that is done in the golang code
                        Common.Metadata configSig = createMetadataSignature(ident.toByteArray(), tuple.msgContext.getNonces(), dummyConf, tuple.block.getHeader());

                        countSigs++;

                    if (countSigs % interval == 0) {

                        float tp = (float) (interval * 1000 / (float) (System.currentTimeMillis() - sigsMeasurementStartTime));
                            logger.info("Throughput = " + tp + " sigs/sec");
                            sigsMeasurementStartTime = System.currentTimeMillis();

                        }

                        //serialize contents
                        byte[][] contents = new byte[3][];
                        contents[0] = tuple.block.toByteArray();
                        contents[1] = blockSig.toByteArray();
                        contents[2] = configSig.toByteArray();

                        byte[] serialized = serializeContents(contents);


                        // send contents to the orderers
                        TOMMessage reply = new TOMMessage(id,
                                tuple.msgContext.getSession(),
                                tuple.sequence, //change sequence because the message is going to be received by all clients, not just the original sender
                                tuple.msgContext.getOperationId(),
                                serialized,
                                replica.getReplicaContext().getCurrentView().getId(),
                                tuple.msgContext.getType());


                        int[] clients = replica.getReplicaContext().getServerCommunicationSystem().getClientsConn().getClients();

                        List<Integer> activeOrderers = new LinkedList<>();

                        for (Integer c : clients) {
                            if (orderers.contains(c)) {

                                activeOrderers.add(c);

                            }
                        }


                        int[] array = new int[activeOrderers.size()];
                        for (int i = 0; i < array.length; i++) {
                            array[i] = activeOrderers.get(i);
                        }

                        replica.getReplicaContext().getServerCommunicationSystem().send(array, reply);                    
                    
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
        
        BFTTuple (Common.Block block, MessageContext msgCtx, int sequence) {
            
            this.block = block;
            this.msgContext = msgCtx;
            this.sequence = sequence;
            
        }
    }
    
    private class FlowControlReplier implements Replier {

        private ReplicaContext rc;
    
        @Override
        public void manageReply(TOMMessage request, MessageContext msgCtx) {
            
            if (request.getSequence() % REQUEST_WINDOW == 0) {
                System.out.println("Window of replica " + id + " is available");
                rc.getServerCommunicationSystem().send(new int[]{request.getSender()}, request.reply);
            }
        }

        @Override
        public void setReplicaContext(ReplicaContext rc) {
            this.rc = rc;
        }
    
    }
}

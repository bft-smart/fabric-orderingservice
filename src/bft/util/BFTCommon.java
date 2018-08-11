/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package bft.util;

import bftsmart.tom.MessageContext;
import bftsmart.tom.util.TOMUtil;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Timestamp;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Security;
import java.security.Signature;
import java.security.SignatureException;
import java.security.cert.CertPathBuilder;
import java.security.cert.CertStore;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.CollectionCertStoreParameters;
import java.security.cert.PKIXBuilderParameters;
import java.security.cert.PKIXCertPathBuilderResult;
import java.security.cert.TrustAnchor;
import java.security.cert.X509CertSelector;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.ASN1OutputStream;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.x500.AttributeTypeAndValue;
import org.bouncycastle.asn1.x500.RDN;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x500.style.IETFUtils;
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemWriter;
import org.hyperledger.fabric.protos.common.Common;
import org.hyperledger.fabric.protos.common.Configtx;
import org.hyperledger.fabric.protos.msp.Identities;
import org.hyperledger.fabric.protos.msp.MspConfig;
import org.hyperledger.fabric.protos.orderer.Configuration;
import org.hyperledger.fabric.sdk.exception.CryptoException;
import org.hyperledger.fabric.sdk.security.CryptoPrimitives;

/**
 *
 * @author joao
 */
public class BFTCommon {
    
    private static Logger logger;
    private static CryptoPrimitives crypto;
    
    public final static String DEFAULT_CONFIG_DIR = "./config/";
        
    public static class BFTException extends Exception {
        
        public BFTException(String msg) {
            
            super(msg);
        }
    
    }
        
    public class BFTTuple {

        public Common.Block block = null;
        public MessageContext msgContext = null;
        public int sequence = -1;
        public String channelID = null;
        public boolean config = false;
        
        public MSPManager clonedManager = null;
        
        private BFTTuple(Common.Block block, MessageContext msgCtx, int sequence, String channelID, boolean config, MSPManager clonedManager) {

            this.block = block;
            this.msgContext = msgCtx;
            this.sequence = sequence;
            this.channelID = channelID;
            this.config = config;
            
            this.clonedManager = clonedManager;
        }
    }
    
    public class RequestTuple {

        public int id = -1;
        public String type = null;
        public String channelID = null;
        public byte[] payload = null;
        public byte[] signature = null;

        private RequestTuple(int id, String type, String channelID, byte[] payload, byte[] signature) {

            this.id = id;
            this.type = type;
            this.channelID = channelID;
            this.payload = payload;
            this.signature = signature;
        }
        
    }
    
    public class ReplyTuple {
        
        public Common.Block block;
        public Common.Metadata[] metadata;
        public String channel;
        public boolean config;
        
        private ReplyTuple (Common.Block block, Common.Metadata[] metadata, String channel, boolean config) {
            this.block = block;
            this.metadata = metadata;
            this.channel = channel;
            this.config = config;
        }
    }
    
    public static RequestTuple getRequestTuple(int id, String type, String channelID, byte[] payload, byte[] signature){
            
        BFTCommon t = new BFTCommon();
        RequestTuple r = t.new RequestTuple(id, type, channelID, payload, signature);
        return r;
    }
    
    public static BFTTuple getBFTTuple(Common.Block block, MessageContext msgCtx, int sequence, String channelID, boolean config, MSPManager clonedManager) {
            
        BFTCommon t = new BFTCommon();
        BFTTuple r = t.new BFTTuple(block, msgCtx, sequence, channelID, config, clonedManager);
        return r;
    }
    
    public static ReplyTuple getReplyTuple(Common.Block block, Common.Metadata[] metadata, String channel, boolean config) {
        
        BFTCommon t = new BFTCommon();
        ReplyTuple r = t.new ReplyTuple(block, metadata, channel, config);
        return r;
    }
    
    public static void init(CryptoPrimitives crypt) throws NoSuchAlgorithmException, NoSuchProviderException{
        
        crypto = crypt;
        logger = LoggerFactory.getLogger(Common.class);
    }
    
    public static String getBFTSMaRtConfigDir(String envVar) {
        
        String configDir = BFTCommon.DEFAULT_CONFIG_DIR;

        String envDir = System.getenv(envVar);
                
        if (envDir != null) {
            
            File path = new File(envDir);
            if (path.exists() && path.isDirectory()) configDir = envDir;
        }
        
        if (!configDir.endsWith("/")) configDir = configDir + "/";
        
        return configDir;
        
    }
    
    public static Duration parseDuration(String s) {
        
        return Duration.parse("PT"+s.trim().replaceAll("\\s+",""));
    }
    
    public static BFTCommon.RequestTuple deserializeRequest(byte[] request) throws IOException {
        
        ByteArrayInputStream bis = new ByteArrayInputStream(request);
        DataInput in = new DataInputStream(bis);
        
        int id = in.readInt();
        String type = in.readUTF();
        String channelID = in.readUTF();
        int l = in.readInt();
        byte[] payload = new byte[l];
        in.readFully(payload);
      
        bis.close();
        
        return BFTCommon.getRequestTuple(id, type, channelID, payload, null);
        
    }
            
    public static BFTCommon.RequestTuple deserializeSignedRequest(byte[] request) throws IOException {
        
        ByteArrayInputStream bis = new ByteArrayInputStream(request);
        DataInput in = new DataInputStream(bis);
        
        int l = in.readInt();
        byte[] msg = new byte[l];
        in.readFully(msg);
        l = in.readInt();
        byte[] sig = new byte[l];
        in.readFully(sig);
        
        bis.close();
        
        bis = new ByteArrayInputStream(msg);
        in = new DataInputStream(bis);
        
        int id = in.readInt();
        String type = in.readUTF();
        String channelID = in.readUTF();
        l = in.readInt();
        byte[] payload = new byte[l];
        in.readFully(payload);
      
        bis.close();
        
        return BFTCommon.getRequestTuple(id, type, channelID, payload, sig);
        
    }
    
    public static byte[] serializeRequest(int id, String type, String channelID, byte[] payload) throws IOException {
            
        ByteArrayOutputStream bos = new ByteArrayOutputStream(type.length() + channelID.length() + payload.length);
        DataOutput out = new DataOutputStream(bos);

        out.writeInt(id);
        out.writeUTF(type);
        out.writeUTF(channelID);
        out.writeInt(payload.length);
        out.write(payload);

        bos.flush();

        bos.close();
        
        return bos.toByteArray();
    }
    
    public static byte[] assembleSignedRequest(PrivateKey key, int id, String type, String channelID, byte[] payload) throws IOException {
                        
        ByteArrayOutputStream bos = new ByteArrayOutputStream(type.length() + channelID.length() + payload.length);
        DataOutput out = new DataOutputStream(bos);

        out.writeInt(id);
        out.writeUTF(type);
        out.writeUTF(channelID);
        out.writeInt(payload.length);
        out.write(payload);

        bos.flush();
        bos.close();

        byte[] msg = bos.toByteArray();
        
        byte[] sig = TOMUtil.signMessage(key, msg);
        
        bos = new ByteArrayOutputStream(msg.length+sig.length);
        out = new DataOutputStream(bos);
        
        out.writeInt(msg.length);
        out.write(msg);
        out.writeInt(sig.length);
        out.write(sig);
        
        bos.flush();
        bos.close();
        
        return bos.toByteArray();
    }
    
    public static Common.Block createNextBlock(long number, byte[] previousHash, byte[][] envs) throws NoSuchAlgorithmException, NoSuchProviderException, BFTException {

        if (crypto == null) throw new BFTException("No CryptoPrimitive object suplied");

        //initialize
        Common.BlockHeader.Builder blockHeaderBuilder = Common.BlockHeader.newBuilder();
        Common.BlockData.Builder blockDataBuilder = Common.BlockData.newBuilder();
        Common.BlockMetadata.Builder blockMetadataBuilder = Common.BlockMetadata.newBuilder();
        Common.Block.Builder blockBuilder = Common.Block.newBuilder();

        //create header
        blockHeaderBuilder.setNumber(number);
        if (previousHash != null) blockHeaderBuilder.setPreviousHash(ByteString.copyFrom(previousHash));
        blockHeaderBuilder.setDataHash(ByteString.copyFrom(crypto.hash(BFTCommon.concatenate(envs))));

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

    public static Common.SignatureHeader createSignatureHeader(byte[] creator, byte[] nonce) {
        
        Common.SignatureHeader.Builder signatureHeaderBuilder = Common.SignatureHeader.newBuilder();

        signatureHeaderBuilder.setCreator(ByteString.copyFrom(creator));
        signatureHeaderBuilder.setNonce(ByteString.copyFrom(nonce));
        
        return signatureHeaderBuilder.build();
    }
    
    public static Common.Metadata createMetadataSignature(PrivateKey privKey, byte[] creator, byte[] nonce, byte[] plaintext, Common.BlockHeader blockHeader) throws NoSuchAlgorithmException, NoSuchProviderException, InvalidKeyException, SignatureException, IOException, CryptoException, BFTException {

        if (crypto == null) throw new BFTException("No CryptoPrimitive object suplied");

        Common.Metadata.Builder metadataBuilder = Common.Metadata.newBuilder();
        Common.MetadataSignature.Builder metadataSignatureBuilder = Common.MetadataSignature.newBuilder();
        
        Common.SignatureHeader sigHeader = createSignatureHeader(creator, nonce);

        metadataSignatureBuilder.setSignatureHeader(sigHeader.toByteString());

        byte[][] concat = {plaintext, sigHeader.toByteString().toByteArray(), encodeBlockHeaderASN1(blockHeader)};

        //byte[] sig = sign(concatenate(concat));
        byte[] sig = crypto.sign(privKey, BFTCommon.concatenate(concat));

        logger.debug("Signature for block #" + blockHeader.getNumber() + ": " + Arrays.toString(sig) + "\n");

        //parseSig(sig);
        metadataSignatureBuilder.setSignature(ByteString.copyFrom(sig));

        metadataBuilder.setValue((plaintext != null ? ByteString.copyFrom(plaintext) : ByteString.EMPTY));
        metadataBuilder.addSignatures(metadataSignatureBuilder);

        return metadataBuilder.build();
    }
    
    public static byte[] encodeBlockHeaderASN1(Common.BlockHeader header) throws IOException {

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
        
    public static String extractMSPID(byte[] bytes) {
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
    
    public static Set<MspConfig.FabricMSPConfig> extractFabricMSPConfigs(Set<String> orgs, Map<String, Configtx.ConfigGroup> groups) throws InvalidProtocolBufferException {
        
        Set<MspConfig.FabricMSPConfig> msps = new HashSet<>();
        
        for (String org : orgs) {
            
            Configtx.ConfigGroup group  = groups.get(org);
            //Configtx.ConfigGroup group  = groups.get(OrdererGroupKey).getGroupsMap().get(org);
            Configtx.ConfigValue value = group.getValuesMap().get("MSP");
            MspConfig.MSPConfig mspConf = MspConfig.MSPConfig.parseFrom(value.getValue());
            MspConfig.FabricMSPConfig fabMspConf = MspConfig.FabricMSPConfig.parseFrom(mspConf.getConfig());

            msps.add(fabMspConf);
        }
        
        return msps;
    }
    
    public static long extractTimestamp(Common.Payload payload) throws InvalidProtocolBufferException {
        
        Common.ChannelHeader header = Common.ChannelHeader.parseFrom(payload.getHeader().getChannelHeader());

        return Instant.ofEpochSecond(header.getTimestamp().getSeconds()).plusNanos(header.getTimestamp().getNanos()).toEpochMilli();
                        
    }
    
    
    public static Configuration.BatchSize extractBachSize(Configtx.Config conf) throws InvalidProtocolBufferException {
        
        Map<String,Configtx.ConfigGroup> groups = conf.getChannelGroup().getGroupsMap();

        return Configuration.BatchSize.parseFrom(groups.get("Orderer").getValuesMap().get("BatchSize").getValue());

        
    }
    
    public static Configtx.ConfigEnvelope extractConfigEnvelope(Common.Block block) throws InvalidProtocolBufferException {
        
        Common.Envelope env = Common.Envelope.parseFrom(block.getData().getData(0));
        Common.Payload payload = Common.Payload.parseFrom(env.getPayload());
        Common.ChannelHeader chanHeader = Common.ChannelHeader.parseFrom(payload.getHeader().getChannelHeader());

        if (chanHeader.getType() == Common.HeaderType.CONFIG_VALUE) {

            return Configtx.ConfigEnvelope.parseFrom(payload.getData());


        }
        else return null;
        
    }
    
    public static Configtx.ConfigEnvelope makeConfigEnvelope (Configtx.Config config, Common.Envelope lastUpdate) {
    
        Configtx.ConfigEnvelope.Builder newConfEnvBuilder = Configtx.ConfigEnvelope.newBuilder();
        newConfEnvBuilder.setConfig(config);
        newConfEnvBuilder.setLastUpdate(lastUpdate);
        return newConfEnvBuilder.build();
    }
    
    public static Common.Envelope makeUnsignedEnvelope(ByteString dataMsg, ByteString sigHeader, Common.HeaderType headerType, int version, String chainID, long epoch, long timestamp) throws CryptoException {
        
        //if (crypto == null) throw new BFTException("No CryptoPrimitive object suplied");

        Common.ChannelHeader chanHeader = makeChannelHeader(headerType, version, chainID, epoch, timestamp);
        //Common.SignatureHeader sigHeader = createSignatureHeader(creator, nonce);
        
        Common.Header.Builder header = Common.Header.newBuilder();
        header.setChannelHeader(chanHeader.toByteString());
        //header.setSignatureHeader(sigHeader.toByteString());
        header.setSignatureHeader(sigHeader);
        
        Common.Payload.Builder payload = Common.Payload.newBuilder();
        payload.setData(dataMsg);
        payload.setHeader(header);
        
        byte[] bytes = payload.build().toByteArray();
        
        //byte[] sig = crypto.sign(privKey, bytes);
        
        Common.Envelope.Builder env = Common.Envelope.newBuilder();
        env.setPayload(ByteString.copyFrom(bytes));
        //env.setSignature(ByteString.copyFrom(sig));
        env.setSignature(ByteString.EMPTY);
        
        return env.build();
    }
    
    public static Common.ChannelHeader makeChannelHeader(Common.HeaderType headerType, int version, String chainID, long epoch, long timestamp) {
        
        Timestamp.Builder ts = Timestamp.newBuilder();
        ts.setSeconds(timestamp / 1000);
        ts.setNanos(0);

        Common.ChannelHeader.Builder result = Common.ChannelHeader.newBuilder();
        result.setType(headerType.getNumber());
        result.setVersion(version);
        result.setEpoch(epoch);
        result.setChannelId(chainID);
        result.setTimestamp(ts);
        result.setTlsCertHash(ByteString.EMPTY);
        
        return result.build();
    }
                            
    public static boolean verifyFrontendSignature(int id, PublicKey key, RequestTuple tuple) {
        
        try {
            
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutput out = new DataOutputStream(bos);
            
            out.writeInt(tuple.id);
            out.writeUTF(tuple.type);
            out.writeUTF(tuple.channelID);
            out.writeInt(tuple.payload.length);
            out.write(tuple.payload);
            
            bos.flush();
            bos.close();
            
            return TOMUtil.verifySignature(key, bos.toByteArray(), tuple.signature);
            
        } catch (IOException ex) {
            logger.error("Failed to verify frontend signature", ex);
            return false;
        }
    }
    
    public static Set<X509Certificate> selectSelfSigned(Set<X509Certificate> roots ) {
        
        // Try to verify root certificate signature with its own public key
        Set<X509Certificate> selfSigned = new HashSet<>();

        for (X509Certificate root : roots) {

            PublicKey key = root.getPublicKey();

            try {
                root.verify(key);
                selfSigned.add(root);
            } catch (CertificateException | NoSuchAlgorithmException | InvalidKeyException | NoSuchProviderException | SignatureException ex) {
                logger.error("Failed to select self-signed certificates", ex);
            }

        }

        return selfSigned;
    }
        
    public static Set<X509Certificate> extractCertificates(List<ByteString> list) throws CertificateException, IOException {
        
        Set<X509Certificate> certs = new HashSet<>();
        CertificateFactory certFactory = CertificateFactory.getInstance("X.509");

        for (ByteString bt : list) {
                
            InputStream in = new ByteArrayInputStream(bt.toByteArray());
            certs.add((X509Certificate)certFactory.generateCertificate(in));
            in.close();

        }
        
        return certs;

    }
    
    public static Map<String,Set<String>> cloneChanMSPs(Map<String,Set<String>> original) {
        
        Map<String,Set<String>> cloneMap = new TreeMap<>();
        Set<String> keys = original.keySet();
        
        keys.forEach((key) -> {
        
            Set<String> cloneSet = new HashSet<>();
            Set<String> originalSet = original.get(key);
            
            originalSet.forEach((msp) -> {
                
                cloneSet.add(msp);
                
            });
            
            cloneMap.put(key, cloneSet);
            
        });
        
        
        return cloneMap;
        
    }
 
    public static byte[] concatenate(byte[][] bytes) {

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
    
    public static PrivateKey getPemPrivateKey(String filename) throws IOException {

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
    
    //public static X509CertificateHolder getCertificate(String filename) throws IOException {
    public static X509Certificate getCertificate(String filename) throws IOException, CertificateException {

        /*BufferedReader br = new BufferedReader(new FileReader(filename));
        PEMParser pp = new PEMParser(br);
        X509CertificateHolder ret = (X509CertificateHolder) pp.readObject();
        
        br.close();
        pp.close();*/
        
        File file = new File(filename);
        InputStream is = new FileInputStream(file);
        return getCertificate(IOUtils.toByteArray(is));
        
        
    }
    
    public static X509Certificate getCertificate(byte[] serializedCert) throws IOException, CertificateException {
        
        CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
        InputStream in = new ByteArrayInputStream(serializedCert);

        X509Certificate ret = (X509Certificate)certFactory.generateCertificate(in);
        in.close();
        
        return ret;
    }
    
    public static byte[] getSerializedCertificate(X509Certificate certificate) throws IOException, CertificateEncodingException {
        
        PemObject pemObj = (new PemObject("", certificate.getEncoded()));

        StringWriter strWriter = new StringWriter();
        PemWriter writer = new PemWriter(strWriter);
        writer.writeObject(pemObj);

        writer.close();
        strWriter.close();

        return strWriter.toString().getBytes();
        
        /*Base64 base64 = new Base64();
        StringWriter strWriter = new StringWriter();
        
        strWriter.write(X509Factory.BEGIN_CERT);
        strWriter.write(base64.encodeAsString(certificate.getEncoded()));
        strWriter.write(X509Factory.END_CERT);
        
        return strWriter.toString().getBytes();*/
    }

    public static Identities.SerializedIdentity getSerializedIdentity(String Mspid, byte[] serializedCert) {

        Identities.SerializedIdentity.Builder ident = Identities.SerializedIdentity.newBuilder();
        ident.setMspid(Mspid);
        ident.setIdBytes(ByteString.copyFrom(serializedCert));
        return ident.build();
    }

    public static byte[] serializeContents(byte[][] contents) throws IOException {

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

    public static byte[][] deserializeContents(byte[] bytes) throws IOException {
        
        byte[][] batch = null;
        
        ByteArrayInputStream bis = new ByteArrayInputStream(bytes);
        DataInputStream in = new DataInputStream(bis);
        int nContents =  in.readInt();
        batch = new byte[nContents][];
        
        for (int i = 0; i < nContents; i++) {
            
            int length = in.readInt();

            batch[i] = new byte[length];
            in.read(batch[i]);
        }
        in.close();
        bis.close();
 
        
        return batch;
    }
    
    public static PKIXCertPathBuilderResult verifyCertificate(X509Certificate cert, Set<X509Certificate> trustedRootCerts,
        Set<X509Certificate> intermediateCerts, Date date) throws GeneralSecurityException {
         
        // Create the selector that specifies the starting certificate
        X509CertSelector selector = new X509CertSelector(); 
        selector.setCertificate(cert);
         
        // Create the trust anchors (set of root CA certificates)
        Set<TrustAnchor> trustAnchors = new HashSet<TrustAnchor>();
        for (X509Certificate trustedRootCert : trustedRootCerts) {
            trustAnchors.add(new TrustAnchor(trustedRootCert, null));
        }
         
        // Configure the PKIX certificate builder algorithm parameters
        PKIXBuilderParameters pkixParams = 
            new PKIXBuilderParameters(trustAnchors, selector);
         
        // Disable CRL checks (this is done manually as additional step)
        pkixParams.setRevocationEnabled(false);
        pkixParams.setSigProvider("BC");
        pkixParams.setDate(date); // necessary for determinism
     
        // Specify a list of intermediate certificates
        CertStore intermediateCertStore = CertStore.getInstance("Collection",
            new CollectionCertStoreParameters(intermediateCerts),"BC");
        pkixParams.addCertStore(intermediateCertStore);
     
        // Build and verify the certification chain
        CertPathBuilder builder = CertPathBuilder.getInstance("PKIX","BC");
        PKIXCertPathBuilderResult result = 
            (PKIXCertPathBuilderResult) builder.build(pkixParams);

        return result;
    }
    
    public static byte[] getCertificationChainIdentifierFromChain(String hashFunction, List<X509Certificate> path) throws BFTException {
            
        try{
            
            MessageDigest digestEngine = MessageDigest.getInstance(hashFunction, "BC");
            
            for (X509Certificate cert : path) {
                
                digestEngine.update(cert.getEncoded());
            }
            
            return digestEngine.digest();
        
        } catch (Exception ex) {
            
            throw new BFTCommon.BFTException("Failed to compute certificate chain ID for certificate chain: " + ex.getMessage());

        }
    }
    
    public static String[] extractOUsFronCertificate(X509Certificate cert) throws BFTException {

        LinkedList<String> OUs = new LinkedList<>();

        try {
            X500Name x500name = new JcaX509CertificateHolder(cert).getSubject();

            for (RDN rdn : x500name.getRDNs(BCStyle.OU)) {

                for (AttributeTypeAndValue atv : rdn.getTypesAndValues()) {

                    OUs.add(IETFUtils.valueToString(atv.getValue()));
                }
            }

        } catch (Exception ex) {

            throw new BFTException("Error fetching OUs from certificate: " + ex.getMessage());
        }

        String[] result = new String[OUs.size()];
        OUs.toArray(result);

        return result;

    }
    
    public static boolean verifySignature(X509Certificate cert, byte[] payload, byte[] signature) throws NoSuchAlgorithmException, NoSuchProviderException, InvalidKeyException, SignatureException, BFTCommon.BFTException {
                    
        Signature sigEngine = Signature.getInstance(cert.getSigAlgName(), "BC");
        sigEngine.initVerify(cert.getPublicKey());
        sigEngine.update(payload);
            
        return sigEngine.verify(signature);
    }
    
    public static byte[] hash(byte[] bytes, String algorithm) throws NoSuchAlgorithmException, NoSuchProviderException {
        
        MessageDigest digestEngine = MessageDigest.getInstance(algorithm,"BC");
                
        return digestEngine.digest(bytes);
    }
    
    public static Date toDate(long timestamp){
        
        return Date.from(Instant.ofEpochMilli(timestamp));
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
    
}

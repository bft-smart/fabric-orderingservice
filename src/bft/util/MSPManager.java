/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package bft.util;

import bft.util.BFTCommon.BFTException;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectInputStream;
import java.io.ObjectOutput;
import java.io.ObjectOutputStream;
import java.security.InvalidKeyException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.SignatureException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.PKIXCertPathBuilderResult;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.apache.commons.codec.binary.Hex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.hyperledger.fabric.protos.common.Common;
import org.hyperledger.fabric.protos.common.Configtx;
import org.hyperledger.fabric.protos.common.Configuration;
import org.hyperledger.fabric.protos.common.MspPrincipal;
import org.hyperledger.fabric.protos.common.Policies;
import org.hyperledger.fabric.protos.msp.Identities;
import org.hyperledger.fabric.protos.msp.MspConfig;
import org.hyperledger.fabric.sdk.exception.CryptoException;

/**
 *
 * @author joao
 */
public class MSPManager {
    
    private static Logger logger;
    //internal state
    private String mspid;
    private String sysChannel;
            
    //fundamental for the state transfer protocol
    private Map<String, Configtx.ConfigEnvelope> chanConfigs = new TreeMap<>(); 
    private Map<String, Long> lastConfigs = new TreeMap<>();
    // these two are fundamental because requie the state machine timestamp everytime they are updated
    private Map<String, OUIdentifier> clientOUs = new TreeMap<>();
    private Map<String, OUIdentifier> peerOUs = new TreeMap<>();
    
    //not necessary for state transfer, but useful for channel management and authentication
    private Map<String,MspConfig.FabricMSPConfig> MSPs = new TreeMap<>();
    private Map<String,Set<String>> chanMSPs = new TreeMap<>();
    private Map<String,Policy> chanWriters = new TreeMap<>();
    private Map<String,Set<X509Certificate>> rootCerts = new TreeMap<>();
    private Map<String,Set<X509Certificate>> intermediateCerts = new TreeMap<>();
    private Map<String,Set<X509Certificate>> adminCerts = new TreeMap<>();
    private Map<String,Set<X509Certificate>> revokedCerts = new TreeMap<>();
    private Map<String,Map<X509Certificate,List<X509Certificate>>> validatedCerts = new TreeMap<>();

    //constants related to channel reconfiguration
    private static final String rootGroupKey = "Channel";
    
    private static final String groupPrefix  = "[Group]  ";
    private static final String valuePrefix  = "[Value]  ";
    private static final String policyPrefix = "[Policy] ";
    private static final String pathSeparator = "/";
    
    private static final String consortiumKey = "Consortium";
    private static final String consortiumsGroupKey = "Consortiums";
    private static final String channelCreationPolicyKey = "ChannelCreationPolicy";
    private static final String applicationGroupKey = "Application";
    private static final String ordererGroupKey = "Orderer";
    private static final String adminsPolicyKey = "Admins";
        
    // Hacky fix constants, used in recurseConfigMap
    private static final String  hackyFixOrdererCapabilities = "[Value]  /Channel/Orderer/Capabilities";
    private static final String  hackyFixNewModPolicy        = "Admins";
    
    private class ConfigItem {
        
        Configtx.ConfigGroup group;
        Configtx.ConfigPolicy policy;
        Configtx.ConfigValue value = null;
        String key = null;
        String[] path = null;
        
        ConfigItem(Configtx.ConfigGroup group, String key, String[] path) {
            
            this.group = group;
            this.key = key;
            this.path = path;
        }
        
        ConfigItem(Configtx.ConfigPolicy policy, String key, String[] path) {
            
            this.policy = policy;
            this.key = key;
            this.path = path;
        }
        
        ConfigItem(Configtx.ConfigValue value, String key, String[] path) {
            
            this.value = value;
            this.key = key;
            this.path = path;
        }
        
        long getVersion() {
            
            if (this.group != null) return group.getVersion();
            else if (this.policy != null) return policy.getVersion();
            else return this.value.getVersion();
        }
        
        String getModPolicy() {
            
            if (this.group != null) return group.getModPolicy();
            else if (this.policy != null) return policy.getModPolicy();
            else return this.value.getModPolicy();
        }
    }
    
    public class SignedData {
        
        byte[] data;
        byte[] identity;
        byte[] signature;
        
        SignedData(byte[] data, byte[] identity, byte[] signature) {
            
            this.data = data;
            this.identity = identity;
            this.signature = signature;
        }
    }
    
    public abstract class Policy {
        
        protected abstract BFTCommon.BFTException evaluate(SignedData[] signedData, boolean[] used, String channel, long timestamp);
        public abstract String getPath();
        
        public void evaluate(SignedData[] signedData, String channel, long timestamp) throws BFTCommon.BFTException {
            
            logger.info("Evaluating policy " + getPath());
            
            boolean[] used = new boolean[signedData.length];
            Arrays.fill(used, Boolean.FALSE);
            BFTException ex = evaluate(deduplicate(channel, signedData), used, channel, timestamp);
            if (ex != null) {
                
                logger.info("Evaluation of policy " + getPath() + " failed: " + ex.getLocalizedMessage());
                throw ex;
            }
            
            logger.info("Evaluation of policy " + getPath() + " succeeded");
            
        }
        
        private SignedData[] deduplicate(String channel, SignedData[] signedData) {
            
            Set<String> ids = new HashSet<>();
            LinkedList<SignedData> deduplicated = new LinkedList<>();
            
            for (SignedData sd : signedData) {
                
                Identity id = null;
                
                try {
                    id = deserializeIdentity(channel, sd.identity);
                } catch (Exception ex) {
                    
                    logger.debug("Failed to deserialize principal during deduplication: " + ex.getMessage());
                    continue;
                }
                
                String key = (id.id+id.msp);
                
                if (ids.contains(key)) {
                    
                    logger.debug("De-duplicating identity " + id + " in signature set");
                } else {
                    
                    deduplicated.add(sd);
                    ids.add(key);
                }
            }
            
            SignedData[] result = new SignedData[deduplicated.size()];
            deduplicated.toArray(result);
            return result;
        }
    }
    
    class Identity {
        
            
        String msp;
        String id;
        String channel;
        X509Certificate certificate;
        byte[] serializedCert;
        
        Identity(String msp, String id,String channel, X509Certificate certificate, byte[] serializedCert) {
            
            this.msp = msp;
            this.id = id;
            this.channel = channel;
            this.certificate = certificate;
            this.serializedCert = serializedCert;
        }

        @Override
        public int hashCode() {
            int hash = 7;
            hash = 31 * hash + this.msp.hashCode();
            hash = 31 * hash + this.id.hashCode();
            hash = 31 * hash + this.channel.hashCode();
            return hash;
        }

        @Override
        public boolean equals(Object o) {

            if (this == o) return true;
            if (o == null) return false;
            if (this.getClass() != o.getClass()) return false;
            Identity i = (Identity) o;
            return this.id.equals(i.id) && this.msp.equals(i.msp) && 
                    this.channel.equals(i.channel) && Arrays.equals(this.serializedCert, i.serializedCert);
        }
        
        @Override
        public String toString(){
            
            return "["+this.id + ":" + this.msp+"]";
        }
        
        public boolean verify(byte[] data, byte[] signature) throws CertificateEncodingException, CryptoException, NoSuchAlgorithmException, NoSuchProviderException, InvalidKeyException, SignatureException, BFTException, KeyStoreException {
           
            
            //Fabric v1.1 implementation fetches the signature hash family and supplies it to the hashing function, but the
            //implemetation of the hashing function ignores that option when it type to compute the hash. Nonetheless, X509
            //certificates have information about the signature algorithm, hence we use those.
            
            //String sigHashFamily = MSPs.get(this.msp).getCryptoConfig().getSignatureHashFamily();
            //String defaultFamily = crypto.getProperties().getProperty(Config.SIGNATURE_ALGORITHM);

            return BFTCommon.verifySignature(this.certificate, data, signature);              
            
        }
        
        private List<X509Certificate> validate(long timestamp) throws BFTException {
            
            logger.debug("Verifying certificate chain for identity "+ this);
            
            List<X509Certificate> certPath = verifyCertificate(this.certificate,this.msp,timestamp);
            
            if (MSPs.get(this.msp).getFabricNodeOus().getEnable()) {
                            
                logger.debug("Fetching OU identifiers for identity " + this);

                OUIdentifier[] OUIDs = getOrganizationalUnits(certPath);

                logger.debug("Obtained the following OU identifiers for identity " +this+": " + Arrays.deepToString(OUIDs));

                int counter = 0;

                for (OUIdentifier OU : OUIDs) {

                    OUIdentifier nodeOU = null;

                    if (OU.organizationalUnitIdentifier.equals(clientOUs.get(this.msp).organizationalUnitIdentifier)) {

                        nodeOU = clientOUs.get(this.msp);
                    }

                    else if (OU.organizationalUnitIdentifier.equals(peerOUs.get(this.msp).organizationalUnitIdentifier)) {

                        nodeOU = peerOUs.get(this.msp);
                    }

                    else {
                        continue;
                    }

                    if (nodeOU.certifiersIdentifier.length != 0 && !Arrays.equals(nodeOU.certifiersIdentifier, OU.certifiersIdentifier)) {

                            throw new BFTCommon.BFTException("CID does not match: OUs: " + Arrays.deepToString(OUIDs) +", MSP: " + this.msp);
                    }

                    counter++;

                    if (counter > 1) {
                            break;
                    }

                }

                if (counter != 1) {

                    String msg = null;

                    if (counter > 0) msg = "Identity " + this + " must be a client, a peer or an orderer identity to be valid, not a combination of them.";
                    else msg = "Identity " + this + " does not have a OU matching the identifier for either a client or a peer of the MSP.";

                    throw new BFTCommon.BFTException(msg + " OUs: " + Arrays.deepToString(OUIDs) +", MSP: " + this.msp);
                }
            
            }
                        
            return certPath;
        }
        
        private void parseRoleMember(MspPrincipal.MSPPrincipal principal, long timestamp) throws BFTException {
            
            logger.debug("Parsing MSP principal "+ principal + " as role member");
            
            validate(timestamp);
            
        }
        
        private void parseRoleAdmin(MspPrincipal.MSPPrincipal principal, long timestamp) throws BFTException {
            
            logger.debug("Parsing MSP principal "+ principal + " as role admin");
            
            if (adminCerts.get(this.msp).contains(this.certificate)) {
            
                validate(timestamp);
            
            } else throw new BFTCommon.BFTException("Identity " + this + " is not an admin");
            

        }
        
        private void parseRolePeer(MspPrincipal.MSPPrincipal principal, MspPrincipal.MSPRole mspRole, long timestamp) throws BFTException {
            
            logger.debug("Parsing MSP principal "+ principal + " as role peer");
            
            List<X509Certificate> certPath = validate(timestamp);
                                    
            if (MSPs.get(this.msp).getFabricNodeOus().getEnable()) {
            
                String nodeOUValue = null;
    
                switch (mspRole.getRole()) {
                    
                    case CLIENT:
                        
                        nodeOUValue = clientOUs.get(this.msp).organizationalUnitIdentifier;
                        break;
                        
                    case PEER:
                        
                        nodeOUValue = peerOUs.get(this.msp).organizationalUnitIdentifier;
                        break;
                        
                    default:
                        
                        throw new BFTCommon.BFTException("Invalid MSPRoleType. It must be CLIENT, PEER or ORDERER");
                }
                
                OUIdentifier[] OUs = getOrganizationalUnits(certPath);
                
                for (OUIdentifier OU : OUs) {
                    
                    if (OU.organizationalUnitIdentifier.equals(nodeOUValue)) return;
                }
                
                throw new BFTCommon.BFTException("Identity "+ this +" does not contain OU " + nodeOUValue);
                
            }
        }
        
        private void parseIdentity(String channel, MspPrincipal.MSPPrincipal principal, long timestamp) throws BFTException {
            
            logger.debug("Parsing MSP principal "+ principal + " as identity");
            
            Identity identPrincipal = null;
            try {
                identPrincipal = deserializeIdentity(channel, principal.getPrincipal().toByteArray());
            } catch (Exception ex) {
                
                throw new BFTCommon.BFTException("Unable to deserialize principal's identity: " + ex.getMessage());
            }
            
            if (Arrays.equals(this.serializedCert, identPrincipal.serializedCert)) {
            
                identPrincipal.validate(timestamp);
                
            } else throw new BFTCommon.BFTException("Principal's identity does not match with identity " + this);
        }
        
        private void parseOU(MspPrincipal.MSPPrincipal principal, long timestamp) throws BFTException {
            
            logger.debug("Parsing MSP principal "+ principal + " as OU");
            
            MspPrincipal.OrganizationUnit OU = null;
            
            try {
                OU = MspPrincipal.OrganizationUnit.parseFrom(principal.getPrincipal());
            } catch (InvalidProtocolBufferException ex) {
                
                throw new BFTCommon.BFTException("Could not parse OrganizationUnit from principal " + principal);
            }
            
            if (!OU.getMspIdentifier().equals(this.msp))
                throw new BFTCommon.BFTException("Identity " + this + "is a member of a different MSP (expected " + this.msp + ", got " + OU.getMspIdentifier());
            
            
            List<X509Certificate> certPath = validate(timestamp);
            
            OUIdentifier[] OUs = getOrganizationalUnits(certPath);
            
            for (OUIdentifier ou : OUs) {
                
                if (ou.organizationalUnitIdentifier.equals(OU.getOrganizationalUnitIdentifier()) &&
                        Arrays.equals(ou.certifiersIdentifier, OU.getCertifiersIdentifier().toByteArray())) {
                    
                    return;
                }
                
            }
            throw new BFTCommon.BFTException("The identities do not match");
 
        }
        
        void satisfiesPrincipal(MspPrincipal.MSPPrincipal principal, long timestamp) throws BFTException {
            
            MspPrincipal.MSPPrincipal[] principals = collectPrincipals(principal);
            
            for (MspPrincipal.MSPPrincipal p : principals) {
                
                satisfiesPrincipalInternal(p, timestamp);
            }
        }
        
        private MspPrincipal.MSPPrincipal[] collectPrincipals(MspPrincipal.MSPPrincipal principal) throws BFTException {
            
            switch(principal.getPrincipalClassification()) {
                
                case COMBINED:
                    
                    try {

                        MspPrincipal.CombinedPrincipal principals = MspPrincipal.CombinedPrincipal.parseFrom(principal.getPrincipal());
                        
                        if (principals.getPrincipalsCount() == 0) {
                            
                            throw new BFTCommon.BFTException("No principals in CombinedPrincipal");
                        }
                        
                        LinkedList principalsSlice = new LinkedList();
                        
                        for (MspPrincipal.MSPPrincipal cp : principals.getPrincipalsList()) {
                            
                            MspPrincipal.MSPPrincipal[] internalSlice = collectPrincipals(cp);
                            principalsSlice.addAll(Arrays.asList(internalSlice));
                        }
                        
                        MspPrincipal.MSPPrincipal[] result = new MspPrincipal.MSPPrincipal[principalsSlice.size()];
                        principalsSlice.toArray(result);
                        return result;

                    } catch (InvalidProtocolBufferException ex) {

                        throw new BFTCommon.BFTException("Unable to parse principal: " + ex.getMessage());
                    }
                                
                default:
                    
                    return new MspPrincipal.MSPPrincipal[] { principal };
            }
        }
        
        private void satisfiesPrincipalInternal(MspPrincipal.MSPPrincipal principal, long timestamp) throws BFTException {
            
            switch (principal.getPrincipalClassification()){
                
                
                case COMBINED:
                
                    throw new BFTCommon.BFTException("SatisfiesPrincipalInternal can not be called with a CombinedPrincipal");
                    
                case ANONYMITY:

                    try {
                        
                        MspPrincipal.MSPIdentityAnonymity anon = MspPrincipal.MSPIdentityAnonymity.parseFrom(principal.getPrincipal());
                        
                        switch (anon.getAnonymityType()) {
                            
                            case ANONYMOUS:
                                
                                throw new BFTCommon.BFTException("Principal is anonymous, but X.509 MSP does not support anonymous identities");
                                
                            case NOMINAL:
                                
                                //Fabric v1.2 performs no validation in this case, so neither will we
                                
                                break;
                                
                            default:
                                
                                throw new BFTCommon.BFTException("Unknown principal anonymity type: " + anon.getAnonymityType());
                        }
                        
                    } catch (InvalidProtocolBufferException ex) {
                        
                        throw new BFTCommon.BFTException("Unable to parse principal: " + ex.getMessage());
                    }
            
                    break;
                    
                case ROLE:
                    
                    MspPrincipal.MSPRole mspRole = null;
            
                    try {
                        mspRole = MspPrincipal.MSPRole.parseFrom(principal.getPrincipal());
                    } catch (InvalidProtocolBufferException ex) {
                        throw new BFTCommon.BFTException("Unable to parse principal: " + ex.getMessage());
                    
                    }
                    
                    logger.debug("Checking identity MSP agaisnt principal MSP (expected "+ this.msp +", got "+ mspRole.getMspIdentifier() +")");
                    
                    if (!mspRole.getMspIdentifier().equals(this.msp)) 
                        throw new BFTCommon.BFTException("Identity is a member of a different MSP (expected " + this.msp + ", got " + mspRole.getMspIdentifier());
                    
                    switch (mspRole.getRole()){
                        
                        case MEMBER:
                            
                            parseRoleMember(principal, timestamp);
                            break;
                            
                        case ADMIN:
                            
                            parseRoleAdmin(principal, timestamp);
                            break;
                            
                        case CLIENT: //same verifications for both cases
                        case PEER:
                            
                            parseRolePeer(principal, mspRole, timestamp);
                            break;
                            
                        default:
                            
                            throw new BFTCommon.BFTException("Invalid MSP role type: "+ mspRole.getRole());
                    }
                    
                    
                    break;
                    
                case IDENTITY:
                
                    parseIdentity(this.channel, principal, timestamp);
                    break;
                    
                case ORGANIZATION_UNIT:
                    
                    parseOU(principal, timestamp);
                    break;
                    
                default:
                    
                    throw new BFTCommon.BFTException("Invalid principal type: "+ principal.getPrincipalClassification());
            }
            
        }
        
        private OUIdentifier[] getOrganizationalUnits(List<X509Certificate> certPath) throws BFTException {
                        
            byte[] cid = BFTCommon.getCertificationChainIdentifierFromChain(
                        MSPs.get(this.msp).getCryptoConfig().getIdentityIdentifierHashFunction(), certPath);
            
            String[] OUs = BFTCommon.extractOUsFronCertificate(this.certificate);
                        
            OUIdentifier[] result = new OUIdentifier[OUs.length];
            
            for (int i = 0; i < OUs.length; i++) {
                
                result[i] = new OUIdentifier(cid, OUs[i]);
                
            }
            
            return result;
            
            
        }
    }
    
    private MSPManager() {
        
    }
    
    private MSPManager(String mspid, String sysChannel) throws NoSuchAlgorithmException, NoSuchProviderException {
        
        this.mspid = mspid;
        this.sysChannel = sysChannel;
        
        logger = LoggerFactory.getLogger(MSPManager.class);

    }
    
    public static MSPManager getInstance(String mspid, String sysChannel) throws NoSuchAlgorithmException, NoSuchProviderException {
        
        return new MSPManager(mspid, sysChannel);
        
        
    }
    
    public Map<String, Configtx.ConfigEnvelope> getChanConfigs() {
        
        return chanConfigs;
    }
    
    public Configtx.ConfigEnvelope getChanConfig(String channel) {
        
        return chanConfigs.get(channel);
    }
    
    public Map<String, Long> getLastConfigs() {
        
        return lastConfigs;
    }
    
    public Long getLastConfig(String channel) {
        
        return lastConfigs.get(channel);
    }
        
    public byte[] serialize() throws IOException {
        
        //serialize channel configs
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        ObjectOutput o = new ObjectOutputStream(b);   
        o.writeObject(chanConfigs);
        o.flush();
        byte[] chanConfs = b.toByteArray();
        b.close();
        o.close();
        
        //serialize channel last configs
        b = new ByteArrayOutputStream();
        o = new ObjectOutputStream(b);
        o.writeObject(lastConfigs);
        o.flush();
        byte[] lastConfs = b.toByteArray();
        b.close();
        o.close();
            
        //serialize clients IO identifiers
        b = new ByteArrayOutputStream();
        o = new ObjectOutputStream(b);
        o.writeObject(clientOUs);
        o.flush();
        byte[] cOUs = b.toByteArray();
        b.close();
        o.close();
        
        //serialize peers IO identifiers
        b = new ByteArrayOutputStream();
        o = new ObjectOutputStream(b);
        o.writeObject(peerOUs);
        o.flush();
        byte[] pOUs = b.toByteArray();
        b.close();
        o.close();
        
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
                
        out.writeInt(chanConfs.length);
        out.write(chanConfs);

        out.flush();
        bos.flush();
        
        out.writeInt(lastConfs.length);
        out.write(lastConfs);

        out.flush();
        bos.flush();

        out.writeInt(cOUs.length);
        out.write(cOUs);

        out.flush();
        bos.flush();
        
        out.writeInt(pOUs.length);
        out.write(pOUs);

        out.flush();
        bos.flush();
            
        out.close();
        bos.close();
        return bos.toByteArray();
        
    }
    
    public static MSPManager deserialize(String mspid, String sysChannel, byte[] bytes) throws CertificateException, IOException, ClassNotFoundException, NoSuchAlgorithmException, NoSuchProviderException, BFTException {
        

        MSPManager instance = getInstance(mspid, sysChannel);
        
        ByteArrayInputStream bis = new ByteArrayInputStream(bytes);
        DataInputStream in = new DataInputStream(bis);
                
        byte[] chanConfs = new byte[0];
        int n = in.readInt();

        if (n > 0) {

            chanConfs = new byte[n];
            in.read(chanConfs);

        }
        
        byte[] lastConfs = new byte[0];
        n = in.readInt();

        if (n > 0) {

            lastConfs = new byte[n];
            in.read(lastConfs);

        }
        
        byte[] cOUs = new byte[0];
        n = in.readInt();

        if (n > 0) {

            cOUs = new byte[n];
            in.read(cOUs);

        }
        
        byte[] pOUs = new byte[0];
        n = in.readInt();

        if (n > 0) {

            pOUs = new byte[n];
            in.read(pOUs);

        }
        
        in.close();
        bis.close();
        
        ByteArrayInputStream b = new ByteArrayInputStream(chanConfs);
        ObjectInput i = null;
            
        i = new ObjectInputStream(b);
        instance.chanConfigs = (Map<String,Configtx.ConfigEnvelope>) i.readObject();
        i.close();
        b.close();
        
        b = new ByteArrayInputStream(lastConfs);
        i = new ObjectInputStream(b);
        instance.lastConfigs = (Map<String,Long>) i.readObject();
        i.close();
        b.close();
        
        b = new ByteArrayInputStream(cOUs);
        i = new ObjectInputStream(b);
        instance.clientOUs = (Map<String,OUIdentifier>) i.readObject();
        i.close();
        b.close();
        
        b = new ByteArrayInputStream(pOUs);
        i = new ObjectInputStream(b);
        instance.peerOUs = (Map<String,OUIdentifier>) i.readObject();
        i.close();
        b.close();
        
        instance.setup();
    
        return instance;
    }
    
    private void setup() throws CertificateException, IOException, BFTException {
        
        this.MSPs = new TreeMap<>();
        this.chanWriters = new TreeMap<>();
        this.adminCerts = new TreeMap<>();
        this.revokedCerts = new TreeMap<>();
        this.intermediateCerts = new TreeMap<>();
        this.rootCerts = new TreeMap<>();
        this.validatedCerts = new TreeMap<>();
                
            
        Set<String> chans = this.chanConfigs.keySet();

        for (String chan : chans) {

            Configtx.ConfigEnvelope conf = this.chanConfigs.get(chan);

            Set<MspConfig.FabricMSPConfig> msps = extractMSPs(conf.getConfig(), chan.equals(sysChannel));

            Set<String> mspIDs = new TreeSet<>();

            for (MspConfig.FabricMSPConfig msp : msps) {

                mspIDs.add(msp.getName());

                MSPs.put(msp.getName(), msp);
                adminCerts.put(msp.getName(), BFTCommon.extractCertificates(msp.getAdminsList()));
                revokedCerts.put(msp.getName(), BFTCommon.extractCertificates(msp.getRevocationListList()));
                intermediateCerts.put(msp.getName(), BFTCommon.extractCertificates(msp.getIntermediateCertsList()));               
                rootCerts.put(msp.getName(), BFTCommon.selectSelfSigned(BFTCommon.extractCertificates(msp.getRootCertsList())));
                validatedCerts.put(msp.getName(), new HashMap<>());

            }
            
            Map<String,ConfigItem> confMap = configGroupToConfigMap(conf.getConfig().getChannelGroup(), rootGroupKey);
            
            String path = policyPrefix + pathSeparator + rootGroupKey;
                        
            Policy policy = parsePolicy(chan, path, path + pathSeparator + "Writers", "Writers", confMap);

            chanWriters.put(chan, policy);
            
            chanMSPs.put(chan, mspIDs);

        }        
        
    }
    
    public void newChannel(String channelID, long sequence, Configtx.ConfigEnvelope conf, long timestamp) throws BFTException  {

        Set<MspConfig.FabricMSPConfig> msps = extractMSPs(conf.getConfig(), channelID.equals(sysChannel));
        Set<String> mspIDs = new TreeSet<>();
        
        try {
            
            for (MspConfig.FabricMSPConfig msp : msps) {

                mspIDs.add(msp.getName());

                this.MSPs.put(msp.getName(), msp);
                this.adminCerts.put(msp.getName(), BFTCommon.extractCertificates(msp.getAdminsList()));
                this.revokedCerts.put(msp.getName(), BFTCommon.extractCertificates(msp.getRevocationListList()));
                this.intermediateCerts.put(msp.getName(), BFTCommon.extractCertificates(msp.getIntermediateCertsList()));
                this.rootCerts.put(msp.getName(), BFTCommon.selectSelfSigned(BFTCommon.extractCertificates(msp.getRootCertsList())));
                this.validatedCerts.put(msp.getName(), new HashMap<>());

                for (X509Certificate adminCert : this.adminCerts.get(msp.getName())) {

                    logger.debug("Verifying admin certificate for channel " + channelID + " and MSP " + msp.getName());
                    
                    this.verifyCertificate(adminCert, msp.getName(), timestamp);

                }

                if (msp.getFabricNodeOus().getEnable()) {

                    logger.debug("Verifying client OUs for channel " + channelID + " and MSP " + msp.getName());
                    
                    X509Certificate cert = BFTCommon.getCertificate(msp.getFabricNodeOus().getClientOuIdentifier().getCertificate().toByteArray());
                    this.clientOUs.put(msp.getName(), new OUIdentifier(this.getCertifiersIdentifier(msp.getName(), cert, timestamp), 
                            msp.getFabricNodeOus().getClientOuIdentifier().getOrganizationalUnitIdentifier()));

                    logger.debug("Verifying peer OUs for channel " + channelID + " and MSP " + msp.getName());
                    
                    cert = BFTCommon.getCertificate(msp.getFabricNodeOus().getPeerOuIdentifier().getCertificate().toByteArray());
                    this.peerOUs.put(msp.getName(), new OUIdentifier(this.getCertifiersIdentifier(msp.getName(), cert, timestamp), 
                            msp.getFabricNodeOus().getPeerOuIdentifier().getOrganizationalUnitIdentifier()));

                }

            }
            
            Map<String,ConfigItem> confMap = configGroupToConfigMap(conf.getConfig().getChannelGroup(), rootGroupKey);
            
            String path = policyPrefix + pathSeparator + rootGroupKey;
                        
            Policy policy = parsePolicy(channelID, path, path + pathSeparator + "Writers", "Writers", confMap);

            this.chanWriters.put(channelID, policy);
        
        } catch (Exception ex) {
                    
            throw new BFTCommon.BFTException("Error while updating MSP manager: " + ex.getLocalizedMessage());
        }
        
        //at this point no error occured, it is safe to update the manager
        this.chanConfigs.put(channelID, conf);
        this.lastConfigs.put(channelID, sequence);
        this.chanMSPs.put(channelID, mspIDs);
        
    }
    
    public void updateChannel(String channel, long newSequence, Configtx.ConfigEnvelope newConfEnv, Configtx.Config newConfig, long timestamp) throws BFTException {
    
        Set<String> msps = chanMSPs.remove(channel); //clean MSPs and certificates that may no longer be associated to the channel
        
        try {
            newChannel(channel, newSequence, newConfEnv, timestamp);
        }
        catch (BFTCommon.BFTException ex) {
            
            chanMSPs.put(channel, msps); // if an exception occurs, cancel the clean up performed earlier
            throw ex;
            
        }
        
    }
    
    @Override
    public MSPManager clone() {
        
            MSPManager clone = new MSPManager();
            
            clone.mspid = mspid;
            clone.sysChannel = sysChannel;
            
            Map<String, Configtx.ConfigEnvelope> clonedConfs = new TreeMap<>();
            clonedConfs.putAll(chanConfigs);
            
            Map<String, Long> clonedLasts = new TreeMap<>();
            clonedLasts.putAll(lastConfigs);
            
            Map<String, OUIdentifier> clonedClientOUs = new TreeMap<>();
            clonedClientOUs.putAll(clientOUs);
                    
            Map<String, OUIdentifier> clonedPeerOUs = new TreeMap<>();
            clonedPeerOUs.putAll(peerOUs);
                                    
            Map<String,MspConfig.FabricMSPConfig> clonedMSPs = new TreeMap<>();
            clonedMSPs.putAll(MSPs);
            
            Map<String,Set<String>> clonedChanMSPs = new TreeMap<>();
            clonedChanMSPs.putAll(chanMSPs);
            
            Map<String,Set<X509Certificate>> clonedRootCerts = new TreeMap<>();
            clonedRootCerts.putAll(rootCerts);
            
            Map<String,Set<X509Certificate>> clonedIntermediateCerts = new TreeMap<>();
            clonedIntermediateCerts.putAll(intermediateCerts);
            
            Map<String,Set<X509Certificate>> clonedAdminCerts = new TreeMap<>();
            clonedAdminCerts.putAll(adminCerts);
            
            Map<String,Set<X509Certificate>> clonedRevokedCerts = new TreeMap<>();
            clonedRevokedCerts.putAll(revokedCerts);
            
            Map<String,Policy> clonedChanWriters = new TreeMap<>();
            clonedChanWriters.putAll(chanWriters);
    
            Map<String,Map<X509Certificate,List<X509Certificate>>> clonedValidatedCerts = new TreeMap<>();
            
            validatedCerts.forEach((msp, map) -> {
            
                Map<X509Certificate,List<X509Certificate>> clonedMap = new HashMap<>();
                
                clonedMap.putAll(map);
                clonedValidatedCerts.put(msp, clonedMap);
            });
                        
            clone.chanConfigs = clonedConfs;
            clone.lastConfigs = clonedLasts;
            clone.clientOUs = clonedClientOUs;
            clone.peerOUs = clonedPeerOUs;
            clone.MSPs = clonedMSPs;
            clone.chanMSPs = clonedChanMSPs;
            clone.rootCerts = clonedRootCerts;
            clone.intermediateCerts = clonedIntermediateCerts;
            clone.adminCerts = clonedAdminCerts;
            clone.revokedCerts = clonedRevokedCerts;
            clone.chanWriters = clonedChanWriters;
            clone.validatedCerts = clonedValidatedCerts;
            
            return clone;
    }
    
    public void validateEnvelope(Common.Envelope envelope, String channel, long timestamp, long timeWindow) throws BFTException {
                
        //TODO: Check the rest of validation performed by the filters at fabric/orderer/common/msgprocessor/standardchannel.go & systemchannel.go
        
        try {
            
            Common.Payload payload = Common.Payload.parseFrom(envelope.getPayload());
                   
            long tsClient = BFTCommon.extractTimestamp(payload);
                        
            if ((timestamp - tsClient) > timeWindow) 
                throw new BFTCommon.BFTException("Envelope is invalid: timestamp falls outside of the required time window");
                        
            Common.SignatureHeader sigHeader = Common.SignatureHeader.parseFrom(payload.getHeader().getSignatureHeader());
            
            SignedData sd = new SignedData(envelope.getPayload().toByteArray(),
                    sigHeader.getCreator().toByteArray(), envelope.getSignature().toByteArray());
                        
            chanWriters.get(channel).evaluate(new SignedData[]{ sd }, channel, timestamp);
            
        } catch (Exception ex) {
            
            if (!(ex instanceof BFTCommon.BFTException)) logger.error("Failed to validate envelope", ex);
                        
            throw new BFTCommon.BFTException("Envelope is invalid: " + ex.getLocalizedMessage());
            
        }
                
    }
    
    /*public boolean validateEnvelope(Common.Envelope envelope, String channel, long timestamp, long timeWindow) {
        
        //System.out.println("[verifier] Sender: " + sender);
        //System.out.println("[verifier] Proxies: " + receivers);

        try {
            //boolean envValidationActive = true; //temporary, option to be added to config file
            //boolean isControlCmd = receivers.contains(sender);

            //System.out.println("[verifier] isControlCmd: " + isControlCmd);
            
            // if this is a control request from the proxy, leave verification to the application
            //if (isControlCmd) return true;
                                    
            // if envelope validation is disabled and this is not a
            // configuration envelope, no need for validation
            //if (!(envValidation || isConfig)) return true;
            
            logger.debug("Deserialising  envelope");

            //Common.Envelope env = Common.Envelope.parseFrom(tuple.payload);
            Common.Payload payload = Common.Payload.parseFrom(envelope.getPayload());
                        
            Common.ChannelHeader header = Common.ChannelHeader.parseFrom(payload.getHeader().getChannelHeader());

            Common.SignatureHeader sigHeader = Common.SignatureHeader.parseFrom(payload.getHeader().getSignatureHeader());
            Identities.SerializedIdentity identity = Identities.SerializedIdentity.parseFrom(sigHeader.getCreator());                        

            long tsClient = Instant.ofEpochSecond(header.getTimestamp().getSeconds()).plusNanos(header.getTimestamp().getNanos()).toEpochMilli();
                        
            if ((timestamp - tsClient) > timeWindow) return false;

            logger.debug("Envelope is within the specified time window");
            
            //if (!ident.getMspid().equals(mspid)) return false;
            if (!(chanMSPs.get(channel) != null && chanMSPs.get(channel).contains(identity.getMspid()))) return false;
            
            logger.debug("Channel has the envelope's MSP");
            
            CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
            InputStream in = new ByteArrayInputStream(identity.getIdBytes().toByteArray());
            
            X509Certificate cert = (X509Certificate)certFactory.generateCertificate(in);
            in.close();
                        
            logger.debug("Pricipal name: "+cert.getSubjectX500Principal());

            //Set<X509Certificate> roots = new HashSet<>(rootCerts.get(ident.getMspid()));
            //Set<X509Certificate> intermediate = new HashSet<>(intermediateCerts.get(ident.getMspid()));
            
            // BouncyCastle provider needs the target certificate in the intermediate set
            Map<String,Set<X509Certificate>> intermediateClone = cloneMapCerts(intermediateCerts);
            intermediateClone.get(identity.getMspid()).add(cert); 
            
            if (revokedCerts.get(identity.getMspid()).contains(cert)) {
                
                logger.debug("Certificate is revoked");
                return false;
            }
            
            BFTCommon.verifyCertificate(cert, rootCerts.get(identity.getMspid()), intermediateClone.get(identity.getMspid()), Date.from(Instant.ofEpochMilli(timestamp))); 
            
            logger.debug("Certificate is valid");
            
            boolean valid = BFTCommon.verifySignature(cert, envelope.getPayload().toByteArray(), envelope.getSignature().toByteArray());
            
            if (valid) logger.debug("Signature is also valid");

            return valid;
            
        } catch (GeneralSecurityException | BFTCommon.BFTException | IOException ex) {
            
            ex.printStackTrace();
            return false; //verification failed for whatever reason, so envelope is invalid
        } 
        
    }*/

    public Configtx.Config newChannelConfig(String channel, Configtx.ConfigGroup readSet, Configtx.ConfigGroup writeSet) throws InvalidProtocolBufferException, BFTCommon.BFTException {
    
        if (!sysChannel.equals(channel)) throw new BFTCommon.BFTException("Trying to create new channel outside of the system channel");
        
        Configtx.Config sysChan = chanConfigs.get(channel).getConfig();
        
        Configtx.Config intermediate = intermediateConf(sysChan, writeSet);
        
        return generateNextConfig(0, intermediate, readSet, writeSet);
    }
    
    private Configtx.Config intermediateConf(Configtx.Config sysChan, Configtx.ConfigGroup writeSet) throws InvalidProtocolBufferException, BFTCommon.BFTException {
        
        Configtx.ConfigValue consortiumConfigValue = writeSet.getValuesMap().get(consortiumKey);
        
        Configuration.Consortium consortium = Configuration.Consortium.parseFrom(consortiumConfigValue.getValue());
                
        Configtx.ConfigGroup consortiums = sysChan.getChannelGroup().getGroupsMap().get(consortiumsGroupKey);
        
        Configtx.ConfigGroup consortiumConf = consortiums.getGroupsMap().get(consortium.getName());
        
        Policies.Policy policy = Policies.Policy.parseFrom(consortiumConf.getValuesMap().get(channelCreationPolicyKey).getValue());
        
        Configtx.ConfigPolicy.Builder confPolicy = Configtx.ConfigPolicy.newBuilder();
        
        confPolicy.setPolicy(policy);
        
        Configtx.ConfigGroup.Builder applicationGroup = Configtx.ConfigGroup.newBuilder();
        applicationGroup.putPolicies(channelCreationPolicyKey, confPolicy.build());
        applicationGroup.setModPolicy(channelCreationPolicyKey);
        
        // If the consortium group has no members, allow the source request to have no members.  However,
	// if the consortium group has any members, there must be at least one member in the source request
        if (consortiums.getGroupsMap().get(consortium.getName()).getGroupsMap().size() > 0 &&
            writeSet.getGroupsMap().get(applicationGroupKey).getGroupsMap().isEmpty()) {
            
            throw new BFTCommon.BFTException("Proposed configuration has no application group members, but consortium contains members");
            
        }
        
        // If the consortium has no members, allow the source request to contain arbitrary members
	// Otherwise, require that the supplied members are a subset of the consortium members
        if (consortiums.getGroupsMap().get(consortium.getName()).getGroupsMap().size() > 0) {
            
            Configtx.ConfigGroup cg = consortiums.getGroupsMap().get(consortium.getName());
            
            if (cg != null) {
                
                Set<String> orgs = writeSet.getGroupsMap().get(applicationGroupKey).getGroupsMap().keySet();

                for (String orgName : orgs) {

                    if (cg.getGroupsMap().get(orgName) == null) {

                        throw new BFTCommon.BFTException("Attempted to include a member which is not in the consortium");
                    }
                    
                    applicationGroup.putGroups(orgName, cg.getGroupsMap().get(orgName));
                }
            }
        }
        
        Configtx.ConfigGroup.Builder channelGroup = Configtx.ConfigGroup.newBuilder();
        
        // Copy the system channel Channel level config to the new config
        Set<String> keys = sysChan.getChannelGroup().getValuesMap().keySet();
        for (String key : keys) {
            
            if (!keys.equals(consortiumKey)) // Do not set the consortium name, we do this later
                channelGroup.putValues(key, sysChan.getChannelGroup().getValuesMap().get(key));
        }
        
        keys = sysChan.getChannelGroup().getPoliciesMap().keySet();
        for (String key : keys) {
            
            channelGroup.putPolicies(key, sysChan.getChannelGroup().getPoliciesMap().get(key));
        }
        
        // Set the new config orderer group to the system channel orderer group and the application group to the new application group
        channelGroup.putGroups(ordererGroupKey, sysChan.getChannelGroup().getGroupsMap().get(ordererGroupKey));
        channelGroup.putGroups(applicationGroupKey, applicationGroup.build());
        Configtx.ConfigValue.Builder confValue = Configtx.ConfigValue.newBuilder();
        confValue.setValue(consortium.toByteString());
        confValue.setModPolicy(adminsPolicyKey);
        channelGroup.putValues(consortiumKey, confValue.build());
        
	// Non-backwards compatible bugfix introduced in v1.1
	// The capability check should be removed once v1.0 is deprecated
        //JCS: I cannot implement this because the Capability protobuf is an empty structure that does not provide the PredictableChannelTemplate parameter
	/*if oc, ok := dt.support.OrdererConfig(); ok && oc.Capabilities().PredictableChannelTemplate() {
		channelGroup.ModPolicy = systemChannelGroup.ModPolicy
		zeroVersions(channelGroup)
	}*/
        
        return Configtx.Config.newBuilder().setChannelGroup(channelGroup).build();
    }
    
    
    private Map<String,ConfigItem> computeDeltaSet(Map<String,ConfigItem>readSet, Map<String,ConfigItem>writeSet) {
        
	Map<String,ConfigItem> result = new HashMap<>();
        Set<String> keys = writeSet.keySet();
        
	for (String key : keys) {
            
		ConfigItem readVal = readSet.get(key);

		/*if (readVal != null) {
                    
                    boolean sameVersion = false;
                    
                    if (readVal.group != null && writeSet.get(key).group != null)
                        sameVersion = readVal.group.getVersion() == writeSet.get(key).group.getVersion();
                    else if (readVal.value != null && writeSet.get(key).value != null)
                        sameVersion = readVal.value.getVersion() == writeSet.get(key).value.getVersion();
                    else if (readVal.policy != null && writeSet.get(key).policy != null)
                        sameVersion = readVal.policy.getVersion() == writeSet.get(key).policy.getVersion();
                    
                    if (sameVersion) continue;
                    
		}*/
                
                if (readVal != null && readVal.getVersion() == writeSet.get(key).getVersion()) continue;

		// If the key in the readset is a different version, we include it
		// Error checking on the sanity of the update is done against the config
		result.put(key, writeSet.get(key));
	}
	return result;
    }
    
    private void verifyDeltaSet(String channel, Map<String,ConfigItem> configMap, Map<String,ConfigItem> deltaSet, SignedData[] signedData, long timestamp) throws InvalidProtocolBufferException, BFTCommon.BFTException {
        
        Set<String> keys = deltaSet.keySet();
        
        for (String key : keys) {
            
            ConfigItem value = deltaSet.get(key);
            
            logger.debug("Processing change to key: " + key);
            
            ConfigItem existing = configMap.get(key);
            
            if (existing == null) {
                
                if (value.getVersion() != 0) {
                    
                        throw new BFTCommon.BFTException("Attempted to set key " + key + " to version " + value.getVersion() + ", but key does not exist");
                }

                continue;
            }
            
            
            if (value.getVersion() != (existing.getVersion()+1)) {
                
                throw new BFTCommon.BFTException("Attempt to set key " + key + " to version " + value.getVersion() + ", but key is at version " + existing.getVersion());
            }
            
            Policy policy = getPolicy(channel, configMap, existing);
            policy.evaluate(signedData, channel, timestamp);
            logger.debug("Policy "+policy+" is successfuly validated");
            
        }
        
    }
    
    private Policy getPolicy(String channel, Map<String,ConfigItem> confMap, ConfigItem existing) throws InvalidProtocolBufferException, BFTCommon.BFTException {
                
        String key = null;
        String path = policyPrefix;

        if (!existing.getModPolicy().startsWith(pathSeparator)) {

            for (String level : existing.path) {

                path = path + pathSeparator + level;
            }
            
            if (existing.group != null) path = path + pathSeparator + existing.key;

            key = path + pathSeparator  + existing.getModPolicy();

        }  else {

            key = path + existing.getModPolicy();
        }
        
        if (existing.getModPolicy().equals("")) throw new BFTCommon.BFTException("No policy for " + path);

        if (confMap.get(key) == null) throw new BFTCommon.BFTException(key + " : no such policy for " + path);

        logger.debug("Policy for: " + key + " is this: " + confMap.get(key).policy.getPolicy());
                
        return parsePolicy(channel, path, key, existing.getModPolicy(), confMap);
        
    }
    
    private Policy parsePolicy(String channel, String path, String key, String name, Map<String,ConfigItem> confMap) throws BFTException, InvalidProtocolBufferException {
        
        Policy result = null;
        
        int type = confMap.get(key).policy.getPolicy().getType();
        ByteString value = confMap.get(key).policy.getPolicy().getValue();
        
        switch (type) {
            
            case Policies.Policy.PolicyType.UNKNOWN_VALUE:
            
                throw new BFTCommon.BFTException("Unknown policy type for " + key);
            
            case Policies.Policy.PolicyType.IMPLICIT_META_VALUE:
                
                result = generateImplicitMetaPolicy(channel, Policies.ImplicitMetaPolicy.parseFrom(value), path.replace(policyPrefix, ""), name, confMap);
                break;
                    
            default:
                
                Policies.SignaturePolicyEnvelope spe = Policies.SignaturePolicyEnvelope.parseFrom(value);
                result = generateSignaturePolicy(channel, spe.getRule(), spe.getIdentitiesList(), path.replace(policyPrefix, ""),name);
        }
        
        
        return result;
    }
    
    private Policy generateImplicitMetaPolicy(String channel, Policies.ImplicitMetaPolicy imp, String path, String name, Map<String,ConfigItem> confMap) throws InvalidProtocolBufferException, BFTCommon.BFTException {
        
        Set<Policy> subPols = new HashSet<>();
        int depth = path.split("\\" + pathSeparator).length;
        Set<String> keys = confMap.keySet();
        
        for (String key: keys) {
            
            if (key.startsWith(groupPrefix + path) && key.split("\\" + pathSeparator).length == (depth + 1)) {
                
                String k = policyPrefix + key.replace(groupPrefix, "") + pathSeparator + imp.getSubPolicy();
                
                if (confMap.get(k) == null) {
                    
                    logger.debug(k + " : no such sub-policy for " + policyPrefix + path + pathSeparator + name);
                    continue;
                }
                
                Policies.Policy policy = confMap.get(k).policy.getPolicy();
                
                logger.debug("Sub-policy for " + policyPrefix + path + pathSeparator + name + " is at " + k + " and is this: " + confMap.get(k).policy.getPolicy());
                 
                int type = policy.getType();
                ByteString value = policy.getValue();

                switch (type) {

                    case Policies.Policy.PolicyType.UNKNOWN_VALUE:

                        throw new BFTCommon.BFTException("Unknown policy type for " + policyPrefix + path);

                    case Policies.Policy.PolicyType.IMPLICIT_META_VALUE:

                        subPols.add(generateImplicitMetaPolicy(channel, Policies.ImplicitMetaPolicy.parseFrom(value), key.replace(groupPrefix, ""), imp.getSubPolicy(), confMap));
                        break;

                    default:

                        Policies.SignaturePolicyEnvelope spe = Policies.SignaturePolicyEnvelope.parseFrom(value);
                        subPols.add(generateSignaturePolicy(channel, spe.getRule(), spe.getIdentitiesList(), key.replace(groupPrefix, ""), imp.getSubPolicy()));
                }
            }
        }
        
        Policy[] array = new Policy[subPols.size()];
        subPols.toArray(array);
        int th;
        
        switch (imp.getRuleValue()) {
            
            case Policies.ImplicitMetaPolicy.Rule.ANY_VALUE:
                    
                    th = 1;
                    break;
                    
            case Policies.ImplicitMetaPolicy.Rule.ALL_VALUE:
                    
                    th = subPols.size();
                    break;
                    
            case Policies.ImplicitMetaPolicy.Rule.MAJORITY_VALUE:
                    
                    th = (subPols.size()/2) + 1;
                    break;
                    
            default:
                throw new BFTCommon.BFTException("Unknown rule for " + policyPrefix + path);
        }
        
        return new Policy() {
            
            String id = path + pathSeparator + name;
            Policy[] subPolicies = array;
            int threshold = th;
            
            @Override
            protected BFTCommon.BFTException evaluate(SignedData[] signedData, boolean[] used, String channel, long timestamp) {
                
                logger.info("Evaluating " + subPolicies.length + " sub-policies from " + getPath());
                int completed = 0;
                
                for (Policy subPolicy : subPolicies) {
                    
                    logger.debug("Evaluating sub-policy " + subPolicy.getPath());
                    
                    try {
                        subPolicy.evaluate(signedData, channel, timestamp);
                        completed++;
                        
                        if (completed >= threshold) return null;
                        
                    }
                    catch (Exception e) {
                        
                        logger.debug("Evaluation of sub-policy " + subPolicy.getPath() + " from " + getPath() + " failed: " + e.getMessage());
                    }
                }
                
                boolean result = completed < threshold;
                String msg = ( result ? "Not enough sub-policies satisfied for " + getPath() + ". (Needed "+ threshold + " got "+ completed  + ")" : 
                        " Enough sub-policies satisfied for "+ getPath() +" (completed " + completed + " out of a minimum of " + threshold + ")");
                
                logger.debug(msg);
                
                return (result ?
                        new BFTCommon.BFTException(msg) : null);
            }
            
            @Override
            public String toString() {
                
                return "[ImplicitMetaPolicy:" + getPath() + ":(" + threshold + "/" + subPolicies.length + "):" + Arrays.deepToString(subPolicies) + "]";
            }
            
            @Override
            public String getPath() {
                
                return id;
            }
        };
        
    }
    private Policy generateSignaturePolicy(String channel, Policies.SignaturePolicy policy, List<MspPrincipal.MSPPrincipal> identities, String path, String name) throws BFTCommon.BFTException {
                
        
        Policy result = null;
                
        switch(policy.getTypeCase()) {
            
            case N_OUT_OF:
            
                Policy[] pols = new Policy[policy.getNOutOf().getRulesCount()];
                
                for (int i = 0; i < pols.length; i++) {
                    
                    Policies.SignaturePolicy p = policy.getNOutOf().getRules(i);
                    pols[i] = generateSignaturePolicy(channel, p, identities, path, name);
                }
                
                result = new Policy() {

                    Policy[] policies = pols;
                    String id = path + pathSeparator + name;

                    @Override
                    protected BFTCommon.BFTException evaluate(SignedData[] signedData, boolean[] used, String channel, long timestamp) {
                        
                        logger.info("Evaluating " + policies.length + " signature policies from " + getPath());
                        
                        int verified = 0;
                        boolean[] _used = null;
                        
                        for (Policy p : policies) {
                            
                            _used = Arrays.copyOf(used, used.length);
                            
                            BFTCommon.BFTException ex = p.evaluate(signedData, _used, channel, timestamp);
                            if (ex == null) {
                                
                                verified++;
                                used = Arrays.copyOf(_used, _used.length);
                                logger.debug("Evaluation for policy " + p.getPath() + " from " + getPath() + " suceeded");
                            }
                            else logger.debug("Evaluation for policy " + p.getPath() + " from " + getPath() + " failed with cause: " + ex.getMessage());
                            
                            
                        }
                        
                        boolean result = verified < policy.getNOutOf().getN();
                        String msg = ( result ? "Not enough signature policies satisfied for " + getPath() + ". (Needed "+ policy.getNOutOf().getN() + " got "+ verified  + ")" : 
                            " Enough signature policies satisfied for "+ getPath() +" (completed " + verified + " out of a minimum of " + policy.getNOutOf().getN() + ")");
               
                        logger.debug(msg);
                        
                        return (result ? new BFTCommon.BFTException(msg) : null);
                    }

                    @Override
                    public String toString() {

                        return "[SignaturePolicy:" + getPath() + "]";
                    }
                    
                    @Override
                    public String getPath() {

                        return id;
                    }
                    
                };
                break;
            case SIGNED_BY:
                
                if (policy.getSignedBy() < 0 || policy.getSignedBy() >= identities.size()) {
                    
			throw new BFTCommon.BFTException("Identity index out of range, requested " + policy.getSignedBy() + ", but identies length is " + identities.size());
		}
                
		MspPrincipal.MSPPrincipal signedByID = identities.get(policy.getSignedBy());
                
                result = new Policy() {
                    
                    String id = path + pathSeparator + name;
                    
                    @Override
                    protected BFTCommon.BFTException evaluate(SignedData[] signedData, boolean[] used, String channel, long timestamp) {
                                                
                        for (int i = 0; i < signedData.length; i++) {
                            
                            if (used[i]) {
                                logger.debug("Skipping " + i + "th identity from " + Arrays.deepToString(signedData) + " because it has already been used");
                                continue;
                            }
                            
                            Identity ident = null;
                            try {
                                ident = deserializeIdentity(channel, signedData[i].identity);
                                logger.debug("Successfully deserialized identity " + ident);
                                
                            } catch (Exception ex) {
                                logger.debug("Unable to deserialize identity #" + i + " :" + ex.getMessage());
                                continue;
                            }
                            
                            try {
                                
                                logger.debug("Verifying principal for identity "+ ident);
                                ident.satisfiesPrincipal(signedByID, timestamp);
                            } catch (Exception ex) {
                                logger.debug("Identity " + ident + " does not satisfies principal " + signedByID + ": " + ex.getMessage());
                                continue;
                            }

                            try {
                                
                                logger.debug("Verifying signature for identity "+ ident);
                                if (!ident.verify(signedData[i].data, signedData[i].signature))
                                    throw new BFTCommon.BFTException("Invalid signature");
                                
                            } catch (Exception ex) {
                                logger.debug("Signature verification for identity " + ident + " failed: " + ex.getMessage());
                                continue;
                            }
                            
                            logger.debug("Principal evaluation succeeded for identity " + ident + " with principal " + signedByID);

                            used[i] = true;
                            
                            //logger.debug("Evaluation for policy " + toString() + " succeeded");
                            return null;
                        }
                        
                        return new BFTCommon.BFTException("Principal evaluation failed for " + Arrays.deepToString(signedData));
                    }
                    
                    @Override
                    public String toString() {

                        return "[SignaturePolicy:" + id + "]";
                    }
                    
                    @Override
                    public String getPath() {

                        return id;
                    }
                };
                        
                break;
                
            default:
                
                throw new BFTCommon.BFTException("Unknown rule for [SignaturePolicy:" + path + pathSeparator + name + "]");
                
        }
        
        return result;
    }
    
    /*public boolean enoughAdminSigs(String channel, Configtx.ConfigUpdateEnvelope confEnv, long timestamp) throws InvalidProtocolBufferException {
              
        Set<String> ApplicationMSPs = new HashSet<>();
        Set<String> OrdererMSPs = new HashSet<>();
        
        Configtx.Config currentConf = chanConfigs.get(channel).getConfig();
        Set<String> msps = chanMSPs.get(channel);

        //Find what MSPs are associtated with the application
        Configtx.ConfigGroup confAppGroup = currentConf.getChannelGroup().getGroupsMap().get(ApplicationGroupKey);
        Configtx.ConfigGroup confOrdererGroup = currentConf.getChannelGroup().getGroupsMap().get(OrdererGroupKey);
                
        Set<String> orgs = confAppGroup.getGroupsMap().keySet();
        logger.debug("Application Orgs " + orgs);
            
        for (String org : orgs) {

            MspConfig.MSPConfig mspConf = MspConfig.MSPConfig.parseFrom(confAppGroup.getGroupsMap().get(org).getValuesMap().get("MSP").getValue());
            ApplicationMSPs.add(MspConfig.FabricMSPConfig.parseFrom(mspConf.getConfig()).getName());
        }

        //Find what MSPs are associtated with the orderer
        orgs = confOrdererGroup.getGroupsMap().keySet();
        logger.debug("Orderer Orgs " + orgs);
    
        
        for (String org : orgs) {

            MspConfig.MSPConfig mspConf = MspConfig.MSPConfig.parseFrom(confOrdererGroup.getGroupsMap().get(org).getValuesMap().get("MSP").getValue());
            OrdererMSPs.add(MspConfig.FabricMSPConfig.parseFrom(mspConf.getConfig()).getName());
        }
        
        logger.debug("Application MSPs " + ApplicationMSPs);
        logger.debug("Orderer MSPs " + OrdererMSPs);
        
        //validate signatures contained in ConfigUpdateEnvelope
        int validAppSigs = 0;
        int validOrdererSigs = 0;
        Set<Identities.SerializedIdentity> evaluatedIdents = new HashSet<>();
        Set<Identities.SerializedIdentity> validIdents = new HashSet<>();
                
        for (Configtx.ConfigSignature sig : confEnv.getSignaturesList()) {

            try {
                
                Common.SignatureHeader header = Common.SignatureHeader.parseFrom(sig.getSignatureHeader());
                Identities.SerializedIdentity identity = Identities.SerializedIdentity.parseFrom(header.getCreator());
                
                logger.debug("Deserialised signature header with ident " + identity.getIdBytes());

                if (evaluatedIdents.contains(identity)) continue; // this is a duplicated signature
                evaluatedIdents.add(identity);
                
                logger.debug("Did not fetch this ident yet");

                //if neither an application or orderer MSP, no point in validating it
                if (!ApplicationMSPs.contains(identity.getMspid()) && !OrdererMSPs.contains(identity.getMspid())) continue;
                    
                logger.debug("Ident is for an " + (ApplicationMSPs.contains(identity.getMspid()) ? "application" : "orderer") + " organization");
                        
                CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
                InputStream in = new ByteArrayInputStream(identity.getIdBytes().toByteArray());

                X509Certificate cert = (X509Certificate)certFactory.generateCertificate(in);
                in.close();
            
                if (revokedCerts.get(identity.getMspid()).contains(cert) ||
                        !msps.contains(identity.getMspid()) ||
                        !adminCerts.get(identity.getMspid()).contains(cert)) continue; // creator is not an admin
                
                logger.debug("Ident is an admin associated to this channel");

                // BouncyCastle provider needs the target certificate in the intermediate set
                Map<String,Set<X509Certificate>> intermediateClone = cloneMapCerts(intermediateCerts);
                intermediateClone.get(identity.getMspid()).add(cert);
                                                
                BFTCommon.verifyCertificate(cert, rootCerts.get(identity.getMspid()), intermediateClone.get(identity.getMspid()), Date.from(Instant.ofEpochMilli(timestamp)));

                logger.debug("Certificate of the ident is valid");
             
                byte[] plaintext = BFTCommon.concatenate(new byte[][]{ sig.getSignatureHeader().toByteArray() , confEnv.getConfigUpdate().toByteArray() });
                
                if (BFTCommon.verifyFabricSignature(cert.getPublicKey(), plaintext, sig.getSignature().toByteArray())) {
                    
                    logger.debug("Ident signature is also valid");
                                    
                    validIdents.add(identity);
                    
                    if (ApplicationMSPs.contains(identity.getMspid())) validAppSigs++;
                    if (OrdererMSPs.contains(identity.getMspid())) validOrdererSigs++;
                    if (!ApplicationMSPs.contains(identity.getMspid()) && !OrdererMSPs.contains(identity.getMspid()))
                        logger.debug("Ident is not associated to either the Application or the Orderer level");
                }

                
            } catch (InvalidProtocolBufferException | CertificateException | BFTCommon.BFTException ex) {
                Logger.getLogger(BFTNode.class.getName()).log(Level.SEVERE, null, ex);
            } catch (IOException | GeneralSecurityException ex) {
                Logger.getLogger(BFTNode.class.getName()).log(Level.SEVERE, null, ex);
            } 

        }
        
        //Perform Fabric's default policy checks as described in http://hyperledger-fabric.readthedocs.io/en/release-1.2/config_update.html#get-the-necessary-signatures
        Configtx.ConfigUpdate cu = Configtx.ConfigUpdate.parseFrom(confEnv.getConfigUpdate());
        
        Configtx.ConfigGroup writesetAppGroup = cu.getWriteSet().getGroupsMap().get(ApplicationGroupKey);
        Configtx.ConfigGroup writesetOrdererGroup = cu.getWriteSet().getGroupsMap().get(OrdererGroupKey);
        
        if (writesetAppGroup != null) {
            
            orgs = writesetAppGroup.getGroupsMap().keySet();
            
            for (String org : orgs) {
                
                int greaterThan = (confAppGroup.getGroupsMap().get(org) == null ? (int) Math.ceil(ApplicationMSPs.size() / 2) : 0);               
                logger.debug(greaterThan > 0 ? "Need app majority of org admins" : "Need app admin for org " + org);
                
                if (!(validAppSigs > greaterThan)) {
                    
                    logger.debug("Not enough app admins");
                    return false;
                }
            }
            
        }
                
        if (writesetOrdererGroup != null) {
            
            orgs = writesetOrdererGroup.getGroupsMap().keySet();
            
            for (String org : orgs) {
                
                int greaterThan = (confOrdererGroup.getGroupsMap().get(org) == null ? (int) Math.ceil(OrdererMSPs.size() / 2) : 0);
                logger.debug(greaterThan > 0 ? "Need orderer majority of org admins" : "Need orderer admin for org " + org);
                
                if (!(validOrdererSigs > greaterThan)) {
                    
                    logger.debug("Not enough orderer admins");
                    return false;
                }               
            }
        }
        
        if ((writesetAppGroup != null && (!writesetAppGroup.getValuesMap().keySet().isEmpty() || !writesetAppGroup.getPoliciesMap().keySet().isEmpty())) ||
                (writesetOrdererGroup != null && (!writesetOrdererGroup.getValuesMap().keySet().isEmpty() || !writesetOrdererGroup.getPoliciesMap().keySet().isEmpty()))) {
                
            int greaterThanApp = ((int) Math.ceil(ApplicationMSPs.size() / 2));
            int greaterThanOrderer = ((int) Math.ceil(OrdererMSPs.size() / 2));
            
            boolean result = ((validAppSigs > greaterThanApp) && (validOrdererSigs > greaterThanOrderer));
            if (!result) logger.debug("Not enough admins for channel group");
            
            return result;
        }

        logger.debug("Enough signatures in ConfigUpdateEnvelope");
        
        return true;
    }*/
    
    // computeUpdateResult takes a configMap generated by an update and produces a new configMap overlaying it onto the old config
    private Map<String,ConfigItem> computeNewConfigMap(Map<String,ConfigItem> oldConfigMap, Map<String,ConfigItem> deltaSet) {
        
        
	Map<String,ConfigItem> newConfigMap = new HashMap<>();
        
        Set<String> keys = oldConfigMap.keySet();
	for (String key : keys) {
		newConfigMap.put(key, oldConfigMap.get(key));
	}

        keys = deltaSet.keySet();
	for (String key : keys) {
		newConfigMap.put(key, deltaSet.get(key));
	}
	return newConfigMap;
    }

    // mapConfig is intended to be called outside this file
    // it takes a ConfigGroup and generates a map of fqPath to comparables (or error on invalid keys)
    private Map<String,ConfigItem> configGroupToConfigMap(Configtx.ConfigGroup channelGroup, String rootGroupKey) {
            
        Map<String,ConfigItem> result = new HashMap<>();
        
        if (channelGroup != null) {
            
            recurseConfig(result, new String[] {rootGroupKey}, channelGroup);

        }
        
        return result;
    }

    // recurseConfig is used only internally by mapConfig
    private void recurseConfig(Map<String,ConfigItem> result, String[] path, Configtx.ConfigGroup group) {
        
        ConfigItem c = new ConfigItem(group, path[path.length-1], Arrays.copyOfRange(path, 0, path.length-1));
	addToMap(c, result);

        Set<String> keys = group.getGroupsMap().keySet();
        for (String key : keys) {
            String[] nextPath = Arrays.copyOf(path, path.length+1);
            nextPath[nextPath.length-1] = key;
            recurseConfig(result, nextPath, group.getGroupsMap().get(key));
        }
        
        keys = group.getValuesMap().keySet();
        for (String key : keys) {
            
            c = new ConfigItem(group.getValuesMap().get(key), key, path);
            addToMap(c, result);
        }

        keys = group.getPoliciesMap().keySet();
        for (String key : keys) {
            
            c = new ConfigItem(group.getPoliciesMap().get(key), key, path);
            addToMap(c, result);
        }
        
    }

    // addToMap is used only internally by mapConfig
    private static void addToMap(ConfigItem cg, Map<String,ConfigItem> result) {
        
	String fqPath = "";

	
	if (cg.group != null) fqPath = groupPrefix;
        else if (cg.value != null) fqPath = valuePrefix;
        else if (cg.policy != null) fqPath = policyPrefix;
	

	//if err := validateConfigID(cg.key); err != nil {
	//	return fmt.Errorf("Illegal characters in key: %s", fqPath)
	//}

	if (cg.path.length == 0) {
		fqPath += pathSeparator + cg.key;
	} else {
		fqPath += pathSeparator + String.join(pathSeparator, cg.path) + pathSeparator + cg.key;
	}

	//logger.debug("Adding to config map: "+fqPath);

	result.put(fqPath, cg);

    }

    
    // configMapToConfig is intended to be called from outside this file
    // It takes a configMap and converts it back into a *cb.ConfigGroup structure
    private Configtx.ConfigGroup configMapToConfigGroup(Map<String,ConfigItem> configMap, String rootGroupKey) throws BFTCommon.BFTException {
        
	String rootPath = pathSeparator + rootGroupKey;
	return recurseConfigMap(rootPath, configMap);
    }
    
    // recurseConfigMap is used only internally by configMapToConfig
    // Note, this function no longer mutates the cb.Config* entries within configMap
    private Configtx.ConfigGroup recurseConfigMap(String path, Map<String,ConfigItem> configMap) throws BFTCommon.BFTException {
        
	String groupPath = groupPrefix + path;
        ConfigItem group = configMap.get(groupPath);
                
        if (group == null) {
            
            throw new BFTCommon.BFTException("Missing group at path: " + groupPath);
        }
        
                
        if (group.group == null) {
            
            throw new BFTCommon.BFTException("ConfigGroup not found at group path: " + groupPath);
        }
        
        Configtx.ConfigGroup.Builder newConfigGroup = Configtx.ConfigGroup.newBuilder(group.group);
        
        Map<String, Configtx.ConfigGroup> groups = new HashMap<>();

        Set<String> keys = group.group.getGroupsMap().keySet();
        
	for (String key : keys) {
            
		Configtx.ConfigGroup updatedGroup = recurseConfigMap(path+pathSeparator+key, configMap);

		groups.put(key,updatedGroup);
	}
        
        newConfigGroup.putAllGroups(groups);

        keys = group.group.getValuesMap().keySet();
        Map<String, Configtx.ConfigValue> values = new HashMap<>();
        
	for (String key : keys) {
		String valuePath = valuePrefix + path + pathSeparator + key;
                ConfigItem value = configMap.get(valuePath);
                
                if (value == null) {
            
                    throw new BFTCommon.BFTException("Missing value at path: " + groupPath);
                }


                if (value.value == null) {

                    throw new BFTCommon.BFTException("ConfigValue not found at value path: " + valuePath);
                }
        
                Configtx.ConfigValue.Builder clone = Configtx.ConfigValue.newBuilder(value.value);
                values.put(key, clone.build());
                
	}
        newConfigGroup.putAllValues(values);

        keys = group.group.getPoliciesMap().keySet();
        Map<String, Configtx.ConfigPolicy> policies = new HashMap<>();
        
	for (String key : keys) {
		String policyPath = policyPrefix + path + pathSeparator + key;
                ConfigItem policy = configMap.get(policyPath);
                
                if (policy == null) {
            
                    throw new BFTCommon.BFTException("Missing policy at path: " + policyPath);
                }


                if (policy.policy == null) {

                    throw new BFTCommon.BFTException("ConfigPolicy not found at policy path: " + policyPath);
                }
        
                Configtx.ConfigPolicy.Builder clone = Configtx.ConfigPolicy.newBuilder(policy.policy);
                policies.put(key, clone.build());
                
	}
        newConfigGroup.putAllPolicies(policies);

	// This is a really very hacky fix to facilitate upgrading channels which were constructed
	// using the channel generation from v1.0 with bugs FAB-5309, and FAB-6080.
	// In summary, these channels were constructed with a bug which left mod_policy unset in some cases.
	// If mod_policy is unset, it's impossible to modify the element, and current code disallows
	// unset mod_policy values.  This hack 'fixes' existing config with empty mod_policy values.
	// If the capabilities framework is on, it sets any unset mod_policy to 'Admins'.
	// This code needs to sit here until validation of v1.0 chains is deprecated from the codebase.
	if (configMap.get(hackyFixOrdererCapabilities) != null) {
            
		// Hacky fix constants, used in recurseConfigMap
		if (newConfigGroup.getModPolicy().equals("")) {
                    
			logger.debug("Performing upgrade of group "+groupPath+" empty mod_policy");
			newConfigGroup.setModPolicy(hackyFixNewModPolicy);
		}

                keys = newConfigGroup.getValuesMap().keySet();
		for (String key : keys) {
                    
			if (newConfigGroup.getValuesMap().get(key).getModPolicy().equals("")) {
                            
				logger.debug("Performing upgrade of value "+(valuePrefix+path+pathSeparator+key)+" empty mod_policy");
                                Configtx.ConfigValue.Builder builder = newConfigGroup.getValuesMap().get(key).toBuilder();
                                builder.setModPolicy(hackyFixNewModPolicy);
                                newConfigGroup.getValuesMap().put(key, builder.build());
			}
		}

		keys = newConfigGroup.getPoliciesMap().keySet();
		for (String key : keys) {
                    
			if (newConfigGroup.getPoliciesMap().get(key).getModPolicy().equals("")) {
                            
				logger.debug("Performing upgrade of value "+(valuePrefix+path+pathSeparator+key)+" empty mod_policy");
                                Configtx.ConfigPolicy.Builder builder = newConfigGroup.getPoliciesMap().get(key).toBuilder();
                                builder.setModPolicy(hackyFixNewModPolicy);
                                newConfigGroup.getPoliciesMap().put(key, builder.build());
			}
		}
	}

	return newConfigGroup.build();
    }

    public Configtx.Config generateNextConfig(String channel, Configtx.ConfigGroup readSet, Configtx.ConfigGroup writeSet) throws InvalidProtocolBufferException, BFTCommon.BFTException {
    
        Configtx.Config oldConf = chanConfigs.get(channel).getConfig();
        
        return generateNextConfig(oldConf.getSequence()+1, oldConf, readSet, writeSet);
    }
    
    private Configtx.Config generateNextConfig(long sequence, Configtx.Config oldConf, Configtx.ConfigGroup readSet, Configtx.ConfigGroup writeSet) throws InvalidProtocolBufferException, BFTCommon.BFTException {
        
        Map<String,ConfigItem> oldConfMap = configGroupToConfigMap(oldConf.getChannelGroup(), rootGroupKey);
        Map<String,ConfigItem> readSetMap = configGroupToConfigMap(readSet, rootGroupKey);
        Map<String,ConfigItem> writeSetMap = configGroupToConfigMap(writeSet, rootGroupKey);
        
        Map<String,ConfigItem> deltaSetMap = computeDeltaSet(readSetMap, writeSetMap);
                                
        Map<String,ConfigItem> newConfMap = computeNewConfigMap(oldConfMap, deltaSetMap);
                                
        Configtx.ConfigGroup channelGroup = configMapToConfigGroup(newConfMap, rootGroupKey);
        
        //Configtx.ConfigGroup channelGroup = generateNextGroup(oldConf.getChannelGroup(), readSet, writeSet);
         
        Configtx.Config.Builder newConf = Configtx.Config.newBuilder();
        newConf.setChannelGroup(channelGroup);
        newConf.setSequence(sequence);
        
        return newConf.build();
    }
    
    public void verifyPolicies(String channel, boolean newChannel,Configtx.ConfigGroup readSet, Configtx.ConfigGroup writeSet,
            Configtx.ConfigUpdateEnvelope confEnv, long timestamp) throws InvalidProtocolBufferException, BFTCommon.BFTException {

        Configtx.Config oldConf = chanConfigs.get(channel).getConfig();
                
        Configtx.Config intermediateConf = (newChannel ? intermediateConf(oldConf, writeSet) : oldConf);

        Map<String,ConfigItem> oldConfMap = configGroupToConfigMap(intermediateConf.getChannelGroup(), rootGroupKey);
        Map<String,ConfigItem> readSetMap = configGroupToConfigMap(readSet, rootGroupKey);
        Map<String,ConfigItem> writeSetMap = configGroupToConfigMap(writeSet, rootGroupKey);
                
        Map<String,ConfigItem> deltaSetMap = computeDeltaSet(readSetMap, writeSetMap);
                
        verifyDeltaSet(channel, oldConfMap, deltaSetMap, asSignedData(confEnv), timestamp);
                        
    }
        
    public Set<MspConfig.FabricMSPConfig> extractMSPs(Configtx.Config conf, boolean forSysChannel) {
        
        try {
            
            Set<MspConfig.FabricMSPConfig> msps = new HashSet<>();
            
            Map<String,Configtx.ConfigGroup> groups = conf.getChannelGroup().getGroupsMap();
            
            if (forSysChannel) {
                
                Set<String> consorts = groups.get(consortiumsGroupKey).getGroupsMap().keySet();
                
                for (String consort : consorts) {
                    
                    Set<String> orgs  = groups.get(consortiumsGroupKey).getGroupsMap().get(consort).getGroupsMap().keySet();
                                        
                    logger.debug("Organizations from " + consort + ": " + orgs);
                    
                    msps.addAll(BFTCommon.extractFabricMSPConfigs(orgs, groups.get(consortiumsGroupKey).getGroupsMap().get(consort).getGroupsMap()));

                }

                
            } else {
                
                Set<String> orgs = groups.get(applicationGroupKey).getGroupsMap().keySet();
                                
                logger.debug("Organizations from Applications: " + orgs);
                
                msps.addAll(BFTCommon.extractFabricMSPConfigs(orgs, groups.get(applicationGroupKey).getGroupsMap()));
                
            }
            
            Set<String> orgs = groups.get(ordererGroupKey).getGroupsMap().keySet();
                                
            logger.debug("Organizations from Orderer: " + orgs);
                
            msps.addAll(BFTCommon.extractFabricMSPConfigs(orgs, groups.get(ordererGroupKey).getGroupsMap()));
                
            return msps;

        } catch (InvalidProtocolBufferException ex) {
            
            logger.error("Failed to extract MSP configs", ex);
            return null;
        }

    }
        
    private static Map<String,Set<X509Certificate>> cloneMapCerts(Map<String,Set<X509Certificate>> original) {
        
        Map<String,Set<X509Certificate>> cloneMap = new TreeMap<>();
        Set<String> keys = original.keySet();
        
        keys.forEach((key) -> {
            Set<X509Certificate> cloneSet = new HashSet<>();
            Set<X509Certificate> originalSet = original.get(key);
            
            originalSet.forEach((cert) -> {
                
                cloneSet.add(cert);
                
            });
            cloneMap.put(key, cloneSet);
        });
        
        return cloneMap;
    }
        
    private SignedData[] asSignedData(Configtx.ConfigUpdateEnvelope confEnv) throws InvalidProtocolBufferException {

        SignedData[] result = new SignedData[confEnv.getSignaturesCount()];
        
        for (int i = 0; i < confEnv.getSignaturesCount(); i++) {
            
            Configtx.ConfigSignature configSig = confEnv.getSignatures(i);
            
            Common.SignatureHeader header = Common.SignatureHeader.parseFrom(configSig.getSignatureHeader());
            
            byte[] data = BFTCommon.concatenate(new byte[][] { configSig.getSignatureHeader().toByteArray(),confEnv.getConfigUpdate().toByteArray() });
            result[i] = new SignedData(data, header.getCreator().toByteArray(), configSig.getSignature().toByteArray());
        }
        
	return result;
    }
    
    private Identity deserializeIdentity(String channel, byte[] serializedIdentity) throws InvalidProtocolBufferException, IOException, CertificateException, BFTException, CryptoException, NoSuchAlgorithmException, NoSuchProviderException {

        Identities.SerializedIdentity ident = Identities.SerializedIdentity.parseFrom(serializedIdentity);
        
        if (!chanMSPs.get(channel).contains(ident.getMspid()))
            throw new BFTCommon.BFTException("Identity's MSP does not belong to channel " + channel);

        String hashFunction = MSPs.get(ident.getMspid()).getCryptoConfig().getIdentityIdentifierHashFunction();
        byte[] digest = BFTCommon.hash(ident.getIdBytes().toByteArray(), hashFunction);
        
        X509Certificate cert = BFTCommon.getCertificate(ident.getIdBytes().toByteArray());

        //TODO: sanitize certificate before creating object, check it at fabric/msp/mspimpl.go->sanitizeCert
        
        return new Identity (ident.getMspid(), Hex.encodeHexString(digest), channel, cert, ident.getIdBytes().toByteArray());

    }

    
    private List<X509Certificate> verifyCertificate(X509Certificate certificate, String mspid, long timestamp) throws BFTException{
        
        if (revokedCerts.get(mspid) != null && revokedCerts.get(mspid).contains(certificate)) {
            
            if (validatedCerts.get(mspid) != null) validatedCerts.get(mspid).remove(certificate);
            throw new BFTCommon.BFTException("Certificate " + certificate + " has been revoked");
        }
        
        if (validatedCerts.get(mspid) != null && validatedCerts.get(mspid).get(certificate) != null) {
            
            logger.debug("Certificate chain for "+ certificate + " was already validated, returning from cache");
            return validatedCerts.get(mspid).get(certificate);
        }
        
        
        // BouncyCastle provider needs the target certificate in the intermediate set
        Map<String,Set<X509Certificate>> intermediateClone = cloneMapCerts(intermediateCerts);
        intermediateClone.get(mspid).add(certificate);
            
        PKIXCertPathBuilderResult certResult = null;
            
        try { 
            certResult = BFTCommon.verifyCertificate(certificate, rootCerts.get(mspid), intermediateClone.get(mspid), BFTCommon.toDate(timestamp));
        } catch (Exception ex) {

            throw new BFTCommon.BFTException("Failed to verify certificate chain for identity: " + certificate + ": " + ex.getMessage());
        }

        logger.debug("Certificate chain for "+ certificate + " is valid, putting it in cache");

        List<X509Certificate> certPath = (List<X509Certificate>) certResult.getCertPath().getCertificates();
        if (certPath.get(0).equals(certificate)) {

            logger.debug("Removing identities certificate from certificate chain before computing path's CID");
            certPath = certPath.subList(1, certPath.size());

        }

        validatedCerts.get(mspid).put(certificate, certPath);
        return certPath;
    }
    
    private byte[] getCertifiersIdentifier(String mspid, X509Certificate cert, long timestamp) throws BFTException {
        
        //TODO: sanitize certificate before doing the rest, check it at fabric/msp/mspimpl.go->sanitizeCert
        
        boolean found = false;
        boolean root = false;
        
        if (this.rootCerts.get(mspid) != null && this.rootCerts.get(mspid).contains(cert)) {
            
            found = true;
            root = true;
        }
        
        if (!found) {
            
            if (this.intermediateCerts.get(mspid) != null && this.intermediateCerts.get(mspid).contains(cert)) {
            
                found = true;
            }
        }
        
        if (!found) throw new BFTCommon.BFTException("Certificate " + cert + " not in root or intermediate certs for MSP " + mspid);
        
        logger.debug("Verifying certificate before returning identifier");
        List<X509Certificate> certPath = verifyCertificate(cert,mspid,timestamp);
        
        return BFTCommon.getCertificationChainIdentifierFromChain(
                        MSPs.get(mspid).getCryptoConfig().getIdentityIdentifierHashFunction(), certPath);
    }
}

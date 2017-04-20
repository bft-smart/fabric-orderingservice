/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package bft;

import com.google.protobuf.ByteString;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.security.InvalidKeyException;
import java.security.KeyPair;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.PrivateKey;
import java.security.SignatureException;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.ASN1OutputStream;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemWriter;
import org.hyperledger.fabric.protos.common.Common;
import org.hyperledger.fabric.protos.msp.Identities;
import org.hyperledger.fabric.sdk.exception.CryptoException;
import org.hyperledger.fabric.sdk.exception.InvalidArgumentException;
import org.hyperledger.fabric.sdk.security.CryptoPrimitives;

import org.zeromq.ZMQ;
import org.zeromq.ZMQ.Context;
import org.zeromq.ZMQ.Socket;
/**
 *
 * @author joao
 */
public class TestSignaturesZMQ {
    
    private static CryptoPrimitives crypto;
    private static final int NUM_BATCHES = 1000;
    
    private static final String Mspid = "DEFAULT";

    private static Random rand;
    private static ExecutorService executor = null;
    private static PrivateKey privKey = null;
    private static byte[] serializedCert = null;
    private static Identities.SerializedIdentity ident;
    private static Context context;
    private static ConcurrentHashMap<String,Set<Common.Block>> toSign;
    
    //measurements
    private static long sigsMeasurementStartTime = -1;
    private static int interval;
    private static int countSigs = 0;
    
    public static void main(String[] args) throws CryptoException, InvalidArgumentException, NoSuchAlgorithmException, NoSuchProviderException, IOException, InterruptedException {
        
        
        if(args.length < 7) {
            System.out.println("Use: java TestSignatures <privKey> <certificate> <batch size> <envelope size> <two sigs?> <parallelism> <sig batch>");
            System.exit(-1);
        }  
        
        TestSignaturesZMQ.crypto = new CryptoPrimitives();
        TestSignaturesZMQ.crypto.init();
        TestSignaturesZMQ.rand = new Random(System.nanoTime());
        
        String privKey = args[0];
        String cert = args[1];
        
        int batchSize = Integer.parseInt(args[2]);
        int envSize =Integer.parseInt(args[3]);
        boolean twoSigs =  Boolean.parseBoolean(args[4]);
        int parallelism = Integer.parseInt(args[5]);
        int sigBatch = Integer.parseInt(args[6]);

        /*TestSignatures.executor = Executors.newFixedThreadPool(parallelism, (Runnable r) -> {
            Thread t = new Thread(r);
            t.setPriority(Thread.MAX_PRIORITY);
            return t;
        });*/
       
        /*TestSignatures.executor = Executors.newCachedThreadPool((Runnable r) -> {
            Thread t = new Thread(r);
            t.setPriority(Thread.MAX_PRIORITY);
            return t;
        });*/
        TestSignaturesZMQ.executor = Executors.newWorkStealingPool(parallelism);
        
        TestSignaturesZMQ.privKey = getPemPrivateKey(privKey);
        parseCertificate(cert);
        TestSignaturesZMQ.ident = getSerializedIdentity();
                
        interval = 100000;
        toSign = new ConcurrentHashMap<>();
        
        
        //ZMQ
        context = ZMQ.context(1);
        Socket broker = context.socket(ZMQ.ROUTER);
        //broker.bind("tcp://*:5671");
        broker.bind("inproc://pipeline");
                    
        //Generate pool of batches
        System.out.print("Generating " + NUM_BATCHES + " batches with " + batchSize + " envelopes each... ");
        byte[][][] batches = new byte[NUM_BATCHES][batchSize][];
        for (int i = 0; i < NUM_BATCHES; i++) {
            
            for (int j = 0; j < batchSize; j++) {
                
                batches[i][j] = new byte[envSize];
                
                rand.nextBytes(batches[i][j]);
                
                
            }
        }
        
        System.out.println(" done!");
        
        System.out.print("Generating batch pool of " + NUM_BATCHES + " blocks... ");
        
        Common.Block[] blocks = new Common.Block[NUM_BATCHES];
        for (int i = 0; i < NUM_BATCHES; i++) {
            
            byte[] dummyDigest = new byte[rand.nextInt(1000)];
            
            rand.nextBytes(dummyDigest);
                    
            dummyDigest = TestSignaturesZMQ.crypto.hash(dummyDigest);
            
            
            blocks[i] = createNextBlock(i, dummyDigest, batches[rand.nextInt(batches.length)]);
        }
        
        System.out.println(" done!");
        
        System.out.println("Generating signatures with a pool of " + NUM_BATCHES + " blocks... ");
                
        sigsMeasurementStartTime = System.currentTimeMillis();

        for (int i = 0 ; i < parallelism; i++) {

            SignerThread s = new SignerThread(twoSigs);
            
            TestSignaturesZMQ.executor.execute(s);

        }
                
        while (true) {
            
         
                            
           byte[] identity = broker.recv();
            //if (multiThread) {
           
            countSigs += sigBatch;

            if (countSigs % interval == 0) {

                float tp = (float) (interval * 1000 / (float) (System.currentTimeMillis() - sigsMeasurementStartTime));
                System.out.println("Throughput = " + tp + " sigs/sec");
                sigsMeasurementStartTime = System.currentTimeMillis();

            }
            
            broker.send(identity, ZMQ.SNDMORE);


            broker.recv(0);     //  Envelope delimiter


            broker.recv(0);     //  Response from worker


            broker.sendMore("");            
            
            Set<Common.Block> s = new HashSet<>();
            for (int i = 0; i < sigBatch; i++) {
                
                Common.Block.Builder block = blocks[rand.nextInt(NUM_BATCHES)].toBuilder();
                block.setHeader(blocks[rand.nextInt(NUM_BATCHES)].getHeader());
            
                s.add(block.build());
                
            }
            
            String key = String.format("%04X-%04X", rand.nextLong(), rand.nextLong());
            
            toSign.put(key, s);
                
            broker.send(key, 0);

            //}
            
        }
        
        
    }

    private static void parseCertificate(String filename) throws IOException {
                
            BufferedReader br = new BufferedReader(new FileReader(filename));
            PEMParser pp = new PEMParser(br);
            X509CertificateHolder certificate = (X509CertificateHolder) pp.readObject();

            PemObject pemObj = (new PemObject("", certificate.getEncoded()));

            StringWriter strWriter = new StringWriter();
            PemWriter writer = new PemWriter(strWriter);
            writer.writeObject(pemObj);
            
            writer.close();
            strWriter.close();
            
            TestSignaturesZMQ.serializedCert = strWriter.toString().getBytes();
            
            
    }
    private static Identities.SerializedIdentity getSerializedIdentity() {
        
                Identities.SerializedIdentity.Builder ident = Identities.SerializedIdentity.newBuilder();
                ident.setMspid(Mspid);
                ident.setIdBytes(ByteString.copyFrom(serializedCert));
                return ident.build();
    }
    
    private static PrivateKey getPemPrivateKey(String filename) throws IOException {
        
        
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
    
    private static Common.Block createNextBlock(long number, byte[] previousHash, byte[][] envs) throws NoSuchAlgorithmException, NoSuchProviderException {
        
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

    private static byte[] concatenate(byte[][] bytes) {

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

    private static class SignerThread implements Runnable {

        //private Common.Block block;        
        private boolean twoSigs;
        
        /*SignerThread(Common.Block block) {

            this.block = block;
            
        }*/
        
        SignerThread(boolean twoSigs) throws InterruptedException {

            this.twoSigs = twoSigs;
        }

        private byte[] encodeBlockHeaderASN1(Common.BlockHeader header) throws IOException {

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

            //parseSig(sig);

            metadataSignatureBuilder.setSignature(ByteString.copyFrom(sig));

            metadataBuilder.setValue((plaintext != null ? ByteString.copyFrom(plaintext) : ByteString.EMPTY));
            metadataBuilder.addSignatures(metadataSignatureBuilder);

            return metadataBuilder.build();
        }
        
        @Override
        public void run() {

            Socket worker = context.socket(ZMQ.DEALER);
            
            String identity = String.format("%04X-%04X", rand.nextInt(), rand.nextInt());
            
            worker.setIdentity(identity.getBytes());
            
            //worker.connect("tcp://localhost:5671");
            worker.connect("inproc://pipeline");
            
            while (true) {
                try {
                    
                   worker.send("", ZMQ.SNDMORE);
                   worker.send("Hi Boss");
                   
                   worker.recv(0); //delimeter
                                      

                   String key =  worker.recvStr(0, Charset.defaultCharset());
                     
                   Set<Common.Block> set = toSign.get(key);
                   //Common.Block block = Common.Block.parseFrom(bytes);
                   for (Common.Block block : set) {
                        //create nonce
                        byte[] nonces = new byte[rand.nextInt(10)];
                        rand.nextBytes(nonces);

                        //create signatures
                        Common.Metadata blockSig = createMetadataSignature(ident.toByteArray(), nonces, null, block.getHeader());


                        if (twoSigs) {

                            byte[] dummyConf = {0, 0, 0, 0, 0, 0, 0, 1}; //TODO: find a way to implement the check that is done in the golang code
                            Common.Metadata configSig = createMetadataSignature(ident.toByteArray(), nonces, dummyConf, block.getHeader());


                        }

                   }    
                    

                } catch (NoSuchAlgorithmException | NoSuchProviderException | InvalidKeyException | SignatureException | IOException | CryptoException ex) {
                    ex.printStackTrace();
                }
            }
        }

    }
}

/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package bft.util;

import bftsmart.tom.util.KeyLoader;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;

/**
 *
 * @author joao
 */
public class ECDSAKeyLoader implements KeyLoader {

    private String path;    
    private int myId;
    private String sigAlgorithm;
    
    public ECDSAKeyLoader(int myId, String configHome, String sigAlgorithm) {
        
        this.myId = myId;
        this.path = configHome + "keys" + System.getProperty("file.separator");
        this.sigAlgorithm = sigAlgorithm;
        
    }
       
    public X509Certificate loadCertificate() throws IOException, NoSuchAlgorithmException, InvalidKeySpecException, CertificateException {
        
        return loadCertificate(this.myId);
        
    }
    
    public X509Certificate loadCertificate(int id) throws IOException, NoSuchAlgorithmException, InvalidKeySpecException, CertificateException {
        
        return BFTCommon.getCertificate(path + "cert" + id + ".pem");
        
    }

    @Override
    public PublicKey loadPublicKey(int id) throws IOException, NoSuchAlgorithmException, InvalidKeySpecException, CertificateException {
        
        return loadCertificate(id).getPublicKey();
    }
    
    @Override
    public PublicKey loadPublicKey() throws IOException, NoSuchAlgorithmException, InvalidKeySpecException, CertificateException {
        
        return loadCertificate(this.myId).getPublicKey();
    }

    @Override
    public PrivateKey loadPrivateKey() throws IOException, NoSuchAlgorithmException, InvalidKeySpecException {
        
        return BFTCommon.getPemPrivateKey(path + "keystore.pem");
    }

    @Override
    public String getSignatureAlgorithm() {
        return this.sigAlgorithm;
    }
    
}

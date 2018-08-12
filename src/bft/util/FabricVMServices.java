/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package bft.util;

import bftsmart.reconfiguration.VMServices;
import bftsmart.tom.util.KeyLoader;

import java.security.Provider;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.hyperledger.fabric.sdk.exception.CryptoException;
import org.hyperledger.fabric.sdk.exception.InvalidArgumentException;
import org.hyperledger.fabric.sdk.helper.Config;
import org.hyperledger.fabric.sdk.security.CryptoPrimitives;

/**
 *
 * @author joao
 */
public class FabricVMServices extends VMServices  {

    private static KeyLoader keyLoader;
    private static Provider provider;

    
    public static void main(String[] args) throws InterruptedException, ClassNotFoundException, IllegalAccessException, InstantiationException, CryptoException, InvalidArgumentException {
            
        String configDir = BFTCommon.getBFTSMaRtConfigDir("RECONFIG_DIR");

        if (System.getProperty("logback.configurationFile") == null)
            System.setProperty("logback.configurationFile", configDir + "logback.xml");
        
        provider = new BouncyCastleProvider();
        
        CryptoPrimitives crypto = new CryptoPrimitives();
        crypto.init();
                
        if (args.length == 1) {
            
            System.out.println("####Tpp Service[Disjoint]####");
            
            int id = Integer.parseInt(args[0]);
            
            keyLoader = new ECDSAKeyLoader(id, configDir, crypto.getProperties().getProperty(Config.SIGNATURE_ALGORITHM));

            VMServices vm = new VMServices(keyLoader, provider, configDir);
                        
            vm.removeServer(id);
            
                
        }else if (args.length == 3) {
           
            System.out.println("####Tpp Service[Join]####");

            int id = Integer.parseInt(args[0]);
            String ipAddress = args[1];
            int port = Integer.parseInt(args[2]);

            keyLoader = new ECDSAKeyLoader(id, configDir, crypto.getProperties().getProperty(Config.SIGNATURE_ALGORITHM));
            
            VMServices vm = new VMServices(keyLoader, provider, configDir);
                        
            vm.addServer(id, ipAddress, port);
            

        }else{
            System.out.println("Usage: java FabricVMServices <replica id> [ip address] [port]");
            System.out.println("If the ip address and port number are given, the replica will be added to the group, otherwise it will be removed.");
            System.exit(1);
        }

        Thread.sleep(2000);//2s
        

        System.exit(0);
    }
}

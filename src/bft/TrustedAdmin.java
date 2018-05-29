/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package bft;

import bftsmart.reconfiguration.ViewManager;
import bftsmart.tom.ServiceProxy;
import java.nio.ByteBuffer;
import java.util.Scanner;
import java.util.StringTokenizer;

/**
 *
 * @author joao
 */
public class TrustedAdmin {
    
    public static void main(String[] args) {

        ViewManager viewManager = null;
        ServiceProxy proxy = null;

        if (args.length > 1) {
            viewManager = new ViewManager(args[1]);
            proxy = new ServiceProxy(Integer.parseInt(args[0]), args[1]);
            
        } else {
            viewManager = new ViewManager("");
            proxy = new ServiceProxy(Integer.parseInt(args[0]), "");
        }
        
        Scanner scan = new Scanner(System.in);
        String str = null;
        do {
            str = scan.nextLine();
            String cmd = "";
            String mode = "";
            try {
                StringTokenizer token = new StringTokenizer(str);
                cmd = token.nextToken();
                mode = token.nextToken();
                int id = Integer.parseInt(token.nextToken());
             
                if (cmd.equals("add")) {

                    if (mode.equals("node")) {

                        String ip = token.nextToken();
                        int port = Integer.parseInt(token.nextToken());

                        viewManager.addServer(id, ip, port);

                        viewManager.executeUpdates();
                    } else if (mode.equals("proxy")) {
                        
                        ByteBuffer buffer = ByteBuffer.allocate(5);
                        
                        buffer.put((byte) 1);
                        buffer.putInt(id);
                        
                        proxy.invokeOrdered(buffer.array());
                    }

                } else if (cmd.equals("remove")) {

                    if (mode.equals("node")) {
                        
                        viewManager.removeServer(id);

                        viewManager.executeUpdates();
                    } else if (mode.equals("proxy")) {
                        
                        ByteBuffer buffer = ByteBuffer.allocate(5);
                        
                        buffer.put((byte) 0);
                        buffer.putInt(id);
                        
                        proxy.invokeOrdered(buffer.array());
                    }
                }
          
                
            } catch (Exception e) {
                
                e.printStackTrace();
                System.exit(0);
            }
            
        } while (!str.equals("exit"));
        viewManager.close();
        System.exit(0);
    }
    
}

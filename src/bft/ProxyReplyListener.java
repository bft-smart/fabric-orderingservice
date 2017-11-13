/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package bft;

import bftsmart.reconfiguration.views.View;
import bftsmart.tom.AsynchServiceProxy;
import bftsmart.tom.core.messages.TOMMessage;
import bftsmart.tom.core.messages.TOMMessageType;
import bftsmart.tom.util.Extractor;
import bftsmart.tom.util.TOMUtil;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.AbstractMap.SimpleEntry;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.hyperledger.fabric.protos.common.Common;

/**
 *
 * @author joao
 */
public class ProxyReplyListener extends AsynchServiceProxy {

    private Map<Integer, Entry<Common.Block, Common.Metadata[]>[]> replies;
    private Map<Integer, Common.Block> responses;
    private Comparator<Entry<Common.Block, Common.Metadata[]>> comparator;
    private int replyQuorum;
    private int next;
    
    private Lock inputLock;
    private Condition blockAvailable;
                
    private Log logger;
    
    //used to detected updates to the view
    private int nextView;
    private View[] views;
        
    public ProxyReplyListener(int id) {
        super(id);
        init();
    }
    
    public ProxyReplyListener(int id, String configHome) {
        super(id, configHome);
        init();
    }
    
    public ProxyReplyListener(int id, String configHome,
            Comparator<byte[]> replyComparator, Extractor replyExtractor) {
        super(id, configHome, replyComparator, replyExtractor);
        init();
    }
    
    private void init() {
        logger = LogFactory.getLog(ProxyReplyListener.class);
        
        responses = new ConcurrentHashMap<>();
        replies = new HashMap<>();
        replyQuorum = getReplyQuorum();
        
        comparator = (Entry<Common.Block, Common.Metadata[]> o1, Entry<Common.Block, Common.Metadata[]> o2) -> o1.getKey().equals(o2.getKey()) && // compare entire block
                o1.getValue()[0].getValue().equals(o2.getValue()[0].getValue()) && // compare block signature value
                o1.getValue()[1].getValue().equals(o2.getValue()[1].getValue()) // compare config signature value
                ? 0 : -1 //TODO: compare the signature values too

        ;
        
        this.inputLock = new ReentrantLock();
        this.blockAvailable = inputLock.newCondition();
        
        nextView = getViewManager().getCurrentViewId();
        views = new View[getViewManager().getCurrentViewN()];
    }
    
    private View newView(byte[] bytes) {
        
        Object o = TOMUtil.getObject(bytes);
        return (o != null && o instanceof View ? (View) o : null);
    }
    
    @Override
    public void replyReceived(TOMMessage tomm) {
        
        logger.debug("Replica " + tomm.getSender());
        logger.debug("Sequence " + tomm.getSequence());
                
        View v = null;
        
        try {

            canReceiveLock.lock();
            
            if ((v = newView(tomm.getContent())) != null) {
            
                processReplyView(tomm, v);
                
            } else {
                
                processReplyBlock(tomm);
            }

        }
        finally {
            
            canReceiveLock.unlock();

        }
    }

    private void processReplyView (TOMMessage tomm, View v) {
                        
        int sameContent = 1;

        int pos = getViewManager().getCurrentViewPos(tomm.getSender());

        views[pos] = v;

        for (int i = 0; i < views.length; i++) {

            if ((views[i] != null) && (i != pos || getViewManager().getCurrentViewN() == 1)
                                && (tomm.getReqType() != TOMMessageType.ORDERED_REQUEST || views[i].equals(v))) {

                sameContent++;

            }
        }

        if (sameContent >= replyQuorum && v.getId() > getViewManager().getCurrentViewId()) {

            System.out.println("Updating ProxyListener to view " + v.getId());

            reconfigureTo(v);

            replyQuorum = getReplyQuorum();

            views = new View[getViewManager().getCurrentViewN()];

            // this message is sent again to make all replicas not from the previous view aware of the client
            askForView();
        }
        
    }
    
    private void processReplyBlock (TOMMessage tomm) {
        
        Common.Block response = null;

        if (tomm.getSequence() < next) { // ignore replies that no longer matter

            replies.remove(tomm.getSequence());
            responses.remove(tomm.getSequence());
            return;
        }
        int pos = getViewManager().getCurrentViewPos(tomm.getSender());

        if (pos < 0) { //ignore messages that don't come from replicas
            return;
        }

        if (replies.get(tomm.getSequence()) == null) //avoid nullpointer exception
            replies.put(tomm.getSequence(), new Entry[getViewManager().getCurrentViewN()]);

        byte[][] contents = null;
        Common.Block block = null;
        Common.Metadata metadata[] = new Common.Metadata[2];

        try {
            contents = deserializeContents(tomm.getContent());
            if (contents == null || contents.length < 3) return;
            block = Common.Block.parseFrom(contents[0]);
            if (block == null) return;
            metadata[0] = Common.Metadata.parseFrom(contents[1]);
            if (metadata[0] == null) return;
            metadata[1] = Common.Metadata.parseFrom(contents[2]);
            if (metadata[1] == null) return;
        } catch (IOException ex) {
            ex.printStackTrace();
            return;
        }

        Entry[] reps = replies.get(tomm.getSequence());

        reps[pos] = new SimpleEntry<>(block,metadata);

        int sameContent = 1;

        for (int i = 0; i < reps.length; i++) {

            if ((i != pos || getViewManager().getCurrentViewN() == 1) && reps[i] != null
                                        && (comparator.compare(reps[i], reps[pos]) == 0)) {

                sameContent++;
                if (sameContent >= replyQuorum) {
                    response = getBlock(reps, pos);
                    responses.put(tomm.getSequence(), response);

                    if (tomm.getViewID() > nextView) {

                        nextView = tomm.getViewID();
                        views = new View[getViewManager().getCurrentViewN()];

                        // this is needed to fetch the current view from the replicas
                        askForView();

                    }

                }
            }
        }

        this.inputLock.lock();
        if (responses.get(next) != null) this.blockAvailable.signalAll();
        this.inputLock.unlock();
        
    }
    
    private void askForView() {
        
        Thread t = new Thread() {

            @Override
            public void run() {

                invokeAsynchRequest("GETVIEW".getBytes(), getViewManager().getCurrentViewProcesses(),
                        null, TOMMessageType.ORDERED_REQUEST);

            }

        };

        t.start();
                        
    }
    
    public Common.Block getNext() {
        
        Common.Block ret = null;
        
        this.inputLock.lock();
        while ((ret = responses.get(next)) == null) {
            
            this.blockAvailable.awaitUninterruptibly();
            
        }
        this.inputLock.unlock();

        next++;
        
        return ret;
    }
                           
    private Common.Block getBlock(Entry<Common.Block, Common.Metadata[]>[] replies, int lastReceived) {
        
            Common.Block.Builder block = replies[lastReceived].getKey().toBuilder();
            Common.BlockMetadata.Builder blockMetadata = block.getMetadata().toBuilder();
            
            Common.Metadata[] blockSigs = new Common.Metadata[replies.length];
            Common.Metadata[] configSigs = new Common.Metadata[replies.length];
            
            Common.Metadata.Builder allBlockSig = Common.Metadata.newBuilder();
            Common.Metadata.Builder allConfigSig = Common.Metadata.newBuilder();
            
            for (int i = 0; i < replies.length; i++) {
                
                if (replies[i] != null) {

                    blockSigs[i] = replies[i].getValue()[0];
                    configSigs[i] = replies[i].getValue()[1];
                    
                }
            }
            
            allBlockSig.setValue(blockSigs[lastReceived].getValue());
            
            for (Common.Metadata sig : blockSigs) {
                
                if (sig != null) {
                    
                    allBlockSig.addSignatures(sig.getSignatures(0));
                }
                
            }
            
            allConfigSig.setValue(configSigs[lastReceived].getValue());
            
            for (Common.Metadata sig : configSigs) {
                
                if (sig != null) {
                    
                    allConfigSig.addSignatures(sig.getSignatures(0));
                }

            }
            blockMetadata.setMetadata(Common.BlockMetadataIndex.SIGNATURES_VALUE, allBlockSig.build().toByteString());
            blockMetadata.setMetadata(Common.BlockMetadataIndex.LAST_CONFIG_VALUE, allConfigSig.build().toByteString());
            
            block.setMetadata(blockMetadata.build());
            
            return block.build();
            
    }
    
    static private byte[][] deserializeContents(byte[] bytes) throws IOException {
        
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
}

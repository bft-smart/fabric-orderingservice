/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package bft.util;

import bftsmart.reconfiguration.views.View;
import bftsmart.tom.AsynchServiceProxy;
import bftsmart.tom.core.messages.TOMMessage;
import bftsmart.tom.core.messages.TOMMessageType;
import bftsmart.tom.util.Extractor;
import bftsmart.tom.util.KeyLoader;
import bftsmart.tom.util.TOMUtil;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.Provider;
import java.util.AbstractMap.SimpleEntry;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.hyperledger.fabric.protos.common.Common;

/**
 *
 * @author joao
 */
public class ProxyReplyListener extends AsynchServiceProxy {

    private int id;
    
    private Map<Integer, BFTCommon.ReplyTuple[]> replies;
    private Map<Integer, Entry<String, Common.Block>> responses;
    private Comparator<BFTCommon.ReplyTuple> comparator;
    private int replyQuorum;
    private int next;
    
    private Lock inputLock;
    private Condition blockAvailable;
                
    private Logger logger;
    
    //used to detected updates to the view
    private int nextView;
    private View[] views;
    
    //used to extract latest sequence number
    private int[] sequences;
            
    public ProxyReplyListener(int id) {
        super(id);
        this.id = id;
        init();
    }
    
    public ProxyReplyListener(int id, String configHome) {
        super(id, configHome);
        this.id = id;
        init();
    }
    
    public ProxyReplyListener(int id, String configHome, KeyLoader loader, Provider provider) {
        super(id, configHome, loader, provider);
        this.id = id;
        init();
    }
    
    public ProxyReplyListener(int id, String configHome,
            Comparator<byte[]> replyComparator, Extractor replyExtractor, KeyLoader loader, Provider provider) {
        super(id, configHome, replyComparator, replyExtractor, loader, provider);
        this.id = id;
        init();
    }
    
    private void init() {
        logger = LoggerFactory.getLogger(ProxyReplyListener.class);
        
        responses = new ConcurrentHashMap<>();
        replies = new HashMap<>();
        replyQuorum = getReplyQuorum();
        
        comparator = (BFTCommon.ReplyTuple o1, BFTCommon.ReplyTuple o2) -> o1.block.equals(o2.block) && // compare entire block
                o1.metadata[0].getValue().equals(o2.metadata[0].getValue()) &&      // compare block signature value
                o1.metadata[1].getValue().equals(o2.metadata[1].getValue()) &&      // compare config signature value
                o1.channel.equals(o2.channel) &&                                    // compare channel id
                o1.config == o2.config                                              // compare block type
                ? 0 : -1

        ;
        
        this.inputLock = new ReentrantLock();
        this.blockAvailable = inputLock.newCondition();
        
        nextView = getViewManager().getCurrentViewId();
        views = new View[getViewManager().getCurrentViewN()];
        
        sequences = new int[getViewManager().getCurrentViewN()];
        for (int i = 0; i < sequences.length; i++) {
            
            sequences[i] = -1;
        }
    }
    
    private View newView(byte[] bytes) {
        
        Object o = TOMUtil.getObject(bytes);
        return (o != null && o instanceof View ? (View) o : null);
    }
    
    private int newSequence(byte[] bytes) {
        
        try {
            byte[][] contents = BFTCommon.deserializeContents(bytes);
            
            return ((new String(contents[0])).equals("SEQUENCE") ? ByteBuffer.wrap(contents[1]).getInt() : -1);
            
        } catch (IOException ex) {
            
            return -1;
        }
    }
    
    @Override
    public void replyReceived(TOMMessage tomm) {
                
        View v = null;
        int s = -1;
        
        try {

            canReceiveLock.lock();
            
            if ((v = newView(tomm.getContent())) != null) { // am I receiving a new view?
            
                processReplyView(tomm, v);
                
            } else if ((s = newSequence(tomm.getContent())) != -1) { // am I being updated on the sequence number?
                                
                processReplySequence(tomm, s);
                
            } else { // I am receiving blocks
                
                processReplyBlock(tomm);
            }

        }
        finally {
            
            canReceiveLock.unlock();

        }
    }

    private void processReplySequence(TOMMessage tomm, int s) {
        
        int sameContent = 1;

        int pos = getViewManager().getCurrentViewPos(tomm.getSender());

        sequences[pos] = s;
        
        for (int i = 0; i < sequences.length; i++) {

            if ((sequences[i] != -1) && (i != pos || getViewManager().getCurrentViewN() == 1)
                                && (tomm.getReqType() != TOMMessageType.ORDERED_REQUEST || sequences[i] == s)) {

                sameContent++;

            }
        }
        
        if (sameContent >= replyQuorum) {

            logger.info("Updating ProxyListener to sequence " + s);

            next = s;

            sequences = new int[getViewManager().getCurrentViewN()];
            
            for (int i = 0; i < sequences.length; i++) {
            
                sequences[i] = -1;
        
            }

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

            logger.info("Updating ProxyListener to view " + v.getId());

            reconfigureTo(v);

            replyQuorum = getReplyQuorum();

            views = new View[getViewManager().getCurrentViewN()];

            // this message is sent again to make all replicas not from the previous view aware of the client
            askForView();
        }
        
    }
    
    private void processReplyBlock (TOMMessage tomm) {
        
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
            replies.put(tomm.getSequence(), new BFTCommon.ReplyTuple[getViewManager().getCurrentViewN()]);

        byte[][] contents = null;
        Common.Block block = null;
        Common.Metadata metadata[] = new Common.Metadata[2];
        String channel = null;
        boolean config = false;

        try {
            contents = BFTCommon.deserializeContents(tomm.getContent());
            if (contents == null || contents.length < 5) return;
            block = Common.Block.parseFrom(contents[0]);
            if (block == null) return;
            metadata[0] = Common.Metadata.parseFrom(contents[1]);
            if (metadata[0] == null) return;
            metadata[1] = Common.Metadata.parseFrom(contents[2]);
            if (metadata[1] == null) return;
            channel = new String(contents[3]);
            config = (contents[4][0] == 1);
            
        } catch (IOException ex) {
            logger.error("Failed to deserialize block and metadata", ex);
            return;
        }

        BFTCommon.ReplyTuple[] reps = replies.get(tomm.getSequence());

        reps[pos] = BFTCommon.getReplyTuple(block,metadata,channel,config);

        int sameContent = 1;

        for (int i = 0; i < reps.length; i++) {

            if ((i != pos || getViewManager().getCurrentViewN() == 1) && reps[i] != null
                                        && (comparator.compare(reps[i], reps[pos]) == 0)) {

                sameContent++;
                if (sameContent >= replyQuorum) {
                    Common.Block response = getBlock(reps, pos);
                    responses.put(tomm.getSequence(), new SimpleEntry<>(channel + ":" + config, response));

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

                try {
                    invokeAsynchRequest(BFTCommon.assembleSignedRequest(getViewManager().getStaticConf().getPrivateKey(), id, "GETVIEW", "", new byte[0]), getViewManager().getCurrentViewProcesses(),
                            null, TOMMessageType.ORDERED_REQUEST);
                } catch (IOException ex) {
                    logger.error("Failed to send GETVIEW request to nodes", ex);
                }

            }

        };

        t.start();
                        
    }
    
    public Entry<String,Common.Block> getNext() {
        
        Entry<String,Common.Block> ret = null;
        
        this.inputLock.lock();
        while ((ret = responses.get(next)) == null) {
            
            this.blockAvailable.awaitUninterruptibly();
            
        }
        this.inputLock.unlock();

        next++;
        
        return ret;
    }
                           
    private Common.Block getBlock(BFTCommon.ReplyTuple[] replies, int lastReceived) {
        
            Common.Block.Builder block = replies[lastReceived].block.toBuilder();
            Common.BlockMetadata.Builder blockMetadata = block.getMetadata().toBuilder();
            
            Common.Metadata[] blockSigs = new Common.Metadata[replies.length];
            Common.Metadata[] configSigs = new Common.Metadata[replies.length];
            
            Common.Metadata.Builder allBlockSig = Common.Metadata.newBuilder();
            Common.Metadata.Builder allConfigSig = Common.Metadata.newBuilder();
            
            for (int i = 0; i < replies.length; i++) {
                
                if (replies[i] != null) {

                    blockSigs[i] = replies[i].metadata[0];
                    configSigs[i] = replies[i].metadata[1];
                    
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
}

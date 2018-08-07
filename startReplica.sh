#!/bin/bash
java -DNODE_ID=$1 -cp dist/BFT-Proxy.jar:lib/* bft.BFTNode $@

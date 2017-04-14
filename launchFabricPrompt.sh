#!/bin/bash
gnome-terminal --tab --working-directory=$GOPATH'/src/github.com/hyperledger/fabric/'  -e "bash -c 'bash'"   \
      --tab --working-directory=$GOPATH'/src/github.com/hyperledger/fabric/orderer/sample_clients/deliver_stdout/' -e "bash -c 'bash'"	\
      --tab --working-directory=$GOPATH'/src/github.com/hyperledger/fabric/orderer/sample_clients/broadcast_timestamp/' -e "bash -c 'bash'"

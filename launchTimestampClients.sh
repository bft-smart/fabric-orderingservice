#!/bin/bash

PATH=/home/joao/gocode/src/github.com/hyperledger/fabric/
CLI=$1
NUM=$2

cd $PATH

for ((i=0;i<CLI;i++)); do

echo 'Launching client #'$i 
CMD=""$PATH"orderer/sample_clients/broadcast_timestamp/broadcast_timestamp --messages "$NUM""
$CMD &
done

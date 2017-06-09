#!/bin/bash

INITID=$1
REPLIERS=$2
WORKERS=$3
DELAY=$4
SIZE=$5
SIGS=$6
BATCH=$7

for ((i=0;i<REPLIERS;i++)); do

let ID=($i*1000)+$INITID
echo "Launching replier #"$ID" with "$WORKERS" workers"
CMD="java -cp dist/BFT-Proxy.jar:lib/* bft.TestNodes "$ID" "$WORKERS" "$DELAY" "$SIZE" "$SIGS" "$BATCH""
$CMD &

if [ $ID -eq 1000 ]
then
  sleep 3
fi

done

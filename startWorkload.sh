#!/bin/bash
java -DWORKLOAD_CLIENT_ID=$1 -cp dist/BFT-Proxy.jar:lib/* bft.test.WorkloadClient $@

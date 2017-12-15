# BFT ordering service for Hyperledger Fabric v1.1

This is a Byzantine fault-tolerant (BFT) ordering service for Hyperledger Fabric (HLF) v1.1. This BFT ordering service is a wrapper around BFT-SMaRt (https://github.com/bft-smart/library), a Java open source BFT library maintained by the LaSIGE research unit at the University of Lisbon.

## Pre-requisites

This code was developed and tested under Ubuntu 16.04.2 LTS and HLF v1.1.

Because this ordering service needs to be integrated into the HLF codebase, it requires the HLF fork repository available at https://github.com/jcs47/fabric instead of the official repository. This means that all dependencies associated with the offical HLF codebase are still required for this fork.

The ordering service module uses the JUDS library to provide UNIX sockets for communication between Java and Go. The codebase already includes the associated jar, but because it uses JNI to access native UNIX sockets interfaces, it is still necessary to download the source code from https://github.com/mcfunley/juds and go through the installation steps described in the README.

## Compiling

Make sure to switch to the 'release-1.1' branch, both for this repository and for the aforementioned HLF fork. You can compile this fork the same way as the official repository. However, you must make sure to compile it outside of Vagrant, by executing `sudo ./fabric/devenv/setupUbuntuOnPPC64le.sh` before executing `make dist-clean all`. Make also sure to set the `$FABRIC_CFG_PATH` environment variable to the absolute path of the `./sampleconfig` directory of the fork. Assuming you have the `$GOROOT` environment variable properly set, you should be able to do this by typing `export FABRIC_CFG_PATH=$GOROOT/src/github.com/hyperledger/fabric/sampleconfig/` in the command line.

## Launching 4 ordering nodes and a single frontend

Edit the `./hyperledger-bftmart/config/node.config`file so that the `CERTIFICATE` parameter is set to the absolute path of the `./fabric/sampleconfig/msp/signcerts/peer.pem` file and that the  `PRIVKEY` parameter is set to the absolute path of the `./fabric/sampleconfig/msp/keystore/key.pem` file. Following this, execute the `startReplica.sh` script in 4 different terminals as follows:

`./startReplica.sh 0`

`./startReplica.sh 1`

`./startReplica.sh 2`

`./startReplica.sh 3`

Once all nodes have outputed `Ready to process operations`, you can launch the Java component of the frontend as follows:

`./startFrontend.sh 1000 10 9999`

The first argument is the ID of the frontend, and it should match one of the IDs specified in the `RECEIVERS` parameter in the `./hyperledger-bftmart/config/node.config`file. The second argument is the number of connections available in the connection pool between the Go and JAva components, and it should match the `ConnectionPoolSize`parameter from the `BFTsmart` section in the `./fabric/sampleconfig/orderer.yaml` file. The third parameter is the TCP port from which the Java component delivers blocks to the Go component, and should match the `RecvPort` parameter in the previous section/file.

The Go component of the frontend is launched with the `./fabric/build/bin/orderer start` command.

## Running with the sample clients

To submit a heavy workload of representative transactions using the sample clients available in `./fabric/sample_clients`, execute the commands bellow in the following order:

1) Execute `go build`  at directories `./fabric/orderer/sample_clients/deliver_stdout` and `./fabric/orderer/sample_clients/broadcast_msg/` to compile the sample clients
2) Generate the genesis file using the `./configtxgen -profile SampleSingleMSPBFTsmart -channelID [channelID] -outputBlock ../../genesisblock` command inside the `./fabric/build/bin/` directory.
3) Run `orderer/sample_clients/deliver_stdout/deliver_stdout --quiet --channelID [channelID]`
4) Run `orderer/sample_clients/broadcast_timestamp/broadcast_timestamp --channelID [channelID] --size [size of each transaction] --messages [number of transactions to send]`

For more information regarding this project, read the technical report available at  http://arxiv.org/abs/1709.06921

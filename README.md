# BFT ordering service for Hyperledger Fabric v1.1

This is a Byzantine fault-tolerant (BFT) ordering service for Hyperledger Fabric (HLF) v1.1. This BFT ordering service is a wrapper around BFT-SMaRt (https://github.com/bft-smart/library), a Java open source BFT library maintained by the LaSIGE research unit at the University of Lisbon.

## Pre-requisites

This code was developed and tested under Ubuntu 16.04.2 LTS and HLF v1.1.

Because this ordering service needs to be integrated into the HLF codebase, it requires the HLF fork repository available at https://github.com/jcs47/fabric instead of the official repository. This means that all dependencies associated with the offical HLF codebase are still required for this fork.

The ordering service module uses the JUDS library to provide UNIX sockets for communication between Java and Go. The codebase already includes the associated jar, but because it uses JNI to access native UNIX sockets interfaces, it is still necessary to download the source code from https://github.com/mcfunley/juds and go through the installation steps described in the README.

## Compiling

Make sure to switch to the 'release-1.1' branch, both for this repository and for the aforementioned HLF fork. You can compile this fork the same way as the official repository. However, you must make sure to compile it outside of Vagrant, by executing `sudo ./fabric/devenv/setupUbuntuOnPPC64le.sh` before executing `make dist-clean all`.

## Running with sample clients

To locally run the ordering service with 4 nodes, execute the commands bellow in the following order:

1) In the main directory of this repository, run 'launch4Replicas.sh'
2) Still in the same directory, run 'launchProxy.sh'.
3) Change to '[gocode directory]/src/github.com/hyperledger/fabric' (of the HLF fork)
4) Execute 'go build'  at directories 'orderer/', 'orderer/sample_clients/deliver_stdout', and 'orderer/sample_clients/broadcast_timestamp/'
5) Run 'orderer/orderer'
6) Run 'orderer/sample_clients/deliver_stdout/deliver_stdout'
7) Run 'orderer/sample_clients/broadcast_timestamp/broadcast_timestamp --secure --messages [number of messages to send]'

For more information regarding this project, read the technical report available at  http://arxiv.org/abs/1709.06921

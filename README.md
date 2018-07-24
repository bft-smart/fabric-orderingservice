# BFT ordering service for Hyperledger Fabric v1.1

This is a Byzantine fault-tolerant (BFT) ordering service for Hyperledger Fabric (HLF) v1.1. This BFT ordering service is a wrapper around [BFT-SMaRt](https://github.com/bft-smart/library), a Java open source BFT library maintained by the LaSIGE research unit at the University of Lisbon. 

## Overview

This ordering service has a similar architecture to the Kafka-based ordering service already provided by HLF. It is comprised by a set of `3f+1` ordering nodes and an arbitrary number of frontends, as depicted in the figure bellow. 

![Architecture](https://github.com/jcs47/hyperledger-bftsmart/blob/master/bft-os.png?raw=true)

The ordering nodes are equivalent to the Kafka-cluster with a Zookeeper ensemble, in the sense that they also execute a distributed consensus protocol responsible for establishing a total order on transactions. Furthermore, the frontends are equivalent to the ordering service nodes (OSNs) used by the Kafka-based ordering service, in the sense that they also relay the transactions issued by clients into the the consensus protocol. However, some key differences between this service and the Kafka-based service are:

1) The ordering nodes execute a BFT consensus protocol, which means malicious nodes are unable to disrupt the service (as long as they do not exceed `f` nodes out of a total of `3f+1`);
2) Whereas Kafka's OSNs receive a stream of ordered transactions, this service's frontends receive a stream of pre-generated blocks containing ECDSA signatures from `2f+1` ordering nodes;
3) Whereas Kafka's OSNs are logically comprised by a single Go process, this service's frontends are comprised by two processes (a Java and a Go component).

For more information regarding this project, check out the technical report available [here](http://arxiv.org/abs/1709.06921)

## Pre-requisites

This ordering service was developed and tested under Ubuntu 16.04.2 LTS and HLF v1.1.

Because this ordering service needs to be integrated into the HLF codebase, it requires the HLF fork repository available [here](https://github.com/jcs47/fabric) instead of the official repository. This means that all dependencies associated with the offical HLF codebase are still required for this fork. The pre-requisites associated to the HLF codebase can be found [here](http://hyperledger-fabric.readthedocs.io/en/release/prereqs.html).

Besides the aforementioned dependecies, this service also uses the JUDS library to provide UNIX sockets for communication between the Java and Go components. The codebase already includes the associated jar, but because it uses JNI to access native UNIX sockets interfaces, it is still necessary to download the source code from [here](https://github.com/mcfunley/juds) and go through the installation steps described in the README.

## Compiling

Make sure to switch to the 'release-1.1' branch, both for this repository and for the aforementioned HLF fork. You can compile that fork the same way as the official repository. However, you must make sure to compile it outside of Vagrant, by executing:

```
cd $GOPATH/src/github.com/hyperledger/fabric/
sudo ./devenv/setupUbuntuOnPPC64le.sh
```
Notice that even though we are working with a fork instead of the original HLF repository, the code still needs to be stored in `$GOPATH/src/github.com/hyperledger/fabric/`. Following this, you can compile HLF as follows:

```
make dist-clean peer orderer configtxgen
```

Make also sure to set the `$FABRIC_CFG_PATH` environment variable to the absolute path of the `./sampleconfig` directory of the fork. You should be able to do this by typing `export FABRIC_CFG_PATH=$GOPATH/src/github.com/hyperledger/fabric/sampleconfig/` in the command line. If you are using multiple terminals, you might need to type this command in each one of them.

To compile the Java code provided by this repository, you can simply type `ant` in its main folder.

## Launching 4 ordering nodes and a single frontend

The first step is to generate the genesis block for the ordering service. Generate the block as follows:

```
cd $GOPATH/src/github.com/hyperledger/fabric/
./build/bin/configtxgen -profile SampleSingleMSPBFTsmart -channelID <system channel ID> -outputBlock <path to genesis file>
```

The `<path to genesis file>` argument should match both the absolute path in the `GenesisFile` parameter in the `General` section in of the `./fabric/sampleconfig/orderer.yaml` configuration file and the `GENESIS` parameter in the `./hyperledger-bftmart/config/node.config`. Keep in mind that in the case of this ordering service, the `GenesisMethod` parameter must always be set to `file`. Otherwise the frontends will generate genesis blocks distinct from the one loaded by the ordering node, thus leading to inconsistencies.

After creating the genesis block, edit the `./hyperledger-bftmart/config/node.config` file so that the `CERTIFICATE` parameter is set to the absolute path of the `./fabric/sampleconfig/msp/signcerts/peer.pem` file and that the  `PRIVKEY` parameter is set to the absolute path of the `./fabric/sampleconfig/msp/keystore/key.pem` file. Following this, enter the main directory for this repository and execute the `startReplica.sh` script in 4 different terminals as follows:

```
./startReplica.sh 0
./startReplica.sh 1
./startReplica.sh 2
./startReplica.sh 3
```

Once all nodes have outputed the message `-- Ready to process operations`, you can launch the Java component of the frontend as follows:

```
./startFrontend.sh 1000 10 9999
```

The first argument is the ID of the frontend, and it should match one of the IDs specified in the `RECEIVERS` parameter in the `./hyperledger-bftmart/config/node.config` file. The second argument is the number of UNIX connections available in the pool between the Go and Java components, and it should match the `ConnectionPoolSize`parameter from the `BFTsmart` section in the `./fabric/sampleconfig/orderer.yaml` file. The third parameter is the TCP port from which the Java component delivers blocks to the Go component, and should match the `RecvPort` parameter in the previous section/file.

You can now launch the Go component as follows:

```
./build/bin/orderer start
```

## Running an example chaincode

To execute an example chaincode using this ordering service, generate the rest of the HLF artifacts as follows:

```
cd $GOPATH/src/github.com/hyperledger/fabric/
./build/bin/configtxgen -profile SampleSingleMSPChannel -outputCreateChannelTx <path to channel creation tx> -channelID <channel ID>
./build/bin/configtxgen -profile SampleSingleMSPChannel -outputAnchorPeersUpdate <path to anchor peer update tx> -channelID <channel ID> -asOrg SampleOrg
```

Note that the channel ID that you use for generating the channel creation transaction must be different that the one used to generate the genesis block. When creating the genesis block, you specify the ID for the *system channel*, whereas when generating the channel creating transaction, you are going to request a new *normal channel* to be created.

With the ordering service bootstrapped, you can now launch an endorsing peer. To do so, make sure that the `fileSystemPath` parameter of the `peer` section in the `./sampleconfig/core.yaml` configuration file is set to a directory where you have write previledges. Following this, start the endorsing peer process by executing:

```
./build/bin/peer node start

```

Use a client to join a channel and install/execute chaincode as follows:

```
./build/bin/peer channel create -o 127.0.0.1:7050 -c <channel ID> -f <path to channel creation tx>
./build/bin/peer channel join -b ./<channel ID>.block
./build/bin/peer channel update -o 127.0.0.1:7050 -c <channel ID> -f <path to anchor peer update tx>
./build/bin/peer chaincode install -n <chaincode ID> -v 1.0 -p github.com/hyperledger/fabric/examples/chaincode/go/chaincode_example02
./build/bin/peer chaincode instantiate -o 127.0.0.1:7050 -C <channel ID> -n <chaincode ID> -v 1.0 -c '{"Args":["init","a","100","b","200"]}'
./build/bin/peer chaincode query -C <channel ID> -n <chaincode ID> -v 1.0 -c '{"Args":["query","a"]}'
./build/bin/peer chaincode invoke -C <channel ID> -n <chaincode ID> -v 1.0 -c '{"Args":["invoke","a","b","10"]}'
./build/bin/peer chaincode query -C <channel ID> -n <chaincode ID> -v 1.0 -c '{"Args":["query","a"]}'
./build/bin/peer chaincode invoke -C <channel ID> -n <chaincode ID> -v 1.0 -c '{"Args":["invoke","a","b","-10"]}'
./build/bin/peer chaincode query -C <channel ID> -n <chaincode ID> -v 1.0 -c '{"Args":["query","a"]}'
```

## Running with the sample clients

To submit a heavy workload of representative transactions using the sample clients available in `./fabric/sample_clients`, execute the commands bellow in the following order:

Execute `go build`  at directories `./fabric/orderer/sample_clients/deliver_stdout`, `./fabric/orderer/sample_clients/broadcast_msg/` and `./fabric/orderer/sample_clients/broadcast_config/` to compile the sample clients

Launch a client to receive the generated blocks as follows:

```
cd $GOPATH/src/github.com/hyperledger/fabric/
./orderer/sample_clients/deliver_stdout/deliver_stdout --quiet --channelID <system channel ID>
```
  
Launch a client to submit transactions to the service as follows:

```
./orderer/sample_clients/broadcast_msg/broadcast_msg --channelID <system channel ID> --size <size of each transaction> --messages <number of transactions to send>
```
  
You can also create a new channel as follows:

```
./orderer/sample_clients/broadcast_config/broadcast_config --cmd newChain --chainID <channel ID>
  ```

## Running the ordering service in a distributed setting

The HLF codebase provided by the fork can be configured and deployed in a distributed setting in the same way as the original. In order to make sure the distributed deployment still uses this ordering service, the genesis block must be generated from a profile that sets the ordering service type to `bftsmart`.

In order to deploy the ordering nodes across multiple hosts, edit the `hyperledger-bftmart/config/host.config` file with the ip address of the hosts running each ordering node. If you want to set up more than 4 nodes, you must also edit the `hyperledger-bftmart/config/system.config` file (you need to fiddle with the parameters `system.servers.num`, `system.servers.f` and `system.initial.view`). These files must be the same across all ordering nodes, as well as the frontend.

Any time you need to bootstrap the system from scratch, make sure you delete the `hyperledger-bftmart/config/currentView` file in each host. Otherwise, the ordering nodes will fetch information about the group of nodes from that file instead of the configuration files described previously. Make also sure that you launch each node in numerical order (node 0, node 1, node 2, etc).

Finally, keep in mind that the Go component needs to be in the same host as the Java component. Nonetheless, you only need to install the JUDS dependencies in the hosts that runs a frontend.

## State transfer and reconfiguration

Each ordering node can be re-started after a crash, as long as the total number of simultaneously crashed nodes do not exceed `f`. Furthermore, it is also possible to change the set of ordering nodes on-the-fly via BFT-SMaRt's reconfiguration protocol. In order to add a node to the group, start it as you would any other node with the `startReplica.sh` script. To make that node join the existing group, use the `reconfigure.sh` script as follows:

```
./reconfigure.sh <node id> <ip address> <port>
```

In order to remove a node from the group, use the same script specifying only the node id. Bear in mind that when doing this in a distributed setting, it is necessary to copy the ``./hyperledger-bftsmart/config/currentView``file into the hosts that are about to join the group before anything else is done. This is because this file specifies the set of nodes that comprise the most up-to-date group. You must also make sure that the host from which the `reconfigure.sh` script is executed is also given this file.

## Limitations

As described above, the current implementation of this ordering service supports state transfer and on-the-fly reconfiguration of the set of ordering nodes. However, this does not extends to the set of frontends yet.

Since the ordering nodes execute a BFT consensus algorithm and each one produces a ECDSA signature for each block, malicious nodes are unable to disrupt the service (as long as the number of malicious nodes in the system do not exceed `f`). However, they perform minimal verifications on the transactions; verification of the endorsing peers' signatures and policies are done at the frontends, before they relay transactions to the consensus algorithm. This means that a malicious frontend is still able to skip these verifications and relay non-complient transactions to the ledger(s). Nonetheless, the BFT consensus algorithm ensures that correct nodes receive all transactions by the same order and generate the same blocks at each correct node. In essence, ledger(s) may contain elicit transactions, but legit transactions cannot be prevented from being added to the ledger(s).


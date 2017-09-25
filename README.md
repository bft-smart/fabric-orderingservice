# hyperledger-bftsmart

This is a Byzantine fault-tolerant (BFT) ordering service for Hyperledger Fabric v1.0. This BFT ordering service is a wrapper around BFT-SMaRt (https://github.com/bft-smart/library) a Java written, open source BFT libraries maintained by University of Lisbon.

This code was developed and tested under Ubuntu 16.04.2 LTS and HLF v1.0. It also requires the HLF fork repository available at https://github.com/jcs47/fabric

Before compilng, make sure to switch to the 'zeromq' branch, both for this repository and for the aforementioned HLF fork. The HLF fork can be compile as usual. Because the code for these braches require the zeromq framework, it is also needed to install in the system all the dependencies for the libaries goczmq (used by the HLF fork) and jzmq (used in this repository). Execute 'ant' in the main directory to compile the code.

To locally run the ordering service with 4 nodes, execute the following commands in the following order:

1) In the main directory of this repository, run 'launch4Replicas.sh'
2) Still in the same directory, run 'launchProxy.sh'.
3) Change to '<gocode directory>/src/github.com/hyperledger/fabric' (of the HLF fork) and run the following commands:
4) execute 'go build'  at directories 'orderer/', 'orderer/sample_clients/deliver_stdout', and 'orderer/sample_clients/broadcast_timestamp/'
5) run 'orderer/orderer'
6) run 'orderer/sample_clients/deliver_stdout/deliver_stdout'
7) run 'orderer/sample_clients/broadcast_timestamp/broadcast_timestamp --secure --messages <number of messages to send>'

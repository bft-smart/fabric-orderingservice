# hyperledger-bftsmart

This code was developed and tested under Ubuntu 16.04.2 LTS. It requires the HLF fork repository available at https://github.com/jcs47/fabric

Exeute ant in the main directory to compile the code. Make sure to switch to the 'zeromq' branch, both for this repository and for the aforementioned HLF fork. Because the code for these braches require the zeromq framework, it is also needed to install in the system all the dependencies for the goczmq library (used by the HLF fork) and jzmq (used in this repository).

#!/bin/bash

#tmpname=`mktemp -d -t`

function main() {

	if [ $# -eq 0 ]; then

		echo "Usage: $0 <system channel name> [clean]"
		echo "Use 'clean' to deleted the auxiliary images (bftsmart/fabric and bftsmart/fabric-common)"
		exit 1
	fi

	cd ..

	#if [ ! -f dist/BFT-Proxy.jar ]; then
	    ant
	#fi

	cd docker_images

	if [ ! -d ./temp ]; then
	    mkdir ./temp
	fi

	if [ -d ./temp/fabric_conf ]; then
	    rm -R ./temp/fabric_conf

	fi

	if [ -d ./temp/config ]; then
	    rm -R ./temp/config

	fi

	if [ -d ./temp/lib ]; then
	    rm -R ./temp/lib

	fi

	if [ -d ./temp/sampleconfig ]; then
	    rm -R ./temp/sampleconfig

	fi

	if [ -d ./temp/chaincode ]; then
	    rm -R ./temp/chaincode

	fi

	rm ./temp/*

	cp ../dist/BFT-Proxy.jar ./temp
	cp -r ../config ./temp/config
	cp -r ../lib ./temp/lib
	mkdir ./temp/fabric_conf

	if [ -f ./temp/config/currentView ]; then
	    rm ./temp/config/currentView
	fi


	#create_hosts_config
	#create_node_config
	create_fabric_configtx
	create_fabric_orderer

	#dirname=`tar -tzf java.tar.gz | head -1 | cut -f1 -d"/"`

	docker-compose build  --build-arg SYS_CHAN_NAME=$1 fabric

	docker create --name="fabric-temp" "bftsmart/fabric"
	id=$(docker ps -aqf "name=fabric-temp")

	docker cp $id:/go/src/github.com/hyperledger/fabric/sampleconfig ./temp
	docker cp $id:/go/src/github.com/hyperledger/fabric/genesisblock ./temp
	docker cp $id:/go/src/github.com/hyperledger/fabric/build/bin/orderer ./temp
	docker cp $id:/go/src/github.com/hyperledger/fabric/build/bin/configtxgen ./temp
	docker cp $id:/go/src/github.com/hyperledger/fabric/orderer/sample_clients/broadcast_msg/broadcast_msg ./temp
	docker cp $id:/go/src/github.com/hyperledger/fabric/orderer/sample_clients/broadcast_config/broadcast_config ./temp
	docker cp $id:/go/src/github.com/hyperledger/fabric/orderer/sample_clients/deliver_stdout/deliver_stdout ./temp
	docker cp $id:/go/src/github.com/hyperledger/fabric/examples/chaincode ./temp
	docker cp $id:/usr/lib/libunixdomainsocket-linux-i386.so ./temp
	docker cp $id:/usr/lib/libunixdomainsocket-linux-x86_64.so ./temp
	docker rm -v $id

	docker-compose build common orderingnode frontend

	create_fabric_core
	create_update_frontend_entrypoint_script

	docker-compose build tools

	if [ ! -z "$2" ] && [ $2 -eq "clean" ] ; then
		docker rmi bftsmart/fabric
		docker rmi bftsmart/fabric-common
	fi
}

#function create_hosts_config () {

#cat > ./temp/config/hosts.config << 'EOF'
#0 172.17.0.2 11000
#1 172.17.0.3 11010
#2 172.17.0.4 11020
#3 172.17.0.5 11030
#7001 127.0.0.1 11100
#EOF

#}

#function create_node_config () {

#cat > ./temp/config/node.config << 'EOF'
#Path to the genesis block for the system channel
#GENESIS=/etc/bftsmart-orderer/config/genesisblock

##The ID of the membership service provider (MSP)
#MSPID=DEFAULT

##Certificate of the node, compliant to Fabric's MSP guidelines
#CERTIFICATE=/etc/bftsmart-orderer/config/cert.pem

##Private key of the node, compliant to Fabric's MSP guidelines
#PRIVKEY=/etc/bftsmart-orderer/config/key.pem

##Number of block generation threads in the pool
#PARELLELISM=10

##Maximum number of blocks to submit to each signer/sending thread
#BLOCKS_PER_THREAD=10000

##IDs of the frontends present in the system, separate by commas
#RECEIVERS=1000,2000

##Enable/disable envelope validation. Configuration envelopes are always verified regardless of this parameter
#ENV_VALIDATION=true

##Enable/disable second block signature. Useful for benchmarking, but it must be enabled on production deployments, so that it abides to Fabric's implementation
#BOTH_SIGS=true

##The acceptable difference between the state machine's time and the client's time. Only used if envelope validation is active
##or in the case of a reconfiguration envelope
#TIME_WINDOW=15m
#EOF

#}

function create_update_frontend_entrypoint_script() {

cat > ./temp/update_frontend_entrypoint.sh << 'EOF'

#!/bin/bash

if [ -z $3 ]; then

	echo "Usage: $0 <path to genesis block> <json list with frontend entrypoints> <output file>"
	exit 1
fi

block=$1
addresses=$2
output=$3

configtxlator proto_decode --input $block --type common.Block | jq .data.data[0].payload.data.config > config.json
jq -s '.[0] * {"channel_group":{"values":{"OrdererAddresses":{"value": {"addresses":.[1]}}}}}' config.json $addresses > modified_config.json
configtxlator proto_encode --input config.json --type common.Config --output config.pb
configtxlator proto_encode --input modified_config.json --type common.Config --output modified_config.pb
configtxlator compute_update --channel_id bftchannel --original config.pb --updated modified_config.pb --output frontend_update.pb
configtxlator proto_decode --input frontend_update.pb --type common.ConfigUpdate | jq . > frontend_update.json
echo '{"payload":{"header":{"channel_header":{"channel_id":"bftchannel", "type":2}},"data":{"config_update":'$(cat frontend_update.json)'}}}' | jq . > frontend_update_in_envelope.json
configtxlator proto_encode --input frontend_update_in_envelope.json --type common.Envelope --output $output

rm config.pb config.json modified_config.json modified_config.pb frontend_update.pb frontend_update.json frontend_update_in_envelope.json

EOF

}

function create_fabric_configtx () {

cat > ./temp/fabric_conf/configtx.yaml << 'EOF'

# Copyright IBM Corp. All Rights Reserved.
#
# SPDX-License-Identifier: Apache-2.0
#

---
################################################################################
#
#   PROFILES
#
#   Different configuration profiles may be encoded here to be specified as
#   parameters to the configtxgen tool. The profiles which specify consortiums
#   are to be used for generating the orderer genesis block. With the correct
#   consortium members defined in the orderer genesis block, channel creation
#   requests may be generated with only the org member names and a consortium
#   name.
#
################################################################################
Profiles:

    # SampleInsecureSolo defines a configuration which uses the Solo orderer,
    # contains no MSP definitions, and allows all transactions and channel
    # creation requests for the consortium SampleConsortium.
    SampleInsecureSolo:
        Orderer:
            <<: *OrdererDefaults
        Consortiums:
            SampleConsortium:
                Organizations:

    # SampleInsecureKafka defines a configuration that differs from the
    # SampleInsecureSolo one only in that it uses the Kafka-based orderer.
    SampleInsecureKafka:
        Orderer:
            <<: *OrdererDefaults
            OrdererType: kafka
        Consortiums:
            SampleConsortium:
                Organizations:

    # JCS: my profile
    # SampleInsecureBFTsmart defines a configuration that differs from the
    # SampleInsecureSolo one only in that is uses the Kafka-based orderer.
    SampleInsecureBFTsmart:
        Orderer:
            <<: *OrdererDefaults
            OrdererType: bftsmart
        Consortiums:
            SampleConsortium:
                Organizations:

    # SampleDevModeSolo defines a configuration which uses the Solo orderer,
    # contains the sample MSP as both orderer and consortium member, and
    # requires only basic membership for admin privileges. It also defines
    # an Application on the ordering system channel, which should usually
    # be avoided.
    SampleDevModeSolo:
        Orderer:
            <<: *OrdererDefaults
            Organizations:
                - <<: *SampleOrg
                  AdminPrincipal: Role.MEMBER
        Application:
            <<: *ApplicationDefaults
            Organizations:
                - <<: *SampleOrg
                  AdminPrincipal: Role.MEMBER
        Consortiums:
            SampleConsortium:
                Organizations:
                    - <<: *SampleOrg
                      AdminPrincipal: Role.MEMBER

    # SampleDevModeSoloV1.1 mimics the SampleDevModeSolo definition but
    # additionally defines the v1.1 only capabilities which do not allow a
    # mixed v1.0.x v1.1.x network.
    SampleDevModeSoloV1_1:
        Capabilities:
            <<: *ChannelCapabilities
        Orderer:
            <<: *OrdererDefaults
            Organizations:
                - <<: *SampleOrg
                  AdminPrincipal: Role.MEMBER
            Capabilities:
                <<: *OrdererCapabilities
        Application:
            <<: *ApplicationDefaults
            Organizations:
                - <<: *SampleOrg
                  AdminPrincipal: Role.MEMBER
            Capabilities:
                <<: *ApplicationCapabilities
        Consortiums:
            SampleConsortium:
                Organizations:
                    - <<: *SampleOrg
                      AdminPrincipal: Role.MEMBER

    # SampleDevModeKafka defines a configuration that differs from the
    # SampleDevModeSolo one only in that it uses the Kafka-based orderer.
    SampleDevModeKafka:
        Orderer:
            <<: *OrdererDefaults
            OrdererType: kafka
            Organizations:
                - <<: *SampleOrg
                  AdminPrincipal: Role.MEMBER
        Application:
            <<: *ApplicationDefaults
            Organizations:
                - <<: *SampleOrg
                  AdminPrincipal: Role.MEMBER
        Consortiums:
            SampleConsortium:
                Organizations:
                    - <<: *SampleOrg
                      AdminPrincipal: Role.MEMBER

    # SampleDevModeKafkaV1_1 mimics the SampleDevModeKafka definition but
    # additionally defines the v1.1 only capabilities which do not allow a
    # mixed v1.0.x v1.1.x network.
    SampleDevModeKafkaV1_1:
        Capabilities:
            <<: *ChannelCapabilities
        Orderer:
            <<: *OrdererDefaults
            OrdererType: kafka
            Organizations:
                - <<: *SampleOrg
                  AdminPrincipal: Role.MEMBER
            Capabilities:
                <<: *OrdererCapabilities
        Application:
            <<: *ApplicationDefaults
            Organizations:
                - <<: *SampleOrg
                  AdminPrincipal: Role.MEMBER
            Capabilities:
                <<: *ApplicationCapabilities
        Consortiums:
            SampleConsortium:
                Organizations:
                    - <<: *SampleOrg
                      AdminPrincipal: Role.MEMBER

    # SampleSingleMSPSolo defines a configuration which uses the Solo orderer,
    # and contains a single MSP definition (the MSP sampleconfig).
    # The Consortium SampleConsortium has only a single member, SampleOrg.
    SampleSingleMSPSolo:
        Orderer:
            <<: *OrdererDefaults
            Organizations:
                - *SampleOrg
        Consortiums:
            SampleConsortium:
                Organizations:
                    - *SampleOrg

    # JCS: my profile
    # SampleSingleMSPBFTsmart defines a configuration which uses the Solo orderer,
    # and contains a single MSP definition (the MSP sampleconfig).
    # The Consortium SampleConsortium has only a single member, SampleOrg
    SampleSingleMSPBFTsmart:
        Orderer:
            <<: *OrdererDefaults
            OrdererType: bftsmart
            Organizations:
                - *SampleOrg
        Consortiums:
            SampleConsortium:
                Organizations:
                    - *SampleOrg

    # SampleSingleMSPKafka defines a configuration that differs from the
    # SampleSingleMSPSolo one only in that it uses the Kafka-based orderer.
    SampleSingleMSPKafka:
        Orderer:
            <<: *OrdererDefaults
            OrdererType: kafka
            Organizations:
                - *SampleOrg
        Consortiums:
            SampleConsortium:
                Organizations:
                    - *SampleOrg

    # SampleSingleMSPSoloV1_1 mimics the SampleSingleMSPSolo definition but
    # additionally defines the v1.1 only capabilities which do not allow a
    # mixed v1.0.x v1.1.x network.
    SampleSingleMSPSoloV1_1:
        Capabilities:
            <<: *ChannelCapabilities
        Orderer:
            <<: *OrdererDefaults
            Organizations:
                - *SampleOrg
            Capabilities:
                <<: *OrdererCapabilities
        Consortiums:
            SampleConsortium:
                Organizations:
                    - *SampleOrg

    # SampleSingleMSPKafkaV1_1 defines a configuration that differs from the
    # SampleSingleMSPSoloV1_1 one only in that it uses the Kafka-based orderer.
    SampleSingleMSPKafkaV1_1:
        Capabilities:
            <<: *ChannelCapabilities
        Orderer:
            <<: *OrdererDefaults
            OrdererType: kafka
            Organizations:
                - *SampleOrg
            Capabilities:
                <<: *OrdererCapabilities
        Consortiums:
            SampleConsortium:
                Organizations:
                    - *SampleOrg

    # SampleNoConsortium is very similar to SampleInsecureSolo, except it does
    # not define Consortiums.
    SampleNoConsortium:
        Orderer:
            <<: *OrdererDefaults

    # SampleEmptyInsecureChannel defines a channel with no members and
    # therefore no access control.
    SampleEmptyInsecureChannel:
        Consortium: SampleConsortium
        Application:
            Organizations:

    # SampleSingleMSPChannel defines a channel with only the sample org as a
    # member. It is designed to be used in conjunction with SampleSingleMSPSolo
    # and SampleSingleMSPKafka orderer profiles.
    SampleSingleMSPChannel:
        Consortium: SampleConsortium
        Application:
            Organizations:
                - *SampleOrg

    # SampleSingleMSPChannelV1_1 defines a channel just as
    # SampleSingleMSPChannel. However, it also defines the capabilities map which
    # makes this channel incompatible with v1.0.x peers.
    SampleSingleMSPChannelV1_1:
        Consortium: SampleConsortium
        Application:
            Organizations:
                - *SampleOrg
            Capabilities:
                <<: *ApplicationCapabilities

################################################################################
#
#   ORGANIZATIONS
#
#   This section defines the organizational identities that can be referenced
#   in the configuration profiles.
#
################################################################################
Organizations:

    # SampleOrg defines an MSP using the sampleconfig. It should never be used
    # in production but may be used as a template for other definitions.
    - &SampleOrg
        # Name is the key by which this org will be referenced in channel
        # configuration transactions.
        # Name can include alphanumeric characters as well as dots and dashes.
        Name: SampleOrg

        # ID is the key by which this org's MSP definition will be referenced.
        # ID can include alphanumeric characters as well as dots and dashes.
        ID: DEFAULT

        # MSPDir is the filesystem path which contains the MSP configuration.
        MSPDir: msp

        # AdminPrincipal dictates the type of principal used for an
        # organization's Admins policy. Today, only the values of Role.ADMIN and
        # Role.MEMBER are accepted, which indicates a principal of role type
        # ADMIN and role type MEMBER respectively.
        AdminPrincipal: Role.ADMIN

        # AnchorPeers defines the location of peers which can be used for
        # cross-org gossip communication. Note, this value is only encoded in
        # the genesis block in the Application section context.
        AnchorPeers:
            - Host: 127.0.0.1
              Port: 7051

################################################################################
#
#   ORDERER
#
#   This section defines the values to encode into a config transaction or
#   genesis block for orderer related parameters.
#
################################################################################
Orderer: &OrdererDefaults

    # Orderer Type: The orderer implementation to start.
    # Available types are "solo" and "kafka".
    OrdererType: solo
    
    # Addresses here is a nonexhaustive list of orderers the peers and clients can 
    # connect to. Adding/removing nodes from this list has no impact on their 
    # participation in ordering. 
    # NOTE: In the solo case, this should be a one-item list.
    Addresses:
        - 172.17.0.6:7050

    # Batch Timeout: The amount of time to wait before creating a batch.
    BatchTimeout: 2s

    # Batch Size: Controls the number of messages batched into a block.
    BatchSize:

        # Max Message Count: The maximum number of messages to permit in a
        # batch.
        MaxMessageCount: 10

        # Absolute Max Bytes: The absolute maximum number of bytes allowed for
        # the serialized messages in a batch. If the "kafka" OrdererType is
        # selected, set 'message.max.bytes' and 'replica.fetch.max.bytes' on
        # the Kafka brokers to a value that is larger than this one.
        AbsoluteMaxBytes: 10 MB

        # Preferred Max Bytes: The preferred maximum number of bytes allowed
        # for the serialized messages in a batch. A message larger than the
        # preferred max bytes will result in a batch larger than preferred max
        # bytes.
        PreferredMaxBytes: 512 KB

    # Max Channels is the maximum number of channels to allow on the ordering
    # network. When set to 0, this implies no maximum number of channels.
    MaxChannels: 0

    Kafka:
        # Brokers: A list of Kafka brokers to which the orderer connects. Edit
        # this list to identify the brokers of the ordering service.
        # NOTE: Use IP:port notation.
        Brokers:
            - kafka0:9092
            - kafka1:9092
            - kafka2:9092

    #JCS: BFT-SMaRt options
    BFTsmart:

        # ConnectionPoolSize: The size of the connection pool that links the golang component to the java component.
        ConnectionPoolSize: 20

        # RecvPort: The localhost TCP port from which the java component sends blocks to the golang component.
        RecvPort: 9999

    # Organizations lists the orgs participating on the orderer side of the
    # network.
    Organizations:

################################################################################
#
#   APPLICATION
#
#   This section defines the values to encode into a config transaction or
#   genesis block for application-related parameters.
#
################################################################################
Application: &ApplicationDefaults

    # Organizations lists the orgs participating on the application side of the
    # network.
    Organizations:

################################################################################
#
#   CAPABILITIES
#
#   This section defines the capabilities of fabric network. This is a new
#   concept as of v1.1.0 and should not be utilized in mixed networks with
#   v1.0.x peers and orderers.  Capabilities define features which must be
#   present in a fabric binary for that binary to safely participate in the
#   fabric network.  For instance, if a new MSP type is added, newer binaries
#   might recognize and validate the signatures from this type, while older
#   binaries without this support would be unable to validate those
#   transactions.  This could lead to different versions of the fabric binaries
#   having different world states.  Instead, defining a capability for a channel
#   informs those binaries without this capability that they must cease
#   processing transactions until they have been upgraded.  For v1.0.x if any
#   capabilities are defined (including a map with all capabilities turned off)
#   then the v1.0.x peer will deliberately crash.
#
################################################################################
Capabilities:
    # Channel capabilities apply to both the orderers and the peers and must be
    # supported by both.  Set the value of the capability to true to require it.
    Channel: &ChannelCapabilities
        # V1.1 for Channel is a catchall flag for behavior which has been
        # determined to be desired for all orderers and peers running v1.0.x,
        # but the modification of which would cause incompatibilities.  Users
        # should leave this flag set to true.
        V1_1: true

    # Orderer capabilities apply only to the orderers, and may be safely
    # manipulated without concern for upgrading peers.  Set the value of the
    # capability to true to require it.
    Orderer: &OrdererCapabilities
        # V1.1 for Order is a catchall flag for behavior which has been
        # determined to be desired for all orderers running v1.0.x, but the
        # modification of which  would cause incompatibilities.  Users should
        # leave this flag set to true.
        V1_1: true

    # Application capabilities apply only to the peer network, and may be
    # safely manipulated without concern for upgrading orderers.  Set the value
    # of the capability to true to require it.
    Application: &ApplicationCapabilities
        # V1.1 for Application is a catchall flag for behavior which has been
        # determined to be desired for all peers running v1.0.x, but the
        # modification of which would cause incompatibilities.  Users should
        # leave this flag set to true.
        V1_1: true
        # V1_1_PVTDATA_EXPERIMENTAL is an Application capability to enable the
        # private data capability.  It is only supported when using peers built
        # with experimental build tag.  When set to true, private data
        # collections can be configured upon chaincode instantiation and
        # utilized within chaincode Invokes.
        # NOTE: Use of this feature with non "experimental" binaries on the
        # network may cause a fork.
        V1_1_PVTDATA_EXPERIMENTAL: false
        # V1_1_RESOURCETREE_EXPERIMENTAL is an Application capability to enable
        # the resources capability. Currently this is needed for defining
        # resource-based access control (RBAC). RBAC helps set fine-grained
        # access control on system resources such as the endorser and various
        # system chaincodes. Default is v1.0-based access control based on
        # CHANNEL_READERS and CHANNEL_WRITERS.
        # NOTE: Use of this feature with non "experimental" binaries on
        # the network may cause a fork.
        V1_1_RESOURCETREE_EXPERIMENTAL: false

EOF
}

function create_fabric_orderer () {

cat > ./temp/fabric_conf/orderer.yaml << 'EOF'

# Copyright IBM Corp. All Rights Reserved.
#
# SPDX-License-Identifier: Apache-2.0
#

---
################################################################################
#
#   Orderer Configuration
#
#   - This controls the type and configuration of the orderer.
#
################################################################################
General:

    # Ledger Type: The ledger type to provide to the orderer.
    # Two non-production ledger types are provided for test purposes only:
    #  - ram: An in-memory ledger whose contents are lost on restart.
    #  - json: A simple file ledger that writes blocks to disk in JSON format.
    # Only one production ledger type is provided:
    #  - file: A production file-based ledger.
    LedgerType: ram

    # Listen address: The IP on which to bind to listen.
    ListenAddress: 0.0.0.0

    # Listen port: The port on which to bind to listen.
    ListenPort: 7050

    # TLS: TLS settings for the GRPC server.
    TLS:
        Enabled: false
        PrivateKey: tls/server.key
        Certificate: tls/server.crt
        RootCAs:
          - tls/ca.crt
        ClientAuthRequired: false
        ClientRootCAs:

    # Keepalive settings for the GRPC server.
    Keepalive:
        # ServerMinInterval is the minimum permitted time between client pings.
        # If clients send pings more frequently, the server will
        # disconnect them.
        ServerMinInterval: 60s
        # ServerInterval is the time between pings to clients.
        ServerInterval: 7200s
        # ServerTimeout is the duration the server waits for a response from
        # a client before closing the connection.
        ServerTimeout: 20s

    # Log Level: The level at which to log. This accepts logging specifications
    # per: fabric/docs/Setup/logging-control.md
    LogLevel: info

    # Log Format:  The format string to use when logging.  Especially useful to disable color logging
    LogFormat: '%{color}%{time:2006-01-02 15:04:05.000 MST} [%{module}] %{shortfunc} -> %{level:.4s} %{id:03x}%{color:reset} %{message}'

    # Genesis method: The method by which the genesis block for the orderer
    # system channel is specified. Available options are "provisional", "file":
    #  - provisional: Utilizes a genesis profile, specified by GenesisProfile,
    #                 to dynamically generate a new genesis block.
    #  - file: Uses the file provided by GenesisFile as the genesis block.
    GenesisMethod: file

    # Genesis profile: The profile to use to dynamically generate the genesis
    # block to use when initializing the orderer system channel and
    # GenesisMethod is set to "provisional". See the configtx.yaml file for the
    # descriptions of the available profiles. Ignored if GenesisMethod is set to
    # "file".
    GenesisProfile: SampleSingleMSPBFTsmart

    # Genesis file: The file containing the genesis block to use when
    # initializing the orderer system channel and GenesisMethod is set to
    # "file". Ignored if GenesisMethod is set to "provisional".
    GenesisFile: /etc/bftsmart-orderer/config/genesisblock

    # LocalMSPDir is where to find the private crypto material needed by the
    # orderer. It is set relative here as a default for dev environments but
    # should be changed to the real location in production.
    LocalMSPDir: msp

    # LocalMSPID is the identity to register the local MSP material with the MSP
    # manager. IMPORTANT: The local MSP ID of an orderer needs to match the MSP
    # ID of one of the organizations defined in the orderer system channel's
    # /Channel/Orderer configuration. The sample organization defined in the
    # sample configuration provided has an MSP ID of "DEFAULT".
    LocalMSPID: DEFAULT

    # Enable an HTTP service for Go "pprof" profiling as documented at:
    # https://golang.org/pkg/net/http/pprof
    Profile:
        Enabled: false
        Address: 0.0.0.0:6060

    # BCCSP configures the blockchain crypto service providers.
    BCCSP:
        # Default specifies the preferred blockchain crypto service provider
        # to use. If the preferred provider is not available, the software
        # based provider ("SW") will be used.
        # Valid providers are:
        #  - SW: a software based crypto provider
        #  - PKCS11: a CA hardware security module crypto provider.
        Default: SW

        # SW configures the software based blockchain crypto provider.
        SW:
            # TODO: The default Hash and Security level needs refactoring to be
            # fully configurable. Changing these defaults requires coordination
            # SHA2 is hardcoded in several places, not only BCCSP
            Hash: SHA2
            Security: 256
            # Location of key store. If this is unset, a location will be
            # chosen using: 'LocalMSPDir'/keystore
            FileKeyStore:
                KeyStore:

    # Authentication contains configuration parameters related to authenticating
    # client messages
    Authentication:
        # the acceptable difference between the current server time and the
        # client's time as specified in a client request message
        TimeWindow: 15m

################################################################################
#
#   SECTION: File Ledger
#
#   - This section applies to the configuration of the file or json ledgers.
#
################################################################################
FileLedger:

    # Location: The directory to store the blocks in.
    # NOTE: If this is unset, a new temporary location will be chosen every time
    # the orderer is restarted, using the prefix specified by Prefix.
    Location: /var/hyperledger/production/orderer

    # The prefix to use when generating a ledger directory in temporary space.
    # Otherwise, this value is ignored.
    Prefix: hyperledger-fabric-ordererledger

################################################################################
#
#   SECTION: RAM Ledger
#
#   - This section applies to the configuration of the RAM ledger.
#
################################################################################
RAMLedger:

    # History Size: The number of blocks that the RAM ledger is set to retain.
    # WARNING: Appending a block to the ledger might cause the oldest block in
    # the ledger to be dropped in order to limit the number total number blocks
    # to HistorySize. For example, if history size is 10, when appending block
    # 10, block 0 (the genesis block!) will be dropped to make room for block 10.
    HistorySize: 1000

################################################################################
#
#   SECTION: Kafka
#
#   - This section applies to the configuration of the Kafka-based orderer, and
#     its interaction with the Kafka cluster.
#
################################################################################
Kafka:

    # Retry: What do if a connection to the Kafka cluster cannot be established,
    # or if a metadata request to the Kafka cluster needs to be repeated.
    Retry:
        # When a new channel is created, or when an existing channel is reloaded
        # (in case of a just-restarted orderer), the orderer interacts with the
        # Kafka cluster in the following ways:
        # 1. It creates a Kafka producer (writer) for the Kafka partition that
        # corresponds to the channel.
        # 2. It uses that producer to post a no-op CONNECT message to that
        # partition
        # 3. It creates a Kafka consumer (reader) for that partition.
        # If any of these steps fail, they will be re-attempted every
        # <ShortInterval> for a total of <ShortTotal>, and then every
        # <LongInterval> for a total of <LongTotal> until they succeed.
        # Note that the orderer will be unable to write to or read from a
        # channel until all of the steps above have been completed successfully.
        ShortInterval: 5s
        ShortTotal: 10m
        LongInterval: 5m
        LongTotal: 12h
        # Affects the socket timeouts when waiting for an initial connection, a
        # response, or a transmission. See Config.Net for more info:
        # https://godoc.org/github.com/Shopify/sarama#Config
        NetworkTimeouts:
            DialTimeout: 10s
            ReadTimeout: 10s
            WriteTimeout: 10s
        # Affects the metadata requests when the Kafka cluster is in the middle
        # of a leader election.See Config.Metadata for more info:
        # https://godoc.org/github.com/Shopify/sarama#Config
        Metadata:
            RetryBackoff: 250ms
            RetryMax: 3
        # What to do if posting a message to the Kafka cluster fails. See
        # Config.Producer for more info:
        # https://godoc.org/github.com/Shopify/sarama#Config
        Producer:
            RetryBackoff: 100ms
            RetryMax: 3
        # What to do if reading from the Kafka cluster fails. See
        # Config.Consumer for more info:
        # https://godoc.org/github.com/Shopify/sarama#Config
        Consumer:
            RetryBackoff: 2s

    # Verbose: Enable logging for interactions with the Kafka cluster.
    Verbose: false

    # TLS: TLS settings for the orderer's connection to the Kafka cluster.
    TLS:

      # Enabled: Use TLS when connecting to the Kafka cluster.
      Enabled: false

      # PrivateKey: PEM-encoded private key the orderer will use for
      # authentication.
      PrivateKey:
        # As an alternative to specifying the PrivateKey here, uncomment the
        # following "File" key and specify the file name from which to load the
        # value of PrivateKey.
        #File: path/to/PrivateKey

      # Certificate: PEM-encoded signed public key certificate the orderer will
      # use for authentication.
      Certificate:
        # As an alternative to specifying the Certificate here, uncomment the
        # following "File" key and specify the file name from which to load the
        # value of Certificate.
        #File: path/to/Certificate

      # RootCAs: PEM-encoded trusted root certificates used to validate
      # certificates from the Kafka cluster.
      RootCAs:
        # As an alternative to specifying the RootCAs here, uncomment the
        # following "File" key and specify the file name from which to load the
        # value of RootCAs.
        #File: path/to/RootCAs

    # Kafka protocol version used to communicate with the Kafka cluster brokers
    # (defaults to 0.10.2.0 if not specified)
    Version:

# JCS: new section for bftsmart
################################################################################
#
#   SECTION: BFT-SMaRt
#
#   - This section applies to the configuration of the bftsmart-based orderer.
#
################################################################################
BFTsmart:

    # ConnectionPoolSize: The size of the connection pool that links the golang component to the java component.
    ConnectionPoolSize: 10

    # RecvPort: The localhost TCP port from which the java component sends blocks to the golang component.
    RecvPort: 9999

################################################################################
#
#   Debug Configuration
#
#   - This controls the debugging options for the orderer
#
################################################################################
Debug:

    # BroadcastTraceDir when set will cause each request to the Broadcast service
    # for this orderer to be written to a file in this directory
    BroadcastTraceDir:

    # DeliverTraceDir when set will cause each request to the Deliver service
    # for this orderer to be written to a file in this directory
    DeliverTraceDir:

EOF
}

function create_fabric_core () {

cat > ./temp/fabric_conf/core.yaml << 'EOF'

# Copyright IBM Corp. All Rights Reserved.
#
# SPDX-License-Identifier: Apache-2.0
#

###############################################################################
#
#    LOGGING section
#
###############################################################################
logging:

    # Default logging levels are specified here.

    # Valid logging levels are case-insensitive strings chosen from

    #     CRITICAL | ERROR | WARNING | NOTICE | INFO | DEBUG

    # The overall default logging level can be specified in various ways,
    # listed below from strongest to weakest:
    #
    # 1. The --logging-level=<level> command line option overrides all other
    #    default specifications.
    #
    # 2. The environment variable CORE_LOGGING_LEVEL otherwise applies to
    #    all peer commands if defined as a non-empty string.
    #
    # 3. The value of `level` that directly follows in this file.
    #
    # If no overall default level is provided via any of the above methods,
    # the peer will default to INFO (the value of defaultLevel in
    # common/flogging/logging.go)

    # Default for all modules running within the scope of a peer.
    # Note: this value is only used when --logging-level or CORE_LOGGING_LEVEL
    #       are not set
    level:       info

    # The overall default values mentioned above can be overridden for the
    # specific components listed in the override section below.

    # Override levels for various peer modules. These levels will be
    # applied once the peer has completely started. They are applied at this
    # time in order to be sure every logger has been registered with the
    # logging package.
    # Note: the modules listed below are the only acceptable modules at this
    #       time.
    cauthdsl:   warning
    gossip:     warning
    grpc:       error
    ledger:     info
    msp:        warning
    policies:   warning
    peer:
        gossip: warning

    # Message format for the peer logs
    format: '%{color}%{time:2006-01-02 15:04:05.000 MST} [%{module}] %{shortfunc} -> %{level:.4s} %{id:03x}%{color:reset} %{message}'

###############################################################################
#
#    Peer section
#
###############################################################################
peer:

    # The Peer id is used for identifying this Peer instance.
    id: jdoe

    # The networkId allows for logical seperation of networks
    networkId: dev

    # The Address at local network interface this Peer will listen on.
    # By default, it will listen on all network interfaces
    listenAddress: 0.0.0.0:7051

    # The endpoint this peer uses to listen for inbound chaincode connections.
    # If this is commented-out, the listen address is selected to be
    # the peer's address (see below) with port 7052
    # chaincodeListenAddress: 0.0.0.0:7052

    # The endpoint the chaincode for this peer uses to connect to the peer.
    # If this is not specified, the chaincodeListenAddress address is selected.
    # And if chaincodeListenAddress is not specified, address is selected from
    # peer listenAddress.
    # chaincodeAddress: 0.0.0.0:7052

    # When used as peer config, this represents the endpoint to other peers
    # in the same organization. For peers in other organization, see
    # gossip.externalEndpoint for more info.
    # When used as CLI config, this means the peer's endpoint to interact with
    address: 172.17.0.7:7051

    # Whether the Peer should programmatically determine its address
    # This case is useful for docker containers.
    addressAutoDetect: false

    # Setting for runtime.GOMAXPROCS(n). If n < 1, it does not change the
    # current setting
    gomaxprocs: -1

    # Keepalive settings for peer server and clients
    keepalive:
        # MinInterval is the minimum permitted time between client pings.
        # If clients send pings more frequently, the peer server will
        # disconnect them
        minInterval: 60s
        # Client keepalive settings for communicating with other peer nodes
        client:
            # Interval is the time between pings to peer nodes.  This must
            # greater than or equal to the minInterval specified by peer
            # nodes
            interval: 60s
            # Timeout is the duration the client waits for a response from
            # peer nodes before closing the connection
            timeout: 20s
        # DeliveryClient keepalive settings for communication with ordering
        # nodes.
        deliveryClient:
            # Interval is the time between pings to ordering nodes.  This must
            # greater than or equal to the minInterval specified by ordering
            # nodes.
            interval: 60s
            # Timeout is the duration the client waits for a response from
            # ordering nodes before closing the connection
            timeout: 20s


    # Gossip related configuration
    gossip:
        # Bootstrap set to initialize gossip with.
        # This is a list of other peers that this peer reaches out to at startup.
        # Important: The endpoints here have to be endpoints of peers in the same
        # organization, because the peer would refuse connecting to these endpoints
        # unless they are in the same organization as the peer.
        bootstrap: 127.0.0.1:7051

        # NOTE: orgLeader and useLeaderElection parameters are mutual exclusive.
        # Setting both to true would result in the termination of the peer
        # since this is undefined state. If the peers are configured with
        # useLeaderElection=false, make sure there is at least 1 peer in the
        # organization that its orgLeader is set to true.

        # Defines whenever peer will initialize dynamic algorithm for
        # "leader" selection, where leader is the peer to establish
        # connection with ordering service and use delivery protocol
        # to pull ledger blocks from ordering service. It is recommended to
        # use leader election for large networks of peers.
        useLeaderElection: true
        # Statically defines peer to be an organization "leader",
        # where this means that current peer will maintain connection
        # with ordering service and disseminate block across peers in
        # its own organization
        orgLeader: false

        # Overrides the endpoint that the peer publishes to peers
        # in its organization. For peers in foreign organizations
        # see 'externalEndpoint'
        endpoint:
        # Maximum count of blocks stored in memory
        maxBlockCountToStore: 100
        # Max time between consecutive message pushes(unit: millisecond)
        maxPropagationBurstLatency: 10ms
        # Max number of messages stored until a push is triggered to remote peers
        maxPropagationBurstSize: 10
        # Number of times a message is pushed to remote peers
        propagateIterations: 1
        # Number of peers selected to push messages to
        propagatePeerNum: 3
        # Determines frequency of pull phases(unit: second)
        pullInterval: 4s
        # Number of peers to pull from
        pullPeerNum: 3
        # Determines frequency of pulling state info messages from peers(unit: second)
        requestStateInfoInterval: 4s
        # Determines frequency of pushing state info messages to peers(unit: second)
        publishStateInfoInterval: 4s
        # Maximum time a stateInfo message is kept until expired
        stateInfoRetentionInterval:
        # Time from startup certificates are included in Alive messages(unit: second)
        publishCertPeriod: 10s
        # Should we skip verifying block messages or not (currently not in use)
        skipBlockVerification: false
        # Dial timeout(unit: second)
        dialTimeout: 3s
        # Connection timeout(unit: second)
        connTimeout: 2s
        # Buffer size of received messages
        recvBuffSize: 20
        # Buffer size of sending messages
        sendBuffSize: 200
        # Time to wait before pull engine processes incoming digests (unit: second)
        digestWaitTime: 1s
        # Time to wait before pull engine removes incoming nonce (unit: second)
        requestWaitTime: 1s
        # Time to wait before pull engine ends pull (unit: second)
        responseWaitTime: 2s
        # Alive check interval(unit: second)
        aliveTimeInterval: 5s
        # Alive expiration timeout(unit: second)
        aliveExpirationTimeout: 25s
        # Reconnect interval(unit: second)
        reconnectInterval: 25s
        # This is an endpoint that is published to peers outside of the organization.
        # If this isn't set, the peer will not be known to other organizations.
        externalEndpoint:
        # Leader election service configuration
        election:
            # Longest time peer waits for stable membership during leader election startup (unit: second)
            startupGracePeriod: 15s
            # Interval gossip membership samples to check its stability (unit: second)
            membershipSampleInterval: 1s
            # Time passes since last declaration message before peer decides to perform leader election (unit: second)
            leaderAliveThreshold: 10s
            # Time between peer sends propose message and declares itself as a leader (sends declaration message) (unit: second)
            leaderElectionDuration: 5s

        pvtData:
            # pullRetryThreshold determines the maximum duration of time private data corresponding for a given block
            # would be attempted to be pulled from peers until the block would be committed without the private data
            pullRetryThreshold: 60s
            # As private data enters the transient store, it is associated with the peer's ledger's height at that time.
            # transientstoreMaxBlockRetention defines the maximum difference between the current ledger's height upon commit,
            # and the private data residing inside the transient store that is guaranteed not to be purged.
            # Private data is purged from the transient store when blocks with sequences that are multiples
            # of transientstoreMaxBlockRetention are committed.
            transientstoreMaxBlockRetention: 1000
            # pushAckTimeout is the maximum time to wait for an acknowledgement from each peer
            # at private data push at endorsement time.
            pushAckTimeout: 3s

    # EventHub related configuration
    events:
        # The address that the Event service will be enabled on the peer
        address: 0.0.0.0:7053

        # total number of events that could be buffered without blocking send
        buffersize: 100

        # timeout duration for producer to send an event.
        # if < 0, if buffer full, unblocks immediately and not send
        # if 0, if buffer full, will block and guarantee the event will be sent out
        # if > 0, if buffer full, blocks till timeout
        timeout: 10ms

        # timewindow is the acceptable difference between the peer's current
        # time and the client's time as specified in a registration event
        timewindow: 15m

        # Keepalive settings for peer server and clients
        keepalive:
            # MinInterval is the minimum permitted time in seconds which clients
            # can send keepalive pings.  If clients send pings more frequently,
            # the events server will disconnect them
            minInterval: 60s

    # TLS Settings
    # Note that peer-chaincode connections through chaincodeListenAddress is
    # not mutual TLS auth. See comments on chaincodeListenAddress for more info
    tls:
        # Require server-side TLS
        enabled:  false
        # Require client certificates / mutual TLS.
        # Note that clients that are not configured to use a certificate will
        # fail to connect to the peer.
        clientAuthRequired: false
        # X.509 certificate used for TLS server
        cert:
            file: tls/server.crt
        # Private key used for TLS server (and client if clientAuthEnabled
        # is set to true
        key:
            file: tls/server.key
        # Trusted root certificate chain for tls.cert
        rootcert:
            file: tls/ca.crt
        # Set of root certificate authorities used to verify client certificates
        clientRootCAs:
            files:
              - tls/ca.crt
        # Private key used for TLS when making client connections.  If
        # not set, peer.tls.key.file will be used instead
        clientKey:
            file:
        # X.509 certificate used for TLS when making client connections.
        # If not set, peer.tls.cert.file will be used instead
        clientCert:
            file:

    # Authentication contains configuration parameters related to authenticating
    # client messages
    authentication:
        # the acceptable difference between the current server time and the
        # client's time as specified in a client request message
        timewindow: 15m

    # Path on the file system where peer will store data (eg ledger). This
    # location must be access control protected to prevent unintended
    # modification that might corrupt the peer operations.
    fileSystemPath: /var/hyperledger/production

    # BCCSP (Blockchain crypto provider): Select which crypto implementation or
    # library to use
    BCCSP:
        Default: SW
        SW:
            # TODO: The default Hash and Security level needs refactoring to be
            # fully configurable. Changing these defaults requires coordination
            # SHA2 is hardcoded in several places, not only BCCSP
            Hash: SHA2
            Security: 256
            # Location of Key Store
            FileKeyStore:
                # If "", defaults to 'mspConfigPath'/keystore
                # TODO: Ensure this is read with fabric/core/config.GetPath() once ready
                KeyStore:

    # Path on the file system where peer will find MSP local configurations
    mspConfigPath: msp

    # Identifier of the local MSP
    # ----!!!!IMPORTANT!!!-!!!IMPORTANT!!!-!!!IMPORTANT!!!!----
    # Deployers need to change the value of the localMspId string.
    # In particular, the name of the local MSP ID of a peer needs
    # to match the name of one of the MSPs in each of the channel
    # that this peer is a member of. Otherwise this peer's messages
    # will not be identified as valid by other nodes.
    localMspId: DEFAULT

    # Delivery service related config
    deliveryclient:
        # It sets the total time the delivery service may spend in reconnection
        # attempts until its retry logic gives up and returns an error
        reconnectTotalTimeThreshold: 3600s

    # Type for the local MSP - by default it's of type bccsp
    localMspType: bccsp

    # Used with Go profiling tools only in none production environment. In
    # production, it should be disabled (eg enabled: false)
    profile:
        enabled:     false
        listenAddress: 0.0.0.0:6060

    # Handlers defines custom handlers that can filter and mutate
    # objects passing within the peer, such as:
    #   Auth filter - reject or forward proposals from clients
    #   Decorators  - append or mutate the chaincode input passed to the chaincode
    # Valid handler definition contains:
    #   - A name which is a factory method name defined in
    #     core/handlers/library/library.go for statically compiled handlers
    #   - library path to shared object binary for pluggable filters
    # Auth filters and decorators are chained and executed in the order that
    # they are defined. For example:
    # authFilters:
    #   -
    #     name: FilterOne
    #     library: /opt/lib/filter.so
    #   -
    #     name: FilterTwo
    # decorators:
    #   -
    #     name: DecoratorOne
    #   -
    #     name: DecoratorTwo
    #     library: /opt/lib/decorator.so
    handlers:
        authFilters:
          -
            name: DefaultAuth
          -
            name: ExpirationCheck    # This filter checks identity x509 certificate expiration
        decorators:
          -
            name: DefaultDecorator

    # Number of goroutines that will execute transaction validation in parallel.
    # By default, the peer chooses the number of CPUs on the machine. Set this
    # variable to override that choice.
    # NOTE: overriding this value might negatively influence the performance of
    # the peer so please change this value only if you know what you're doing
    validatorPoolSize:

###############################################################################
#
#    VM section
#
###############################################################################
vm:

    # Endpoint of the vm management system.  For docker can be one of the following in general
    # unix:///var/run/docker.sock
    # http://localhost:2375
    # https://localhost:2376
    endpoint: unix:///var/run/docker.sock

    # settings for docker vms
    docker:
        tls:
            enabled: false
            ca:
                file: docker/ca.crt
            cert:
                file: docker/tls.crt
            key:
                file: docker/tls.key

        # Enables/disables the standard out/err from chaincode containers for
        # debugging purposes
        attachStdout: false

        # Parameters on creating docker container.
        # Container may be efficiently created using ipam & dns-server for cluster
        # NetworkMode - sets the networking mode for the container. Supported
        # standard values are: `host`(default),`bridge`,`ipvlan`,`none`.
        # Dns - a list of DNS servers for the container to use.
        # Note:  `Privileged` `Binds` `Links` and `PortBindings` properties of
        # Docker Host Config are not supported and will not be used if set.
        # LogConfig - sets the logging driver (Type) and related options
        # (Config) for Docker. For more info,
        # https://docs.docker.com/engine/admin/logging/overview/
        # Note: Set LogConfig using Environment Variables is not supported.
        hostConfig:
            NetworkMode: host
            Dns:
               # - 192.168.0.1
            LogConfig:
                Type: json-file
                Config:
                    max-size: "50m"
                    max-file: "5"
            Memory: 2147483648

###############################################################################
#
#    Chaincode section
#
###############################################################################
chaincode:

    # The id is used by the Chaincode stub to register the executing Chaincode
    # ID with the Peer and is generally supplied through ENV variables
    # the `path` form of ID is provided when installing the chaincode.
    # The `name` is used for all other requests and can be any string.
    id:
        path:
        name:

    # Generic builder environment, suitable for most chaincode types
    #builder: $(DOCKER_NS)/fabric-ccenv:$(ARCH)-$(PROJECT_VERSION)
    builder: $(DOCKER_NS)/fabric-ccenv:$(ARCH)-1.1.1

    # Enables/disables force pulling of the base docker images (listed below)
    # during user chaincode instantiation.
    # Useful when using moving image tags (such as :latest)
    pull: false

    golang:
        # golang will never need more than baseos
        runtime: $(BASE_DOCKER_NS)/fabric-baseos:$(ARCH)-$(BASE_VERSION)

        # whether or not golang chaincode should be linked dynamically
        dynamicLink: false

    car:
        # car may need more facilities (JVM, etc) in the future as the catalog
        # of platforms are expanded.  For now, we can just use baseos
        runtime: $(BASE_DOCKER_NS)/fabric-baseos:$(ARCH)-$(BASE_VERSION)

    java:
        # This is an image based on java:openjdk-8 with addition compiler
        # tools added for java shim layer packaging.
        # This image is packed with shim layer libraries that are necessary
        # for Java chaincode runtime.
        Dockerfile:  |
            from $(DOCKER_NS)/fabric-javaenv:$(ARCH)-$(PROJECT_VERSION)

    node:
        # need node.js engine at runtime, currently available in baseimage
        # but not in baseos
        runtime: $(BASE_DOCKER_NS)/fabric-baseimage:$(ARCH)-$(BASE_VERSION)

    # Timeout duration for starting up a container and waiting for Register
    # to come through. 1sec should be plenty for chaincode unit tests
    startuptimeout: 300s

    # Timeout duration for Invoke and Init calls to prevent runaway.
    # This timeout is used by all chaincodes in all the channels, including
    # system chaincodes.
    # Note that during Invoke, if the image is not available (e.g. being
    # cleaned up when in development environment), the peer will automatically
    # build the image, which might take more time. In production environment,
    # the chaincode image is unlikely to be deleted, so the timeout could be
    # reduced accordingly.
    executetimeout: 30s

    # There are 2 modes: "dev" and "net".
    # In dev mode, user runs the chaincode after starting peer from
    # command line on local machine.
    # In net mode, peer will run chaincode in a docker container.
    mode: net

    # keepalive in seconds. In situations where the communiction goes through a
    # proxy that does not support keep-alive, this parameter will maintain connection
    # between peer and chaincode.
    # A value <= 0 turns keepalive off
    keepalive: 0

    # system chaincodes whitelist. To add system chaincode "myscc" to the
    # whitelist, add "myscc: enable" to the list below, and register in
    # chaincode/importsysccs.go
    system:
        cscc: enable
        lscc: enable
        escc: enable
        vscc: enable
        qscc: enable

    # System chaincode plugins: in addition to being imported and compiled
    # into fabric through core/chaincode/importsysccs.go, system chaincodes
    # can also be loaded as shared objects compiled as Go plugins.
    # See examples/plugins/scc for an example.
    # Like regular system chaincodes, plugins must also be white listed in the
    # chaincode.system section above.
    systemPlugins:
      # example configuration:
      # - enabled: true
      #   name: myscc
      #   path: /opt/lib/myscc.so
      #   invokableExternal: true
      #   invokableCC2CC: true

    # Logging section for the chaincode container
    logging:
      # Default level for all loggers within the chaincode container
      level:  info
      # Override default level for the 'shim' module
      shim:   warning
      # Format for the chaincode container logs
      format: '%{color}%{time:2006-01-02 15:04:05.000 MST} [%{module}] %{shortfunc} -> %{level:.4s} %{id:03x}%{color:reset} %{message}'

###############################################################################
#
#    Ledger section - ledger configuration encompases both the blockchain
#    and the state
#
###############################################################################
ledger:

  blockchain:

  state:
    # stateDatabase - options are "goleveldb", "CouchDB"
    # goleveldb - default state database stored in goleveldb.
    # CouchDB - store state database in CouchDB
    stateDatabase: goleveldb
    couchDBConfig:
       # It is recommended to run CouchDB on the same server as the peer, and
       # not map the CouchDB container port to a server port in docker-compose.
       # Otherwise proper security must be provided on the connection between
       # CouchDB client (on the peer) and server.
       couchDBAddress: 127.0.0.1:5984
       # This username must have read and write authority on CouchDB
       username:
       # The password is recommended to pass as an environment variable
       # during start up (eg LEDGER_COUCHDBCONFIG_PASSWORD).
       # If it is stored here, the file must be access control protected
       # to prevent unintended users from discovering the password.
       password:
       # Number of retries for CouchDB errors
       maxRetries: 3
       # Number of retries for CouchDB errors during peer startup
       maxRetriesOnStartup: 10
       # CouchDB request timeout (unit: duration, e.g. 20s)
       requestTimeout: 35s
       # Limit on the number of records to return per query
       queryLimit: 10000
       # Limit on the number of records per CouchDB bulk update batch
       maxBatchUpdateSize: 1000
       # Warm indexes after every N blocks.
       # This option warms any indexes that have been
       # deployed to CouchDB after every N blocks.
       # A value of 1 will warm indexes after every block commit,
       # to ensure fast selector queries.
       # Increasing the value may improve write efficiency of peer and CouchDB,
       # but may degrade query response time.
       warmIndexesAfterNBlocks: 1

  history:
    # enableHistoryDatabase - options are true or false
    # Indicates if the history of key updates should be stored.
    # All history 'index' will be stored in goleveldb, regardless if using
    # CouchDB or alternate database for the state.
    enableHistoryDatabase: true

###############################################################################
#
#    Metrics section
#
#
###############################################################################
metrics:
        # enable or disable metrics server
        enabled: false

        # when enable metrics server, must specific metrics reporter type
        # currently supported type: "statsd","prom"
        reporter: statsd

        # determines frequency of report metrics(unit: second)
        interval: 1s

        statsdReporter:

              # statsd server address to connect
              address: 0.0.0.0:8125

              # determines frequency of push metrics to statsd server(unit: second)
              flushInterval: 2s

              # max size bytes for each push metrics request
              # intranet recommend 1432 and internet recommend 512
              flushBytes: 1432

        promReporter:

              # prometheus http server listen address for pull metrics
              listenAddress: 0.0.0.0:8080
EOF
}

main $1

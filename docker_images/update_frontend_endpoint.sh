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

#!/bin/bash

function main () {

	docker pull bftsmart/fabric-tools

	docker create --name="cli-temp" "bftsmart/fabric-tools" > /dev/null
	id=$(docker ps -aqf "name=cli-temp")

	if [ ! -d ./cli_material ]; then
	    mkdir ./cli_material
	fi

	if [ ! -d ./cli_material/fabric ]; then

		docker cp $id:/etc/hyperledger/fabric/ ./cli_material/

		if [ -f ./cli_material/fabric/configtx.yaml ]; then

			rm ./cli_material/fabric/configtx.yaml

		fi

		if [ -f ./cli_material/fabric/orderer.yaml ]; then

			rm ./cli_material/fabric/orderer.yaml

		fi
	fi

	docker rm -v $id > /dev/null

	echo ""
	echo "Default configuration available at '$PWD/cli_material/'"
	echo "Generate containers with 'create_tools_container.sh <container name> [peers endpoint]'"
	echo ""
}

main $@

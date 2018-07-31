#!/bin/bash

function main () {

	docker pull bftsmart/fabric-orderingnode

	docker create --name="os-temp" "bftsmart/fabric-orderingnode" > /dev/null
	id=$(docker ps -aqf "name=os-temp")

	if [ ! -d ./orderingnode_material ]; then
	    mkdir ./orderingnode_material
	fi

	if [ ! -f ./orderingnode_material/hosts.config ]; then
		cp ../config/hosts.config ./orderingnode_material
		#nano ./orderingnode_material/hosts.config
	fi


	if [ ! -f ./orderingnode_material/system.config ]; then
		cp ../config/system.config ./orderingnode_material
		#nano ./orderingnode_material/system.config
	fi

	if [ ! -f ./orderingnode_material/genesisblock ]; then
		docker cp $id:/etc/bftsmart-orderer/config/genesisblock ./orderingnode_material
	fi

	if [ ! -f ./orderingnode_material/cert.pem ]; then
		docker cp $id:/etc/bftsmart-orderer/config/cert.pem ./orderingnode_material
	fi

	if [ ! -f ./orderingnode_material/key.pem ]; then
		docker cp $id:/etc/bftsmart-orderer/config/key.pem ./orderingnode_material
	fi

	docker rm -v $id > /dev/null

	echo ""
	echo "Default configuration available at '$PWD/orderingnode_material/'"
	echo "Generate containers with 'create_orderingnode_container.sh <container name> <replica ID>'"
	echo ""
}

main "$@"

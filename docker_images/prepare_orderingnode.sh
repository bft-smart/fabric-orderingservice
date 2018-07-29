#!/bin/bash

function main () {

	#re='^[0-9]+$'

	#if [ $# -eq 0 ] || [[ ! $1 =~ $re ]]; then

	#	echo "Usage: $0 <ordering node ID>"
	#	echo "The ID must be an integer starting from 0 (inclusively)"
	#	exit 1
	#fi

	if [[ "$(docker images -q bftsmart/fabric-frontend 2> /dev/null)" == "" ]]; then

		echo "bftsmart/fabric-frontend missing, either pull it from repository or compile it"
		exit 1
		
	fi

	docker create --name="os-temp" "bftsmart/fabric-orderingnode"
	id=$(docker ps -aqf "name=os-temp")

	if [ ! -d ./orderingnode_material ]; then
	    mkdir ./orderingnode_material
	fi

	if [ ! -f ./orderingnode_material/hosts.config ]; then
		cp ../config/hosts.config ./orderingnode_material
		nano ./orderingnode_material/hosts.config
	fi


	if [ ! -f ./orderingnode_material/system.config ]; then
		cp ../config/system.config ./orderingnode_material
		nano ./orderingnode_material/system.config
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

	docker rm -v $id

	docker-compose build --build-arg prepared_orderingnode
}

main $@

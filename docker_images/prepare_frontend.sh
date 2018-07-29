#!/bin/bash

function main () {

	#re='^[0-9]+$'

	#if [ $# -eq 0 ] || [[ ! $1 =~ $re ]]; then

	#	echo "Usage: $0 <ordering node ID>"
	#	echo "The ID must be an integer starting from 0 (inclusively)"
	#	exit 1
	#fi

	if [[ "$(docker images -q bftsmart/fabric-orderingnode 2> /dev/null)" == "" ]]; then

		echo "bftsmart/fabric-orderingnode missing, either pull it from repository or compile it"
		exit 1
		
	fi

	docker create --name="os-temp" "bftsmart/fabric-frontend"
	id=$(docker ps -aqf "name=os-temp")

	if [ ! -d ./frontend_material ]; then
	    mkdir ./frontend_material
	fi

	if [ ! -f ./frontend_material/hosts.config ]; then
		cp ../config/hosts.config ./frontend_material
		nano ./frontend_material/hosts.config
	fi


	if [ ! -f ./frontend_material/system.config ]; then
		cp ../config/system.config ./frontend_material
		nano ./frontend_material/system.config
	fi

	if [ ! -f ./frontend_material/genesisblock ]; then
		docker cp $id:/etc/bftsmart-orderer/config/genesisblock ./frontend_material
	fi

	if [ ! -d ./frontend_material/fabric ]; then

		docker cp $id:/etc/hyperledger/fabric/ ./frontend_material/

		if [ -f ./frontend_material/fabric/configtx.yaml ]; then

			rm ./frontend_material/fabric/configtx.yaml

		fi

		if [ -f ./frontend_material/fabric/core.yaml ]; then

			rm ./frontend_material/fabric/core.yaml

		fi

	fi	

	docker rm -v $id

	docker-compose build prepared_frontend
}

main $@

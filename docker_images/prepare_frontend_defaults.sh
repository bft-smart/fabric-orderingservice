#!/bin/bash

function main () {

	docker pull bftsmart/fabric-frontend

	docker create --name="frontend-temp" "bftsmart/fabric-frontend" > /dev/null
	id=$(docker ps -aqf "name=frontend-temp")

	if [ ! -d ./frontend_material ]; then
	    mkdir ./frontend_material
	fi

	if [ ! -f ./frontend_material/hosts.config ]; then
		cp ../config/hosts.config ./frontend_material
		#nano ./frontend_material/hosts.config
	fi


	if [ ! -f ./frontend_material/system.config ]; then
		cp ../config/system.config ./frontend_material
		#nano ./frontend_material/system.config
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

	docker rm -v $id > /dev/null

	echo ""
	echo "Default configuration available at '$PWD/frontend_material/'"
	echo "Generate containers with 'create_frontend_container.sh <container name> <frontend ID>'"
	echo ""
}



main $@

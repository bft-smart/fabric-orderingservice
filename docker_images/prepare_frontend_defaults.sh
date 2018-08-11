#!/bin/bash

function main () {

	dir=./frontend_material

	if [ ! -z $1 ]; then

		dir=$1	
	fi

	docker pull bftsmart/fabric-frontend:x86_64-1.1.1

	docker create --name="frontend-temp" "bftsmart/fabric-frontend:x86_64-1.1.1" > /dev/null
	id=$(docker ps -aqf "name=frontend-temp")

	if [ ! -d $dir ]; then
	    mkdir $dir
	fi

	if [ ! -f $dir/hosts.config ]; then
		cp ../config/hosts.config $dir
	fi


	if [ ! -f $dir/system.config ]; then
		cp ../config/system.config $dir
	fi

	if [ ! -f $dir/node.config ]; then
		cp ../config/node.config $dir

	fi

	if [ ! -f $dir/logback.xml ]; then
		cp ../config/logback.xml $dir

	fi

	if [ ! -d $dir/keys ]; then
		cp -r ../config/keys $dir/

	fi

	if [ ! -f $dir/genesisblock ]; then
		docker cp $id:/etc/bftsmart-orderer/config/genesisblock $dir
	fi

	if [ ! -d $dir/fabric ]; then

		docker cp $id:/etc/hyperledger/fabric/ $dir

		if [ -f $dir/fabric/configtx.yaml ]; then

			rm $dir/fabric/configtx.yaml

		fi

		if [ -f $dir/fabric/core.yaml ]; then

			rm $dir/fabric/core.yaml

		fi

	fi	

	docker rm -v $id > /dev/null

	echo ""
	echo "Default configuration available at '$dir'"
	echo ""
}



main $@

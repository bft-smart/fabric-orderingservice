#!/bin/bash

function main () {

	dir=./cli_material

	if [ ! -z $1 ]; then

		dir=$1	
	fi

	docker pull bftsmart/fabric-tools:x86_64-1.1.1

	docker create --name="cli-temp" "bftsmart/fabric-tools:x86_64-1.1.1" > /dev/null
	id=$(docker ps -aqf "name=cli-temp")

	if [ ! -d $dir ]; then
	    mkdir $dir
	fi

	if [ ! -d $dir/fabric ]; then

		docker cp $id:/etc/hyperledger/fabric/ $dir/

		if [ -f $dir/fabric/configtx.yaml ]; then

			rm .$dir/fabric/configtx.yaml

		fi

		if [ -f $dir/fabric/orderer.yaml ]; then

			rm $dir/fabric/orderer.yaml

		fi
	fi

	docker rm -v $id > /dev/null

	echo ""
	echo "Default configuration available at '$dir'"
	echo ""
}

main $@

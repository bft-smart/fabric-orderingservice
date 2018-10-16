#!/bin/bash

function main () {

	dir=./orderingnode_material

	if [ ! -z $1 ]; then

		dir=$1	
	fi

	docker pull bftsmart/fabric-orderingnode:amd64-1.3.0

	docker create --name="os-temp" "bftsmart/fabric-orderingnode:amd64-1.3.0" > /dev/null
	id=$(docker ps -aqf "name=os-temp")

	if [ ! -d $dir ]; then
	    mkdir $dir
	fi

	if [ ! -d $dir/config ]; then
	    mkdir $dir/config
	fi

	if [ ! -f $dir/hosts.config ]; then
		docker cp $id:/etc/bftsmart-orderer/config/hosts.config $dir/config
	fi


	if [ ! -f $dir/system.config ]; then
		docker cp $id:/etc/bftsmart-orderer/config/system.config $dir/config
	fi

	if [ ! -f $dir/node.config ]; then
		docker cp $id:/etc/bftsmart-orderer/config/node.config $dir/config

	fi

	if [ ! -f $dir/logback.xml ]; then
		docker cp $id:/etc/bftsmart-orderer/config/logback.xml $dir/config

	fi

	if [ ! -d $dir/keys ]; then
		docker cp $id:/etc/bftsmart-orderer/config/keys $dir/config/

	fi

	if [ ! -f $dir/genesisblock ]; then
		docker cp $id:/etc/bftsmart-orderer/config/genesisblock $dir
	fi

	docker rm -v $id > /dev/null

	echo ""
	echo "Default configuration available at '$dir'"
	echo ""
}

main "$@"

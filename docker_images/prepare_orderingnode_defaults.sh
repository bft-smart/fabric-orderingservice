#!/bin/bash

function main () {

	dir=./orderingnode_material

	if [ ! -z $1 ]; then

		dir=$1	
	fi

	docker pull bftsmart/fabric-orderingnode:x86_64-1.1.1

	docker create --name="os-temp" "bftsmart/fabric-orderingnode:x86_64-1.1.1" > /dev/null
	id=$(docker ps -aqf "name=os-temp")

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

	docker rm -v $id > /dev/null

	echo ""
	echo "Default configuration available at '$dir'"
	echo ""
}

main "$@"

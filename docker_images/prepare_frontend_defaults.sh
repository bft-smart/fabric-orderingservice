#!/bin/bash

function main () {

	dir=./frontend_material

	if [ ! -z $1 ]; then

		dir=$1	
	fi

	docker pull bftsmart/fabric-frontend:amd64-1.3.0

	docker create --name="frontend-temp" "bftsmart/fabric-frontend:amd64-1.3.0" > /dev/null
	id=$(docker ps -aqf "name=frontend-temp")

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

	if [ ! -d $dir/fabric ]; then

		docker cp $id:/etc/hyperledger/fabric/ $dir

		if [ -f $dir/fabric/configtx.yaml ]; then

			rm $dir/fabric/configtx.yaml

		fi

		if [ -f $dir/fabric/core.yaml ]; then

			rm $dir/fabric/core.yaml

		fi


		if [ -d $dir/fabric/etcdraft ]; then

			rm -r $dir/fabric/etcdraft/

		fi
	fi	

	docker rm -v $id > /dev/null

	echo ""
	echo "Default configuration available at '$dir'"
	echo ""
}



main $@

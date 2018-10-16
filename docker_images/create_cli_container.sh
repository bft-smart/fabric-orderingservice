#!/bin/bash

function main () {

	if [ $# -eq 0 ]; then

		echo "Usage: $0 <container name> [peers endpoint]"
		echo "If a peers endpoint is given, parameter peer->address in core.yaml is overridden"
		exit 1
	fi

	id=$(docker ps -aqf "name=$1")

	if [ ! -z "$id" ]; then

		echo "Container name already exists"
		exit 1
	fi

	my_contianer_name=$1

	if [ ! -z $2 ]; then
	
		endpoint="-e CORE_PEER_ADDRESS=$2"

	fi

	docker pull bftsmart/fabric-tools:amd64-1.3.0

	docker create -i -t --name=$my_contianer_name $endpoint "bftsmart/fabric-tools:amd64-1.3.0" > /dev/null
	id=$(docker ps -aqf "name=$my_contianer_name")

	echo ""
	echo "Container ID for $my_contianer_name is $id"
	echo "Launch the cli by typing 'docker start -a -i $my_contianer_name'"
	echo "Stop the cli by typing 'docker stop $my_contianer_name'"
	echo ""

}

main $@

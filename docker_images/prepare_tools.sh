#!/bin/bash

function main () {

	if [ -z $2 ]; then

		echo "Usage: $0 <container name> <endpoint to peers>"
		exit 1
	fi

	id=$(docker ps -aqf "name=$1")

	if [ ! -z "$id" ]; then

		echo "Container name already exists"
		exit 1
	fi

	my_contianer_name=$1
	endpoint=$2

	docker pull bftsmart/fabric-tools

	docker create -i -t --name=$my_contianer_name -e core_peer_address=$endpoint "bftsmart/fabric-tools" > /dev/null
	id=$(docker ps -aqf "name=$my_contianer_name")

	echo ""
	echo "Container ID for $my_contianer_name is $id"
	echo "Launch the tools by typing 'docker start -a -i $my_contianer_name'"
	echo "Stop the tools by typing 'docker stop $my_contianer_name'"
	echo ""
}

main $@

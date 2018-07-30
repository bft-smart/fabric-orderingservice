#!/bin/bash

function main () {

	re='^[0-9]+$'

	if [ $# -eq 0 ] || [[ ! $2 =~ $re ]]; then

		echo "Usage: $0 <container name> <replica ID>"
		exit 1
	fi

	id=$(docker ps -aqf "name=$1")

	if [ ! -z "$id" ]; then

		echo "Container name already exists"
		exit 1
	fi

	if [[ "$(docker images -q bftsmart/fabric-orderingnode 2> /dev/null)" == "" ]]; then

		echo "bftsmart/fabric-orderingnode missing, either pull it from repository or compile it"
		exit 1
		
	fi

	my_contianer_name=$1
	my_node_id=$2

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

	my_port=-1
	
	while read line; do

		count=$(wc -w <<< "$line")
		if [ "$count" -eq 3 ]; then
   	 		
			node_id=$(awk '{print $1;}' <<< "$line")
			ip_address=$(awk '{print $2;}' <<< "$line")
			port=$(awk '{print $3;}' <<< "$line")


			if [ "$node_id" -eq "$my_node_id" ]; then

				my_port=$port

			fi


		fi
	done < ./orderingnode_material/hosts.config

	echo "my port is $my_port"

	if [ $my_port -eq -1 ]; then

		echo "Unable to find corresponding port to $my_node_id in hosts.config"
		exit 1

	fi

	my_other_port=$(($my_port+1))

	#docker-compose build --build-arg prepared_orderingnode

	docker create -i -t -p $my_port:$my_port -p $my_other_port:$my_other_port --name=$my_contianer_name "bftsmart/fabric-orderingnode" $my_node_id
	id=$(docker ps -aqf "name=$my_contianer_name")

	docker cp ./orderingnode_material/hosts.config $id:/etc/bftsmart-orderer/config/hosts.config
	docker cp ./orderingnode_material/system.config $id:/etc/bftsmart-orderer/config/system.config
	docker cp ./orderingnode_material/genesisblock $id:/etc/bftsmart-orderer/config/genesisblock
	docker cp ./orderingnode_material/cert.pem $id:/etc/bftsmart-orderer/config/cert.pem
	docker cp ./orderingnode_material/key.pem $id:/etc/bftsmart-orderer/config/key.pem

	echo "Container ID for my_contianer_name is $id"
}

main "$@"

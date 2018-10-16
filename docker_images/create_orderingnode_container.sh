function main() {

	re='^[0-9]+$'

	if [ -z $2 ] || [[ ! $2 =~ $re ]]; then

		echo "Usage: $0 <container name> <replica ID>"
		exit 1
	fi

	id=$(docker ps -aqf "name=$1")

	if [ ! -z "$id" ]; then

		echo "Container name already exists"
		exit 1
	fi

	my_contianer_name=$1
	my_node_id=$2

	docker pull bftsmart/fabric-orderingnode:amd64-1.3.0

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


	if [ $my_port -eq -1 ]; then

		echo ""
		echo "Unable to find corresponding port to $my_node_id in hosts.config"
		echo ""
		exit 1

	fi

	my_other_port=$(($my_port+1))

	#docker-compose build --build-arg prepared_orderingnode

	docker create -i -t -p $my_port:$my_port -p $my_other_port:$my_other_port --name=$my_contianer_name "bftsmart/fabric-orderingnode:amd64-1.3.0" $my_node_id > /dev/null
	id=$(docker ps -aqf "name=$my_contianer_name")

	docker cp ./orderingnode_material/hosts.config $id:/etc/bftsmart-orderer/config/hosts.config
	docker cp ./orderingnode_material/system.config $id:/etc/bftsmart-orderer/config/system.config
	docker cp ./orderingnode_material/genesisblock $id:/etc/bftsmart-orderer/config/genesisblock
	docker cp ./orderingnode_material/cert.pem $id:/etc/bftsmart-orderer/config/cert.pem
	docker cp ./orderingnode_material/key.pem $id:/etc/bftsmart-orderer/config/key.pem

	echo ""
	echo "Container ID for $my_contianer_name is $id"
	echo "Launch the ordering node by typing 'docker start -a $my_contianer_name'"
	echo "Stop the ordering node by typing 'docker stop $my_contianer_name'"
	echo ""

}

main $@

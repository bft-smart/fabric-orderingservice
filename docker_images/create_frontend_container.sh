#!/bin/bash

function main () {

	re='^[0-9]+$'


	if [ -z $2 ] || [[ ! $2 =~ $re ]]; then

		echo "Usage: $0 <container name> <frontend ID>"
		exit 1
	fi

	id=$(docker ps -aqf "name=$1")

	if [ ! -z "$id" ]; then

		echo "Container name already exists"
		exit 1
	fi

	my_contianer_name=$1
	my_id=$2

	docker pull bftsmart/fabric-frontend:amd64-1.3.0

	eval $(parse_yaml ./frontend_material/fabric/orderer.yaml "orderer_")

	#echo $orderer_General_ListenPort $my_id $orderer_BFTsmart_ConnectionPoolSize $orderer_BFTsmart_RecvPort

	docker create -i -t -p $orderer_General_ListenPort:$orderer_General_ListenPort --name=$my_contianer_name "bftsmart/fabric-frontend:amd64-1.3.0" $my_id $orderer_BFTsmart_ConnectionPoolSize $orderer_BFTsmart_RecvPort > /dev/null
	id=$(docker ps -aqf "name=$my_contianer_name")

	docker cp ./frontend_material/hosts.config $id:/etc/bftsmart-orderer/config/hosts.config
	docker cp ./frontend_material/system.config $id:/etc/bftsmart-orderer/config/system.config
	docker cp ./frontend_material/genesisblock $id:/etc/bftsmart-orderer/config/genesisblock
	docker cp ./frontend_material/fabric $id:/etc/hyperledger/fabric/

	echo ""
	echo "Container ID for $my_contianer_name is $id"
	echo "Launch the frontend by typing 'docker start -a $my_contianer_name'"
	echo "Stop the frontend by typing 'docker stop $my_contianer_name'"
	echo ""

}

parse_yaml() {
   local prefix=$2
   local s='[[:space:]]*' w='[a-zA-Z0-9_]*' fs=$(echo @|tr @ '\034')
   sed -ne "s|^\($s\)\($w\)$s:$s\"\(.*\)\"$s\$|\1$fs\2$fs\3|p" \
        -e "s|^\($s\)\($w\)$s:$s\(.*\)$s\$|\1$fs\2$fs\3|p" $1 | sed 's/\$/\\\$/g' |
   awk -F$fs '{
      indent = length($1)/4;
      vname[indent] = $2;
      for (i in vname) {if (i > indent) {delete vname[i]}}
      if (length($3) > 0) {
         vn=""; for (i=0; i<indent; i++) {vn=(vn)(vname[i])("_")}
         printf("%s%s%s=\"%s\"\n", "'$prefix'",vn, $2, $3);
      }
   }'
}

main $@

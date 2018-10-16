#!/bin/bash

function main () {

	if [ $# -eq 0 ]; then

		echo "Usage: $0 <container name>"
		exit 1
	fi

	id=$(docker ps -aqf "name=$1")

	if [ ! -z "$id" ]; then

		echo "Container name already exists"
		exit 1
	fi

	my_contianer_name=$1

	docker pull hyperledger/fabric-peer:amd64-1.3.0

	eval $(parse_yaml ./peer_material/fabric/core.yaml "core_")

	if [ ! -z "$core_peer_chaincodeAddress" ]; then

		port="$core_peer_chaincodeAddress"

	elif [ ! -z "$core_peer_chaincodeListenAddress" ]; then

		port="$core_peer_chaincodeListenAddress"

	else

		port="$core_peer_listenAddress"
		
	fi

	port=$(awk '{split($0, a, ":"); print a[2]}' <<< $port)

	#echo "Docker endpoint: $core_vm_endpoint"
	#echo "Port to listen on: $port"


	docker create -i -t -p $port:$port --name=$my_contianer_name -v /var/run/:/var/run/ "hyperledger/fabric-peer:amd64-1.3.0" > /dev/null
	id=$(docker ps -aqf "name=$my_contianer_name")

	docker cp ./peer_material/fabric $id:/etc/hyperledger/fabric/

	echo ""
	echo "Container ID for $my_contianer_name is $id"
	echo "Launch the peer by typing 'docker start -a $my_contianer_name'"
	echo "Stop the peer by typing 'docker stop $my_contianer_name'"
	echo ""
}

parse_yaml() {

   local prefix=$2
   local s='[[:space:]]*' w='[a-zA-Z0-9_]*' fs=$(echo @|tr @ '\034')

   #echo "------ $fs"

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

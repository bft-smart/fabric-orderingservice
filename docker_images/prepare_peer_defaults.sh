#!/bin/bash

function main () {

	dir=./peer_material

	if [ ! -z $1 ]; then

		dir=$1	
	fi

	docker pull hyperledger/fabric-peer:amd64-1.3.0

	docker create --name="peer-temp" "hyperledger/fabric-peer:amd64-1.3.0" > /dev/null
	id=$(docker ps -aqf "name=peer-temp")

	if [ ! -d $dir ]; then
	    mkdir $dir
	fi

	if [ ! -d $dir/fabric ]; then

		docker cp $id:/etc/hyperledger/fabric/ $dir/

		if [ -f $dir/fabric/configtx.yaml ]; then

			rm $dir/fabric/configtx.yaml

		fi

		if [ -f $dir/fabric/orderer.yaml ]; then

			rm $dir/fabric/orderer.yaml

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

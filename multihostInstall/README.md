
Bft_smart multihost Install


一、Basic environment installation

1、Install docker 	Version： Docker 17.06.2-ce  or higher

（1） premise

  The centos-extras repository in the yum source must be enabled (enable)

  Added in the extras item in the file CentOS-Base.repo
  
  Enabled=1

  The command is as follows:
   
  vim /etc/yum.repos.d/CentOS-Base.repo

  It is recommended to use the overlay2 storage driver.

（2）Uninstall the old version

    The old version of docker is called docker or docker-engine, 

   and the new version of docker-ce package is called docker-ce.

   If you have docker or docker-engine installed on your host, uninstall them first.

   The command is as follows:

# yum remove docker \
docker-client \
docker-client-latest \
docker-common \
docker-latest \
docker-latest-logrotate \
docker-logrotate \
docker-selinux \
docker-engine-selinux \
docker-engine

# rm -rf /var/lib/docker/


If yum reports "No Packages marked for removal," that's ok.
Directory /var/lib/docker/ contains images, containers, volumes, and networks, which should be deleted together.
At this point, docker is completely unloaded.

（3）install docker-ce

（A）install dependency packages

The command is as follows:

# yum install -y yum-utils \
device-mapper-persistent-data \
lvm2

（B）set up stable repository
The command is as follows:

# yum-config-manager \
--add-repo \
https://mirrors.aliyun.com/docker-ce/linux/centos/docker-ce.repo 

(C) View the available version of docker-ce
The command is as follows:
# yum list docker-ce --showduplicates | sort -r

Install the specified version of docker-ce
From the above installation list, install docker-ce
//For example: yum install docker-ce-18.03.0.ce
# yum install docker-ce-<VERSION STRING>

(D) run docker
The command is as follows:
# systemctl start docker

(E) View the docker version
The command is as follows:
# docker version


2、Install docker-compose   Version： 1.14.0 or higher

（1）Do the following command to download docker-compose
#curl -L https://github.com/docker/compose/releases/download/1.19.0/docker-compose-`uname -s`-`uname -m` -o /usr/local/bin/docker-compose

（2）Convert to an executable program
#chmod +x /usr/local/bin/docker-compose


3、Install go   Version：1.10.x

(1) Download installation package
Need the installation package to download from https://studygolang.com/dl

(2) Install go

(A) Unzip go1.8.3.linux-amd64.tar.gz to /usr/local and do the following:
# tar -C /usr/local -xzf go1.8.3.linux-amd64.tar.gz

(B) Configure the go environment variable to modify the /etc/profile file 
to make it permanent and effective for all system users.
Add the following two lines of code to the end of the file:

export PATH=$PATH:/usr/local/go/bin    
export GOPATH=/opt/gopath 


The above modification of /etc/profile is implemented as follows:

#cd /etc    
#vim profile

After the modification is executed, continue to execute:

#source profile

Put it into effect.

(C)Verify that the installation was successful

#echo $PATH 

#go version

For example:
[root@localhost ~]# echo $PATH 
/usr/local/sbin:/usr/local/bin:/sbin:/bin:/usr/sbin:/usr/bin:/usr/local/go/bin:/usr/local/node-v8.11.4-linux-x64/bin:/root/bin
[root@localhost ~]# go version
go version go1.10.3 linux/amd64
[root@localhost ~]# 


4、install bft-smart fabric source code    Version：1.3.0

（1） Create a storage directory

#cd $GOPATH
#mkdir -p src/github.com/hyperledger
#cd src/github.com/hyperledger


（2）Download the 1.3.0 source code with the following command

#git clone -b release-1.3  https://github.com/jcs47/fabric.git

二、 Install the bft-smart image

1、Execute the following commands on each host to install the image

docker pull bftsmart/fabric-orderingnode:amd64-1.3.0
docker tag bftsmart/fabric-orderingnode:amd64-1.3.0 bftsmart/fabric-orderingnode

docker pull bftsmart/fabric-frontend:amd64-1.3.0
docker tag bftsmart/fabric-frontend:amd64-1.3.0 bftsmart/fabric-frontend

docker pull bftsmart/fabric-peer:amd64-1.3.0 
docker tag bftsmart/fabric-peer:amd64-1.3.0 bftsmart/fabric-peer

docker pull bftsmart/fabric-tools:amd64-1.3.0
docker tag bftsmart/fabric-tools:amd64-1.3.0 bftsmart/fabric-tools

docker pull hyperledger/fabric-couchdb:amd64-0.4.10
docker tag hyperledger/fabric-couchdb:amd64-0.4.10 hyperledger/fabric-couchdb


2、View the container image， execute the command

# docker images

[root@localhost ~]# docker images
REPOSITORY TAG IMAGE ID CREATED SIZE
bftsmart/fabric-tools amd64-1.3.0 523199627f87 2 weeks ago 1.6GB
bftsmart/fabric-tools latest 523199627f87 2 weeks ago 1.6GB
bftsmart/fabric-frontend amd64-1.3.0 2909908cd708 2 weeks ago 510MB
bftsmart/fabric-frontend latest 2909908cd708 2 weeks ago 510MB
bftsmart/fabric-orderingnode amd64-1.3.0 23bb3eae1014 2 weeks ago 467MB
bftsmart/fabric-orderingnode latest 23bb3eae1014 2 weeks ago 467MB
bftsmart/fabric-peer amd64-1.3.0 41810fd2290b 3 weeks ago 183MB
bftsmart/fabric-peer latest 41810fd2290b 3 weeks ago 183MB

hyperledger/fabric-couchdb amd64-0.4.10 3092eca241fc 4 months ago 1.61GB

hyperledger/fabric-couchdb latest 3092eca241fc 4 months ago 1.61GB


三、Create chain
1、configure the following yaml profiles as required, fill them out according to the template, and place them on separate hosts
configtx.yaml
crypto-config.yaml 

Edit the yaml file for each node：
node*.yaml, frontend*.yaml, peer*.yaml, cli*.yaml

2、Generate a certificate
#cryptogen generate --config=./crypto-config.yaml

Put the corresponding certificate in the corresponding directory for reference
Copy the certificate to each directory:

keys correspond to node*， frontend* signed signcerts correspond to cert0 ... certn, keystore 对应 keystore.pem

refer to：
    ./configuration directory files

	
3、Generate genesisblock，  And put it in the corresponding directory
#configtxgen -profile BFTGenesis -channelID bftchannel -outputBlock genesisblock

4、Start dockor

（1）Start each dockor of the node

#docker-compose -f node*.yaml up -d 

（2）Start each dockert of the frontend 

#docker-compose -f frontend*.yaml up -d 

（3）Start peer of the node

#docker-compose -f peer*.yaml up -d  

（4）Start client

#docker-compose -f cli*.yaml up -d

5、 Open an interactive mode terminal in the dockor client， the following command：
#docker exec -i -t bft.cli.0  bash


Execute the following commands:

（1）Generate block

#configtxgen -profile BFTChannel -outputCreateChannelTx channel.tx -channelID spnchannel
#configtxgen -profile BFTChannel -outputAnchorPeersUpdate anchor.tx -channelID spnchannel -asOrg LaSIGE

（2）Create a channel
#peer channel create -o 1000.frontend.bft:7050 -c spnchannel -f channel.tx
#peer channel update -o 1000.frontend.bft:7050 -c spnchannel -f anchor.tx 

（3）Join channel

#peer channel join -b spnchannel.block

（4）Install the chaincode

#peer chaincode install -n example02 -v 1.3 -p github.com/hyperledger/fabric/examples/chaincode/go/example02/cmd

（5）Initialize the chaincode

#peer chaincode instantiate -o 1000.frontend.bft:7050 -C spnchannel -n example02 -v 1.3 -c '{"Args":["init","a","100","b","200"]}'

（6）The operation of a chaincode

#peer chaincode query -C spnchannel -n example02 -c '{"Args":["query","a"]}'
#peer chaincode invoke -C spnchannel -n example02 -c '{"Args":["invoke","a","b","10"]}'
#peer chaincode query -C spnchannel -n example02 -c '{"Args":["query","a"]}'





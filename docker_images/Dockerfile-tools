FROM hyperledger/fabric-tools:amd64-1.3.0

RUN apt-get update
RUN apt-get install -y nano jq

WORKDIR /

ADD temp/configtxgen /usr/local/bin/configtxgen
ADD temp/broadcast_config /usr/local/bin/broadcast_config
ADD temp/broadcast_msg /usr/local/bin/broadcast_msg
ADD temp/deliver_stdout /usr/local/bin/deliver_stdout
ADD temp/update_frontend_entrypoint.sh /usr/local/bin/update_frontend_entrypoint.sh
ADD temp/invoke_demo.sh /usr/local/bin/invoke_demo.sh
ADD temp/query_demo.sh /usr/local/bin/query_demo.sh

RUN chmod +x /usr/local/bin/update_frontend_entrypoint.sh
RUN chmod +x /usr/local/bin/invoke_demo.sh
RUN chmod +x /usr/local/bin/query_demo.sh

ADD temp/fabric_conf/configtx.yaml /etc/hyperledger/fabric/configtx.yaml
ADD temp/fabric_conf/core.yaml /etc/hyperledger/fabric/core.yaml

WORKDIR $GOPATH

RUN mkdir src
RUN mkdir src/github.com
RUN mkdir src/github.com/hyperledger
RUN mkdir src/github.com/hyperledger/fabric
RUN mkdir src/github.com/hyperledger/fabric/examples
RUN mkdir src/github.com/hyperledger/fabric/examples/chaincode

WORKDIR src/github.com/hyperledger/fabric/examples/chaincode

ADD temp/chaincode ./

WORKDIR /

#Uncomment the instructions below to install OpenJRE
RUN apt-get update
RUN apt-get -y install openjdk-8-jre

#Uncomment the instructions below to install Oracle JDK 8
#RUN add-apt-repository ppa:webupd8team/java
#RUN apt-get update
#RUN echo "oracle-java8-installer shared/accepted-oracle-license-v1-1 select true" | debconf-set-selections
#RUN apt-get install -y oracle-java8-installer

ADD temp/config /etc/bftsmart-orderer/config
ADD temp/lib /etc/bftsmart-orderer/lib
ADD temp/BFT-Proxy.jar /etc/bftsmart-orderer/bin/orderingservice.jar

RUN echo "#!/bin/bash" > /usr/local/bin/startWorkload.sh
RUN echo "cd /etc/bftsmart-orderer/" >> /usr/local/bin/startWorkload.sh
RUN echo "java -DWORKLOAD_CLIENT_ID=\$1 -cp bin/orderingservice.jar:lib/* bft.test.WorkloadClient \$@" >> /usr/local/bin/startWorkload.sh

RUN echo "#!/bin/bash" > /usr/local/bin/reconfigure.sh
RUN echo "cd /etc/bftsmart-orderer/" >> /usr/local/bin/reconfigure.sh
RUN echo "java -cp bin/orderingservice.jar:lib/* bft.util.FabricVMServices \$@" >> /usr/local/bin/reconfigure.sh

RUN chmod +x /usr/local/bin/startWorkload.sh
RUN chmod +x /usr/local/bin/reconfigure.sh

FROM ubuntu:16.04

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
ADD temp/genesisblock /etc/bftsmart-orderer/config/genesisblock

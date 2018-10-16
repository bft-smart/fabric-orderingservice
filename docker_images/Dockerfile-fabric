FROM hyperledger/fabric-orderer:amd64-1.3.0

RUN apt-get update
RUN apt-get -y install python-software-properties debconf-utils git-all build-essential python-pip python-dev curl libc6-dev-i386 autoconf software-properties-common
RUN add-apt-repository ppa:webupd8team/java
RUN apt-get update

RUN echo "oracle-java8-installer shared/accepted-oracle-license-v1-1 select true" | debconf-set-selections
RUN apt-get install -y oracle-java8-installer

RUN curl -O https://dl.google.com/go/go1.10.3.linux-amd64.tar.gz
RUN tar -xvf go1.10.3.linux-amd64.tar.gz
RUN mv go /usr/local

ENV PATH=$PATH:/usr/local/go/bin
ENV GOPATH=/go/

RUN echo $PATH
RUN echo $GOPATH
RUN /usr/local/go/bin/go version
RUN go version

WORKDIR /go/src/github.com/hyperledger/
RUN git clone https://github.com/jcs47/fabric.git
WORKDIR /go/src/github.com/hyperledger/fabric/

RUN pip install --upgrade pip

RUN git checkout release-1.3

RUN devenv/setupUbuntuOnPPC64le.sh

RUN make configtxgen orderer peer

WORKDIR /go/src/github.com/hyperledger/fabric/orderer/sample_clients/broadcast_msg/
RUN go build
WORKDIR /go/src/github.com/hyperledger/fabric/orderer/sample_clients/broadcast_config/
RUN go build
WORKDIR /go/src/github.com/hyperledger/fabric/orderer/sample_clients/deliver_stdout/
RUN go build

WORKDIR /go/src/github.com/hyperledger/fabric/

ENV FABRIC_CFG_PATH=/go/src/github.com/hyperledger/fabric/sampleconfig

ADD temp/fabric_conf /go/src/github.com/hyperledger/fabric/sampleconfig
ARG SYS_CHAN_NAME
RUN .build/bin/configtxgen -profile SampleSingleMSPBFTsmart -channelID $SYS_CHAN_NAME -outputBlock ./genesisblock

RUN cp /go/src/github.com/hyperledger/fabric/.build/bin/orderer /usr/local/bin/orderer
RUN cp /go/src/github.com/hyperledger/fabric/.build/bin/configtxgen /usr/local/bin/configtxgen

WORKDIR /

ENV JAVA_HOME=/usr/lib/jvm/java-8-oracle/

RUN git clone https://github.com/mcfunley/juds.git
WORKDIR /juds
RUN ./autoconf.sh
RUN ./configure
RUN make
RUN make install

WORKDIR /

ENTRYPOINT ["/bin/bash"]


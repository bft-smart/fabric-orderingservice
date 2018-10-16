FROM bftsmart/fabric-common:amd64-1.3.0

RUN apt-get -y install libltdl7

WORKDIR /etc/hyperledger/fabric

ADD temp/sampleconfig/ ./
RUN rm ./core.yaml
RUN rm ./configtx.yaml

WORKDIR /

ADD temp/orderer /usr/bin/orderer
ADD temp/configtxgen /usr/bin/configtxgen
ADD temp/libunixdomainsocket-linux-i386.so /usr/lib/
ADD temp/libunixdomainsocket-linux-x86_64.so /usr/lib/

ENV FABRIC_CFG_PATH=/etc/hyperledger/fabric/

WORKDIR /etc/bftsmart-orderer/

ADD temp/startFrontend.sh ./

RUN chmod +x ./startFrontend.sh

ENTRYPOINT ["./startFrontend.sh"]

CMD [""]

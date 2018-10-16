FROM bftsmart/fabric-common:amd64-1.3.0

WORKDIR /etc/bftsmart-orderer/

RUN echo "#!/bin/bash" > startReplica.sh
RUN echo "java -DNODE_ID=\$1 -cp bin/orderingservice.jar:lib/* bft.BFTNode \$@" >> startReplica.sh

RUN chmod +x ./startReplica.sh

ENTRYPOINT ["./startReplica.sh"]
CMD [""]

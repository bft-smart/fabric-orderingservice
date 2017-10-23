#!/bin/bash
gnome-terminal --working-directory=$(pwd)  -e "bash -c 'java -cp dist/BFT-Proxy.jar:lib/* bft.BFTProxy 1000 10 9999; bash'"

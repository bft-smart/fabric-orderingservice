#!/bin/bash
gnome-terminal --working-directory=$(pwd)  -e "bash -c 'java -cp dist/BFT-Proxy.jar:dist/* bft.BFTProxy 1000 9998 9999; bash'"

#!/bin/bash

#java -XX:+UnlockCommercialFeatures -XX:+FlightRecorder -XX:StartFlightRecording=duration=60s,filename=myrecording.jfr,settings=myprofile.jfc -cp dist/BFT-Proxy.jar:lib/* bft.TestNodes $@
java -cp dist/BFT-Proxy.jar:lib/* bft.TestNodes $@

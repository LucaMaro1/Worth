#!/bin/bash

cd ./server
javac Card.java MainServer.java NotifyEventInterface.java Progetto.java ServerInterface.java Server.java Utente.java -classpath ../lib/com.fasterxml.jackson/jar_files/jackson-core-2.12.0.jar:../lib/com.fasterxml.jackson/jar_files/jackson-databind-2.12.0.jar:../lib/com.fasterxml.jackson/jar_files/jackson-annotations-2.12.0.jar
if [[ $? -eq 0 ]]; then
	echo "Compilazione server eseguita."
	echo ""
	java -classpath .:../lib/com.fasterxml.jackson/jar_files/jackson-core-2.12.0.jar:../lib/com.fasterxml.jackson/jar_files/jackson-databind-2.12.0.jar:../lib/com.fasterxml.jackson/jar_files/jackson-annotations-2.12.0.jar MainServer
fi

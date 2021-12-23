#!/bin/bash

cd ./client
javac Client.java MainClient.java NotifyEventInterface.java Progetto.java MessageReader.java ServerInterface.java Card.java Utente.java -classpath ../lib/com.fasterxml.jackson/jar_files/jackson-core-2.12.0.jar:../lib/com.fasterxml.jackson/jar_files/jackson-databind-2.12.0.jar:../lib/com.fasterxml.jackson/jar_files/jackson-annotations-2.12.0.jar
if [[ $? -eq 0 ]]; then
	echo "Compilazione client eseguita."
	echo ""
	java MainClient
fi

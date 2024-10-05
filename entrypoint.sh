#!/bin/bash

ulimit -c unlimited

PRIVATE_IP=`ifconfig eth0 | grep 'inet ' | awk '{print $2}'`

if [ $ADD_LATENCY == true ]
then
  echo "Adding artificial latency"
  tc qdisc add dev eth0 root netem delay 20ms 5ms
fi

if [ $LOCAL_DEPLOYMENT == true ]
then
	PUBLIC_IP=$PRIVATE_IP
else
	PUBLIC_IP=`curl https://ipinfo.io/ip -s`
fi

if [ $CONSENSUS_DEBUG == true ]
then
  mv consensus/bft-smart/config/logback.debug.xml consensus/bft-smart/config/logback.xml
fi

# Starting the ID server on a specific replica

if [[ "$PUBLIC_IP" == $ID_HOST ]]
then
	echo "Starting ID Server"
	java -Djava.security.properties="./consensus/bft-smart/config/java.security" -Dlogback.configurationFile="./consensus/bft-smart/config/logback.xml" -cp "cart/lib/*" cart.setup.IdServer ${NUM_REPLICAS} &
else
	echo "Waiting for ID"
fi

# Starting the ID client
java -Djava.security.properties="./consensus/bft-smart/config/java.security" -Dlogback.configurationFile="./consensus/bft-smart/config/logback.xml" -cp "cart/lib/*" cart.setup.IdClient ${ID_HOST} ${PUBLIC_IP} ${PRIVATE_IP} 8080 8081 config/CART-hosts.config consensus/bft-smart/config/hosts.config

REPLICA_ID=`cat replicaID`

if [ "$WRAPPER" == "CART" ]
then

	echo "Starting CART-based BFT-SMaRt"
	# Starting the Server Handler
	java -Djava.security.properties="./consensus/bft-smart/config/java.security" -Dlogback.configurationFile="./consensus/bft-smart/config/logback.xml" -cp "cart/lib/*" cart.server.ServerSideCART ${REPLICA_ID} ${NUM_PROXIES} ${NUM_REPLICAS} ${MAX_FAULTS} ${TENTATIVE_AGGREGATION} ${AGGREGATION_BUFFER_SIZE} ${CART_DEBUG} ${THROUGHPUT_BENCHMARK} ${LATENCY_BENCHMARK} ${MEASUREMENT_INTERVAL} ${BENCHMARK_DURATION} ${NUM_SIGN_THREADS} ${NUM_AGGREGATE_THREADS} ${BYZANTINE_AGGREGATOR} ${BYZANTINE_ATTACK_RATE} ${ATTACK_STARTING_POINT} ${ATTACK_DURATION} ${BATCH_SIGNATURES} &

	echo "Starting BFTClientSocketAdapter"
	pushd consensus/bft-smart > /dev/null
	java -Djava.security.properties="./config/java.security" -Dlogback.configurationFile="./config/logback.xml" -cp "lib/*" cart.adapter.BFTClientSocketAdapter ${REPLICA_ID} ${NUM_REPLICAS} ${NUM_PROXIES} ${CART_DEBUG} &
	popd > /dev/null

	if [ "$APP" == "YCSB" ]
	then
		echo "Starting YCSB Server"
		java -cp "demo/YCSB-CART/lib/*" ycsb.server.YCSBServer ${DEBUG} ${FAULTY_REPLICA} &

		echo "Starting BatchConsensusSocketAdapter"
		pushd consensus/bft-smart  > /dev/null
		java -Djava.security.properties="./config/java.security" -Dlogback.configurationFile="./config/logback.xml" -cp "lib/*" cart.adapter.CombinedConsensusSocketAdapter ${REPLICA_ID} true ${CART_DEBUG} 
		popd > /dev/null
	else
		echo "Starting Counter Server"
		java -cp "apps/counter/lib/*" bftsmart.demo.cart.CounterServer &

		echo "Starting SingleConsensusSocketAdapter"
		pushd consensus/bft-smart  > /dev/null
		java -Djava.security.properties="./config/java.security" -Dlogback.configurationFile="./config/logback.xml" -cp "lib/*" cart.adapter.CombinedConsensusSocketAdapter ${REPLICA_ID} false ${CART_DEBUG}
		popd > /dev/null
	fi
elif [ "$WRAPPER" == "ECDSA" ]
then
	# TODO change directory here
	echo "Starting BFT-SMaRt equipped with ECDSA"
	pushd consensus/bft-smart  > /dev/null
	java -Djava.security.properties="./config/java.security" -Dlogback.configurationFile="./config/logback.xml" -cp "lib/*" bftsmart.demo.ycsb.YCSBServerECDSA ${REPLICA_ID} ${CONSENSUS_DEBUG}
	popd > /dev/null
else
	echo "Starting native BFT-SMaRt"
	if [ "$APP" = YCSB ]
	then
		pushd demo/YCSB-BFT-SMaRt  > /dev/null
		if [ $CONSENSUS_DEBUG == true ]
		then
  		mv config/logback.debug.xml config/logback.xml
		fi
	  echo "Starting YCSBServer"
		java -Djava.security.properties="config/java.security" -Dlogback.configurationFile="config/logback_disabled.xml" -cp "lib/*:../../consensus/bft-smart/lib/*" ycsb.server.YCSBServer ${REPLICA_ID} ${CONSENSUS_DEBUG}
	else
		pushd consensus/bft-smart  > /dev/null
		echo "Starting CounterServer"
		java -Djava.security.properties="./config/java.security" -Dlogback.configurationFile="./config/logback.xml" -cp "/lib/*" bftsmart.demo.counter.CounterServer ${REPLICA_ID}
	fi
	popd > /dev/null
fi
























#!/bin/bash
if [ $# -lt 4 ]; then
  echo "Usage: ycsbBenchmark.sh <numThreads> <optimizedReads?> <Deployment> <verify>"
  echo "Deployment Options: 0 - localhost; 1 - vs cluster; 2 - AWS"
  exit 1
fi

if [ $3 -lt 0 ] || [ $3 -gt 2 ]; then
  echo "Invalid deployment selection: $4"
  exit 1
fi

echo "YCSB Config:"
echo "Threads: $1"
echo "Read Optimization: $2"
echo "Signature Verification: $4"
echo -n "Deployment: "

  case $3 in
  0)
    cp config/cart_local_hosts.config config/cart_ycsb_hosts.config
    echo "localhost"
    ;;
  1)
    cp config/cart_vs_hosts.config config/cart_ycsb_hosts.config
    echo "vs"
    ;;
  2)
    cp config/cart_aws_hosts.config config/cart_ycsb_hosts.config
    echo "aws"
    ;;
  esac

echo ""

echo "Connecting to hosts:"
cat config/cart_ycsb_hosts.config

echo -e "\n"

echo "Executing Benchmark"
  if [ $2 == true ]; then
    if [ $4 == true ]; then
      java  -Djava.library.path="/usr/local/lib" -Djava.security.properties="./config/java.security" -Dlogback.configurationFile="./config/logback.xml" -cp ./build/install/YCSB-CART/lib/*:lib/* com.yahoo.ycsb.Client \
        -threads $1 -P config/workloads/workloada -p measurementtype=timeseries -p timeseries.granularity=1000 -db ycsb.client.YCSBClientOptimizedReadsVerified -s
    else
      java -Djava.security.properties="./config/java.security" -Dlogback.configurationFile="./config/logback.xml" -cp ./build/install/YCSB-CART/lib/*:lib/* com.yahoo.ycsb.Client \
        -threads $1 -P config/workloads/workloada -p measurementtype=timeseries -p timeseries.granularity=1000 -db ycsb.client.YCSBClientOptimizedReads -s
    fi
  else
    if [ $4 == true ]; then
      java -Djava.library.path="/usr/local/lib" -Djava.security.properties="./config/java.security" -Dlogback.configurationFile="./config/logback.xml" -cp ./build/install/YCSB-CART/lib/*:lib/* com.yahoo.ycsb.Client \
        -threads $1 -P config/workloads/workloada -p measurementtype=timeseries -p timeseries.granularity=1000 -db ycsb.client.YCSBClientVerified -s
    else
      java -Djava.security.properties="./config/java.security" -Dlogback.configurationFile="./config/logback.xml" -cp ./build/install/YCSB-CART/lib/*:lib/* com.yahoo.ycsb.Client \
        -threads $1 -P config/workloads/workloada -p measurementtype=timeseries -p timeseries.granularity=1000 -db ycsb.client.YCSBClient -s
    fi
  fi


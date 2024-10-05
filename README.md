# Consensus-Agnostic Replication Toolkit

[![Licensed under GPL-3.0](https://img.shields.io/badge/license-GPLv3-blue)](./LICENSE)

This repository contains a proof-of-concept implementation of the Consensus-Agnostic Replication Toolkit (CART).

## Repository Structure
 * [`consensus/`](consensus) - contains a adapter code for the BFT-SMaRt SMR library
 * [`crypto/`](crypto) - the cryptographic functionality of the CART
 * [`demo/`](demo) - a Java YCSB implementation for benchmarking the CART
 * [`docker/`](docker) - contains Dockerfiles for building the project
 * [`wrapper/`](results) - the main codebase of the CART

## Building the Prototype
The only prerequisite for building the protoytype is an available docker installation with a version greater or equal `version 19.03`. 
The build process has been tested with different machines with `Ubuntu 22.04` or `Ubuntu 24.04` installations, and an M1 Mac with `macOS 12`.  

In order to build the project, simply execute the `build.sh` script in the root directory.
The script will first create two intermediate images and will then produce a `cart-monolith` and a `cart-client` docker image.

## Starting a Deployment with four Replicas
### Server-side
In order to launch a local deployment with four replicas, simply execute `docker-compose up` in the root directory, which will launch four docker containers as specified in the `docker-compose.yml` file.
The deployment is ready for operation if all four containers output the following on the command line:
```
###################################
    Ready to process operations    
###################################
```

The deployment can be configured by setting the respective environment variables in the provided `.env` file. 
The configuration comprises the following parameters:

| Parameter | Explanation |
|----------|-------------|
| ID_HOST| The ip-address of the replica that performs the initial setup |
| ADD_LATENCY | Adds aritificial communication latency between the containers if set to `true` |
| LOCAL_DEPLOYMENT | Specifies whether the system is deployed locally or distributed over multiple machines |
| NUM_REPLICAS | Specifies the number of replicas in the system |
| MAX_FAULTS | Specifies the maximum number of Byzantine replicas that can be tolerated by the underlying consensus protocol |
| WRAPPER | If set to `CART` the deployed consensus protocol is harnessed with the CART, otherwise the plain consensus protocol is deployed |
| APP | Specifies which server-side application should be deployed, currently only `YCSB` is supported here|
| NUM_PROXIES | Specifies the number of BFT client handlers which are used on the server-side |
| TENTATIVE_AGGREGATION | Enables the batch verification optimization if set to `true` |
| AGGREGATION_BUFFER_SIZE | Specifies the number of requests which are buffered before the batch verification is executed |
| FAULTY_REPLICA | Specifies whether a replica should behave malicious by distributing invalid results |
| CART_DEBUG | Enables the debug logs of the CART if set to `true`|
| CONSENSUS_DEBUG | Enables the debug logs of the underlying consensus protocol if set to `true` |
| THROUGHPUT_BENCHMARK | Specifies whether the throughput should be monitored on the server-side |
| LATENCY_BENCHMARK | Specifies whether the request processing times should be monitored on the server-side |
| MEASUREMENT_INTERVAL | Specifies the number of requests that are counted to compute the server-side throughput |
| BENCHMARK_DURATION | Specifies the interval in ms in which the server-side throughput is logged on the command line |
| NUM_SIGN_THREADS | Specifies the number of threads that are deployed for computing signatures |
| NUM_AGGREGATE_THREADS | Specifies the number of threads that are deployed for aggregating signatures |
| BYZANTINE_AGGREGATOR | Specifies whether the aggregator replicas should behave maliciously by creating invalid group signatures |
| BYZANTINE_ATTACK_RATE | Specifies the percentage of invalid signatures a malicious aggregator replica creates |
| ATTACK_STARTING_POINT | Specifies the number of requests that have to be processed until an attack starts |
| ATTACK_DURATION | Specifies the duration of the Byzantine aggregator attack in requests |
| BATCH_SIGNATURES | Enables the batch signature optimization if set to `true` |


### Client-Side
Perform the following steps to launch a YCSB client instance:
* Open a new terminal window
* Launch the CART client container with `docker run -it --network host cart-client:latest /bin/bash`
* Inside the container start the YCSB client process using the command `./scripts/ycsbBenchmark.sh <num-threads> <read-optimization?> <deployment> <verify?>`
* The parameters hereby mean the following:
	* The `<num-threads>` option specificies the number of YCSB client threads
	* The `<read-optimization?>` option expects a boolean string that specifies whether non-modifying requests should be invoked using the read-only optimization
	* The `<deployment>` option expects an integer between 0 and 2 and esentially specifies which host configuration file should be used, whereby 0 is required for the local deployment
	* The `<verify?>` option expects a boolean and specifies whether the signatures attached to the responses shall be verified.
* As an example, the command  `./scripts/ycsbBenchmark.sh 5 true 0 true` launches 5 YCSB client threads which connect to local CART deployment with the read-only optimization and signature verification enabled.


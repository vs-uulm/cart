# Consensus-Agnostic Replication Toolkit

This repository contains a proof-of-concept implementation of the Consensus-Agnostic Replication Toolkit (CART).

## Repository Structure
 * [`consensus/`](consensus) - contains a adapter code for the BFT-SMaRt SMR library
 * [`crypto/`](crypto) - the cryptographic functionality of the CART
 * [`demo/`](demo) - a Java YCSB implementation for benchmarking the CART
 * [`docker/`](docker) - contains Dockerfiles for building the project
 * [`wrapper/`](results) - the main codebase of the CART

## Building the prototype
The only prerequiste for building the protoytpe is an available docker installation. 
The build process has been tested with systems running different x64 systems running `Ubuntu 22.04` or `Ubuntu 24.04`, and an M1 Mac running `macOS 12`.  
It should however work with most Linux Distribution and macOS versions.

In order to build the project, simply execute the `build.sh` script in the root directory which will assmble a client and a server CART image.

## Starting a Deployment with four Replicas
### Server-side
In order to create a local deployment with four replicas, the configuration in the provided `docker-compose.yml` file can be used by executing `docker-compose up` in the root directory.
#### Configuration
The deployment can be configured by manipulating the environment variables in the provided `.env` file. The configuration comprises the following parameters:
TODO

### Client-Side
Since the relic library only supports x64 Linux systems, it is easiest to start the client inside a docker container.
In order to start a YCSB Client perform the following steps:
* Open a new terminal window
* Launch the CART client container with `docker run -it --network host cart-client:latest /bin/bash`
* Inside the container start the YCSB client process using the command `./scripts/ycsbBenchmark.sh <num-threads> <read-optimization?> <deployment> <verify?>`
* The parameters hereby mean the following:
	* The `<num-threads>` option specificies the number of YCSB client threads
	* The `<read-optimization?>` option expects a boolean string that specifies whether non-modifying requests should be invoked using the read-only optimization
	* The `<deployment>` option expects an integer between 0 and 2 and esentially specifies which host configuration file should be used, whereby 0 is required for the local deployment
	* The `<verify?>` option expects a boolean and specifies whether the signatures attached to the responses shall be verified.
* As an example, the command  `./scripts/ycsbBenchmark.sh 5 true 0 true` launches 5 YCSB client threads with enabled read-only optimization which will verify the signatures of the received responses in a local deployment


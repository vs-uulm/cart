#!/bin/bash
set -e

echo "Building crypto library"
docker buildx build --platform linux/amd64 --no-cache -f docker/crypto/Dockerfile -t cart-crypto:latest .

echo "Building the cart and bft-smart libraries"
docker buildx build --platform linux/amd64 --no-cache -f docker/build/Dockerfile -t cart-java-builder:latest .

echo "Assembling the server container"
docker buildx build --platform linux/amd64 --no-cache -f docker/server/Dockerfile -t cart-monolith:latest .

echo "Assembling the client container"
docker buildx build --platform linux/amd64 --no-cache -f docker/client/Dockerfile -t cart-client:latest .
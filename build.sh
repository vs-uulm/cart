#!/bin/bash
set -e

echo "Building crypto library"
docker buildx build --platform linux/amd64 -f docker/crypto/Dockerfile -t cart-crypto:latest .

echo "Building the cart and bft-smart libraries"
docker buildx build --platform linux/amd64 -f docker/java/Dockerfile -t cart-java-builder:latest .

echo "Assembling the server container"
docker buildx build --platform linux/amd64 -f docker/server/Dockerfile -t cart-monolith:latest .

echo "Assembling the client container"
docker buildx build --platform linux/amd64 -f docker/client/Dockerfile -t cart-client:latest .
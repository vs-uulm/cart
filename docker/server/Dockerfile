FROM ubuntu:22.04
# circumvent timezone query
ENV TZ=Europe/Berlin
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

RUN apt-get update 
RUN apt-get install net-tools -y
RUN apt-get install htop -y
RUN apt-get install iproute2 -y
RUN apt-get install openjdk-21-jdk-headless -y
RUN apt-get install curl -y

# Copy compiled libraries
WORKDIR /usr/local/lib
COPY --from=cart-crypto:latest /usr/local/lib/librelic.so .
WORKDIR /usr/lib
COPY --from=cart-crypto:latest /home/ubuntu/cart-crypto/libcart_jni_binding.so .

WORKDIR /home/ubuntu

# Copy CART
RUN mkdir -p config
COPY --from=cart-java-builder /opt/app/wrapper/build/install/wrapper cart

# Copy BFT-SMaRt
COPY --from=cart-java-builder /opt/app/bft-smart/build/install/bft-smart-adapter consensus/bft-smart
COPY --from=cart-java-builder /opt/app/bft-smart/library/config/ consensus/bft-smart/config
COPY consensus/bft-smart/config/ consensus/bft-smart/config

# Copy CART YCSB App
COPY --from=cart-java-builder /opt/app/demo/build/install/YCSB-CART demo/YCSB-CART


COPY ./entrypoint.sh .

CMD ./entrypoint.sh
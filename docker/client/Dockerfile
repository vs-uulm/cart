FROM ubuntu:22.04
# circumvent timezone query
ENV TZ=Europe/Berlin
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

RUN apt-get update 
RUN apt-get install net-tools -y
RUN apt-get install htop -y
RUN apt-get install iproute2 -y
RUN apt-get install openjdk-21-jdk-headless -y
RUN apt-get install nano -y

# Copy compiled libraries
WORKDIR /usr/local/lib
COPY --from=cart-crypto:latest /usr/local/lib/librelic.so .
COPY --from=cart-crypto:latest /home/ubuntu/cart-crypto/libcart_jni_binding.so .

# Copy CART YCSB App
WORKDIR /home/ubuntu
COPY --from=cart-java-builder /opt/app/demo/build build
ADD demo/YCSB-CART/scripts scripts
ADD demo/YCSB-CART/config config
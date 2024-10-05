FROM ubuntu:22.04

# circumvent timezone query
ENV TZ=Europe/Berlin
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

# relic dependencies
RUN apt-get clean
RUN apt-get update --fix-missing
RUN apt-get install build-essential -y
RUN apt-get install openssl -y 
RUN apt-get install libssl-dev -y
RUN apt-get install git -y
RUN apt-get install cmake -y
RUN apt-get install libgmp3-dev -y

# relic library
RUN git clone https://github.com/relic-toolkit/relic /opt/relic
WORKDIR /opt/relic
RUN git checkout d7dcb22
RUN mkdir -p relic-target
WORKDIR /opt/relic/relic-target
RUN cmake -DMULTI=PTHREAD ..
RUN make -j 4
RUN make install
RUN apt-get install openjdk-21-jdk-headless -y

WORKDIR /home/ubuntu/cart-crypto

ADD ./CMakeLists.txt .
ADD ./src ./src
ADD ./include ./include
RUN cmake .
RUN make Microbenchmark -j 4
RUN make cart_jni_binding -j 4

COPY ./entrypoint.sh ./entrypoint.sh
CMD ./entrypoint.sh

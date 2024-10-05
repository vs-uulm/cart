FROM gradle:8.10-jdk21-jammy

# Build the CART library
WORKDIR /opt/app/wrapper

ADD wrapper/src src
ADD wrapper/build.gradle .
ADD wrapper/settings.gradle . 

RUN gradle install

# Fetch and build bft-smart
WORKDIR /opt/app/bft-smart
RUN git clone https://github.com/bft-smart/library library

WORKDIR /opt/app/bft-smart/library
RUN git checkout 8ef604ef6280e465109e0223a53136119eb8dc4f
RUN gradle install

WORKDIR /opt/app/bft-smart
ADD consensus/bft-smart/src src
ADD consensus/bft-smart/build.gradle .
ADD consensus/bft-smart/settings.gradle . 

RUN mkdir -p lib
RUN cp /opt/app/bft-smart/library/build/install/library/lib/BFT-SMaRt.jar lib/
RUN gradle install

WORKDIR /opt/app/demo
ADD demo/YCSB-CART/src src
ADD demo/YCSB-CART/build.gradle .
ADD demo/YCSB-CART/settings.gradle .

RUN mkdir -p lib
RUN cp /opt/app/wrapper/build/install/wrapper/lib/CART-0.1.jar lib/
RUN cp /opt/app/bft-smart/library/lib/core-0.1.4.jar lib/
RUN gradle install
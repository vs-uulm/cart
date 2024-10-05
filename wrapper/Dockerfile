FROM ubuntu:22.04
# circumvent timezone query
ENV TZ=Europe/Berlin
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

RUN apt-get update 
RUN apt-get install net-tools -y
RUN apt-get install openjdk-21-jdk-headless -y
RUN apt-get install curl -y
RUN apt-get install htop -y
RUN apt-get install iproute2 -y

# Copy compiled libraries
WORKDIR /usr/local/lib
COPY --from=gitlab-vs.informatik.uni-ulm.de:4567/art/threshsig-wrapper:jni-build /usr/local/lib/librelic.so .
WORKDIR /usr/lib
COPY --from=gitlab-vs.informatik.uni-ulm.de:4567/art/threshsig-wrapper:jni-build /home/ubuntu/JNIBinding/libcart_jni_binding.so .
COPY --from=gitlab-vs.informatik.uni-ulm.de:4567/art/threshsig-wrapper:jni-build /home/ubuntu/JNIBinding/libcart_ffm_binding.so .

# TODO this is incomplete

COPY ./entrypoint.sh .

CMD ./entrypoint.sh
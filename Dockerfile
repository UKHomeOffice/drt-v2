FROM openjdk:jre-alpine
WORKDIR /opt/docker
ADD opt /opt
RUN ["chown", "-R", "daemon:daemon", "."]
RUN mkdir /var/lib/drt-v2
RUN mkdir -p /var/run/drt && chown daemon /var/run/drt
RUN mkdir -p /var/log/drt && chown daemon /var/log/drt
USER daemon
COPY id_rsa /opt/ida_rsa

CMD bin/drt $JAVA_OPTS

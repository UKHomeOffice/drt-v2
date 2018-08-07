FROM openjdk:alpine
WORKDIR /opt/docker
ADD server/target/docker/stage/opt /opt
RUN ["chown", "-R", "1000:1000", "."]
RUN mkdir /var/lib/drt-v2
RUN mkdir -p /var/run/drt && chown 1000 /var/run/drt
RUN mkdir -p /var/log/drt && chown 1000 /var/log/drt
RUN apk --update add ssh \
    bash \
    && \
    rm -rf /var/cache/apk/*
USER 1000

CMD bin/drt

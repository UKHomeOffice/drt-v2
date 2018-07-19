FROM openjdk:slim
WORKDIR /opt/docker
<<<<<<< HEAD
ADD server/target/docker/stage/opt /opt
=======
ADD opt /opt
>>>>>>> master
RUN ["chown", "-R", "1000:1000", "."]
RUN mkdir /var/lib/drt-v2
RUN mkdir -p /var/run/drt && chown 1000 /var/run/drt
RUN mkdir -p /var/log/drt && chown 1000 /var/log/drt
USER 1000

CMD bin/drt

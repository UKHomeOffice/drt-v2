FROM openjdk:11-jre-slim-buster as stage0
LABEL snp-multi-stage="intermediate"
LABEL snp-multi-stage-id="1b9d58c2-d90d-45d5-825f-be1cd93f3935"
WORKDIR /opt/docker
COPY server/target/docker/stage/1/opt /1/opt
COPY server/target/docker/stage/2/opt /2/opt
COPY server/target/docker/stage/4/opt /4/opt
USER root
RUN ["chmod", "-R", "u=rX,g=rX", "/1/opt/docker"]
RUN ["chmod", "-R", "u=rX,g=rX", "/2/opt/docker"]
RUN ["chmod", "-R", "u=rX,g=rX", "/4/opt/docker"]
RUN ["chmod", "u+x,g+x", "/4/opt/docker/bin/drt"]

FROM openjdk:11-jre-slim-buster as mainstage
USER root
RUN id -u demiourgos728 1>/dev/null 2>&1 || (( getent group 0 1>/dev/null 2>&1 || ( type groupadd 1>/dev/null 2>&1 && groupadd -g 0 root || addgroup -g 0 -S root )) && ( type useradd 1>/dev/null 2>&1 && useradd --system --create-home --uid 1001 --gid 0 demiourgos728 || adduser -S -u 1001 -G root demiourgos728 ))
WORKDIR /opt/docker
COPY --from=stage0 --chown=demiourgos728:root /1/opt/docker /opt/docker
COPY --from=stage0 --chown=demiourgos728:root /2/opt/docker /opt/docker
COPY --from=stage0 --chown=demiourgos728:root /4/opt/docker /opt/docker

RUN mkdir /var/lib/drt-v2
RUN mkdir -p /var/run/drt && chown 1001 /var/run/drt
RUN mkdir -p /var/log/drt && chown 1001 /var/log/drt
RUN apt-get update
RUN apt-get install -y openssh-client ca-certificates
RUN rm -rf /var/cache/apt/*

RUN mkdir -p /home/drt/.ssh
RUN ssh-keyscan -T 60 ftp.acl-uk.org >> /home/drt/.ssh/known_hosts
RUN ssh-keyscan -T 60 gateway.heathrow.com >> /home/drt/.ssh/known_hosts
RUN chown -R 1000:1000 /home/drt/.ssh

RUN mkdir -p /var/data
RUN chown 1000:1000 -R /var/data

COPY certs/rds-combined-ca-bundle.der /etc/drt/rds-combined-ca-bundle.der
COPY certs/rds-ca-2019-root.der /etc/drt/rds-ca-2019-root.der

RUN echo keytool $KEYTOOL_PASSWORD
RUN keytool -noprompt -storepass changeit -import -alias rds-root-deprecated -keystore $JAVA_HOME/lib/security/cacerts -file /etc/drt/rds-combined-ca-bundle.der
RUN keytool -noprompt -storepass changeit -import -alias rds-root -keystore $JAVA_HOME/lib/security/cacerts -file /etc/drt/rds-ca-2019-root.der

USER 1001:0
ENTRYPOINT ["/opt/docker/bin/drt", "-Duser.timezone=UTC"]
CMD []

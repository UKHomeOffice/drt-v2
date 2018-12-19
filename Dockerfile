FROM openjdk:alpine
WORKDIR /opt/docker
ADD server/target/docker/stage/opt /opt
RUN adduser -D -u 1000 drt-admin
RUN mkdir /home/drt-admin/.ssh
ADD know_hosts /home/drt-admin/.ssh/
RUN ["chown", "-R", "1000:1000", "."]
RUN ["chown", "-R", "1000:1000", "/home/drt-admin/.ssh"]
RUN mkdir /var/lib/drt-v2
RUN mkdir -p /var/run/drt && chown 1000 /var/run/drt
RUN mkdir -p /var/log/drt && chown 1000 /var/log/drt
RUN apk --update add openssh-client \
    bash && \
    apk --no-cache add python py-pip py-setuptools ca-certificates groff less && \
    pip --no-cache-dir install awscli && \
    rm -rf /var/cache/apk/*
RUN mkdir -p /var/data/snapshots
RUN chown 1000:1000 -R /var/data/snapshots


COPY certs/rds-combined-ca-bundle.pem /etc/drt/rds-combined-ca-bundle.pem

RUN echo keytool $KEYTOOL_PASSWORD
RUN keytool -noprompt -storepass changeit -import -alias rds -keystore $JAVA_HOME/jre/lib/security/cacerts -file /etc/drt/rds-combined-ca-bundle.pem
# can't run as root
USER 1000

ENTRYPOINT ["bin/drt", "-Duser.timezone=UTC"]

FROM openjdk:8
WORKDIR /opt/docker
ADD server/target/docker/stage/opt /opt
RUN adduser -D -u 1000 drt-admin

RUN ["chown", "-R", "1000:1000", "."]

RUN mkdir /var/lib/drt-v2
RUN mkdir -p /var/run/drt && chown 1000 /var/run/drt
RUN mkdir -p /var/log/drt && chown 1000 /var/log/drt
RUN apk --update add openssh-client \
    bash && \
    apk --no-cache add python py-pip py-setuptools ca-certificates groff less && \
    pip --no-cache-dir install awscli && \
    rm -rf /var/cache/apk/*

RUN mkdir /home/drt-admin/.ssh
RUN ssh-keyscan -T 60 ftp.acl-uk.org >> /home/drt-admin/.ssh/known_hosts
RUN ssh-keyscan -T 60 gateway.heathrow.com >> /home/drt-admin/.ssh/known_hosts
RUN chown -R 1000:1000 /home/drt-admin/.ssh

RUN mkdir -p /var/data
RUN chown 1000:1000 -R /var/data

COPY certs/rds-combined-ca-bundle.pem /etc/drt/rds-combined-ca-bundle.pem

RUN echo keytool $KEYTOOL_PASSWORD
RUN keytool -noprompt -storepass changeit -import -alias rds -keystore $JAVA_HOME/jre/lib/security/cacerts -file /etc/drt/rds-combined-ca-bundle.pem

USER 1000

ENTRYPOINT ["bin/drt", "-Duser.timezone=UTC"]

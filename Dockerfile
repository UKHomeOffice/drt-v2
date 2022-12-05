FROM openjdk:8-jre-alpine
WORKDIR /opt/docker
ADD server/target/docker/stage/opt /opt
RUN adduser -D -u 1000 drt

RUN ["chown", "-R", "1000:1000", "."]

RUN mkdir /var/lib/drt-v2
RUN mkdir -p /var/run/drt && chown 1000 /var/run/drt
RUN mkdir -p /var/log/drt && chown 1000 /var/log/drt
RUN apk --update add openssh-client \
    bash && \
    apk --no-cache add python py-pip py-setuptools ca-certificates groff less && \
    pip --no-cache-dir install awscli && \
    rm -rf /var/cache/apk/*

RUN mkdir /home/drt/.ssh
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

USER 1000

ENTRYPOINT ["bin/drt", "-Duser.timezone=UTC"]

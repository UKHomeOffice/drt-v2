FROM openjdk:24-ea-17-slim-bookworm as stage0
LABEL snp-multi-stage="intermediate"
LABEL snp-multi-stage-id="1b9d58c2-d90d-45d5-825f-be1cd93f3935"
WORKDIR /opt/docker
COPY server/target/docker/stage/1/opt /1/opt
COPY server/target/docker/stage/2/opt /2/opt
COPY server/target/docker/stage/4/opt /4/opt
COPY server/target/web/public /opt/docker/public

USER root
RUN ["chmod", "-R", "u=rX,g=rX", "/1/opt/docker"]
RUN ["chmod", "-R", "u=rX,g=rX", "/2/opt/docker"]
RUN ["chmod", "-R", "u=rX,g=rX", "/4/opt/docker"]
RUN ["chmod", "u+x,g+x", "/4/opt/docker/bin/drt"]

FROM openjdk:24-ea-17-slim-bookworm as mainstage
USER root
RUN id -u drt 1>/dev/null 2>&1 || (( getent group 0 1>/dev/null 2>&1 || ( type groupadd 1>/dev/null 2>&1 && groupadd -g 0 root || addgroup -g 0 -S root )) && ( type useradd 1>/dev/null 2>&1 && useradd --system --create-home --uid 1000 --gid 0 drt || adduser -S -u 1000 -G root drt ))
WORKDIR /opt/docker
COPY --from=stage0 --chown=drt:root /1/opt/docker /opt/docker
COPY --from=stage0 --chown=drt:root /2/opt/docker /opt/docker
COPY --from=stage0 --chown=drt:root /4/opt/docker /opt/docker
COPY --from=stage0 --chown=drt:root /opt/docker/public /opt/docker/public

RUN mkdir /var/lib/drt-v2
RUN mkdir -p /var/run/drt && chown 1000 /var/run/drt
RUN mkdir -p /var/log/drt && chown 1000 /var/log/drt
RUN mkdir -p /opt/docker/target && chown 1000 /opt/docker/target
RUN apt-get update
RUN apt-get install -y openssh-client curl
RUN rm -rf /var/cache/apt/*

RUN mkdir -p /home/drt/.ssh

RUN ssh-keyscan -T 60 ftp.acl-uk.org >> /home/drt/.ssh/known_hosts
RUN ssh-keyscan -T 60 gateway.heathrow.com >> /home/drt/.ssh/known_hosts
#RUN ssh-keyscan -T 60 -p 1022 galtransfer.gatwickairport.com >> /home/drt/.ssh/known_hosts
RUN echo "[galtransfer.gatwickairport.com]:1022 ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABAQCe84ySHGHdFFp7cvyukRIZCE/khNOZxlkB38KDQpw6y7cjSr4NLtA6azgyNm42bSTLh8dAxzhm5FNYP++URPshcW26RGXOGRc6qmbWTLRVVT0oH/MpDCNe1W8KtJCAbaXMuAxN90jhZtdNK2JRuljlZjLlGeP8GfJyMzg0D3CORqBC0yhXC2w7HXirPDid8LeH9oMIKpcrJcHDTYnvyAniUdomeU4sFqO8BoTbNyHFz8XSlEl1bA3LG6hJ1oe8sLei/E1iJ90U/oE6HVMfWomNiuqcifLAv6WjpnoJ54x1FaWdSdqoGsviAqE8/a2Pv8n0aPaUrGir5/2emKO/ZIml" >> /home/drt/.ssh/known_hosts
RUN chown -R 1000:1000 /home/drt/.ssh

RUN mkdir -p /var/data
RUN chown 1000:1000 -R /var/data

RUN mkdir /home/drt/.postgresql
RUN curl https://truststore.pki.rds.amazonaws.com/eu-west-2/eu-west-2-bundle.pem > /home/drt/.postgresql/root.crt

USER 1000:0
ENTRYPOINT ["/opt/docker/bin/drt", "-Duser.timezone=UTC"]
CMD []

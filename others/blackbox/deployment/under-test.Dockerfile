# The image under test, plus the black-box truststore (Story 3.6). Nothing else changes: the
# configuration that points at the file is in docker-compose.yml. Copied in rather than mounted,
# because Colima cannot bind-mount paths under /Volumes.
ARG X9_IMAGE=x9-qrcode:latest
FROM ${X9_IMAGE}
COPY x9-blackbox-truststore.jks /x9-blackbox/x9-blackbox-truststore.jks

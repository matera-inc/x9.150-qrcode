# The image under test, plus the black-box truststore (Story 3.6). Nothing else changes: the
# configuration that points at the file (the PKCS#12 copy) is in docker-compose.yml.
# Copied in rather than mounted, because Colima cannot bind-mount paths under /Volumes.
ARG X9_IMAGE=x9-qrcode:latest
FROM ${X9_IMAGE}
COPY x9-blackbox-truststore.* /x9-blackbox/
# The test CA as a TLS trust anchor too, so the service can fetch another bank's payload from the
# suite over https (test_tolerant_reading.py), read through SSL_CERT_DIR (the compose files). The
# file is named by its OpenSSL subject hash (openssl x509 -subject_hash), which the Java build's
# image required; Go reads any name.
COPY x9-test-ca.pem /x9-blackbox/ca/8b3bb28d.0

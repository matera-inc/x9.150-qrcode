# The Java build as a golden-set signer (Story 4.7): the image under export, plus the black-box
# truststore and the repository's bundled TEST keystores, so it can sign as each of them in turn.
# NON-PRODUCTION: every key copied in here is public. Copied in rather than mounted, because
# Colima cannot bind-mount paths under /Volumes.
ARG X9_IMAGE=x9-qrcode:latest
FROM ${X9_IMAGE}
COPY x9-blackbox-truststore.jks x9-test-payer.p12 x9-test-payer-ec.p12 \
     x9-test-payer-revoked.p12 x9-test-untrusted-payer.p12 /x9-golden/

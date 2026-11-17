# One instance of a two-instance-payment-cycle.sh pairing (Story 11.3): the image under test plus
# the run's throwaway PKI. Nothing else changes; the compose file the script writes says
# which of the two signing identities the instance uses. Copied in rather than mounted, because
# Colima cannot bind-mount paths under /Volumes.
#
#   /x9-pair/payee-signing.p12   the payee's X9 signing identity (self-signed, PKCS#12)
#   /x9-pair/payer-signing.p12   the payer's
#   /x9-pair/x9-truststore.p12   both certificates: each side trusts the other's signer
#   /x9-pair/ca/<hash>.0         the run's TLS CA, read through SSL_CERT_DIR; named by its OpenSSL
#                                subject hash (the retired Java image required it)
#
# NON-PRODUCTION: every key in it is generated per run and thrown away.
ARG X9_IMAGE=x9-qrcode:latest
FROM ${X9_IMAGE}
COPY payee-signing.p12 payer-signing.p12 x9-truststore.p12 /x9-pair/
COPY ca/ /x9-pair/ca/

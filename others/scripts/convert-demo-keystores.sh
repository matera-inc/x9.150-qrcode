#!/usr/bin/env bash
# Copyright © 2026 Matera Systems, Inc.
# Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
# Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
# based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
#
# Converts the Java build's bundled DEMO identity (NON-PRODUCTION) into the PEM files the Go build
# embeds (internal/keys/bundled/), so both builds start out of the box with the same key and the
# same certificate (Story 8.2, S8.2-AC4; ADR-0031). No key is generated: the PEM key is the JKS key,
# re-encoded, and the certificate's SHA-256 fingerprint is checked against the one Java ships
# (TestClockCertificateGuard), which the Go test-clock guard depends on.
#
#   others/scripts/convert-demo-keystores.sh           # rewrite the PEM files
#   others/scripts/convert-demo-keystores.sh --check   # fail if the committed files differ
#
# Needs keytool (from the JDK) and openssl 3 or later. Both conversions are deterministic: an
# unencrypted PKCS#8 key and a DER certificate have one encoding each.
#
# The same commands convert an operator's own JKS keystore by hand (x9-config-convert does it for
# them, ADR-0031):
#   keytool -importkeystore -srckeystore my.jks -srcstoretype JKS \
#           -destkeystore my.p12 -deststoretype PKCS12             # JKS -> PKCS#12
#   openssl pkcs12 -in my.p12 -nodes -out my.pem                   # PKCS#12 -> PEM (key + chain)

set -euo pipefail
cd "$(dirname "$0")/../.."

SRC=x9-qrcode-infrastructure/src/main/resources/certificate
DST=internal/keys/bundled
PASS=x9demo123              # the demo's password, public in application.yml
KEY_ALIAS=x9-private-keystore
TRUST_ALIAS=x9-truststore
# SHA-256 of the demo certificate's DER, as TestClockCertificateGuard.BUNDLED_FINGERPRINTS has it.
WANT_FINGERPRINT=67e9eae59581e99c81d3744fe086042f7363e13c2d15e05113011d2d76e5ed1b

check=false
[ "${1:-}" = "--check" ] && check=true

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# 1. A PKCS#12 copy (keytool), then the key alone, unencrypted, as PKCS#8 (openssl). Despite its
#    name, x9-demo.jks is already PKCS#12 (keytool's default since JDK 9; x9-demo-truststore.jks
#    too), so this copy only re-encrypts it; keytool reads either format, a true JKS included.
keytool -importkeystore -noprompt \
  -srckeystore "$SRC/x9-demo.jks" -srcstorepass "$PASS" -srcalias "$KEY_ALIAS" \
  -destkeystore "$work/x9-demo.p12" -deststoretype PKCS12 -deststorepass "$PASS" -destkeypass "$PASS" \
  >/dev/null 2>&1
openssl pkcs12 -in "$work/x9-demo.p12" -passin "pass:$PASS" -nocerts -nodes 2>/dev/null \
  | openssl pkey -out "$work/key.pem"

# 2. The certificates, straight from the Java files.
keytool -exportcert -rfc -alias "$KEY_ALIAS" -keystore "$SRC/x9-demo.jks" -storepass "$PASS" \
  -file "$work/cert.pem" >/dev/null 2>&1
keytool -exportcert -rfc -alias "$TRUST_ALIAS" -keystore "$SRC/x9-demo-truststore.jks" -storepass "$PASS" \
  -file "$work/x9-demo-truststore.pem" >/dev/null 2>&1

# 3. The keystore is one file: the key, then its certificate.
cat "$work/key.pem" "$work/cert.pem" >"$work/x9-demo.pem"

# 4. The key and the certificate belong together, and the certificate is the one Java ships.
[ "$(openssl pkey -in "$work/key.pem" -pubout)" = "$(openssl x509 -in "$work/cert.pem" -pubkey -noout)" ] \
  || { echo "FAIL  the converted key does not match the demo certificate" >&2; exit 1; }
got=$(openssl x509 -in "$work/cert.pem" -outform DER | openssl dgst -sha256 -r | cut -d' ' -f1)
[ "$got" = "$WANT_FINGERPRINT" ] \
  || { echo "FAIL  demo certificate fingerprint is $got, Java ships $WANT_FINGERPRINT" >&2; exit 1; }

if $check; then
  for f in x9-demo.pem x9-demo-truststore.pem; do
    cmp -s "$work/$f" "$DST/$f" || { echo "FAIL  $DST/$f differs from the conversion of $SRC" >&2; exit 1; }
  done
  echo "ok  $DST matches $SRC (SHA-256 $got)"
else
  mkdir -p "$DST"
  cp "$work/x9-demo.pem" "$work/x9-demo-truststore.pem" "$DST/"
  echo "wrote $DST/x9-demo.pem and $DST/x9-demo-truststore.pem (SHA-256 $got)"
fi

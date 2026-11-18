#!/usr/bin/env bash
# Copyright © 2026 Matera Systems, Inc.
# Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
# Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
# based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
#
# Generates the bundled DEMO identity (NON-PRODUCTION) that the binary embeds from
# internal/keys/bundled/ and signs with when no keystore is configured (ADR-0031, ADR-0040):
#
#   x9-demo.p12             the private key and its self-signed certificate, alias x9-private-keystore
#   x9-demo-truststore.p12  that certificate alone, as a trusted entry, alias x9-truststore
#
# Since 3.0.1 the key is EC P-256 and the service signs with it as ES256 (ADR-0040). Until 3.0.0 it
# was the Java build's RSA-3072 key, signing PS512, which others/scripts/convert-demo-keystores.sh
# copied byte for byte (removed with the Java build; it stays in the history, tag java-final). The
# subject, the NON-PRODUCTION marker (OU=Demo NonProduction), the aliases, the password and the
# ten-year validity are the old identity's.
#
# Its private key is committed to a public repository and is therefore public: nothing may trust
# this certificate outside a demo or a test. The service logs a WARN at every load that says so.
#
# Running it makes a NEW key, so the fingerprint changes. Then update, in the same commit:
#   internal/keys/keys.go          DemoFingerprint
#   internal/testclock/guard.go    the demo entry of bundledFingerprints
#   others/blackbox/deployment/    run ./build-truststore.sh (that truststore holds this certificate)
# and the tests that pin its public key (go test ./... names them).
#
#   others/scripts/generate-demo-identity.sh
#
# Needs only keytool (from the JDK); openssl, when present, prints the fingerprint.

set -euo pipefail
cd "$(dirname "$0")/../.."

DST=internal/keys/bundled
PASS=x9demo123                  # public: the demo's published password
KEY_ALIAS=x9-private-keystore
TRUST_ALIAS=x9-truststore
DNAME="CN=x9.matera.com,O=Matera Systems Inc,OU=Demo NonProduction,C=US"   # keytool writes it reversed: C, OU, O, CN
DAYS=3650                       # ten years, as the RSA demo certificate had

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

keytool -genkeypair -alias "$KEY_ALIAS" -dname "$DNAME" \
  -keyalg EC -groupname secp256r1 -sigalg SHA256withECDSA -validity "$DAYS" \
  -ext ku:c=digitalSignature \
  -keystore "$tmp/x9-demo.p12" -storetype PKCS12 -storepass "$PASS" -keypass "$PASS"
keytool -exportcert -alias "$KEY_ALIAS" -keystore "$tmp/x9-demo.p12" -storepass "$PASS" -file "$tmp/demo.cer"
keytool -importcert -noprompt -alias "$TRUST_ALIAS" -file "$tmp/demo.cer" \
  -keystore "$tmp/x9-demo-truststore.p12" -storetype PKCS12 -storepass "$PASS"

mkdir -p "$DST"
cp "$tmp/x9-demo.p12" "$DST/x9-demo.p12"
cp "$tmp/x9-demo-truststore.p12" "$DST/x9-demo-truststore.p12"
if command -v openssl >/dev/null; then
  fp=$(openssl dgst -sha256 -r "$tmp/demo.cer" | cut -d' ' -f1)
  echo "wrote $DST/x9-demo.p12 and $DST/x9-demo-truststore.p12 (certificate SHA-256 $fp)"
else
  echo "wrote $DST/x9-demo.p12 and $DST/x9-demo-truststore.p12"
fi

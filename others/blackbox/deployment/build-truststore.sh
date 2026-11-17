#!/usr/bin/env bash
#
# Builds x9-blackbox-truststore.jks, and its PKCS#12 copy x9-blackbox-truststore.p12: the truststore
# a deployment under black-box test runs with. The JKS is the Java build's; the Go build refuses JKS
# (ADR-0031) and reads the PKCS#12 copy, which holds the same two certificates and no private key.
#
# NON-PRODUCTION. It trusts two things whose private keys are committed to this public repository:
#
#   x9-truststore   the shipped DEMO certificate, which such a deployment keeps signing with, so its
#                   own decoder can fetch its own payloads (it verifies its own signature);
#   x9-test-ca      the bundled TEST root CA, which issues the suite's independent payers and
#                   test payees at run time (others/blackbox/x9client/jws.py).
#
# Nothing else. A deployment that trusts this file trusts keys anybody can download; never use it
# outside a test.
#
# Needs only `keytool` (ships with the JDK). Re-run it if either source truststore changes:
#
#   ./others/blackbox/deployment/build-truststore.sh
#
set -euo pipefail
cd "$(dirname "$0")"

ROOT="../../.."
DEMO="$ROOT/internal/keys/bundled/x9-demo-truststore.p12"
TEST="$ROOT/testdata/pki/x9-test-truststore.jks"
OUT="x9-blackbox-truststore.jks"
OUT_P12="x9-blackbox-truststore.p12"
STOREPASS="x9test123"

rm -f "$OUT" "$OUT_P12" ./*.cer
keytool -exportcert -alias x9-truststore -keystore "$DEMO" -storepass x9demo123 -file demo.cer
keytool -exportcert -alias x9-test-ca -keystore "$TEST" -storepass x9test123 -file test-ca.cer
keytool -importcert -noprompt -alias x9-truststore -file demo.cer \
  -keystore "$OUT" -storetype JKS -storepass "$STOREPASS"
keytool -importcert -noprompt -alias x9-test-ca -file test-ca.cer \
  -keystore "$OUT" -storetype JKS -storepass "$STOREPASS"
rm -f ./*.cer
# The same two trusted certificates, as PKCS#12, for the Go build (S9.1-AC2).
keytool -importkeystore -noprompt -srckeystore "$OUT" -srcstoretype JKS -srcstorepass "$STOREPASS" \
  -destkeystore "$OUT_P12" -deststoretype PKCS12 -deststorepass "$STOREPASS"
keytool -list -keystore "$OUT" -storepass "$STOREPASS"
keytool -list -keystore "$OUT_P12" -storetype PKCS12 -storepass "$STOREPASS"

#!/usr/bin/env bash
#
# Builds x9-blackbox-truststore.jks: the truststore a deployment under black-box test runs with.
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
DEMO="$ROOT/x9-qrcode-infrastructure/src/main/resources/certificate/x9-demo-truststore.jks"
TEST="$ROOT/x9-qrcode-infrastructure/src/test/resources/certificate/x9-test-truststore.jks"
OUT="x9-blackbox-truststore.jks"
STOREPASS="x9test123"

rm -f "$OUT" ./*.cer
keytool -exportcert -alias x9-truststore -keystore "$DEMO" -storepass x9demo123 -file demo.cer
keytool -exportcert -alias x9-test-ca -keystore "$TEST" -storepass x9test123 -file test-ca.cer
keytool -importcert -noprompt -alias x9-truststore -file demo.cer \
  -keystore "$OUT" -storetype JKS -storepass "$STOREPASS"
keytool -importcert -noprompt -alias x9-test-ca -file test-ca.cer \
  -keystore "$OUT" -storetype JKS -storepass "$STOREPASS"
rm -f ./*.cer
keytool -list -keystore "$OUT" -storepass "$STOREPASS"

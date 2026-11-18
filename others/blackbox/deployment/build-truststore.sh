#!/usr/bin/env bash
#
# Builds the truststores a deployment under black-box test runs with:
#
#   x9-blackbox-truststore.p12  the Go build's (it refuses JKS, ADR-0031): the test CA and the demo
#                               certificate the Go image bundles (EC P-256 since 3.0.1, ADR-0040)
#   x9-blackbox-truststore.jks  the Java build's, for runs against the java-final image (the golden
#                               export, the benchmark's Java turns): the test CA and the RSA demo
#                               certificate that image bundles, kept as testdata/pki/x9-demo-rsa-3.0.0.p12
#
# Each trusts the demo certificate of the image it is for, never both: the two have the same
# subject, and a truststore holding both would let a build put the other's certificate in its chain.
#
# NON-PRODUCTION. Each trusts two things whose private keys are committed to this public repository:
#
#   x9-truststore   the shipped DEMO certificate, which such a deployment keeps signing with, so its
#                   own decoder can fetch its own payloads (it verifies its own signature);
#   x9-test-ca      the bundled TEST root CA, which issues the suite's independent payers and
#                   test payees at run time (others/blackbox/x9client/jws.py).
#
# Nothing else. A deployment that trusts this file trusts keys anybody can download; never use it
# outside a test.
#
# Needs only `keytool` (ships with the JDK). Re-run it if a demo identity or the test CA changes:
#
#   ./others/blackbox/deployment/build-truststore.sh
#
set -euo pipefail
cd "$(dirname "$0")"

ROOT="../../.."
DEMO="$ROOT/internal/keys/bundled/x9-demo-truststore.p12"
JAVA_DEMO="$ROOT/testdata/pki/x9-demo-rsa-3.0.0.p12"
TEST="$ROOT/testdata/pki/x9-test-truststore.jks"
OUT="x9-blackbox-truststore.jks"
OUT_P12="x9-blackbox-truststore.p12"
STOREPASS="x9test123"

rm -f "$OUT" "$OUT_P12" ./*.cer
keytool -exportcert -alias x9-truststore -keystore "$DEMO" -storepass x9demo123 -file demo.cer
keytool -exportcert -alias x9-private-keystore -keystore "$JAVA_DEMO" -storepass x9test123 -file java-demo.cer
keytool -exportcert -alias x9-test-ca -keystore "$TEST" -storepass x9test123 -file test-ca.cer
# The Java build's (S9.1-AC2 kept it beside the PKCS#12 one).
keytool -importcert -noprompt -alias x9-truststore -file java-demo.cer \
  -keystore "$OUT" -storetype JKS -storepass "$STOREPASS"
keytool -importcert -noprompt -alias x9-test-ca -file test-ca.cer \
  -keystore "$OUT" -storetype JKS -storepass "$STOREPASS"
# The Go build's, as PKCS#12 (S9.1-AC2).
keytool -importcert -noprompt -alias x9-truststore -file demo.cer \
  -keystore "$OUT_P12" -storetype PKCS12 -storepass "$STOREPASS"
keytool -importcert -noprompt -alias x9-test-ca -file test-ca.cer \
  -keystore "$OUT_P12" -storetype PKCS12 -storepass "$STOREPASS"
rm -f ./*.cer
keytool -list -keystore "$OUT" -storepass "$STOREPASS"
keytool -list -keystore "$OUT_P12" -storetype PKCS12 -storepass "$STOREPASS"

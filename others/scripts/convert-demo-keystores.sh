#!/usr/bin/env bash
# Copyright © 2026 Matera Systems, Inc.
# Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
# Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
# based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
#
# Ships the Java build's bundled DEMO identity (NON-PRODUCTION) to the Go build, which embeds it from
# internal/keys/bundled/, so both builds start out of the box with the same key and the same
# certificate (Story 8.2, S8.2-AC4; ADR-0031). Despite their names, Java's x9-demo.jks and
# x9-demo-truststore.jks are PKCS#12 files (keytool's default since JDK 9), so the "conversion" is a
# byte-for-byte copy under a .p12 name: no key is generated or re-encoded. The certificate's SHA-256
# fingerprint is checked against the one Java ships (TestClockCertificateGuard), which the Go
# test-clock guard depends on.
#
#   others/scripts/convert-demo-keystores.sh           # copy the files
#   others/scripts/convert-demo-keystores.sh --check   # fail if the committed copies differ
#
# Needs keytool (from the JDK) and openssl. A true JKS keystore (one starting with 0xFEEDFEED) is
# converted the same way an operator converts theirs (x9-config-convert does it for them):
#   keytool -importkeystore -srckeystore my.jks -srcstoretype JKS \
#           -destkeystore my.p12 -deststoretype PKCS12

set -euo pipefail
cd "$(dirname "$0")/../.."

SRC=x9-qrcode-infrastructure/src/main/resources/certificate
DST=internal/keys/bundled
PASS=x9demo123              # the demo's password, public in application.yml
KEY_ALIAS=x9-private-keystore
# SHA-256 of the demo certificate's DER, as TestClockCertificateGuard.BUNDLED_FINGERPRINTS has it.
WANT_FINGERPRINT=67e9eae59581e99c81d3744fe086042f7363e13c2d15e05113011d2d76e5ed1b

check=false
[ "${1:-}" = "--check" ] && check=true

# 1. The Java files are PKCS#12 already; anything else would need a real conversion.
for f in x9-demo.jks x9-demo-truststore.jks; do
  keytool -list -keystore "$SRC/$f" -storepass "$PASS" 2>/dev/null | grep -q '^Keystore type: PKCS12$' \
    || { echo "FAIL  $SRC/$f is no longer PKCS#12: convert it with keytool -importkeystore" >&2; exit 1; }
done

# 2. The certificate is the one Java ships.
got=$(keytool -exportcert -alias "$KEY_ALIAS" -keystore "$SRC/x9-demo.jks" -storepass "$PASS" 2>/dev/null \
  | openssl dgst -sha256 -r | cut -d' ' -f1)
[ "$got" = "$WANT_FINGERPRINT" ] \
  || { echo "FAIL  demo certificate fingerprint is $got, Java ships $WANT_FINGERPRINT" >&2; exit 1; }

# 3. Copy, or compare.
if $check; then
  cmp -s "$SRC/x9-demo.jks" "$DST/x9-demo.p12" && cmp -s "$SRC/x9-demo-truststore.jks" "$DST/x9-demo-truststore.p12" \
    || { echo "FAIL  $DST differs from Java's demo files in $SRC" >&2; exit 1; }
  echo "ok  $DST matches $SRC (SHA-256 $got)"
else
  mkdir -p "$DST"
  cp "$SRC/x9-demo.jks" "$DST/x9-demo.p12"
  cp "$SRC/x9-demo-truststore.jks" "$DST/x9-demo-truststore.p12"
  echo "wrote $DST/x9-demo.p12 and $DST/x9-demo-truststore.p12 (SHA-256 $got)"
fi

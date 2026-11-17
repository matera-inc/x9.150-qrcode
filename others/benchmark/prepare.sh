#!/usr/bin/env bash
# Copyright © 2026 Matera Systems, Inc.
# Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
# Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
# based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
#
# Converts the repository's TEST-CA material into what the benchmark needs (Story 5.1), under
# others/benchmark/build/ (git-ignored, regenerated on every run of collect.sh):
#
#   payers.json     the k6 payers: each test-CA payer's private key as PKCS#8 (k6's WebCrypto
#                   cannot read PKCS#12), its chain for x5c, its x5t#S256; the payee certificate
#                   every signed answer must name, and the algorithm every answer must be signed with
#   signing.json    which identity the services sign their answers with (SIGNING); collect.sh
#                   copies it into every run's images.json
#   signing/        SIGNING=es256 only: the EC signing identity both builds mount, as PKCS#12
#   crl/            the test CA's CRL, served next to the service at the distribution point the
#                   payer certificates name (http://127.0.0.1:18081/test-ca.crl)
#   truststore/     what the service trusts: the test CA plus the demo certificate, as the
#                   black-box JKS (Java) and as PEM (Go reads PKCS#12 or PEM, never JKS)
#
# SIGNING chooses the identity both builds sign their answers with:
#   ps512 (default)  the shipped demo key and certificate (RSA, PS512), bundled in both images
#   es256            the test-CA EC leaf testdata/pki/x9-test-payer-ec.p12 (P-256, ES256), mounted
#                    into both builds. It is also the k6 EC payer: the test PKI has one EC end
#                    entity, and its README names it as the one that doubles as a deployment's own
#                    identity (the Java build's EcSigningIdentityApiTest ran the service with it).
#                    Trust is unchanged: its issuer, the test CA, is already trusted by both builds.
#
# NON-PRODUCTION. Every key read here is committed to this public repository (see
# testdata/pki/README.md). The script refuses any other
# source: it reads only those fixed paths.
#
# Needs openssl, keytool (any JDK) and python3.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
TEST_PKI="$ROOT/testdata/pki"
DEMO_TRUSTSTORE="$ROOT/internal/keys/bundled/x9-demo-truststore.p12"
BLACKBOX_TRUSTSTORE="$ROOT/others/blackbox/deployment/x9-blackbox-truststore.jks"
OUT="${BENCH_BUILD_DIR:-$HERE/build}"
PASS="x9test123"
SIGNING="${SIGNING:-ps512}"
EC_IDENTITY="x9-test-payer-ec.p12"
case "$SIGNING" in ps512 | es256) ;; *) echo "SIGNING=$SIGNING: use ps512 or es256" >&2; exit 2 ;; esac

# name  keystore                alg
PAYERS=("ec x9-test-payer-ec.p12 ES256" "rsa x9-test-payer.p12 PS512")

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir -p "$OUT/crl" "$OUT/truststore"

for entry in "${PAYERS[@]}"; do
  read -r name store alg <<<"$entry"
  p12="$TEST_PKI/$store"
  openssl pkcs12 -in "$p12" -passin "pass:$PASS" -nocerts -nodes 2>/dev/null |
    openssl pkcs8 -topk8 -nocrypt -outform DER | openssl base64 -A >"$work/$name.pkcs8"
  # The chain, leaf first: the leaf, then the CA certificates in the store.
  openssl pkcs12 -in "$p12" -passin "pass:$PASS" -nokeys -clcerts 2>/dev/null >"$work/$name.leaf.pem"
  openssl pkcs12 -in "$p12" -passin "pass:$PASS" -nokeys -cacerts 2>/dev/null >"$work/$name.cas.pem"
  echo "$name $alg" >>"$work/payers.list"
done

# The demo certificate stays in the Go truststore whichever identity signs, so that both settings
# trust exactly the same set; only the pinned payee certificate changes.
keytool -exportcert -alias x9-truststore -keystore "$DEMO_TRUSTSTORE" -storepass x9demo123 \
  -file "$work/demo.der" >/dev/null 2>&1
rm -rf "$OUT/signing"
if [ "$SIGNING" = es256 ]; then
  mkdir -p "$OUT/signing"
  cp "$TEST_PKI/$EC_IDENTITY" "$OUT/signing/x9-bench-signing.p12"
  openssl pkcs12 -in "$TEST_PKI/$EC_IDENTITY" -passin "pass:$PASS" -nokeys -clcerts 2>/dev/null |
    openssl x509 -outform DER >"$work/payee.der"
  payee_alg=ES256
  identity="testdata/pki/$EC_IDENTITY: the test CA's EC P-256 leaf, mounted as PKCS#12"
else
  cp "$work/demo.der" "$work/payee.der"
  payee_alg=PS512
  identity="the shipped demo key (RSA), bundled in both images"
fi
openssl base64 -A <"$work/payee.der" >"$work/payee.b64"
openssl x509 -inform DER -in "$work/payee.der" -noout -subject -nameopt RFC2253 >"$work/payee.subject"

python3 -I - "$work" "$OUT/payers.json" "$OUT/signing.json" "$SIGNING" "$payee_alg" "$identity" <<'PY'
import base64, hashlib, json, os, re, sys
work, out, signing_out, signing, payee_alg, identity = sys.argv[1:7]
read = lambda name: open(os.path.join(work, name)).read().strip()
pem = re.compile(r"-----BEGIN CERTIFICATE-----(.*?)-----END CERTIFICATE-----", re.S)
ders = lambda name: [base64.b64decode("".join(b.split())) for b in pem.findall(read(name))]
thumb = lambda der: base64.urlsafe_b64encode(hashlib.sha256(der).digest()).decode().rstrip("=")
payers = []
for line in read("payers.list").splitlines():
    name, alg = line.split()
    chain = ders(f"{name}.leaf.pem") + ders(f"{name}.cas.pem")
    assert len(chain) >= 2, f"{name}: expected a leaf and its CA, found {len(chain)} certificates"
    payers.append({"name": name, "alg": alg, "pkcs8": read(f"{name}.pkcs8"),
                   "x5c": [base64.b64encode(d).decode() for d in chain],
                   "x5tS256": thumb(chain[0])})
json.dump({"about": "NON-PRODUCTION test-CA payers for the k6 benchmark; every key here is "
                    "committed to the public repository. Generated by others/benchmark/prepare.sh.",
           "payers": payers,
           "payeeCertificates": [read("payee.b64")],
           "payeeAlg": payee_alg}, open(out, "w"), indent=1)
json.dump({"signing": signing, "alg": payee_alg, "identity": identity,
           "certificate": read("payee.subject").removeprefix("subject=").strip(),
           "x5tS256": thumb(base64.b64decode(read("payee.b64")))}, open(signing_out, "w"), indent=1)
PY

cp "$TEST_PKI/test-ca.crl" "$OUT/crl/test-ca.crl"
cp "$BLACKBOX_TRUSTSTORE" "$OUT/truststore/x9-blackbox-truststore.jks"
{
  openssl x509 -inform DER -in "$work/demo.der"
  openssl x509 -in "$ROOT/others/blackbox/golden/pki/x9-test-ca.pem"
} >"$OUT/truststore/x9-bench-truststore.pem"
echo "prepared $OUT: $(python3 -I -c 'import json,sys; d=json.load(open(sys.argv[1])); print(", ".join(p["name"]+" "+p["alg"] for p in d["payers"]))' "$OUT/payers.json"), payee pinned ($payee_alg), CRL, truststore"

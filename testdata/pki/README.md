# Test PKI — NON-PRODUCTION

> **Every private key in this directory is committed to a public repository and is therefore
> public.** Nothing here may be trusted anywhere but this test suite. These are fixtures, not
> credentials.

Committed rather than generated at build time, deliberately: a fresh clone should run `mvn test`
green with no setup, no network and no certificate to obtain. `generate-test-ca.sh` regenerates them
using only `keytool` (plus `bc` for one hex→decimal conversion) if you ever need to.

## What is here

| File | What it is |
|---|---|
| `x9-test-ca.p12` | **Test Root CA** — in the truststore and allowed by `issuer-name` |
| `x9-test-payer.p12` | RSA payer leaf issued by that CA; signs `PS512` |
| `x9-test-payer-ec.p12` | EC (P-256) payer leaf issued by that CA; signs `ES256` |
| `x9-test-payer-revoked.p12` | Leaf issued by that CA, then revoked |
| `x9-test-payer-crl-unreachable.p12` | Names only a CRL, on `127.0.0.1:1` where nothing listens |
| `x9-test-payer-ocsp-unreachable.p12` | Names only an OCSP responder, on `127.0.0.1:1` |
| `x9-test-payer-both-unreachable.p12` | Names a CRL and an OCSP responder, both on `127.0.0.1:1` |
| `x9-test-payer-no-revocation.p12` | Names no revocation source at all — refused, as it should be |
| `x9-test-payer-crl-unreadable.p12` | Names only a CRL, on the test's server, which answers with an HTML page |
| `x9-test-payer-crl-unreadable-payload.p12` | The same, its own distribution point, for the payload endpoint |
| `x9-test-payer-crl-unreadable-ocsp-unreachable.p12` | That CRL, plus an OCSP responder on `127.0.0.1:1` |
| `x9-test-payer-crl-unreadable-ocsp-unreachable-payload.p12` | The same, its own distribution point, for the payload endpoint |
| `x9-test-ca-digital-signature.pem` | The Test Root CA re-issued to itself (same name, same key) with `digitalSignature` in its key usage: a CA allowed to sign, for `X9-SIG-069` (BUG-006). Step 9 of the script, issued from the committed CA |
| `x9-test-untrusted-ca.p12` | A CA in **no** truststore |
| `x9-test-untrusted-payer.p12` | Leaf from that unknown issuer |
| `x9-test-truststore.jks` | Holds **only** the Test Root CA |
| `x9-demo-rsa-3.0.0.p12` | **Not** from the test CA: the self-signed RSA-3072 demo identity the image bundled until 3.0.0 (`CN=x9.matera.com, OU=Demo NonProduction`, the Java build's `x9-demo.jks`), re-saved under this directory's password. 3.0.1 bundles an EC P-256 one instead ([ADR-0040](../../docs/adr/0040-the-bundled-demo-identity-signs-es256.md)); this copy is kept for the tests that pin what the Java build published for it, the Java black-box truststore, and the benchmark's `SIGNING=ps512`. Not made by `generate-test-ca.sh` |
| `test-ca.crl` | Revokes nothing |
| `test-ca-revoked.crl` | Revokes `x9-test-payer-revoked` |

Password for all of them: `x9test123`.

Each payer keystore holds **exactly one entry**, with its full chain attached to the private-key
entry. That is deliberate: `PrivateKeyRetriever` refuses a keystore containing more than one entry,
so a single-entry store can double as a *deployment's own identity* — which is how
`EcSigningIdentityApiTest` runs the service with an EC certificate of its own.

## Why this exists

The shipped demo keystore (now `internal/keys/bundled/x9-demo.p12`) is **self-signed**, and
`JwsQRCodeSignatureService` deliberately skips revocation checking for self-signed end entities —
there is no authority to consult, so the check could only ever fail. That is the right production
behaviour, but it means the demo certificate can never exercise the CA-issued branch: chain
building, revocation, or the trusted-issuer allowlist. Every test that signed via
`/api/v1/signature/generate` was the service verifying its own signature.

This PKI is the smallest thing that can take the other branch. It found a real bug the first time it
ran — see `CaIssuedSignatureApiTest.anEcPayerIssuedByATrustedCaIsAccepted`.

## Two things that will bite if you regenerate

**The CRL port is baked into the certificates.** A distribution point is fixed at issuance, so
`CRL_PORT` in the script and `CRL_PORT` in `CaIssuedSignatureApiTest` must agree. Change one and
the other silently stops matching.

**Leaves must carry a CRL distribution point.** Without one, PKIX rejects a CA-issued certificate
outright with `UNDETERMINED_REVOCATION_STATUS`: `SOFT_FAIL` tolerates a revocation source being
*unreachable*, not one being *absent*. A leaf without a CDP would make the tests fail for a reason
that has nothing to do with what they check. (`x9-test-payer-no-revocation.p12` exists to pin
exactly that refusal; the three `*-unreachable` leaves pin the soft-fail, BUG-003.)

The `*-unreadable` leaves were issued from the committed CA without regenerating the others
(step 6c of the script). Each endpoint has its own leaf because, within 30 seconds of a failed
fetch, the JDK does not fetch a distribution point again and answers "no CRL" instead, which
soft-fails: a second test sharing the URL would pass whatever the code did.

The revoked leaf uses its **own** distribution point rather than sharing one and swapping the file,
because the JDK caches a fetched CRL — sharing a URL would make the revocation test depend on
execution order and on a cache lifetime nobody controls.

---

<sub>Copyright © 2026 Matera Systems, Inc. Licensed under the Matera Source License v1.0
(source-available; not open source) — see LICENSE.md at the repository root.</sub>

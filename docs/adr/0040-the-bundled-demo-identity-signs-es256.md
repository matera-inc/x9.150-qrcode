# 40. The bundled demo identity is EC P-256 and signs ES256 (3.0.1)

Date: 2026-11-18

## Status

Accepted 2026-11-18 (owner). Amends the "PS512 by default" of
[0027](0027-crypto-in-go-x9150-jws-profile-pkix-with-crl-and-emv-by-hand.md) for the bundled demo
identity only, and the out-of-the-box run of
[0031](0031-same-image-lighter-and-3-0-is-a-configuration-migration.md). Released as 3.0.1.

## Context

With no keystore configured, the image signs with a bundled **NON-PRODUCTION** demo identity.
Until 3.0.0 that identity was the Java build's: a self-signed RSA-3072 certificate
(`CN=x9.matera.com, OU=Demo NonProduction`), used with `x9.certificate.jwk-algorithm` at its
default, `PS512`.

Signing dominates this service's CPU: every answer, notification and payload is a JWS
([BENCHMARK.md](../../BENCHMARK.md), and `internal/cpu`). The benchmark harness can sign both ways
(`SIGNING=ps512` or `SIGNING=es256`, [others/benchmark/README.md](../../others/benchmark/README.md)).
On the same hardware, ES256 on a P-256 key gave this service about **70% more payments per second**
than PS512 on the RSA-3072 key (owner's measurement, 2026-11-17). The two are of the same strength
(about 128 bits), and ANSI X9.150-2026's own Annex A examples use ES256.

Most people meet the service through the demo identity: `docker run`, Compose, the playground, the
chart's minimal values, the benchmark's default campaign. They saw the slower of the two choices.

## Decision

**The bundled demo identity is an EC P-256 key** with a self-signed certificate of the same subject,
the same NON-PRODUCTION marker (`OU=Demo NonProduction`), the same aliases and password, and a
ten-year validity, made by `others/scripts/generate-demo-identity.sh`. Its SHA-256 fingerprint is
`71f5abfa2557037258e616ef8b4d87b7f8ec4c97ae777e03b406ed9da8b75e41`; the test-clock guard (ADR-0033)
lists it in place of the RSA one. The RSA demo identity is no longer bundled.

**When `X9_CERTIFICATE_JWKALGORITHM` is unset, the default follows the identity:**

| Signing identity | Default algorithm |
|---|---|
| the bundled demo (no `X9_CERTIFICATE_PRIVATEKEYSTORE_LOCATION`) | `ES256` |
| the operator's own keystore | `PS512`, as before 3.0.1 |

An operator's own keystore is therefore unaffected: an RSA key with no algorithm set still signs
PS512, and one with RS\*, PS\* or ES\* set signs as configured. ADR-0027's rules stand: each ES
algorithm is bound to its own curve (ES256 to P-256, ES384 to P-384, ES512 to P-521), and an
algorithm the key cannot sign with stops the service at start, naming both halves.

The benchmark's default campaign follows the product: `SIGNING` defaults to `es256`.
`SIGNING=ps512` stays available and mounts the RSA demo key of 3.0.0 into both builds, so it signs
and pins exactly what the campaigns before 3.0.1 did.

## Consequences

- `docker run` with no configuration signs ES256 and publishes an EC P-256 key at
  `/pub/.well-known/jwks`. A counterpart that pinned the 3.0.0 demo certificate (only a test or a
  demo should have) must trust the new one; the black-box truststore for the Go build
  (`x9-blackbox-truststore.p12`) is rebuilt with it. The JKS beside it stays the Java build's, with
  the RSA certificate the `java-final` image bundles. No truststore holds both: they share a
  subject, and the chain a build publishes follows the truststore by subject.
- The golden JWS set is untouched: its cases are signed by the test PKI, not the demo key, and
  verified against their own certificates.
- A deployment that copied the demo files out of the 3.0.0 image and mounted them as its "own"
  keystore keeps working: it is now an operator keystore, so it signs PS512 as before. It is still
  NON-PRODUCTION; it is simply no longer recognised as the bundled demo, so the demo WARN no longer
  names it.
- The old RSA demo key stays in the repository only as a test fixture,
  `testdata/pki/x9-demo-rsa-3.0.0.p12` (password `x9test123`, like the rest of the test PKI), for
  the tests that pin how the Java build published an RSA key (its JWK, `x5t`, and PEM, byte for
  byte), for the Java truststore above, and for the benchmark's `SIGNING=ps512`. Its private key is
  public, so the test-clock guard keeps listing it, like every other key committed here.

## Alternatives rejected

- **Change the default to ES256 for every key.** An operator who mounted an RSA keystore and never
  set the algorithm would fail at start after an upgrade to a patch release. The default must not
  break a working deployment.
- **Derive the algorithm from the key type for every key** (EC → ES on its curve, RSA → PS512).
  It would quietly start an EC deployment that is misconfigured today, and it makes the default a
  rule about keys rather than a value an operator can read; the demo is the only key whose type
  this project chooses.
- **Keep RSA and switch the demo to PS256.** The cost is the RSA private-key operation, not the
  hash: the gain would be negligible.
- **Ship both demo identities.** Two bundled keys means two fingerprints to warn about and to
  guard, and a choice nobody needs to make for a demo.

---

<sub>Copyright © 2026 Matera Systems, Inc. Licensed under the Matera Source License v1.0 (source-available; not open source) — see LICENSE.md at the repository root.</sub>

# 27. Crypto in Go: the X9.150 JWS profile, revocation done explicitly, and EMV by hand

Date: 2026-11-13

## Status

Proposed. Part of [0022](0022-rewrite-in-go-as-a-standalone-product.md).

## Context

`JwsQRCodeSignatureService` (Nimbus, 803 lines) does three jobs.

**Signing:**

- PS512 by default (RS\*, PS\* and ES\* accepted);
- `typ: JOSE`;
- the custom headers `correlationId`, `iat` and `ttl` (**milliseconds**), plus `statusCode` on
  responses, all listed in `crit`;
- `x5t#S256`;
- the certificate published as `jku`, `x5u` or `x5c`.

**Verification:**

- enforces `crit`, `iat + ttl` and the UUID format of `correlationId`;
- fetches the certificate (`x5c`, `jku` or `x5u`);
- runs PKIX against the truststore, with revocation options `PREFER_CRLS`, `ONLY_END_ENTITY` and
  `SOFT_FAIL`. Because `NO_FALLBACK` is absent, **Java falls back to OCSP when no CRL is
  available.** Self-signed end entities skip revocation;
- checks the issuer name;
- returns the signer subject, which is the reservation holder's identity (ADR-0021).

**Keys:** JKS or PKCS#12, an optional PKCS#11 HSM, hot reload every 30 s, Jasypt-encrypted
properties.

**EMV:** a vendored jar producing TLV with tag 26 (`org.x9` + loc URL) and CRC16-CCITT.

**On the Go side:**

- `crypto/x509` verifies chains but **does no revocation checking**. `x509.ParseRevocationList`
  exists, and `golang.org/x/crypto/ocsp` provides OCSP.
- **go-jose v4 can sign** with custom critical headers (`WithCritical`), but **its `Verify` accepts
  only `b64` as a critical header.** The list is not extensible, so it cannot verify an X9.150 JWS
  as-is.

## Decision

**JWS: `internal/jws`, the X9.150 profile only.**

- **Signing:** go-jose v4 with `WithCritical(correlationId, iat, ttl[, statusCode])`, ms `iat` and
  `ttl`, `typ`, `x5t#S256`, and `jku`/`x5u`/`x5c` as configured.
- **Verification is our own sequence,** not go-jose's `Verify`:
  1. parse with `jose.ParseSignedCompact` and **an explicit allowed-algorithm list**;
  2. split the compact string ourselves to get the raw protected header and the signing input
     (go-jose does not export them). Decode the header with integer fields or `UseNumber`, so a
     millisecond `iat` never passes through `float64`;
  3. check `crit` is exactly the supported set, `correlationId` is a UUID, `iat + ttl` is in the
     future and `typ` is acceptable;
  4. resolve the certificate (`x5c`, or a fetch of `jku`/`x5u`) and check `x5t#S256`;
  5. run PKIX and revocation (below);
  6. verify the signature over the signing input with the standard library (`rsa.VerifyPSS`,
     `rsa.VerifyPKCS1v15`, `ecdsa.Verify`).
- **Fuzzing and corpus:** header parsing and `crit` handling are fuzzed, and a **golden corpus**
  produced by the Java build must give identical accept/reject results.

**Certificate fetching** (unauthenticated input):

- no redirects;
- no credentials;
- connect and read timeouts;
- a size cap;
- HTTPS only, except the deployment's own host;
- results cached by `x5t#S256` (the cache skips the fetch, never the checks).

**Revocation, parity with Java:**

1. Build the chain with `x509.Certificate.Verify`.
2. Check the end entity **against the CRL** from its distribution point, cached until `NextUpdate`,
   with its signature verified against the issuer.
3. **If no CRL is obtainable, fall back to OCSP** from the certificate's AIA.
4. **Soft-fail** when neither answers. A listed or revoked serial refuses.
5. Self-signed end entities skip revocation.

The test CA covers each branch: valid, revoked by CRL, revoked by OCSP, no responder, untrusted, EC.

**Keys:**

- **PKCS#12 and PEM;** JKS is converted ([0031](0031-same-image-lighter-and-3-0-is-a-configuration-migration.md)).
- **The signer is `crypto.Signer`.** The file-backed signer ships. PKCS#11 is deferred until a
  deployment needs it (plan B2).
- **Reload:** the mounted files are re-read on a timer.
- **No Jasypt:** secrets come from mounted files or the environment, and `ENC(...)` values are
  decrypted once by the configuration converter.

**EMV: `internal/emv`, by hand:**

- TLV encode and decode;
- CRC16-CCITT (poly `0x1021`, init `0xFFFF`);
- tag 26 with GUI `org.x9` and sub-tag `01`;
- golden strings from Java, and a fuzzed decoder.

The ≤37-character host check stays at startup.

## Consequences

- No vendored jar, no Nimbus, no Bouncy Castle and no Jasypt: go-jose's parser plus the standard
  library.
- Revocation becomes visible code with tests for every branch, rather than JVM flags.
- HSM users wait for the PKCS#11 signer, or stay on `java-final`.

## Alternatives rejected

- **go-jose `Verify` as-is.** It refuses every X9.150 token.
- **CRL only.** Simpler, but a regression wherever Java falls back to OCSP today.
- **A hand-written JOSE parser.** The parsing stays go-jose's; only verification is ours.
- **cgo PKCS#11 from day one.** It breaks the static binary for a feature with no known user.
- **Porting the EMV jar's full model.** We emit and parse one shape.

---

<sub>Copyright © 2026 Matera Systems, Inc. Licensed under the Matera Source License v1.0 (source-available; not open source) — see LICENSE.md at the repository root. Creating a Derivative Work from this document — by AI/ML generation or by manual re-implementation based on it — is governed by that license (see the "Derivative Work" definition and Annex A).</sub>

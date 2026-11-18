# 27. Crypto in Go: the X9.150 JWS profile, revocation done explicitly, and EMV by hand

Date: 2026-11-13

## Status

Accepted 2026-11-13. Part of [0022](0022-rewrite-in-go-as-a-standalone-product.md).

## Context

`JwsQRCodeSignatureService` (Nimbus, 803 lines) does three jobs.

**Signing:**

- PS512 by default (RS\*, PS\* and ES\* accepted); *(amended by [0040](0040-the-bundled-demo-identity-signs-es256.md), 2026-11-18: with the
  algorithm unset, the bundled demo identity, EC P-256 from 3.0.1, signs ES256; an operator's own
  keystore still signs PS512)*
- `typ: JOSE`;
- the custom headers `correlationId`, `iat` and `ttl` (**milliseconds**), plus `statusCode` on
  responses, all listed in `crit`;
- `x5t#S256`;
- the certificate published as `jku`, `x5u` or `x5c`.

**Verification:**

- enforces `crit`, `iat + ttl` and the UUID format of `correlationId`;
- fetches the certificate (`x5c`, `jku` or `x5u`);
- runs PKIX against the truststore, with revocation options `PREFER_CRLS`, `ONLY_END_ENTITY` and
  `SOFT_FAIL`. **Java falls back to OCSP when no CRL is available**, except when the end entity
  names a CRL distribution point and no OCSP responder: then `NO_FALLBACK` is added, so an
  unreachable CRL soft-fails instead of failing on the missing responder (BUG-003, fixed
  2026-11-14). Self-signed end entities skip revocation;
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
     future. **`typ` is not checked**, as Java does not check it (owner, 2026-11-14): a token with
     no `typ`, `JWT`, or any of the X9.150 types is accepted until the `typ` values are reviewed;
  4. resolve the certificate (`x5c`, or a fetch of `jku`/`x5u`) and check `x5t#S256`;
  5. run PKIX and revocation (below);
  6. verify the signature over the signing input with the standard library (`rsa.VerifyPSS`,
     `rsa.VerifyPKCS1v15`, `ecdsa.Verify`). **Each `ES` algorithm is bound to its curve** (RFC 7518
     §3.4: `ES256`/P-256, `ES384`/P-384, `ES512`/P-521): `ES384` over a P-256 key is refused with
     `BAD_SIGNATURE` even though the ECDSA maths verify, as Java refuses it (owner, 2026-11-14).
     `ecdsa.Verify` alone would accept it.
  - **2026-11-15 (owner rule: the standard wins over Java's behaviour; Java fixed, Go never
    reproduces the bug).** Three rules Java broke and Go's verifier (Story 8.5) keeps; Java now
    gives the same code ([RULE-COVERAGE](../RULE-COVERAGE.md), `R-ADR0027-09` to `-11`):
    - step 4: `x5t#S256` is the thumbprint of the **end-entity** certificate, the first of the
      chain (ANSI X9.150-2026 §10.3.1, §10.7 step 7). One naming any other certificate of the
      chain is `UNTRUSTED_CHAIN`, as one naming none is. Java used to verify with whichever
      certificate matched, so a CA whose key usage allows `digitalSignature` could sign as the
      payer whose leaf it presented (BUG-006);
    - step 3: `correlationId` is a UUID in its **canonical** form, 8-4-4-4-12 hexadecimal digits in
      either case (§10.7 step 3.1); anything else is `BAD_HEADER`. Java's `UUID.fromString` also
      took `1-2-3-4-5`;
    - a signed answer's body `statusCode` is a JSON **integer** within 32 bits (A.4.5 note);
      `200.5`, `200.0`, `2e2` or a larger integer is `STATUS_MISSING`. Java truncated any number.
- **2026-11-15 (owner): every signed answer from another party must echo our correlationId.** In
  the owner's words: "Always check that the payee's answer echoes our correlationId. No
  correlationId, I can't trust the other side." Wherever this service reads a signed answer to a
  request it sent, the answer's JWS header must carry **exactly** the `correlationId` that request
  went out under: the caller's when it gave one, otherwise the one this service generated for it.
  Missing or different, the answer is not trusted. Both builds, same rule:
  - **the decoder's payload** (`POST /api/v1/qrcode-emv-decoder`): refused, 400
    business-rule-violation, `Payload JWS signature is invalid.`, the words already used for a
    payload whose signature does not verify, and nothing of it is presented. Before this, the
    payload's id was compared only when the caller supplied a `correlationID`; when the service
    generated one, any id passed. (Its words were changed later the same day: see below.)
  - **the outbound payment notification's answer** (`POST /api/v1/payment-notification/
    pre-payment` and `post-payment`): already compared every time, with a generated id as with the
    caller's. It is reported, not refused, as Story 2.3 has it: `signature: INVALID` with
    `signatureProblem` `CORRELATION_ID_MISMATCH` (another id) or `BAD_HEADER` (none), and the
    payee's status and body passed through for the payer to judge. (Changed later the same day:
    see below.)

  No other place reads a signed answer from another party: the payload request, the inbound payment
  notification and `POST /api/v1/signature/validate` verify what someone sent *us*, and are not
  answers. A missing `correlationId` was already refused everywhere by step 3 (`crit` must name it,
  and the header must carry it), so the new rule is the comparison. Pinned by `R-ADR0027-12` in
  [RULE-COVERAGE](../RULE-COVERAGE.md).
- **2026-11-15 (owner), later the same day: what a bad correlationId means on each path.** It
  amends the one above for the decoder's words and for the outbound notification, which until now
  only reported it. **A bad correlationId** is a signed answer whose header carries no
  `correlationId`, or another one than the request went out under. It is decided from the
  verifier's code first, so the two builds draw the line in the same place: `CORRELATION_ID_MISMATCH`,
  or `BAD_HEADER` with no `correlationId` or another one in the header (Java's `CorrelationEcho`,
  Go's `jws.NotEchoed`). An answer refused for its signature, its chain, its time window or its
  `statusCode` is not one, whatever it carries (the verifier checks those first), and neither is a
  `BAD_HEADER` for another parameter while the header carries ours. An answer that is not a JWS at
  all (`ABSENT_BODY`, `NOT_JWS`) is not a *signed* answer, and is reported as before. Both builds,
  same words, same statuses:
  1. **The decoder** (`POST /api/v1/qrcode-emv-decoder`), the payload fetched from the QR Code's
     URL: refused, **400 business-rule-violation, `QR Code is invalid (bad correlationId)`**. In
     the owner's words, "try again, but not remediable by the user": another try may succeed,
     nothing in the caller's input can fix it. A payload that fails any other check keeps
     `Payload JWS signature is invalid.`
  2. **The outbound notification before the money moves** (pre-commit: no `transactionId` on the
     wire, which is how the payee reads the phase, ADR-0004): refused, **502
     notification-undeliverable** (ADR-0028 freezes the list of types), with the detail **`failed
     to pay - payee is not responding (bad correlationId)`**, after one delivery. Every other
     problem with a pre-commit answer is still reported in the 200.
  3. **The outbound notification after the money moved** (post-commit: a `transactionId`): sent
     **once more**, the same signed body under a **new** correlationId. The retry is a new
     exchange: under the first id, an answer replayed from the first exchange would pass for an
     answer to the second, and the first id has already been answered wrongly. A good answer to the
     retry is reported as any answer is (200, its `signature`). If it is bad again, or never comes,
     nothing operational starts; the attempt is **recorded** and the retry's answer reported as
     today's contract reports an untrusted answer (200, `signature: INVALID`, the problem code), or
     the usual 502 when the retry got no answer. In the owner's words: "money was sent already, so
     we can try one more time and, in case it fails again, we just log the tentative on the log for
     the QR Code, letting the payer bank know about it. We don't start any operational work on
     this, but the payer may want to do an investigation so, by logging the failed correlationId,
     we're empowering it."

     **Where the record lives.** The payer side holds no state for a QR Code another bank issued:
     no document, no row, no event. "The QR Code's log" is therefore one structured line at
     **WARN**, the same on both builds, under the message `x9-qrcode post-commit notification: the
     payee's answer did not carry our correlationId, twice`, with `qrcodeId`, `transactionId`,
     `correlationIdsSent` (both), `correlationIdsAnswered` (what each answer's header carried, as
     written and clipped to 64 characters, `missing`, or `no answer`), `problems` and `at` (the
     business clock). Never the endpoint, never a key. The caller's result has no field for the
     attempts; one is proposed in the PR rather than added silently.
- **Fuzzing and corpus:** header parsing and `crit` handling are fuzzed, and a **golden corpus**
  produced by the Java build must give identical accept/reject results, **and the same
  `signatureProblem` code** (`others/blackbox/golden/`, Story 4.7). That includes two codes that
  are what Java happens to answer rather than a design, kept for parity (owner, 2026-11-14): an
  `ES256` header over an RSA certificate is `UNVERIFIABLE`; a token with its signature removed but
  its last dot kept is `NOT_JWS`.

**Certificate fetching** (unauthenticated input):

- no redirects;
- no credentials;
- connect and read timeouts;
- a size cap;
- HTTPS only, except the deployment's own host: the URL's host, port included, equal to
  `x9.public-endpoints.host` (case-insensitive), never a URL that merely contains that string
  (BUG-005, fixed in Java 2026-11-15);
- results cached by `x5t#S256` (the cache skips the fetch, never the checks).

**Revocation, parity with Java:**

1. Build the chain with `x509.Certificate.Verify`.
2. Check the end entity **against the CRL** from its distribution point, cached until `NextUpdate`,
   with its signature verified against the issuer.
3. **If no CRL is obtainable, fall back to OCSP** from the certificate's AIA.
4. **Soft-fail** when neither answers. A listed or revoked serial refuses.
5. Self-signed end entities skip revocation.

The one rule steps 2 to 4 give, and the answers both builds must agree on: a revocation source
the certificate **names but that cannot be reached, or cannot be read**, soft-fails; a certificate
a source reports as **revoked** is refused; a CA-issued certificate that **names no source at all**
is refused, since nothing could ever revoke it.

| Sources the end entity names, and what they answer | Answer | Pinned by |
|---|---|---|
| CRL and OCSP, neither reachable | accepted (soft-fail) | `X9-SIG-058`, `X9-LOC-307` |
| OCSP only, unreachable | accepted (soft-fail) | `X9-SIG-057`, `X9-LOC-307` |
| CRL only, unreachable | accepted (soft-fail) | `X9-SIG-055`, `X9-SIG-056`, `X9-LOC-307` |
| neither | refused | `X9-SIG-059`, `X9-LOC-308` |
| CRL named, answers with something that is not a CRL (an HTML error page); no OCSP, or OCSP unreachable | accepted (soft-fail) | `X9-SIG-064` to `X9-SIG-067`, `X9-LOC-307` |
| CRL named, answers with something that is not a CRL; OCSP answers | OCSP decides: good accepted, revoked refused | `X9-LOC-311` |

**A CRL that is not a CRL** (owner, 2026-11-15: "a named source that can't be read soft-fails").
The JDK refuses it: the parse fails with a `CRLException`, which `SOFT_FAIL` does not forgive, as
it forgives an `IOException`. Java now validates the chain again as if the CRL had been
unreachable: with no OCSP responder named, without revocation; with one, on OCSP alone, which
still soft-fails when the responder cannot be reached. A CRL with a bad signature already
soft-failed (the JDK discards it and finds no CRL), and still does. A CRL that is read and lists
the certificate refuses, as before.

**OCSP answers that are refused, not soft-failed**, in the JDK and in Go's `internal/pki` alike
(owner, 2026-11-15). They are answers, not a responder that cannot be reached, so `SOFT_FAIL`
does not cover them:

- an error status other than `tryLater` or `internalError` (`malformedRequest`, `sigRequired`,
  `unauthorized`); those two soft-fail;
- an answer signed by a responder that is neither the issuer nor authorised by it;
- a delegated responder certificate without the `OCSPSigning` extended key usage;
- an out-of-date answer (`nextUpdate` passed, or `thisUpdate` in the future, beyond the 15-minute
  clock skew the JDK allows);
- an answer with no response for the certificate asked about.

An answer that cannot be parsed at all is unreadable, and soft-fails.

Pinned on both payer endpoints by `X9-LOC-313` (each refused answer) and `X9-LOC-314` (`tryLater`
and `internalError` soft-fail; a delegated responder with `OCSPSigning` is relied on).

The test CA covers each branch: valid, revoked by CRL, revoked by OCSP, no responder, untrusted, EC.

**Keys:**

- **PKCS#12 and PEM;** JKS is converted ([0031](0031-same-image-lighter-and-3-0-is-a-configuration-migration.md);
  *amended 2026-11-16, [0038](0038-no-data-migration-from-the-java-build-a-written-mapping-instead.md): by the operator, with `keytool`, as [CONFIGURATION-FROM-JAVA.md](../CONFIGURATION-FROM-JAVA.md) shows*).
  PKCS#12 is read with `software.sslmate.com/src/go-pkcs12` (BSD-3-Clause), approved by the owner on
  2026-11-15, because neither the standard library nor `golang.org/x/crypto/pkcs12` reads what
  current keytool and OpenSSL write (PBES2 with AES, an HMAC-SHA-256 MAC).
- **The signer is `crypto.Signer`.** The file-backed signer ships. PKCS#11 is deferred until a
  deployment needs it (plan B2).
- **Reload:** the mounted files are re-read on a timer.
- **No Jasypt:** secrets come from mounted files or the environment, and `ENC(...)` values are
  decrypted once by the configuration converter. *(Amended 2026-11-16, [0038](0038-no-data-migration-from-the-java-build-a-written-mapping-instead.md): there is no
  converter; the operator supplies the plain secret through the environment or a mounted file,
  as [CONFIGURATION-FROM-JAVA.md](../CONFIGURATION-FROM-JAVA.md) shows.)*

**EMV: `internal/emv`, by hand:**

- TLV encode and decode;
- CRC16-CCITT (poly `0x1021`, init `0xFFFF`);
- tag 26 with GUI `org.x9` and sub-tag `01`;
- golden strings from Java, and a fuzzed decoder;
- **2026-11-15 (owner decision): malformed EMV is refused, never tolerated, on both builds.** A bad,
  missing or non-hex CRC, a zero, non-numeric or overrunning length, a duplicate top-level tag, and a
  truncated field or template are refused with a 400 naming `qrCode`, before anything is fetched
  from the host the string names; the case list is Go's `TestDecodeRefusesMalformedInput`, and Java
  reads EMV with the same rules by hand (`EmvReader`) rather than through the jar. A configured host
  with any character outside printable ASCII, or a space, stops the service at startup.

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

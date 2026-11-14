# Golden JWS and EMV sets, exported from Java

What the Java build signed and encoded, kept as files, with a manifest saying what each one is, at
what instant it is judged, and what the verdict is (Story 4.7,
[ADR-0027](../../../docs/adr/0027-crypto-in-go-x9150-jws-profile-pkix-with-crl-and-emv-by-hand.md)).

They exist for the Go build. `internal/jws` and `internal/emv` are written before any endpoint, and
these files are how they are checked against the real thing: the Go verifier must give every case
the verdict in `manifest.json`, and the Go EMV code must decode and re-encode every string. Nothing
here needs a network, a running service or a private key.

> **NON-PRODUCTION.** Every certificate here belongs to the repository's bundled test PKI
> (`x9-qrcode-infrastructure/src/test/resources/certificate`), whose private keys are public. No
> private key is in this directory; the export signs with those bundled keystores.

## What is here

| | |
|---|---|
| `manifest.json` | Everything a test needs: the verifier's settings, the PKI, every JWS file with its cases, every EMV string with its decoding. |
| `jws/*.jws` | One compact JWS per file, exactly the bytes, no trailing newline. 79 files, 89 cases. |
| `pki/x9-test-ca.pem` | The trust anchor. |
| `pki/signer-*.pem` | Each signer's chain, leaf first (the same certificates the tokens carry in `x5c`). |
| `pki/test-ca.crl`, `pki/test-ca-revoked.crl` | The CRLs (DER) behind the distribution points the signers' certificates name. |
| `export.sh`, `export.py`, `docker-compose.yml`, `signer.Dockerfile` | The export. |

### The JWS set

Java signs with four identities, one run of the service each:

| Signer | Keystore | Algorithm | Verdict |
|---|---|---|---|
| `rsa` | `x9-test-payer.p12` | `PS512` | trusted |
| `ec` | `x9-test-payer-ec.p12` | `ES256` | trusted |
| `revoked` | `x9-test-payer-revoked.p12` | `PS512` | `UNTRUSTED_CHAIN`: listed on `test-ca-revoked.crl` |
| `untrusted` | `x9-test-untrusted-payer.p12` | `PS512` | `UNTRUSTED_CHAIN`: its CA is in no truststore |

Each file says how it was made (`producedBy`):

| `producedBy` | Files | What |
|---|---|---|
| `java` | 24 | Signed by Java and not touched: payload requests, payload responses, the pre- and post-commit notifications Java sends as a payer, and its signed answers to notifications: the yes (200), and refusals with 400, 401, 404 and 409. |
| `java+tamper` | 7 | Java's token with bytes changed after signing (payload, signature). No key involved. |
| `java+resign` | 46 | Java's header with **one** thing changed, signed again with the same bundled test key: the defects Java never emits. `crit` missing, empty, incomplete, unknown; `typ` absent, `JWT`, and the standard's four profile values; `x5c` and `x5t#S256` missing or wrong; `correlationId`, `iat` and `ttl` missing or malformed; every RSA algorithm; a header that lies about its algorithm; another key. `*-resigned-control` changes nothing and is accepted, which proves the re-signing is not what gets refused. |
| `constructed` | 2 | Not a JWS: `alg: none`, plain text. |

`kind` says which message it is. Java writes `typ: JOSE` on all of them and reads nothing from
`typ` (INTERPRETATION I-12, "`typ` stays `JOSE`"), so the standard's profile values appear only as
re-typed tokens, and all of them are accepted today:

| `kind` | The standard's `typ` | |
|---|---|---|
| `payload-request` | `payreq+jws` | the payer asks for the payload |
| `payload-response` | `payresp+jws` | the payee's signed payload; `statusCode` in the header and in `crit` |
| `notification` | `paynote+jws` | the payer's pre- or post-commit |
| `notification-answer` | none | the payee's signed answer; `statusCode` in the header, in `crit` and in the body; `httpStatus` is the HTTP status it came with |

Java signs no QR content, so there is no fixture of the `qr+jws` kind.

### A case, and its clock

A JWS expires (`iat + ttl`), so a verdict is only true at an instant. Each file has one or more
**cases**:

```json
{"name": "at-iat-plus-ttl", "verifyAt": "2026-10-01T00:05:00.413Z",
 "verifyAtMillis": 1790813100413,
 "expected": {"verdict": "REFUSED", "signatureProblem": "EXPIRED"}}
```

Set the verifier's clock to `verifyAt` and the verdict is `VERIFIED`, or `REFUSED` with one fixed
`signatureProblem` code from the closed list in the OpenAPI (`SignatureProblem`). The same file
judged at several instants is how the time rules are covered: 30 s before `iat` (accepted, within
the 60 s skew), 5 minutes before (`FUTURE_IAT`), 30 s before `iat + ttl` (accepted), exactly
`iat + ttl` and a day later (`EXPIRED`). The rule, as `manifest.json` states under `verifier`:

- `FUTURE_IAT` when `iat > now + clockSkewMillis` (60 000);
- `EXPIRED` when `now >= iat + ttl`;
- both are judged after `crit` and `correlationId`, and before the certificate and the signature.
  The set pins that order: an altered token at `iat + ttl` is `EXPIRED`, not `BAD_SIGNATURE`; a
  token without `crit` at `iat + ttl` is `BAD_HEADER`, not `EXPIRED`.

`verifyAt` is the instant for everything: the JWS window, the certificates and the CRLs are all
valid then. Every fixture was signed with the service's clock moved to **2026-10-01T00:00:00Z**
(`epoch`), so the set never goes stale: it is always judged on a clock moved back to that day.

To verify offline: trust `pki/x9-test-ca.pem` and nothing else; require the issuer's name to
contain `X9` (`issuerNameMustContain`, the service's default); read the CRL for each certificate's
distribution point from `pki.crls` by its `url`; understand the critical headers `correlationId`,
`iat`, `ttl` and `statusCode`.

### Three verdicts worth reading before porting

- **`typ` is not checked.** Absent, `JWT`, `payreq+jws`: all `VERIFIED`. ADR-0027 lists "`typ` is
  acceptable" among the Go verifier's checks; with this set as its acceptance test, every `typ` is
  acceptable until the owner decides otherwise.
- **`ec-alg-ES384-on-a-p256-key` is `BAD_SIGNATURE`** although the ECDSA mathematics verify
  (SHA-384 over P-256). RFC 7518 binds each `ES` algorithm to one curve; `ecdsa.Verify` alone does
  not.
- **`rsa-alg-ES256-on-an-rsa-certificate` is `UNVERIFIABLE`**, the code for "none of the others",
  and `rsa-empty-signature` (the signature removed, its dot kept) is `NOT_JWS`. Both are what Java
  answers today rather than what one would design.

### The EMV set

`manifest.json` → `emv`: 24 strings, six QR Codes under each of four public hosts:

| Host | |
|---|---|
| `x9-150.example.com` | the host CI runs with |
| `x9.thirty-seven-characters.example.io` | 37 characters, the longest that fits: tag 26 is exactly 99 |
| `x9.example` | short |
| `qr.example.org:8443` | with a port |

Each entry has the string (`qrCode`), its `crc` (CRC16/CCITT-FALSE, poly `0x1021`, init `0xFFFF`,
over everything before the last four characters), every top-level field in order (`fields`), tag 26
decoded (`template26`: GUI `org.x9`, then the location URL), and the create `request` it was made
from. The six requests vary what Java writes: a name and a city longer than the 25 and 15
characters kept, another currency and rail, an editable amount, a twelve-digit amount, punctuation
and another country and MCC. What they show is that Java writes the currency as `840` and the
amount as `0` whatever the request says.

## Is the manifest true?

`tests/test_golden.py` proves it, and the `blackbox` CI job runs it on every pull request:

- **offline**: the files are whole and match their SHA-256; the set covers what Story 4.7 names;
  it holds no private key and only bundled test certificates; the shipped PKI is valid at every
  `verifyAt`; a second implementation (`x9client.jws`, `x9client.emv`) accepts what Java accepts,
  refuses the bad signatures, and decodes every EMV string to the manifest's fields;
- **against the running Java build**: for every case, the clock is moved to `verifyAt` and the
  token is given to Java, which must return the manifest's verdict. A notification answer is
  judged where Java judges answers (as a payee's reply to a notification it sent); everything else
  is sent to the notification endpoint, whose signed 401 names the `signatureProblem`. Java also
  reads the location out of every EMV string, and encodes each request of its own host again to
  the same characters.

## Regenerating

```bash
./mvnw -B -ntp -Pdocker -DskipTests package -Dbuild.image.name=x9-qrcode:golden
X9_IMAGE=x9-qrcode:golden ./others/blackbox/golden/export.sh                  # make golden-export
X9_IMAGE=x9-qrcode:golden ./others/blackbox/deployment/run-against-image.sh   # make blackbox-image
```

`export.sh` needs Docker and nothing else (no bind mounts, so it works on Colima). It starts the
image four times, once per signer, each time with one bundled test keystore, another public host,
the chain published in `x5c` and the clock moved to the epoch, and has `export.py` capture what
Java signs. A few minutes. Every export makes **new** tokens (new `correlationId`s, new
signatures, new locations), so the set is replaced as a whole: commit all of `manifest.json`,
`jws/` and `pki/` together, and never edit a file by hand. The expected verdicts are written by
`export.py`; an export is finished only when the suite has passed against the same image.

Regenerate when:

- the Java build changes how it signs, verifies or encodes (the suite fails and says which case);
- the bundled test PKI is regenerated (`generate-test-ca.sh`): the certificates in the tokens
  would no longer be the bundled ones;
- **before 2046-09-22**, when the bundled test certificates and CRLs expire (`regenerateBefore` in
  the manifest; `test_golden.py` fails from that day, saying so). Java checks certificates and
  CRLs on the real clock, so no moved clock helps past that date.

## What is not in the set

- **`jku` and `x5u`.** Every token carries its chain in `x5c`. Fetching a certificate is covered
  against the running service, not by fixtures.
- **OCSP.** A response is only good for a short time and cannot be a fixture. Revocation by OCSP,
  and unreachable sources, are covered live (`tests/test_payers.py`, `x9client/ocsp.py`).
- **The signer's subject as Java reports it.** `pki.signers.*.subject` is the RFC 2253 form
  (`CN=Test Payer PSP,O=Example PSP,C=US`), which is how Java prints it, but no test here observes
  Java doing so.

---

<sub>Copyright © 2026 Matera Systems, Inc. Licensed under the Matera Source License v1.0 (source-available; not open source) — see LICENSE.md at the repository root.</sub>

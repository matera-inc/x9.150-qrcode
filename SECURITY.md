# Security Policy

This project implements the **ANSI X9.150** payment QR Code standard, including JWS signing and X9
Financial PKI certificate handling. Security reports are taken seriously.

## Reporting a vulnerability

**Please do not report security vulnerabilities through public GitHub issues, discussions, or pull
requests.**

Report privately through GitHub's **Private Vulnerability Reporting**: open the repository's
**Security** tab → **Report a vulnerability**
([direct link](https://github.com/matera-inc/x9.150-qrcode/security/advisories/new)). This creates a
private advisory visible only to you and the maintainers.

Please include:

- A description of the issue and its potential impact.
- Steps to reproduce (a minimal proof of concept if possible).
- The affected version/commit and relevant configuration.

We will acknowledge your report, investigate, and coordinate a fix and disclosure timeline with you.
Please give us a reasonable window to remediate before any public disclosure.

## Scope notes

- The repository ships a **self-signed demo identity** (`internal/keys/bundled/x9-demo*.p12`,
  EC P-256 since 3.0.1, password `x9demo123`) so the service runs out of the box, and a test PKI under `testdata/pki/`;
  both private keys are public. They are **non-production** — real deployments must supply their
  own X9-issued certificates (`X9_CERTIFICATE_PRIVATEKEYSTORE_LOCATION`,
  `X9_CERTIFICATE_TRUSTSTORE_LOCATION`). The demo and test keys are not a vulnerability; the test
  clock refuses to run with any other certificate.
- This is an **open, unauthenticated API by design**; access control is the deployer's
  responsibility (API gateway, mTLS, network policy). "The management endpoints are reachable
  without auth" is expected behavior, not a vulnerability — see the README.

## Known advisories we carry deliberately

None. Until 3.0.0 this section carried GHSA-jgj7-c8vj-w563 / CVE-2026-9370 (jasypt-spring-boot,
dismissed as `not_used`); the dependency left with the Java build
([ADR-0039](docs/adr/0039-java-retired-at-3-0-0.md)). The Go build has no Jasypt and refuses
`ENC(…)` values and `JASYPT_*` variables at start. CI's `go` job runs `govulncheck` on every pull
request that touches Go.

## Supported versions

Security fixes are applied to `main` and released in the 3.x line. The Java build (2.x, the
`java-final` tag and image) is retired and receives no fixes.

---

<sub>Copyright © 2026 Matera Systems, Inc. Licensed under the Matera Source License v1.0 (source-available; not open source) — see LICENSE.md at the repository root.</sub>

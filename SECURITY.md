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

- The repository ships a **self-signed sample keystore** (`x9-demo*.jks`, password `x9demo123`) so
  the app runs out of the box. It is **non-production** — real deployments must supply their own
  X9-issued certificates via the git-ignored `secrets/` folder. The demo keystore is not a
  vulnerability.
- This is an **open, unauthenticated API by design**; access control is the deployer's
  responsibility (API gateway, mTLS, network policy). "The management endpoints are reachable
  without auth" is expected behavior, not a vulnerability — see the README.

## Supported versions

The project is pre-release; security fixes are applied to `main`.

---

<sub>Copyright © 2026 Matera Systems, Inc. Licensed under the Matera Source License v1.0 (source-available; not open source) — see LICENSE.md at the repository root.</sub>

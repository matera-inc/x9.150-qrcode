# ADR-0007 — Configurable CA allowlist for notification signatures

- **Status:** Accepted
- **Date:** 2026-09-26
- **Context:** `supporting-payment-notifications`

## Context

A payment notification is a signed JWS from the payer's PSP, and X9.150 verifies it against the X9
Financial PKI. In production a bank will want to accept only its partner CA — initially **DigiCert**,
X9's PKI partner. But the project is source-available and ships a **self-signed demo keystore** so it
runs out of the box, and the test suite depends on that.

Today trust is a truststore plus a single `x9.certificate.issuer-name` string.

## Decision

Add an explicit, configurable issuer allowlist, applied **after** the existing X.509 checks:

```yaml
x9:
  certificate:
    trust:
      allowed-issuers: []        # e.g. ["DigiCert"]; empty = any issuer the truststore chains to
      allow-self-signed: false   # true for dev/playground only
```

Validation order is unchanged and still complete: chain to a trust anchor in the truststore, validity
window, revocation — **then** the allowlist as an additional narrowing filter. The allowlist never
weakens a check; it only narrows what is accepted.

### Amendment, 2026-09-26: this gates issuers, never payers

As first accepted, this ADR did not say what the allowlist must **not** become. Closing that:

> **The allowlist constrains the certificate's ISSUER, never its SUBJECT.** Every payer holding a
> certificate from an allowed CA is accepted. X9.150 holds **no list of permitted payers** and never
> checks payer identity.

A payment QR Code is presented in public and is payable by anyone with a valid X9-issued certificate
— that is what the artefact is for. The only payer-side gate is a valid signature.

This matters because the word "allowlist" invites the wrong extension. An implementation that filtered
on certificate subject, organisation or any payer-identifying field would believe it was following
this ADR while actually introducing the payer allowlist the product forbids. Trust-anchor
configuration and counterparty gating are different things and only the first is in scope here.

## Consequences

- A production deployment can restrict to DigiCert without a code change.
- The default (`[]`, `false`) preserves today's behavior exactly.
- `allow-self-signed: true` is what keeps the playground and test suite working out of the box. It
  **must log a loud startup WARN** and be documented as non-production, exactly as the demo keystore is.
- A rejected issuer is a **distinct, 401/403-class refusal**, separate from a malformed or expired JWS,
  so an operator can tell *"your CA is not on my list"* from *"your signature is broken"* — the
  difference between a policy decision and a bug.

## Alternatives rejected

**Truststore-only (today):** a bank cannot express "DigiCert only" without rebuilding the truststore,
and the distinction between an untrusted chain and a disallowed issuer is lost in the error.

**Hardcode DigiCert:** breaks the demo keystore, the playground and CI, and makes the project unusable
for anyone not in the X9 PKI — unacceptable for a source-available project.

---

<sub>Copyright © 2026 Matera Systems, Inc. Licensed under the Matera Source License v1.0 (source-available; not open source) — see LICENSE.md at the repository root.</sub>

# TODO

Planned improvements, not yet implemented.

## Cache signer certificates on the verifier (payer) side

Today the payer/verify path (`QRCodeExternalJwkService` → `RestClientExternalJwkService`) fetches the
signer's certificate / JWK Set from the `jku`/`x5u` URL on **every** JWS verification — no caching.
The header already carries `x5t#S256` (SHA-256 thumbprint), which is the natural cache key.

Add a caching layer (decorator around the external JWK/cert gateway) so a repeated signer cert isn't
re-fetched every time:

- **Key by `x5t#S256`** (not by URL) so signer key rotation naturally misses and refreshes.
- **Re-check validity on every hit** — cached ≠ trusted forever; still enforce notBefore/notAfter,
  chain-to-trust-anchor, and revocation (cached only skips the HTTP fetch, never the trust checks).
- **Bounded + TTL** (e.g. Caffeine with max size + expiry); optionally honor the cert endpoint's
  HTTP `Cache-Control`.
- **Behind a config flag** (default on) so it can be disabled.
- Cover with a unit test (cache hit avoids a second `RestClient` call; rotated thumbprint refetches;
  expired cert is rejected).

The spec treats certificate caching as RECOMMENDED, not required, so this is an optimization — the
current behavior is already conformant, just an extra round-trip per verification.

---

<sub>Copyright © 2026 Matera Systems, Inc. Licensed under the Matera Source License v1.0 (source-available; not open source) — see LICENSE.md at the repository root.</sub>

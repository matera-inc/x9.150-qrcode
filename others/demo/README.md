# Running two X9.150 instances against each other

`two-instance-payment-cycle.sh` plays a whole payment between two deployments that share nothing but
the wire: separate databases, separate signing keys, separate public hosts. One, the **payee**,
issues a QR Code; the other, the **payer's PSP**, scans it, announces the payment, and reports it;
then the payee's own payment system marks it paid (Story 11.3).

```bash
make image                                        # x9-qrcode:latest
./others/demo/two-instance-payment-cycle.sh       # that image paying that image
./others/demo/two-instance-payment-cycle.sh --keep   # leave both instances running to poke at
```

| Variable | Default | What it does |
|---|---|---|
| `IMAGE` | `x9-qrcode:latest` | The image of both sides |
| `PAYER_IMAGE`, `PAYEE_IMAGE` | `IMAGE` | Each side's image (say, a published release against your branch) |

Both images must be the Go build (entrypoint `/x9-qrcode`). Until 3.0.0 the script ran every
pairing of the Java and Go builds — Java→Java, Java→Go, Go→Java, Go→Go — so a bank could move from
one build to the other while its counterparts had not. The Java build is retired
([ADR-0039](../../docs/adr/0039-java-retired-at-3-0-0.md)); that version of the script is in git
history under the tag `java-final`.

It needs Docker with compose v2, `openssl`, `keytool` (any JDK), `curl` and `python3`. Nothing is
bind-mounted, so it works on Colima.

The summary at the end says `PASS` or `FAIL`, and the script exits non-zero on a failure. A failure
names the step, and prints both instances' last log lines:

```
FAILED [go payer → go payee] at pre-commit (payer → payee): the payee refused it
```

CI runs it on every pull request that touches the code, `db/`, this directory or the black-box
suite: the `pairings` job in [`.github/workflows/pairings.yml`](../../.github/workflows/pairings.yml).

### What a run holds

The run is its own compose project (`x9-pair-<run>-go-go`, its own network), removed afterwards
with its images:

| Service | What it is |
|---|---|
| `payee`, `payer` | The two images, plus this run's PKI copied in ([`pairing/instance.Dockerfile`](pairing/instance.Dockerfile)), API port published on an ephemeral `127.0.0.1` port |
| `<role>-postgres`, `-bootstrap`, `-liquibase` | Each instance's PostgreSQL 16: `db/local/bootstrap.sql`, then `db/changelog` applied by Liquibase as the owner role, before the service starts and connects as the application role — as [`docker-compose.yml`](../blackbox/deployment/docker-compose.yml) does |
| `tls` | nginx terminating HTTPS for both public hosts, `payee.x9demo.test` and `payer.x9demo.test`, which are its network aliases ([`pairing/nginx.conf`](pairing/nginx.conf)) |

The service serves plain HTTP and expects TLS in front of it, so nginx fronts both instances.

**Trust** is generated per run, into a temporary directory, and thrown away:

- **TLS** — a CA and one certificate for both hosts. Each instance trusts the CA through
  `SSL_CERT_DIR` (the file is named by its OpenSSL subject hash, which the retired Java image
  needed; Go reads any name).
- **X9 signing** — a self-signed identity per *role* (payee, payer), and one PKCS#12 truststore
  holding both certificates, which both instances load as their certificate truststore. Two
  different keys, so a message verified with the wrong key, or with the receiver's own, fails.
  PKCS#12, because the service refuses JKS (ADR-0031).

**The cycle**, each step a check:

1. The payee issues a USDC QR Code on Solana — the rail that exercises both notification phases.
2. The payer decodes it: it fetches the payload from `https://payee.x9demo.test`, verifies the
   payee's JWS against its truststore (through the payee's `jku`), and must read the same id,
   recipient and a notification endpoint on the payee's host.
3. **Pre-commit** through the payer: the payee verifies the payer's JWS (fetching the payer's JWKS),
   accepts, and answers signed; the payer must report the answer `accepted` and its signature
   `VERIFIED`, and the payee must now hold the QR Code `PAYMENT_INITIATED`.
4. **Post-commit**, with the transaction hash, checked the same way.
5. The payee's payment system marks it `PAID` (`PUT …/status-update`); the payee must say `PAID`.
6. The payee's event stream must read exactly `payment.initiated`, `payment.sent`, `payment.cleared`
   for that QR Code.
7. The HTTPS front's access log must show the payload fetch, the notifications, and each side
   reading the other's JWKS: the two talked host to host over TLS, never through a shortcut.

## HTTPS is not optional

**Any deployment you want to test against must be reachable over HTTPS.** This is not a hardening
preference; plain HTTP will not complete a payment, and the failure is not obvious when it happens.

When a payer scans a QR Code, it reads the payload URL out of the EMV string and normalises it
before fetching. This deployment's own public host (`X9_PUBLICENDPOINTS_HOST`) is read internally;
**any other host is always fetched over `https`**, whatever scheme the QR Code wrote
([`internal/decoder/target.go`](../../internal/decoder/target.go), S10.7-AC2).

So a single instance talking to itself works over HTTP — which is why the test suite and everyday
local development never hit this. The moment a *second* deployment is involved, the payload fetch
goes out as `https://…` regardless of what the QR Code said, and an HTTP-only payee simply never
answers.

The same applies to `/pub/.well-known/jwks`. The payee fetches the payer's key from the `jku` in the
JWS header, so an HTTP-only payer gets its perfectly valid signature rejected as unverifiable.

### Two ways to have HTTPS

| | When to use it | Cost |
|---|---|---|
| **nginx in front** | this script and CI; any deployment you run yourself | one config file |
| **Cloudflare Tunnel** | a real phone, on cellular, scanning a real QR Code | an account for a stable hostname |

The service does not terminate TLS itself. (The Java build could, with Spring's `server.ssl.*`;
that recipe is in this file at the tag `java-final`.)

**1. A small nginx in front** — what the script does ([`pairing/nginx.conf`](pairing/nginx.conf)
serves both hosts from one container). For one instance:

```nginx
server {
    listen 8443 ssl;
    server_name localhost;

    ssl_certificate     /etc/nginx/certs/x9-demo.crt;
    ssl_certificate_key /etc/nginx/certs/x9-demo.key;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host              $host;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto https;
    }
}
```

Then run the service on 8080 with `X9_PUBLICENDPOINTS_HOST=localhost:8443`, so the URLs it
advertises name the proxy rather than itself. The certificate is self-signed, so the *other*
instance has to trust it: put it (PEM) in a directory listed in that instance's `SSL_CERT_DIR`, as
the script does.

> That trust is for the TLS connection. It is **not** the X9 certificate truststore
> (`X9_CERTIFICATE_TRUSTSTORE_*`), which is the X9 PKI used to validate the signer of a JWS. The
> two are unrelated and both must be right.

**2. A Cloudflare Tunnel**, when the QR Code has to be scanned by a real phone that cannot reach
your machine. See [ENDPOINTS.md](../../ENDPOINTS.md) — including the host-length trap, which bites
anonymous quick tunnels every time.

## Why this exists

A single instance cannot check whether it is interoperable, only whether it is self-consistent. Both
sides of every conversation are the same code, holding the same assumptions, so a message that is
wrong in the same way at both ends still round-trips. While there were two builds, the same held
between them: only a Go instance talking to a Java one showed that each read what the other wrote.
The first run of this script, Java against Java, found three defects the suite could not:

- The payer signed its **internal** notification record instead of the contract shape, putting
  `qrCodeId` at the top level where X9.150 puts `payment.qrcodeId`. Every conformant payee refuses
  that. The test asserting the id "appears in the payload" passed either way.
- A notification without the **optional** `expectedDate` crashed the payee with a 500 while
  persisting it. Every existing test happened to send one.
- The access log reported `status 200` for responses that were 401 and 500, because the status was
  read before the request was handled.

---

<sub>Copyright © 2026 Matera Systems, Inc. Licensed under the Matera Source License v1.0 (source-available; not open source) — see LICENSE.md at the repository root.</sub>

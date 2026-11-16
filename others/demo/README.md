# Running two X9.150 instances against each other

`two-instance-payment-cycle.sh` plays a whole payment between two deployments that share nothing but
the wire: separate databases, separate signing keys, separate public hosts. One, the **payee**,
issues a QR Code; the other, the **payer's PSP**, scans it, announces the payment, and reports it;
then the payee's own payment system marks it paid. It does so in every pairing of the two builds,
so a bank can move from the Java build to the Go one while its counterparts have not (Story 11.3).

```bash
make image && make go-image                       # x9-qrcode:latest (Java) and x9-qrcode:go (Go)
./others/demo/two-instance-payment-cycle.sh       # all four pairings, one after the other
./others/demo/two-instance-payment-cycle.sh --keep   # leave every pairing running to poke at
```

A pairing is written **payer-payee**: `java-go` is a Java PSP paying a QR Code a Go payee issued.

| Variable | Default | What it does |
|---|---|---|
| `PAIRINGS` | `java-java java-go go-java go-go` | Which pairings to run (space- or comma-separated) |
| `JAVA_IMAGE` | `x9-qrcode:latest` | The image used wherever a pairing says `java` |
| `GO_IMAGE` | `x9-qrcode:go` | The image used wherever a pairing says `go` |
| `PAYER_IMAGE`, `PAYEE_IMAGE` | — | Set together: run one pairing of exactly these two images (say, a published release against your branch). Each image's build is read from its entrypoint, as `run-against-image.sh` does; `PAYER_BUILD` / `PAYEE_BUILD` (`java` or `go`) state it instead |

It needs Docker with compose v2, `openssl`, `keytool` (any JDK), `curl` and `python3`. Nothing is
bind-mounted, so it works on Colima. A Java-Java pairing needs about 2 GiB of Docker memory; the
pairings run one at a time.

The summary at the end lists every pairing as `PASS` or `FAIL`, and the script exits non-zero if
any failed. A failure names the pairing and the step, and prints both instances' last log lines:

```
FAILED [go payer → java payee] at pre-commit (payer → payee): the payee refused it
```

CI runs all four on every pull request that touches either build, `db/`, this directory or the
black-box suite: the `pairings` job in [`.github/workflows/pairings.yml`](../../.github/workflows/pairings.yml).

### What one pairing runs

Each pairing is its own compose project (`x9-pair-<run>-<pairing>`, its own network), removed
afterwards with its images:

| Service | What it is |
|---|---|
| `payee`, `payer` | The two images, plus this run's PKI copied in ([`pairing/instance.Dockerfile`](pairing/instance.Dockerfile)), API port published on an ephemeral `127.0.0.1` port |
| `<role>-mongo` | A Java instance's MongoDB 7.0.45, as a single-node replica set (the notification write is transactional) |
| `<role>-postgres`, `-bootstrap`, `-liquibase` | A Go instance's PostgreSQL 16: `db/local/bootstrap.sql`, then `db/changelog` applied by Liquibase as the owner role, before the service starts and connects as the application role — as [`docker-compose.go.yml`](../blackbox/deployment/docker-compose.go.yml) does |
| `tls` | nginx terminating HTTPS for both public hosts, `payee.x9demo.test` and `payer.x9demo.test`, which are its network aliases ([`pairing/nginx.conf`](pairing/nginx.conf)) |

The Go build serves plain HTTP and expects TLS in front of it, so the same nginx fronts both
builds: a Java and a Go instance are reached exactly the same way.

**Trust** is generated per run, into a temporary directory, and thrown away:

- **TLS** — a CA and one certificate for both hosts. Each instance trusts the CA through
  `SSL_CERT_DIR`: Go reads it natively, and the Java image's start-up adds it to the JVM's
  truststore, which is why the file is named by its OpenSSL subject hash.
- **X9 signing** — a self-signed identity per *role* (payee, payer), whatever the build, and one
  PKCS#12 truststore holding both certificates, which both instances load as
  `x9.certificate.truststore`. Two different keys, so a message verified with the wrong key, or with
  the receiver's own, fails. PKCS#12, because the Go build refuses JKS (ADR-0031); the Java build
  reads it too.

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
before fetching. A host that is not this instance's own configured `x9.public-endpoints.host` is
**always** given the `https` scheme:

```java
// DefaultQRCodeLocationService.normalizeHost
if (originalUri.contains(publicHost) && !originalUri.contains(retrieveLocalHost())) {
    return replaceSchema(originalUri.replace(publicHost, retrieveLocalHost()), HTTP);  // ourselves
}
return replaceSchema(originalUri, HTTPS);                                             // everyone else
```

So a single instance talking to itself works over HTTP — which is why the test suite and everyday
local development never hit this. The moment a *second* deployment is involved, the payload fetch
goes out as `https://…` regardless of what the QR Code said, and an HTTP-only payee simply never
answers.

The same applies to `/pub/.well-known/jwks`. The payee fetches the payer's key from the `jku` in the
JWS header, so an HTTP-only payer gets its perfectly valid signature rejected as unverifiable.

### Three ways to have HTTPS

| | When to use it | Cost |
|---|---|---|
| **Spring's own TLS** | the Java build alone, run by hand | nothing to install, no code change |
| **nginx in front** | this script and CI; the Go build, which does not terminate TLS; or a stock jar | one config file |
| **Cloudflare Tunnel** | a real phone, on cellular, scanning a real QR Code | an account for a stable hostname |

**1. Spring terminates TLS itself** — Java only. `server.ssl.*` are ordinary properties, so this is
configuration only:

```bash
keytool -genkeypair -alias x9-demo-tls -dname "CN=localhost,O=Demo,C=US" \
  -keyalg RSA -keysize 2048 -validity 3650 \
  -ext "san=dns:localhost,ip:127.0.0.1" -ext "eku=serverAuth" \
  -keystore x9-demo-tls.p12 -storetype PKCS12 -storepass x9demo123 -keypass x9demo123

java -cp "app/BOOT-INF/classes:app/BOOT-INF/lib/*" \
  com.matera.x9qrcode.infrastructure.X9QRCodeApplication \
  --server.port=8443 --server.ssl.enabled=true \
  --server.ssl.key-store=$PWD/x9-demo-tls.p12 --server.ssl.key-store-type=PKCS12 \
  --server.ssl.key-store-password=x9demo123 --server.ssl.key-alias=x9-demo-tls \
  --x9.public-endpoints.host=localhost:8443
```

The certificate is self-signed, so the *other* instance has to trust it. Copy the JDK's truststore,
add the certificate, and point the JVM at the copy:

```bash
cp "$JAVA_HOME/lib/security/cacerts" truststore.jks && chmod u+w truststore.jks
keytool -exportcert -alias x9-demo-tls -keystore x9-demo-tls.p12 -storepass x9demo123 -rfc -file tls.cer
keytool -importcert -noprompt -alias x9-demo-tls -file tls.cer -keystore truststore.jks -storepass changeit

java -Djavax.net.ssl.trustStore=$PWD/truststore.jks -Djavax.net.ssl.trustStorePassword=changeit …
```

> This truststore is the JVM's, for the TLS connection. It is **not** `x9.certificate.truststore`,
> which is the X9 PKI used to validate the signer of a JWS. The two are unrelated and both must be
> right.

**2. A small nginx in front** — what the script does, for both builds
([`pairing/nginx.conf`](pairing/nginx.conf) serves both hosts from one container). For one instance:

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

Then run the app on 8080 with `--x9.public-endpoints.host=localhost:8443`, so the URLs it advertises
name the proxy rather than itself. The payer still needs to trust the certificate, exactly as above.

**3. A Cloudflare Tunnel**, when the QR Code has to be scanned by a real phone that cannot reach
your machine. See [ENDPOINTS.md](../../ENDPOINTS.md) — including the host-length trap, which bites
anonymous quick tunnels every time.

## Two things worth knowing before you run the jar by hand

**Run it from somewhere other than the repository root.** `SPRING_PROFILES_ACTIVE` and a
`secrets/application-secrets.yml` that resolves relative to the working directory will quietly
replace the demo keystore with keys whose password you do not have. The symptom is
`UnrecoverableKeyException: failed to decrypt safe contents entry` at startup and nothing else. Set
`SPRING_PROFILES_ACTIVE=default` and run from another directory.

**The jar is exploded rather than run with `-jar`.** The keystore loader resolves `classpath:`
locations as files, and an entry nested inside `BOOT-INF/classes` is not one.

## Why this exists

A single instance cannot check whether it is interoperable, only whether it is self-consistent. Both
sides of every conversation are the same code, holding the same assumptions, so a message that is
wrong in the same way at both ends still round-trips. The same holds between the two builds: the Go
build was written to answer as the Java one does, so only a Go instance talking to a Java one shows
that each reads what the other writes. The first run of this script, Java against Java, found three
defects the suite could not:

- The payer signed its **internal** notification record instead of the contract shape, putting
  `qrCodeId` at the top level where X9.150 puts `payment.qrcodeId`. Every conformant payee refuses
  that. The test asserting the id "appears in the payload" passed either way.
- A notification without the **optional** `expectedDate` crashed the payee with a 500 while
  persisting it. Every existing test happened to send one.
- The access log reported `status 200` for responses that were 401 and 500, because the status was
  read before the request was handled.

---

<sub>Copyright © 2026 Matera Systems, Inc. Licensed under the Matera Source License v1.0 (source-available; not open source) — see LICENSE.md at the repository root.</sub>

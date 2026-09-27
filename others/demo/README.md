# Running two X9.150 instances against each other

`two-instance-payment-cycle.sh` plays a whole payment between two deployments that share nothing but
the wire: separate databases, separate ports, separate identities. One issues a QR Code, the other
scans it, announces the payment, pays, and reports it.

```bash
mvn -q package -DskipTests
./others/demo/two-instance-payment-cycle.sh            # run it
./others/demo/two-instance-payment-cycle.sh --keep     # leave both running to poke at
```

It needs a JDK and MongoDB on `localhost:27017`. Nothing else — no Docker, no proxy, no network.

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
| **Spring's own TLS** | local testing, CI, this script | nothing to install, no code change |
| **nginx in front** | you already run a proxy, or want the stock jar untouched | one config file |
| **Cloudflare Tunnel** | a real phone, on cellular, scanning a real QR Code | an account for a stable hostname |

**1. Spring terminates TLS itself** — what the script does. `server.ssl.*` are ordinary properties,
so this is configuration only:

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

**2. A small nginx in front**, if you would rather leave the jar exactly as it ships:

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

## Two things worth knowing before you debug

**Run it from somewhere other than the repository root.** `SPRING_PROFILES_ACTIVE` and a
`secrets/application-secrets.yml` that resolves relative to the working directory will quietly
replace the demo keystore with keys whose password the script does not have. The symptom is
`UnrecoverableKeyException: failed to decrypt safe contents entry` at startup and nothing else. The
script sets `SPRING_PROFILES_ACTIVE=default` and runs from its own directory for this reason.

**The jar is exploded rather than run with `-jar`.** The keystore loader resolves `classpath:`
locations as files, and an entry nested inside `BOOT-INF/classes` is not one.

## Why this exists

A single instance cannot check whether it is interoperable, only whether it is self-consistent. Both
sides of every conversation are the same code, holding the same assumptions, so a message that is
wrong in the same way at both ends still round-trips. The first run of this script found three
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

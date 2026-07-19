# secrets/

Local, environment-specific secrets for running X9 QRCode. **Real secret values
and keystores in this folder are git-ignored** — only the `*.sample` files and
this README are committed.

## Setup
1. Copy the sample and fill in real values:
   ```
   cp secrets/application-secrets.yml.sample secrets/application-secrets.yml
   ```
2. Place your signing keystore and truststore in `secrets/certificate/`
   (see `secrets/certificate/README.md`).

The application loads `secrets/application-secrets.yml` automatically via
`spring.config.import` (optional — the app still starts if it is absent).
`docker-compose.prod.yml` mounts this folder into the container.

## What belongs here
- `application-secrets.yml` — jasypt master password, keystore/truststore passwords
- `certificate/*.jks` — your X9 signing keystore and truststore

**Never commit real secrets or private keys.**

# secrets/certificate/

Place your X9 signing **keystore** and **truststore** here (e.g. `x9.jks`,
`x9-truststore.jks`). These files are **git-ignored** — they contain private keys
and must never be committed.

Generate a dev keystore with `keytool`, or use keys issued under the X9 Financial PKI.
Point `secrets/application-secrets.yml` at the files you place here.

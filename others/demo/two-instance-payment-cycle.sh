#!/usr/bin/env bash
#
# A whole payment, between two X9.150 deployments that share nothing — in every pairing of the two
# builds (Story 11.3).
#
# One instance is the PAYEE: it issues the QR Code, is told about the payment, and is finally told
# by its own payment system that the money arrived. The other is the PAYER's PSP: it scans, fetches
# the payload, announces the payment (pre-commit) and reports it (post-commit). Each has its own
# database, its own signing key and its own public host, and they talk only over HTTPS, exactly as
# two institutions would. Each side verifies every signed message the other sends.
#
#   ./others/demo/two-instance-payment-cycle.sh                        # all four pairings
#   PAIRINGS="go-java" ./others/demo/two-instance-payment-cycle.sh     # one: a Go payer, a Java payee
#   PAYER_IMAGE=x9-qrcode:go PAYEE_IMAGE=materainc/x9-qrcode:1.4.0 \
#       ./others/demo/two-instance-payment-cycle.sh                    # any two images
#   ./others/demo/two-instance-payment-cycle.sh --keep                 # leave them running afterwards
#
# A pairing is written payer-payee: `java-go` is a Java PSP paying a QR Code issued by a Go payee.
# PAIRINGS takes any of java-java, java-go, go-java and go-go (space- or comma-separated); the
# default is all four. JAVA_IMAGE (default x9-qrcode:latest, `make image`) and GO_IMAGE (default
# x9-qrcode:go, `make go-image`) say which image each build is. PAYER_IMAGE and PAYEE_IMAGE, set
# together, run one pairing of exactly those two images instead; the build of each is read from
# the image (the Go image's entrypoint is /x9-qrcode), or stated with PAYER_BUILD / PAYEE_BUILD.
#
# Per pairing, one compose project (x9-pair-<run>-<pairing>, its own network) holding:
#
#   payee, payer          the two images, plus this run's PKI copied in (nothing is bind-mounted,
#                         so it works on Colima, which cannot mount paths under /Volumes)
#   <role>-mongo          a Java instance's MongoDB, a single-node replica set (transactions)
#   <role>-postgres       a Go instance's PostgreSQL 16, with db/local/bootstrap.sql and then
#   <role>-bootstrap,     db/changelog applied by Liquibase before the service starts, the service
#   <role>-liquibase      connecting as the application role (as docker-compose.go.yml does)
#   tls                   nginx terminating HTTPS for both public hosts, payee.x9demo.test and
#                         payer.x9demo.test (network aliases), so the two builds are fronted the same
#                         way: the Go build serves plain HTTP and expects TLS in front of it
#
# Trust, generated per run into a temporary directory:
#
#   - TLS: a throwaway CA and one server certificate for both hosts. Each instance trusts the CA
#     through SSL_CERT_DIR (the Go build reads it natively; the Java image's start-up adds it to the
#     JVM's truststore, which is why the file is named by its OpenSSL subject hash).
#   - X9 signing: a self-signed identity per ROLE (payee, payer), whatever the build, as PKCS#12,
#     and one PKCS#12 truststore holding both certificates. Two different keys, so a message
#     verified by the wrong key, or by the receiver's own, fails. PKCS#12 because the Go build
#     refuses JKS (ADR-0031); the Java build reads it too.
#
# The cycle, with the pairing and the step named on any failure: health, create on the payee,
# decode on the payer (which fetches the payload from the payee over HTTPS and verifies its JWS),
# pre-commit through the payer (the payee verifies the payer's JWS through its jku and answers
# signed; the payer must report that answer VERIFIED), the payee now PAYMENT_INITIATED,
# post-commit the same way, the payee's payment system marking it PAID, and the payee's event
# stream reading payment.initiated, payment.sent, payment.cleared for that QR Code. Last, the HTTPS
# front's log must show the payload fetch, the notifications and each side's read of the other's
# JWKS: the two talked host to host over TLS, never through a shortcut.
#
# NEEDS: Docker with compose v2, openssl, keytool (any JDK), curl and python3. About 2 GiB of
# Docker memory for a Java-Java pairing; pairings run one at a time.
#
# NON-PRODUCTION. Every key this generates is throwaway; the databases trust every connection.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
HERE="$ROOT/others/demo"
KEEP=0
[ "${1:-}" = "--keep" ] && KEEP=1

JAVA_IMAGE="${JAVA_IMAGE:-x9-qrcode:latest}"
GO_IMAGE="${GO_IMAGE:-x9-qrcode:go}"
RUN="${X9_PAIR_RUN:-$(LC_ALL=C tr -dc 'a-z0-9' </dev/urandom | head -c 6 || true)}"
STAGE="$(mktemp -d "${TMPDIR:-/tmp}/x9-pair-$RUN.XXXXXX")"

PAYEE_HOST="payee.x9demo.test"
PAYER_HOST="payer.x9demo.test"
PASS="x9pair123"

# A Solana method, because it is the rail that exercises BOTH notification phases: a pre-commit
# reserves the QR Code and a post-commit carries the on-chain hash.
RECIPIENT="9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM"
PAYER_WALLET="7cVfgArCheMR6Cs4t6vz5rfnqd56vZq4ndaBrY5xkxXy"
TX_HASH="5VfydnLu4XwV2FquzyFbENPBqbCyJLxq5wRhtZ8rLraBdHfDDmWXnc8nBtCqPU4f"
AMOUNT=22500

say()  { printf '\n\033[1m== %s\033[0m\n' "$1"; }
step() { printf '   %s\n' "$1"; }
fail() { printf '\n\033[31mFAILED: %s\033[0m\n' "$1" >&2; exit 1; }

for tool in docker openssl keytool curl python3; do
    command -v "$tool" >/dev/null || fail "no $tool on PATH"
done
docker compose version >/dev/null 2>&1 || fail "docker compose (v2) is not available"

# ------------------------------------------------------------------------------------- the pairings

build_of() {   # the build an image is, read from its entrypoint, as run-against-image.sh does
    docker image inspect "$1" >/dev/null 2>&1 || docker pull -q "$1" >/dev/null \
        || fail "no image $1 (build it: make image / make go-image, or pull it)"
    case "$(docker image inspect "$1" --format '{{json .Config.Entrypoint}}')" in
        '["/x9-qrcode"]') echo go ;;
        *) echo java ;;
    esac
}

declare -a NAMES=() PAYER_IMAGES=() PAYEE_IMAGES=() PAYER_BUILDS=() PAYEE_BUILDS=()
if [ -n "${PAYER_IMAGE:-}" ] || [ -n "${PAYEE_IMAGE:-}" ]; then
    [ -n "${PAYER_IMAGE:-}" ] && [ -n "${PAYEE_IMAGE:-}" ] || fail "set PAYER_IMAGE and PAYEE_IMAGE together"
    PAYER_BUILDS+=("${PAYER_BUILD:-$(build_of "$PAYER_IMAGE")}")
    PAYEE_BUILDS+=("${PAYEE_BUILD:-$(build_of "$PAYEE_IMAGE")}")
    PAYER_IMAGES+=("$PAYER_IMAGE")
    PAYEE_IMAGES+=("$PAYEE_IMAGE")
    NAMES+=("${PAYER_BUILDS[0]}-${PAYEE_BUILDS[0]}")
else
    for pairing in $(printf '%s' "${PAIRINGS:-java-java java-go go-java go-go}" | tr ',' ' '); do
        case "$pairing" in
            java-java|java-go|go-java|go-go) ;;
            *) fail "unknown pairing '$pairing': use java-java, java-go, go-java or go-go (payer-payee)" ;;
        esac
        payer="${pairing%-*}" payee="${pairing#*-}"
        NAMES+=("$pairing")
        PAYER_BUILDS+=("$payer")
        PAYEE_BUILDS+=("$payee")
        [ "$payer" = java ] && PAYER_IMAGES+=("$JAVA_IMAGE") || PAYER_IMAGES+=("$GO_IMAGE")
        [ "$payee" = java ] && PAYEE_IMAGES+=("$JAVA_IMAGE") || PAYEE_IMAGES+=("$GO_IMAGE")
    done
fi
[ "${#NAMES[@]}" -gt 0 ] || fail "PAIRINGS is empty"
for b in "${PAYER_BUILDS[@]}" "${PAYEE_BUILDS[@]}"; do
    case "$b" in java|go) ;; *) fail "a build is java or go, not '$b'" ;; esac
done
for image in "${PAYER_IMAGES[@]}" "${PAYEE_IMAGES[@]}"; do build_of "$image" >/dev/null; done

# Every local image this run builds, removed at the end unless --keep.
declare -a BUILT=()
cleanup_run() {
    if [ "$KEEP" = "1" ]; then return; fi
    [ "${#BUILT[@]}" -eq 0 ] || docker image rm "${BUILT[@]}" >/dev/null 2>&1 || true
    rm -rf "$STAGE"
}
trap cleanup_run EXIT

# --------------------------------------------------------------------------------------------- PKI

say "PKI for run $RUN (throwaway, in $STAGE)"
PKI="$STAGE/pki"
mkdir -p "$PKI/ca"
cat > "$PKI/openssl.cnf" <<EOF
[req]
distinguished_name = dn
[dn]
[ca]
basicConstraints = critical, CA:TRUE
keyUsage = critical, keyCertSign, cRLSign
subjectKeyIdentifier = hash
[server]
basicConstraints = critical, CA:FALSE
keyUsage = critical, digitalSignature, keyEncipherment
extendedKeyUsage = serverAuth
subjectAltName = DNS:$PAYEE_HOST, DNS:$PAYER_HOST
EOF
openssl req -x509 -newkey rsa:2048 -nodes -days 30 -sha256 -config "$PKI/openssl.cnf" \
    -extensions ca -subj "/CN=X9 pairing demo TLS CA/O=NonProduction" \
    -keyout "$PKI/tls-ca.key" -out "$PKI/tls-ca.pem" 2>/dev/null
openssl req -new -newkey rsa:2048 -nodes -sha256 -config "$PKI/openssl.cnf" \
    -subj "/CN=$PAYEE_HOST/O=NonProduction" -keyout "$PKI/tls.key" -out "$PKI/tls.csr" 2>/dev/null
openssl x509 -req -in "$PKI/tls.csr" -CA "$PKI/tls-ca.pem" -CAkey "$PKI/tls-ca.key" \
    -CAcreateserial -days 30 -sha256 -extfile "$PKI/openssl.cnf" -extensions server \
    -out "$PKI/tls.pem" 2>/dev/null
# The JVM image reads SSL_CERT_DIR entries named by subject hash; Go reads any name.
cp "$PKI/tls-ca.pem" "$PKI/ca/$(openssl x509 -subject_hash -noout -in "$PKI/tls-ca.pem").0"
step "TLS: a CA, and one certificate for $PAYEE_HOST and $PAYER_HOST"

for role in payee payer; do
    host="$PAYEE_HOST"; [ "$role" = payer ] && host="$PAYER_HOST"
    keytool -genkeypair -alias x9-private-keystore -keyalg RSA -keysize 3072 -sigalg SHA512withRSA \
        -validity 30 -dname "CN=$host, OU=X9 pairing demo NonProduction, O=Demo, C=US" \
        -ext "KeyUsage:critical=digitalSignature" \
        -keystore "$PKI/$role-signing.p12" -storetype PKCS12 -storepass "$PASS" -keypass "$PASS" \
        >/dev/null 2>&1
    keytool -exportcert -alias x9-private-keystore -keystore "$PKI/$role-signing.p12" \
        -storepass "$PASS" -file "$PKI/$role.cer" >/dev/null 2>&1
    keytool -importcert -noprompt -alias "x9-$role" -file "$PKI/$role.cer" \
        -keystore "$PKI/x9-truststore.p12" -storetype PKCS12 -storepass "$PASS" >/dev/null 2>&1
done
chmod 0644 "$PKI"/*.p12 "$PKI"/ca/* "$PKI"/tls.pem "$PKI"/tls.key
step "X9 signing: a self-signed identity each for the payee and the payer; one truststore with both"

# ------------------------------------------------------------------------------------------ images

say "Images for run $RUN"
build_image() {   # tag dockerfile context [docker build args...]; quiet unless it fails
    local tag="$1" file="$2" context="$3"
    shift 3
    docker build -q -t "$tag" -f "$file" "$@" "$context" >"$STAGE/build.log" 2>&1 \
        || { cat "$STAGE/build.log" >&2; fail "could not build $tag"; }
    BUILT+=("$tag")
}
mkdir -p "$STAGE/instance/ca" "$STAGE/tls"
cp "$PKI"/payee-signing.p12 "$PKI"/payer-signing.p12 "$PKI"/x9-truststore.p12 "$STAGE/instance/"
cp "$PKI"/ca/* "$STAGE/instance/ca/"
cp "$PKI/tls.pem" "$PKI/tls.key" "$HERE/pairing/nginx.conf" "$STAGE/tls/"

# The local image of one source image: that image plus the PKI. bash 3.2 (macOS) has no maps.
instance_image() { printf 'x9-pair-instance:%s-%s' "$RUN" "$(printf '%s' "$1" | tr -c 'a-zA-Z0-9_.-' '_' | tail -c 80)"; }
for image in $(printf '%s\n' "${PAYER_IMAGES[@]}" "${PAYEE_IMAGES[@]}" | sort -u); do
    tag="$(instance_image "$image")"
    build_image "$tag" "$HERE/pairing/instance.Dockerfile" "$STAGE/instance" --build-arg "X9_IMAGE=$image"
    step "$tag  ($image, plus this run's PKI)"
done
TLS_IMAGE="x9-pair-tls:$RUN"
build_image "$TLS_IMAGE" "$HERE/pairing/tls.Dockerfile" "$STAGE/tls"
step "$TLS_IMAGE  (nginx, HTTPS for both hosts)"
if printf '%s\n' "${PAYER_BUILDS[@]}" "${PAYEE_BUILDS[@]}" | grep -qx go; then
    BOOTSTRAP_IMAGE="x9-pair-bootstrap:$RUN"
    LIQUIBASE_IMAGE="x9-pair-liquibase:$RUN"
    build_image "$BOOTSTRAP_IMAGE" "$ROOT/db/local/bootstrap.Dockerfile" "$ROOT/db"
    build_image "$LIQUIBASE_IMAGE" "$ROOT/db/local/liquibase.Dockerfile" "$ROOT/db"
    step "$BOOTSTRAP_IMAGE, $LIQUIBASE_IMAGE  (db/, for the Go instances)"
fi

# ----------------------------------------------------------------------------------------- compose

# The services one instance needs: its database and itself. $1 role, $2 build, $3 image, $4 host.
instance_services() {
    local role="$1" build="$2" image="$3" host="$4"
    if [ "$build" = java ]; then
        cat <<EOF
  $role-mongo:
    image: mongo:7.0.45
    command: ["mongod", "--replSet", "x9-qrcode", "--bind_ip_all", "--wiredTigerCacheSizeGB", "0.25"]
    healthcheck:
      test: ["CMD", "mongosh", "--quiet", "--eval",
             "try { rs.status().ok } catch (e) { rs.initiate({_id: 'x9-qrcode', members: [{_id: 0, host: '$role-mongo:27017'}]}).ok }; db.hello().isWritablePrimary ? 0 : quit(1)"]
      interval: 2s
      timeout: 10s
      retries: 60
      start_period: 5s
    tmpfs: [/data/db, /data/configdb]

  $role:
    image: "$image"
    depends_on:
      $role-mongo:
        condition: service_healthy
    mem_limit: 1g
    ports: ["127.0.0.1::8080"]
    environment:
      SPRING_DATA_MONGODB_URI: "mongodb://$role-mongo:27017/x9-qrcode?replicaSet=x9-qrcode"
      X9_PUBLICENDPOINTS_HOST: "$host"
      X9_CERTIFICATE_PRIVATEKEYSTORE_LOCATION: "file:/x9-pair/$role-signing.p12"
      X9_CERTIFICATE_PRIVATEKEYSTORE_TYPE: PKCS12
      X9_CERTIFICATE_PRIVATEKEYSTORE_PASSWORD: "$PASS"
      X9_CERTIFICATE_PRIVATEKEYSTORE_PRIVATEKEYPASSWORD: "$PASS"
      X9_CERTIFICATE_TRUSTSTORE_LOCATION: "file:/x9-pair/x9-truststore.p12"
      X9_CERTIFICATE_TRUSTSTORE_TYPE: PKCS12
      X9_CERTIFICATE_TRUSTSTORE_PASSWORD: "$PASS"
      SSL_CERT_DIR: "/etc/ssl/certs:/x9-pair/ca"

EOF
    else
        cat <<EOF
  $role-postgres:
    image: postgres:16
    environment:
      POSTGRES_HOST_AUTH_METHOD: trust
    healthcheck:
      test: ["CMD", "pg_isready", "-U", "postgres", "-d", "postgres"]
      interval: 2s
      timeout: 3s
      retries: 30
    tmpfs: [/var/lib/postgresql/data]

  $role-bootstrap:
    image: "$BOOTSTRAP_IMAGE"
    depends_on:
      $role-postgres:
        condition: service_healthy
    command: ["psql", "-X", "-q", "-h", "$role-postgres", "-U", "postgres", "-v", "owner=x9_qrcode_owner",
              "-v", "schema=x9_qrcode", "-f", "/x9/bootstrap.sql"]

  $role-liquibase:
    image: "$LIQUIBASE_IMAGE"
    depends_on:
      $role-bootstrap:
        condition: service_completed_successfully
    environment:
      LIQUIBASE_SEARCH_PATH: /liquibase/x9
      LIQUIBASE_COMMAND_CHANGELOG_FILE: changelog/db.changelog-master.yaml
      LIQUIBASE_COMMAND_URL: jdbc:postgresql://$role-postgres:5432/postgres
      LIQUIBASE_COMMAND_USERNAME: x9_qrcode_owner
      LIQUIBASE_COMMAND_DEFAULT_SCHEMA_NAME: x9_qrcode
      LIQUIBASE_LIQUIBASE_SCHEMA_NAME: x9_qrcode
      LIQUIBASE_SHOW_BANNER: "false"
      # Two of these may start together (go-go); a small heap keeps both inside a 4 GiB Docker VM.
      JAVA_OPTS: "-Xmx192m -XX:+UseSerialGC"
    command: ["update"]

  $role:
    image: "$image"
    depends_on:
      $role-liquibase:
        condition: service_completed_successfully
    ports: ["127.0.0.1::8080"]
    environment:
      DATABASE_HOST: $role-postgres
      DATABASE_NAME: postgres
      DATABASE_USER: x9_qrcode_app
      # The database trusts every connection; the Go build still requires a credential to be set.
      DATABASE_PASSWORD: unused
      DATABASE_SSLMODE: disable
      X9_PUBLICENDPOINTS_HOST: "$host"
      X9_CERTIFICATE_PRIVATEKEYSTORE_LOCATION: "/x9-pair/$role-signing.p12"
      X9_CERTIFICATE_PRIVATEKEYSTORE_TYPE: PKCS12
      X9_CERTIFICATE_PRIVATEKEYSTORE_PASSWORD: "$PASS"
      X9_CERTIFICATE_TRUSTSTORE_LOCATION: "/x9-pair/x9-truststore.p12"
      X9_CERTIFICATE_TRUSTSTORE_TYPE: PKCS12
      X9_CERTIFICATE_TRUSTSTORE_PASSWORD: "$PASS"
      SSL_CERT_DIR: "/etc/ssl/certs:/x9-pair/ca"

EOF
    fi
}

write_compose() {   # $1 file, $2 payer build, $3 payer image, $4 payee build, $5 payee image
    {
        echo "# Generated by others/demo/two-instance-payment-cycle.sh for one pairing; not committed."
        echo "services:"
        instance_services payee "$4" "$5" "$PAYEE_HOST"
        instance_services payer "$2" "$3" "$PAYER_HOST"
        cat <<EOF
  tls:
    image: "$TLS_IMAGE"
    depends_on: [payee, payer]
    networks:
      default:
        aliases: ["$PAYEE_HOST", "$PAYER_HOST"]
EOF
    } > "$1"
}

# ---------------------------------------------------------------------------------------- the cycle

# One pairing, in a subshell: `fail` ends the pairing, not the run. $1 name, then as write_compose.
run_pairing() {
    local name="$1" payer_build="$2" payer_image="$3" payee_build="$4" payee_image="$5"
    local project="x9-pair-$RUN-$name" work="$STAGE/$name" current="start"
    mkdir -p "$work"
    # Not local: the EXIT trap that tears the project down runs after this function has returned.
    compose=(docker compose -p "$project" -f "$work/compose.yml")

    fail() {
        printf '\n\033[31mFAILED [%s payer → %s payee] at %s: %s\033[0m\n' \
            "$payer_build" "$payee_build" "$current" "$1" >&2
        "${compose[@]}" logs --no-color --tail 60 payee payer >&2 2>&1 || true
        exit 1
    }
    teardown() {
        if [ "$KEEP" = "1" ]; then
            say "Left running: $project"
            step "payee http://127.0.0.1:${PAYEE_PORT:-?}   payer http://127.0.0.1:${PAYER_PORT:-?}"
            step "stop with: docker compose -p $project down -v"
            return
        fi
        "${compose[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
    }
    trap teardown EXIT

    say "Pairing $name: $payer_build payer ($payer_image) → $payee_build payee ($payee_image)"
    write_compose "$work/compose.yml" "$payer_build" "$(instance_image "$payer_image")" \
        "$payee_build" "$(instance_image "$payee_image")"

    current="start-up"
    "${compose[@]}" up -d --quiet-pull tls >"$work/up.log" 2>&1 || { cat "$work/up.log" >&2; fail "compose up failed"; }
    PAYEE_PORT="$("${compose[@]}" port payee 8080 | sed 's/.*://')"
    PAYER_PORT="$("${compose[@]}" port payer 8080 | sed 's/.*://')"
    local PAYEE="http://127.0.0.1:$PAYEE_PORT" PAYER="http://127.0.0.1:$PAYER_PORT"

    await() {
        local url="$1" who="$2" code=""
        for _ in $(seq 1 90); do
            code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "$url/actuator/health" || true)"
            [ "$code" = "200" ] && { step "$who up"; return 0; }
            sleep 2
        done
        fail "the $who never became healthy (last status: ${code:-none})"
    }
    await "$PAYEE" "payee ($payee_build)"
    await "$PAYER" "payer ($payer_build)"

    json() { python3 -c "import json,sys;print(json.load(open(sys.argv[1]))$1)" "$2"; }

    call() {   # method url body-file out-file expected-status
        local code
        if [ -n "$3" ]; then
            code="$(curl -s -X "$1" "$2" -H 'Content-Type: application/json' -d @"$3" -o "$4" -w '%{http_code}')"
        else
            code="$(curl -s -X "$1" "$2" -o "$4" -w '%{http_code}')"
        fi
        [ "$code" = "$5" ] || { cat "$4" >&2; echo >&2; fail "$1 $2 answered $code, expected $5"; }
    }

    current="create (payee)"
    cat > "$work/create.json" <<EOF
{
  "validUntil": "2030-12-31T23:59:59Z",
  "creditor": {
    "name": "California Electric Company", "phone": "+15552223333",
    "email": "billing@californiaelectric.com",
    "address": { "line1": "500 Energy Ave", "city": "Los Angeles", "state": "CA",
                 "postalCode": "90012", "country": "US" },
    "MCC": "4900"
  },
  "bill": {
    "description": "Electricity service",
    "invoice": { "number": "INV-DEMO-1", "date": "2030-01-10", "dueDate": "2030-12-31T23:59:59Z" },
    "amountDue": { "amount": $AMOUNT, "currency": "USDC" }
  },
  "paymentNotification": { "kind": "DEFAULT" },
  "paymentMethods": [
    { "currency": "USDC", "validUntil": "2030-12-31T23:59:59Z", "amount": $AMOUNT,
      "networks": { "solana": { "recipient": "$RECIPIENT" } } }
  ]
}
EOF
    call POST "$PAYEE/api/v1/payment-request" "$work/create.json" "$work/created.json" 201
    local QR_ID EMV
    QR_ID="$(json "['id']" "$work/created.json")"
    EMV="$(json "['qrCode']" "$work/created.json")"
    step "1. the payee issued $QR_ID"

    current="decode and payload fetch (payer)"
    python3 -c "import json,sys;print(json.dumps({'qrCode':sys.argv[1]}))" "$EMV" > "$work/decode.json"
    call POST "$PAYER/api/v1/qrcode-emv-decoder" "$work/decode.json" "$work/decoded.json" 200
    local ENDPOINT
    ENDPOINT="$(json "['paymentNotification']" "$work/decoded.json")"
    [ "$(json "['id']" "$work/decoded.json")" = "$QR_ID" ] || fail "the decoded payload is not $QR_ID"
    [ "$(json "['paymentMethods'][0]['networks']['solana']['recipient']" "$work/decoded.json")" = "$RECIPIENT" ] \
        || fail "the decoded payload does not name the recipient"
    case "$ENDPOINT" in
        "https://$PAYEE_HOST/"*) ;;
        *) fail "the payload names the notification endpoint '$ENDPOINT', not one on $PAYEE_HOST" ;;
    esac
    step "2. the payer fetched the payload over HTTPS from $PAYEE_HOST and verified the payee's JWS"

    current="pre-commit (payer → payee)"
    cat > "$work/pre.json" <<EOF
{ "endpoint": "$ENDPOINT",
  "notification": {
    "payment": { "qrcodeId": "$QR_ID", "amount": $AMOUNT, "currency": "USDC", "network": "Solana" },
    "payer": { "info": "demo-payer@example.com" },
    "blockchain": { "action": "PAYMENT_INITIATED", "from": "$PAYER_WALLET", "to": "$RECIPIENT" }
  } }
EOF
    call POST "$PAYER/api/v1/payment-notification/pre-payment" "$work/pre.json" "$work/pre-result.json" 200
    signed_yes "$work/pre-result.json"
    call GET "$PAYEE/api/v1/payment-request/$QR_ID" "" "$work/status.json" 200
    [ "$(json "['status']" "$work/status.json")" = "PAYMENT_INITIATED" ] \
        || fail "after the pre-commit the payee holds $(json "['status']" "$work/status.json"), not PAYMENT_INITIATED"
    step "3. pre-commit: the payee verified the payer's JWS and reserved the QR Code; its signed yes verified"

    current="post-commit (payer → payee)"
    cat > "$work/post.json" <<EOF
{ "endpoint": "$ENDPOINT",
  "notification": {
    "payment": { "qrcodeId": "$QR_ID", "amount": $AMOUNT, "currency": "USDC", "network": "Solana",
                 "transactionId": "$TX_HASH" },
    "payer": { "info": "demo-payer@example.com" },
    "blockchain": { "action": "SENT", "from": "$PAYER_WALLET", "to": "$RECIPIENT" }
  } }
EOF
    call POST "$PAYER/api/v1/payment-notification/post-payment" "$work/post.json" "$work/post-result.json" 200
    signed_yes "$work/post-result.json"
    step "4. post-commit: the payee verified the payer's JWS and recorded the hash; its signed yes verified"

    current="status PAID (payee's payment system)"
    cat > "$work/paid.json" <<EOF
{ "status": "PAID", "network": "solana", "endToEndId": "$TX_HASH" }
EOF
    call PUT "$PAYEE/api/v1/payment-request/$QR_ID/status-update" "$work/paid.json" "$work/paid-result.json" 200
    call GET "$PAYEE/api/v1/payment-request/$QR_ID" "" "$work/status.json" 200
    [ "$(json "['status']" "$work/status.json")" = "PAID" ] \
        || fail "the payee holds $(json "['status']" "$work/status.json"), not PAID"
    step "5. the payee's payment system saw the funds: PAID"

    current="events (payee)"
    local types="" want="payment.initiated payment.sent payment.cleared"
    for _ in $(seq 1 20); do
        call GET "$PAYEE/pub/api/v1/events?limit=500" "" "$work/events.json" 200
        types="$(python3 -c "
import json,sys
print(' '.join(e.get('type','') for e in json.load(open(sys.argv[1])).get('events',[]) if e.get('qrCodeId')==sys.argv[2]))" \
            "$work/events.json" "$QR_ID")"
        [ "$types" = "$want" ] && break
        sleep 1
    done
    [ "$types" = "$want" ] || fail "the payee's event stream reads '${types:-nothing}' for $QR_ID, expected '$want'"
    step "6. the payee's event stream: $types"

    # What crossed the wire between the two, as the HTTPS front logged it: every exchange above went
    # host to host over TLS, and each side fetched the other's key from the jku it was given.
    current="the wire (HTTPS front)"
    "${compose[@]}" logs --no-color tls 2>/dev/null | grep -o 'x9-wire .*' > "$work/wire.log" || true
    wire() {
        grep -Eq "^x9-wire $1 \"$2 HTTP/[0-9.]+\" $3\$" "$work/wire.log" || {
            cat "$work/wire.log" >&2; fail "the HTTPS front never saw $1 $2 answered $3"; }
    }
    wire "$PAYEE_HOST" "POST /pub/api/v1/loc/[A-Za-z0-9]+" 200
    wire "$PAYEE_HOST" "POST /pub/api/v1/payment-notification" 200
    wire "$PAYEE_HOST" "GET /pub/.well-known/jwks" 200
    wire "$PAYER_HOST" "GET /pub/.well-known/jwks" 200
    step "7. over HTTPS: the payload fetch, both notifications, and each side reading the other's JWKS"

    current="done"
    say "Pairing $name: complete"
}

# The payer's report of the payee's answer: accepted, and signed by the payee in a way the payer
# verified (ANSI X9.150 A.4.5).
signed_yes() {
    local accepted signature
    accepted="$(json "['accepted']" "$1")"
    signature="$(json ".get('signature')" "$1")"
    [ "$accepted" = "True" ] || { cat "$1" >&2; fail "the payee refused it"; }
    [ "$signature" = "VERIFIED" ] || { cat "$1" >&2; fail "the payee's answer was accepted but its signature is $signature, not VERIFIED"; }
}

declare -a RESULTS=()
status=0
for i in "${!NAMES[@]}"; do
    if ( run_pairing "${NAMES[$i]}" "${PAYER_BUILDS[$i]}" "${PAYER_IMAGES[$i]}" \
                     "${PAYEE_BUILDS[$i]}" "${PAYEE_IMAGES[$i]}" ); then
        RESULTS+=("PASS  ${PAYER_BUILDS[$i]} payer → ${PAYEE_BUILDS[$i]} payee")
    else
        RESULTS+=("FAIL  ${PAYER_BUILDS[$i]} payer → ${PAYEE_BUILDS[$i]} payee")
        status=1
    fi
done

say "Summary (run $RUN)"
for r in "${RESULTS[@]}"; do step "$r"; done
exit "$status"

#!/usr/bin/env bash
#
# A whole payment, between two X9.150 deployments that share nothing.
#
# One instance is the PAYEE: it issues the QR Code and waits to be told about the payment. The other
# is the PAYER: it scans, fetches the payload, announces the payment, and reports it. They have
# separate databases and talk only over HTTPS, exactly as two institutions would.
#
#   ./others/demo/two-instance-payment-cycle.sh            # run it
#   ./others/demo/two-instance-payment-cycle.sh --keep     # leave both running afterwards
#
# NEEDS: a JDK (for java and keytool), MongoDB on localhost:27017, and the project built. Nothing
# else — no Docker, no nginx, no tunnel, no network access. See README.md in this directory for why
# HTTPS is unavoidable here and what the alternatives are.
#
# NON-PRODUCTION. The TLS key this generates is throwaway and the signing keys are the bundled demo
# ones, which are self-signed and public.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
WORK="${X9_DEMO_DIR:-${TMPDIR:-/tmp}/x9-demo}"
KEEP=0
[ "${1:-}" = "--keep" ] && KEEP=1

PAYEE_PORT=8443
PAYER_PORT=8444
STOREPASS="x9demo123"

PAYEE="https://localhost:${PAYEE_PORT}"
PAYER="https://localhost:${PAYER_PORT}"

# A Solana method, because it is the rail that exercises BOTH notification phases: a pre-payment
# reserves the QR Code and a post-payment carries the on-chain hash. FedNow and RTP carry the id
# inside the ISO 20022 message instead, so there is nothing to announce beforehand.
RECIPIENT="9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM"
PAYER_WALLET="7cVfgArCheMR6Cs4t6vz5rfnqd56vZq4ndaBrY5xkxXy"
TX_HASH="5VfydnLu4XwV2FquzyFbENPBqbCyJLxq5wRhtZ8rLraBdHfDDmWXnc8nBtCqPU4f"
AMOUNT=22500

say()  { printf '\n\033[1m== %s\033[0m\n' "$1"; }
step() { printf '   %s\n' "$1"; }
die()  { printf '\n\033[31mFAILED: %s\033[0m\n' "$1" >&2; exit 1; }

cleanup() {
    if [ "$KEEP" = "1" ]; then
        say "Left running"
        step "payee $PAYEE   (log: $WORK/payee.log)"
        step "payer $PAYER   (log: $WORK/payer.log)"
        step "stop with: pkill -f X9QRCodeApplication"
        return
    fi
    pkill -f "X9QRCodeApplication" 2>/dev/null || true
}
trap cleanup EXIT

# --------------------------------------------------------------------------------- prerequisites

command -v java    >/dev/null || die "no java on PATH"
command -v keytool >/dev/null || die "no keytool on PATH (it ships with the JDK)"

JAR="$(ls "$ROOT"/x9-qrcode-infrastructure/target/x9-qrcode-infrastructure-*.jar 2>/dev/null | head -1 || true)"
[ -n "$JAR" ] || die "build it first:  mvn -q package -DskipTests"

mkdir -p "$WORK"

# ------------------------------------------------------------------------------------ TLS, local
#
# Spring Boot terminates TLS itself, so this needs no proxy and no code change — server.ssl.* are
# ordinary properties passed on the command line. One self-signed certificate for `localhost`
# serves both instances, and each trusts it through a truststore copied from the JDK's own.

say "TLS material (self-signed, localhost, throwaway)"

if [ ! -f "$WORK/tls/x9-demo-tls.p12" ]; then
    mkdir -p "$WORK/tls"
    keytool -genkeypair -alias x9-demo-tls -dname "CN=localhost,O=Matera Systems Demo,C=US" \
        -keyalg RSA -keysize 2048 -sigalg SHA256withRSA -validity 3650 \
        -ext "san=dns:localhost,ip:127.0.0.1" -ext "eku=serverAuth" \
        -keystore "$WORK/tls/x9-demo-tls.p12" -storetype PKCS12 \
        -storepass "$STOREPASS" -keypass "$STOREPASS" >/dev/null
    keytool -exportcert -alias x9-demo-tls -keystore "$WORK/tls/x9-demo-tls.p12" \
        -storepass "$STOREPASS" -rfc -file "$WORK/tls/x9-demo-tls.cer" >/dev/null

    CACERTS="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")}/lib/security/cacerts"
    [ -f "$CACERTS" ] || die "could not find the JDK truststore (looked at $CACERTS)"
    cp "$CACERTS" "$WORK/tls/x9-demo-truststore.jks"
    chmod u+w "$WORK/tls/x9-demo-truststore.jks"
    keytool -importcert -noprompt -alias x9-demo-tls -file "$WORK/tls/x9-demo-tls.cer" \
        -keystore "$WORK/tls/x9-demo-truststore.jks" -storepass changeit >/dev/null
    step "generated in $WORK/tls"
else
    step "reusing $WORK/tls"
fi

# ---------------------------------------------------------------------------------- the two apps
#
# The jar is exploded rather than run with -jar: the keystore loader resolves `classpath:` entries
# as files, which a nested BOOT-INF/classes entry is not.

say "Starting both instances"

pkill -f "X9QRCodeApplication" 2>/dev/null || true
sleep 1

rm -rf "$WORK/app" && mkdir -p "$WORK/app"
( cd "$WORK/app" && jar xf "$JAR" )

start() {
    local name="$1" port="$2" db="$3"

    # SPRING_PROFILES_ACTIVE=default on purpose: a developer's own profile may import secrets/ and
    # replace the demo keystore with keys this script does not have the password for.
    ( cd "$WORK" && SPRING_PROFILES_ACTIVE=default nohup java \
        -Djavax.net.ssl.trustStore="$WORK/tls/x9-demo-truststore.jks" \
        -Djavax.net.ssl.trustStorePassword=changeit \
        -cp "app/BOOT-INF/classes:app/BOOT-INF/lib/*" \
        com.matera.x9qrcode.infrastructure.X9QRCodeApplication \
        --server.port="$port" \
        --server.ssl.enabled=true \
        --server.ssl.key-store="$WORK/tls/x9-demo-tls.p12" \
        --server.ssl.key-store-type=PKCS12 \
        --server.ssl.key-store-password="$STOREPASS" \
        --server.ssl.key-alias=x9-demo-tls \
        --x9.public-endpoints.host="localhost:$port" \
        --spring.data.mongodb.uri="mongodb://localhost:27017/$db?directConnection=true" \
        > "$WORK/$name.log" 2>&1 & )
}

await() {
    local url="$1" name="$2" code=""

    for _ in $(seq 1 40); do
        code="$(curl -sk -o /dev/null -w "%{http_code}" --max-time 5 "$url/actuator/health" || true)"
        [ "$code" = "200" ] && { step "$name up at $url"; return 0; }
        sleep 3
    done

    tail -5 "$WORK/$name.log" >&2
    die "$name never became healthy (last status: ${code:-none}) — is MongoDB running on 27017?"
}

start payee "$PAYEE_PORT" x9demo_payee
start payer "$PAYER_PORT" x9demo_payer
await "$PAYEE" payee
await "$PAYER" payer

# ------------------------------------------------------------------------------------- the cycle

json() { python3 -c "import json,sys;print(json.load(open(sys.argv[1]))$1)" "$2"; }

call() {
    local method="$1" url="$2" body="$3" out="$4" expected="$5"
    local code

    if [ -n "$body" ]; then
        code="$(curl -sk -X "$method" "$url" -H 'Content-Type: application/json' -d @"$body" -o "$out" -w '%{http_code}')"
    else
        code="$(curl -sk -X "$method" "$url" -o "$out" -w '%{http_code}')"
    fi

    [ "$code" = "$expected" ] || { cat "$out"; die "$method $url answered $code, expected $expected"; }
}

say "1. The payee issues a QR Code"

cat > "$WORK/create.json" <<EOF
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

call POST "$PAYEE/api/v1/payment-request" "$WORK/create.json" "$WORK/created.json" 201

QR_ID="$(json "['id']" "$WORK/created.json")"
EMV="$(json "['qrCode']" "$WORK/created.json")"

step "id  $QR_ID"
step "EMV ${EMV:0:64}…"

say "2. The payer scans it"

python3 -c "import json,sys;print(json.dumps({'qrCode':sys.argv[1]}))" "$EMV" > "$WORK/decode.json"
call POST "$PAYER/api/v1/qrcode-emv-decoder" "$WORK/decode.json" "$WORK/decoded.json" 200

ENDPOINT="$(json "['paymentNotification']" "$WORK/decoded.json")"

step "fetched the payload from the payee over HTTPS and verified its JWS"
step "owed  $(json "['bill']['amountDue']['amount']" "$WORK/decoded.json") $(json "['bill']['amountDue']['currency']" "$WORK/decoded.json")"
step "to    $(json "['paymentMethods'][0]['networks']['solana']['recipient']" "$WORK/decoded.json")"
step "notify $ENDPOINT"

say "3. The payer announces the payment, and waits for permission"

cat > "$WORK/pre.json" <<EOF
{ "endpoint": "$ENDPOINT",
  "notification": {
    "payment": { "qrcodeId": "$QR_ID", "amount": $AMOUNT, "currency": "USDC", "network": "Solana" },
    "payer": { "info": "demo-payer@example.com" },
    "blockchain": { "action": "PAYMENT_INITIATED", "from": "$PAYER_WALLET", "to": "$RECIPIENT" }
  } }
EOF

call POST "$PAYER/api/v1/payment-notification/pre-payment" "$WORK/pre.json" "$WORK/pre-result.json" 200

ACCEPTED="$(json "['accepted']" "$WORK/pre-result.json")"
[ "$ACCEPTED" = "True" ] || { cat "$WORK/pre-result.json"; die "the payee refused the pre-payment"; }

call GET "$PAYEE/api/v1/payment-request/$QR_ID" "" "$WORK/status.json" 200
step "payee accepted; QR Code is now $(json "['status']" "$WORK/status.json") — reserved, and no longer payable by anyone else"

say "4. The payer pays on Solana"
step "(simulated — X9.150 never moves money)"
step "txHash ${TX_HASH:0:24}…"

say "5. The payer reports the payment"

cat > "$WORK/post.json" <<EOF
{ "endpoint": "$ENDPOINT",
  "notification": {
    "payment": { "qrcodeId": "$QR_ID", "amount": $AMOUNT, "currency": "USDC", "network": "Solana",
                 "transactionId": "$TX_HASH" },
    "payer": { "info": "demo-payer@example.com" },
    "blockchain": { "action": "SENT", "from": "$PAYER_WALLET", "to": "$RECIPIENT" }
  } }
EOF

call POST "$PAYER/api/v1/payment-notification/post-payment" "$WORK/post.json" "$WORK/post-result.json" 200

ACCEPTED="$(json "['accepted']" "$WORK/post-result.json")"
[ "$ACCEPTED" = "True" ] || { cat "$WORK/post-result.json"; die "the payee refused the post-payment"; }

step "payee accepted the report"

say "6. What the payee's own systems now see"

call GET "$PAYEE/api/v1/payment-request/$QR_ID" "" "$WORK/status.json" 200
step "QR Code status: $(json "['status']" "$WORK/status.json")"
step "  still not PAID, and that is correct: X9.150 never sees the funds. Only the system that"
step "  actually received them may say paid, through the status endpoint."

# The drain moves events from the outbox into the log on a schedule, so the last one may not have
# landed yet. Poll rather than sleep a fixed amount.
for _ in $(seq 1 15); do
    call GET "$PAYEE/pub/api/v1/events?after=" "" "$WORK/events.json" 200
    COUNT="$(python3 -c "
import json
print(len([e for e in json.load(open('$WORK/events.json')).get('events',[]) if e.get('qrCodeId')=='$QR_ID']))")"
    [ "$COUNT" -ge 2 ] && break
    sleep 2
done

step "event stream:"
python3 -c "
import json
page = json.load(open('$WORK/events.json'))
for e in page.get('events', []):
    if e.get('qrCodeId') == '$QR_ID':
        print('     %-18s %s  tx=%s' % (e.get('type'), e.get('occurredAt'), e.get('transactionId') or '—'))
print('     cursor: %s' % page.get('nextCursor'))"

say "Done — a complete cycle between two independent deployments"

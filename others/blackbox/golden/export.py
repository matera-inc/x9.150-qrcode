#!/usr/bin/env python3
# Copyright © 2026 Matera Systems, Inc.
# Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
"""Exports the golden JWS and EMV sets from a running Java build (Story 4.7, ADR-0027).

Run by ``export.sh``, once per signing identity, inside the service's network namespace:

    python others/blackbox/golden/export.py capture     # GOLDEN_IDENTITY, GOLDEN_KEYSTORE, ...

The Java service under export signs with one of the repository's bundled TEST keystores
(``x9-test-payer*.p12``, NON-PRODUCTION, their keys are public), with its clock moved to
:data:`EPOCH`. This script has it sign and encode the real artifacts, and writes them down:

- **from Java, untouched** (``producedBy: java``): payload requests, payload responses, the
  notifications it sends as a payer, its signed answers to notifications (yes and refusals), and
  the EMV string of each QR Code it creates;
- **Java's output, tampered** (``java+tamper``): bytes changed after signing, no key involved;
- **Java's header, re-signed** (``java+resign``): one header field changed and the token signed
  again with the same bundled test key, for the defects Java itself never emits (``crit``,
  ``typ``, ``x5c``, ``iat``...). A re-signed, unchanged control proves the re-signing is not what
  gets refused;
- **constructed** (``constructed``): things that are not a JWS at all.

Every expected verdict written here is what this script believes Java answers. It is not trusted:
``tests/test_golden.py`` feeds each fixture to the running Java build and fails where the manifest
is wrong, and ``export.sh`` is not finished until that test has passed.

This is the one place in ``others/blackbox`` allowed to call ``/api/v1/signature/generate``: here
Java signing is the point, not a shortcut (compare ``test_signatures.py``, S3.2-AC5).
"""
import base64
import copy
import datetime
import hashlib
import json
import os
import sys
import time
import urllib.request
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))

from cryptography import x509                                              # noqa: E402
from cryptography.hazmat.primitives import hashes, serialization           # noqa: E402
from cryptography.hazmat.primitives.asymmetric import ec, padding          # noqa: E402
from cryptography.hazmat.primitives.asymmetric.utils import decode_dss_signature  # noqa: E402
from cryptography.hazmat.primitives.serialization import pkcs12            # noqa: E402

from x9client import emv, jws, samples                                     # noqa: E402
from x9client import peer as peer_module                                   # noqa: E402
from x9client.api import X9Api                                             # noqa: E402
from x9client.clock import Clock, format_instant, parse_instant            # noqa: E402

# Every fixture is signed at, and judged near, this instant. It is after the bundled test PKI's
# notBefore (2026-09-27) and before anybody runs this, so the committed set is always judged on a
# clock moved BACK, and never goes stale as real time passes.
EPOCH = "2026-10-01T00:00:00.000Z"
CRL_PORT = 18081                    # the distribution point baked into the bundled test leaves
SKEW_MS = 60_000                    # x9.public-endpoints.jws-clock-skew-seconds, the default
TTL_MS = 300_000                    # x9.public-endpoints.jws-ttl-seconds, the default
ISSUER_NAME = "X9"                  # x9.certificate.issuer-name, the default

GENERATE = "/api/v1/signature/generate"
NOTIFY = "/pub/api/v1/payment-notification"
PRE = "/api/v1/payment-notification/pre-payment"
POST = "/api/v1/payment-notification/post-payment"

ORDER = ["rsa", "ec", "revoked", "untrusted"]
TRUSTED = {"rsa", "ec"}

VERIFIED = {"verdict": "VERIFIED"}


def refused(problem):
    return {"verdict": "REFUSED", "signatureProblem": problem}


# ------------------------------------------------------------------------------- signing

_DIGESTS = {"256": hashes.SHA256, "384": hashes.SHA384, "512": hashes.SHA512}
_EC_SIZES = {"256": 32, "384": 48, "512": 66}


def sign_raw(key, alg, data, *, der=False):
    """A JWS signature of ``data`` under ``alg``, with the digest the algorithm names."""
    digest = _DIGESTS[alg[2:]]()
    if isinstance(key, ec.EllipticCurvePrivateKey):
        signature = key.sign(data, ec.ECDSA(digest))
        if der:
            return signature
        r, s = decode_dss_signature(signature)
        size = _EC_SIZES[alg[2:]]
        return r.to_bytes(size, "big") + s.to_bytes(size, "big")
    if alg.startswith("PS"):
        return key.sign(data, padding.PSS(mgf=padding.MGF1(digest), salt_length=digest.digest_size),
                        digest)
    return key.sign(data, padding.PKCS1v15(), digest)


def resign(header, payload_b64, key, *, der=False, sign_alg=None):
    """``header`` over the SAME payload bytes, signed with ``key`` under ``header['alg']`` (or
    under ``sign_alg``, for a header that lies about its algorithm)."""
    signing_input = f"{jws.b64u_json(header)}.{payload_b64}".encode()
    signature = sign_raw(key, sign_alg or header["alg"], signing_input, der=der)
    return f"{signing_input.decode()}.{jws.b64u(signature)}"


def keystore_identity(name):
    """The key and chain of a bundled test keystore, the one the service under export signs with."""
    cert_dir = os.environ.get("X9_TEST_CA_DIR") or jws.DEFAULT_CERT_DIR
    with open(os.path.join(cert_dir, f"{name}.p12"), "rb") as f:
        key, certificate, others = pkcs12.load_key_and_certificates(f.read(), jws.STOREPASS)
    return jws.Identity(name, key, certificate, [certificate, *others])


# ------------------------------------------------------------------------------- the part

class Part:
    """What one identity's run produced: JWS files with their cases, and EMV strings."""

    def __init__(self, identity, host):
        self.identity = identity
        self.host = host
        self.jws = []
        self.emv = []

    def add(self, name, token, *, kind, produced_by, what, expected=None, cases=None,
            derived_from=None, http_status=None):
        """Record one token. ``expected`` is its verdict one second after its ``iat``; ``cases``
        adds or replaces cases as ``{name: (verify_at_ms, expected)}``."""
        entry = {"id": f"{self.identity}-{name}", "kind": kind, "signer": self.identity,
                 "producedBy": produced_by, "what": what, "token": token}
        if derived_from:
            entry["derivedFrom"] = f"{self.identity}-{derived_from}"
        if http_status is not None:
            entry["httpStatus"] = http_status
        entry["cases"] = []
        if expected is not None:
            entry["cases"].append(case("fresh", self.reference_ms + 1000, expected))
        for case_name, (at, verdict) in (cases or {}).items():
            entry["cases"].append(case(case_name, at, verdict))
        self.jws.append(entry)
        return token


def case(name, at_ms, expected):
    return {"name": name, "verifyAt": format_instant(datetime.datetime.fromtimestamp(
        at_ms / 1000, datetime.timezone.utc)), "verifyAtMillis": at_ms, "expected": expected}


# ------------------------------------------------------------------------------- capture

CREDITOR_LONG = {"name": "California Electric Company of the West Coast",
                 "address": {"line1": "500 Energy Ave", "city": "San Francisco Bay Area",
                             "country": "US"}, "MCC": "4900"}
# Printable ASCII only: the contract refuses anything else in a name or a city.
CREDITOR_PUNCTUATION = {"name": "O'Brien & Sons, Inc. #1", "address": {
    "line1": "1 Rue A", "city": "St. John's", "country": "CA"}, "MCC": "5812"}
EMV_VARIANTS = [
    ("default", {}),
    ("long-name-and-city", {"creditor": CREDITOR_LONG}),
    ("usd-fednow", {"currency": "USD", "networks": {"fednow": samples.FEDNOW}, "amount": 22599}),
    ("editable-amount", {"editable": {"range": {"min": 500, "max": 2000}}}),
    ("twelve-digit-amount", {"amount": 123456789012}),
    ("punctuation-and-another-country", {"creditor": CREDITOR_PUNCTUATION}),
]


def wait_for_health(base_url, seconds):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        try:
            with urllib.request.urlopen(base_url + "/actuator/health", timeout=5) as response:
                if response.status == 200:
                    return
        except Exception:                                           # noqa: BLE001 - not up yet
            pass
        time.sleep(2)
    raise SystemExit(f"{base_url} did not come UP within {seconds} s")


def capture():
    identity = os.environ["GOLDEN_IDENTITY"]
    host = os.environ["GOLDEN_HOST"]
    out = os.environ.get("GOLDEN_OUT", "/out")
    base_url = os.environ.get("X9_BASE_URL", "http://127.0.0.1:8080").rstrip("/")
    wait_for_health(base_url, int(os.environ.get("X9_WAIT_SECONDS", "240")))

    api = X9Api(base_url)
    clock = Clock(os.environ.get("X9_CLOCK_URL", "http://127.0.0.1:8099"))
    clock.move_to(EPOCH)
    me = keystore_identity(os.environ["GOLDEN_KEYSTORE"])
    crl = jws.CrlServer(jws.CertificateAuthority.bundled(), port=CRL_PORT)
    assert crl.port == CRL_PORT, f"port {CRL_PORT} is taken; the bundled leaves name it"

    part = Part(identity, host)
    capture_emv(api, part)
    capture_jws(api, clock, me, part)

    os.makedirs(os.path.join(out, "parts"), exist_ok=True)
    with open(os.path.join(out, "parts", f"{identity}.json"), "w") as f:
        json.dump({"identity": identity, "host": host, "jws": part.jws, "emv": part.emv}, f)
    print(f"{identity}: {len(part.jws)} JWS files, "
          f"{sum(len(e['cases']) for e in part.jws)} cases, {len(part.emv)} EMV strings", flush=True)
    assemble(out)


def capture_emv(api, part):
    """One QR Code per variant: the string Java encoded, and its decoding by ``x9client.emv``."""
    if len(part.host) == 37:
        print(f"{part.identity}: host {part.host} is 37 characters, the longest that fits")
    for name, kwargs in EMV_VARIANTS:
        request = samples.payment_request(**copy.deepcopy(kwargs))
        response = api.post("/api/v1/payment-request", body=request)
        assert response.status == 201, f"{name}: {response.status} {response.body}"
        code = response.body["qrCode"]
        location = response.body["location"]
        assert emv.crc_is_valid(code), code
        assert base64.b64decode(response.body["qrCodeB64"]).decode() == code
        tag, template = emv.template_with(code, "org.x9")
        url = template["01"]
        assert tag == "26" and url == f"{part.host}/pub/api/v1/loc/{location['id']}", (tag, url)
        part.emv.append({
            "id": f"{part.identity}-{name}", "host": part.host, "locationId": location["id"],
            "locationEndpoint": location["endpoint"], "qrCode": code, "length": len(code),
            "crc": code[-4:], "crcInput": "everything before the last four characters",
            "fields": [{"tag": t, "length": len(v), "value": v} for t, v in emv.parse(code)],
            "template26": [{"tag": t, "length": len(v), "value": v}
                           for t, v in emv.parse(dict(emv.parse(code))["26"])],
            "url": url, "request": request})
    part.first_emv = part.emv[0]


def capture_jws(api, clock, me, part):
    identity = part.identity
    trusted = identity in TRUSTED

    def java_sign(payload, ttl_seconds=300):
        response = api.post(GENERATE, body=payload, headers={
            "Correlation-Id": str(uuid.uuid4()), "TTL-Seconds": str(ttl_seconds)})
        assert response.status == 200 and response.text.count(".") == 2, response
        return response.text.strip()

    def answer_to(token, status):
        response = api.post_jose(NOTIFY, token)
        assert response.status == status and response.content_type == "application/jose", \
            f"expected a signed {status}: {response.status} {response.text[:300]}"
        return response.text.strip()

    def iat_of(token):
        return jws.parse(token)[0]["iat"]

    content = {"qrCodeContent": base64.b64encode(part.first_emv["qrCode"].encode()).decode()}
    chain_problem = None if trusted else "UNTRUSTED_CHAIN"
    judged = VERIFIED if trusted else refused(chain_problem)

    # ---- from Java, untouched
    request = java_sign(content)
    part.reference_ms = iat_of(request)
    part.add("payload-request", request, kind="payload-request", produced_by="java",
             what="the payer's request for a payload: {qrCodeContent}, signed by Java",
             expected=judged)

    def add_answer(name, token, status, what, **kwargs):
        part.reference_ms = iat_of(token)
        part.add(name, token, kind="notification-answer", produced_by="java", what=what,
                 http_status=status, expected=judged, **kwargs)

    add_answer("answer-401-not-a-jws", answer_to("not-a-jws", 401), 401,
               "Java's signed refusal of a body that is not a JWS: 401, signatureProblem NOT_JWS "
               "in the body, a fresh correlationId")
    if not trusted:
        return

    response = api.post_jose(f"/pub/api/v1/loc/{part.first_emv['locationId']}", request)
    assert response.status == 200 and response.content_type == "application/jose", response
    part.reference_ms = iat_of(response.text)
    part.add("payload-response", response.text.strip(), kind="payload-response",
             produced_by="java", what="Java's signed payload for that request (statusCode 200 in "
             "the header and in crit)", expected=VERIFIED)

    payee = peer_module.TestPayee(me, now_ms=clock.now_millis)
    try:
        notes = {}
        for phase, path, tx in (("pre-commit", PRE, None), ("post-commit", POST, samples.TX_HASH)):
            payee.reset()
            qr = api.create_qr()
            sent = api.post(path, body=peer_module.outbound(
                "post-payment" if tx else "pre-payment", endpoint=payee.endpoint, qr_id=qr,
                transaction_id=tx))
            assert sent.status == 200 and len(payee.received) == 1, sent
            notes[phase] = (qr, payee.received[0].raw)
    finally:
        payee.close()

    qr_pre, note_pre = notes["pre-commit"]
    iat, ttl = iat_of(note_pre), jws.parse(note_pre)[0]["ttl"]
    part.reference_ms = iat
    part.add("notification-pre-commit", note_pre, kind="notification", produced_by="java",
             what="the pre-commit notification Java sends as a payer", expected=VERIFIED, cases={
                 "30s-before-iat-plus-ttl": (iat + ttl - 30_000, VERIFIED),
                 "at-iat-plus-ttl": (iat + ttl, refused("EXPIRED")),
                 "a-day-later": (iat + 86_400_000, refused("EXPIRED")),
                 "30s-before-iat-within-skew": (iat - 30_000, VERIFIED),
                 "5min-before-iat": (iat - 300_000, refused("FUTURE_IAT")),
             } if identity == "rsa" else None)
    part.reference_ms = iat_of(notes["post-commit"][1])
    part.add("notification-post-commit", notes["post-commit"][1], kind="notification",
             produced_by="java", what="the post-commit notification Java sends as a payer, with "
             "its transactionId", expected=VERIFIED)

    yes = answer_to(note_pre, 200)
    assert jws.parse(yes)[1] == {"statusCode": 200}, jws.parse(yes)
    iat = iat_of(yes)
    add_answer("answer-200-yes", yes, 200,
               "Java's signed yes to that pre-commit: the body is exactly {statusCode: 200}, the "
               "correlationId is the question's", cases={
                   "at-iat-plus-ttl": (iat + TTL_MS, refused("EXPIRED")),
                   "5min-before-iat": (iat - 300_000, refused("FUTURE_IAT")),
               } if identity == "rsa" else None)
    add_answer("answer-200-yes-post-commit", answer_to(notes["post-commit"][1], 200), 200,
               "Java's signed yes to that post-commit")
    add_answer("answer-400-wrong-amount", answer_to(java_sign(samples.notification(
        api.create_qr(), amount=samples.BILL + 1, payer_info="golden@example.com")), 400), 400,
        "Java's signed refusal of a wrong amount: 400, the error fields and violations in the "
        "signed body")
    if identity == "ec":
        derive_ec(part, me, note_pre)
    if identity != "rsa":
        return

    add_answer("answer-404-unknown-qr", answer_to(java_sign(samples.notification(
        "0" * 32, payer_info="golden@example.com")), 404), 404,
        "Java's signed refusal of an unknown QR Code: 404")
    add_answer("answer-409-another-holder", answer_to(java_sign(samples.notification(
        qr_pre, payer_info="another-payer@example.com")), 409), 409,
        "Java's signed refusal of a second payer on a reserved QR Code: 409")
    header_b64, payload_b64, signature_b64 = note_pre.split(".")
    altered_payload = jws.b64u_json({**jws.parse(note_pre)[1], "x9golden": "altered"})
    add_answer("answer-401-bad-signature",
               answer_to(f"{header_b64}.{altered_payload}.{signature_b64}", 401), 401,
               "Java's signed refusal of a question altered after signing: 401, signatureProblem "
               "BAD_SIGNATURE in the body, a fresh correlationId")

    short = java_sign(content, ttl_seconds=30)
    part.reference_ms = iat_of(short)
    part.add("payload-request-ttl-30s", short, kind="payload-request", produced_by="java",
             what="as payload-request, with a 30 s ttl", expected=VERIFIED,
             cases={"at-iat-plus-ttl": (iat_of(short) + 30_000, refused("EXPIRED"))})

    derive(part, me, note_pre, yes)


def derive(part, me, note, yes):
    """Everything Java never emits, made from Java's own notification (and its yes)."""
    header, _ = jws.parse(note)
    header_b64, payload_b64, signature_b64 = note.split(".")
    iat, ttl = header["iat"], header["ttl"]
    part.reference_ms = iat
    base = "notification-pre-commit"

    def tampered(name, token, what, expected, **kwargs):
        part.add(name, token, kind="notification", produced_by="java+tamper", what=what,
                 expected=expected, derived_from=base, **kwargs)

    def resigned(name, change, what, expected, *, key=None, sign_alg=None, cases=None):
        changed = change(copy.deepcopy(header))
        token = resign(changed, payload_b64, key or me.key, sign_alg=sign_alg)
        part.add(name, token, kind="notification",
                 produced_by="java+resign", what=what, expected=expected, derived_from=base,
                 cases=cases)

    def without(*names):
        def change(h):
            for name in names:
                h.pop(name, None)
            return h
        return change

    def having(**values):
        def change(h):
            h.update(values)
            return h
        return change

    def crit(names, **extra):
        def change(h):
            h["crit"] = names
            h.update(extra)
            return h
        return change

    # ---- tampered after signing: no key involved
    altered = jws.b64u_json({**jws.parse(note)[1], "x9golden": "altered"})
    tampered("altered-payload", f"{header_b64}.{altered}.{signature_b64}",
             "one claim added to the payload after signing", refused("BAD_SIGNATURE"), cases={
                 # the time window is judged before the signature
                 "at-iat-plus-ttl": (iat + ttl, refused("EXPIRED"))})
    flipped = bytearray(jws.b64u_decode(signature_b64))
    flipped[-1] ^= 0x01
    tampered("altered-signature", f"{header_b64}.{payload_b64}.{jws.b64u(bytes(flipped))}",
             "the last bit of the signature flipped", refused("BAD_SIGNATURE"))
    tampered("signature-of-another-token", f"{header_b64}.{payload_b64}.{yes.split('.')[2]}",
             "the signature replaced by the signature of Java's yes", refused("BAD_SIGNATURE"))
    tampered("empty-signature", f"{header_b64}.{payload_b64}.",
             "the signature removed, its dot kept, the header still naming PS512",
             refused("NOT_JWS"))
    tampered("two-parts", f"{header_b64}.{payload_b64}",
             "the signature and its dot removed", refused("NOT_JWS"))

    # ---- constructed: not a JWS
    part.add("alg-none", f"{jws.b64u_json({'alg': 'none'})}.{payload_b64}.", kind="notification",
             produced_by="constructed", what="an unsecured JOSE object over Java's payload",
             expected=refused("ALG_NONE"))
    part.add("not-a-jws", "not-a-jws", kind="not-a-jws", produced_by="constructed",
             what="plain text", expected=refused("NOT_JWS"))

    # ---- Java's header, one thing changed, signed again with the same bundled test key
    resigned("resigned-control", lambda h: h,
             "nothing changed: proves a re-signed token is accepted", VERIFIED)

    resigned("crit-missing", without("crit"), "no crit", refused("BAD_HEADER"), cases={
        # crit is judged before the time window
        "at-iat-plus-ttl": (iat + ttl, refused("BAD_HEADER"))})
    resigned("crit-empty", crit([]), "crit is an empty list", refused("BAD_HEADER"))
    resigned("crit-without-ttl", crit(["correlationId", "iat"]),
             "crit does not name ttl", refused("BAD_HEADER"))
    resigned("crit-unknown", crit(["correlationId", "iat", "ttl", "x9golden"], x9golden=True),
             "crit names a parameter nobody understands", refused("BAD_HEADER"))
    resigned("crit-statuscode-without-the-header", crit(["correlationId", "iat", "ttl",
                                                         "statusCode"]),
             "crit names statusCode, the header has none", refused("BAD_HEADER"))
    resigned("crit-in-another-order", crit(["ttl", "iat", "correlationId"]),
             "crit names the same three parameters in another order", VERIFIED)

    # typ: Java always writes JOSE and reads nothing from it
    resigned("typ-absent", without("typ"), "no typ", VERIFIED)
    for typ in ("JWT", "payreq+jws", "payresp+jws", "paynote+jws", "qr+jws"):
        resigned(f"typ-{typ.replace('+', '-')}", having(typ=typ),
                 f"typ is {typ} instead of JOSE", VERIFIED)

    # the certificate
    resigned("x5c-missing", without("x5c"), "no x5c, x5u or jku: nothing to verify with",
             refused("BAD_HEADER"))
    resigned("x5t-missing", without("x5t#S256"), "no x5t#S256", refused("BAD_HEADER"))
    resigned("x5t-of-another-certificate",
             having(**{"x5t#S256": jws.b64u(hashlib.sha256(b"another certificate").digest())}),
             "x5t#S256 names no certificate in x5c", refused("UNTRUSTED_CHAIN"))
    resigned("x5c-leaf-only", having(x5c=header["x5c"][:1]),
             "x5c carries the leaf without its issuer, which the truststore holds", VERIFIED)
    resigned("x5c-root-first", having(x5c=list(reversed(header["x5c"]))),
             "x5c in the wrong order: the root, then the leaf", refused("UNTRUSTED_CHAIN"))
    resigned("x5c-not-a-certificate", having(x5c=["bm90IGEgY2VydGlmaWNhdGU="]),
             "x5c holds base64 that is not a certificate", refused("BAD_HEADER"))

    # correlationId, iat, ttl
    resigned("correlationid-missing", without("correlationId"),
             "no correlationId (crit still names it)", refused("BAD_HEADER"))
    resigned("correlationid-not-a-uuid", having(correlationId="not-a-uuid"),
             "correlationId is not a UUID", refused("BAD_HEADER"))
    resigned("iat-missing", without("iat"), "no iat (crit still names it)", refused("BAD_HEADER"))
    resigned("iat-a-string", having(iat=str(iat)), "iat is a JSON string", refused("BAD_HEADER"))
    resigned("iat-a-decimal", having(iat=float(iat) + 0.5), "iat is a JSON number with a fraction",
             refused("BAD_HEADER"))
    resigned("iat-zero", having(iat=0), "iat is 0", refused("BAD_HEADER"))
    resigned("iat-in-seconds", having(iat=iat // 1000),
             "iat in seconds, not milliseconds: it reads as January 1970", refused("EXPIRED"))
    resigned("ttl-missing", without("ttl"), "no ttl (crit still names it)", refused("BAD_HEADER"))
    resigned("ttl-zero", having(ttl=0), "ttl is 0", refused("BAD_HEADER"))
    resigned("ttl-negative", having(ttl=-1), "ttl is negative", refused("BAD_HEADER"))
    resigned("ttl-a-string", having(ttl=str(ttl)), "ttl is a JSON string", refused("BAD_HEADER"))

    # the algorithm and the key
    for alg in ("RS256", "RS384", "RS512", "PS256", "PS384"):
        resigned(f"alg-{alg}", having(alg=alg), f"signed {alg} with the same RSA key", VERIFIED)
    resigned("alg-says-PS256-signed-PS512", having(alg="PS256"),
             "the header says PS256, the signature is PS512", refused("BAD_SIGNATURE"),
             sign_alg="PS512")
    resigned("alg-ES256-on-an-rsa-certificate", having(alg="ES256"),
             "the header says ES256, the certificate holds an RSA key", refused("UNVERIFIABLE"),
             sign_alg="PS512")
    resigned("alg-HS256", having(alg="HS256"), "the header says HS256 (a MAC, not a signature)",
             refused("BAD_HEADER"), sign_alg="PS512")
    resigned("signed-by-another-key", lambda h: h,
             "the certificate is the signer's, the signature another RSA key's",
             refused("BAD_SIGNATURE"), key=jws.new_key("rsa"))

    # ---- the same for a few of Java's answers
    answer_header, _ = jws.parse(yes)
    a_header_b64, a_payload_b64, a_signature_b64 = yes.split(".")
    part.reference_ms = answer_header["iat"]

    def answer(name, token, produced_by, what, expected):
        part.add(name, token, kind="notification-answer", produced_by=produced_by, what=what,
                 expected=expected, derived_from="answer-200-yes", http_status=200)

    answer("answer-altered-payload",
           f"{a_header_b64}.{jws.b64u_json({'statusCode': 201})}.{a_signature_b64}", "java+tamper",
           "Java's yes with the signed statusCode changed after signing", refused("BAD_SIGNATURE"))
    answer("answer-resigned-control", resign(answer_header, a_payload_b64, me.key), "java+resign",
           "Java's yes, nothing changed, signed again", VERIFIED)
    no_status = {k: v for k, v in answer_header.items() if k != "statusCode"}
    answer("answer-statuscode-header-missing", resign(no_status, a_payload_b64, me.key),
           "java+resign", "Java's yes without the statusCode header its crit names",
           refused("BAD_HEADER"))
    not_http = {**answer_header, "statusCode": 99}
    answer("answer-statuscode-not-an-http-status", resign(not_http, a_payload_b64, me.key),
           "java+resign", "Java's yes with statusCode 99 in the header", refused("BAD_HEADER"))


def derive_ec(part, me, note):
    header, _ = jws.parse(note)
    header_b64, payload_b64, signature_b64 = note.split(".")
    part.reference_ms = header["iat"]
    base = "notification-pre-commit"

    def add(name, token, produced_by, what, expected):
        part.add(name, token, kind="notification", produced_by=produced_by, what=what,
                 expected=expected, derived_from=base)

    altered = jws.b64u_json({**jws.parse(note)[1], "x9golden": "altered"})
    add("altered-payload", f"{header_b64}.{altered}.{signature_b64}", "java+tamper",
        "one claim added to the payload after signing", refused("BAD_SIGNATURE"))
    add("resigned-control", resign(header, payload_b64, me.key), "java+resign",
        "nothing changed: proves a re-signed ES256 token is accepted", VERIFIED)
    add("signature-in-der", resign(header, payload_b64, me.key, der=True), "java+resign",
        "the ECDSA signature in ASN.1 DER instead of the 64-byte R||S that JWS requires",
        refused("BAD_SIGNATURE"))
    add("alg-ES384-on-a-p256-key", resign({**header, "alg": "ES384"}, payload_b64, me.key),
        "java+resign", "ES384 with a P-256 key: the ECDSA mathematics verify, and it is refused "
        "all the same, because RFC 7518 binds ES384 to P-384", refused("BAD_SIGNATURE"))
    add("signed-by-another-key", resign(header, payload_b64, jws.new_key("ec")), "java+resign",
        "the certificate is the signer's, the signature another P-256 key's",
        refused("BAD_SIGNATURE"))


# ------------------------------------------------------------------------------- assemble

def _pem(certificate):
    return certificate.public_bytes(serialization.Encoding.PEM)


def _iso(moment):
    return moment.astimezone(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def _crl_url(certificate):
    try:
        points = certificate.extensions.get_extension_for_class(x509.CRLDistributionPoints).value
    except x509.ExtensionNotFound:
        return None
    return points[0].full_name[0].value


def assemble(out):
    """Merge the parts present into ``manifest.json``, ``jws/`` and ``pki/``."""
    cert_dir = os.environ.get("X9_TEST_CA_DIR") or jws.DEFAULT_CERT_DIR
    parts = {}
    for identity in ORDER:
        path = os.path.join(out, "parts", f"{identity}.json")
        if os.path.exists(path):
            with open(path) as f:
                parts[identity] = json.load(f)

    golden = os.path.join(out, "golden")
    for folder in ("jws", "pki"):
        os.makedirs(os.path.join(golden, folder), exist_ok=True)

    def write(relative, data):
        with open(os.path.join(golden, relative), "wb") as f:
            f.write(data)
        return hashlib.sha256(data).hexdigest()

    # ---- pki: what an offline verifier needs. Public material only; no private key leaves the
    # bundled test keystores.
    ca = jws.CertificateAuthority.bundled(cert_dir).certificate
    expiries = [ca.not_valid_after_utc]
    anchors = [{"file": "pki/x9-test-ca.pem", "sha256": write("pki/x9-test-ca.pem", _pem(ca)),
                "subject": ca.subject.rfc4514_string(), "notBefore": _iso(ca.not_valid_before_utc),
                "notAfter": _iso(ca.not_valid_after_utc)}]
    crls = []
    for name in ("test-ca.crl", "test-ca-revoked.crl"):
        with open(os.path.join(cert_dir, name), "rb") as f:
            data = f.read()
        crl = x509.load_der_x509_crl(data)
        expiries.append(crl.next_update_utc)
        crls.append({"url": f"http://127.0.0.1:{CRL_PORT}/{name}", "file": f"pki/{name}",
                     "encoding": "DER", "sha256": write(f"pki/{name}", data),
                     "issuer": crl.issuer.rfc4514_string(),
                     "thisUpdate": _iso(crl.last_update_utc),
                     "nextUpdate": _iso(crl.next_update_utc),
                     "revokedSerials": [format(r.serial_number, "X") for r in crl]})
    revoked = {serial for entry in crls for serial in entry["revokedSerials"]}

    signers = {}
    keystores = {"rsa": "x9-test-payer", "ec": "x9-test-payer-ec",
                 "revoked": "x9-test-payer-revoked", "untrusted": "x9-test-untrusted-payer"}
    for identity in ORDER:
        who = keystore_identity(keystores[identity])
        certificate = who.certificate
        expiries.append(certificate.not_valid_after_utc)
        serial = format(certificate.serial_number, "X")
        signers[identity] = {
            "keystore": f"{keystores[identity]}.p12",
            "file": f"pki/signer-{identity}.pem",
            "sha256": write(f"pki/signer-{identity}.pem", b"".join(_pem(c) for c in who.chain)),
            "subject": who.subject, "issuer": certificate.issuer.rfc4514_string(),
            "serial": serial, "alg": jws.alg_for(who.key),
            "x5tS256": jws.thumbprint(certificate),
            "notBefore": _iso(certificate.not_valid_before_utc),
            "notAfter": _iso(certificate.not_valid_after_utc),
            "crlDistributionPoint": _crl_url(certificate),
            "issuedByTheTrustAnchor": certificate.issuer == ca.subject,
            "revoked": serial in revoked,
            "host": parts.get(identity, {}).get("host")}

    # ---- jws
    files = []
    for identity, part in parts.items():
        for entry in part["jws"]:
            token = entry.pop("token")
            relative = f"jws/{entry['id']}.jws"
            entry = {"id": entry["id"], "file": relative,
                     "sha256": write(relative, token.encode()), **{
                         k: v for k, v in entry.items() if k != "id"}}
            try:
                header, _ = jws.parse(token)
                for name in ("alg", "typ", "correlationId", "iat", "ttl", "statusCode"):
                    if name in header:
                        entry.setdefault("header", {})[name] = header[name]
            except ValueError:
                pass
            files.append(entry)

    manifest = {
        "about": "Golden JWS and EMV sets exported from the Java build (Story 4.7, ADR-0027). "
                 "See README.md. Regenerate with export.sh; never edit by hand.",
        "generatedAt": _iso(datetime.datetime.now(datetime.timezone.utc)),
        "generatedFrom": {"image": os.environ.get("GOLDEN_IMAGE", ""),
                          "revision": os.environ.get("GOLDEN_REVISION", "")},
        "epoch": EPOCH,
        "regenerateBefore": _iso(min(expiries)),
        "verifier": {
            "clockSkewMillis": SKEW_MS,
            "issuerNameMustContain": ISSUER_NAME,
            "rule": "FUTURE_IAT when iat > now + clockSkewMillis; EXPIRED when now >= iat + ttl; "
                    "both judged after crit and correlationId, before the certificate and the "
                    "signature",
            "supportedCritical": ["correlationId", "iat", "ttl", "statusCode"],
        },
        "kinds": {
            "payload-request": {
                "standardTyp": "payreq+jws", "javaTyp": "JOSE",
                "what": "POST /pub/api/v1/loc/{id}: the payer asks for the payload"},
            "payload-response": {
                "standardTyp": "payresp+jws", "javaTyp": "JOSE",
                "what": "the payee's signed payload; statusCode in the header and in crit"},
            "notification": {
                "standardTyp": "paynote+jws", "javaTyp": "JOSE",
                "what": "POST /pub/api/v1/payment-notification: the payer's pre- or post-commit"},
            "notification-answer": {
                "standardTyp": None, "javaTyp": "JOSE",
                "what": "the payee's signed answer to a notification (INTERPRETATION I-12); "
                        "statusCode in the header, in crit and in the body; httpStatus is the "
                        "HTTP status it came with"},
            "not-a-jws": {"standardTyp": None, "javaTyp": None, "what": "not a JOSE object"},
            "note": "Java writes typ JOSE on everything it signs and reads nothing from typ; it "
                    "signs no QR content, so qr+jws appears only as a re-typed notification.",
        },
        "pki": {"trustAnchors": anchors, "crls": crls, "signers": signers},
        "jws": files,
        "emv": [entry for part in parts.values() for entry in part["emv"]],
    }
    write("manifest.json", (json.dumps(manifest, indent=2, ensure_ascii=False) + "\n").encode())
    print(f"assembled {len(files)} JWS files, {sum(len(e['cases']) for e in files)} cases, "
          f"{len(manifest['emv'])} EMV strings from {list(parts)}", flush=True)


if __name__ == "__main__":
    command = sys.argv[1] if len(sys.argv) > 1 else ""
    if command == "capture":
        capture()
    elif command == "assemble":
        assemble(os.environ.get("GOLDEN_OUT", "/out"))
    else:
        raise SystemExit(__doc__)

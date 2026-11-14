# Copyright © 2026 Matera Systems, Inc.
# Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
"""The golden JWS and EMV sets are true today (Story 4.7, ADR-0027).

``others/blackbox/golden`` holds what the Java build signed and encoded, exported by
``golden/export.sh``, with a manifest saying what each fixture is, at what instant it is judged and
what the verdict is. The Go build's ``internal/jws`` and ``internal/emv`` will be checked against
that manifest before any endpoint exists, so the manifest has to be right. Nothing in it is taken on
trust:

- offline, with no deployment: the set is whole and unedited, covers what the story names, carries
  no private key, can be judged with the PKI shipped beside it, and a second implementation
  (``x9client.jws``, ``x9client.emv``) agrees with it;
- against the running Java build: every JWS case is fed to Java with the clock moved to the case's
  instant, and Java gives the manifest's verdict; every EMV string is read by Java and, where the
  host is this deployment's, encoded again to the same characters.

The fixtures were signed on 2026-10-01 by the test clock, so Java judges them on a clock moved
BACK: the set does not go stale as real time passes. What does expire is the bundled test PKI
(2046); ``regenerateBefore`` in the manifest is checked here.
"""
import base64
import datetime
import hashlib
import json
import os

import pytest

from x9client import emv, jws
from x9client import peer as peer_module
from x9client.api import jws_body
from x9client.checks import assert_refused
from x9client.clock import parse_instant

GOLDEN = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "golden")
with open(os.path.join(GOLDEN, "manifest.json")) as _f:
    MANIFEST = json.load(_f)

FILES = MANIFEST["jws"]
CASES = [(entry, case) for entry in FILES for case in entry["cases"]]
CASE_IDS = [f"{entry['id']}@{case['name']}" for entry, case in CASES]
EMV = MANIFEST["emv"]
SIGNERS = MANIFEST["pki"]["signers"]

NOTIFY = "/pub/api/v1/payment-notification"
PRE = "/api/v1/payment-notification/pre-payment"
CRL_PORT = 18081
VERIFIED = {"verdict": "VERIFIED"}


def read(relative, mode="rb"):
    with open(os.path.join(GOLDEN, relative), mode) as f:
        return f.read()


def token_of(entry):
    return read(entry["file"]).decode()


def header_of(entry):
    try:
        return jws.parse(token_of(entry))[0]
    except ValueError:
        return {}


def problem_of(case):
    return case["expected"].get("signatureProblem")


def cases_where(predicate):
    return [(entry, case) for entry, case in CASES if predicate(entry, case)]


# ====================================================================== offline: the set itself

def test_the_golden_set_is_whole_and_unedited():
    """X9-SIG-470 — S4.7-AC3: every file the manifest names is there with the manifest's SHA-256,
    and nothing else is.

    Source: Mechanism. The export writes the set as a whole (``golden/export.sh``).
    Why: A token edited by hand, or left behind by an earlier export, is a fixture whose verdict
      nobody observed. The Go build would then be checked against something Java never said.
    """
    listed = {}
    for entry in FILES:
        listed[entry["file"]] = entry["sha256"]
    pki = MANIFEST["pki"]
    for entry in pki["trustAnchors"] + pki["crls"] + list(pki["signers"].values()):
        listed[entry["file"]] = entry["sha256"]

    assert len({entry["id"] for entry in FILES}) == len(FILES), "fixture ids are unique"
    assert len(set(CASE_IDS)) == len(CASE_IDS), "case ids are unique"
    for relative, sha256 in listed.items():
        assert hashlib.sha256(read(relative)).hexdigest() == sha256, \
            f"{relative} is not what the export wrote; run golden/export.sh, never edit"

    on_disk = {f"{folder}/{name}" for folder in ("jws", "pki")
               for name in os.listdir(os.path.join(GOLDEN, folder))}
    assert on_disk == set(listed), f"files the manifest does not name: {on_disk ^ set(listed)}"


def test_the_golden_jws_set_covers_what_the_story_names():
    """X9-SIG-471 — S4.7-AC1: valid, tampered, expired, missing and unknown ``crit``, wrong
    ``typ``, revoked, untrusted, EC keys, and Java's signed answers (yes and refusal); and every
    kind of message the service signs.

    Source: Ours. Story 4.7; ADR-0027 ("a golden corpus produced by the Java build must give
      identical accept/reject results").
    Why: An export that silently stopped producing one category would still pass every other
      test here, and the Go verifier would ship without ever having seen, say, a revoked signer.
    """
    def some(description, predicate):
        found = cases_where(predicate)
        assert found, f"the golden set has no {description}"
        return found

    java = lambda e: e["producedBy"] == "java"                               # noqa: E731
    ok = lambda c: c["expected"] == VERIFIED                                 # noqa: E731

    for kind in ("payload-request", "payload-response", "notification", "notification-answer"):
        some(f"valid {kind} signed by Java", lambda e, c: java(e) and e["kind"] == kind and ok(c))
    some("tampered token", lambda e, c: e["producedBy"] == "java+tamper"
         and problem_of(c) == "BAD_SIGNATURE")
    some("expired token signed by Java", lambda e, c: java(e) and problem_of(c) == "EXPIRED")
    some("token dated in the future", lambda e, c: java(e) and problem_of(c) == "FUTURE_IAT")
    some("token without crit", lambda e, c: e["id"].endswith("crit-missing")
         and "crit" not in header_of(e) and problem_of(c) == "BAD_HEADER")
    some("token with an unknown crit", lambda e, c: e["id"].endswith("crit-unknown")
         and "x9golden" in header_of(e).get("crit", []) and problem_of(c) == "BAD_HEADER")
    some("token with another typ", lambda e, c: header_of(e).get("typ") not in (None, "JOSE"))
    for typ in ("payreq+jws", "payresp+jws", "paynote+jws", "qr+jws"):
        some(f"token typed {typ}", lambda e, c: header_of(e).get("typ") == typ)
    some("revoked signer, signed by Java", lambda e, c: java(e) and e["signer"] == "revoked"
         and problem_of(c) == "UNTRUSTED_CHAIN")
    some("untrusted signer, signed by Java", lambda e, c: java(e) and e["signer"] == "untrusted"
         and problem_of(c) == "UNTRUSTED_CHAIN")
    some("EC token signed by Java", lambda e, c: java(e) and e["signer"] == "ec"
         and header_of(e).get("alg") == "ES256" and ok(c))
    some("signed yes from Java", lambda e, c: java(e) and e["kind"] == "notification-answer"
         and e["httpStatus"] == 200 and ok(c))
    for status in (400, 401, 404, 409):
        some(f"signed {status} from Java", lambda e, c: java(e) and e.get("httpStatus") == status
             and e["kind"] == "notification-answer" and ok(c))

    assert SIGNERS["revoked"]["revoked"] and SIGNERS["revoked"]["issuedByTheTrustAnchor"]
    assert not SIGNERS["untrusted"]["issuedByTheTrustAnchor"]
    for entry in FILES:
        if entry["producedBy"] == "java":
            assert header_of(entry)["typ"] == "JOSE", "Java writes typ JOSE on everything it signs"
            assert "x5c" in header_of(entry), "the export has Java publish its chain in x5c"


def test_the_golden_set_carries_test_ca_material_only_and_no_private_key():
    """X9-SIG-472 — S4.7-AC3: every certificate in the set is one of the repository's bundled test
    certificates, and no private key is in it.

    Source: Ours. Story 4.7 ("carry test-CA keys only"); the repository is public.
    Why: The export signs with keystores. A real key, or a real certificate, committed under
      ``golden/`` would be published. The signing keys stay where they already were: the bundled,
      NON-PRODUCTION ``x9-test-*.p12`` under the Java test resources.
    """
    from cryptography import x509

    known = {signer["x5tS256"] for signer in SIGNERS.values()}
    for anchor in MANIFEST["pki"]["trustAnchors"]:
        known.add(jws.thumbprint(x509.load_pem_x509_certificate(read(anchor["file"]))))
    for signer in SIGNERS.values():
        for certificate in x509.load_pem_x509_certificates(read(signer["file"])):
            known.add(jws.thumbprint(certificate))

    for entry in FILES:
        for encoded in header_of(entry).get("x5c", []):
            try:
                certificate = x509.load_der_x509_certificate(base64.b64decode(encoded))
            except ValueError:
                assert entry["id"].endswith("x5c-not-a-certificate"), entry["id"]
                continue
            assert jws.thumbprint(certificate) in known, \
                f"{entry['id']} carries a certificate that is not bundled test material"

    for folder, _, names in os.walk(GOLDEN):
        for name in names:
            assert not name.endswith((".p12", ".jks", ".key", ".pfx")), name
            with open(os.path.join(folder, name), "rb") as f:
                assert b"PRIVATE KEY" not in f.read(), f"{name} holds a private key"


def test_the_shipped_pki_can_judge_every_case_at_its_instant():
    """X9-SIG-473 — the trust anchor, the signers' chains and the CRLs shipped in ``golden/pki``
    are valid at every case's ``verifyAt``, the CRLs are the test CA's and revoke exactly the
    revoked signer, and the set does not need regenerating yet.

    Source: Mechanism. Java checks certificates and CRLs on the real clock and the JWS window on
      the business clock; an offline verifier is given one instant, ``verifyAt``, for both.
    Why: A fixture judged outside its certificate's validity, or with a CRL that had lapsed,
      would be refused by the Go build for a reason the manifest does not name. And on
      2046-09-22 the bundled test PKI expires: from then on Java refuses every fixture, and this
      fails first, saying so.
    """
    from cryptography import x509

    pki = MANIFEST["pki"]
    anchor = x509.load_pem_x509_certificate(read(pki["trustAnchors"][0]["file"]))
    instants = [parse_instant(case["verifyAt"]) for _, case in CASES]
    earliest, latest = min(instants), max(instants)
    not_after = [anchor.not_valid_after_utc]

    for identity, signer in SIGNERS.items():
        leaf = x509.load_pem_x509_certificates(read(signer["file"]))[0]
        assert jws.thumbprint(leaf) == signer["x5tS256"]
        assert leaf.not_valid_before_utc <= earliest and latest <= leaf.not_valid_after_utc, identity
        assert (leaf.issuer == anchor.subject) is signer["issuedByTheTrustAnchor"], identity
        not_after.append(leaf.not_valid_after_utc)

    urls = set()
    for entry in pki["crls"]:
        crl = x509.load_der_x509_crl(read(entry["file"]))
        assert crl.is_signature_valid(anchor.public_key()), f"{entry['file']} is the test CA's"
        assert crl.last_update_utc <= earliest and latest <= crl.next_update_utc, entry["file"]
        assert [format(r.serial_number, "X") for r in crl] == entry["revokedSerials"]
        not_after.append(crl.next_update_utc)
        urls.add(entry["url"])
    for identity, signer in SIGNERS.items():
        assert signer["crlDistributionPoint"] in urls, f"no CRL shipped for {identity}"
        listed = any(signer["serial"] in entry["revokedSerials"] for entry in pki["crls"]
                     if entry["url"] == signer["crlDistributionPoint"])
        assert listed is signer["revoked"], identity

    regenerate_before = parse_instant(MANIFEST["regenerateBefore"])
    assert regenerate_before == min(not_after).replace(microsecond=0)
    assert datetime.datetime.now(datetime.timezone.utc) < regenerate_before, \
        "the bundled test PKI has expired: regenerate it, then run golden/export.sh"


@pytest.mark.parametrize("entry, case", cases_where(
    lambda e, c: "x5c" in header_of(e) and (c["expected"] == VERIFIED or (
        problem_of(c) == "BAD_SIGNATURE" and c["name"] == "fresh"))),
    ids=lambda value: value.get("id") or value.get("name"))
def test_a_second_implementation_agrees_offline(entry, case):
    """X9-SIG-474 — every case Java accepts verifies with ``x9client.jws`` against the
    certificate in its own ``x5c`` at ``verifyAt``; every case Java calls BAD_SIGNATURE does not.

    Source: Mechanism. RFC 7515 for the signature; the window is this service's rule
      (INTERPRETATION I-13).
    Why: The set is for verifying offline. If only Java could verify what Java signed, the
      fixtures would prove nothing about another implementation. This one shares no code with
      Java.
    """
    from cryptography import x509

    header = header_of(entry)
    certificates = [x509.load_der_x509_certificate(base64.b64decode(c)) for c in header["x5c"]]
    leaf = next(c for c in certificates if jws.thumbprint(c) == header["x5t#S256"])
    now = lambda: case["verifyAtMillis"]                                     # noqa: E731

    if case["expected"] == VERIFIED:
        jws.verify(token_of(entry), leaf, now_ms=now)
    else:
        with pytest.raises((jws.VerificationError, ValueError)) as refusal:
            jws.verify(token_of(entry), leaf, now_ms=now)
        if isinstance(refusal.value, jws.VerificationError):
            assert refusal.value.problem == "BAD_SIGNATURE", refusal.value


@pytest.mark.parametrize("golden", EMV, ids=lambda golden: golden["id"])
def test_a_golden_emv_string_decodes_to_its_manifest_fields(golden):
    """X9-LOC-470 — S4.7-AC2: each string decodes, TLV by TLV, to the fields the manifest lists;
    its CRC16 is the manifest's; tag 26 is ``org.x9`` and the host's location URL, within 99
    characters.

    Source: EMV QRCPS (TLV, CRC16/CCITT-FALSE over everything up to and including ``6304``);
      ANSI X9.150 for the ``org.x9`` template. The decoder here is ``x9client.emv``, not Java's.
    Why: A manifest whose "expected decoding" did not match its own string would make the Go
      decoder fail on a correct implementation, or pass on a wrong one.
    """
    code = golden["qrCode"]
    fields = emv.parse(code)

    assert [{"tag": t, "length": len(v), "value": v} for t, v in fields] == golden["fields"]
    assert len(code) == golden["length"]
    assert emv.crc16(code[:-4]) == golden["crc"] == code[-4:] and emv.crc_is_valid(code)
    raw = dict(fields)["26"]
    assert [{"tag": t, "length": len(v), "value": v} for t, v in emv.parse(raw)] \
        == golden["template26"]
    assert golden["template26"][0] == {"tag": "00", "length": 6, "value": "org.x9"}
    assert golden["url"] == f"{golden['host']}/pub/api/v1/loc/{golden['locationId']}"
    assert golden["template26"][1]["value"] == golden["url"]
    assert len(raw) == 10 + 4 + len(golden["host"]) + 16 + 32 <= 99, raw


def test_the_golden_emv_set_covers_representative_hosts_and_the_longest():
    """X9-LOC-471 — S4.7-AC2: several hosts, several locations each, and one host of 37
    characters whose tag 26 is exactly 99 characters long.

    Source: Ours. EMV QRCPS caps a template at 99 characters; 37 is that arithmetic (X9-LOC-425).
    Why: The 37-character host is the one string where an off-by-one in a TLV length shows. A
      set without it would let the Go encoder be wrong exactly at the limit.
    """
    hosts = {golden["host"] for golden in EMV}
    assert len(hosts) >= 3, hosts
    assert len({golden["locationId"] for golden in EMV}) == len(EMV), "every location is its own"
    longest = [golden for golden in EMV if len(golden["host"]) == 37]
    assert longest, f"no 37-character host among {hosts}"
    for golden in longest:
        assert dict(emv.parse(golden["qrCode"]))["26"] and golden["fields"][2]["length"] == 99


# ====================================================================== against Java, today

@pytest.fixture(scope="module")
def golden_crls(pki):
    """The bundled CRLs at ``127.0.0.1:18081``, the distribution point the golden signers'
    certificates name. The service must reach this process there, as it does in CI, where the
    suite shares its network namespace."""
    if pki["crl"].port == CRL_PORT:
        yield pki["crl"]                            # X9_SUITE_CRL_PORT=18081: already serving
        return
    server = jws.CrlServer(pki["ca"], port=CRL_PORT)
    if server.port != CRL_PORT:
        server.close()
        reason = (f"port {CRL_PORT} is taken, and the golden signers' certificates name it as "
                  f"their CRL distribution point")
        if os.environ.get("X9_REQUIRE_ALL", "") not in ("", "0", "false"):
            pytest.fail(f"{reason} (X9_REQUIRE_ALL is set, so this is a failure, not a skip)")
        pytest.skip(reason)
    yield server
    server.close()


def judged_as_a_question(api, token):
    """Java's verdict on ``token`` sent to the notification endpoint: a signed 401 names the
    ``signatureProblem``; anything else means the signature was accepted (what the message then
    says is not this test's business)."""
    response = api.post_jose(NOTIFY, token)
    assert response.content_type == "application/jose", f"{response.status} {response.text[:200]}"
    if response.status != 401:
        return VERIFIED, response
    body = jws_body(response.text)
    return {"verdict": "REFUSED", "signatureProblem": body.get("signatureProblem")}, response


def judged_as_an_answer(api, peer, entry, token):
    """Java's verdict on ``token`` given by a payee as its answer, with the HTTP status the
    fixture came with, to a notification sent under the answer's own correlationId."""
    peer.answer = lambda question: peer_module.Answer(entry["httpStatus"], token)
    response = api.post(PRE, body=peer_module.outbound(
        "pre-payment", endpoint=peer.endpoint, qr_id=api.create_qr(),
        correlation_id=header_of(entry)["correlationId"]))
    assert response.status == 200, f"the service reports, it does not fail: {response}"
    report = response.body
    if report["signature"] == "VERIFIED":
        assert report["statusCode"] == entry["httpStatus"], report
        assert report.get("signatureProblem") is None, report
        return VERIFIED, response
    return {"verdict": "REFUSED", "signatureProblem": report.get("signatureProblem")}, response


@pytest.mark.serial
@pytest.mark.parametrize("entry, case", CASES, ids=CASE_IDS)
def test_java_gives_each_golden_jws_its_manifest_verdict(api, clock, peer, golden_crls, entry,
                                                         case):
    """X9-SIG-475 — S4.7-AC1, S4.7-AC3: with the clock at the case's ``verifyAt``, the running
    Java build accepts the fixture, or refuses it with the manifest's ``signatureProblem``.

    A notification answer is judged where Java judges answers (a payee's reply to a notification
    it sent, ``signature`` / ``signatureProblem`` in its report); everything else is sent to the
    notification endpoint, whose signed 401 names the problem.

    Source: Mechanism: the manifest records Java's verdicts, code by code (the closed list is
      ``SignatureProblem`` in the OpenAPI). ADR-0027 makes them the Go verifier's acceptance test.
    Why: The Go verifier is written against these files before any endpoint exists. A manifest
      that said BAD_HEADER where Java says UNTRUSTED_CHAIN would be ported faithfully and be
      wrong. This also proves the set is not stale: the fixtures were signed on 2026-10-01 by the
      test clock and are judged on a clock moved back to then.
    """
    clock.move_to(case["verifyAt"])
    token = token_of(entry)

    if entry["kind"] == "notification-answer":
        verdict, response = judged_as_an_answer(api, peer, entry, token)
    else:
        verdict, response = judged_as_a_question(api, token)

    assert verdict == case["expected"], (
        f"{entry['id']} ({entry['what']}) at {case['verifyAt']}: the manifest says "
        f"{case['expected']}, Java says {verdict}: {response.status} {str(response.body)[:300]}. "
        f"If every certificate-bearing case fails, the service cannot fetch "
        f"http://127.0.0.1:{CRL_PORT}/test-ca.crl from this suite")


@pytest.mark.parametrize("golden", EMV, ids=lambda golden: golden["id"])
def test_java_reads_the_location_out_of_each_golden_emv_string(api, golden):
    """X9-LOC-472 — S4.7-AC2: asked for the location a golden string names, Java gets as far as
    looking it up; asked for another location with the same string, it refuses the content.

    Source: Mechanism. The request names a location and carries the EMV string; Java decodes
      tag 26 and checks the two agree before it looks anything up (X9-LOC-020, X9-LOC-301).
    Why: This is Java's own EMV decoder reading the golden strings, the long host included. The
      location belongs to the deployment that was exported, not to this one, so the lookup fails;
      that it is the LOOKUP that fails is the proof that Java read the right location.
    """
    named = api.fetch_payload(golden["locationId"], golden["qrCode"])
    another = api.fetch_payload("F" * 32, golden["qrCode"])

    assert_refused(named, naming="Error processing payment payload retrieve.", status=400)
    assert_refused(another, naming="Invalid JWS signature for payment payload request.", status=400)


def test_java_encodes_the_same_request_to_the_same_characters_today(api):
    """X9-LOC-473 — S4.7-AC2: the request behind each golden string of THIS deployment's host,
    created again today, gives the golden string with only the location (and so the CRC) changed.

    Source: Mechanism. EMV QRCPS for the layout; which fields Java writes, and how it truncates
      them, is this build's.
    Why: The golden strings pin Java's encoding (25 characters of the name, 15 of the city, the
      currency as 840, the amount field). If Java's encoder changed, the set would be describing
      an older build, and this is the test that says so.
    """
    live_host = emv.template_with(api.create_qr_detailed().body["qrCode"], "org.x9")[1]["01"] \
        .split("/pub/api/v1/loc/")[0]
    same_host = [golden for golden in EMV if golden["host"] == live_host]
    if not same_host:
        pytest.skip(f"no golden string for this deployment's host {live_host}; "
                    f"hosts in the set: {sorted({golden['host'] for golden in EMV})}")

    for golden in same_host:
        response = api.post("/api/v1/payment-request", body=golden["request"])
        assert response.status == 201, f"{golden['id']}: {response}"
        moved = golden["qrCode"][:-4].replace(golden["locationId"], response.body["location"]["id"])
        assert response.body["qrCode"] == moved + emv.crc16(moved), golden["id"]

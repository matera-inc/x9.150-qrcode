# Copyright © 2026 Matera Systems, Inc.
# Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
"""A test payee answers right and wrong, and the service tells them apart (Story 3.5).

Here the service under test is the PAYER's side: it signs a notification and delivers it to a
payee (``POST /api/v1/payment-notification/pre-payment`` / ``post-payment``, ADR-0015). The payee
is ``x9client.peer``, on its own test-CA identity. The service checks the answer against A.4.5 and
reports, never refuses (INTERPRETATION I-12):

- ``signature``: VERIFIED, ABSENT (no JWS at all) or INVALID (a JWS failing a check);
- ``signatureProblem``: a fixed code from the closed list in the OpenAPI, absent when VERIFIED;
- ``statusCode`` and ``accepted``: the signed status when VERIFIED, else the HTTP status.

Every case also asserts the QR Code named in the notification is untouched: the answer is reported,
and nothing in this service's state or events changes because of it (S3.5-AC3).
"""
import json

import pytest

from x9client import jws
from x9client import peer as peer_module
from x9client.samples import TX_HASH

PRE = "/api/v1/payment-notification/pre-payment"
POST = "/api/v1/payment-notification/post-payment"


def notify_through(api, peer, *, post=False):
    """Have the service notify ``peer`` about a fresh QR Code; returns (response, qr_id)."""
    qr = api.create_qr()
    body = peer_module.outbound("post-payment" if post else "pre-payment", endpoint=peer.endpoint,
                                qr_id=qr, transaction_id=TX_HASH if post else None)
    return api.post(POST if post else PRE, body=body), qr


def assert_reported(response, *, signature, problem, status_code, accepted):
    assert response.status == 200, f"the service reports, it does not fail: {response}"
    result = response.body
    assert result["signature"] == signature, result
    assert result.get("signatureProblem") == problem, result
    assert result["statusCode"] == status_code, result
    assert result["accepted"] is accepted, result
    if signature == "VERIFIED":
        assert result.get("signatureDetail") is None, result
    else:
        assert result.get("signatureDetail"), "the words travel with the code"
    return result


def assert_untouched(api, events, qr):
    """S3.5-AC3 — the answer changed nothing here: still ACTIVE, no event."""
    assert api.status_of(qr) == "ACTIVE", "an answer from a payee must not change our QR Code"
    assert events.none_for(qr, wait=1.5) == [], "nor publish anything"


# ------------------------------------------------------------------ S3.5-AC1

@pytest.mark.parametrize("post", [False, True], ids=["pre-payment", "post-payment"])
def test_the_test_payee_captures_the_exact_jws_it_received(api, peer, payee_certificate,
                                                           business_now, post):
    """S3.5-AC1 — the payee holds the service's signed question, byte for byte: it verifies
    against the service's certificate, carries the correlationId the caller chose, and says what
    the caller asked it to say."""
    qr = api.create_qr()
    correlation_id = "1b4e28ba-2fa1-11d2-883f-0016d3cca427"
    body = peer_module.outbound("post-payment" if post else "pre-payment", endpoint=peer.endpoint,
                                qr_id=qr, correlation_id=correlation_id,
                                transaction_id=TX_HASH if post else None)

    response = api.post(POST if post else PRE, body=body)

    assert response.status == 200, response
    assert len(peer.received) == 1, "exactly one delivery"
    question = peer.received[0]
    assert question.headers.get("Content-Type", "").startswith("application/jose")
    header, claims = jws.verify(question.raw, payee_certificate, now_ms=business_now,
                                expected_correlation_id=correlation_id)
    assert claims["payment"]["qrcodeId"] == qr
    assert claims["payment"].get("transactionId") == (TX_HASH if post else None)
    assert peer.answers_given[0].body.decode() == response.body["body"], \
        "the payee's answer is passed through verbatim"


# ------------------------------------------------------------------ S3.5-AC2: the 17 answers

def _replay(peer):
    """Case 4: a valid, signed yes from ANOTHER notification, sent back unchanged."""
    stored = {}

    def answer(question):
        if "token" not in stored:
            first = peer.yes(question)
            stored["token"] = first.body
            return first
        return peer_module.Answer(200, stored["token"])
    return answer


def _altered(peer):
    def answer(question):
        header, _, signature = peer.signed(question, 200).body.decode().split(".")
        return peer_module.Answer(200, f"{header}.{jws.b64u_json({'statusCode': 201})}.{signature}")
    return answer


def _alg_none(question):
    return peer_module.Answer(200, f"{jws.b64u_json({'alg': 'none'})}.{jws.b64u_json({'statusCode': 200})}.")


CASES = {
    # case: (answer factory taking the peer, expected signature, problem, statusCode, accepted)
    "01-signed-yes": (lambda p: p.yes, "VERIFIED", None, 200, True),
    "02-wrong-correlation-id": (
        lambda p: lambda q: p.signed(q, 200, correlation_id="00000000-0000-4000-8000-000000000000"),
        "INVALID", "CORRELATION_ID_MISMATCH", 200, True),
    "03-no-correlation-id": (
        lambda p: lambda q: p.signed(q, 200, drop=("correlationId",)),
        "INVALID", "BAD_HEADER", 200, True),
    "04-a-yes-replayed-from-another-notification": (
        _replay, "INVALID", "CORRELATION_ID_MISMATCH", 200, True),
    "05-empty-body": (
        lambda p: lambda q: peer_module.Answer(200, b"", content_type=None),
        "ABSENT", "ABSENT_BODY", 200, True),
    "06-unsigned-json": (
        lambda p: lambda q: peer_module.plain_json(200, {"statusCode": 200}),
        "ABSENT", "NOT_JWS", 200, True),
    "07-not-a-jws": (
        lambda p: lambda q: peer_module.Answer(200, "OK", content_type="text/plain"),
        "ABSENT", "NOT_JWS", 200, True),
    "08-altered-after-signing": (_altered, "INVALID", "BAD_SIGNATURE", 200, True),
    "11a-expired": (
        lambda p: lambda q: p.signed(q, 200, iat=p.signer.now_ms() - 600_000, ttl=60_000),
        "INVALID", "EXPIRED", 200, True),
    "11b-iat-in-the-future": (
        lambda p: lambda q: p.signed(q, 200, iat=p.signer.now_ms() + 300_000),
        "INVALID", "FUTURE_IAT", 200, True),
    "12-alg-none": (lambda p: _alg_none, "INVALID", "ALG_NONE", 200, True),
    "13-unknown-crit": (
        lambda p: lambda q: p.signed(q, 200, crit=jws.CRIT + ["statusCode", "x9blackbox"],
                                     extra={"x9blackbox": True}),
        "INVALID", "BAD_HEADER", 200, True),
    "14a-http-200-signed-400": (
        lambda p: lambda q: p.signed(q, 400, http_status=200),
        "INVALID", "STATUS_MISMATCH", 200, True),
    "14b-http-400-signed-200": (
        lambda p: lambda q: p.signed(q, 200, http_status=400),
        "INVALID", "STATUS_MISMATCH", 400, False),
    "15-400-plain-json-error": (
        lambda p: lambda q: peer_module.plain_json(400, {"title": "Bad Request", "status": 400}),
        "ABSENT", "NOT_JWS", 400, False),
    "16-400-signed-no": (lambda p: lambda q: p.signed(q, 400), "VERIFIED", None, 400, False),
}


@pytest.mark.parametrize("case", sorted(CASES))
def test_the_service_reports_each_answer_for_what_it_is(api, peer, events, case):
    """S3.5-AC2 (cases 1-8 and 11-16), S3.5-AC3 — statusCode, accepted, signature,
    signatureProblem; and the QR Code untouched."""
    factory, signature, problem, status_code, accepted = CASES[case]
    answer = factory(peer)

    if case.startswith("04"):
        peer.answer = answer
        first, _ = notify_through(api, peer)       # the notification whose yes gets replayed
        assert first.body["signature"] == "VERIFIED", first.body
    peer.answer = answer

    response, qr = notify_through(api, peer)

    result = assert_reported(response, signature=signature, problem=problem,
                             status_code=status_code, accepted=accepted)
    if case.startswith("15"):
        assert json.loads(result["body"]) == {"title": "Bad Request", "status": 400}, \
            "the payee's error is passed through"
    assert_untouched(api, events, qr)


@pytest.mark.parametrize("case, who", [("09-outside-the-trusted-chain", "untrusted"),
                                       ("10-revoked-certificate", "payee_revoked")])
def test_an_answer_signed_by_a_certificate_it_must_not_trust_is_invalid(api, peer, pki, events,
                                                                        business_now, case, who):
    """S3.5-AC2 cases 9 and 10, S3.5-AC4 — a certificate from a CA nobody trusts, and one the
    test CA revoked on the CRL this suite serves: INVALID, UNTRUSTED_CHAIN."""
    signer = jws.Signer(pki[who], now_ms=business_now)
    peer.answer = lambda question: peer.signed(question, 200, signer=signer)

    response, qr = notify_through(api, peer)

    assert_reported(response, signature="INVALID", problem="UNTRUSTED_CHAIN", status_code=200,
                    accepted=True)
    if who == "payee_revoked":
        assert pki["crl"].fetches > 0, "the service must have fetched the CRL to know"
        assert "revoked" in (response.body.get("signatureDetail") or "").lower(), response.body
    assert_untouched(api, events, qr)


def test_a_payee_that_hangs_up_without_answering_is_502(api, peer, events):
    """S3.5-AC2 case 17 — no answer at all: the payee reads the notification and closes the
    connection. 502, a delivery to retry, and nothing changed here."""
    peer.answer = lambda question: peer_module.Answer(0, hang_up=True)

    response, qr = notify_through(api, peer)

    assert response.status == 502, f"no answer is a delivery to retry: {response}"
    assert peer.received, "it was delivered; the payee simply never answered"
    assert_untouched(api, events, qr)


@pytest.mark.xfail(strict=True, reason=(
    "Java does not apply its configured 15 s read timeout to outbound notifications "
    "(ServiceConfiguration.notificationRestClient): a payee answering after 20 s, or 61 s, is "
    "waited for and its answer reported. Question for the owner."))
def test_a_payee_answering_after_the_timeout_is_502(api, peer, events):
    """S3.5-AC2 case 17 — an answer that arrives after the 15 s timeout: 502."""
    peer.answer = lambda question: peer_module.Answer(200, peer.yes(question).body, delay=20)

    response, qr = notify_through(api, peer)

    assert response.status == 502, f"a late answer is no answer: {response}"
    assert_untouched(api, events, qr)


@pytest.mark.parametrize("case", sorted(jws.Tamperer.CASES))
def test_every_tampered_answer_is_named_by_its_fixed_code(api, peer, pki, events, case):
    """S3.2-AC2's reasons, named: each of the tamperer's defects, in a payee's answer, gets the
    service's fixed code. (A payer's tampered question is refused with one generic 401; the
    outbound check is where the service says which check failed.)"""
    tamperer = jws.Tamperer(peer.signer, pki["untrusted"])
    peer.answer = lambda question: peer_module.Answer(200, tamperer.token(
        case, {"statusCode": 200}, correlation_id=question.correlation_id,
        crit=jws.CRIT + ["statusCode"], extra={"statusCode": 200}))

    response, qr = notify_through(api, peer)

    assert_reported(response, signature="INVALID", problem=jws.Tamperer.CASES[case],
                    status_code=200, accepted=True)
    assert_untouched(api, events, qr)

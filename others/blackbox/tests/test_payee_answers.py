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
# The 502's detail (owner, 2026-11-15): no answer is not proof that nothing arrived.
ANSWER_NOT_RECEIVED = ("The payee's answer was not received; the payee may have received the "
                       "notification. Retrying is safe.")


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
    """X9-SIG-501 — the test payee holds the service's signed outbound question byte for byte, under
    the caller's correlationId.

    S3.5-AC1 — the payee holds the service's signed question, byte for byte: it verifies
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
    """X9-SIG-502 — each of the payee's answers (cases 1-8, 11-16) is reported for what it is:
    signature, signatureProblem, statusCode, accepted.

    S3.5-AC2 (cases 1-8 and 11-16), S3.5-AC3 — statusCode, accepted, signature,
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
    """X9-SIG-503 — an answer signed by an untrusted or a revoked certificate is INVALID,
    UNTRUSTED_CHAIN.

    S3.5-AC2 cases 9 and 10, S3.5-AC4 — a certificate from a CA nobody trusts, and one the
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
    """X9-SIG-504 — a payee that hangs up without answering is 502, and nothing changes here.

    S3.5-AC2 case 17 — no answer at all: the payee reads the notification and closes the
    connection. 502, a delivery to retry, and nothing changed here. The payee did receive it, so
    the problem's detail must not say that nothing was delivered (owner, 2026-11-15)."""
    peer.answer = lambda question: peer_module.Answer(0, hang_up=True)

    response, qr = notify_through(api, peer)

    assert response.status == 502, f"no answer is a delivery to retry: {response}"
    assert response.body["detail"] == ANSWER_NOT_RECEIVED, response
    assert peer.received, "it was delivered; the payee simply never answered"
    assert_untouched(api, events, qr)


def test_a_payee_answering_after_the_timeout_is_502(api, peer, events):
    """X9-SIG-505 — a payee answering after the 15 s timeout is 502.

    S3.5-AC2 case 17 — an answer that arrives after the 15 s timeout: 502. (Java once handed
    this call its shared, @Primary client, which has no timeout, and reported a 20 s answer as an
    ordinary 200.)"""
    peer.answer = lambda question: peer_module.Answer(200, peer.yes(question).body, delay=20)

    response, qr = notify_through(api, peer)

    assert response.status == 502, f"a late answer is no answer: {response}"
    assert_untouched(api, events, qr)


@pytest.mark.parametrize("redirect", [302, 307])
def test_a_redirect_from_the_payee_is_not_followed(api, peer, events, redirect):
    """X9-SIG-150 — a payee answering 302 or 307 with a ``Location`` that would answer a signed
    yes is not followed: one delivery, and the redirect is reported as the payee's answer.

    Source: Ours. ADR-0015 ("redirects are not followed"); openapi.yaml, the pre-payment and
      post-payment endpoints. The outbound client is built with ``Redirect.NEVER``.
    Why: The destination is trusted because it arrived inside a signed payload. A redirect leads
      somewhere that signature never attested, so following it would deliver a payer's signed
      notification, and take its answer, from a host nobody vouched for.
    """
    target = peer.endpoint + "/redirected"

    def answer(question):
        if len(peer.received) == 1:
            return peer_module.Answer(redirect, b"", content_type=None,
                                      headers={"Location": target})
        return peer.yes(question)
    peer.answer = answer

    response, qr = notify_through(api, peer)

    assert len(peer.received) == 1, \
        f"the redirect was followed: {len(peer.received)} deliveries"
    assert response.status == 200, f"the service reports, it does not fail: {response}"
    assert response.body["statusCode"] == redirect, response.body
    assert response.body["accepted"] is False, response.body
    assert_untouched(api, events, qr)


@pytest.mark.parametrize("case", sorted(jws.Tamperer.CASES))
def test_every_tampered_answer_is_named_by_its_fixed_code(api, peer, pki, events, case):
    """X9-SIG-506 — each tampered payee answer is reported INVALID with the service's fixed code.

    S3.2-AC2's reasons, named: each of the tamperer's defects, in a payee's answer, gets the
    service's fixed code. (A payer's tampered question gets the same code in its signed 401:
    ``test_signatures``.) A ``correlationId`` that is not a canonical UUID is ``BAD_HEADER``,
    not a mismatch: the header is malformed before it is compared."""
    tamperer = jws.Tamperer(peer.signer, pki["untrusted"], pki["ca"])
    peer.answer = lambda question: peer_module.Answer(200, tamperer.token(
        case, {"statusCode": 200}, correlation_id=question.correlation_id,
        crit=jws.CRIT + ["statusCode"], extra={"statusCode": 200}))

    response, qr = notify_through(api, peer)

    assert_reported(response, signature="INVALID", problem=jws.Tamperer.CASES[case],
                    status_code=200, accepted=True)
    assert_untouched(api, events, qr)


@pytest.mark.parametrize("body", [b'{"statusCode":200.5}', b'{"statusCode":200.0}',
                                  b'{"statusCode":2e2}', b'{"statusCode":4294967496}'])
def test_a_signed_answer_whose_status_code_is_not_an_integer_is_invalid(api, peer, events, body):
    """X9-SIG-527 — a signed answer whose body ``statusCode`` is a number but not an integer, or
    an integer beyond 32 bits, is INVALID, ``STATUS_MISSING``, though the HTTP status is 200 and
    the header's ``statusCode`` is 200.

    Source: ANSI X9.150-2026 A.4.5 note (the body is ``{"statusCode": integer}``). Conformance.
    Why: Java truncated any JSON number, so ``200.5`` (or ``2e2``, or ``4294967496``, which wraps
      round to 200) was reported as a VERIFIED 200: a signed body the standard does not define,
      taken as the payee's decision. Go's ``CheckAnswer`` refuses all four, and so does Java now.
    """
    peer.answer = lambda question: peer_module.Answer(200, peer.signer.sign(
        body, correlation_id=question.correlation_id, crit=jws.CRIT + ["statusCode"],
        extra={"statusCode": 200}))

    response, qr = notify_through(api, peer)

    assert_reported(response, signature="INVALID", problem="STATUS_MISSING", status_code=200,
                    accepted=True)
    assert_untouched(api, events, qr)

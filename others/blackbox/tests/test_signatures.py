# Copyright © 2026 Matera Systems, Inc.
# Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
"""Payers sign for themselves, and the payee's signed answers are checked (Story 3.2).

Payer A and payer B are two PSPs issued by the bundled test CA at run time, each with its own key
and certificate subject. Nothing here asks the service under test to sign anything: a service that
signs the messages it then verifies can only ever be shown messages it would accept.

Every answer to a payment notification is a JWS signed by the payee (INTERPRETATION I-12). The
suite verifies it against the payee's certificate, from the payee's own JWK set, and checks its
``correlationId`` against the question's.
"""
import datetime
import os
import re
import uuid

import pytest

from x9client import jws
from x9client.samples import BILL, TX_HASH

NOTIFY = "/pub/api/v1/payment-notification"
ERROR_FIELDS = ("type", "title", "status", "detail")


def answer(api, payee_certificate, business_now, token, *, question_correlation_id=None):
    """Send ``token``; verify the signed answer. Returns ``(response, header, body)``.

    With ``question_correlation_id`` the answer must echo it; without, it must be fresh (the
    question could not be verified, so there was nothing trustworthy to echo).
    """
    response = api.post_jose(NOTIFY, token)
    assert response.content_type == "application/jose", \
        f"every answer is a JWS, Content-Type application/jose: {response.status} {response.headers}"
    header, body = jws.verify(response.text, payee_certificate, now_ms=business_now,
                              expected_correlation_id=question_correlation_id)
    if question_correlation_id is None:
        assert header["correlationId"] != (api.signer.last_correlation_id or ""), \
            "an unverifiable question is answered under a fresh correlationId"
        uuid.UUID(header["correlationId"])
    assert "statusCode" in header.get("crit", []), "the answer's crit names statusCode"
    assert header.get("statusCode") == response.status, header
    return response, header, body


# ------------------------------------------------------------------ S3.2-AC1: the profile

class TestTheProfile:

    def test_a_payer_token_carries_the_x9150_profile(self, payer_a, payer_b):
        """S3.2-AC1 — ms iat/ttl, correlationId, crit, x5c, x5t#S256, for both payers.

        Why: the suite's own signer is the first thing to be wrong in a way that hides everything
    else. A and B must also be different parties, or the holder tests below prove nothing.
        """
        for signer, alg in ((payer_a, "PS512"), (payer_b, "ES256")):
            header, _ = jws.parse(signer.sign({"hello": "x9"}))
            assert header["alg"] == alg
            assert set(header["crit"]) == {"correlationId", "iat", "ttl"}
            assert header["iat"] > 1_000_000_000_000, "iat is in milliseconds"
            assert header["ttl"] == jws.DEFAULT_TTL_MS
            uuid.UUID(header["correlationId"])
            assert len(header["x5c"]) == 2, "leaf and the test CA"
            assert header["x5t#S256"] == jws.thumbprint(signer.identity.certificate)

        assert payer_a.identity.subject != payer_b.identity.subject

    @pytest.mark.parametrize("who", ["payer_a", "payer_b"])
    def test_each_payer_is_accepted_on_its_own_key(self, api, request, who):
        """S3.2-AC1 — the deployment accepts both test-CA payers, RSA and EC."""
        signer = request.getfixturevalue(who)
        qr = api.create_qr()

        status, body = api.notify(qr, signer=signer, payer_info=f"{who}@example.com")

        assert status == 200, f"{who} must be accepted: {status} {body}"
        assert api.status_of(qr) == "PAYMENT_INITIATED"


# ------------------------------------------------------------------ S3.2-AC2: the tamperer

class TestTamperedQuestions:
    """Each token differs from an accepted one in exactly one respect."""

    @pytest.mark.parametrize("case", sorted(jws.Tamperer.CASES))
    def test_a_tampered_notification_is_refused_and_changes_nothing(
            self, api, tamperer, payee_certificate, business_now, events, case):
        """S3.2-AC2 — wrong key, untrusted CA, expired iat + ttl, iat in the future, missing or
        unknown crit, missing x5c, altered payload, alg none: each refused, nothing changes.

        Why: these are the signatures the service under test would never produce, which is why
    they could not be tested while the suite signed through the service.

        Java answers every one with the same signed 401 ``invalid-signature``; it does not tell
    the sender which check failed. The reason is named where the service reports it, on an
    answer it receives as a payer (Story 3.5, ``test_payee_answers``). Here the reason is pinned
    by construction: the control below is the same message, untampered, and it is accepted.
        """
        qr = api.create_qr()
        payload = api_notification(qr)

        response, header, body = answer(api, payee_certificate, business_now,
                                        tamperer.token(case, payload))

        assert response.status == 401, f"{case} must be refused: {response.status} {body}"
        assert body["statusCode"] == 401 and body["status"] == 401
        assert body["type"].endswith("/invalid-signature"), body
        assert api.status_of(qr) == "ACTIVE", "a refused question reserves nothing"
        assert events.none_for(qr, wait=0) == [], "and publishes nothing"

    def test_the_untampered_control_is_accepted(self, api, payer_a):
        """S3.2-AC2 — the control: the same message, signed honestly, is accepted."""
        qr = api.create_qr()
        status, body = api.send_notification(payer_a.sign(api_notification(qr)))
        assert status == 200, f"{status} {body}"

    def test_a_revoked_payer_certificate_is_refused(self, api, pki, business_now):
        """S3.2-AC2 — a certificate the test CA revoked on its CRL is refused.

        The service fetches the CRL from this suite, so this also proves the CRL is reachable
        and read, not merely tolerated when absent.
        """
        qr = api.create_qr()
        token = jws.Signer(pki["revoked"], now_ms=business_now).sign(api_notification(qr))

        status, body = api.send_notification(token)

        assert status == 401, f"a revoked certificate must be refused: {status} {body}"
        assert pki["crl"].fetches > 0, "the service must have fetched the CRL to know"
        assert api.status_of(qr) == "ACTIVE"


def api_notification(qr):
    from x9client import samples
    return samples.notification(qr, payer_info="tamper@example.com")


# ------------------------------------------------------------------ S3.2-AC3: two payers

class TestTwoPayers:
    """The holder is ``payer.info`` AND the certificate subject (ADR-0021, ADR-0036)."""

    INFO = "same-person@example.com"

    def test_another_payers_announcement_is_a_conflict(self, api, payer_a, payer_b, events):
        """S3.2-AC3 — A holds; B, on its own key and with the SAME payer.info, announces: 409.

        Why: two customers of two PSPs can share an e-mail address. The certificate is the part
    of the identity the sender did not choose, so the same payer.info under another certificate
    is another payer.
        """
        qr = api.create_qr()
        assert api.notify(qr, signer=payer_a, payer_info=self.INFO).status == 200

        status, body = api.notify(qr, signer=payer_b, payer_info=self.INFO)

        assert status == 409, f"B must not take A's reservation: {status} {body}"
        assert api.status_of(qr) == "PAYMENT_INITIATED"
        initiated = [e for e in events.for_qr(qr) if e.get("type") == "payment.initiated"]
        assert len(initiated) == 1 and initiated[0].get("payerInfo") == self.INFO

    def test_another_payer_cannot_give_the_reservation_back(self, api, payer_a, payer_b):
        """S3.2-AC3 — A holds; B's NOT_SENT on its own key: 409, and A keeps it."""
        qr = api.create_qr()
        api.notify(qr, signer=payer_a, payer_info=self.INFO)

        status, body = api.notify(qr, signer=payer_b, payer_info=self.INFO, action="NOT_SENT")

        assert status == 409, f"B must not release A's reservation: {status} {body}"
        assert api.status_of(qr) == "PAYMENT_INITIATED"

    def test_another_payers_report_is_registered_and_changes_nothing(self, api, payer_a, payer_b,
                                                                    events):
        """S3.2-AC3, as ADR-0036 settles it — A holds; B reports a hash on its own key.

        The story asks for 409 here. ADR-0036 (accepted the same day) says a post-commit on a QR
    Code held by another payer is accepted, registered and flagged, and the holder keeps it,
    because money has moved. This pins ADR-0036 and is listed as a question for the owner.
        """
        qr = api.create_qr()
        api.notify(qr, signer=payer_a, payer_info=self.INFO)

        status, body = api.notify(qr, signer=payer_b, payer_info=self.INFO, action="SENT",
                                  transaction_id=TX_HASH)

        assert status == 200, f"{status} {body}"
        assert api.status_of(qr) == "PAYMENT_INITIATED", "A keeps the reservation"
        sent = [e for e in events.for_qr(qr, until=lambda es: any(
            e.get("type") == "payment.sent" for e in es)) if e.get("type") == "payment.sent"]
        assert len(sent) == 1, sent
        assert "RECEIVED_IN_WRONG_STATUS" in (sent[0].get("deviations") or []), sent[0]


# ------------------------------------------------------------------ S3.2-AC4/6: a signed yes

@pytest.mark.serial
def test_a_yes_is_signed_by_the_payee_and_answers_this_question(api, clock, payer_a,
                                                                 payee_certificate):
    """S3.2-AC4, S3.2-AC6 — 200, application/jose, a JWS whose body is exactly
    ``{"statusCode": 200}``, verified against the payee's certificate, echoing the question's
    correlationId, with ``iat`` the test clock's instant within 1 s.

    The clock is moved a day ahead first, so an ``iat`` taken from the wall clock instead of the
    business clock would be off by a day, not by a few milliseconds.
    """
    clock.move_by(86_400_000)
    qr = api.create_qr()
    token = payer_a.sign(api_notification(qr))
    question_id = payer_a.last_correlation_id

    before = clock.now_millis()
    response, header, body = answer(api, payee_certificate, clock.now_millis, token,
                                    question_correlation_id=question_id)
    after = clock.now_millis()

    assert response.status == 200
    assert body == {"statusCode": 200}, f"the signed yes is exactly statusCode 200: {body}"
    assert before - 1000 <= header["iat"] <= after + 1000, \
        f"iat {header['iat']} must be the business clock's instant ({before}..{after})"


# ------------------------------------------------------------------ S3.2-AC7: a signed no

class TestASignedNo:
    """Every refusal keeps its HTTP status and is signed, with the error fields inside."""

    def _refusal(self, api, payee_certificate, business_now, token, status, question_id):
        response, header, body = answer(api, payee_certificate, business_now, token,
                                        question_correlation_id=question_id)
        assert response.status == status, f"expected {status}: {response.status} {body}"
        assert body["statusCode"] == status, body
        for field in ERROR_FIELDS:
            assert field in body, f"the error field {field} is inside the signed body: {body}"
        return body

    def test_a_wrong_amount(self, api, payer_a, payee_certificate, business_now):
        """S3.2-AC7 — 400, amount: the signed no echoes the question and names the field."""
        qr = api.create_qr()
        token = payer_a.sign(api_notification_with(qr, amount=BILL + 1))
        body = self._refusal(api, payee_certificate, business_now, token, 400,
                             payer_a.last_correlation_id)
        assert "payment.amount" in str(body.get("violations")), body

    def test_an_unknown_qr_code(self, api, payer_a, payee_certificate, business_now):
        """S3.2-AC7 — 404, unknown QR Code."""
        token = payer_a.sign(api_notification_with("0" * 32))
        self._refusal(api, payee_certificate, business_now, token, 404,
                      payer_a.last_correlation_id)

    def test_another_holder(self, api, payer_a, payer_b, payee_certificate, business_now):
        """S3.2-AC7 — 409, another payer holds the reservation."""
        qr = api.create_qr()
        api.notify(qr, signer=payer_a, payer_info="holder@example.com")
        token = payer_b.sign(api_notification_with(qr, payer_info="other@example.com"))
        self._refusal(api, payee_certificate, business_now, token, 409,
                      payer_b.last_correlation_id)

    def test_a_cancelled_qr_code(self, api, payer_a, payee_certificate, business_now):
        """S3.2-AC7 — 400, a pre-commit on a CANCELLED QR Code."""
        qr = api.create_qr()
        api.set_status(qr, "CANCELLED")
        token = payer_a.sign(api_notification_with(qr))
        self._refusal(api, payee_certificate, business_now, token, 400,
                      payer_a.last_correlation_id)

    @pytest.mark.serial
    def test_a_pre_commit_after_valid_until(self, api, clock, payer_a, payee_certificate):
        """S3.2-AC7 — 400, the QR Code's validUntil has passed by the payee's clock."""
        valid_until = (datetime.datetime.now(datetime.timezone.utc)
                       + datetime.timedelta(hours=1)).replace(microsecond=0)
        qr = api.create_qr(valid_until=valid_until.strftime("%Y-%m-%dT%H:%M:%SZ"))
        clock.move_to(valid_until + datetime.timedelta(seconds=1))

        token = payer_a.sign(api_notification_with(qr))
        self._refusal(api, payee_certificate, clock.now_millis, token, 400,
                      payer_a.last_correlation_id)
        assert api.status_of(qr) != "PAYMENT_INITIATED"

    @pytest.mark.parametrize("case", ["wrong_key", "alg_none"])
    def test_an_unverifiable_question_gets_a_fresh_correlation_id(
            self, api, tamperer, payee_certificate, business_now, case):
        """S3.2-AC7 — 401: signed, but under a fresh correlationId (none was verified)."""
        qr = api.create_qr()
        token = tamperer.token(case, api_notification(qr))
        question_id = jws.parse(token)[0].get("correlationId")

        response, header, body = answer(api, payee_certificate, business_now, token)

        assert response.status == 401
        assert header["correlationId"] != question_id

    @pytest.mark.parametrize("raw, status", [("not-a-jws", 401), ("", 400)])
    def test_an_unreadable_question_is_still_answered_signed(
            self, api, payee_certificate, business_now, raw, status):
        """S3.2-AC7 — not a JWS (401), an empty body (400): signed, fresh correlationId."""
        response, header, body = answer(api, payee_certificate, business_now, raw)
        assert response.status == status and body["statusCode"] == status, body


def api_notification_with(qr, **kwargs):
    from x9client import samples
    kwargs.setdefault("payer_info", "refused@example.com")
    return samples.notification(qr, **kwargs)


# ------------------------------------------------------------------ S3.2-AC8: a replay

def test_the_same_signed_question_twice_is_answered_twice(api, payer_a, payee_certificate,
                                                          business_now, events):
    """S3.2-AC8 — the same JWS (same correlationId) sent twice: today's answer, pinned.

    Java accepts it twice: the second is the holder's retry (ADR-0021), judged again, and it adds
    no event. Each answer echoes the question's correlationId. Refusing replays would be a
    separate interpretation entry, not a change this suite makes.
    """
    qr = api.create_qr()
    token = payer_a.sign(api_notification_with(qr, payer_info="replay@example.com"))
    question_id = payer_a.last_correlation_id

    first = answer(api, payee_certificate, business_now, token, question_correlation_id=question_id)
    second = answer(api, payee_certificate, business_now, token, question_correlation_id=question_id)

    assert first[0].status == 200 and second[0].status == 200
    assert api.status_of(qr) == "PAYMENT_INITIATED"
    initiated = [e for e in events.for_qr(qr) if e.get("type") == "payment.initiated"]
    assert len(initiated) == 1, f"a replay is not a second announcement: {initiated}"


# ------------------------------------------------------------------ S3.2-AC5

def test_no_test_signs_through_the_service_under_test():
    """S3.2-AC5 — nothing in the suite calls /api/v1/signature/generate to sign as a payer."""
    here = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    offenders = []
    for folder, _, files in os.walk(here):
        if ".venv" in folder or "__pycache__" in folder:
            continue
        for name in files:
            if name.endswith(".py") and name != os.path.basename(__file__):
                with open(os.path.join(folder, name)) as f:
                    if re.search(r"/api/v1/signature/generate", f.read()):
                        offenders.append(os.path.join(folder, name))
    assert offenders == [], f"signs through the service under test: {offenders}"

# Copyright © 2026 Matera Systems, Inc.
# Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
"""Payers are pinned: the payload, the notifications, the payer-side endpoints (Story 4.3).

Plan §4.1 groups C (payload retrieval), E (payment notifications) and H (the payer-side
endpoints), against the running build. These pin TODAY's Java behaviour, except where an accepted
ADR says otherwise: such a case is a strict expected failure naming the rule, so it turns red the
day Java and the rule agree.

Many cases of the three groups were already pinned before this story. They are not repeated here;
this module adds what was missing, and the table says where the rest lives:

| AC case | Where |
|---|---|
| C: expired QR Code | ``X9-LIFE-126`` (``test_payment_notification.py``) |
| E: pre-commit reserves, every rail | ``X9-MAP-020`` (``test_transition_map.py``) |
| E: post-commit from the holder recorded | ``X9-MAP-032`` |
| E: post-commit on ACTIVE reserves and registers the hash | ``X9-MAP-030``, ``X9-MAP-031`` |
| E: wrong amount | ``X9-AMT-001``, ``X9-AMT-002`` |
| E: tip outside its range; tip on top of the bill (ADR-0017) | ``X9-TIP-104``; ``X9-TIP-101``, ``X9-TIP-102`` |
| E: unoffered currency | ``X9-CUR-001`` |
| E: rail or currency not offered, pre- and post-commit (ADR-0036, amended 2026-11-15) | ``X9-RAIL-520`` to ``-522``, ``X9-CUR-520`` to ``-522`` (``TestNotOffered``) |
| E: destination not on the QR Code | ``X9-RAIL-001`` |
| E: second payer → 409 | ``X9-HOLD-011``, ``X9-MAP-024`` |
| E: holder re-announces (refresh, no event) | ``X9-MAP-021``, ``X9-HOLD-010``, ``X9-HOLD-012`` |
| E: ``NOT_SENT`` releases | ``X9-HOLD-020``, ``X9-MAP-042`` |
| H: the signature verdicts of Story 3.5 | ``test_payee_answers.py`` (all 17 answers, every tamperer code, 502 on a hang-up and on the 15 s timeout) |

Every notification answer in this module is verified as a JWS signed by the payee (Story 2.3),
whatever its status.
"""
import base64
import collections
import datetime
import threading
import time
import urllib.parse
import uuid

import pytest
from cryptography.hazmat.primitives import serialization

from x9client import emv as emv_module
from x9client import jws, ocsp, samples
from x9client import peer as peer_module
from x9client.checks import assert_refused
from x9client.samples import BILL, FEDNOW, RECIPIENT, TX_HASH

PRE = "/api/v1/payment-notification/pre-payment"
POST = "/api/v1/payment-notification/post-payment"
SIGNATURE_REFUSED = "Invalid JWS signature for payment payload request."
TX_2 = "5q7nKs4Wd9vAhZrT2uYbP6eLjJ8gFwC3"

RAILS = {
    "solana": {"create": {}, "notify": {}},
    "fednow": {"create": {"currency": "USD", "networks": {"fednow": FEDNOW}},
               "notify": {"currency": "USD", "network": "fednow", "blockchain": False}},
    "rtp": {"create": {"currency": "USD", "networks": {"rtp": FEDNOW}},
            "notify": {"currency": "USD", "network": "rtp", "blockchain": False}},
    "ach": {"create": {"currency": "USD", "networks": {"ach": FEDNOW}},
            "notify": {"currency": "USD", "network": "ach", "blockchain": False,
                       "expected_date": "2030-01-02T00:00:00Z"}},
}


# ------------------------------------------------------------------ helpers

def signed(response, payee_certificate, now_ms, status):
    """Story 2.3: the answer has ``status`` and is a JWS signed by the payee, statusCode inside."""
    assert response.status == status, f"expected {status}: {response.status} {response.body}"
    assert response.content_type == "application/jose", \
        f"every answer is a JWS: {response.status} {response.headers}"
    header, body = jws.verify(response.text, payee_certificate, now_ms=now_ms)
    assert header.get("statusCode") == status and body.get("statusCode") == status, (header, body)
    return body


def fetch(api, location_id, emv, *, signer=None):
    return api.fetch_payload(location_id, emv, signer=signer)


def assert_payload_refused(response, naming):
    """The payload endpoint refuses with 400 (not 401, unlike the notification endpoint; the
    OpenAPI says so) and the answer carries no payload."""
    assert_refused(response, naming=naming, status=400)
    assert RECIPIENT not in response.text, "a refused request must not carry the bank details"


def with_crc(content):
    """``content`` (an EMV string without its CRC value) completed with a correct CRC."""
    return content + emv_module.crc16(content)


@pytest.fixture(scope="module")
def responder(pki):
    """An OCSP responder for the test CA, in this process (``x9client.ocsp``)."""
    server = ocsp.OcspResponder(pki["ca"])
    yield server
    server.close()


@pytest.fixture
def issued(api):
    """A fresh QR Code as created: id, location, EMV."""
    response = api.create_qr_detailed()
    assert response.status == 201, response
    return response.body


def unique(path):
    """A URL path no earlier fetch has used (the JVM caches what it fetched, by URL)."""
    return f"{path}/{uuid.uuid4().hex}"


# ================================================================== group C: the payload

class TestPayload:
    """S4.3-AC1 — what a payer fetching ``/pub/api/v1/loc/{id}`` is served, or told."""

    def test_an_unknown_location_is_refused(self, api, issued):
        """X9-LOC-301 — a location no QR Code holds is refused, and serves nothing.

        S4.3-AC1, unknown location. The request is signed and its content names the unknown
        location (a well-formed EMV with a correct CRC), so the lookup is what fails, not the
        location check of X9-LOC-020.

        Source: Mechanism. A 400 with a generic reason ("Error processing payment payload
          retrieve."); openapi.yaml says so since the owner kept 400 over 404 (2026-11-14).
        Why: A Go build answering 404 here, or naming the location, would be a visible change for
          every payer that branches on the status.
        """
        unknown = "0" * 32
        content = with_crc(issued["qrCode"][:-4].replace(issued["location"]["id"], unknown))

        response = fetch(api, unknown, content)

        assert_payload_refused(response, naming="Error processing payment payload retrieve.")

    @pytest.mark.parametrize("change, naming", [
        ("one-character", "qrCodeContent"),
        ("wrong-crc", SIGNATURE_REFUSED),
        ("trailing-space", SIGNATURE_REFUSED),
    ])
    def test_content_not_byte_equal_to_the_issued_emv_is_refused(self, api, issued, change,
                                                                 naming):
        """X9-LOC-302 — the signed qrCodeContent must be byte-equal to the EMV this location was
        issued with.

        S4.3-AC1, qrCodeContent not byte-equal. Each variant names the same location: a
        different merchant name with a correct CRC (a well-formed, different QR Code) is refused
        by the comparison with the stored content, naming ``qrCodeContent``; the issued string
        with a wrong CRC, and with a trailing space, are malformed EMV and refused one step
        earlier, by the request check that reads the location out of the content (ADR-0027,
        EMV: malformed EMV is refused, never tolerated; today's reason pinned).

        Source: Ours. ANSI X9.150 signs the request; binding it to the content issued for THIS
          location is this service's rule (X9-LOC-001..005).
        Why: Without byte equality a payer could fetch the payload of a code it altered, and the
          signature would vouch for content the payee never issued.
        """
        original = issued["qrCode"]
        content = {
            "one-character": with_crc(original[:-4].replace("Blackbox Co", "Blackbox Cp")),
            "wrong-crc": original[:-4] + ("0000" if original[-4:] != "0000" else "FFFF"),
            "trailing-space": original + " ",
        }[change]
        assert content != original, "the variant must differ, or this tests nothing"

        response = fetch(api, issued["location"]["id"], content)

        assert_payload_refused(response, naming=naming)

    def test_the_issued_content_is_served(self, api, issued, payee_certificate, business_now):
        """X9-LOC-303 — control: the issued content, signed by a trusted payer, is served.

        S4.3-AC1. Without an acceptance case every refusal above could pass against a service
        that refuses everything.

        Source: ANSI X9.150 — the payer fetches the signed payload at the location. Conformance.
        Why: The refusals in this class are only meaningful next to this.
        """
        response = fetch(api, issued["location"]["id"], issued["qrCode"])

        assert response.status == 200, response
        _, claims = jws.verify(response.text, payee_certificate, now_ms=business_now)
        assert claims["id"] == issued["id"]

    @pytest.mark.parametrize("case", sorted(jws.Tamperer.CASES))
    def test_a_payload_request_failing_its_signature_is_refused(self, api, issued, tamperer,
                                                                case):
        """X9-LOC-304 — a payload request whose JWS fails any check is refused, 400, serving
        nothing.

        S4.3-AC1: bad signature (wrong key, altered payload), expired iat + ttl, iat in the
        future, missing or unknown crit, missing x5c, untrusted CA, alg none; and an x5t#S256
        naming the issuing CA rather than the end entity (BUG-006), and a correlationId that is
        not a canonical UUID (ANSI X9.150-2026 §10.3.1, §10.7 steps 3.1 and 7).

        Source: Ours, ANSI X9.150 §8 profile as INTERPRETATION reads it. Mechanism for the
          status and the words: this endpoint answers 400 with one generic reason, never 401
          and never a signatureProblem code (the notification endpoint does both; the OpenAPI
          documents the difference).
        Why: The payload carries bank details. Any check that stops refusing here hands them to
          whoever asks; and a Go build answering 401 would change what payers branch on.
        """
        content = {"qrCodeContent": base64.b64encode(issued["qrCode"].encode()).decode()}

        response = api.post_jose(f"/pub/api/v1/loc/{issued['location']['id']}",
                                 tamperer.token(case, content))

        assert_payload_refused(response, naming=SIGNATURE_REFUSED)

    def test_a_payer_revoked_on_the_crl_is_refused(self, api, issued, pki, business_now):
        """X9-LOC-305 — a payer certificate revoked on the test CA's CRL cannot fetch a payload.

        S4.3-AC1, revoked certificate (CRL).

        Source: Ours. ADR-0027: revocation checked against the CRL first.
        Why: A revoked PSP keeps a valid-looking signature; only the revocation check stops it.
        """
        revoked = jws.Signer(pki["revoked"], now_ms=business_now)

        response = fetch(api, issued["location"]["id"], issued["qrCode"], signer=revoked)

        assert_payload_refused(response, naming=SIGNATURE_REFUSED)
        assert pki["crl"].fetches > 0, "the service must have fetched the CRL to know"

    @pytest.mark.parametrize("endpoint", ["payload", "notification"])
    @pytest.mark.parametrize("revoked", [False, True], ids=["good", "revoked"])
    def test_ocsp_decides_when_there_is_no_crl(self, api, issued, pki, responder, business_now,
                                               payee_certificate, endpoint, revoked):
        """X9-LOC-306 — a payer certificate with no CRL distribution point is checked by OCSP:
        "good" is accepted, "revoked" refused, on both payer endpoints.

        S4.3-AC1, revoked certificate (OCSP). The responder is the suite's own
        (``x9client.ocsp``), signing with the test CA's key; the certificate names it in its
        Authority Information Access.

        Source: Ours. ADR-0027: PREFER_CRLS, and OCSP when no CRL is obtainable.
        Why: A CA that publishes only OCSP is common; without the fallback its revoked PSPs
          would be accepted, or all of its PSPs refused.
        """
        identity = ocsp.issue(pki["ca"], f"ocsp-{uuid.uuid4().hex[:8]}", "Blackbox Payer OCSP",
                              responder=responder)
        if revoked:
            responder.revoke(identity)
        signer = jws.Signer(identity, now_ms=business_now)
        queries = responder.queries

        if endpoint == "payload":
            response = fetch(api, issued["location"]["id"], issued["qrCode"], signer=signer)
            if revoked:
                assert_payload_refused(response, naming=SIGNATURE_REFUSED)
            else:
                assert response.status == 200, response
        else:
            response = api.notify(issued["id"], signer=signer)
            if revoked:
                body = signed(response, payee_certificate, business_now, 401)
                assert body.get("signatureProblem") == "UNTRUSTED_CHAIN", body
                assert api.status_of(issued["id"]) == "ACTIVE"
            else:
                signed(response, payee_certificate, business_now, 200)
        assert responder.queries > queries, "the service must have asked the OCSP responder"

    # sources -> (the CRL the certificate names, the OCSP responder it names): each "unreachable"
    # (on a port where nothing listens), "unreadable" (answers 200 with an HTML page, not a CRL),
    # or None (not named).
    SOFT_FAIL_SOURCES = {
        "crl-and-ocsp-unreachable": ("unreachable", "unreachable"),
        "ocsp-unreachable-no-crl": (None, "unreachable"),
        "crl-unreachable-no-ocsp": ("unreachable", None),
        "crl-unreadable-no-ocsp": ("unreadable", None),
        "crl-unreadable-and-ocsp-unreachable": ("unreadable", "unreachable"),
    }

    @pytest.mark.parametrize("endpoint", ["payload", "notification"])
    @pytest.mark.parametrize("sources", list(SOFT_FAIL_SOURCES))
    def test_an_unreachable_revocation_source_soft_fails(self, api, issued, pki, business_now,
                                                         payee_certificate, endpoint, sources):
        """X9-LOC-307 — when the revocation sources a certificate names cannot be reached, or
        cannot be read, the payer is accepted (soft-fail), on both payer endpoints.

        S4.3-AC1, unreachable revocation source (soft-fail). "Unreachable" is a source the
        certificate names, on a port where nothing listens; "unreadable" a CRL distribution point
        that answers 200 with an HTML error page instead of a CRL.

        Source: Ours. ADR-0027, "Revocation, parity with Java", step 4: soft-fail when neither
          answers; a named source that cannot be read soft-fails like one that cannot be reached
          (owner, 2026-11-15).
        Why: A CA's CRL server being down must not stop every payment of every PSP it issued,
          whether it refuses the connection or a proxy in front of it answers with an error page.
          (A certificate naming NO source is a different case, X9-LOC-308.)
        """
        crl_kind, ocsp_kind = self.SOFT_FAIL_SOURCES[sources]
        crl = {"unreachable": unique(ocsp.UNREACHABLE + "/crl"),
               "unreadable": pki["crl"].unreadable_url(), None: None}[crl_kind]
        ocsp_url = unique(ocsp.UNREACHABLE + "/ocsp") if ocsp_kind == "unreachable" else None
        identity = ocsp.issue(pki["ca"], f"soft-{uuid.uuid4().hex[:8]}", "Blackbox Payer Soft",
                              crl_url=crl, ocsp_url=ocsp_url)
        signer = jws.Signer(identity, now_ms=business_now)

        if endpoint == "payload":
            response = fetch(api, issued["location"]["id"], issued["qrCode"], signer=signer)
            assert response.status == 200, f"soft-fail: the payload is served: {response}"
        else:
            signed(api.notify(issued["id"], signer=signer), payee_certificate, business_now, 200)

    @pytest.mark.parametrize("endpoint", ["payload", "notification"])
    @pytest.mark.parametrize("revoked", [False, True], ids=["good", "revoked"])
    def test_ocsp_decides_when_the_crl_cannot_be_read(self, api, issued, pki, responder,
                                                      business_now, payee_certificate, endpoint,
                                                      revoked):
        """X9-LOC-311 — a payer certificate whose CRL answers with something that is not a CRL
        is checked by its OCSP responder, as when the CRL cannot be reached: "good" is accepted,
        "revoked" refused, on both payer endpoints.

        S4.3-AC1, revoked certificate (OCSP), behind an unreadable CRL.

        Source: Ours. ADR-0027: PREFER_CRLS, and OCSP when no CRL is obtainable; an unreadable
          CRL is not obtainable (owner, 2026-11-15).
        Why: The limit of the soft-fail X9-LOC-307 adds: an unreadable CRL must hand over to
          OCSP, never accept on its own, or a revoked PSP behind a broken CRL server would pay.
        """
        identity = ocsp.issue(pki["ca"], f"unreadable-{uuid.uuid4().hex[:8]}",
                              "Blackbox Payer Unreadable CRL", responder=responder,
                              crl_url=pki["crl"].unreadable_url())
        if revoked:
            responder.revoke(identity)
        signer = jws.Signer(identity, now_ms=business_now)
        queries = responder.queries

        if endpoint == "payload":
            response = fetch(api, issued["location"]["id"], issued["qrCode"], signer=signer)
            if revoked:
                assert_payload_refused(response, naming=SIGNATURE_REFUSED)
            else:
                assert response.status == 200, response
        else:
            response = api.notify(issued["id"], signer=signer)
            if revoked:
                body = signed(response, payee_certificate, business_now, 401)
                assert body.get("signatureProblem") == "UNTRUSTED_CHAIN", body
                assert api.status_of(issued["id"]) == "ACTIVE"
            else:
                signed(response, payee_certificate, business_now, 200)
        assert responder.queries > queries, "the service must have asked the OCSP responder"

    # mode of x9client.ocsp.OcspResponder -> what ADR-0027 says the service answers
    OCSP_ANSWERS_REFUSED = [
        "malformedRequest", "sigRequired", "unauthorized",
        "unauthorised-signer", "delegated-no-eku",
        "next-update-passed", "this-update-future",
        "other-certificate",
    ]
    OCSP_ANSWERS_ACCEPTED = ["tryLater", "internalError", "delegated"]

    def _payer_whose_responder_answers(self, pki, responder, mode, business_now):
        """A payer naming only ``responder`` (no CRL), which answers for it as ``mode``."""
        identity = ocsp.issue(pki["ca"], f"ocsp-{mode}-{uuid.uuid4().hex[:8]}",
                              f"Blackbox Payer OCSP {mode}", responder=responder)
        responder.answer(identity, mode)
        return jws.Signer(identity, now_ms=business_now)

    @pytest.mark.parametrize("endpoint", ["payload", "notification"])
    @pytest.mark.parametrize("mode", OCSP_ANSWERS_REFUSED)
    def test_an_ocsp_answer_adr_0027_refuses_is_refused(self, api, issued, pki, responder,
                                                        business_now, payee_certificate,
                                                        endpoint, mode):
        """X9-LOC-313 — an OCSP answer that is an answer, but not one the service can rely on, is
        refused, not soft-failed, on both payer endpoints: an error status other than
        ``tryLater`` or ``internalError`` (``malformedRequest``, ``sigRequired``,
        ``unauthorized``); an answer signed by a responder that is neither the issuer nor
        authorised by it; a delegated responder without ``OCSPSigning``; an out-of-date answer
        (``nextUpdate`` passed, or ``thisUpdate`` an hour in the future); an answer with no
        response for the certificate asked about.

        The certificate names no CRL, so OCSP is the only source, and the responder (the suite's
        ``x9client.ocsp``) answers each way on purpose.

        Source: Ours. ADR-0027, "OCSP answers that are refused, not soft-failed" (owner,
          2026-11-15), in the JDK and in Go's ``internal/pki`` alike. R-ADR0027-06.
        Why: Soft-fail forgives a responder that cannot be reached. A responder that answers, but
          with something unusable, is a different thing: forgiving it would let anyone on the
          path, or a misconfigured responder, turn every revocation check into "accepted".
        """
        signer = self._payer_whose_responder_answers(pki, responder, mode, business_now)
        queries = responder.queries

        if endpoint == "payload":
            response = fetch(api, issued["location"]["id"], issued["qrCode"], signer=signer)
            assert_payload_refused(response, naming=SIGNATURE_REFUSED)
        else:
            body = signed(api.notify(issued["id"], signer=signer), payee_certificate,
                          business_now, 401)
            assert body.get("signatureProblem") == "UNTRUSTED_CHAIN", body
            assert api.status_of(issued["id"]) == "ACTIVE"
        assert responder.queries > queries, "the service must have asked the OCSP responder"

    @pytest.mark.parametrize("endpoint", ["payload", "notification"])
    @pytest.mark.parametrize("mode", OCSP_ANSWERS_ACCEPTED)
    def test_an_ocsp_answer_adr_0027_forgives_is_accepted(self, api, issued, pki, responder,
                                                          business_now, payee_certificate,
                                                          endpoint, mode):
        """X9-LOC-314 — the controls of X9-LOC-313: ``tryLater`` and ``internalError`` soft-fail,
        and a responder the issuer delegated with ``OCSPSigning`` is relied on, on both payer
        endpoints.

        Source: Ours. ADR-0027, "OCSP answers that are refused, not soft-failed": ``tryLater``
          and ``internalError`` soft-fail (a responder saying it cannot answer now is a
          responder that cannot be reached); RFC 6960 §4.2.2.2 for the delegated responder.
        Why: Without these, every refusal of X9-LOC-313 could pass against a service that
          refuses every OCSP answer except the issuer's own "good"; and the delegated case shows
          that X9-LOC-313's "delegated-no-eku" is refused for the missing key usage, not for
          being delegated.
        """
        signer = self._payer_whose_responder_answers(pki, responder, mode, business_now)
        queries = responder.queries

        if endpoint == "payload":
            response = fetch(api, issued["location"]["id"], issued["qrCode"], signer=signer)
            assert response.status == 200, response
        else:
            signed(api.notify(issued["id"], signer=signer), payee_certificate, business_now, 200)
        assert responder.queries > queries, "the service must have asked the OCSP responder"

    def test_an_x5u_that_only_mentions_the_own_host_is_not_fetched_over_http(
            self, api, issued, pki, business_now, payee_certificate):
        """X9-LOC-312 — an ``x5u`` on another host, over plain http, that mentions this
        deployment's own host in its query string is not fetched over http: the signer's
        certificate is unavailable and the notification is refused with
        ``CERTIFICATE_UNAVAILABLE``.

        Source: Ours. ADR-0027, "Certificate fetching": HTTPS only, except the deployment's own
          host, which is a URL's host exactly, not a string anywhere in it (BUG-005).
        Why: The URL is unauthenticated input. Read over plain http, anyone on the path could
          hand the service a certificate of their choosing. The suite serves a genuine chain at
          that URL over http: a service that fetched it would accept the payer.
        """
        own_host = urllib.parse.urlsplit(issued["location"]["endpoint"]).netloc
        assert own_host, issued["location"]
        payer = pki["a"]
        chain = b"".join(c.public_bytes(serialization.Encoding.PEM) for c in payer.chain)
        url, path = pki["crl"].publish(chain, "application/pem-certificate-chain",
                                       query=f"h={own_host}")
        signer = jws.Signer(payer, now_ms=business_now)
        token = signer.sign(samples.notification(issued["id"]), x5c=False, extra={"x5u": url})

        body = signed(api.send_notification(token), payee_certificate, business_now, 401)

        assert body.get("signatureProblem") == "CERTIFICATE_UNAVAILABLE", body
        assert pki["crl"].published_fetches[path] == 0, "the chain must not be fetched over http"
        assert api.status_of(issued["id"]) == "ACTIVE"

    def test_a_certificate_naming_no_revocation_source_is_refused(self, api, issued, pki,
                                                                  business_now):
        """X9-LOC-308 — a CA-issued payer certificate with neither a CRL distribution point nor
        an OCSP responder is refused.

        S4.3-AC1 (the limit of soft-fail).

        Source: Mechanism. The JDK's SOFT_FAIL forgives a source that cannot be reached, not one
          that does not exist (the test CA's README says so); ADR-0027 asks Go for parity.
        Why: The line between X9-LOC-307 and this one is exactly where a Go port would most
          likely differ.
        """
        identity = ocsp.issue(pki["ca"], f"none-{uuid.uuid4().hex[:8]}", "Blackbox Payer None")

        response = fetch(api, issued["location"]["id"], issued["qrCode"],
                         signer=jws.Signer(identity, now_ms=business_now))

        assert_payload_refused(response, naming=SIGNATURE_REFUSED)

    @pytest.mark.serial
    def test_an_expired_method_is_dropped_and_the_others_stay(self, api, clock,
                                                              payee_certificate):
        """X9-LOC-309 — once one payment method's validUntil has passed, the payload omits it and
        still serves the others.

        S4.3-AC1, an expired method dropped while others stay. Two methods in one peg group: USD
        on FedNow until an hour from now, USDC on Solana far beyond; the test clock then moves
        one second past the first.

        Source: ANSI X9.150 — each payment method carries its own validUntil. Conformance; that
          the payload drops it, rather than refusing the fetch, is Ours.
        Why: A payer must never be offered a method the payee will refuse in the notification;
          and one lapsed method must not take the whole QR Code down with it.
        """
        ends = (clock.now() + datetime.timedelta(hours=1)).replace(microsecond=0)
        body = samples.payment_request()
        body["paymentMethods"] = [
            samples.payment_method(currency="USD", valid_until=ends.strftime("%Y-%m-%dT%H:%M:%SZ"),
                                   networks={"fednow": FEDNOW}),
            samples.payment_method(currency="USDC"),
        ]
        created = api.post("/api/v1/payment-request", body=body)
        assert created.status == 201, created

        def methods():
            response = fetch(api, created.body["location"]["id"], created.body["qrCode"])
            assert response.status == 200, response
            _, claims = jws.verify(response.text, payee_certificate, now_ms=clock.now_millis)
            return [m["currency"] for m in claims["paymentMethods"]]

        assert methods() == ["USD", "USDC"], "both are served while both are valid"
        clock.move_to(ends + datetime.timedelta(seconds=1))
        assert methods() == ["USDC"]

    @pytest.mark.parametrize("status, reason", [("PAID", "has already been paid"),
                                                ("CANCELLED", "was cancelled by the biller")])
    def test_a_closed_qr_code_serves_no_payload_and_says_why(self, api, issued, status, reason):
        """X9-LOC-310 — a PAID or CANCELLED QR Code serves no payload, and the refusal names which.

        S4.3-AC1, PAID and CANCELLED codes. The request carries the issued content, so the status
        rule is what refuses it (X9-LIFE-124 signs placeholder content, so there the content
        check refuses first).

        Source: Ours. ADR-0020: payers are held strictly.
        Why: "Already paid" means stop; "cancelled" means go back to the biller. A payer cannot
          act on a refusal that does not say which.
        """
        if status == "PAID":
            assert api.mark_paid(issued["id"]).status == 200
        else:
            assert api.set_status(issued["id"], "CANCELLED").status == 200

        response = fetch(api, issued["location"]["id"], issued["qrCode"])

        assert_payload_refused(response, naming=reason)


# ================================================================== group E: notifications

class TestNotifications:
    """S4.3-AC2 — payment notifications under the pre-commit / post-commit rule (ADR-0036)."""

    @pytest.mark.parametrize("rail, network, currency", [
        ("solana", "SOLANA", "usdc"), ("fednow", "FedNow", "usd"), ("rtp", "RTP", "Usd"),
        ("ach", "Ach", "uSD")])
    def test_rail_and_currency_in_another_case_are_accepted_and_echoed(
            self, api, events, payee_certificate, business_now, rail, network, currency):
        """X9-HOLD-401 — a rail and a currency spelled in another case are accepted, and echoed as
        the payer sent them, in the stored notification and in the event.

        S4.3-AC2, rail and currency in another case (accepted, echoed).

        Source: Ours. INTERPRETATION: network names are read case-insensitively. Mechanism for
          the echo: Java stores and publishes the payer's own spelling, it does not normalise.
        Why: A consumer matching events against its own records by currency or network must
          know whether to expect "usdc" or "USDC". Whichever it is, both builds must agree.
        """
        qr = api.create_qr(**RAILS[rail]["create"])
        notify = {**RAILS[rail]["notify"], "network": network, "currency": currency}

        signed(api.notify(qr, **notify), payee_certificate, business_now, 200)

        stored = api.qr(qr).body
        assert stored["status"] == "PAYMENT_INITIATED"
        payment = stored["paymentNotification"]["data"]["payment"]
        assert (payment["network"], payment["currency"]) == (network, currency), payment
        initiated, = events.for_qr(qr, until=bool, timeout=60)
        assert initiated["type"] == "payment.initiated"
        assert (initiated["network"], initiated["currency"]) == (network, currency), initiated

    @pytest.mark.parametrize("rail", sorted(RAILS))
    def test_a_post_commit_never_marks_paid(self, api, events, payee_certificate, business_now,
                                            rail):
        """X9-HOLD-402 — no post-commit marks a QR Code PAID: not the holder's, not a second one
        with another transaction id, not one on an ACTIVE QR Code.

        S4.3-AC2, SENT never marks PAID.

        Source: Ours. ADR-0036: "Only the payment system marks a QR Code PAID. This software
          never does."
        Why: X9.150 never sees funds arrive. A PAID set on a payer's word would close the bill on
          a claim nobody checked, and payment.cleared would tell the payment system so.
        """
        extra = {"action": "SENT"} if rail == "solana" else {}
        notify = {**RAILS[rail]["notify"], **extra}
        held = api.create_qr(**RAILS[rail]["create"])
        signed(api.notify(held, **RAILS[rail]["notify"]), payee_certificate, business_now, 200)
        signed(api.notify(held, transaction_id=TX_HASH, **notify), payee_certificate,
               business_now, 200)
        signed(api.notify(held, transaction_id=TX_2, **notify), payee_certificate,
               business_now, 200)
        active = api.create_qr(**RAILS[rail]["create"])
        signed(api.notify(active, transaction_id=TX_HASH, **notify), payee_certificate,
               business_now, 200)

        for qr in (held, active):
            seen = [e["type"] for e in events.for_qr(qr, timeout=60)]
            assert "payment.cleared" not in seen, seen
            assert api.status_of(qr) == "PAYMENT_INITIATED"

    def test_get_shows_the_holders_not_sent_after_a_hash_is_registered(
            self, api, payee_certificate, business_now):
        """X9-HOLD-504 — the holder's ``NOT_SENT``, accepted once a hash is registered, is what GET
        shows as ``paymentNotification.data``; the QR Code stays ``PAYMENT_INITIATED``.

        S11.4-AC1.

        Source: Ours. ADR-0036: every accepted notification is recorded, and GET shows the latest
          (ADR-0028, known difference 16). That it changes nothing else is ``X9-MAP-043``.
        Why: The payer was told 200. A notification accepted and then not recorded leaves the payee
          reading an earlier message as the payer's last word, while the payer believes it said
          something else.
        """
        payer = "holder-not-sent@example.com"
        qr = api.create_qr()
        signed(api.notify(qr, payer_info=payer), payee_certificate, business_now, 200)
        signed(api.notify(qr, payer_info=payer, action="SENT", transaction_id=TX_HASH),
               payee_certificate, business_now, 200)

        signed(api.notify(qr, payer_info=payer, action="NOT_SENT"), payee_certificate,
               business_now, 200)

        stored = api.qr(qr).body
        assert stored["status"] == "PAYMENT_INITIATED", stored
        data = stored["paymentNotification"]["data"]
        assert data["blockchain"]["action"] == "NOT_SENT", data
        assert "transactionId" not in data["payment"], data

    def test_a_qr_code_without_payment_notification_refuses_notifications(
            self, api, events, payee_certificate, business_now):
        """X9-HOLD-403 — a QR Code created without ``paymentNotification`` refuses every payer
        notification, signed, and stays ACTIVE.

        S4.3-AC2, no paymentNotification.

        Source: Ours. ``paymentNotification`` is optional at create; without it the payee
          announced no notification endpoint, so there is nothing to accept.
        Why: Accepting would reserve a QR Code whose payee never asked to be told, and the
          payer's announcement would hold it against everybody else.
        """
        body = samples.payment_request()
        del body["paymentNotification"]
        created = api.post("/api/v1/payment-request", body=body)
        assert created.status == 201, created
        qr = created.body["id"]

        response = api.notify(qr)

        signed(response, payee_certificate, business_now, 400)
        assert_refused(response, naming="paymentNotification", status=400)
        assert api.status_of(qr) == "ACTIVE"
        assert events.none_for(qr) == []

    def test_a_qr_code_with_an_external_notification_endpoint_refuses_notifications(
            self, api, events, payee_certificate, business_now):
        """X9-HOLD-404 — ``paymentNotification.kind: EXTERNAL`` sends payers elsewhere: the
        payload names the external endpoint, and a notification sent here is refused, signed.

        S4.3-AC2, kind EXTERNAL.

        Source: Ours. EXTERNAL means another service receives this QR Code's notifications.
        Why: Two services accepting announcements for one QR Code would hold two reservations
          that know nothing of each other.
        """
        endpoint = "https://payee.example.com/pub/api/v1/payment-notification"
        created = api.post("/api/v1/payment-request", body=samples.payment_request(
            paymentNotification={"kind": "EXTERNAL", "endpoint": endpoint}))
        assert created.status == 201, created
        qr = created.body["id"]

        payload = fetch(api, created.body["location"]["id"], created.body["qrCode"])
        assert payload.status == 200, payload
        _, claims = jws.verify(payload.text, payee_certificate, now_ms=business_now)
        assert claims["paymentNotification"] == endpoint, claims["paymentNotification"]

        response = api.notify(qr)

        signed(response, payee_certificate, business_now, 400)
        assert_refused(response, naming="paymentNotification.kind", status=400)
        assert api.status_of(qr) == "ACTIVE"
        assert events.none_for(qr) == []

    @pytest.mark.parametrize("missing", ["payer", "payer.info", "expectedDate"])
    def test_an_ach_notification_without_payer_info_or_expected_date_is_refused(
            self, api, events, payee_certificate, business_now, missing):
        """X9-HOLD-407 — an ACH notification without ``payer.info`` (no payer at all, or a payer
        with no info) or without ``expectedDate`` is refused, signed, and reserves nothing.

        Source: Ours. STATE-MACHINE.md (``payer.info`` and ``expectedDate`` on ACH); ADR-0036
          ("ACH already requires ``payer.info``").
        Why: An ACH transfer settles a day or more later. Without ``expectedDate`` nobody knows
          when to look for the money; without ``payer.info`` the holder cannot be told apart from
          the next payer (ADR-0036: no info is not the same payer), so a reservation taken on it
          could be neither refreshed nor given back by the one who took it.
        """
        qr = api.create_qr(**RAILS["ach"]["create"])
        body = samples.notification(qr, **RAILS["ach"]["notify"])
        if missing == "payer":
            del body["payer"]
        elif missing == "payer.info":
            body["payer"] = {}
        else:
            del body["expectedDate"]

        response = api.send_notification(api.sign(body))

        signed(response, payee_certificate, business_now, 400)
        assert_refused(response, naming="payer.info" if missing.startswith("payer")
                       else "ExpectedDate", status=400)
        assert api.status_of(qr) == "ACTIVE", "a refused pre-commit reserves nothing"
        assert events.none_for(qr) == []
        control = api.notify(qr, **RAILS["ach"]["notify"])
        assert control.status == 200, f"the control: with both, the same notification is " \
                                      f"accepted: {control}"

    @pytest.mark.parametrize("case, status, naming", [
        ("wrong-amount", 400, "payment.amount"),
        ("tip-outside-range", 400, "payment.tipAmount"),
        ("unoffered-currency", 400, "currency"),
        ("destination-not-on-the-qr-code", 400, "destination address"),
        ("second-payer", 409, "Another payer holds this QR Code"),
        ("paid", 400, ""),
        ("unknown-qr-code", 404, ""),
        ("unsigned", 401, "signatureProblem"),
    ])
    def test_every_answer_is_signed(self, api, payee_certificate, business_now, case, status,
                                    naming):
        """X9-HOLD-405 — every answer to a payer is a JWS signed by the payee, a refusal as much
        as a yes, with its HTTP status as the signed statusCode.

        S4.3-AC2, every answer signed (Story 2.3), across the refusals of group E.

        Source: ANSI X9.150 A.4.5 note; INTERPRETATION I-12. Conformance.
        Why: An unsigned no can be forged by anyone on the path to make a payer give up, or to
          make it retry into a double payment.
        """
        tip = {"allowed": True, "range": {"min": 0, "max": 20}}
        qr = api.create_qr(tip=tip) if case == "tip-outside-range" else api.create_qr()
        if case == "second-payer":
            signed(api.notify(qr, payer_info="first@example.com"), payee_certificate,
                   business_now, 200)
        if case == "paid":
            assert api.mark_paid(qr).status == 200
        kwargs = {
            "wrong-amount": {"amount": BILL + 1},
            "tip-outside-range": {"amount": BILL + 300, "tip": 300},
            "unoffered-currency": {"currency": "USD"},
            "destination-not-on-the-qr-code": {"recipient": samples.PAYER_WALLET},
            "second-payer": {"payer_info": "second@example.com"},
        }.get(case, {})

        if case == "unsigned":
            response = api.send_notification("not-a-jws")
        elif case == "unknown-qr-code":
            response = api.notify("0" * 32)
        else:
            response = api.notify(qr, **kwargs)

        body = signed(response, payee_certificate, business_now, status)
        if naming:
            assert naming in str(body), body

    def test_32_concurrent_pre_commits_reserve_once(self, api, events, pki, business_now,
                                                    payee_certificate):
        """X9-HOLD-406 — 32 payers announcing one QR Code at the same instant: exactly one 200,
        31 signed 409s, exactly one payment.initiated, and the winner holds it.

        S4.3-AC2, 32 concurrent pre-commit announcements.

        Every token is signed before any is sent, and a barrier releases the 32 requests
        together, so they race inside the service rather than in this process. The assertion
        does not depend on who wins or in what order the answers arrive.

        Source: Ours. ADR-0021 / ADR-0036: the reservation is what stops two payers paying one
          QR Code; a lost race is 409, never 500 (ADR-0036, "Losing a concurrent write").
        Why: Two 200s here is two payers each told to move the money for one bill.
        """
        qr = api.create_qr()
        payers = [f"racer-{i:02d}@example.com" for i in range(32)]
        signer = jws.Signer(pki["a"], now_ms=business_now)
        tokens = [signer.sign(samples.notification(qr, payer_info=info)) for info in payers]
        barrier = threading.Barrier(len(tokens))
        answers = [None] * len(tokens)

        def announce(i):
            barrier.wait(timeout=30)
            answers[i] = api.send_notification(tokens[i])

        threads = [threading.Thread(target=announce, args=(i,)) for i in range(len(tokens))]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join(timeout=90)

        statuses = collections.Counter(a.status for a in answers if a is not None)
        assert statuses == {200: 1, 409: 31}, f"exactly one winner: {statuses}"
        for answer in answers:
            signed(answer, payee_certificate, business_now, answer.status)
        winner = payers[next(i for i, a in enumerate(answers) if a.status == 200)]
        # Wait as long as the outbox drain may need under load, then long enough again for a
        # second event that must not exist to have appeared.
        events.for_qr(qr, until=bool, timeout=60)
        time.sleep(3)
        seen = [e for e in events.read_all()[0] if e.get("qrCodeId") == qr]
        assert [e["type"] for e in seen] == ["payment.initiated"], seen
        assert seen[0]["payerInfo"] == winner, seen[0]
        assert api.status_of(qr) == "PAYMENT_INITIATED"


# ================================================================== group H: payer-side endpoints

class TestPayerSideEndpoints:
    """S4.3-AC3 — this service as the payer's agent (ADR-0015): it signs and delivers, and reports
    the payee's verdict without acting on it."""

    def test_a_payee_refusal_is_reported_not_raised(self, api, peer, events):
        """X9-SIG-301 — the payee answers a signed 409: this service answers 200 with
        ``accepted: false`` and the payee's status, and changes nothing here.

        S4.3-AC3, the payee refuses → 200 + accepted: false. (The signed 400 is case 16 of
        ``test_payee_answers.py``; this is the conflict a payer most often meets.)

        Source: Ours. ADR-0015: the 200 says the payee answered, not that it agreed.
        Why: Raising the payee's 409 as this service's own error would make a payer retry a
          refusal, or treat a transport failure and a "no" alike.
        """
        peer.answer = lambda question: peer.signed(question, 409)
        qr = api.create_qr()

        response = api.post(PRE, body=peer_module.outbound("pre-payment", endpoint=peer.endpoint,
                                                           qr_id=qr))

        assert response.status == 200, response
        result = response.body
        assert (result["accepted"], result["statusCode"], result["signature"]) == \
            (False, 409, "VERIFIED"), result
        assert api.status_of(qr) == "ACTIVE" and events.none_for(qr, wait=1.5) == []

    @pytest.mark.parametrize("phase", ["pre-payment", "post-payment"])
    def test_an_unreachable_payee_is_502(self, api, phase):
        """X9-SIG-302 — nothing listens at the payee's endpoint: 502, a problem, no verdict.

        S4.3-AC3, the payee is unreachable → 502. (A payee that hangs up, or answers after the
        15 s read timeout, is also 502: ``test_payee_answers.py``.)

        Source: Ours. ADR-0015: a transport failure is not an answer.
        Why: A 200 with ``accepted: false`` here would tell the payer "no" when the truth is
          "nobody answered": one stops, the other retries.
        """
        endpoint = unique(ocsp.UNREACHABLE + "/pub/api/v1/payment-notification")
        body = peer_module.outbound(phase, endpoint=endpoint, qr_id=api.create_qr(),
                                    transaction_id=TX_HASH if phase == "post-payment" else None)

        response = api.post(PRE if phase == "pre-payment" else POST, body=body)

        assert response.status == 502, response
        assert response.is_problem and "accepted" not in response.body, response

    def test_a_pre_payment_carrying_a_transaction_id_is_refused_before_sending(self, api, peer):
        """X9-SIG-303 — a pre-payment with a ``transactionId`` is refused, 400 naming it, and
        nothing is delivered.

        S4.3-AC3, ADR-0015.

        Source: Ours. ADR-0004 / ADR-0036: the payee infers the phase from the transactionId, so
          a pre-payment carrying one would arrive as a post-commit.
        Why: The payer would believe it asked "may I pay?" while the payee recorded "I paid":
          no reservation judged, and a second payer free to pay too.
        """
        body = peer_module.outbound("pre-payment", endpoint=peer.endpoint, qr_id=api.create_qr())
        body["notification"]["payment"]["transactionId"] = TX_HASH

        response = api.post(PRE, body=body)

        assert_refused(response, naming="payment.transactionId", status=400)
        assert peer.received == [], "refused before sending"

    @pytest.mark.parametrize("action, delivered", [("SENT", False), ("NOT_SENT", True)])
    def test_a_post_payment_needs_a_transaction_id_unless_it_gives_the_qr_code_back(
            self, api, peer, action, delivered):
        """X9-SIG-304 — a post-payment reporting a payment (SENT) without a ``transactionId`` is
        refused before sending; NOT_SENT, which reports that no payment happened, is delivered.

        S4.3-AC3, ADR-0015 (the mirror of X9-SIG-303).

        Source: Ours. ADR-0036: a committed transaction without its id cannot exist; NOT_SENT by
          its nature has none.
        Why: A report with no reference gives the payee nothing to reconcile; and refusing
          NOT_SENT for lacking an id would leave the payer no way to give a reservation back.
        """
        body = peer_module.outbound("post-payment", endpoint=peer.endpoint, qr_id=api.create_qr())
        body["notification"]["blockchain"]["action"] = action

        response = api.post(POST, body=body)

        if delivered:
            assert response.status == 200, response
            assert len(peer.received) == 1
        else:
            assert_refused(response, naming="payment.transactionId", status=400)
            assert peer.received == [], "refused before sending"


# ------------------------------------------------------------------ a rail or currency not offered

NOT_OFFERED_E2E = "E2E-BLACKBOX-NOT-OFFERED-1"
RIWS = "RECEIVED_IN_WRONG_STATUS"


def exactly(events, qr, count, *, settle=2.0, timeout=60.0):
    """Every event for ``qr``, once ``count`` have appeared and a settle has passed, so an event
    that should not exist has had its chance to appear too."""
    if count:
        events.for_qr(qr, until=lambda es: len(es) >= count, timeout=timeout)
    time.sleep(settle)
    return [e for e in events.read_all()[0] if e.get("qrCodeId") == qr]


def fednow_only(api):
    return api.create_qr(**RAILS["fednow"]["create"])


def snapshot(api, qr):
    response = api.qr(qr)
    body = response.body
    return {"status": body.get("status"), "revision": body.get("revision"),
            "revisedAt": body.get("revisedAt"),
            "paymentNotification": body.get("paymentNotification"), "etag": response.etag}


class TestNotOffered:
    """ADR-0036, amended 2026-11-15 (owner): a notification naming a rail or a currency the QR
    Code does not offer. A pre-commit is refused, nothing recorded. A post-commit is "an error —
    we record the call just in case, but we return an error": a signed 400, one ``payment.sent``
    carrying ``NOT_OFFERED_RAIL`` and/or ``NOT_OFFERED_CURRENCY``, and the QR Code unchanged."""

    @pytest.mark.parametrize("published, notified", [
        ("fednow", "rtp"), ("fednow", "ach"), ("rtp", "fednow"), ("ach", "fednow")])
    def test_a_pre_commit_on_a_rail_the_qr_code_does_not_offer_is_refused(
            self, api, events, payee_certificate, business_now, published, notified):
        """X9-RAIL-520 — a pre-commit on a rail the QR Code does not offer is a signed 400 naming
        the rail, each rail on its own (RTP on a FedNow-only QR Code included); nothing recorded.

        Source: Ours. ADR-0036, amended 2026-11-15 (owner). ANSI X9.150-2026 §14.5 gives each
          rail its own object.
        Why: A pre-commit asks "may I pay this way?" before money moves; a way the biller never
          published is a no. Java grouped FedNow and RTP as "instant payments".
        """
        qr = api.create_qr(**RAILS[published]["create"])
        notify = RAILS[notified]["notify"]
        before = snapshot(api, qr)

        body = signed(api.notify(qr, **notify), payee_certificate, business_now, 400)

        assert f"does not offer the {notify['network']} rail" in str(body), body
        assert snapshot(api, qr) == before
        assert exactly(events, qr, 0) == []

    def test_a_pre_commit_in_a_currency_the_qr_code_does_not_offer_is_refused(
            self, api, events, payee_certificate, business_now):
        """X9-CUR-520 — a pre-commit in a currency the QR Code does not offer is a signed 400 naming
        the currency, and with an unoffered rail too, naming both; nothing recorded.

        Source: Ours. ADR-0036, amended 2026-11-15 (owner).
        Why: The payer must be told every part of its message that was wrong, in the words a
          post-commit gets too.
        """
        qr = fednow_only(api)
        before = snapshot(api, qr)

        currency = signed(api.notify(qr, **{**RAILS["fednow"]["notify"], "currency": "EUR"}),
                          payee_certificate, business_now, 400)
        both = signed(api.notify(qr, **{**RAILS["rtp"]["notify"], "currency": "EUR"}),
                      payee_certificate, business_now, 400)

        assert "does not offer the EUR currency" in str(currency), currency
        assert "does not offer the rtp rail nor the EUR currency" in str(both), both
        assert snapshot(api, qr) == before
        assert exactly(events, qr, 0) == []

    def test_a_post_commit_on_a_rail_the_qr_code_does_not_offer_is_recorded_and_refused(
            self, api, events, payee_certificate, business_now):
        """X9-RAIL-521 — a post-commit on a rail the QR Code does not offer (RTP on FedNow-only) is
        a signed 400 naming the rail; one payment.sent records it, flagged NOT_OFFERED_RAIL, with
        its reference and receivedInStatus; the QR Code is unchanged and still open.

        Source: Ours. ADR-0036, amended 2026-11-15 (owner): "It's an error — we record the call
          just in case, but we return an error."
        Why: The money may have moved, so the payment system needs the reference; the payer
          needs to hear it was wrong; and the QR Code must not be reserved by a payment on a rail
          the biller never published.
        """
        qr = fednow_only(api)
        before = snapshot(api, qr)

        body = signed(api.notify(qr, transaction_id=NOT_OFFERED_E2E, **RAILS["rtp"]["notify"]),
                      payee_certificate, business_now, 400)

        assert "does not offer the rtp rail" in str(body), body
        assert snapshot(api, qr) == before, "nothing about the QR Code changes"
        sent, = exactly(events, qr, 1)
        assert sent["type"] == "payment.sent", sent
        assert sent["transactionId"] == NOT_OFFERED_E2E, sent
        assert sent["network"] == "rtp", sent
        assert sent["receivedInStatus"] == "ACTIVE", sent
        assert sorted(sent["deviations"]) == sorted([RIWS, "NOT_OFFERED_RAIL"]), sent
        assert sent["schemaVersion"] == "1.1", sent
        signed(api.notify(qr, payer_info="honest@example.com", **RAILS["fednow"]["notify"]),
               payee_certificate, business_now, 200)

    def test_the_holders_post_commit_in_a_currency_not_offered_is_recorded_and_refused(
            self, api, events, payee_certificate, business_now):
        """X9-CUR-521 — the holder's post-commit in a currency the QR Code does not offer is a
        signed 400 naming the currency; one payment.sent records its hash, flagged
        NOT_OFFERED_CURRENCY; the reservation is not touched and no hash is registered against it.

        Source: Ours. ADR-0036, amended 2026-11-15 (owner).
        Why: Java checked the currency only at the pre-commit gate, so this report registered its
          hash and froze the reservation as if the QR Code had been paid.
        """
        qr = api.create_qr()
        signed(api.notify(qr), payee_certificate, business_now, 200)
        before = snapshot(api, qr)

        body = signed(api.notify(qr, currency="EUR", action="SENT", transaction_id=TX_HASH),
                      payee_certificate, business_now, 400)

        assert "does not offer the EUR currency" in str(body), body
        assert snapshot(api, qr) == before
        initiated, sent = exactly(events, qr, 2)
        assert (initiated["type"], sent["type"]) == ("payment.initiated", "payment.sent")
        assert sent["transactionId"] == TX_HASH, sent
        assert sent["currency"] == "EUR", sent
        assert sent["receivedInStatus"] == "PAYMENT_INITIATED", sent
        assert sent["deviations"] == ["NOT_OFFERED_CURRENCY"], sent
        signed(api.notify(qr), payee_certificate, business_now, 200)  # no hash registered: a retry

    def test_a_post_commit_offering_neither_carries_both_codes_and_a_paid_qr_code_stays_paid(
            self, api, events, payee_certificate, business_now):
        """X9-RAIL-522 — a post-commit naming neither an offered rail nor an offered currency is a
        signed 400 naming both; its payment.sent carries both codes, and a PAID QR Code stays PAID.

        Source: Ours. ADR-0036, amended 2026-11-15 (owner).
        Why: The payment system decides what to do with the money from the codes, so it must be
          told everything that was wrong; a closed QR Code never reopens.
        """
        qr = fednow_only(api)
        assert api.mark_paid(qr, network="fednow").status == 200
        exactly(events, qr, 1)
        before = snapshot(api, qr)

        body = signed(api.notify(qr, action="SENT", transaction_id=TX_HASH),
                      payee_certificate, business_now, 400)

        assert "does not offer the Solana rail nor the USDC currency" in str(body), body
        assert snapshot(api, qr) == before
        cleared, sent = exactly(events, qr, 2)
        assert (cleared["type"], sent["type"]) == ("payment.cleared", "payment.sent")
        assert sent["transactionId"] == TX_HASH, sent
        assert sent["receivedInStatus"] == "PAID", sent
        assert sorted(sent["deviations"]) == sorted(
            [RIWS, "NOT_OFFERED_RAIL", "NOT_OFFERED_CURRENCY"]), sent

    def test_a_currency_offered_only_on_another_rail_is_not_offered(
            self, api, events, payee_certificate, business_now):
        """X9-CUR-522 — a rail and a currency each offered, but not on the same payment method (USD
        on Solana, when the QR Code offers USD on FedNow and USDC on Solana), are not offered: a
        pre-commit is a signed 400 and records nothing; the holder's post-commit is the same signed
        400, recorded on one payment.sent flagged NOT_OFFERED_CURRENCY, the QR Code unchanged.

        Source: Ours. Owner decision of 2026-11-15, amending ADR-0036: the rail is offered, the
          currency is not offered on it.
        Why: Java registered that post-commit as an ordinary one, so its hash stopped the holder's
          window on a currency the QR Code never offered on that rail.
        """
        reason = "This QR Code does not offer the USD currency on the Solana rail."
        methods = [samples.payment_method(currency="USD", networks={"fednow": FEDNOW}),
                   samples.payment_method(currency="USDC")]

        untouched = api.create_qr(currency="USD", paymentMethods=methods)
        before = snapshot(api, untouched)
        body = signed(api.notify(untouched, currency="USD"), payee_certificate, business_now, 400)
        assert reason in str(body), body
        assert snapshot(api, untouched) == before
        assert exactly(events, untouched, 0) == []

        qr = api.create_qr(currency="USD", paymentMethods=methods)
        signed(api.notify(qr), payee_certificate, business_now, 200)
        exactly(events, qr, 1)
        before = snapshot(api, qr)

        body = signed(api.notify(qr, currency="USD", action="SENT", transaction_id=TX_HASH),
                      payee_certificate, business_now, 400)

        assert reason in str(body), body
        assert snapshot(api, qr) == before, "nothing about the QR Code changes"
        initiated, sent = exactly(events, qr, 2)
        assert (initiated["type"], sent["type"]) == ("payment.initiated", "payment.sent")
        assert sent["transactionId"] == TX_HASH, sent
        assert sent["currency"] == "USD", sent
        assert sent["receivedInStatus"] == "PAYMENT_INITIATED", sent
        assert sent["deviations"] == ["NOT_OFFERED_CURRENCY"], sent
        signed(api.notify(qr), payee_certificate, business_now, 200)  # no hash registered: a retry

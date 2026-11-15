# Copyright © 2026 Matera Systems, Inc.
# Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
"""Another bank's QR Code is read with tolerance; ours are generated strictly (ADR-0012, 2026-11-15).

The owner's words: "if we're called to decode a QR Code created by another bank, we may not reject
if there are unknown currencies / rails in paymentMethods. We parse and ignore the ones we don't
know. That's the soul of interoperability of X9.150."

Here the other bank is real enough: a host of its own (``other_bank``, ``x9client.peer`` over
https), a payload signed by its own certificate from the test CA, and an EMV string whose payload
URL names that host. The service under test fetches the payload, verifies the signature, and
answers the payer. Nothing is stubbed on the service's side.
"""
import copy

import pytest

from x9client import emv, jws

LOCATION = "B" * 32

FOREIGN = {
    "id": "0196cb82afab46f1bf8dfff2f9c8a540",
    "revision": 2,
    "createdAt": "2030-01-01T00:00:00Z",
    "revisedAt": "2030-01-01T00:00:00Z",
    "sentAt": "2030-01-01T00:00:00Z",
    "validUntil": "2030-12-31T23:59:59Z",
    "status": "ACTIVE",
    "creditor": {"name": "Banco Exemplo", "loyaltyId": "L-1"},
    "bill": {"description": "foreign", "amountDue": {"amount": 1250, "currency": "USD"}},
    "bankExtension": {"channel": "branch", "flags": [1, 2]},
    "paymentMethods": [
        {"currency": "USD", "validUntil": "2030-12-31T23:59:59Z", "amount": 1250, "priority": 1,
         "networks": {
             "ach": {"routingNumber": "021000021", "accountNumber": "1234567890",
                     "protectionType": "plaintext", "accountType": "checking"},
             "zelle": {"token": "loja@example.com"}}},
        {"currency": "BRL", "validUntil": "2030-12-31T23:59:59Z", "amount": 6500,
         "networks": {"pix": {"pixKey": "loja@example.com", "txid": "ABC123"}}},
    ],
}


@pytest.fixture
def decode(api, other_bank, pki, business_now):
    """Decode ``payload`` as served by the other bank, signed by its own key under the correlationId
    of the request it answers, as every answer must be (ADR-0027, amendment of 2026-11-15).

    ``token``, when given, is a function of the request's correlationId returning the whole answer
    instead (a tampered one). ``correlation_id`` is the caller's, sent as ``correlationID``; without
    it the service generates one. Returns the decoder's Response."""
    signer = jws.Signer(pki["payee"], now_ms=business_now)
    qr_code = emv.with_location(api.create_qr_detailed().body["qrCode"], other_bank.location(LOCATION))

    def run(payload, token=None, correlation_id=None):
        from x9client import peer as peer_module

        def answer(question):
            asked = question.correlation_id
            body = token(asked) if token is not None else signer.sign(payload, correlation_id=asked)
            return peer_module.Answer(200, body)

        other_bank.reset(answer)
        request = {"qrCode": qr_code}
        if correlation_id is not None:
            request["correlationID"] = str(correlation_id)
        return api.post("/api/v1/qrcode-emv-decoder", body=request)

    return run


def test_a_foreign_payload_with_unknown_rails_currencies_and_fields_decodes(decode, other_bank):
    """X9-DEC-501 — another bank's payload offering unknown rails (``zelle``, ``pix``), an unknown
    currency (``BRL``) and unknown fields decodes: 200, every payment method presented, in order.

    Source: Ours. ADR-0012, amendment of 2026-11-15: strict about what we generate, tolerant about
      what we read; INTERPRETATION I-9.
    Why: Refusing here would make a QR Code from any bank that offers one more rail or one more
      currency unpayable through this service, although its payer may pay the parts it knows.
    """
    response = decode(FOREIGN)

    assert response.status == 200, response
    assert other_bank.received, "the payload was fetched from the other bank"
    assert response.body["id"] == FOREIGN["id"], response.body
    assert [m["currency"] for m in response.body["paymentMethods"]] == ["USD", "BRL"], response.body


def test_an_unknown_currency_and_an_unknown_rail_are_presented_as_written(decode):
    """X9-DEC-502 — a currency this deployment does not settle, and a rail it does not interpret,
    reach the payer as the other bank wrote them.

    Source: Ours. ADR-0012, amendment of 2026-11-15: ``supported-currencies.json`` and the rail
      list govern what we generate, not what we read; R-I9-02 carries an unknown network intact.
    Why: The other bank's payer may settle BRL over Pix perfectly well; dropping either would
      remove what it needed in order to pay.
    """
    response = decode(FOREIGN)

    assert response.status == 200, response
    brl = response.body["paymentMethods"][1]
    assert brl["currency"] == "BRL" and brl["amount"] == 6500, brl
    assert brl["networks"]["pix"] == {"pixKey": "loja@example.com", "txid": "ABC123"}, brl
    assert response.body["paymentMethods"][0]["networks"]["zelle"] == {"token": "loja@example.com"}


def test_an_unknown_field_inside_a_known_rail_is_ignored(decode):
    """X9-DEC-503 — an unknown field inside a rail we interpret is ignored, and the rail's known
    fields are presented, ``protectionType`` as the other bank wrote it.

    Source: Ours. ADR-0012, amendment of 2026-11-15: the payload-side rails are open, unlike the
      ones we generate.
    Why: Closing a rail keeps our own QR Codes honest; applied to another bank's, it would refuse
      a payload for a field its owner was entitled to add.
    """
    response = decode(FOREIGN)

    assert response.status == 200, response
    ach = response.body["paymentMethods"][0]["networks"]["ach"]
    assert ach == {"routingNumber": "021000021", "accountNumber": "1234567890",
                   "protectionType": "plaintext"}, ach


def test_an_unknown_field_on_a_method_or_at_the_top_is_ignored(decode):
    """X9-DEC-504 — an unknown field on a payment method, on the creditor, or at the top of the
    payload is ignored, not refused and not passed on.

    Source: Ours. ADR-0012, amendment of 2026-11-15; INTERPRETATION I-9 (outside ``networks``
      an unknown key is not carried).
    Why: The decode must not fail on it, and must not hand it on as if X9.150 defined it.
    """
    response = decode(FOREIGN)

    assert response.status == 200, response
    assert "bankExtension" not in response.body, response.body
    assert "priority" not in response.body["paymentMethods"][0], response.body
    assert "loyaltyId" not in response.body["creditor"], response.body


def test_tolerance_stops_at_integrity(decode, pki, business_now):
    """X9-DEC-505 — the same tolerant payload is refused when its integrity fails: signed by a CA
    nobody trusts, or altered after signing. Nothing of it reaches the payer.

    Source: Ours. ADR-0012, amendment of 2026-11-15 (tolerant about content, never about
      integrity); ADR-0027 (the payload's JWS is verified before it is read).
    Why: Unknown content is safe to read only because the issuer's signature attests it. A
      payload whose signature fails is nobody's. (A malformed EMV string is X9-DEC-016.)
    """
    def untrusted(asked):
        return jws.Signer(pki["untrusted"], now_ms=business_now).sign(FOREIGN, correlation_id=asked)

    def tampered(asked):
        header, _, signature = jws.Signer(pki["payee"], now_ms=business_now) \
            .sign(FOREIGN, correlation_id=asked).split(".")
        altered = copy.deepcopy(FOREIGN)
        altered["paymentMethods"][0]["networks"]["ach"]["accountNumber"] = "9999999999"
        return ".".join([header, jws.b64u_json(altered), signature])

    for case, token in (("untrusted CA", untrusted), ("altered payload", tampered)):
        response = decode(FOREIGN, token=token)

        assert 400 <= response.status < 500, (case, response)
        assert FOREIGN["id"] not in response.text, (case, response.body)


# The decoder's refusal of a payload that does not carry our correlationId (owner, 2026-11-15):
# trying again may succeed, and nothing in the caller's input can fix it.
REFUSED = "QR Code is invalid (bad correlationId)"
INVALID = "Payload JWS signature is invalid."


def _refused(case, response, words=REFUSED):
    assert response.status == 400, (case, response)
    assert response.body.get("type") == "https://x9.matera.com/api/business-rule-violation", \
        (case, response.body)
    assert words in response.text, (case, response.body)
    assert FOREIGN["id"] not in response.text, (case, response.body)


def test_a_payload_under_another_correlation_id_is_refused(decode, pki, business_now):
    """X9-DEC-506 — a payload validly signed by the other bank, under another correlationId than the
    request was sent under, is refused, 400, "QR Code is invalid (bad correlationId)": when the
    caller gave no ``correlationID`` (the service generated one) as when it gave one. Nothing of it
    reaches the payer.

    Source: Ours. ADR-0027, amendment of 2026-11-15 (owner): "Always check that the payee's answer
      echoes our correlationId." ANSI X9.150-2026 §10.3.2 has the responder echo it. Before the
      amendment, the id was compared only when the caller gave one.
    Why: A good signature proves who wrote the payload, not that it answers our request: under
      another id it is a replay, or the payee's answer to someone else.
    """
    import uuid

    signer = jws.Signer(pki["payee"], now_ms=business_now)
    another = str(uuid.uuid4())

    def elsewhere(_asked):
        return signer.sign(FOREIGN, correlation_id=another)

    _refused("generated", decode(FOREIGN, token=elsewhere))
    _refused("the caller's", decode(FOREIGN, token=elsewhere, correlation_id=uuid.uuid4()))


def test_a_payload_without_a_correlation_id_is_refused(decode, pki, business_now):
    """X9-DEC-507 — a payload validly signed by the other bank whose header carries no
    ``correlationId`` (in ``crit`` or out of it) is refused, 400, "QR Code is invalid (bad
    correlationId)", whether or not the caller gave one.

    Source: Ours. ADR-0027, amendment of 2026-11-15 (owner): "No correlationId, I can't trust the
      other side." ANSI X9.150-2026 §10.7 step 3 requires the header.
    Why: An answer that names no request cannot be tied to ours, however good its signature.
    """
    import uuid

    signer = jws.Signer(pki["payee"], now_ms=business_now)

    def anonymous(_asked):
        return signer.sign(FOREIGN, drop=("correlationId",), crit=["iat", "ttl"])

    _refused("generated", decode(FOREIGN, token=anonymous))
    _refused("the caller's", decode(FOREIGN, token=anonymous, correlation_id=uuid.uuid4()))


def test_a_tampered_payload_under_another_correlation_id_is_a_bad_signature(decode, pki,
                                                                           business_now):
    """X9-DEC-508 — a payload altered after signing, under another correlationId than the request's,
    is refused as a bad signature ("Payload JWS signature is invalid."), not as a bad correlationId.

    Source: Ours. Owner, 2026-11-15 (ADR-0027): only a payload refused for its correlationId gets
      "QR Code is invalid (bad correlationId)"; the verifier's verdict comes first, on both builds.
    Why: The two refusals tell the payer different things: a bad correlationId may clear on another
      try, a payload nobody signed will not.
    """
    import uuid

    another = str(uuid.uuid4())

    def tampered(_asked):
        header, _, signature = jws.Signer(pki["payee"], now_ms=business_now) \
            .sign(FOREIGN, correlation_id=another).split(".")
        altered = copy.deepcopy(FOREIGN)
        altered["paymentMethods"][0]["networks"]["ach"]["accountNumber"] = "9999999999"
        return ".".join([header, jws.b64u_json(altered), signature])

    response = decode(FOREIGN, token=tampered)

    _refused("tampered", response, words=INVALID)
    assert REFUSED not in response.text, response.body

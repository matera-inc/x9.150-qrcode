# Copyright © 2026 Matera Systems, Inc.
# Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
"""What we generate is closed at every level (INTERPRETATION I-16, owner 2026-11-15).

A create, a PATCH and an outbound payment notification refuse an unknown field wherever it is, with
one violation that names its path and the fields the object does have, in the same words on both
builds. ``test_contract.py`` generates the same cases from ``additionalProperties: false`` and checks
the fields listed against the contract; these pin the exact words, so the two builds cannot drift
apart in what they say.
"""
import json

import pytest

from x9client import samples

UNKNOWN = "zzUnknown"
DEAD_PAYEE = "http://127.0.0.1:9/pub/api/v1/payment-notification"

ADDRESS = "line1, line2, city, state, postalCode and country"
PARTY = "name, phone, email and address"
BILL = "paymentTiming, description, order, invoice, tip and amountDue"
METHOD = "currency, validUntil, amount, editable and networks"
BANK = "routingNumber, accountNumber and protectionType"
PARAMETERS = "bill/amountDue/adjustments/parameters"

# (where, as a JSON pointer; what the refusal calls the object; its fields, or None for "only field")
CREATE_LEVELS = [
    ("", "the payment request", "validUntil, creditor, bill, locationId, unstructured, "
                                "additionalInformation, paymentNotification and paymentMethods"),
    ("/creditor", "creditor", "name, phone, email, address, ultimateCreditor and MCC"),
    ("/creditor/address", "address", ADDRESS),
    ("/creditor/ultimateCreditor", "ultimateCreditor", "account, name, phone, email and address"),
    ("/creditor/ultimateCreditor/account", "account", "id and schemaName"),
    ("/creditor/ultimateCreditor/address", "address", ADDRESS),
    ("/bill", "bill", BILL),
    ("/bill/order", "order", "number and date"),
    ("/bill/invoice", "invoice", "number, date, dueDate and invoicee"),
    ("/bill/invoice/invoicee", "invoicee", PARTY),
    ("/bill/invoice/invoicee/address", "address", ADDRESS),
    ("/bill/tip", "tip", "allowed, range and presets"),
    ("/bill/tip/range", "range", "min and max"),
    ("/bill/amountDue", "amountDue", "amount, currency and adjustments"),
    ("/bill/amountDue/adjustments", "adjustments", "formula and parameters"),
    (f"/{PARAMETERS}", "parameters", "discounts and lateFees"),
    (f"/{PARAMETERS}/discounts/0", "a discount", "daysBefore, discount and explanation"),
    (f"/{PARAMETERS}/lateFees", "lateFees", "fixed, perDay and explanation"),
    ("/additionalInformation/0", "an additionalInformation entry", "key and value"),
    ("/paymentNotification", "paymentNotification", "kind and endpoint"),
    ("/paymentMethods/0", "a payment method", METHOD),
    ("/paymentMethods/0/editable", "editable", None),
    ("/paymentMethods/0/editable/range", "range", "min and max"),
    ("/paymentMethods/0/networks/rtp", "rtp", BANK),
    ("/paymentMethods/1/networks/solana", "solana", "recipient and memo"),
]

PATCH_LEVELS = [
    ("", "the payment request", "locationId, validUntil, bill, unstructured, additionalInformation "
                                "and paymentMethods"),
    ("/bill", "bill", BILL),
    ("/bill/order", "order", "number and date"),
    ("/bill/invoice", "invoice", "number, date, dueDate and invoicee"),
    ("/bill/invoice/invoicee", "invoicee", PARTY),
    ("/bill/invoice/invoicee/address", "address", ADDRESS),
    ("/bill/tip", "tip", "allowed, range and presets"),
    ("/bill/tip/range", "range", "min and max"),
    ("/bill/amountDue", "amountDue", "amount and adjustments"),
    ("/bill/amountDue/adjustments", "adjustments", "formula and parameters"),
    (f"/{PARAMETERS}", "parameters", "discounts and lateFees"),
    (f"/{PARAMETERS}/discounts/0", "a discount", "daysBefore, discount and explanation"),
    (f"/{PARAMETERS}/lateFees", "lateFees", "fixed, perDay and explanation"),
    ("/additionalInformation/0", "an additionalInformation entry", "key and value"),
    ("/paymentMethods/0", "a payment method", METHOD),
    ("/paymentMethods/0/editable", "editable", None),
    ("/paymentMethods/0/editable/range", "range", "min and max"),
    ("/paymentMethods/0/networks/ach", "ach", BANK),
]

OUTBOUND_LEVELS = [
    ("", "the outbound notification", "endpoint, correlationId and notification"),
    ("/notification", "notification", "payment, payer, expectedDate and blockchain"),
    ("/notification/payment", "payment", "qrcodeId, amount, tipAmount, currency, network and "
                                         "transactionId"),
    ("/notification/payer", "payer", None),
    ("/notification/blockchain", "blockchain", "action, from and to"),
]

ONLY = {"editable": "range", "payer": "info"}


def _with_unknown(body, pointer):
    # Through JSON, not deepcopy: the samples share one address dict and one bank dict between
    # several places, and deepcopy keeps them shared.
    body = json.loads(json.dumps(body))
    node = body
    for step in [s for s in pointer.split("/") if s]:
        node = node[int(step)] if isinstance(node, list) else node[step]
    node[UNKNOWN] = True
    return body


def _expected(pointer, name, fields):
    path = ".".join(f"[{s}]" if s.isdigit() else s for s in pointer.split("/") if s)
    path = (path.replace(".[", "[") + "." + UNKNOWN) if path else UNKNOWN
    if fields is None:
        return f"{path}: is not a field of {name}, whose only field is {ONLY[name]}"
    return f"{path}: is not a field of {name}, whose fields are {fields}"


def _ids(levels):
    return [f"{name}@{pointer or '/'}" for pointer, name, _ in levels]


@pytest.mark.parametrize("pointer, name, fields", CREATE_LEVELS, ids=_ids(CREATE_LEVELS))
def test_an_unknown_field_at_any_level_of_a_create_is_refused(api, pointer, name, fields):
    """X9-RAIL-507 — an unknown field at any level of a create is refused: 400, one violation
    naming its path and the object's own fields, in the same words on both builds; no id.

    Source: Ours. INTERPRETATION I-16 (owner, 2026-11-15): what we generate is closed at every
      level; ``openapi.yaml`` declares every object of a create ``additionalProperties: false``
      except ``networks``.
    Why: Before this, only the rails refused one; anywhere else the field was dropped with a 201,
      so a biller's mistyped or invented field vanished from the QR Code they printed.
    """
    body = _with_unknown(samples.full_payment_request(), pointer)

    response = api.post("/api/v1/payment-request", body=body)

    assert response.status == 400, response
    assert response.violations == [_expected(pointer, name, fields)], response.body
    assert "id" not in response.body


@pytest.mark.parametrize("pointer, name, fields", PATCH_LEVELS, ids=_ids(PATCH_LEVELS))
def test_an_unknown_field_at_any_level_of_a_patch_is_refused_and_nothing_moves(api, pointer, name,
                                                                                fields):
    """X9-RAIL-508 — an unknown field at any level of a PATCH is refused, naming its path, and the
    QR Code does not move: same ETag, same revision.

    Source: Ours. INTERPRETATION I-16: a PATCH restates the request, closed as a create is.
    Why: A refusal at create that a PATCH lets through is no refusal. (What a GET carries but a
      PATCH cannot change, such as ``bill.amountDue.currency``, is not unknown: it is compared with
      the stored value, X9-PATCH-510 and -511.)
    """
    request = samples.full_payment_request()
    created = api.post("/api/v1/payment-request", body=request)
    assert created.status == 201, created
    qr = created.body["id"]
    before = api.qr(qr)

    response = api.patch(f"/api/v1/payment-request/{qr}",
                         body=_with_unknown(samples.full_patch(request), pointer), if_match=before.etag)

    assert response.status == 400, response
    assert response.violations == [_expected(pointer, name, fields)], response.body
    after = api.qr(qr)
    assert after.etag == before.etag, "a refused PATCH moved the ETag"
    assert after.body.get("revision") == before.body.get("revision")


@pytest.mark.parametrize("pointer, name, fields", OUTBOUND_LEVELS, ids=_ids(OUTBOUND_LEVELS))
def test_an_unknown_field_at_any_level_of_an_outbound_notification_is_refused(api, pointer, name,
                                                                              fields):
    """X9-RAIL-509 — an unknown field at any level of an outbound payment notification is refused,
    naming its path, before anything is sent.

    Source: Ours. INTERPRETATION I-16: the body of ``pre-payment`` is what this service signs and
      sends to a payee, so it is something we generate (``OutboundPaymentNotificationData``,
      closed). What a payee reads from another bank stays open.
    Why: The field was dropped and the notification signed and sent without it. The unbroken body,
      sent to a payee nobody answers for, gets 502 (``test_contract.py``'s control), so a 400 here is
      the refusal and not the transport.
    """
    body = {"endpoint": DEAD_PAYEE, "correlationId": "8a652794-9e73-4fd7-a628-4d525b638cbc",
            "notification": samples.notification("0123456789ABCDEF0123456789ABCDEF",
                                                 expected_date="2030-12-31T23:59:59Z")}

    response = api.post("/api/v1/payment-notification/pre-payment", body=_with_unknown(body, pointer))

    assert response.status == 400, response
    assert response.violations == [_expected(pointer, name, fields)], response.body


def _full_qr(api):
    created = api.post("/api/v1/payment-request", body=samples.full_payment_request())
    assert created.status == 201, created
    return created.body["id"]


def test_a_patch_of_the_whole_get_answer_with_one_change_is_applied(api):
    """X9-PATCH-510 — a PATCH whose body is the whole GET answer, with one patchable change: 200,
    the change applied, and every read-only member it carried (id, status, revision, creditor,
    createdAt, bill.amountDue.currency, …) accepted and ignored.

    Source: Ours. INTERPRETATION I-16 (owner, 2026-11-15, option B): a member a GET carries but a
      PATCH cannot change is accepted when it equals the stored value, compared as GET renders it;
      ``sentAt``, the instant of the GET answer itself, is never compared.
    Why: The natural client reads, edits and writes back. Refusing what it copied from our own
      answer would make every such client strip fields it never meant to touch.
    """
    qr = _full_qr(api)
    current = api.qr(qr)
    body = json.loads(json.dumps(current.body))
    body["unstructured"] = "edited from a GET copy"

    response = api.patch(f"/api/v1/payment-request/{qr}", body=body, if_match=current.etag)

    assert response.status == 200, response
    after = api.qr(qr).body
    assert after["unstructured"] == "edited from a GET copy"
    assert after["revision"] == current.body["revision"] + 1
    for key in ("creditor", "status", "createdAt"):
        assert after[key] == current.body[key], key
    assert after["bill"]["amountDue"]["currency"] == current.body["bill"]["amountDue"]["currency"]


@pytest.mark.parametrize("path, value, said", [
    ("status", "CANCELLED", "status: cannot be changed by a PATCH (stored ACTIVE)"),
    ("bill.amountDue.currency", "USDC", "bill.amountDue.currency: cannot be changed by a PATCH (stored USD)"),
    ("creditor.name", "Another Co", "creditor: cannot be changed by a PATCH"),
], ids=["status", "currency", "creditor"])
def test_a_patch_of_the_get_answer_changing_a_read_only_member_is_refused(api, path, value, said):
    """X9-PATCH-511 — the whole GET answer with one read-only member changed (and one patchable
    change beside it): 400 naming the member and its stored value, and nothing moves.

    Source: Ours. INTERPRETATION I-16 (owner, 2026-11-15, option B): a member a GET carries but a
      PATCH cannot change is refused by name when it differs from the stored value.
    Why: Silently ignoring it would tell the caller the status, the currency or the creditor had
      changed when it had not.
    """
    qr = _full_qr(api)
    current = api.qr(qr)
    body = json.loads(json.dumps(current.body))
    body["unstructured"] = "edited from a GET copy"
    *parents, last = path.split(".")
    node = body
    for step in parents:
        node = node[step]
    node[last] = value

    response = api.patch(f"/api/v1/payment-request/{qr}", body=body, if_match=current.etag)

    assert response.status == 400, response
    assert response.violations == [said], response.body
    after = api.qr(qr)
    assert after.etag == current.etag, "a refused PATCH moved the ETag"
    assert after.body["unstructured"] == current.body["unstructured"]

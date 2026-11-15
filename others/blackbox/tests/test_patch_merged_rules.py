# Copyright © 2026 Matera Systems, Inc.
# Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
"""A PATCH is checked like a create on its merged result, and ``sentAt`` is when the JSON is
generated (owner decisions of 2026-11-15), the same on both builds.

- The merged tip is held to ADR-0017: allowed with neither a range nor presets is refused.
- A date the PATCH sets is not already past on the payee's clock, in the create's words; a date it
  leaves as it was is not judged again, so an expired QR Code can still be extended.
- A PATCH that moves ``validUntil`` earlier cuts a running reservation back to it (ADR-0019).
- What Story 9.7 found the Java build dropping, or failing on with a 500, is applied or refused by
  name: an order, invoice or adjustments sent to a bill that had none; a currency named twice;
  ``bill: null``, ``editable: null``, ``editable: {}``.
- ``sentAt`` in the GET answer is the instant it is generated, never earlier than ``revisedAt``
  (X9.150 §6; INTERPRETATION I-17).

Every refusal asserts the words and that nothing moved: a refused PATCH writes nothing.
"""
import datetime

import pytest

from x9client import samples
from x9client.checks import assert_refused
from x9client.clock import format_instant, parse_instant
from x9client.samples import BILL, FAR_FUTURE, RECIPIENT

UTC = datetime.timezone.utc
PR = "/api/v1/payment-request"


def _method(valid_until=FAR_FUTURE, amount=BILL, **extra):
    method = {"currency": "USDC", "validUntil": valid_until, "amount": amount,
              "networks": {"solana": {"recipient": RECIPIENT}}}
    method.update(extra)
    return method


def _bill(**extra):
    bill = {"description": "blackbox", "paymentTiming": "immediate", "amountDue": {"amount": BILL}}
    bill.update(extra)
    return bill


def _invoice(due):
    return {"number": "INV-1", "date": "2020-01-01", "dueDate": due}


def _create(api, **extra):
    body = samples.payment_request()
    body.update(extra)
    response = api.post(PR, body=body)
    assert response.status == 201, f"fixture QR Code could not be created: {response}"
    return response.body["id"]


def _patch(api, qr, body):
    return api.patch(f"{PR}/{qr}", body=body)


def _assert_refused_unchanged(api, qr, body, naming):
    before = api.qr(qr)
    assert_refused(_patch(api, qr, body), naming=naming, status=400)
    after = api.qr(qr)
    assert after.etag == before.etag, "a refused PATCH does not move the ETag"
    for key in ("revision", "validUntil", "bill", "paymentMethods"):
        assert after.body.get(key) == before.body.get(key), f"a refused PATCH changed {key}"


def _at(delta):
    return format_instant(datetime.datetime.now(UTC).replace(microsecond=0) + delta)


def test_the_merged_tip_is_held_to_the_creates_rule(api):
    """X9-PATCH-512 — a PATCH whose merged tip is allowed with neither a range nor presets is
    refused in the create's words, and nothing moves; one that keeps the stored presets is applied.

    Source: Ours. ADR-0017, INTERPRETATION I-10; held on PATCH by the owner on 2026-11-15.
    Why: a create refused an empty tip offer while a PATCH could write one."""
    qr = _create(api)
    _assert_refused_unchanged(api, qr, {"bill": _bill(tip={"allowed": True}), "paymentMethods": [_method()]},
                              "tip.allow: should inform range or presets when tip is allowed")

    body = samples.payment_request(tip={"allowed": True, "presets": [10, 15]})
    with_presets = api.post(PR, body=body)
    assert with_presets.status == 201, with_presets
    qr = with_presets.body["id"]
    response = _patch(api, qr, {"bill": _bill(tip={"allowed": True}), "paymentMethods": [_method()]})
    assert response.status == 200, response
    assert api.qr(qr).body["bill"]["tip"].get("presets") == [10, 15], "the stored presets are kept"


def test_a_due_date_or_discount_the_patch_sets_in_the_past_is_refused(api):
    """X9-PATCH-513 — a due date, or a discount's edge, that a PATCH sets already past is refused in
    the create's words, and nothing moves.

    Source: Ours. The create's rules, held on what a PATCH changes by the owner on 2026-11-15.
    Why: a create refuses a bill already overdue or a discount already over; a PATCH could write
    them."""
    qr = _create(api)
    _assert_refused_unchanged(api, qr, {"bill": _bill(invoice=_invoice("2020-06-30T00:00:00Z")),
                                        "paymentMethods": [_method()]},
                              "bill.invoice.dueDate: must be after or equal to actual date.")
    adjustments = {"formula": "FixedDiscountLateFeeLinearInterest", "parameters": {
        "discounts": [{"daysBefore": 5, "discount": 10, "explanation": "early"}],
        "lateFees": {"fixed": 0, "perDay": 0, "explanation": "none"}}}
    _assert_refused_unchanged(api, qr, {
        "bill": _bill(invoice=_invoice(_at(datetime.timedelta(days=1))),
                      amountDue={"amount": BILL, "adjustments": adjustments}),
        "paymentMethods": [_method()]},
        "bill.amountDue.adjustments.discounts[0]: target date must be after the current date.")


@pytest.mark.serial
def test_a_valid_until_set_in_the_past_is_refused_and_an_expired_qr_code_is_extended(api, clock):
    """X9-PATCH-514 — a ``validUntil`` the PATCH sets already past is refused, and nothing moves;
    once the QR Code has expired, a PATCH extending it, its bill restated with its past due date, is
    applied: a date left as it was is not judged again.

    Source: Ours. Owner decision 2026-11-15, on the payee's (business) clock.
    Why: holding every stored date to now would make an expired QR Code impossible to extend, the
    edit a biller most needs once it has lapsed."""
    start = clock.now().replace(microsecond=0)
    due = format_instant(start + datetime.timedelta(minutes=30))
    ends = format_instant(start + datetime.timedelta(hours=1))
    qr = _create(api, validUntil=format_instant(start + datetime.timedelta(hours=2)),
                 bill={"description": "blackbox", "invoice": _invoice(due),
                       "amountDue": {"amount": BILL, "currency": "USDC"}},
                 paymentMethods=[_method(valid_until=ends)])

    later = clock.move_to(start + datetime.timedelta(minutes=90))
    _assert_refused_unchanged(api, qr, {"validUntil": format_instant(later - datetime.timedelta(seconds=5)),
                                        "paymentMethods": [_method(valid_until=ends)]},
                              "validUntil: must not be less than the current date.")

    much_later = clock.move_to(start + datetime.timedelta(hours=3))
    extended = format_instant(much_later + datetime.timedelta(days=1))
    response = _patch(api, qr, {"validUntil": extended, "bill": _bill(invoice=_invoice(due)),
                                "paymentMethods": [_method(valid_until=extended)]})
    assert response.status == 200, response
    assert parse_instant(api.qr(qr).body["validUntil"]) == parse_instant(extended)


@pytest.mark.serial
def test_an_earlier_valid_until_cuts_the_reservation_back(api, clock):
    """X9-PATCH-515 — a PATCH that moves ``validUntil`` before the end of a running reservation
    cuts it back: past the new ``validUntil`` the QR Code reads ACTIVE, not for the rest of its
    90 s window.

    Source: Ours. ADR-0019 ("a reservation cannot outlive the thing reserved"); held on PATCH by
    the owner on 2026-11-15.
    Why: the window was capped by the validUntil it was taken under; moved earlier, the QR Code
    would read PAYMENT_INITIATED after its own expiry."""
    start = clock.now().replace(microsecond=0)
    qr = _create(api)
    assert api.set_status(qr, "PAYMENT_INITIATED").status == 200

    cut = format_instant(start + datetime.timedelta(seconds=30))
    response = _patch(api, qr, {"validUntil": cut, "paymentMethods": [_method(valid_until=cut)]})
    assert response.status == 200, response
    assert api.status_of(qr) == "PAYMENT_INITIATED"

    clock.move_to(start + datetime.timedelta(seconds=31))
    assert api.status_of(qr) == "ACTIVE", "the reservation ended at the new validUntil"


def test_what_is_sent_to_a_bill_that_had_none_is_applied(api):
    """X9-PATCH-516 — an order, an invoice with its invoicee, and adjustments sent to a bill that
    had none are applied.

    Source: Ours. Story 9.7 found the Java build dropping them with a 200; the owner chose the Go
    build's answer (2026-11-15).
    Why: a 200 for a change that went nowhere is the silent drop ADR-0018 removed for currencies."""
    qr = _create(api)
    adjustments = {"formula": "FixedDiscountLateFeeLinearInterest",
                   "parameters": {"lateFees": {"fixed": 1, "perDay": 1, "explanation": "late"}}}
    response = _patch(api, qr, {"bill": _bill(
        order={"number": "ORD-1"},
        invoice={**_invoice("2030-12-30T23:59:59Z"), "invoicee": {"name": "Ann"}},
        amountDue={"amount": BILL, "adjustments": adjustments}), "paymentMethods": [_method()]})
    assert response.status == 200, response

    bill = api.qr(qr).body["bill"]
    assert bill.get("order", {}).get("number") == "ORD-1", bill
    assert bill.get("invoice", {}).get("invoicee", {}).get("name") == "Ann", bill
    assert bill["amountDue"].get("adjustments", {}).get("parameters", {}).get("lateFees"), bill


def test_a_currency_named_twice_is_refused(api):
    """X9-PATCH-517 — a PATCH naming a currency twice is refused naming it, in the create's words,
    and nothing moves.

    Source: Ours. Story 9.7 found the Java build keeping the last in silence; the owner chose the
    Go build's answer (2026-11-15).
    Why: with two amounts for one currency, the caller cannot know which the QR Code now asks for."""
    qr = _create(api)
    _assert_refused_unchanged(api, qr, {"paymentMethods": [_method(amount=700), _method(amount=800)]},
                              "paymentMethods: must not contain duplicated currency: USDC")


def test_the_nulls_that_ended_in_a_500_are_answered(api):
    """X9-PATCH-518 — ``bill: null`` is refused by name; ``editable: {}`` on a method that had none
    is the create's refusal; ``editable: null`` removes ``editable``.

    Source: Ours. Story 9.7 found each ending in a 500 on the Java build; the owner chose the Go
    build's answers (2026-11-15).
    Why: a 500 tells the caller nothing about what to fix."""
    qr = _create(api)
    _assert_refused_unchanged(api, qr, {"bill": None, "paymentMethods": [_method(amount=700)]},
                              "bill: must not be updated with null.")
    _assert_refused_unchanged(api, qr, {"paymentMethods": [_method(editable={})]},
                              "Editable amount range is required when the amount is editable.")

    editable = _create(api, paymentMethods=[_method(editable={"range": {"min": 1, "max": 2000}})])
    response = _patch(api, editable, {"paymentMethods": [_method(editable=None)]})
    assert response.status == 200, response
    assert "editable" not in api.qr(editable).body["paymentMethods"][0]


@pytest.mark.serial
def test_get_sent_at_is_never_before_revised_at(api, clock):
    """X9-TIME-024 — the GET answer's ``sentAt`` is the instant it is generated, on the business
    clock, but never earlier than ``revisedAt``.

    Source: X9.150 §6 (``sentAt`` SHALL be greater than or equal to ``revisedAt``); the instant is
    ours (INTERPRETATION I-17, owner 2026-11-15).
    Why: a clock behind the last revision would otherwise answer a sentAt the standard forbids."""
    qr = _create(api)
    revised = parse_instant(api.qr(qr).body["revisedAt"])

    clock.move_to(revised - datetime.timedelta(hours=1))
    assert parse_instant(api.qr(qr).body["sentAt"]) == revised, "a clock behind revisedAt: revisedAt"

    ahead = clock.move_to(revised + datetime.timedelta(hours=1))
    sent = parse_instant(api.qr(qr).body["sentAt"])
    assert ahead <= sent < ahead + datetime.timedelta(minutes=1), f"a clock ahead: now, got {sent}"

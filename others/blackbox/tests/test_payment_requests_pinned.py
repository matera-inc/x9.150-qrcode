# Copyright © 2026 Matera Systems, Inc.
# Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
"""Payment requests are pinned: create, PATCH, ETag, rebind (Story 4.2, plan §4.1 groups A, B, B2,
B3).

Every refusal and limit of create and PATCH, as the running Java build answers today, so that the Go
build can accept neither more nor less. Keys are ``X9-<AREA>-42N`` (the 420s are this story's).

What is pinned elsewhere, and therefore not repeated here:

- every ``maxLength``/``minLength``/``pattern``/``required`` of the create and PATCH bodies, a
  missing currency included: ``contract.py`` (S3.3);
- patching while ``PAYMENT_INITIATED``, ``PAID`` or ``CANCELLED``, and a valid patch moving the
  revision: ``test_transition_map.py::test_map_002_patch`` (X9-MAP-002);
- dropping a currency, or adding one to a QR Code that offers two: ``test_patch_currencies.py``;
- a PATCH onto a live holder's location, and onto a ``CANCELLED`` one's: ``test_location_handover.py``.

A cell where Java contradicts the story or an accepted ADR is a strict expected failure naming the
rule, so it turns red the day the two agree.
"""
import datetime

import pytest

import builds
from test_transition_map import (CANCELLED_EVT, CLEARED, INITIATED, LAPSED_MS, OTHER,
                                 assert_events, assert_untouched, closed, held_by_holder, pre, snap)
from x9client import emv, jws, samples
from x9client.checks import assert_refused
from x9client.samples import BILL, FAR_FUTURE, FEDNOW, RECIPIENT

PR = "/api/v1/payment-request"
INT64_MAX = 2 ** 63 - 1
EIGHTEEN_NINES = 10 ** 18 - 1
OTHER_CREDITOR = {"name": "Another Co", "address": {"line1": "2 B St", "city": "Denver",
                                                    "country": "US"}, "MCC": "5812"}


def instant(at):
    return at.astimezone(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def service_now(business_now):
    return datetime.datetime.fromtimestamp(business_now() / 1000, tz=datetime.timezone.utc)


def assert_not_created(response):
    assert "id" not in (response.body if isinstance(response.body, dict) else {}), response.body


def restated(api, qr):
    """A PATCH body restating only the payment methods as they stand, and the ETag it was read at."""
    current = api.qr(qr)
    methods = [{k: pm[k] for k in ("currency", "validUntil", "amount", "networks")}
               for pm in current.body["paymentMethods"]]
    return {"paymentMethods": methods}, current.etag


def patch(api, qr, body, if_match=None):
    return api.patch(f"{PR}/{qr}", body=body, if_match=if_match)


def location_of(api, qr):
    return (api.qr(qr).body.get("location") or {}).get("id")


# ====================================================================== S4.2-AC1: create (group A)

@pytest.mark.parametrize("networks, named", [
    ({"tron": {"walletAddress": "TXYZ"}}, ["tron"]),
    ({"solana": {"recipient": RECIPIENT}, "tron": {"walletAddress": "T"}, "zelle": {"id": "z"}},
     ["tron", "zelle"]),
    ({"FedNow": FEDNOW}, ["FedNow"]),
], ids=["alone", "beside-a-supported-one", "another-case"])
def test_an_unsupported_network_is_refused_by_name(api, networks, named):
    """X9-RAIL-420 — S4.2-AC1: a network this deployment does not interpret is refused at create
    with a 400 naming it: alone, beside a supported one (never silently dropped), and a known rail
    in another case (the create API takes the spec's spelling only).

    Source: Ours. ADR-0012 (refuse what this deployment cannot honour), ADR-0010;
      INTERPRETATION.md "Inbound, on our own create/patch API: the same spelling, exactly".
    Why: Dropping ``tron`` and keeping ``solana`` would print a QR Code that advertises less than
      the biller asked for, with a 201 saying all was well.
    """
    currency = "USD" if "FedNow" in networks else "USDC"
    response = api.create_qr_detailed(currency=currency, networks=networks)

    for name in named:
        assert_refused(response, naming=name, status=400)
    assert_refused(response, naming="paymentMethods.networks")
    assert_not_created(response)


@pytest.mark.parametrize("currency, named", [("usd", '\\"usd\\" must be written as \\"USD\\"'),
                                             ("Usdc", '\\"Usdc\\" must be written as \\"USDC\\"'),
                                             ("EUR", "Currency EUR is not settled")])
def test_an_unsupported_or_wrong_case_currency_is_refused_by_name(api, currency, named):
    """X9-CUR-420 — S4.2-AC1: ``usd`` is not ``USD``; EUR is not on the allow-list.

    Source: Ours. ``supported-currencies.json`` is an allow-list (ADR-0012); one spelling on our
      own API, the one we emit (INTERPRETATION.md).
    Why: Accepting ``usd`` and storing ``usd`` would emit a code no payer matches; normalising it
      would hand the caller back a value it never sent.
    """
    response = api.create_qr_detailed(currency=currency, networks={"fednow": FEDNOW})
    assert_refused(response, naming=named, status=400)
    assert_not_created(response)


def test_a_payment_method_without_a_currency_does_not_inherit_the_bills(api):
    """X9-CUR-421 — S4.2-AC1: no default currency, not even the bill's.

    Source: Ours. "No default currency" (Story 2.6 decision; ``contract.py`` also refuses the
      missing field mechanically).
    Why: A default would let a method be settled in a currency the biller never wrote down.
    """
    body = samples.payment_request(currency="USD", networks={"fednow": FEDNOW})
    del body["paymentMethods"][0]["currency"]

    response = api.post(PR, body=body)

    assert_refused(response, naming="paymentMethods[0].currency", status=400)
    assert_not_created(response)


def test_currencies_from_two_peg_groups_are_refused(api):
    """X9-CUR-422 — S4.2-AC1: USD beside BRL is refused, naming BRL.

    Source: Ours. ``pegged-currencies.json`` puts BRL in another group; on this deployment the
      allow-list (``supported-currencies.json``) is checked first and refuses it, so the peg rule's
      own message is not reachable here: every supported currency is in the dollar group.
    Why: One QR Code with two unpegged prices is two debts; the order of the checks is pinned so the
      Go build names the same culprit.
    """
    body = samples.payment_request(currency="USD", networks={"fednow": FEDNOW})
    body["paymentMethods"].append(samples.payment_method(currency="BRL"))

    response = api.post(PR, body=body)

    assert_refused(response, naming="Currency BRL is not settled", status=400)
    assert_not_created(response)


def test_a_duplicate_currency_is_refused_by_name(api):
    """X9-CUR-423 — S4.2-AC1: two methods in USD are refused, naming USD.

    Source: Ours (memory: the duplicate-currency rule is ours, not the spec's; it blocks
      per-network amounts and is deferred, not forgotten).
    Why: Methods are matched by currency on PATCH and on notification; two of them make every match
      ambiguous.
    """
    body = samples.payment_request(currency="USD", networks={"fednow": FEDNOW})
    body["paymentMethods"].append(samples.payment_method(currency="USD", networks={"rtp": FEDNOW}))

    response = api.post(PR, body=body)

    assert_refused(response, naming="must not contain duplicated currency: USD", status=400)
    assert_not_created(response)


@pytest.mark.parametrize("offset_s", [-60, 0], ids=["in-the-past", "exactly-now"])
def test_a_valid_until_in_the_past_or_now_is_refused(api, business_now, offset_s):
    """X9-LIFE-420 — S4.2-AC1: ``validUntil`` an hour ago, or the service's own "now" (to the
    second, so already past when the request lands).

    Source: Ours. The standard sets no rule for an issuer creating an expired request.
    Why: A QR Code born expired can be printed and never paid; refusing it at create is the only
      point at which the biller is still listening.
    """
    at = instant(service_now(business_now) + datetime.timedelta(seconds=offset_s))

    response = api.create_qr_detailed(valid_until=at)

    assert_refused(response, naming="validUntil: must be after or equal to actual date", status=400)
    assert_not_created(response)


def test_a_due_date_after_valid_until_is_refused(api):
    """X9-LIFE-421 — S4.2-AC1: the invoice falls due after the QR Code stops being payable.

    Source: Ours. ``bill.invoice.dueDate`` is the standard's; tying it to ``validUntil`` is not.
    Why: A late fee that accrues after the code expires can never be collected through it.
    """
    body = samples.payment_request()
    body["bill"]["invoice"] = {"number": "INV-1", "date": "2030-01-01",
                               "dueDate": "2031-01-01T00:00:00Z"}

    response = api.post(PR, body=body)

    assert_refused(response, naming="bill.invoice.dueDate: must be before or equal to validUntil",
                   status=400)
    assert_not_created(response)


def test_a_methods_valid_until_after_the_qr_codes_is_refused(api):
    """X9-LIFE-422 — S4.2-AC1: a payment method outliving its QR Code.

    Source: Ours. X9.150 carries both instants; requiring the method's to fit inside is our rule.
    Why: The payer would read a method as payable after the payload itself refuses to be fetched.
    """
    body = samples.payment_request()
    body["validUntil"] = "2030-06-30T00:00:00Z"

    response = api.post(PR, body=body)

    assert_refused(response, naming="paymentMethods[0].validUntil: must be before or equal than "
                                    "payment request validUntil", status=400)
    assert_not_created(response)


@pytest.mark.parametrize("tip, named", [
    ({"allowed": False, "range": {"min": 0, "max": 20}}, "cannot provided range or presets"),
    ({"allowed": False, "presets": [10]}, "cannot provided range or presets"),
    ({"allowed": True}, "should inform range or presets"),
], ids=["not-allowed-with-a-range", "not-allowed-with-presets", "allowed-with-neither"])
def test_a_tip_offer_that_contradicts_itself_is_refused(api, tip, named):
    """X9-TIP-420 — S4.2-AC1: ``allowed: false`` with a range or presets; ``allowed: true`` with
    neither.

    Source: Ours. ADR-0017 (a tip is money on top of the bill); the standard's tip object does not
      say which combinations are coherent.
    Why: A payer app shown a range on a bill that refuses tips offers money the payee will refuse.
    """
    response = api.create_qr_detailed(tip=tip)
    assert_refused(response, naming=f"tip.allow: {named}", status=400)
    assert_not_created(response)


def test_a_tip_allowed_with_presets_and_no_range_is_accepted(api):
    """X9-TIP-421 — S4.2-AC1: ``allowed: true`` with presets and no range is accepted (today's
    behaviour pinned: the presets then bind nothing, see ``test_tip_offers.py``).

    Source: Mechanism. Java asks for "range or presets", not for a range.
    Why: The story reads "true without one [a range]"; Java accepts presets alone. Pinned so the Go
      build does not refuse what Java accepts; see the PR's questions for the owner.
    """
    response = api.create_qr_detailed(tip={"allowed": True, "presets": [10, 15]})
    assert response.status == 201, response


@pytest.mark.parametrize("where", ["bill-and-method", "method-only"])
def test_a_negative_amount_is_refused(api, where):
    """X9-AMT-420 — S4.2-AC1: −1 in the bill and the method, or in the method alone.

    Source: ANSI X9.150-2026 §2.1 — amounts are integers with minimum value 0. Conformance.
    Why: Every later comparison (bill, range, tip) assumes a non-negative integer.
    """
    body = samples.payment_request(amount=-1 if where == "bill-and-method" else BILL)
    body["paymentMethods"][0]["amount"] = -1

    response = api.post(PR, body=body)

    assert_refused(response, naming="monetary values should not be negative", status=400)
    assert_not_created(response)


@pytest.mark.parametrize("where, field", [
    ("bill-and-method", "bill.amountDue.amount"),
    ("method-only", "paymentMethods[0].amount"),
])
def test_18_nines_is_accepted_and_19_digits_refused(api, where, field):
    """X9-AMT-421 — S4.2-AC1: 999999999999999999 (18 nines) is accepted and read back to the
    unit; 10^18, the smallest 19-digit amount, is refused with 400 naming the field, and nothing
    is created, though a 64-bit integer holds it.

    Source: ANSI X9.150-2026 §13.5.1 and §14.3 — amount fields have a maximum length of 18.
      Conformance (ADR-0005; Java accepted 19 digits until 2026-11-14).
    Why: A QR Code asking for an amount no conforming payer can hold is one nobody can pay. The
      accepted case is the control, and a JSON parser that goes through a double would round it:
      the bill would then read back as a different debt.
    """
    accepted = api.create_qr_detailed(amount=EIGHTEEN_NINES)
    assert accepted.status == 201, accepted
    read = api.qr(accepted.body["id"]).body
    assert read["bill"]["amountDue"]["amount"] == EIGHTEEN_NINES
    assert read["paymentMethods"][0]["amount"] == EIGHTEEN_NINES

    body = samples.payment_request(amount=10 ** 18 if where == "bill-and-method" else BILL)
    body["paymentMethods"][0]["amount"] = 10 ** 18

    refused = api.post(PR, body=body)

    assert_refused(refused, naming=field, status=400)
    assert_not_created(refused)


@pytest.mark.parametrize("amount", [INT64_MAX + 1, 9999999999999999999],
                         ids=["int64-max-plus-one", "19-nines"])
def test_a_19_digit_amount_beyond_int64_is_refused_as_unreadable(api, amount):
    """X9-AMT-422 — S4.2-AC1: past 2^63−1 the body is unreadable: 400 ``malformed-json``, no
    violations, nothing created (today's behaviour pinned).

    Source: Mechanism. Jackson cannot bind the number to a ``long``.
    Why: Wrapping round to a negative, or saturating at the maximum, would store a debt nobody
      asked for.
    """
    body = samples.payment_request()
    body["paymentMethods"][0]["amount"] = amount

    response = api.post(PR, body=body)

    assert response.status == 400, response
    assert response.body.get("type", "").endswith("/malformed-json"), response.body
    assert_not_created(response)


def late_fee_body(bill, method, fixed, valid_until=FAR_FUTURE):
    """A bill due one day before ``valid_until``: its largest late fee is ``fixed + 100``."""
    body = samples.payment_request(amount=bill, valid_until=valid_until)
    body["bill"]["invoice"] = {"number": "INV-1", "date": "2030-01-10",
                               "dueDate": "2030-12-30T23:59:59Z"}
    body["bill"]["amountDue"]["adjustments"] = {
        "formula": "FixedDiscountLateFeeLinearInterest",
        "parameters": {"lateFees": {"fixed": fixed, "perDay": 100, "explanation": "Late fee"}}}
    body["paymentMethods"][0]["amount"] = method
    return body


@pytest.mark.parametrize("case, field", [
    ("bill", "bill.amountDue.amount"),
    ("fee", "bill.amountDue.amount"),
    ("method", "paymentMethods[0].amount"),
    ("patch-valid-until", "bill.amountDue.amount"),
])
def test_a_late_fee_that_could_pass_18_digits_before_valid_until_is_refused(api, case, field):
    """X9-AMT-423 — ADR-0005: the late fee stops growing at ``validUntil``, so the largest amount a
    bill can show is known at creation. Bill and method of 18 nines − 1000 with a largest fee of
    1000 (900 fixed + 100 for the one day between ``dueDate`` and ``validUntil``) are accepted;
    one more unit of bill, fee or method, or a PATCH moving ``validUntil`` a day later, is refused
    with 400 naming the field, nothing created and the revision unmoved.

    Source: ANSI X9.150-2026 §13.5.1, §13.5.3.2, §14.3 — amount fields have a maximum length of 18.
      Conformance; refusing at creation and PATCH is ours (ADR-0005, owner decision 2026-11-14).
    Why: Each input fits 18 digits alone; only their sum on the last day does not. Without the
      bound the payload would carry, on that day, an amount no conforming payer can hold.
    """
    top = EIGHTEEN_NINES - 1000
    accepted = api.post(PR, body=late_fee_body(top, top, 900))
    assert accepted.status == 201, accepted

    if case == "patch-valid-until":
        qr = accepted.body["id"]
        before = snap(api, qr)
        methods = [{k: pm[k] for k in ("currency", "validUntil", "amount", "networks")}
                   for pm in api.qr(qr).body["paymentMethods"]]
        refused = patch(api, qr, {"validUntil": "2031-01-01T23:59:59Z", "paymentMethods": methods})
        assert_refused(refused, naming=field, status=400)
        assert snap(api, qr) == before
        return

    bill, method, fixed = {"bill": (top + 1, top, 900), "fee": (top, top, 901),
                           "method": (top, top + 1, 900)}[case]
    refused = api.post(PR, body=late_fee_body(bill, method, fixed))

    assert_refused(refused, naming=field, status=400)
    assert "late fee" in str(refused.body), refused.body
    assert_not_created(refused)


def test_the_host_fits_emv_tag_26_and_its_limit_is_37_characters(api):
    """X9-LOC-425 — S4.2-AC1: the X9 template in tag 26 is ``0006org.x9`` + ``01LL`` + host +
    ``/pub/api/v1/loc/`` + a 32-character location, so the host may be 37 characters at most.

    Source: EMV QRCPS: a template's value is at most 99 characters. The 37 is that arithmetic, and
      is ours.
    Why: A longer host overflows tag 26 and every scanner rejects the code. No request can make the
      host longer: it is configuration, and ``locationId`` is exactly 32 characters (contract). A
      38-character host stops the Java build at startup ("Property x9.public-endpoints.host should
      not exceeded the 37 limit", observed 2026-11-14), which this suite, running against one
      deployment, cannot start; this pins the arithmetic the Go build must refuse by.
    """
    created = api.create_qr_detailed()
    assert created.status == 201, created

    raw = dict(emv.parse(created.body["qrCode"]))["26"]
    _, template = emv.template_with(created.body["qrCode"], "org.x9")
    url = template["01"]
    host = url.split("/pub/api/v1/loc/")[0]

    assert url == f"{host}/pub/api/v1/loc/{created.body['location']['id']}", url
    assert len(raw) == len("0006org.x9") + len("01LL") + len(url) <= 99, raw
    assert 99 - len("0006org.x9") - len("01LL") - len("/pub/api/v1/loc/") - 32 == 37
    assert len(host) <= 37, host


# ====================================================================== S4.2-AC2: PATCH (group B)

NOT_PATCHABLE = {"creditor": OTHER_CREDITOR, "status": "CANCELLED", "id": "0" * 32, "revision": 7,
                 "createdAt": "2020-01-01T00:00:00Z", "qrCode": "000201", "qrCodeB64": "AA==",
                 "paymentNotification": {"kind": "EXTERNAL"}}


def test_fields_that_are_not_patchable_are_never_applied(api, events):
    """X9-PATCH-420 — S4.2-AC2: creditor, status, id, revision, createdAt, qrCode and
    paymentNotification sent beside a real change: 200, the change applies, none of them does.

    Source: Ours. ADR-0024 "six top-level tags can change"; Jackson ignores the rest.
    Why: A PATCH that could set ``status`` would be a cancel with no ``payment.cancelled``; one that
      could set ``creditor`` would re-point a printed code at another merchant.
    """
    qr = api.create_qr()
    before = snap(api, qr)
    created_at = api.qr(qr).body["createdAt"]
    body, etag = restated(api, qr)
    body["paymentMethods"][0]["amount"] = BILL - 100

    response = patch(api, qr, {**body, **NOT_PATCHABLE}, if_match=etag)

    assert response.status == 200, response
    after = api.qr(qr).body
    assert after["id"] == qr and after["status"] == "ACTIVE"
    assert after["revision"] == before["revision"] + 1, "the revision moved by one, not to 7"
    assert after["creditor"] == before["creditor"]
    assert after["createdAt"] == created_at
    assert after["paymentNotification"] == {"kind": "DEFAULT"}
    assert after["qrCode"] != NOT_PATCHABLE["qrCode"]
    assert after["paymentMethods"][0]["amount"] == BILL - 100, "the patchable change applied"
    assert_events(events, qr, [])


def test_a_patch_changing_only_fields_that_are_not_patchable_is_a_refused_no_op(api):
    """X9-PATCH-421 — S4.2-AC2: the same methods plus a new creditor: nothing patchable changed, so
    400 and nothing moves, ETag included.

    Source: Ours. ADR-0024 "a PATCH that changes nothing is refused".
    Why: Answering 200 would tell the caller the creditor changed.
    """
    qr = api.create_qr()
    before = snap(api, qr)
    body, etag = restated(api, qr)

    response = patch(api, qr, {**body, "creditor": OTHER_CREDITOR}, if_match=etag)

    assert_refused(response, naming="can not find any paymentMethod to be updated", status=400)
    assert_untouched(before, snap(api, qr))


def test_a_no_op_patch_is_refused(api):
    """X9-PATCH-422 — S4.2-AC2: the payment methods exactly as they stand, nothing else: 400,
    nothing moves (the black-box twin of X9-PATCH-023).

    Source: Ours. ADR-0024 "a PATCH that changes nothing is refused".
    Why: With If-Match, a retry after an ambiguous timeout gets 412 if the first attempt landed; a
      silent 200 would not answer the caller's real question.
    """
    qr = api.create_qr()
    before = snap(api, qr)
    body, etag = restated(api, qr)

    response = patch(api, qr, body, if_match=etag)

    assert_refused(response, naming="paymentMethods: can not find any paymentMethod to be updated",
                   status=400)
    assert_untouched(before, snap(api, qr))


def test_restating_the_bill_unchanged_counts_as_a_change(api):
    """X9-PATCH-423 — S4.2-AC2: the same methods plus the bill exactly as stored: 200 and the
    revision moves (pinned, not endorsed).

    Source: Mechanism. ADR-0024 "What counts as changed: a tag counts when it is present;
      paymentMethods counts when its value differs", and the Go build ports that rule.
    Why: A caller restating the whole request on every edit gets a new revision each time; the Go
      build must do the same, or the two builds disagree about what a no-op is.
    """
    qr = api.create_qr()
    current = api.qr(qr).body
    body, etag = restated(api, qr)
    body["bill"] = {"description": current["bill"]["description"], "paymentTiming": "immediate",
                    "amountDue": current["bill"]["amountDue"]}

    response = patch(api, qr, body, if_match=etag)

    assert response.status == 200, response
    after = api.qr(qr).body
    assert after["revision"] == current["revision"] + 1
    assert after["bill"] == current["bill"] and after["paymentMethods"] == current["paymentMethods"]


def test_a_patch_may_not_add_a_currency_to_a_single_currency_qr_code(api):
    """X9-PATCH-424 — S4.2-AC2: USD added to a USDC-only QR Code: 400 naming USD, nothing moves.
    (Dropping one, and adding one to a two-currency code: ``test_patch_currencies.py``.)

    Source: Ours. ADR-0018 "a patch may change the amount of a currency already there; it may never
      add one".
    Why: Adding a currency is issuing a new offer; the printed code and its payers never saw it.
    """
    qr = api.create_qr()
    before = snap(api, qr)
    body, etag = restated(api, qr)
    body["paymentMethods"].append(samples.payment_method(currency="USD",
                                                         networks={"fednow": FEDNOW}))

    response = patch(api, qr, body, if_match=etag)

    assert_refused(response, naming="USD is not here", status=400)
    assert_untouched(before, snap(api, qr))


DUE_DATE_RULE = "bill.invoice.dueDate: must be before or equal to validUntil"
METHOD_RULE = "paymentMethods[0].validUntil: must be before or equal than payment request validUntil"


@pytest.mark.parametrize("case, named", [
    ("valid-until-before-due-date", DUE_DATE_RULE),
    ("valid-until-before-a-method", METHOD_RULE),
    ("a-method-after-valid-until", METHOD_RULE),
])
def test_every_patch_is_held_to_the_cross_field_date_rules(api, case, named):
    """X9-PATCH-426 — the create-time date rules hold on the merged state of every PATCH:
    ``dueDate`` and every method's ``validUntil`` no later than the QR Code's. A PATCH moving only
    one side of a pair past the other is 400 naming the field, and nothing moves, ETag included.

    Source: Ours. The rules of X9-LIFE-421 and X9-LIFE-422; holding them on PATCH is the owner's
      decision of 2026-11-14.
    Why: ``dueDate ≤ validUntil`` used to run only when the bill was patched, so a PATCH of
      ``validUntil`` alone left a QR Code that expires before its bill falls due: a state creation
      refuses, reached by edit.
    """
    body = samples.payment_request()
    body["paymentMethods"][0]["validUntil"] = "2030-06-30T00:00:00Z"
    if case == "valid-until-before-due-date":
        body["bill"]["invoice"] = {"number": "INV-1", "date": "2030-01-01",
                                   "dueDate": "2030-12-30T23:59:59Z"}
    created = api.post(PR, body=body)
    assert created.status == 201, created
    qr = created.body["id"]
    before = snap(api, qr)
    edit, etag = restated(api, qr)

    if case == "valid-until-before-due-date":
        edit["validUntil"] = "2030-12-01T00:00:00Z"
    elif case == "valid-until-before-a-method":
        edit["validUntil"] = "2030-06-01T00:00:00Z"
    else:
        edit["paymentMethods"][0]["validUntil"] = "2031-01-01T00:00:00Z"

    response = patch(api, qr, edit, if_match=etag)

    assert_refused(response, naming=named, status=400)
    assert_untouched(before, snap(api, qr))


def _move(api, qr, how):
    if how == "pre-commit":
        assert pre(api, qr)[0].status == 200
    elif how == "PAID":
        assert api.mark_paid(qr, end_to_end_id="E2E-S42", network="solana").status == 200
    else:
        assert api.set_status(qr, how).status == 200


@pytest.mark.parametrize("moves, status", [
    (["pre-commit"], "PAYMENT_INITIATED"),
    (["PAYMENT_INITIATED"], "PAYMENT_INITIATED"),
    (["PAYMENT_INITIATED", "ACTIVE"], "ACTIVE"),
    (["PAID"], "PAID"),
    (["CANCELLED"], "CANCELLED"),
], ids=["payer-pre-commit", "status-update-initiated", "released", "paid", "cancelled"])
def test_a_status_move_never_moves_the_revision(api, moves, status):
    """X9-PATCH-425 — S4.2-AC2: after a PATCH (revision 1), every way the status moves keeps
    revision 1 and ``revisedAt`` moves; the ETag's status part follows.

    Source: Ours. ADR-0016 "a revision is a version of the request, not of its status".
    Why: A bill marked PAID that nobody edited must not read as version 2, 3, 4.
    """
    qr = api.create_qr()
    body, etag = restated(api, qr)
    body["paymentMethods"][0]["amount"] = BILL
    body["unstructured"] = "edited once"
    assert patch(api, qr, body, if_match=etag).status == 200
    edited = snap(api, qr)
    assert edited["revision"] == 1

    for how in moves:
        _move(api, qr, how)

    after = snap(api, qr)
    assert after["status"] == status
    assert after["revision"] == 1, f"{status}: revision {after['revision']}"
    assert after["etag"] == f'"1-{status}"'
    assert after["revisedAt"] != edited["revisedAt"], "a status move is still a write"


# ====================================================================== S4.2-AC3: ETag (group B2)

def test_the_etag_is_revision_dash_effective_status(api):
    """X9-PATCH-430 — S4.2-AC3: ``"<revision>-<STATUS>"`` on GET, in quotes, matching the body,
    through create, a patch and a cancel.

    Source: Ours. ADR-0028 "The ETag is \"<revision>-<STATUS>\"", built from the effective status;
      ADR-0016 (why it carries both).
    Why: The Go build must serve the same opaque token, or a client holding a tag from one build is
      refused by the other during the cut-over.
    """
    qr = api.create_qr()
    assert api.qr(qr).etag == '"0-ACTIVE"'

    body, etag = restated(api, qr)
    body["paymentMethods"][0]["amount"] = BILL - 1
    assert patch(api, qr, body, if_match=etag).status == 200
    assert api.qr(qr).etag == '"1-ACTIVE"'

    assert api.set_status(qr, "CANCELLED").status == 200
    read = api.qr(qr)
    assert read.etag == f'"{read.body["revision"]}-{read.body["status"]}"' == '"1-CANCELLED"'


IF_MATCH_FORMS = {"quoted": lambda tag: tag,
                  "bare": lambda tag: tag.strip('"'),
                  "weak-quoted": lambda tag: "W/" + tag,
                  "weak-bare": lambda tag: "W/" + tag.strip('"')}


@pytest.mark.parametrize("form", list(IF_MATCH_FORMS))
@pytest.mark.parametrize("operation", ["patch", "status-update"])
def test_if_match_is_accepted_with_and_without_w_and_quotes(api, operation, form):
    """X9-PATCH-431 — S4.2-AC3: the current tag, quoted, bare, ``W/"…"`` and ``W/…``, is accepted
    on PATCH and on status-update.

    Source: Ours. ADR-0028 "If-Match parsing strips W/ and quotes".
    Why: Proxies weaken ETags and hand-written clients drop the quotes; refusing either would make
      every conditional request from them a 412.
    """
    qr = api.create_qr()
    body, etag = restated(api, qr)
    if_match = IF_MATCH_FORMS[form](etag)

    if operation == "patch":
        body["paymentMethods"][0]["amount"] = BILL - 1
        response = patch(api, qr, body, if_match=if_match)
        expected = '"1-ACTIVE"'
    else:
        response = api.status_update(qr, {"status": "CANCELLED"}, if_match=if_match)
        expected = '"0-CANCELLED"'

    assert response.status == 200, f"If-Match {if_match}: {response}"
    assert api.qr(qr).etag == expected


def _stale(api, qr, operation, tag):
    if operation == "patch":
        body, _ = restated(api, qr)
        body["paymentMethods"][0]["amount"] = BILL - 7
        return patch(api, qr, body, if_match=tag)
    return api.status_update(qr, {"status": "CANCELLED"}, if_match=tag)


@pytest.mark.parametrize("operation", ["patch", "status-update"])
def test_a_stale_revision_is_412_and_nothing_moves(api, operation):
    """X9-PATCH-432 — S4.2-AC3: the tag read before somebody else's PATCH: 412 naming the current
    revision and status, nothing written.

    Source: Ours. ADR-0016 (conditional requests), ADR-0028.
    Why: The second editor decided on an amount that no longer stands; applying it would undo the
      first edit without either of them knowing.
    """
    qr = api.create_qr()
    body, old = restated(api, qr)
    body["paymentMethods"][0]["amount"] = BILL - 1
    assert patch(api, qr, body, if_match=old).status == 200
    before = snap(api, qr)

    response = _stale(api, qr, operation, old)

    assert response.status == 412, response
    assert response.body.get("currentRevision") == 1 and response.body.get("currentStatus") == "ACTIVE"
    assert_untouched(before, snap(api, qr))


@pytest.mark.parametrize("operation", ["patch", "status-update"])
def test_a_stale_status_is_412_and_nothing_moves(api, operation):
    """X9-PATCH-433 — S4.2-AC3: the tag read while ACTIVE, used after a payer's pre-commit (same
    revision, new status): 412, the reservation and the bill untouched.

    Source: Ours. ADR-0016: "a caller meaning 'cancel only if nobody has started paying'" is the
      race the status half of the tag exists for.
    Why: Without it, a cancel would land on a QR Code a payer is midway through paying.
    """
    qr = api.create_qr()
    _, old = restated(api, qr)
    assert pre(api, qr)[0].status == 200
    before = snap(api, qr)

    response = _stale(api, qr, operation, old)

    assert response.status == 412, response
    assert response.body.get("currentStatus") == "PAYMENT_INITIATED"
    assert_untouched(before, snap(api, qr))


@pytest.mark.parametrize("if_match, status", [("abc", 412), ("*", 200)],
                         ids=["malformed-is-412", "star-is-unconditional"])
def test_a_malformed_if_match_is_412_and_a_star_is_unconditional(api, if_match, status):
    """X9-PATCH-434 — S4.2-AC3: ``If-Match: abc`` is compared as a tag and fails (412); ``*`` is
    no condition (today's behaviour pinned).

    Source: Mechanism. ``UpdateQRCodeStatusRequestMapper.parseIfMatch``.
    Why: Treating a malformed tag as no condition would apply the very write the caller meant to
      make conditional.
    """
    qr = api.create_qr()
    body, _ = restated(api, qr)
    body["paymentMethods"][0]["amount"] = BILL - 1

    assert patch(api, qr, body, if_match=if_match).status == status


@pytest.mark.serial
@pytest.mark.parametrize("operation", ["patch", "status-update"])
def test_after_a_lapsed_reservation_the_etag_reads_active_and_is_accepted(api, events, clock,
                                                                           operation):
    """X9-PATCH-435 — S4.2-AC3: a payer's reservation lapses; GET serves ``"0-ACTIVE"``, and that
    tag is accepted by PATCH and by a cancel.

    Source: Ours. ADR-0028: built from the effective status on GET and compared against the stored
      one on If-Match "was a Java bug: once a reservation lapsed, every conditional request
      answered 412" (fixed by Story 2.2).
    Why: Every biller using If-Match was locked out of every QR Code whose payer walked away.
    """
    qr, _ = held_by_holder(api)
    assert api.qr(qr).etag == '"0-PAYMENT_INITIATED"'

    clock.move_by(LAPSED_MS)
    read = api.qr(qr)
    assert read.etag == '"0-ACTIVE"' and read.body["status"] == "ACTIVE"

    if operation == "patch":
        body, _ = restated(api, qr)
        body["paymentMethods"][0]["amount"] = BILL - 1
        assert patch(api, qr, body, if_match=read.etag).status == 200
        assert api.qr(qr).etag == '"1-ACTIVE"'
        assert_events(events, qr, [INITIATED])
    else:
        assert api.status_update(qr, {"status": "CANCELLED"}, if_match=read.etag).status == 200
        assert api.qr(qr).etag == '"0-CANCELLED"'
        assert_events(events, qr, [INITIATED, CANCELLED_EVT])


# ====================================================================== S4.2-AC4: rebind (group B3)

def _rebind_by_create(api, location, creditor=None):
    body = samples.payment_request(locationId=location)
    if creditor:
        body["creditor"] = creditor
    return api.post(PR, body=body)


def _rebind_by_patch(api, taker, location):
    body, etag = restated(api, taker)
    return patch(api, taker, {**body, "locationId": location}, if_match=etag)


def _resolves_to(api, location, emv_content, payee_certificate, business_now):
    response = api.fetch_payload(location, emv_content)
    assert response.status == 200, f"/loc/{location}: {response}"
    _, claims = jws.verify(response.text, payee_certificate, now_ms=business_now)
    return claims.get("qrcodeId") or claims.get("id")


def _assert_released(api, events, donor, before, closing_event):
    after = snap(api, donor)
    assert after["location"] and after["location"] != before["location"], \
        "the holder answers on a fresh location"
    assert after["status"] == before["status"]
    for key in ("revision", "bill", "paymentMethods", "validUntil", "creditor"):
        assert after[key] == before[key], key
    assert_events(events, donor, [closing_event] if closing_event else [])


@pytest.mark.parametrize("holder, via", [("CANCELLED", "create"), ("PAID", "patch")])
def test_a_closed_holder_of_the_same_creditor_gives_up_its_location(
        api, events, payee_certificate, business_now, holder, via):
    """X9-LOC-420 — S4.2-AC4: the holder ``CANCELLED`` (taken by a create) or ``PAID`` (taken by a
    PATCH), same creditor: accepted; the holder moves to a fresh location with no event; the old
    location resolves the new code only.

    Source: Ours. ADR-0024 location rule; ADR-0035 row "location released by a rebind" (no event).
    Why: Re-pointing a printed code at the balance still owed is the point of a rebind; emitting a
      payment event for that bookkeeping would read as a payment to every consumer.
    """
    donor = closed(api, holder)
    before = snap(api, donor)
    location = before["location"]

    if via == "create":
        response = _rebind_by_create(api, location)
        assert response.status == 201, response
        taker = response.body["id"]
    else:
        taker = api.create_qr()
        response = _rebind_by_patch(api, taker, location)
        assert response.status == 200, response

    assert location_of(api, taker) == location
    _assert_released(api, events, donor, before, CLEARED if holder == "PAID" else CANCELLED_EVT)
    assert_events(events, taker, [])
    assert _resolves_to(api, location, api.qr(taker).body["qrCode"], payee_certificate,
                        business_now) == taker


@pytest.mark.serial
# S9.1-AC5: strict on Java, which fails it by mistake; an ordinary test on Go, which follows ADR-0024.
@pytest.mark.xfail(builds.under_test() == "java", strict=True, reason=(
    "S4.2-AC4 / ADR-0024: a holder past its validUntil is not live and gives its location up; "
    "Java's releaseLocation reads the status only, so an expired ACTIVE holder still in MongoDB "
    "(the TTL monitor has not deleted it yet) refuses the rebind with 400. BUG-004; see "
    "'Questions for the owner'."))
def test_an_expired_active_holder_of_the_same_creditor_gives_up_its_location(
        api, events, clock, business_now):
    """X9-LOC-421 — S4.2-AC4: the holder is ``ACTIVE`` but past its ``validUntil``: accepted, the
    holder moves to a fresh location, no event.

    Source: Ours. ADR-0024 "Not live means stored status PAID or CANCELLED, or valid_until in the
      past" — the gap the ADR closes for the Go build, where rows are retained.
    Why: Without it, a biller re-pointing an expired printed code gets a 400 until MongoDB happens to
      delete the holder (Java), and forever on a build that retains rows (Go).
    """
    valid_until = instant(service_now(business_now) + datetime.timedelta(minutes=2))
    donor = api.create_qr(valid_until=valid_until)
    before = snap(api, donor)
    clock.move_by(3 * 60_000)

    response = _rebind_by_create(api, before["location"])

    assert response.status == 201, response
    _assert_released(api, events, donor, before, None)


@pytest.mark.parametrize("holder", ["ACTIVE", "PAYMENT_INITIATED"])
@pytest.mark.parametrize("via", ["create", "patch"])
def test_a_live_holder_keeps_its_location(api, events, holder, via):
    """X9-LOC-422 — S4.2-AC4: the holder ``ACTIVE`` in time, or held by a payer: refused 400 naming
    its status, on create and on PATCH; the holder keeps everything, the taker is not created or
    not changed.

    Source: Ours. ADR-0024 location rule.
    Why: The printed code is in a payer's hands, or being paid right now; moving it would send that
      payer's money to a different bill.
    """
    if holder == "ACTIVE":
        donor, setup = api.create_qr(), []
    else:
        donor, setup = held_by_holder(api)[0], [INITIATED]
    before = snap(api, donor)

    if via == "create":
        response = _rebind_by_create(api, before["location"])
        assert_not_created(response)
    else:
        taker = api.create_qr()
        taker_before = snap(api, taker)
        response = _rebind_by_patch(api, taker, before["location"])
        assert_untouched(taker_before, snap(api, taker))

    assert_refused(response, naming=f"needs to be inactive to reuse location. Current status: {holder}",
                   status=400)
    assert_untouched(before, snap(api, donor))
    assert_events(events, donor, setup)


@pytest.mark.parametrize("via", ["create", "patch"])
def test_a_different_creditor_never_takes_the_location(api, events, via):
    """X9-LOC-423 — S4.2-AC4: the holder ``CANCELLED`` (releasable), but the taker's creditor
    differs: refused 400, on create and on PATCH; nothing moves.

    Source: Ours. ADR-0024 "only when the creditor is the same".
    Why: A location is a printed code on one merchant's counter; another merchant taking it would
      collect that merchant's payments.
    """
    donor = closed(api, "CANCELLED")
    before = snap(api, donor)

    if via == "create":
        response = _rebind_by_create(api, before["location"], creditor=OTHER_CREDITOR)
        assert_refused(response, naming="Creditor information must not change", status=400)
        assert_not_created(response)
    else:
        body = samples.payment_request()
        body["creditor"] = OTHER_CREDITOR
        created = api.post(PR, body=body)
        assert created.status == 201, created
        taker = created.body["id"]
        taker_before = snap(api, taker)
        response = _rebind_by_patch(api, taker, before["location"])
        assert_refused(response, naming="Creditor information must be equal", status=400)
        assert_untouched(taker_before, snap(api, taker))

    assert_untouched(before, snap(api, donor))
    assert_events(events, donor, [CANCELLED_EVT])


@pytest.mark.parametrize("breakage", ["unsupported-currency", "valid-until-in-the-past"])
def test_a_refused_create_does_not_release_the_holder(api, breakage):
    """X9-LOC-424 — S4.2-AC4: a create naming a releasable holder's location, refused for another
    reason: the holder keeps its location.

    Source: Ours. ADR-0025 (a transition is one transaction); the release is part of the create.
    Why: A refused create that still moved the holder would leave the printed code resolving to
      nothing: the bug section 11 of the old acceptance script was written for.
    """
    donor = closed(api, "CANCELLED")
    before = snap(api, donor)
    body = samples.payment_request(locationId=before["location"])
    if breakage == "unsupported-currency":
        body = samples.payment_request(locationId=before["location"], currency="EUR",
                                       networks={"fednow": FEDNOW})
    else:
        body["validUntil"] = body["paymentMethods"][0]["validUntil"] = "2020-01-01T00:00:00Z"

    response = api.post(PR, body=body)

    assert response.status == 400, response
    assert_untouched(before, snap(api, donor))

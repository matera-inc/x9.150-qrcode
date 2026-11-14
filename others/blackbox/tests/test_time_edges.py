# Copyright © 2026 Matera Systems, Inc.
# Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
"""The time edges of plan §4.2 (T1–T12), each at the instant it falls, on the test clock (Story 4.5).

Every test here moves the deployment's one clock (ADR-0033), so the whole module is ``serial``, and
every test takes the ``clock`` fixture, which resets it before and after, on failure too
(S4.5-AC1). Keys are ``X9-TIME-NNN``; the T-number of the plan is in each docstring.

**How an edge is driven, given that the clock ticks.** The clock is the real one plus an offset; it
is never frozen (ADR-0033), so no request can be judged at exactly an instant E. Two moves are
exact enough to pin an inclusive edge:

- **just after:** ``PUT {"at": E + 1 ms}`` and then the request. The clock never reads earlier than
  what was put, so the request is judged at E + 1 ms or later: "refused 1 ms after" is exact.
- **just before:** ``PUT {"at": E − 2 s}`` and then the request, timing both on this machine's
  monotonic clock. The service's clock runs at the same rate, so the request is judged no later
  than E − 2 s + the elapsed time. If that elapsed time is not under 2 s the attempt proves
  nothing, and it is made again (three attempts) rather than read either way. Two seconds is
  generous: a clock move plus one signed request takes tens of milliseconds on loopback, so the
  margin absorbs a garbage-collection pause or a slow CI runner, and an over-long request is a retry,
  never a wrong verdict.

Where the service evaluates an instant it was given rather than the clock, E itself is reached
exactly: the payload's ``dateForPayment`` header is a calendar date read as that day's UTC start,
and every due date here is a UTC midnight, so ``dateForPayment`` = E's date prices the payload at
E, to the millisecond.

The fixtures are created at real time, before the clock moves: a due date must be in the future
when the QR Code is created.
"""
import base64
import datetime
import json
import os
import time

import pytest

from x9client import jws, samples
from x9client.checks import assert_refused
from x9client.samples import BILL, TX_HASH

pytestmark = pytest.mark.serial

UTC = datetime.timezone.utc
MS = datetime.timedelta(milliseconds=1)
SECOND = datetime.timedelta(seconds=1)
DAY = datetime.timedelta(days=1)
LEAD = datetime.timedelta(seconds=2)
ATTEMPTS = 3

DAYS_BEFORE, DISCOUNT = 5, 50              # the discount: 50 off, up to 5 days before the due date
FIXED_FEE, PER_DAY = 100, 10               # the late fee: 100 once, plus 10 per started day late
DISCOUNTED = BILL - DISCOUNT

HOLDER = "holder@example.com"
OTHER = "other@example.com"

# Past x9.reservation.ttl-seconds (90 s, the default the black-box deployment runs with).
LAPSED_MS = 95_000

INITIATED, SENT, CLEARED = "payment.initiated", "payment.sent", "payment.cleared"
RIWS = "RECEIVED_IN_WRONG_STATUS"


# ------------------------------------------------------------------ instants

def iso(at):
    """Whole seconds, as a create body takes them."""
    return at.astimezone(UTC).strftime("%Y-%m-%dT%H:%M:%SZ")


def instant(text):
    return datetime.datetime.fromisoformat(text.replace("Z", "+00:00"))


def real_now():
    return datetime.datetime.now(UTC)


def whole_seconds_from_now(delta):
    """An instant ``delta`` from real now, truncated to the second (what the API stores)."""
    return (real_now() + delta).replace(microsecond=0)


def midnight_in(days):
    """A UTC midnight ``days`` from today: a due date that ``dateForPayment`` can reach exactly."""
    today = real_now().replace(hour=0, minute=0, second=0, microsecond=0)
    return today + days * DAY


def just_after(clock, edge, request):
    """``request()`` judged at ``edge`` + 1 ms or later (see the module docstring)."""
    clock.move_to(edge + MS)
    return request()


def just_before(clock, edge, request):
    """``request()`` judged within the two seconds before ``edge``, never after it.

    Retries when the attempt took too long to prove which side of the edge it landed on.
    ``request`` must be safe to repeat: a fetch, a refusal (which changes nothing), or the same
    holder's pre-commit (a retry, judged again and accepted again).
    """
    for _ in range(ATTEMPTS):
        started = time.monotonic()
        clock.move_to(edge - LEAD)
        response = request()
        if time.monotonic() - started < LEAD.total_seconds():
            return response
    pytest.fail(f"no attempt finished within {LEAD} of moving the clock, so none can be shown to "
                f"have been judged before {edge}; the deployment is too slow for this test")


# ------------------------------------------------------------------ fixtures

def adjusted_qr(api, *, due, valid_until=samples.FAR_FUTURE):
    """A QR Code whose bill has the discount and the late fee, due at ``due``; the create body."""
    body = samples.payment_request(valid_until=valid_until)
    body["bill"]["invoice"] = {"number": "INV-TIME-1", "date": real_now().strftime("%Y-%m-%d"),
                               "dueDate": iso(due)}
    body["bill"]["amountDue"]["adjustments"] = {
        "formula": "FixedDiscountLateFeeLinearInterest",
        "parameters": {
            "discounts": [{"daysBefore": DAYS_BEFORE, "discount": DISCOUNT,
                           "explanation": "Early payment"}],
            "lateFees": {"fixed": FIXED_FEE, "perDay": PER_DAY, "explanation": "Late fee"}}}
    response = api.post("/api/v1/payment-request", body=body)
    assert response.status == 201, f"fixture QR Code could not be created: {response}"
    return response.body


def created(api, **kwargs):
    response = api.create_qr_detailed(**kwargs)
    assert response.status == 201, f"fixture QR Code could not be created: {response}"
    return response.body


def fetch(api, qr, *, date_for_payment=None):
    """The payer's fetch of the payload, and the answer (not asserted)."""
    token = api.sign({"qrCodeContent": base64.b64encode(qr["qrCode"].encode()).decode()})
    headers = {"Content-Type": "application/jose"}
    if date_for_payment is not None:
        headers["dateForPayment"] = date_for_payment.isoformat()
    return api.call("POST", f"/pub/api/v1/loc/{qr['location']['id']}", raw=token, headers=headers)


def quote(api, qr, payee_certificate, clock, **kwargs):
    """What the payload offers: ``(amount of the first method, the bill's adjustments)``.

    The payload is verified against the payee's certificate: an amount nobody signed proves nothing.
    """
    response = fetch(api, qr, **kwargs)
    assert response.status == 200, f"the payload must be served: {response}"
    _, claims = jws.verify(response.text, payee_certificate, now_ms=clock.now_millis)
    adjustment = claims["bill"]["amountDue"].get("adjustment") or []
    return claims["paymentMethods"][0]["amount"], adjustment, claims


def assert_quoted(api, qr, payee_certificate, clock, *, amount, adjustment=None, until=None,
                  **kwargs):
    """The payload offers ``amount``; with ``adjustment``, one adjustment of that amount, signed
    as valid until exactly ``until``; without, none at all."""
    offered, adjustments, _ = quote(api, qr, payee_certificate, clock, **kwargs)
    assert offered == amount, f"the payload offers {offered}, wanted {amount}: {adjustments}"
    if adjustment is None:
        assert adjustments == [], f"no adjustment applies, yet one is advertised: {adjustments}"
        return
    assert [a.get("amount") for a in adjustments] == [adjustment], adjustments
    assert instant(adjustments[0]["validUntil"]) == until, \
        f"the {adjustment} is advertised until {adjustments[0]['validUntil']}, wanted {iso(until)}"


def violation_fields(response):
    """The fields a refusal names (``"field: message"`` strings, or dicts with a ``field``)."""
    fields = []
    for violation in response.violations:
        if isinstance(violation, dict):
            fields.append(violation.get("field"))
        else:
            fields.append(str(violation).split(":", 1)[0].strip())
    return fields


def assert_refused_on(response, field):
    """400 on exactly ``field``: ``validUntil`` and ``paymentMethods.validUntil`` are different
    rules, and a substring match cannot tell them apart."""
    assert response.status == 400, f"expected 400 on {field}, got {response.status}: {response.body}"
    assert field in violation_fields(response), \
        f"refused, but not on {field}: {violation_fields(response)} {response.body}"


def event_types(events, qr, count, *, settle=2.0):
    """The events for ``qr``, once ``count`` have appeared and ``settle`` more seconds passed."""
    events.for_qr(qr, until=lambda es: len(es) >= count, timeout=20)
    time.sleep(settle)
    return [e for e in events.read_all()[0] if e.get("qrCodeId") == qr]


# ------------------------------------------------------------------ T1, T3: the discount edge

def test_t1_the_discount_holds_up_to_and_including_e_and_not_a_millisecond_after(
        api, clock, payee_certificate):
    """X9-TIME-001 — T1: the discount is served, advertised and accepted up to and including E,
    refused 1 ms after; the face amount is still accepted (S4.5-AC1, S2.6-AC1, S1.3-AC1).

    Source: Ours. Plan §4.2 T1; Story 2.6 ("the payee honours what it signed": the advertised
    ``validUntil`` of the discounted amount is E itself); Story 1.3 (the payee's clock at receipt
    decides). The standard does not settle when an adjusted amount stops applying.
    Why: before Story 2.6 the discount was advertised until 23:59:59 UTC of E's day but refused from
    E on, so for up to ~21 h the payee refused an amount its own signature called valid.
    """
    e = midnight_in(10) - DAYS_BEFORE * DAY
    held, late = adjusted_qr(api, due=e + DAYS_BEFORE * DAY), \
        adjusted_qr(api, due=e + DAYS_BEFORE * DAY)

    clock.move_to(e - 10 * SECOND)
    assert_quoted(api, held, payee_certificate, clock, amount=DISCOUNTED, adjustment=-DISCOUNT,
                  until=e)
    # Exactly E: dateForPayment is E's date, priced at its UTC start, which is E.
    assert_quoted(api, held, payee_certificate, clock, amount=DISCOUNTED, adjustment=-DISCOUNT,
                  until=e, date_for_payment=e.date())

    accepted = just_before(clock, e, lambda: api.notify(held["id"], amount=DISCOUNTED,
                                                        payer_info=HOLDER))
    assert accepted.status == 200, f"the discount is accepted up to E: {accepted}"

    clock.move_to(e + MS)
    assert_quoted(api, late, payee_certificate, clock, amount=BILL)
    refused = api.notify(late["id"], amount=DISCOUNTED, payer_info=HOLDER)
    assert_refused(refused, naming="payment.amount", status=400)
    assert api.status_of(late["id"]) == "ACTIVE", "a refused pre-commit reserves nothing"
    face = api.notify(late["id"], amount=BILL, payer_info=HOLDER)
    assert face.status == 200, f"the face amount is still accepted after E: {face}"


def test_t3_there_is_no_second_edge_at_the_end_of_es_day(api, clock, payee_certificate):
    """X9-TIME-003 — T3: one edge. From E + 1 ms to 23:59:59.999 UTC of E's day (the old advertised
    edge V) the discount is neither offered nor accepted (S4.5-AC1, S2.6-AC4).

    Source: Ours. Plan §4.2 T3; Story 2.6.
    Why: the old formula truncated the advertised edge to the end of the calendar day; a build that
    still did would offer, or accept, the discount somewhere in this interval.
    """
    e = midnight_in(10) - DAYS_BEFORE * DAY
    qr = adjusted_qr(api, due=e + DAYS_BEFORE * DAY)
    end_of_day = e + DAY - SECOND

    for at in (e + MS, end_of_day, end_of_day + 999 * MS):
        clock.move_to(at)
        assert_quoted(api, qr, payee_certificate, clock, amount=BILL)
        assert_refused(api.notify(qr["id"], amount=DISCOUNTED, payer_info=HOLDER),
                       naming="payment.amount", status=400)

    assert api.notify(qr["id"], amount=BILL, payer_info=HOLDER).status == 200, \
        "the control: the face amount is accepted"


# ------------------------------------------------------------------ T2: money already moved

def test_t2_the_holders_post_commit_after_the_edge_is_recorded_without_a_re_check(
        api, clock, events, payee_certificate):
    """X9-TIME-002 — T2: the holder announced the discounted amount before E and reports it sent
    after E: recorded, a signed yes, no re-check, and the event carries the amount reported
    (S4.5-AC1).

    Source: Ours. Plan §4.2 T2; ADR-0036 (a post-commit is never judged on amount or time);
    ADR-0014 (the consumer reconciles the difference).
    Why: the money has moved. Refusing the report because the discount ended while the
    transaction settled would hide a real payment from the payment system.
    """
    e = midnight_in(10) - DAYS_BEFORE * DAY
    qr = adjusted_qr(api, due=e + DAYS_BEFORE * DAY)["id"]
    pre = just_before(clock, e, lambda: api.notify(qr, amount=DISCOUNTED, payer_info=HOLDER))
    assert pre.status == 200, f"setup: the pre-commit before E: {pre}"

    clock.move_to(e + MS)
    sent = api.notify(qr, amount=DISCOUNTED, payer_info=HOLDER, action="SENT",
                      transaction_id=TX_HASH)

    assert sent.status == 200, f"a post-commit is not re-checked against the ended discount: {sent}"
    assert sent.body == {"statusCode": 200}, sent.body
    jws.verify(sent.text, payee_certificate, now_ms=clock.now_millis)
    mine = event_types(events, qr, 2)
    assert [e_.get("type") for e_ in mine] == [INITIATED, SENT], mine
    assert mine[-1].get("amount") == DISCOUNTED, f"the amount actually reported: {mine[-1]}"
    assert mine[-1].get("transactionId") == TX_HASH, mine[-1]
    assert not mine[-1].get("deviations"), f"the holder's own report is clean: {mine[-1]}"


# ------------------------------------------------------------------ T4, T5: the due date and the fee

def test_t4_at_exactly_the_due_date_the_full_amount_and_no_fee(api, clock, payee_certificate):
    """X9-TIME-004 — T4: at exactly ``dueDate`` the full amount, no late fee; 1 ms later the first
    day's fee (S4.5-AC1, S1.3-AC4).

    Source: Ours. Plan §4.2 T4; Story 1.3 ("a payment up to and including dueDate is on time").
    Why: the formula used to throw when the payment instant equalled the due date, and a payer
    paying in the due date's last instant was charged, or failed, for being early.
    """
    due = midnight_in(10)
    on_time, late = adjusted_qr(api, due=due), adjusted_qr(api, due=due)

    clock.move_to(due - 10 * SECOND)
    assert_quoted(api, on_time, payee_certificate, clock, amount=BILL,
                  date_for_payment=due.date())          # priced at exactly dueDate
    on = just_before(clock, due, lambda: api.notify(on_time["id"], amount=BILL, payer_info=HOLDER))
    assert on.status == 200, f"the full amount is accepted up to the due date: {on}"

    clock.move_to(due + MS)
    first_day = BILL + FIXED_FEE + PER_DAY
    assert_quoted(api, late, payee_certificate, clock, amount=first_day,
                  adjustment=FIXED_FEE + PER_DAY, until=due + DAY)
    assert_refused(api.notify(late["id"], amount=BILL, payer_info=HOLDER),
                   naming="payment.amount", status=400)
    assert api.notify(late["id"], amount=first_day, payer_info=HOLDER).status == 200


def test_t5_the_late_fee_rises_every_24_hours_from_the_due_date(api, clock, payee_certificate):
    """X9-TIME-005 — T5: fee = k × per-day + fixed with k = ceil((t − D) / 24 h); the advertised
    ``validUntil`` is D + k × 24 h, capped by the QR Code's; accepted at that boundary and refused
    1 ms later (S4.5-AC1, S2.6-AC2).

    Source: Ours. Plan §4.2 T5; Story 2.6.
    Why: the fee used to be advertised until the end of the calendar day while it rose at
    D + k × 24 h, so a signed fee went stale before its signature said so.
    """
    due = midnight_in(10)
    day_one, day_two = adjusted_qr(api, due=due), adjusted_qr(api, due=due)
    capped = adjusted_qr(api, due=due, valid_until=iso(due + 30 * 3600 * SECOND))
    fee = {k: BILL + FIXED_FEE + k * PER_DAY for k in (1, 2, 3)}

    clock.move_to(due + MS)
    assert_quoted(api, day_one, payee_certificate, clock, amount=fee[1],
                  adjustment=fee[1] - BILL, until=due + DAY)
    assert_quoted(api, day_one, payee_certificate, clock, amount=fee[1],     # exactly D + 24 h
                  adjustment=fee[1] - BILL, until=due + DAY, date_for_payment=(due + DAY).date())
    kept = just_before(clock, due + DAY,
                       lambda: api.notify(day_one["id"], amount=fee[1], payer_info=HOLDER))
    assert kept.status == 200, f"the first day's fee is accepted up to D + 24 h: {kept}"

    clock.move_to(due + DAY + MS)
    assert_quoted(api, day_two, payee_certificate, clock, amount=fee[2],
                  adjustment=fee[2] - BILL, until=due + 2 * DAY)
    assert_quoted(api, capped, payee_certificate, clock, amount=fee[2],
                  adjustment=fee[2] - BILL, until=due + 30 * 3600 * SECOND)
    assert_refused(api.notify(day_two["id"], amount=fee[1], payer_info=HOLDER),
                   naming="payment.amount", status=400)
    assert api.notify(day_two["id"], amount=fee[2], payer_info=HOLDER).status == 200

    clock.move_to(due + 2 * DAY + MS)
    assert_quoted(api, day_two, payee_certificate, clock, amount=fee[3],
                  adjustment=fee[3] - BILL, until=due + 3 * DAY)


# ------------------------------------------------------------------ T6, T7: validUntil

def test_t6_a_pre_commit_after_valid_until_is_refused_and_the_payee_may_still_mark_it_paid(
        api, clock, events):
    """X9-TIME-006 — T6: fetched before ``validUntil``, announced 1 ms after it: refused on
    ``validUntil``; the payee may still mark it PAID. Announced just before it: accepted
    (S4.5-AC1, S1.3-AC3).

    Source: Ours. Plan §4.2 T6; Story 1.3 (``validUntil`` is inclusive, judged at the payee's
    clock on receipt); ADR-0020 (the payee states the outcome of its own receivable).
    Why: a payer who fetched in time does not keep the right to pay after expiry; but money that
    reached the payee anyway must still be recordable, or the books cannot close.
    """
    valid_until = whole_seconds_from_now(datetime.timedelta(hours=1))
    late, on_time = created(api, valid_until=iso(valid_until)), \
        created(api, valid_until=iso(valid_until))

    clock.move_to(valid_until - 5 * SECOND)
    assert fetch(api, late).status == 200, "fetched while valid"

    refused = just_after(clock, valid_until,
                         lambda: api.notify(late["id"], payer_info=HOLDER))
    assert_refused_on(refused, "validUntil")
    assert api.status_of(late["id"]) == "ACTIVE", "a refused pre-commit reserves nothing"

    paid = api.mark_paid(late["id"])
    assert paid.status == 200, f"the payee may mark an expired QR Code PAID: {paid}"
    assert api.status_of(late["id"]) == "PAID"
    assert [e.get("type") for e in event_types(events, late["id"], 1)] == [CLEARED]

    accepted = just_before(clock, valid_until,
                           lambda: api.notify(on_time["id"], payer_info=HOLDER))
    assert accepted.status == 200, f"validUntil is inclusive: accepted up to it: {accepted}"


def test_t7_a_payment_method_that_expires_first_is_withdrawn_alone(api, clock, payee_certificate):
    """X9-TIME-007 — T7: between a method's ``validUntil`` and the QR Code's, that method is absent
    from the payload and refused in a notification; the other is served and accepted (S4.5-AC1).

    Source: Ours. Plan §4.2 T7. ANSI X9.150-2026 gives each payment method its own ``validUntil``;
    what a payee does once one passes is ours.
    Why: a rail the payee no longer offers (a bank account being closed, a rate quoted for an
    hour) must not be paid into because the QR Code as a whole is still valid.
    """
    method_until = whole_seconds_from_now(datetime.timedelta(hours=1))
    code_until = method_until + datetime.timedelta(hours=1)
    body = samples.payment_request(valid_until=iso(code_until))
    body["paymentMethods"].append(samples.payment_method(
        currency="USD", valid_until=iso(method_until), networks={"fednow": samples.FEDNOW}))
    response = api.post("/api/v1/payment-request", body=body)
    assert response.status == 201, f"fixture QR Code could not be created: {response}"
    qr = response.body

    def currencies():
        return sorted(m["currency"] for m in quote(api, qr, payee_certificate, clock)[2]
                      ["paymentMethods"])

    assert just_before(clock, method_until, currencies) == ["USD", "USDC"], \
        "both methods are served up to and including the USD method's validUntil"

    clock.move_to(method_until + MS)
    assert currencies() == ["USDC"], "the expired method is absent; the other is served"
    usd = api.notify(qr["id"], currency="USD", network="FedNow", blockchain=False,
                     payer_info=HOLDER)
    assert_refused_on(usd, "paymentMethods.validUntil")
    assert api.notify(qr["id"], payer_info=HOLDER).status == 200, "USDC is still accepted"


# ------------------------------------------------------------------ T8, T9: the reservation

def test_t8_after_a_lapse_another_payer_takes_over_and_the_first_payers_late_report_is_kept(
        api, clock, events, payer_a, payer_b):
    """X9-TIME-008 — T8: A's reservation lapses; the QR Code reads ACTIVE and an If-Match on that
    ETag is accepted; B's pre-commit takes it over with ``payment.initiated``; A's late SENT is
    registered, flagged RECEIVED_IN_WRONG_STATUS, and B keeps the QR Code (S4.5-AC1).

    Source: Ours. Plan §4.2 T8; ADR-0019 (a reservation expires and nothing sweeps), ADR-0028
    (the ETag is the effective status), S2.5-AC5 (a takeover is evented), ADR-0036 (a post-commit
    is registered in every status, never refused for status reasons; X9-MAP-033).
    Why: a payer who announced and vanished must not keep the bill from everyone else; and the money
    the first payer moved anyway is evidence the payment system needs, so its report is kept.
    """
    qr = api.create_qr()
    assert api.notify(qr, signer=payer_a, payer_info=HOLDER).status == 200

    clock.move_by(LAPSED_MS)
    lapsed = api.qr(qr)
    assert lapsed.body.get("status") == "ACTIVE", f"a lapsed reservation reads ACTIVE: {lapsed}"
    assert lapsed.etag and lapsed.etag.strip('W/"').endswith("-ACTIVE"), lapsed.etag
    patched = api.patch(f"/api/v1/payment-request/{qr}", if_match=lapsed.etag,
                        body={"paymentMethods": [samples.payment_method(valid_until="2030-12-30T23:59:59Z")]})
    assert patched.status == 200, f"If-Match with the ETag GET served is accepted: {patched.body}"

    taken = api.notify(qr, signer=payer_b, payer_info=OTHER)
    assert taken.status == 200, f"B takes over the lapsed reservation: {taken}"
    late = api.notify(qr, signer=payer_a, payer_info=HOLDER, action="SENT", transaction_id=TX_HASH)
    assert late.status == 200, f"A's money moved; its report is registered: {late}"

    mine = event_types(events, qr, 3)
    assert [e.get("type") for e in mine] == [INITIATED, INITIATED, SENT], mine
    assert mine[1].get("payerInfo") == OTHER, f"the takeover's event is B's: {mine[1]}"
    assert mine[2].get("payerInfo") == HOLDER and RIWS in (mine[2].get("deviations") or []), \
        f"A's report is A's, flagged: {mine[2]}"
    assert api.status_of(qr) == "PAYMENT_INITIATED", "B keeps the QR Code"


def test_t9_a_reservation_taken_30_s_before_valid_until_ends_at_valid_until(api, clock):
    """X9-TIME-010 — T9: a pre-commit accepted at ``validUntil − 30 s`` holds the QR Code up to
    and including ``validUntil`` and no longer, not for the full 90 s (S4.5-AC1).

    Source: Ours. Plan §4.2 T9; ADR-0019 ("a reservation cannot outlive the thing reserved").
    Why: an uncapped reservation would read PAYMENT_INITIATED for a minute past the QR Code's own
    expiry: a payee reconciling at that instant would wait for a payment that can no longer come.
    """
    valid_until = whole_seconds_from_now(datetime.timedelta(hours=1))
    qr = created(api, valid_until=iso(valid_until))["id"]

    clock.move_to(valid_until - 30 * SECOND)
    assert api.notify(qr, payer_info=HOLDER).status == 200, "setup: the reservation is taken"

    assert just_before(clock, valid_until, lambda: api.status_of(qr)) == "PAYMENT_INITIATED", \
        "held up to validUntil"
    assert just_after(clock, valid_until, lambda: api.status_of(qr)) == "ACTIVE", \
        "and ended at validUntil, 60 s before its 90 s window would have"


# ------------------------------------------------------------------ T10: JWS freshness

def test_t10_a_stale_payer_signature_is_refused_before_any_lookup(api, clock, events, payer_a):
    """X9-TIME-011 — T10: a payer ``iat`` 10 min in the service's past with a 5-min ``ttl`` is a
    401 EXPIRED, for a QR Code that exists and one that does not alike (S4.5-AC1).

    Source: Ours, on the X9.150 JWS profile (``iat`` and ``ttl`` are critical header fields).
    ADR-0033: the freshness check reads the business clock, which is moved an hour ahead here, so
    the same ``iat`` is 50 min in the future by the real clock: a check on the real clock would
    answer FUTURE_IAT, not EXPIRED.
    Why: a replayed or delayed question must be refused before it can tell its sender anything,
    even whether the QR Code exists (a fresh question about an unknown one is a 404).
    """
    clock.move_by(3_600_000)
    qr = api.create_qr()
    unknown = "0" * 32

    def stale(qr_id):
        token = payer_a.sign(samples.notification(qr_id, payer_info=HOLDER),
                             iat=clock.now_millis() - 600_000, ttl=300_000)
        return api.send_notification(token)

    for qr_id in (qr, unknown):
        response = stale(qr_id)
        assert response.status == 401, f"a stale question is refused: {response}"
        assert response.body.get("signatureProblem") == "EXPIRED", response.body

    assert api.notify(unknown, payer_info=HOLDER).status == 404, \
        "the control: a fresh question reaches the lookup, so the 401 came before it"
    assert api.status_of(qr) == "ACTIVE", "a refused question reserves nothing"
    assert events.none_for(qr, wait=1) == [], "and publishes nothing"


# ------------------------------------------------------------------ T11: expired is not gone

def test_t11_an_expired_qr_code_stays_readable_on_the_test_clock(api, clock):
    """X9-TIME-012 — T11, both builds: an hour past ``validUntil`` on the test clock the payload is
    refused with the expiry reason, and GET still returns the QR Code, ACTIVE (S4.5-AC1).

    Source: Ours. Plan §4.2 T11; ADR-0020 (expiry is not a status; the payee's endpoints are not
    gated by it); ADR-0029 (Go keeps the code). Java's TTL deletion runs on MongoDB's real clock, so
    a moved clock never deletes the document (X9-TIME-013 waits real time for that).
    Why: a payer scanning an expired code must be told it expired, never handed bank details as if
    still on offer; the payee must still be able to read what it issued.
    """
    valid_until = whole_seconds_from_now(datetime.timedelta(hours=1))
    qr = created(api, valid_until=iso(valid_until))

    clock.move_to(valid_until + datetime.timedelta(hours=1))
    payload = fetch(api, qr)
    assert payload.status >= 400, f"an expired QR Code serves no payload: {payload}"
    assert "expired" in json.dumps(payload.body).lower(), f"and says why: {payload.body}"
    read = api.qr(qr["id"])
    assert read.status == 200 and read.body.get("status") == "ACTIVE", read


@pytest.mark.java_only(reason="java_only: MongoDB's TTL monitor deletes an expired QR Code on the "
                              "real clock (validUntil + 30 s, swept every 60 s); Go keeps it "
                              "(ADR-0029, X9-TIME-014)")
def test_t11_java_deletes_an_expired_qr_code_on_the_real_clock(api, clock):
    """X9-TIME-013 — T11, Java: a QR Code a few seconds from expiry is gone (404) once MongoDB's TTL
    monitor has run, which takes real time, about 30–95 s; never driven by the test clock
    (S4.5-AC1).

    Source: Mechanism. The ``ttl`` index on ``qrcodes`` (``expireAfter = 30S`` on ``validUntil``);
    plan §4.2 T11; tolerated difference 2 (ADR-0028).
    Why: pins the behaviour the Go build deliberately drops, so the difference stays a known one.
    """
    valid_until = whole_seconds_from_now(datetime.timedelta(seconds=3))
    qr = created(api, valid_until=iso(valid_until))["id"]
    assert api.qr(qr).status == 200

    deadline = time.monotonic() + 180
    while time.monotonic() < deadline and api.qr(qr).status == 200:
        time.sleep(3)

    assert api.qr(qr).status == 404, "MongoDB's TTL monitor deleted the expired QR Code"


@pytest.mark.go_only(reason="go_only: the Go build keeps an expired QR Code and releases its "
                            "location (ADR-0029); it first runs in Epics 11-12")
def test_t11_go_keeps_an_expired_qr_code_and_releases_its_location(api, clock):
    """X9-TIME-014 — T11, Go: an hour past ``validUntil`` the code is still readable, its payload
    answers with the expiry reason, and its location can be handed to another QR Code
    (S4.5-AC1, S4.5-AC3).

    Source: Ours. ADR-0029 ("Expiry is not deletion ... Its location is releasable"); plan §4.2 T11.
    Why: "expired" and "never existed" must be told apart, and a printed location must not stay
    locked to a code nobody can pay.
    """
    valid_until = whole_seconds_from_now(datetime.timedelta(hours=1))
    donor = created(api, valid_until=iso(valid_until))
    taker = api.create_qr()

    clock.move_to(valid_until + datetime.timedelta(hours=1))
    assert api.qr(donor["id"]).status == 200
    assert "expired" in json.dumps(fetch(api, donor).body).lower()

    current = api.qr(taker)
    handed = api.patch(f"/api/v1/payment-request/{taker}", if_match=current.etag, body={
        "locationId": donor["location"]["id"],
        "bill": {"description": "blackbox", "amountDue": current.body["bill"]["amountDue"],
                 "paymentTiming": current.body["bill"].get("paymentTiming", "immediate")},
        "paymentMethods": [samples.payment_method()]})
    assert handed.status == 200, f"an expired QR Code's location is releasable: {handed}"
    assert api.qr(taker).body["location"]["id"] == donor["location"]["id"]


# ------------------------------------------------------------------ T12: retention (Go)

def _duration_ms(name, default_days):
    """A retention from the environment, in days (the Go keys' defaults, ADR-0029)."""
    return int(float(os.environ.get(name, default_days)) * 86_400_000)


@pytest.mark.go_only(reason="go_only: retention is the Go build's one background job (ADR-0029); "
                            "it first runs in Epics 11-12")
def test_t12_retention_purges_events_then_qr_codes_by_the_test_clock(api, clock, events):
    """X9-TIME-015 — T12: past the events' retention, an old QR Code's events are purged and the
    code is kept; past the QR Codes' retention, the code goes too; nothing younger is touched;
    the cutoffs are the test clock's (S4.5-AC1, S4.5-AC3).

    Source: Ours. ADR-0029 (events after ``events.retention``, default P30D; QR Codes whose
    ``valid_until`` and ``revised_at`` are both older than ``qrcode.retention``, default P400D);
    plan §4.2 T12. ``X9_EVENTS_RETENTION_DAYS``, ``X9_QRCODE_RETENTION_DAYS`` and
    ``X9_RETENTION_WAIT_SECONDS`` (the test deployment's timer interval, plus slack) describe the
    deployment under test.
    Why: the only sweep deletes; one that read the real clock could not be tested, and one that
    deleted a young row would lose a live bill.
    """
    events_ms = _duration_ms("X9_EVENTS_RETENTION_DAYS", 30)
    qrcodes_ms = _duration_ms("X9_QRCODE_RETENTION_DAYS", 400)
    wait = float(os.environ.get("X9_RETENTION_WAIT_SECONDS", "90"))

    old = created(api, valid_until=iso(whole_seconds_from_now(datetime.timedelta(hours=1))))["id"]
    assert api.set_status(old, "CANCELLED").status == 200
    assert event_types(events, old, 1), "setup: the old QR Code has an event"

    def until(predicate):
        deadline = time.monotonic() + wait
        while time.monotonic() < deadline and not predicate():
            time.sleep(2)
        return predicate()

    def events_of(qr):
        return [e for e in events.read_all()[0] if e.get("qrCodeId") == qr]

    clock.move_by(events_ms + 3_600_000)
    young = api.create_qr()
    assert api.set_status(young, "CANCELLED").status == 200
    assert until(lambda: events_of(old) == []), "the old events are purged"
    assert events_of(young), "the young QR Code's event is not"
    assert api.qr(old).status == 200, "the old QR Code is kept until its own retention"

    clock.move_by(qrcodes_ms + 3_600_000)
    assert until(lambda: api.qr(old).status == 404), "the old QR Code is purged"
    assert api.qr(young).status == 200, "the young one is not: its validUntil is years ahead"


# ------------------------------------------------------------------ S4.5-AC2: whose clock

def test_the_payees_clock_decides_when_it_runs_ahead_of_the_payers(api, clock, pki):
    """X9-TIME-016 — S4.5-AC2: the service's clock is moved past ``validUntil`` while the payer's
    (this machine's) still reads a minute before it. The payer's own check says "valid"; the payee
    refuses on ``validUntil`` (S1.3-AC2).

    Source: Ours. Story 1.3 and INTERPRETATION ("the payee's clock at receipt decides validity and
    amount; the payer's check is advisory").
    Why: a payer whose clock runs behind must not be able to pay what the payee no longer offers;
    the skew is the payer's risk.
    """
    valid_until = whole_seconds_from_now(datetime.timedelta(seconds=90))
    qr = created(api, valid_until=iso(valid_until))["id"]
    # The payer signs on its own clock: about a minute behind the payee's, inside the 5-min ttl.
    behind = jws.Signer(pki["a"])

    clock.move_to(valid_until + MS)
    assert real_now() < valid_until - 30 * SECOND, "the payer's own clock still reads valid"
    refused = api.notify(qr, signer=behind, payer_info=HOLDER)

    assert_refused_on(refused, "validUntil")
    assert api.status_of(qr) == "ACTIVE"


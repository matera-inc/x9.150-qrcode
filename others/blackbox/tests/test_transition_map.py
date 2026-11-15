# Copyright © 2026 Matera Systems, Inc.
# Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
"""ADR-0035's transition map, one test per row, against the running build (Story 4.1).

Every row of the map in ``docs/adr/0035-one-event-builder-and-a-written-transition-map.md`` has a
key ``X9-MAP-NNN``, and the test for it is named ``test_map_NNN_...``. Where ADR-0036's
message-by-status matrix says more than the ADR-0035 row (a refusal in a column the row does not
list, say), the extra cells are cases of the same test. The row-to-test table is in the ADR and
in the black-box README.

Each test asserts three things (S4.1-AC1):

- **the resulting status**, as ``GET`` reports it (the effective status);
- **the events, exactly and in order**: the whole stream for that QR Code since it was created,
  read again after a settle, so an event that should NOT exist has had its chance to appear;
- **what did not change**: the revision (only PATCH moves it, ADR-0016), the location, the bill,
  the payment methods; and for a refusal, everything, ``revisedAt`` and the ETag included.

And every event body is checked against its source (S4.1-AC3): the **last stored notification**
when there is one, otherwise the **first payment method** with the status update's payment
details.

A row Java contradicts is a strict expected failure carrying the row key (S4.1-AC2): it turns red
the day Java and the ADR agree, so the contradiction cannot be fixed, or made worse, silently.
"""
import time

import pytest

from x9client import samples
from x9client.checks import assert_refused
from x9client.samples import BILL, FEDNOW, TX_HASH

HOLDER = "holder@example.com"
OTHER = "other@example.com"
TX_2 = "4p9mLr3Wc8zUgYqS1tXaN5dKiH7fEvB2"
E2E = "E2E-BLACKBOX-MAP-1"

# Past x9.reservation.ttl-seconds (90 s, the default the black-box deployment runs with).
LAPSED_MS = 95_000

RIWS = "RECEIVED_IN_WRONG_STATUS"
ACH = "ACTION_CONTRADICTS_HASH"

INITIATED, SENT, FAILED = "payment.initiated", "payment.sent", "payment.failed"
CLEARED, CANCELLED_EVT = "payment.cleared", "payment.cancelled"

CHAIN = "solana"
RAILS = {
    "solana": {"create": {}, "notify": {}, "paid_on": "solana"},
    "fednow": {"create": {"currency": "USD", "networks": {"fednow": FEDNOW}},
               "notify": {"currency": "USD", "network": "FedNow", "blockchain": False},
               "paid_on": "fednow"},
    "rtp": {"create": {"currency": "USD", "networks": {"rtp": FEDNOW}},
            "notify": {"currency": "USD", "network": "RTP", "blockchain": False},
            "paid_on": "rtp"},
    "ach": {"create": {"currency": "USD", "networks": {"ach": FEDNOW}},
            "notify": {"currency": "USD", "network": "ACH", "blockchain": False,
                       "expected_date": "2030-01-02T00:00:00Z"},
            "paid_on": "ach"},
}
BANK_RAILS = ("fednow", "rtp", "ach")


def xfail(row, adr_says, java_does):
    """A cell of the map Java contradicts (S4.1-AC2): strict, so it turns red once they agree."""
    return pytest.mark.xfail(strict=True, reason=f"{row}: the ADR says {adr_says}; "
                                                 f"Java {java_does}. See 'Questions for the owner'.")


# ------------------------------------------------------------------ the payer's messages

def send(api, qr, rail=CHAIN, *, signer=None, payer_info=HOLDER, **kwargs):
    """A payer's notification on ``rail``; returns ``(response, body sent)``."""
    body = samples.notification(qr, payer_info=payer_info, **{**RAILS[rail]["notify"], **kwargs})
    token = (signer or api).sign(body)
    return api.send_notification(token), body


def pre(api, qr, rail=CHAIN, **kwargs):
    """A pre-commit: no transactionId."""
    return send(api, qr, rail, **kwargs)


def post(api, qr, rail=CHAIN, *, tx=None, **kwargs):
    """A post-commit: transactionId present (the txHash on a chain, an End-to-End ID on a bank)."""
    if rail == CHAIN:
        kwargs.setdefault("action", "SENT")
    return send(api, qr, rail, transaction_id=tx or (TX_HASH if rail == CHAIN else E2E), **kwargs)


def accepted(result):
    response, body = result
    assert response.status == 200, f"setup: the notification was not accepted: {response}"
    return body


# ------------------------------------------------------------------ fixtures in a given status

def create(api, rail=CHAIN):
    return api.create_qr(**RAILS[rail]["create"])


def held_by_holder(api, rail=CHAIN, *, payer_info=HOLDER, signer=None):
    """PAYMENT_INITIATED, held by the holder's pre-commit; returns ``(qr, notification)``."""
    qr = create(api, rail)
    return qr, accepted(pre(api, qr, rail, payer_info=payer_info, signer=signer))


def held_unattributed(api, rail=CHAIN):
    """PAYMENT_INITIATED through the status-update API: held by nobody identifiable."""
    qr = create(api, rail)
    assert api.set_status(qr, "PAYMENT_INITIATED").status == 200
    return qr


def closed(api, status, rail=CHAIN):
    """PAID or CANCELLED through the status-update API, with no payer notification stored."""
    qr = create(api, rail)
    if status == "PAID":
        response = api.mark_paid(qr, end_to_end_id=E2E, network=RAILS[rail]["paid_on"])
    else:
        response = api.set_status(qr, "CANCELLED")
    assert response.status == 200, f"setup: could not reach {status}: {response}"
    return qr


CLOSING_EVENT = {"PAID": CLEARED, "CANCELLED": CANCELLED_EVT}


# ------------------------------------------------------------------ what changed, what did not

STABLE = ("revision", "location", "bill", "paymentMethods", "validUntil", "creditor")


def snap(api, qr):
    response = api.qr(qr)
    assert response.status == 200, f"cannot read {qr}: {response}"
    body = response.body
    return {"status": body.get("status"), "revision": body.get("revision"),
            "revisedAt": body.get("revisedAt"),
            "location": (body.get("location") or {}).get("id"),
            "bill": body.get("bill"), "paymentMethods": body.get("paymentMethods"),
            "validUntil": body.get("validUntil"), "creditor": body.get("creditor"),
            "etag": response.etag}


def assert_kept(before, after):
    """An accepted transition that is not a PATCH: the request and its revision are untouched."""
    for key in STABLE:
        assert after[key] == before[key], f"{key} changed: {before[key]!r} -> {after[key]!r}"


def assert_untouched(before, after):
    """A refusal, a no-op or a lapse: nothing written, revisedAt and ETag included."""
    assert after == before, f"something changed:\n  before {before}\n  after  {after}"


def assert_same_status_is_a_noop(response, before, after, events, qr, setup):
    """A status-update to the status the QR Code already has (stored and effective) is a retry:
    200, nothing written (revisedAt and ETag included), no event (ADR-0020; ADR-0036 matrix)."""
    assert response.status == 200, f"a same-status update is a retry, not a refusal: {response}"
    assert_untouched(before, after)
    assert_events(events, qr, setup)


# ------------------------------------------------------------------ the events

def assert_events(events, qr, expected, *, settle=2.0, timeout=20.0):
    """Every event for ``qr`` since it was created: exactly ``expected``, in order.

    Waits until at least as many as expected have appeared, then ``settle`` more seconds, so one
    that should not exist has had the drain's time to appear too.
    """
    if expected:
        events.for_qr(qr, until=lambda es: len(es) >= len(expected), timeout=timeout)
    time.sleep(settle)
    mine = [e for e in events.read_all()[0] if e.get("qrCodeId") == qr]
    assert [e.get("type") for e in mine] == expected, \
        f"events for {qr}: wanted {expected}, got {[e.get('type') for e in mine]}\n{mine}"
    return mine


def assert_from_notification(event, sent):
    """S4.1-AC3: the body comes from the last stored notification."""
    payment = sent["payment"]
    wanted = {"amount": payment["amount"], "tipAmount": payment.get("tipAmount"),
              "currency": payment["currency"], "network": payment["network"],
              "transactionId": payment.get("transactionId"),
              "payerInfo": (sent.get("payer") or {}).get("info")}
    got = {key: event.get(key) for key in wanted}
    assert got == wanted, f"{event.get('type')} body is not the stored notification's:\n" \
                          f"  wanted {wanted}\n  got    {got}"


def assert_from_first_method(event, *, currency="USDC", network=None, transaction_id=None):
    """S4.1-AC3: with no stored notification, the first payment method and the payment details."""
    wanted = {"amount": BILL, "tipAmount": None, "currency": currency, "network": network,
              "transactionId": transaction_id, "payerInfo": None}
    got = {key: event.get(key) for key in wanted}
    assert got == wanted, f"{event.get('type')} body is not the first payment method's:\n" \
                          f"  wanted {wanted}\n  got    {got}"


def assert_receipt(event, received_in, deviations=()):
    """How a payer's message arrived (ADR-0036); ``deviations`` absent, never [], when clean."""
    assert event.get("receivedInStatus") == received_in, \
        f"{event.get('type')}: receivedInStatus {event.get('receivedInStatus')!r}, wanted {received_in}"
    got = event.get("deviations")
    if deviations:
        assert sorted(got or []) == sorted(deviations), \
            f"{event.get('type')}: deviations {got!r}, wanted {sorted(deviations)}"
    else:
        assert got is None, f"{event.get('type')}: deviations must be absent, got {got!r}"


def assert_from_status_update(event):
    """An event the status-update API produced carries no receipt."""
    assert event.get("receivedInStatus") is None and event.get("deviations") is None, event


# ================================================================== create and PATCH

def test_map_001_create(api, events):
    """X9-MAP-001 — create: ACTIVE, no event.

    Source: Ours. ADR-0035 transition map, row "create".
    Why: A consumer reading the stream must not see a payment fact for a bill nobody has touched.
    """
    response = api.create_qr_detailed()
    assert response.status == 201, response
    qr = response.body["id"]

    assert snap(api, qr)["status"] == "ACTIVE"
    assert_events(events, qr, [])


@pytest.mark.parametrize("start", ["ACTIVE", "PAYMENT_INITIATED", "PAID", "CANCELLED"])
def test_map_002_patch(api, events, start):
    """X9-MAP-002 — PATCH: allowed from ACTIVE or PAYMENT_INITIATED, status unchanged, no event;
    refused from PAID and CANCELLED.

    Source: Ours. ADR-0035 row "PATCH" (PatchQRCodeUseCase, stored status); ADR-0016: a valid
    patch moves the revision.
    Why: An edit is not a payment fact. If it emitted, or moved the status, a consumer could not
    tell an edited bill from a paid one.
    """
    if start == "ACTIVE":
        qr, setup = create(api), []
    elif start == "PAYMENT_INITIATED":
        qr, setup = held_by_holder(api)[0], [INITIATED]
    else:
        qr, setup = closed(api, start), [CLOSING_EVENT[start]]
    assert_events(events, qr, setup)
    before = snap(api, qr)

    status, body = api.patch(f"/api/v1/payment-request/{qr}", body={
        "unstructured": "patched", "paymentMethods": [samples.payment_method(amount=BILL + 500)]})

    after = snap(api, qr)
    if start in ("PAID", "CANCELLED"):
        assert_refused((status, body), naming="must be active to be updated", status=400)
        assert_untouched(before, after)
    else:
        assert status == 200, f"{status} {body}"
        assert after["status"] == start, "a PATCH never moves the status"
        assert after["revision"] == before["revision"] + 1, "a valid PATCH moves the revision"
        assert after["paymentMethods"][0]["amount"] == BILL + 500, "the patch applied"
        for key in ("location", "bill", "validUntil", "creditor"):
            assert after[key] == before[key], key
    assert_events(events, qr, setup)


# ================================================================== the status-update API

@pytest.mark.parametrize("start", [
    "ACTIVE",
    "held", "PAID", "CANCELLED"])
def test_map_010_status_update_payment_initiated(api, events, start):
    """X9-MAP-010 — status-update PAYMENT_INITIATED: from ACTIVE, PAYMENT_INITIATED held by nobody
    identifiable, payment.initiated; on a live reservation a retry, 200 and nothing changes
    (ADR-0020); refused 409 from PAID and CANCELLED.

    Source: Ours. ADR-0035 row "status-update PAYMENT_INITIATED"; ADR-0036 matrix, "→
    PAYMENT_INITIATED"; ADR-0021 (an unattributed reservation matches nobody).
    Why: The payee recording a reservation it made elsewhere must keep every payer out, and must
    not hand an open reservation to whichever payer asks next.
    """
    if start == "ACTIVE":
        qr, setup = create(api), []
    elif start == "held":
        qr, setup = held_by_holder(api)[0], [INITIATED]
    else:
        qr, setup = closed(api, start), [CLOSING_EVENT[start]]
    assert_events(events, qr, setup)
    before = snap(api, qr)

    response = api.set_status(qr, "PAYMENT_INITIATED")

    after = snap(api, qr)
    if start == "held":
        assert_same_status_is_a_noop(response, before, after, events, qr, setup)
        return
    if start != "ACTIVE":
        assert response.status == 409, response
        assert_untouched(before, after)
        assert_events(events, qr, setup)
        return

    assert response.status == 200, response
    assert after["status"] == "PAYMENT_INITIATED"
    assert_kept(before, after)
    initiated, = assert_events(events, qr, [INITIATED])
    assert_from_first_method(initiated)
    assert_from_status_update(initiated)
    refused, _ = pre(api, qr)
    assert refused.status == 409, f"an unattributed reservation matches no payer: {refused}"


@pytest.mark.serial
def test_map_010_status_update_payment_initiated_on_a_lapsed_reservation(api, events, clock):
    """X9-MAP-010 — status-update PAYMENT_INITIATED on a lapsed reservation counts as ACTIVE.

    Source: Ours. ADR-0035 row "status-update PAYMENT_INITIATED", allowed from ACTIVE
    (effective).
    Why: After the window passes the QR Code reads ACTIVE everywhere; a payee reserving it then
    must actually reserve it, or the next payer walks straight in.
    """
    qr, _ = held_by_holder(api)
    clock.move_by(LAPSED_MS)
    assert snap(api, qr)["status"] == "ACTIVE"

    response = api.set_status(qr, "PAYMENT_INITIATED")

    assert response.status == 200, response
    assert_events(events, qr, [INITIATED, INITIATED])
    refused, _ = pre(api, qr, payer_info=OTHER)
    assert refused.status == 409, f"the payee's reservation keeps payers out: {refused}"


@pytest.mark.parametrize("start", [
    "ACTIVE", "held", "held_with_hash", "unattributed", "CANCELLED", "PAID"])
def test_map_011_status_update_paid(api, events, start):
    """X9-MAP-011 — status-update PAID: from ACTIVE or PAYMENT_INITIATED (stored), PAID,
    payment.cleared; on PAID a retry, 200 and nothing changes (ADR-0020); refused from CANCELLED.

    Source: Ours. ADR-0035 row "status-update PAID"; ADR-0020; ADR-0036 ("only the payment system
    marks a QR Code PAID").
    Why: The one door to PAID. The cleared event's body is the last notification's when a payer
    sent one (its amount, payer and hash), which is what a consumer reconciles against (ADR-0014);
    otherwise the first payment method's with the payment details given here.
    """
    sent = None
    if start == "ACTIVE":
        qr, setup = create(api), []
    elif start == "held":
        qr, sent = held_by_holder(api)
        setup = [INITIATED]
    elif start == "held_with_hash":
        qr, _ = held_by_holder(api)
        sent = accepted(post(api, qr))
        setup = [INITIATED, SENT]
    elif start == "unattributed":
        qr, setup = held_unattributed(api), [INITIATED]
    else:
        qr, setup = closed(api, start), [CLOSING_EVENT[start]]
    assert_events(events, qr, setup)
    before = snap(api, qr)

    response = api.mark_paid(qr, end_to_end_id="E2E-MAP-PAID", network="solana")

    after = snap(api, qr)
    if start == "PAID":
        assert_same_status_is_a_noop(response, before, after, events, qr, setup)
        return
    if start == "CANCELLED":
        assert response.status == 400, response
        assert_untouched(before, after)
        assert_events(events, qr, setup)
        return

    assert response.status == 200, response
    assert after["status"] == "PAID"
    assert_kept(before, after)
    cleared = assert_events(events, qr, setup + [CLEARED])[-1]
    assert_from_status_update(cleared)
    if sent:
        assert_from_notification(cleared, sent)
    else:
        assert_from_first_method(cleared, network="solana", transaction_id="E2E-MAP-PAID")


@pytest.mark.parametrize("start", [
    "ACTIVE", "held", "unattributed", "PAID", "CANCELLED"])
def test_map_012_status_update_cancelled(api, events, start):
    """X9-MAP-012 — status-update CANCELLED: from ACTIVE or PAYMENT_INITIATED (stored), CANCELLED,
    payment.cancelled; on CANCELLED a retry, 200 and nothing changes (ADR-0020); refused from PAID.

    Source: Ours. ADR-0035 row "status-update CANCELLED"; ADR-0036 matrix.
    Why: Withdrawing a bill closes it for good; the event says so once, with the body of the last
    payer notification if there was one, so the consumer knows whose reservation it ended.
    """
    sent = None
    if start == "ACTIVE":
        qr, setup = create(api), []
    elif start == "held":
        qr, sent = held_by_holder(api)
        setup = [INITIATED]
    elif start == "unattributed":
        qr, setup = held_unattributed(api), [INITIATED]
    else:
        qr, setup = closed(api, start), [CLOSING_EVENT[start]]
    assert_events(events, qr, setup)
    before = snap(api, qr)

    response = api.set_status(qr, "CANCELLED")

    after = snap(api, qr)
    if start == "CANCELLED":
        assert_same_status_is_a_noop(response, before, after, events, qr, setup)
        return
    if start == "PAID":
        assert response.status == 400, response
        assert_untouched(before, after)
        assert_events(events, qr, setup)
        return

    assert response.status == 200, response
    assert after["status"] == "CANCELLED"
    assert_kept(before, after)
    cancelled = assert_events(events, qr, setup + [CANCELLED_EVT])[-1]
    assert_from_status_update(cancelled)
    if sent:
        assert_from_notification(cancelled, sent)
    else:
        assert_from_first_method(cancelled)


@pytest.mark.parametrize("start", [
    "held", "held_with_hash", "unattributed", "PAID", "CANCELLED", "ACTIVE"])
def test_map_013_status_update_active_reactivates(api, events, start):
    """X9-MAP-013 — status-update ACTIVE (reactivate): from PAYMENT_INITIATED (effective), ACTIVE,
    reservation cleared, NO event; on ACTIVE a retry, 200 and nothing changes (ADR-0020); refused
    400 from PAID and CANCELLED.

    Source: Ours. ADR-0035 row "status-update ACTIVE"; ADR-0019 (no event on a reactivate);
    ADR-0036 (the payment system's only way back once a hash is registered).
    Why: Reactivating is the payment system undoing a reservation, not a payment fact. Once it
    has, the reservation is really gone: another payer can take the QR Code.
    """
    if start == "ACTIVE":
        qr, setup = create(api), []
    elif start == "held":
        qr, setup = held_by_holder(api)[0], [INITIATED]
    elif start == "held_with_hash":
        qr, _ = held_by_holder(api)
        accepted(post(api, qr))
        setup = [INITIATED, SENT]
    elif start == "unattributed":
        qr, setup = held_unattributed(api), [INITIATED]
    else:
        qr, setup = closed(api, start), [CLOSING_EVENT[start]]
    assert_events(events, qr, setup)
    before = snap(api, qr)

    response = api.set_status(qr, "ACTIVE")

    after = snap(api, qr)
    if start == "ACTIVE":
        assert_same_status_is_a_noop(response, before, after, events, qr, setup)
        return
    if start in ("PAID", "CANCELLED"):
        assert response.status == 400, response
        assert_untouched(before, after)
        assert_events(events, qr, setup)
        return

    assert response.status == 200, response
    assert after["status"] == "ACTIVE"
    assert_kept(before, after)
    assert_events(events, qr, setup)
    taken, _ = pre(api, qr, payer_info=OTHER)
    assert taken.status == 200, f"the reservation is gone, another payer may take it: {taken}"


@pytest.mark.serial
def test_map_013_status_update_active_on_a_lapsed_reservation_is_refused(api, events, clock):
    """X9-MAP-013 — reactivating a lapsed reservation is refused: it already reads ACTIVE.

    Source: Ours. ADR-0035 row "status-update ACTIVE", allowed from PAYMENT_INITIATED
    (effective); ADR-0036 matrix, "→ ACTIVE" on ACTIVE: 400.
    Why: Pins that the decision reads the effective status, as every other one does.
    """
    qr, _ = held_by_holder(api)
    clock.move_by(LAPSED_MS)
    before = snap(api, qr)

    response = api.set_status(qr, "ACTIVE")

    assert response.status == 400, response
    assert_untouched(before, snap(api, qr))
    assert_events(events, qr, [INITIATED])


# ================================================================== pre-commit

@pytest.mark.parametrize("rail", list(RAILS))
def test_map_020_pre_commit_new_holder(api, events, payer_b, rail):
    """X9-MAP-020 — a pre-commit on ACTIVE, any rail: PAYMENT_INITIATED held by the sender,
    payment.initiated.

    Source: Ours. ADR-0035 row "pre-commit, any rail, new holder"; ADR-0036 ("Pre-commit on every
    rail").
    Why: The reservation is what stops two payers paying one QR Code, on FedNow and RTP as much
    as on a chain.
    """
    qr = create(api, rail)
    before = snap(api, qr)

    response, sent = pre(api, qr, rail)

    assert response.status == 200, response
    after = snap(api, qr)
    assert after["status"] == "PAYMENT_INITIATED"
    assert_kept(before, after)
    initiated, = assert_events(events, qr, [INITIATED])
    assert_from_notification(initiated, sent)
    assert_receipt(initiated, "ACTIVE")
    other, _ = pre(api, qr, rail, signer=payer_b, payer_info=OTHER)
    assert other.status == 409, f"held by the sender, so another payer is refused: {other}"


@pytest.mark.serial
def test_map_020_pre_commit_takes_over_a_lapsed_reservation(api, events, clock, payer_b):
    """X9-MAP-020 — winning a pre-commit on a lapsed reservation emits payment.initiated for the
    new holder.

    Source: Ours. ADR-0035 row "pre-commit, any rail, new holder" ("also on a takeover of a
    lapsed reservation"); ADR-0036; S2.5-AC5.
    Why: Otherwise the stream names the old payer as holding a QR Code somebody else is paying.
    """
    qr, _ = held_by_holder(api)
    clock.move_by(LAPSED_MS)

    response, sent = pre(api, qr, signer=payer_b, payer_info=OTHER)

    assert response.status == 200, response
    assert snap(api, qr)["status"] == "PAYMENT_INITIATED"
    first, second = assert_events(events, qr, [INITIATED, INITIATED])
    assert_from_notification(second, sent)
    assert_receipt(second, "ACTIVE")
    old, _ = pre(api, qr)
    assert old.status == 409, f"the new holder holds it; the old one is another payer: {old}"


@pytest.mark.parametrize("rail", ["solana", "fednow"])
def test_map_021_pre_commit_retry_by_the_holder(api, events, rail):
    """X9-MAP-021 — the holder's retry: unchanged, no event.

    Source: Ours. ADR-0035 row "pre-commit, any rail, same holder (retry)"; ADR-0021; ADR-0036.
    Why: A payer that timed out must be able to ask again; a second payment.initiated would look
    like a second payer.
    """
    qr, _ = held_by_holder(api, rail)
    assert_events(events, qr, [INITIATED])
    before = snap(api, qr)

    response, _ = pre(api, qr, rail)

    assert response.status == 200, response
    after = snap(api, qr)
    assert after["status"] == "PAYMENT_INITIATED"
    assert_kept(before, after)
    assert_events(events, qr, [INITIATED])


@pytest.mark.serial
def test_map_021_a_retry_refreshes_the_window(api, events, clock):
    """X9-MAP-021 — a retry refreshes the reservation's window.

    Source: Ours. ADR-0035 row "pre-commit, any rail, same holder (retry)": "window refreshed".
    Why: A holder still working on its payment must not lose the QR Code to the clock it already
    reset by asking again.
    """
    qr, _ = held_by_holder(api)
    clock.move_by(60_000)
    accepted(pre(api, qr))

    clock.move_by(60_000 + LAPSED_MS - 30_000)
    assert snap(api, qr)["status"] == "PAYMENT_INITIATED", "past the first window, inside the new"
    clock.move_by(60_000 + LAPSED_MS)
    assert snap(api, qr)["status"] == "ACTIVE", "and the refreshed window still ends"
    assert_events(events, qr, [INITIATED])


@pytest.mark.parametrize("rail", ["solana", "fednow"])
def test_map_022_pre_commit_once_a_hash_is_registered(api, events, rail):
    """X9-MAP-022 — a pre-commit once a hash is registered, the holder's included: 409.

    Source: Ours. ADR-0035 row "pre-commit, any rail, once a hash is registered"; ADR-0036 §.
    Why: Money has moved for this QR Code; no payer message may change its status now.
    """
    qr, _ = held_by_holder(api, rail)
    accepted(post(api, qr, rail))
    setup = [INITIATED, SENT] if rail == CHAIN else [INITIATED]
    assert_events(events, qr, setup)
    before = snap(api, qr)

    response, _ = pre(api, qr, rail)

    assert response.status == 409, response
    assert_untouched(before, snap(api, qr))
    assert_events(events, qr, setup)


@pytest.mark.parametrize("rail", ["solana", "fednow"])
def test_map_023_pre_commit_retry_refused_by_the_gate(api, events, rail):
    """X9-MAP-023 — the holder's retry, refused by the gate: 400, ACTIVE, reservation cleared;
    payment.failed on a chain, nothing on a bank rail.

    Source: Ours. ADR-0035 row "pre-commit, any rail, same holder, refused by the gate"; S2.5-AC2.
    Why: A payer the payee has just said no to must not keep everyone else out until its window
    lapses. The failed event's body is the last STORED notification, the accepted first one: the
    refused retry is not stored.
    """
    qr, first = held_by_holder(api, rail)
    assert_events(events, qr, [INITIATED])
    before = snap(api, qr)

    response, _ = pre(api, qr, rail, amount=BILL + 1)

    assert_refused(response, naming="payment.amount", status=400)
    after = snap(api, qr)
    assert after["status"] == "ACTIVE"
    assert_kept(before, after)
    if rail == CHAIN:
        failed = assert_events(events, qr, [INITIATED, FAILED])[-1]
        assert_from_notification(failed, first)
        assert_receipt(failed, "PAYMENT_INITIATED")
        assert "refused" in (failed.get("reason") or ""), failed
    else:
        assert_events(events, qr, [INITIATED])
    taken, _ = pre(api, qr, rail, payer_info=OTHER)
    assert taken.status == 200, f"the reservation was cleared: {taken}"


@pytest.mark.parametrize("holder", [
    "another_payer_info", "another_certificate", "holder_without_payer_info", "unattributed"])
def test_map_024_pre_commit_from_another_payer(api, events, payer_a, payer_b, holder):
    """X9-MAP-024 — a pre-commit while someone else holds the QR Code: 409, nothing changes.

    Source: Ours. ADR-0035 row "pre-commit, any rail, another payer"; ADR-0036 ("The same payer
    only when payer.info proves it": both carry payer.info, equal, and the same certificate).
    Why: Two payers must never both believe they hold one bill. Without payer.info nothing shows
    a retry comes from the holder, so it is another payer.
    """
    if holder == "unattributed":
        qr = held_unattributed(api)
        sender = {"signer": payer_a, "payer_info": HOLDER}
    elif holder == "holder_without_payer_info":
        qr, _ = held_by_holder(api, payer_info=None)
        sender = {"signer": payer_a, "payer_info": None}
    elif holder == "another_certificate":
        qr, _ = held_by_holder(api)
        sender = {"signer": payer_b, "payer_info": HOLDER}
    else:
        qr, _ = held_by_holder(api)
        sender = {"signer": payer_a, "payer_info": OTHER}
    assert_events(events, qr, [INITIATED])
    before = snap(api, qr)

    response, _ = pre(api, qr, **sender)

    assert response.status == 409, response
    assert_untouched(before, snap(api, qr))
    assert_events(events, qr, [INITIATED])


@pytest.mark.parametrize("status", ["PAID", "CANCELLED"])
def test_map_025_pre_commit_on_a_closed_qr_code(api, events, status):
    """X9-MAP-025 — a pre-commit on PAID or CANCELLED: 400, nothing changes.

    Source: Ours. ADR-0035 row "pre-commit, any rail" on PAID or CANCELLED.
    Why: A closed QR Code never reopens; asking to pay it is a mistake the payer must correct.
    """
    qr = closed(api, status)
    assert_events(events, qr, [CLOSING_EVENT[status]])
    before = snap(api, qr)

    response, _ = pre(api, qr)

    assert_refused(response, naming=status, status=400)
    assert_untouched(before, snap(api, qr))
    assert_events(events, qr, [CLOSING_EVENT[status]])


# ================================================================== post-commit

def assert_window_stopped(api, clock, qr):
    """Past the reservation's window, it still holds: a registered hash stops it (ADR-0036)."""
    clock.move_by(LAPSED_MS)
    assert snap(api, qr)["status"] == "PAYMENT_INITIATED", "a registered hash stops the window"


@pytest.mark.serial
@pytest.mark.parametrize("rail", BANK_RAILS)
def test_map_030_post_commit_bank_rail_on_active(api, events, clock, rail):
    """X9-MAP-030 — a bank-rail post-commit on ACTIVE: PAYMENT_INITIATED held by the sender, window
    stopped, payment.initiated flagged RECEIVED_IN_WRONG_STATUS.

    Source: Ours. ADR-0035 row "post-commit, bank rail"; ADR-0036 ("A post-commit on an ACTIVE QR
    Code reserves it"; bank rails never emit payment.sent).
    Why: A FedNow or RTP payer that sends only its courtesy report has still paid; the report
    takes the QR Code out of circulation and tells the payment system the payer skipped a step.
    """
    qr = create(api, rail)
    before = snap(api, qr)

    response, sent = post(api, qr, rail)

    assert response.status == 200, response
    after = snap(api, qr)
    assert after["status"] == "PAYMENT_INITIATED"
    assert_kept(before, after)
    initiated, = assert_events(events, qr, [INITIATED])
    assert_from_notification(initiated, sent)
    assert_receipt(initiated, "ACTIVE", [RIWS])
    assert_window_stopped(api, clock, qr)


@pytest.mark.serial
def test_map_031_post_commit_blockchain_on_active(api, events, clock):
    """X9-MAP-031 — a blockchain post-commit on ACTIVE: PAYMENT_INITIATED held by the sender, window
    stopped, payment.initiated then payment.sent, both flagged RECEIVED_IN_WRONG_STATUS.

    Source: Ours. ADR-0035 row "post-commit, blockchain"; ADR-0036.
    Why: The hash is the payee's only link between the funds and the bill; it is registered, and
    the QR Code reserved for the payer that paid it.
    """
    qr = create(api)
    before = snap(api, qr)

    response, sent = post(api, qr)

    assert response.status == 200, response
    after = snap(api, qr)
    assert after["status"] == "PAYMENT_INITIATED"
    assert_kept(before, after)
    initiated, registered = assert_events(events, qr, [INITIATED, SENT])
    for event in (initiated, registered):
        assert_from_notification(event, sent)
        assert_receipt(event, "ACTIVE", [RIWS])

    second, sent_again = post(api, qr, tx=TX_2)
    assert second.status == 200, second
    again = assert_events(events, qr, [INITIATED, SENT, SENT])[-1]
    assert_from_notification(again, sent_again)
    assert_receipt(again, "PAYMENT_INITIATED")  # held by the sender: its next report is clean
    assert_window_stopped(api, clock, qr)


@pytest.mark.serial
def test_map_031_post_commit_on_a_lapsed_reservation_counts_as_active(api, events, clock):
    """X9-MAP-031 — a post-commit on a lapsed reservation is received in ACTIVE.

    Source: Ours. ADR-0035 rows "post-commit" on ACTIVE (effective; a lapsed reservation counts);
    ADR-0036 ("paid after its reservation lapsed").
    Why: The payer paid after its window passed; the registration must say so.
    """
    qr, _ = held_by_holder(api)
    clock.move_by(LAPSED_MS)

    response, sent = post(api, qr)

    assert response.status == 200, response
    assert snap(api, qr)["status"] == "PAYMENT_INITIATED"
    _, initiated, registered = assert_events(events, qr, [INITIATED, INITIATED, SENT])
    for event in (initiated, registered):
        assert_from_notification(event, sent)
        assert_receipt(event, "ACTIVE", [RIWS])


@pytest.mark.serial
@pytest.mark.parametrize("rail", ["solana", "fednow"])
def test_map_032_post_commit_by_the_holder(api, events, clock, rail):
    """X9-MAP-032 — the holder's post-commit: status unchanged, window stopped; payment.sent on a
    chain, unflagged; nothing on a bank rail.

    Source: Ours. ADR-0035 row "post-commit, any rail" held by the sender; ADR-0036.
    Why: The happy path's second half. Clean, so the payment system can tell it from every
    deviation.
    """
    qr, _ = held_by_holder(api, rail)
    assert_events(events, qr, [INITIATED])
    before = snap(api, qr)

    response, sent = post(api, qr, rail)

    assert response.status == 200, response
    after = snap(api, qr)
    assert after["status"] == "PAYMENT_INITIATED"
    assert_kept(before, after)
    if rail == CHAIN:
        registered = assert_events(events, qr, [INITIATED, SENT])[-1]
        assert_from_notification(registered, sent)
        assert_receipt(registered, "PAYMENT_INITIATED")
    else:
        assert_events(events, qr, [INITIATED])
    assert_window_stopped(api, clock, qr)


@pytest.mark.serial
@pytest.mark.parametrize("case", [
    "another_payer", "sender_without_payer_info", "unattributed", "fednow_another_payer"])
def test_map_033_post_commit_while_someone_else_holds_it(api, events, clock, payer_b, case):
    """X9-MAP-033 — a post-commit while someone else holds the QR Code: unchanged, the holder keeps
    it, window stopped; payment.sent flagged on a chain, nothing on a bank rail.

    Source: Ours. ADR-0035 row "post-commit, any rail" held by someone else; ADR-0036 ("A
    post-commit on a closed or held QR Code is registered, and changes nothing"; without
    payer.info the sender is never the holder).
    Why: The money has moved, whoever moved it; refusing would hide it from the payment system.
    The stranger gains nothing over the bill.
    """
    rail = "fednow" if case == "fednow_another_payer" else CHAIN
    if case == "unattributed":
        qr = held_unattributed(api)
    else:
        qr, _ = held_by_holder(api, rail)
    sender = {"signer": payer_b, "payer_info": None if case == "sender_without_payer_info" else OTHER}
    assert_events(events, qr, [INITIATED])
    before = snap(api, qr)

    response, sent = post(api, qr, rail, **sender)

    assert response.status == 200, f"registered, not refused: {response}"
    after = snap(api, qr)
    assert after["status"] == "PAYMENT_INITIATED"
    assert_kept(before, after)
    if rail != CHAIN:
        assert_events(events, qr, [INITIATED])
        assert_window_stopped(api, clock, qr)
        return
    registered = assert_events(events, qr, [INITIATED, SENT])[-1]
    assert_from_notification(registered, sent)
    assert_receipt(registered, "PAYMENT_INITIATED", [RIWS])
    if case != "unattributed":
        own, own_sent = post(api, qr, tx=TX_2)
        assert own.status == 200, own
        mine = assert_events(events, qr, [INITIATED, SENT, SENT])[-1]
        assert_receipt(mine, "PAYMENT_INITIATED")  # the holder kept it: its report is clean
        assert_from_notification(mine, own_sent)
    assert_window_stopped(api, clock, qr)


@pytest.mark.parametrize("status, rail", [
    ("PAID", "solana"), ("CANCELLED", "solana"), ("PAID", "fednow"), ("CANCELLED", "fednow")])
def test_map_034_post_commit_on_a_closed_qr_code(api, events, status, rail):
    """X9-MAP-034 — a post-commit on PAID or CANCELLED: unchanged, never reopens; payment.sent
    flagged on a chain, nothing on a bank rail.

    Source: Ours. ADR-0035 row "post-commit, any rail" on PAID or CANCELLED; ADR-0036.
    Why: Money arriving for a bill already closed is exactly what the payment system needs to
    hear about, with the payer named and flagged.
    """
    qr = closed(api, status, rail)
    setup = [CLOSING_EVENT[status]]
    assert_events(events, qr, setup)
    before = snap(api, qr)

    response, sent = post(api, qr, rail)

    assert response.status == 200, f"registered, not refused: {response}"
    after = snap(api, qr)
    assert after["status"] == status, "a closed QR Code never reopens"
    assert_kept(before, after)
    if rail == CHAIN:
        registered = assert_events(events, qr, setup + [SENT])[-1]
        assert_from_notification(registered, sent)
        assert_receipt(registered, status, [RIWS])
    else:
        assert_events(events, qr, setup)


# ================================================================== what blockchain.action says

@pytest.mark.parametrize("start", ["ACTIVE", "held", "held_by_another", "PAID"])
def test_map_040_payment_initiated_with_a_hash(api, events, payer_b, start):
    """X9-MAP-040 — blockchain PAYMENT_INITIATED carrying a hash: as the post-commit row for that
    status, plus ACTION_CONTRADICTS_HASH.

    Source: Ours. ADR-0035 row "blockchain PAYMENT_INITIATED with a hash"; ADR-0036 ("A
    contradictory message is the payer's fault, and its hash is still recorded"); ADR-0004.
    Why: The evidence decides the phase. Refusing would throw away the hash of money that has
    already moved.
    """
    if start == "ACTIVE":
        qr, setup = create(api), []
    elif start in ("held", "held_by_another"):
        qr, setup = held_by_holder(api)[0], [INITIATED]
    else:
        qr, setup = closed(api, "PAID"), [CLEARED]
    assert_events(events, qr, setup)
    sender = {"signer": payer_b, "payer_info": OTHER} if start == "held_by_another" else {}

    response, sent = send(api, qr, action="PAYMENT_INITIATED", transaction_id=TX_HASH, **sender)

    assert response.status == 200, response
    if start == "ACTIVE":
        new = assert_events(events, qr, [INITIATED, SENT])
        wanted_status, received_in, flags = "PAYMENT_INITIATED", "ACTIVE", [RIWS, ACH]
    else:
        new = assert_events(events, qr, setup + [SENT])[len(setup):]
        wanted_status = "PAID" if start == "PAID" else "PAYMENT_INITIATED"
        received_in = wanted_status
        flags = [ACH] if start == "held" else [RIWS, ACH]
    assert snap(api, qr)["status"] == wanted_status
    for event in new:
        assert_from_notification(event, sent)
        assert_receipt(event, received_in, flags)


@pytest.mark.parametrize("start", ["ACTIVE", "held", "held_by_another", "PAID", "CANCELLED"])
def test_map_041_sent_without_a_hash(api, events, payer_b, start):
    """X9-MAP-041 — blockchain SENT without a hash: 400 in every status, nothing changes.

    Source: Ours. ADR-0035 row "blockchain SENT without a hash"; ADR-0036.
    Why: A committed transaction with no hash cannot exist, and there is nothing to record.
    """
    if start == "ACTIVE":
        qr, setup = create(api), []
    elif start in ("held", "held_by_another"):
        qr, setup = held_by_holder(api)[0], [INITIATED]
    else:
        qr, setup = closed(api, start), [CLOSING_EVENT[start]]
    assert_events(events, qr, setup)
    before = snap(api, qr)
    sender = {"signer": payer_b, "payer_info": OTHER} if start == "held_by_another" else {}

    response, _ = send(api, qr, action="SENT", **sender)

    assert_refused(response, naming="transactionId", status=400)
    assert_untouched(before, snap(api, qr))
    assert_events(events, qr, setup)


def test_map_042_not_sent_by_the_holder(api, events):
    """X9-MAP-042 — the holder's NOT_SENT: ACTIVE, reservation cleared, payment.failed.

    Source: Ours. ADR-0035 row "blockchain NOT_SENT (holder)"; ADR-0021.
    Why: The one door a payer has out of a reservation; it must release, and say so on the
    stream, with the NOT_SENT itself as the body (it is stored before the event is built).
    """
    qr, _ = held_by_holder(api)
    assert_events(events, qr, [INITIATED])
    before = snap(api, qr)

    response, sent = send(api, qr, action="NOT_SENT")

    assert response.status == 200, response
    after = snap(api, qr)
    assert after["status"] == "ACTIVE"
    assert_kept(before, after)
    failed = assert_events(events, qr, [INITIATED, FAILED])[-1]
    assert_from_notification(failed, sent)
    assert_receipt(failed, "PAYMENT_INITIATED")
    taken, _ = pre(api, qr, payer_info=OTHER)
    assert taken.status == 200, f"the reservation was cleared: {taken}"


@pytest.mark.serial
def test_map_042_not_sent_by_the_holder_of_a_lapsed_reservation(api, events, clock):
    """X9-MAP-042 — NOT_SENT from the holder of a lapsed reservation: accepted, payment.failed.

    Source: Ours. ADR-0035 row "blockchain NOT_SENT (holder)": "or a lapsed reservation of the
    sender's"; ADR-0036 matrix (ACTIVE column).
    Why: Nobody has taken it since, so it is still the holder's to give back, and the stream must
    hear the payment did not proceed.
    """
    qr, _ = held_by_holder(api)
    clock.move_by(LAPSED_MS)

    response, sent = send(api, qr, action="NOT_SENT")

    assert response.status == 200, response
    assert snap(api, qr)["status"] == "ACTIVE"
    failed = assert_events(events, qr, [INITIATED, FAILED])[-1]
    assert_from_notification(failed, sent)
    assert_receipt(failed, "ACTIVE")


def test_map_043_not_sent_once_a_hash_is_registered(api, events):
    """X9-MAP-043 — the holder's NOT_SENT once a hash is registered: accepted, nothing changes, no
    event (ACTION_CONTRADICTS_HASH is in the warning only).

    Source: Ours. ADR-0035 row "blockchain NOT_SENT (holder), once a hash is registered";
    ADR-0036 §.
    Why: Money has moved; "not sent" cannot release it, and payment.failed would tell the payment
    system something untrue.
    """
    qr, _ = held_by_holder(api)
    accepted(post(api, qr))
    assert_events(events, qr, [INITIATED, SENT])
    before = snap(api, qr)

    response, _ = send(api, qr, action="NOT_SENT")

    assert response.status == 200, response
    after = snap(api, qr)
    assert after["status"] == "PAYMENT_INITIATED"
    assert_kept(before, after)
    assert_events(events, qr, [INITIATED, SENT])


@pytest.mark.parametrize("start", ["ACTIVE", "held"])
def test_map_044_not_sent_with_a_transaction_id(api, events, start):
    """X9-MAP-044 — NOT_SENT carrying a transactionId: as the post-commit row for that status, plus
    ACTION_CONTRADICTS_HASH.

    Source: Ours. ADR-0035 row "blockchain NOT_SENT with a transactionId"; ADR-0036 (the evidence
    decides).
    Why: A hash means the money moved, whatever the action says; its registration is kept.
    """
    if start == "ACTIVE":
        qr, setup = create(api), []
    else:
        qr, setup = held_by_holder(api)[0], [INITIATED]
    assert_events(events, qr, setup)

    response, sent = send(api, qr, action="NOT_SENT", transaction_id=TX_HASH)

    assert response.status == 200, response
    assert snap(api, qr)["status"] == "PAYMENT_INITIATED"
    if start == "ACTIVE":
        new, received_in, flags = assert_events(events, qr, [INITIATED, SENT]), "ACTIVE", [RIWS, ACH]
    else:
        new = assert_events(events, qr, [INITIATED, SENT])[1:]
        received_in, flags = "PAYMENT_INITIATED", [ACH]
    for event in new:
        assert_from_notification(event, sent)
        assert_receipt(event, received_in, flags)


@pytest.mark.parametrize("start, status, naming", [
    ("held_by_another", 409, "not yours to release"),
    ("holder_without_payer_info", 409, "not yours to release"),
    ("ACTIVE", 400, "blockchain.action"),
    ("PAID", 400, "blockchain.action"),
    ("CANCELLED", 400, "blockchain.action")])
def test_map_045_not_sent_refused(api, events, payer_b, start, status, naming):
    """X9-MAP-045 — NOT_SENT not from the holder: 409 while someone else holds it; 400 on ACTIVE
    with no reservation of the sender's, PAID, CANCELLED. Nothing changes.

    Source: Ours. ADR-0035 row "blockchain NOT_SENT" (refused); ADR-0036 (without payer.info the
    sender is never the holder).
    Why: Otherwise NOT_SENT knocks any payer off any bill.
    """
    sender = {}
    if start == "held_by_another":
        qr, setup = held_by_holder(api)[0], [INITIATED]
        sender = {"signer": payer_b, "payer_info": OTHER}
    elif start == "holder_without_payer_info":
        qr, setup = held_by_holder(api, payer_info=None)[0], [INITIATED]
        sender = {"payer_info": None}
    elif start == "ACTIVE":
        qr, setup = create(api), []
    else:
        qr, setup = closed(api, start), [CLOSING_EVENT[start]]
    assert_events(events, qr, setup)
    before = snap(api, qr)

    response, _ = send(api, qr, action="NOT_SENT", **sender)

    assert_refused(response, naming=naming, status=status)
    assert_untouched(before, snap(api, qr))
    assert_events(events, qr, setup)


# ================================================================== location and time

def test_map_050_location_released_by_a_rebind(api, events):
    """X9-MAP-050 — a location released by a rebind: the donor (not live) keeps its status, gets a
    new random location, no event.

    Source: Ours. ADR-0035 row "location released by a rebind"; ADR-0024.
    Why: Handing a location over is bookkeeping about a printed code, not a payment fact.
    """
    donor, taker = create(api), create(api)
    assert api.set_status(donor, "CANCELLED").status == 200
    assert_events(events, donor, [CANCELLED_EVT])
    before = snap(api, donor)
    current = api.qr(taker)
    body = {"locationId": before["location"],
            "bill": {"description": "blackbox", "paymentTiming": "immediate",
                     "amountDue": samples.patch_amount_due(current.body["bill"]["amountDue"])},
            "paymentMethods": [{k: pm[k] for k in ("currency", "validUntil", "amount", "networks")}
                               for pm in current.body["paymentMethods"]]}

    response = api.patch(f"/api/v1/payment-request/{taker}", body=body, if_match=current.etag)

    assert response.status == 200, response
    after = snap(api, donor)
    assert after["status"] == "CANCELLED"
    assert after["location"] and after["location"] != before["location"], "a new random location"
    for key in ("revision", "bill", "paymentMethods", "validUntil", "creditor"):
        assert after[key] == before[key], key
    assert_events(events, donor, [CANCELLED_EVT])
    assert_events(events, taker, [])


@pytest.mark.serial
def test_map_051_a_reservation_lapses_on_read_only(api, events, clock):
    """X9-MAP-051 — the reservation's window passes: reads ACTIVE, nothing written, no event.

    Source: Ours. ADR-0035 row "reservation lapse"; ADR-0019 (no event on a lapse).
    Why: A lapse is time passing, not a transition; writing or emitting on it would wake systems
    at 3 a.m. for nothing, and the ETag must read the effective status so it is not refused.
    """
    qr, _ = held_by_holder(api)
    before = snap(api, qr)

    clock.move_by(LAPSED_MS)

    after = snap(api, qr)
    assert after["status"] == "ACTIVE"
    assert after["etag"] and "ACTIVE" in after["etag"], after["etag"]
    for key in STABLE + ("revisedAt",):
        assert after[key] == before[key], f"nothing is written on a lapse: {key}"
    assert_events(events, qr, [INITIATED])


@pytest.mark.serial
def test_map_051_never_once_a_hash_is_registered(api, events, clock):
    """X9-MAP-051 — the window never passes once a hash is registered.

    Source: Ours. ADR-0035 row "reservation lapse" ("never once a hash is registered");
    ADR-0036 ("The reservation's window"). The `ttl-seconds: 0` case needs a deployment with that
    setting and is pinned by JUnit (QRCodeEntityNotificationMatrixTest,
    ReservationTtlConfigurationTest), not here.
    Why: Once money has moved, a lapse would let a second payer be approved for a paid bill.
    """
    qr, _ = held_by_holder(api)
    accepted(post(api, qr))

    clock.move_by(LAPSED_MS * 10)

    assert snap(api, qr)["status"] == "PAYMENT_INITIATED"
    assert_events(events, qr, [INITIATED, SENT])


def test_every_map_row_has_a_test():
    """X9-SUITE-502 — every X9-MAP key in ADR-0035's map has a test in test_transition_map.py.

    The row-to-test table is checkable: every X9-MAP key in the ADR has a test here."""
    import pathlib
    import re

    here = pathlib.Path(__file__).resolve()
    adr = next(here.parents[3].glob("docs/adr/0035-*.md"), None)
    if adr is None:
        pytest.skip("the ADR is not shipped with this copy of the suite")
    keys = set(re.findall(r"X9-MAP-(\d{3})", adr.read_text()))
    tested = set(re.findall(r"def test_map_(\d{3})_", here.read_text()))
    assert keys, "the ADR lists no X9-MAP keys"
    assert keys == tested, f"rows without a test: {sorted(keys - tested)}; " \
                           f"tests without a row: {sorted(tested - keys)}"

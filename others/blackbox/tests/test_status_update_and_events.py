# Copyright © 2026 Matera Systems, Inc.
# Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
"""Status update and the event stream, pinned (Story 4.4, plan §4.1 groups F and G).

**Group F, status update (S4.4-AC1).** The transition matrix itself is
``test_transition_map.py`` (Story 4.1): one test per row of ADR-0035's map, every status-update
cell included, with the same-status retry of ADR-0020 (X9-MAP-010 to -013). It is not repeated
here. This module adds what the map does not reach:

- ``PAID`` after ``validUntil`` and after a lapsed reservation (ADR-0020): the payee's order is
  obeyed where a payer would be refused;
- ``CANCELLED`` is final: every door tried, in turn, on one QR Code;
- ``PAYMENT_INITIATED`` through status-update: no payer takes it over while it holds, whoever
  they claim to be; when its window passes it lapses like any other (ADR-0019);
- a stale ``If-Match``, by revision and by status.

**Group G, the event stream (S4.4-AC2).** Every transition emits exactly its event, in order per
QR Code; pages leave no gap; ``hasMore``; ``wait`` above 30; replaying from an old cursor is safe
by ``eventId``.

Other tests write events while these run, so nothing here assumes an empty or quiet log: each
test filters by its own QR Codes, compares windows of the stream rather than the whole of it, and
holds a long poll open only on a cursor past the end of any stream (``END``), where no other
test's event can cut it short.
"""
import time

import pytest

from test_transition_map import (
    CANCELLED_EVT, CLEARED, HOLDER, INITIATED, LAPSED_MS, OTHER, assert_events,
    assert_from_first_method, assert_from_notification, assert_from_status_update, assert_kept,
    assert_untouched, create, held_by_holder, held_unattributed, pre, send, snap)
from x9client import samples
from x9client.samples import BILL
from x9client.checks import assert_refused
from x9client.events import PATH, EventReader

# The largest ULID: no event's cursor is ever after it, so a long poll there always runs its
# whole hold, whatever other tests write meanwhile.
END = "7ZZZZZZZZZZZZZZZZZZZZZZZZZ"

E2E = "E2E-BLACKBOX-S44"


def paid(api, qr):
    return api.mark_paid(qr, end_to_end_id=E2E, network="solana")


# ================================================================== group F: status update

def expiring_qr(api, clock, *, minutes=5):
    """A QR Code whose validUntil is ``minutes`` ahead of the service's clock; returns
    ``(qr, validUntil as a datetime)``."""
    import datetime

    valid_until = (clock.now() + datetime.timedelta(minutes=minutes)).replace(microsecond=0)
    text = valid_until.astimezone(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    return api.create_qr(valid_until=text), valid_until


@pytest.mark.serial
@pytest.mark.parametrize("start", ["ACTIVE", "held"])
def test_paid_after_valid_until(api, events, clock, start):
    """X9-LIFE-170 — past validUntil, the payee may still mark a QR Code PAID: 200,
    payment.cleared, while a payer is refused at the same instant.

    S4.4-AC1 ("PAID after expiry", ADR-0020). Moves the test clock instead of sleeping, and
    checks what X9-LIFE-122 does not: the event and what did not change.

    Source: Ours. ADR-0020: "past validUntil: accepted; expiry is not a status". A reservation's
    window is capped at validUntil (ADR-0019), so the held case is also a lapsed one.
    Why: The payee settling a bill after it lapsed is the exception, analysed by hand, that this
    authority exists for. A well-meant expiry check on the status-update path would remove it.
    """
    import datetime

    qr, valid_until = expiring_qr(api, clock)
    sent = None
    if start == "held":
        sent = pre(api, qr)[1]
        assert snap(api, qr)["status"] == "PAYMENT_INITIATED"
    setup = [INITIATED] if sent else []
    clock.move_to(valid_until + datetime.timedelta(seconds=1))

    refused, _ = pre(api, qr, payer_info=OTHER)
    assert refused.status == 400, f"a payer is refused past validUntil: {refused}"
    before = snap(api, qr)
    assert before["status"] == "ACTIVE", "expiry is not a status; a capped reservation lapsed"

    response = paid(api, qr)

    assert response.status == 200, f"the payee's order is obeyed past validUntil: {response}"
    after = snap(api, qr)
    assert after["status"] == "PAID"
    assert_kept(before, after)
    cleared = assert_events(events, qr, setup + [CLEARED])[-1]
    assert_from_status_update(cleared)
    if sent:
        assert_from_notification(cleared, sent)
    else:
        assert_from_first_method(cleared, network="solana", transaction_id=E2E)


@pytest.mark.serial
@pytest.mark.parametrize("holder", ["payer", "unattributed"])
def test_paid_after_a_lapsed_reservation(api, events, clock, holder):
    """X9-LIFE-171 — PAID on a lapsed reservation: 200, payment.cleared, the body from the
    lapsed holder's notification (or the first payment method when nobody was identifiable).

    S4.4-AC1 ("PAID after a lapsed reservation", ADR-0020). ADR-0035's PAID row reads the
    stored status; this pins that a lapsed one (effective ACTIVE) is accepted as well.

    Source: Ours. ADR-0020: "PAYMENT_INITIATED: accepted, including a reservation that has
    lapsed"; ADR-0035 row "status-update PAID"; ADR-0014 (the event body).
    Why: The payer whose window lapsed may well be the one whose money arrived. Refusing, or
    emitting a body without that payer's notification, would leave the consumer unable to
    reconcile the payment it is being told about.
    """
    if holder == "payer":
        qr, sent = held_by_holder(api)
    else:
        qr, sent = held_unattributed(api), None
    clock.move_by(LAPSED_MS)
    before = snap(api, qr)
    assert before["status"] == "ACTIVE", "the reservation lapsed"

    response = paid(api, qr)

    assert response.status == 200, response
    after = snap(api, qr)
    assert after["status"] == "PAID"
    assert_kept(before, after)
    cleared = assert_events(events, qr, [INITIATED, CLEARED])[-1]
    assert_from_status_update(cleared)
    if sent:
        assert_from_notification(cleared, sent)
    else:
        assert_from_first_method(cleared, network="solana", transaction_id=E2E)


# A PATCH the contract accepts (S4.2 pins the others): a new amount and a note.
REVIVE = {"unstructured": "edited", "paymentMethods": [samples.payment_method(amount=BILL + 500)]}


def test_cancelled_is_final(api, events, payer_b):
    """X9-LIFE-172 — CANCELLED is final: every status-update, pre-commit, NOT_SENT and PATCH is
    answered without changing anything, and the stream holds one payment.cancelled.

    S4.4-AC1 ("CANCELLED final"). Each door has its own map test (X9-MAP-002, -010 to -013,
    -025, -045); this walks them all on ONE QR Code, so a door that reopened it would show up as
    every later answer changing too.

    Source: Ours. ADR-0020 ("a withdrawn bill stays withdrawn"); ADR-0035 rows; ADR-0036 matrix
    (CANCELLED → CANCELLED is a retry: 200, nothing changes).
    Why: The biller ended the receivable. Nothing a payer or the payment system sends afterwards
    may revive it, or a withdrawn bill could be paid.
    """
    qr = create(api)
    assert api.set_status(qr, "CANCELLED").status == 200
    assert_events(events, qr, [CANCELLED_EVT])
    before = snap(api, qr)

    answers = {
        "status-update ACTIVE": api.set_status(qr, "ACTIVE"),
        "status-update PAYMENT_INITIATED": api.set_status(qr, "PAYMENT_INITIATED"),
        "status-update PAID": paid(api, qr),
        "status-update CANCELLED": api.set_status(qr, "CANCELLED"),
        "pre-commit, payer A": pre(api, qr)[0],
        "pre-commit, payer B": pre(api, qr, signer=payer_b, payer_info=OTHER)[0],
        "NOT_SENT": send(api, qr, action="NOT_SENT")[0],
        "PATCH": api.patch(f"/api/v1/payment-request/{qr}", body=REVIVE),
    }

    assert {door: r.status for door, r in answers.items()} == {
        "status-update ACTIVE": 400, "status-update PAYMENT_INITIATED": 409,
        "status-update PAID": 400, "status-update CANCELLED": 200,
        "pre-commit, payer A": 400, "pre-commit, payer B": 400, "NOT_SENT": 400, "PATCH": 400,
    }, answers
    for door in ("pre-commit, payer A", "pre-commit, payer B", "NOT_SENT"):
        assert_refused(answers[door], naming="CANCELLED")
    assert_refused(answers["PATCH"], naming="must be active to be updated")
    assert_untouched(before, snap(api, qr))
    assert_events(events, qr, [CANCELLED_EVT])


def test_a_status_update_reservation_is_taken_over_by_no_payer(api, events, payer_a, payer_b):
    """X9-LIFE-173 — PAYMENT_INITIATED through status-update holds against every payer: the same
    certificate with or without payer.info, another certificate, and a NOT_SENT, each 409.

    S4.4-AC1 ("PAYMENT_INITIATED through status-update (no payer can take it over)").
    X9-MAP-010 tries one payer and X9-MAP-024 one claim; this tries every identity a payer can
    present, in turn, on one reservation.

    Source: Ours. ADR-0021 ("a reservation taken through the payee's own status-update API
    belongs to no payer at all, so no payer may adopt it"); ADR-0036 ("an unattributed
    reservation still matches nobody").
    Why: The payee reserves a QR Code it is settling elsewhere. If any payer could adopt that
    reservation, a second payment would be approved for a bill that is already being paid.
    """
    qr = held_unattributed(api)
    assert_events(events, qr, [INITIATED])
    before = snap(api, qr)

    answers = {
        "payer A, payer.info": pre(api, qr, signer=payer_a, payer_info=HOLDER)[0].status,
        "payer A, no payer.info": pre(api, qr, signer=payer_a, payer_info=None)[0].status,
        "payer B, payer.info": pre(api, qr, signer=payer_b, payer_info=OTHER)[0].status,
        "payer A, NOT_SENT": send(api, qr, action="NOT_SENT")[0].status,
    }

    assert answers == {key: 409 for key in answers}, answers
    assert_untouched(before, snap(api, qr))
    assert_events(events, qr, [INITIATED])


@pytest.mark.serial
def test_a_status_update_reservation_lapses_like_any_other(api, events, clock, payer_b):
    """X9-LIFE-174 — once its window passes, a status-update reservation reads ACTIVE and the
    next payer's pre-commit takes it, with payment.initiated.

    S4.4-AC1 (the limit of "no payer can take it over"). Pinned because it surprises: the
    payee's reservation is not permanent.

    Source: Ours. ADR-0019 ("there were two doors into the reserved state ... both now stamp");
    ADR-0036 matrix, "The reservation's window passes", same for a reservation held by someone
    else. A payee that wants no window runs with `x9.reservation.ttl-seconds: 0`.
    Why: A payment system that reserves and then never answers must not lock a bill until its
    validUntil, exactly as a payer that vanished must not.
    """
    qr = held_unattributed(api)
    clock.move_by(LAPSED_MS)
    assert snap(api, qr)["status"] == "ACTIVE"

    response, sent = pre(api, qr, signer=payer_b, payer_info=OTHER)

    assert response.status == 200, response
    assert snap(api, qr)["status"] == "PAYMENT_INITIATED"
    taken = assert_events(events, qr, [INITIATED, INITIATED])[-1]
    assert_from_notification(taken, sent)


def _mark_paid_body():
    return {"status": "PAID", "endToEndId": E2E, "network": "solana"}


@pytest.mark.parametrize("stale", ["revision", "status"])
@pytest.mark.parametrize("target", ["PAID", "CANCELLED", "ACTIVE", "PAYMENT_INITIATED"])
def test_a_stale_if_match_is_refused(api, events, stale, target):
    """X9-LIFE-175 — a status-update carrying a stale If-Match is refused 412 and changes
    nothing, whether the tag is stale by revision (a PATCH since) or by status (a payer
    reserved since); the current tag is accepted.

    S4.4-AC1 ("a stale If-Match"). The ETag is "<revision>-<STATUS>" (S4.2-AC3 pins its forms);
    here the point is that both halves are checked on the status-update path.

    Source: Ours. ADR-0016 (a revision is a version of the request, not of its status, so the
    status is in the tag on its own).
    Why: A payment system that read the QR Code, then decided, must not overwrite a change it
    never saw: a new amount, or a payer's reservation.
    """
    if stale == "revision":
        qr = create(api) if target != "ACTIVE" else held_unattributed(api)
        old = api.qr(qr).etag
        edited = api.patch(f"/api/v1/payment-request/{qr}", if_match=old, body=REVIVE)
        assert edited.status == 200, f"setup: the PATCH since: {edited}"
    else:
        qr = create(api)
        old = api.qr(qr).etag
        assert pre(api, qr)[0].status == 200
    setup = [e.get("type") for e in events.for_qr(qr, timeout=5)]
    before = snap(api, qr)
    assert before["etag"] != old, "setup: the tag moved"

    body = _mark_paid_body() if target == "PAID" else {"status": target}
    response = api.status_update(qr, body, if_match=old)

    assert response.status == 412, f"a stale tag must not apply: {response}"
    assert_untouched(before, snap(api, qr))
    assert_events(events, qr, setup)
    current = api.status_update(qr, body, if_match=before["etag"])
    assert current.status == 200, f"the current tag is accepted: {current}"


def test_a_retry_with_the_tag_of_the_first_attempt(api, events):
    """X9-LIFE-176 — a payment system that marks PAID with If-Match, times out and sends the
    same request again with the same tag: today 412, the first attempt's PAID and its single
    payment.cleared unchanged.

    S4.4-AC1 (stale If-Match), pinning behaviour rather than endorsing it: the tag the retry
    carries became stale BECAUSE the first attempt succeeded. RFC 9110 §13.1.1 allows a 2xx here
    when the change is already in effect; ADR-0020's retry rule (200, nothing changes) is silent
    on If-Match. See "Questions for the owner" in the Story 4.4 PR.

    Source: Ours (ADR-0020 retry; ADR-0016 tag). Pinned, not decided.
    Why: Either answer is safe for the money (nothing is written twice); the test exists so the
    answer cannot change by accident between builds.
    """
    qr = create(api)
    tag = api.qr(qr).etag
    assert api.status_update(qr, _mark_paid_body(), if_match=tag).status == 200
    assert_events(events, qr, [CLEARED])
    before = snap(api, qr)

    retry = api.status_update(qr, _mark_paid_body(), if_match=tag)

    assert retry.status == 412, f"today's answer changed: {retry}"
    assert_untouched(before, snap(api, qr))
    assert_events(events, qr, [CLEARED])


# ================================================================== group G: the event stream

def page(api, *, after="", limit=100, wait=0):
    """One raw page of the stream, as a dict, insisting on a 200."""
    response = api.get(f"{PATH}?after={after}&limit={limit}&wait={wait}")
    assert response.status == 200 and isinstance(response.body, dict), response
    return response.body


def mine(stream, qrs):
    return [e for e in stream if e.get("qrCodeId") in qrs]


@pytest.fixture
def cursor(api):
    """The end of the stream as it stands now: a private reader, so the shared one is untouched."""
    return EventReader(api).mark()


def read_from(api, after, *, until, timeout=20.0):
    """Every event after ``after``, deduplicated, polled until ``until(events)`` holds."""
    reader = EventReader(api)
    deadline = time.monotonic() + timeout
    while True:
        stream, _ = reader.read_all(after=after)
        if until(stream) or time.monotonic() >= deadline:
            return stream
        time.sleep(0.5)


def test_every_transition_emits_exactly_its_event_in_order(api, events, cursor):
    """X9-EVT-050 — two QR Codes walked through every status-update transition, interleaved:
    each emits exactly its own events, in the order they happened, with a stable shape.

    S4.4-AC2 ("every transition emits exactly its event; per-QR order"). Interleaving is what
    makes the order per QR Code, not per stream, the thing tested.

    Source: Ours. ADR-0035 map (the events per row); ADR-0014 ("events for one QR Code are
    ordered; nothing is promised across QR Codes"); ADR-0037 (seq order, schemaVersion 1.1).
    Why: A consumer applies events per QR Code in stream order. One out of order (cleared before
    initiated) or one missing (a reactivate that emitted nothing is right; a PAID that emitted
    nothing is lost money) and its books are wrong.
    """
    a, b = create(api), create(api)
    for qr, status in [(a, "PAYMENT_INITIATED"), (b, "PAYMENT_INITIATED"), (a, "ACTIVE"),
                       (b, "CANCELLED"), (a, "PAYMENT_INITIATED")]:
        assert api.set_status(qr, status).status == 200, (qr, status)
    assert paid(api, a).status == 200

    stream = read_from(api, cursor, until=lambda s: len(mine(s, {a, b})) >= 5)
    expected = {a: [INITIATED, INITIATED, CLEARED], b: [INITIATED, CANCELLED_EVT]}
    for qr, types in expected.items():
        own = assert_events(events, qr, types)
        assert [e["eventId"] for e in own] == [e["eventId"] for e in mine(stream, {qr})], \
            "the shared reader and a fresh cursor read the same events, in the same order"
        stamps = [e["occurredAt"] for e in own]
        assert stamps == sorted(stamps), f"occurredAt goes backwards for {qr}: {stamps}"
        revisions = [e.get("qrCodeRevision") for e in own]
        assert revisions == sorted(revisions), f"qrCodeRevision goes backwards: {revisions}"
        for event in own:
            assert event["qrCodeId"] == qr, "the short id, as the API gives it"
            assert event["schemaVersion"] == "1.1", event
            assert event.get("eventId"), event
    assert len({e["eventId"] for e in mine(stream, {a, b})}) == 5, "every eventId is distinct"


def test_pages_leave_no_gap(api, cursor):
    """X9-EVT-051 — paging the stream two events at a time from an old cursor yields exactly
    the events one large read yields, in the same order: none skipped, none repeated.

    S4.4-AC2 ("no gaps across pages"). Other tests may append while this pages, so the two reads
    are compared over the window the paged read covered.

    Source: Ours. ADR-0037 (seq order: the cursor is the last event's seq, the next page starts
    strictly after it); ADR-0026 (identifiers and the event cursor).
    Why: A consumer persists nextCursor and asks again. An off-by-one at a page boundary loses
    exactly one event per page, silently, and the lost one may be a payment.cleared.
    """
    qrs = [create(api) for _ in range(4)]
    for qr in qrs:
        assert api.set_status(qr, "PAYMENT_INITIATED").status == 200
        assert api.set_status(qr, "CANCELLED").status == 200
    whole = read_from(api, cursor, until=lambda s: len(mine(s, set(qrs))) >= 8)
    assert len(mine(whole, set(qrs))) == 8, "setup: eight events were written"

    paged, after, pages = [], cursor, 0
    while len(mine(paged, set(qrs))) < 8:
        body = page(api, after=after, limit=2)
        events_ = body["events"]
        assert len(events_) <= 2, body
        if body["hasMore"]:
            assert len(events_) == 2, f"hasMore with a short page: {body}"
        if not events_:
            assert pages < 1000, "the paged read never caught up"
            time.sleep(0.2)
            continue
        assert body.get("nextCursor"), f"a page with events gives a cursor: {body}"
        paged.extend(events_)
        after, pages = body["nextCursor"], pages + 1

    ids = [e["eventId"] for e in paged]
    assert len(ids) == len(set(ids)), "an event was repeated across pages"
    whole = read_from(api, cursor, until=lambda s: len(s) >= len(paged))
    assert ids == [e["eventId"] for e in whole[:len(paged)]], \
        "the paged read differs from the large read over the same window"


def test_has_more(api, cursor):
    """X9-EVT-052 — hasMore is true when a page was cut at limit and more stood behind it, and a
    read past the end is an empty page with hasMore false and no nextCursor.

    S4.4-AC2 ("hasMore").

    Source: Ours. OpenAPI PaymentEventPage ("true when more events were available than `limit`
    allowed"; nextCursor "absent when the page is empty").
    Why: A consumer catching up drains while hasMore is true and only then starts long-polling.
    A hasMore that lied false would leave it a page behind until the next event; one that lied
    true would spin.
    """
    qrs = [create(api) for _ in range(2)]
    for qr in qrs:
        assert api.set_status(qr, "PAYMENT_INITIATED").status == 200
    read_from(api, cursor, until=lambda s: len(mine(s, set(qrs))) >= 2)

    first = page(api, after=cursor, limit=1)
    assert len(first["events"]) == 1 and first["hasMore"] is True, first

    after, last = cursor, None
    for _ in range(1000):
        last = page(api, after=after, limit=500)
        if not last["hasMore"]:
            break
        assert len(last["events"]) == 500, last
        after = last["nextCursor"]
    assert last["hasMore"] is False, "the stream never reported its end"

    past = page(api, after=END, limit=1)
    assert past["events"] == [] and past["hasMore"] is False, past
    assert "nextCursor" not in past or past["nextCursor"] in (None, ""), past


@pytest.mark.parametrize("wait", [31, 300])
def test_wait_above_30_is_refused(api, wait):
    """X9-EVT-053 — a long poll asking for more than 30 s is refused at once: 400 naming `wait`.

    S4.4-AC2 ("`wait` above 30 refused with 400 naming `wait`").

    Source: Ours. openapi.yaml `maximum: 30`, frozen by ADR-0028; the owner chose the refusal over
    a clamp on 2026-11-14 (a clamp would silently change the contract).
    Why: The hold is bounded to stay under proxy and ingress idle timeouts. A consumer asking for
    more is told so at once, by name, instead of being held for a time it did not ask for.
    """
    started = time.monotonic()
    response = api.get(f"{PATH}?after={END}&limit=1&wait={wait}")
    held = time.monotonic() - started

    assert response.status == 400, response
    assert "wait" in str(response.body), f"the refusal names `wait`: {response.body}"
    assert held < 5.0, f"refused at once, not held: {held:.1f} s"


@pytest.mark.parametrize("limit", [0, 501])
def test_a_limit_outside_1_to_500_is_refused(api, limit):
    """X9-EVT-056 — ``limit`` 0 and ``limit`` 501 are refused: 400 naming ``limit``. 1 and 500 are
    accepted.

    Source: Ours. ENDPOINTS.md (``limit`` 1 to 500, 100 by default); openapi.yaml ``minimum: 1``,
    ``maximum: 500``. The same validation as ``wait`` (X9-EVT-053).
    Why: A consumer asking for 0 would page forever without moving its cursor; one asking for more
    than 500 would be served a page the deployment never sized for. Either is told so, by name,
    rather than silently clamped into a page size it did not ask for.
    """
    response = api.get(f"{PATH}?after={END}&limit={limit}")

    assert response.status == 400, response
    assert "limit" in str(response.body), f"the refusal names `limit`: {response.body}"
    for edge in (1, 500):
        assert api.get(f"{PATH}?after={END}&limit={edge}").status == 200, f"limit={edge} is valid"


def test_without_limit_a_page_holds_at_most_100_events(api):
    """X9-EVT-057 — a read without ``limit`` returns 100 events when more stand behind the cursor,
    and says so with ``hasMore``.

    Source: Ours. ENDPOINTS.md and openapi.yaml (``limit`` defaults to 100).
    Why: A consumer that never sets ``limit`` still gets bounded pages; a default that served the
    whole stream would turn its first catch-up into one unbounded answer.
    """
    whole = page(api, after="", limit=500)
    while len(whole["events"]) < 101:
        qr = create(api)
        assert api.set_status(qr, "PAYMENT_INITIATED").status == 200
        assert api.set_status(qr, "CANCELLED").status == 200
        time.sleep(0.2)
        whole = page(api, after="", limit=500)

    response = api.get(f"{PATH}?after=")
    assert response.status == 200, response
    assert len(response.body["events"]) == 100, f"{len(response.body['events'])} events"
    assert response.body["hasMore"] is True, response.body.get("hasMore")
    assert [e["eventId"] for e in response.body["events"]] == \
        [e["eventId"] for e in whole["events"][:100]], "the first 100 of the stream, in order"


@pytest.mark.parametrize("wait, low, high", [(0, 0.0, 1.5), (2, 1.8, 4.0), (30, 29.0, 34.0)])
def test_wait_holds_an_empty_poll_for_as_long_as_asked(api, wait, low, high):
    """X9-EVT-054 — with nothing to return, the poll is held for `wait` seconds (0 returns at
    once, 30 is honoured), then answers 200 with an empty page.

    S4.4-AC2 (the control for X9-EVT-053: 30 itself is accepted and held).

    Source: Ours. openapi.yaml `wait` (0–30, default 0); ENDPOINTS.md.
    Why: An idle consumer costs one parked request instead of a poll loop only if the hold is
    real; one that returned at once would turn every consumer into a busy loop.
    """
    started = time.monotonic()
    body = page(api, after=END, limit=1, wait=wait)
    held = time.monotonic() - started

    assert body["events"] == [] and body["hasMore"] is False, body
    assert low <= held <= high, f"held {held:.1f} s for wait={wait}"


def test_replay_from_an_old_cursor_is_safe_by_event_id(api, cursor):
    """X9-EVT-055 — reading again from an old cursor returns the same events with the same
    eventIds and bodies, so a consumer deduplicating by eventId applies each exactly once.

    S4.4-AC2 ("replay from an old cursor is safe by `eventId`"). Two replays: from the cursor
    before the QR Code's events (all of them again) and from one between them (the suffix).

    Source: Ours. ADR-0014 (at-least-once delivery; "deduplicate by eventId"); OpenAPI
    PaymentEvent.eventId ("stable across re-publishes").
    Why: A consumer that crashes before persisting its cursor re-reads. If a replay gave an event
    a new id, or a different body, deduplication would apply a payment twice.
    """
    qr = create(api)
    assert api.set_status(qr, "PAYMENT_INITIATED").status == 200
    assert api.set_status(qr, "CANCELLED").status == 200
    first = mine(read_from(api, cursor, until=lambda s: len(mine(s, {qr})) >= 2), {qr})
    assert [e["type"] for e in first] == [INITIATED, CANCELLED_EVT], first

    again = mine(read_from(api, cursor, until=lambda s: len(mine(s, {qr})) >= 2), {qr})
    assert again == first, "a replay from the same cursor changed an event"

    between, after = None, cursor
    while between is None:
        body = page(api, after=after, limit=1)
        assert body["events"], "setup: walked past the end without meeting the first event"
        if body["events"][0]["eventId"] == first[0]["eventId"]:
            between = body["nextCursor"]
        after = body["nextCursor"]
    suffix = mine(read_from(api, between, until=lambda s: len(mine(s, {qr})) >= 1), {qr})
    assert suffix == first[1:], "a replay from between the events starts after the first"

    applied = {}
    for event in first + again + suffix:
        applied.setdefault(event["eventId"], event)
    assert list(applied.values()) == first, "deduplicated by eventId, each event applies once"

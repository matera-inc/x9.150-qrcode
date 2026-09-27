# ADR-0014 — We transport and sequence; the consumer reconciles

- **Status:** Accepted
- **Date:** 2026-09-27
- **Context:** `docs/reconciliation-is-the-consumers`

## Context

A payer may announce one amount and report another.

The pre-commit notification says "I am about to pay 10 USDC" and the QR Code comes out of
circulation. The post-commit says "I paid 9 USDC". We cannot refuse the second one: the money has
moved, and refusing a report of fact would be a lie about reality — that is why a post-commit is
recorded rather than judged ([ADR-0004](0004-phase-inferred-from-transaction-id.md)).

So the discrepancy exists, somebody has to notice it, and the question is who.

Two candidates. **Either X9.150 compares the two notifications and raises something**, or **X9.150
forwards both and the system it serves does the comparison.**

## Decision

**The consuming system reconciles. We transport, validate the things we can, and sequence.**

Three reasons, in order of weight.

**We would be comparing the wrong pair.** X9.150 never touches money and cannot observe settlement.
Comparing a post-commit against a pre-commit compares *a claim against an earlier claim by the same
party*: a payer could announce 10, report 10, and send 9, and our check would find nothing wrong. The
consuming system is already matching the transaction against funds that actually arrived — it holds
the only figure that settles the question.

**"9 instead of 10" is not self-evidently wrong.** It may be gas deducted at source, FX slippage, a
tolerated underpayment, a partial payment this biller accepts, or fraud. Choosing a threshold and an
action is a business policy that differs per biller, and encoding one here would bake a single
biller's rules into a transport.

**The boundary already exists and this is the same line.** A post-commit does not mark a QR Code
PAID; only a system that saw the funds may say that, through the status endpoint
([ADR-0008](0008-status-update-is-the-iso20022-entry-point.md)). Judging an amount discrepancy is the
same act as judging settlement, and it belongs on the same side of the line.

### The asymmetry with pre-commit is deliberate

We *do* check the amount on a pre-commit, against the quote we published. That is not inconsistent:

- A **pre-commit is a request for permission**. Nothing has moved, so refusing is meaningful and
  cheap — it is the last moment anyone can say no.
- A **post-commit is a report of fact**. Refusing changes nothing except our own records.

Different acts, different treatment.

## What the consumer needs, and already has

`PaymentEvent` carries `amount`, `currency`, `network` and `transactionId` on **every** event. So
`payment.initiated` publishes 10 and `payment.sent` publishes 9, and the consumer subtracts. Nothing
new is required for this to work, and a test pins it so the property cannot be refactored away by
someone tidying the event shape.

**The discrepancy is visible only in the event stream.** `notifyPayment` replaces
`paymentNotification.data` wholesale, so once the post-commit lands the entity shows 9 and the
announced 10 is gone. A consumer reading `GET /payment-request/{id}` sees a single figure and no hint
there was ever a difference. That is acceptable because events are the designed channel
([ADR-0001](0001-events-leave-by-pull-not-push.md)) — but it is a trap worth stating, because the
state endpoint looks like it would tell you and does not.

## The delivery mechanism is already built

Recorded here because it was nearly redesigned from scratch: the outbox is embedded in the QR Code
document ([ADR-0002](0002-atomic-transitions-with-embedded-outbox.md)), drained into a MongoDB event
log, and read through a **long-polling** `GET /pub/api/v1/events?after=<cursor>`.

The cursor is a **monotonic ULID** (`UlidCreator.getMonotonicUlid`). It has the properties a sequence
would not: generated with no central counter, so nothing to contend on; lexicographic order equal to
time order, so `seq > after` is a valid scan; and monotonic *within* a millisecond, which plain
UUIDv7 does not guarantee without an added counter. UUIDv7 would serve equally well and is the later
standardisation of the same idea — it is only a cursor, never a foreign key, so it can be changed
later without migrating anything that points at it.

**The cursor model is fan-out, not work-sharing.** Each consumer keeps its own cursor and sees every
event. Nothing is claimed and nothing is locked, so there is no "skip if locked": two processes
sharing one cursor would race, and the model assumes one logical consumer per cursor.

## Consequence: exactly one drainer, today by luck

A cursor scan of `seq > after` is only safe if events become **visible in the order their cursors
were assigned**. Assign a ULID at t=1ms and another at t=2ms, let the second become visible first,
and a consumer that reads to the second and saves its cursor will **never see the first**. It is
skipped permanently, silently, and only for that consumer.

That cannot happen today, because three things line up:

- `@Scheduled(fixedDelay)` does not overlap runs, and Spring's default scheduler is single-threaded
- `drainOnce()` appends events sequentially, one at a time
- the ULID is stamped immediately before the save, on the same thread

So assignment order equals write order equals visibility order — **on one instance**. The Helm chart
ships `replicaCount: 1`, which means that value is currently load-bearing correctness rather than a
capacity choice, and nothing says so.

With two replicas there is no lock or leader election, and two failures appear: the skip above, and a
second one — `append()` upserts on the event id so a re-drain is idempotent, but two pods draining the
same event would upsert it twice with **different `seq` values**, moving an event in the stream after
a consumer may already have passed it.

**The drain is therefore single-writer, enforced rather than assumed.** `PaymentEventDrainLock`
holds a Mongo-backed lease keyed on a single document id: the winner's upsert matches the query, a
loser's upsert collides with the unique `_id` and is refused. A lease rather than a lock, so an
instance that dies mid-drain cannot wedge the stream — it lapses and the next tick elsewhere takes
over, and the events it had not drained are still in their QR Code documents, because the outbox
entry is removed only after the log entry is written.

`replicaCount` may now scale the API without ever running two drainers.

## Consequence: the log is bounded by time, not by acknowledgement

`payment_events` had no retention at all. It now carries a TTL index on `occurred_at`, thirty days.

Retention rather than deletion-on-read, because the log is the **consumers'** record and not ours.
A cursor consumer may legitimately rewind and reprocess — after a bug, that is the normal recovery
path — and a second consumer may be added later. Deleting what one reader has acknowledged would
destroy the record for every other reader, and for that reader's own second attempt.

A cursor older than the window resolves to nothing rather than to an error. That is the right
failure: the consumer discovers it has fallen too far behind, instead of silently skipping.

## Alternatives rejected

**Compare and raise an alert.** Rejected on all three grounds above: wrong pair, unknowable policy,
wrong side of the settlement boundary. It would also give the appearance of a guarantee we cannot
make — a consumer might reasonably stop checking, having been told we do.

**Compare and refuse the post-commit.** Worse. The money has moved; refusing the report loses the
record of it and leaves the QR Code claiming something untrue.

**Carry a computed `discrepancy` field on the event.** Tempting, and nearly harmless — subtracting two
numbers is not a policy. Rejected because the numbers are already both on the stream: a derived field
adds a second thing to keep correct, and invites the reading that we validated it.

**Keep the pre-commit notification in entity state alongside the post-commit.** Reasonable, and worth
revisiting if consumers turn out to read state rather than events. Not done now because it changes the
payload shape for a case the event stream already answers.

**Use a MongoDB change-stream resume token as the cursor.** The most *correct* answer to the ordering
problem, and it was seriously considered: a change stream yields changes in true commit order, so the
token cannot skip, and it removes the assign-before-commit hazard at the root rather than defending
it. Rejected on one property — **resume tokens expire.** The oplog is a ring buffer; a consumer
offline longer than that window comes back to an invalid token and cannot resume at all. A ULID is a
key into a permanent collection, so a consumer may disappear for a week and carry on exactly where it
stopped. For a stream consumed by somebody else's system, that durability is worth more than the
elegance, especially once a single-writer lease makes the ordering hazard moot. A change stream
remains attractive for *driving* the drain — commit-ordered reads, no poll latency — but it would not
by itself remove the need for one writer, since two instances watching one stream both react.

**Drop the cursor entirely: return everything unacknowledged, and delete what the consumer confirms.**
This fixes the ordering hazard completely rather than defending it — with no `seq > cursor` scan, an
entry that becomes visible late is simply still there next time — and it bounds the collection
without a TTL. It also makes the backlog directly observable, which a cursor does not: table size
would *be* the undelivered work.

Rejected because it turns a log into a queue, and three things follow. **Consumer mistakes become
irrecoverable**: `drainOnce` removes the outbox entry once the log entry is written, and the entity
keeps only the latest notification, so `payment_events` is the sole surviving record — a consumer
that acknowledges before committing destroys data nobody can reproduce, where a cursor consumer
simply rewinds. **Only one consumer is ever possible**, because the first acknowledgement destroys
the event for everyone. And **we would be deleting our own evidence** of what we published, which is
precisely what a dispute asks for. It does not even remove the need for idempotent consumers: an
acknowledgement lost after a successful commit causes redelivery, so they must still deduplicate on
`eventId`.

The deciding asymmetry: **a wrong cursor is recoverable; a wrong delete is not.** The two things that
design was reaching for — bounded growth and no ordering hazard — are obtained instead by the TTL
index and the drain lease, neither of which makes anything unrecoverable. Backlog observability, if
it is wanted, can be an acknowledgement endpoint that records a checkpoint without deleting.

---

<sub>Copyright © 2026 Matera Systems, Inc. Licensed under the Matera Source License v1.0
(source-available; not open source) — see LICENSE.md at the repository root.</sub>

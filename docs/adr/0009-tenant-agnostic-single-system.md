# ADR-0009 — X9.150 is tenant-agnostic; one system serves a deployment

- **Status:** Accepted
- **Date:** 2026-09-26
- **Context:** `supporting-payment-notifications`

## Context

[ADR-0001](0001-events-leave-by-pull-not-push.md) makes a single pull API the only way payment events
leave the system. That raises the obvious question: who may read it, and does the reader need to be
told apart from other readers?

The tempting answer is a tenancy model — a `tenantId` on each QR Code and a filter on the reader.

## Decision

**X9.150 has no concept of a tenant at all.** Not one tenant: *none*.

**Every QR Code exists on its own.** Who it belongs to — which bank, which PSP, which merchant — is
not modelled, not stored and not consulted. A QR Code is a standalone payment object with an id, a
bill, payment methods and a lifecycle, and that is the whole of it.

It follows that:

- There is **no `tenantId`** on a QR Code and **no tenant filter** on the events reader.
- The events API returns **everything that deployment holds**, because there is no axis to slice it on.
- **Scope is decided by what an operator puts in a deployment**, not by anything inside the software.
  Keeping two institutions' QR Codes apart means running two deployments.
- **One system serves a deployment** — see below.

"Tenant-agnostic" is a stronger and more honest claim than "single-tenant". Single-tenant would mean
we have a tenancy model configured to one. We do not have one to configure.

### Amendment, 2026-09-26: the one system is also the only creator

As first accepted, this ADR said *one consuming system, one poller*. It did not say who **creates**
the QR Codes. Completing it:

> **Exactly one system is X9.150's client, on both sides.** The same platform is the **only creator**
> of QR Codes *and* the **only poller** of the events and approval APIs.

This is additive — it forecloses a case the original left open rather than changing any decision — and
it repairs an argument below that was otherwise only probably true. "The consuming system maps each
event to the right bank itself, and it already can: it created the QR Code" holds *by construction*
once creator and consumer are the same system. Had system A created QR Codes while system B polled,
B would hold no mapping and routing would be impossible; nothing in the original text ruled that out.

It also settles how an approval can be authorized without an auth framework: a token minted at QR
creation returns to the same system that must later vote, so there is no case where the creator and
the voter differ.

### Who runs a deployment

In practice an operator is a **PSP** — a bank or payment processor — playing the Payee-PSP role this
service implements, and many billers' QR Codes coexist in that deployment. But that is a deployment
convention, not a model in the code: X9.150 is not aware it is serving "a PSP", only that it holds
QR Codes.

Two boundaries follow, and they are not equally important:

| Boundary | Between | Consequence of a leak |
|---|---|---|
| **Deployment ↔ deployment** | Competing financial institutions | A confidentiality incident between banks |
| Biller ↔ biller, inside one deployment | One operator's own merchants | Data that operator already holds, by definition |

The first is enforced by separate deployments. The second is the operator's to make, downstream, and
is not X9.150's to enforce.

### The consuming system may be multi-tenant; X9.150 does not need to know

Nothing here constrains the platform that consumes the events. It is free to be multi-tenant and
serve many banks from one installation of *its own* software — that is its architecture, not ours.
What follows from this ADR is only:

- **All of that deployment's notifications are available to that system**, undifferentiated.
- **The system maps each event to the right bank itself**, and it already can: it created the QR Code,
  so it holds that mapping. It does not need X9.150 to carry its tenant model.
- Every event carries the correlation keys that make this a lookup rather than a guess — `qrId`,
  `locationId`, `paymentId`, and the biller's own `invoiceNumber` / `orderNumber`.

This is the payoff of being tenant-agnostic: X9.150 stays ignorant of a consumer's tenancy while
giving it everything needed to route. A `tenantId` would be X9.150 storing someone else's model of
the world, and going stale the first time that model changed.

### Exactly one system polls

**Two systems must not poll the same deployment.** For the read-only events API this is a matter of
clarity — two cursors each see everything, so a second poller gains no isolation and only invites the
belief that it has some.

For the **approval channel** ([ADR-0003](0003-two-phase-payment-notification-approval.md)) it is a
correctness requirement. A pending approval is a row, not a queued message, so two pollers both see
it and both may post a verdict. The first wins and the second is told the outcome already applied —
nothing is corrupted, but **the decision becomes whichever system answered first**. For a vote on
whether money may move, a nondeterministic winner is not acceptable, and two systems that disagree
would produce a different result on every run.

So: one deployment, one system, one poller — and per the amendment above, that same system is the
only creator of the QR Codes it later reads events for. A consumer needing internal redundancy should
make its *own* pollers mutually exclusive (a lease, a leader election) before calling X9.150 — the
same posture X9.150 takes for its own outbox drain.

## Rationale

**1. A filter that is never written can never be wrong.** For the boundary that matters — deployment
to deployment — the failure mode of a tenant filter is one bank reading another's payment facts:
amounts, QR ids, transaction ids, creditor references. A single missing predicate on any read path,
now or in any future one, is a confidentiality incident between competing institutions. Separate
deployments make that structurally impossible rather than conditionally prevented.

**2. There is no caller identity to filter *by*.** The API is open and unauthenticated by design, and
that is a stated product position, not an oversight — access control belongs at the deployer's edge.
Any filter needs an authenticated principal to filter on, so tenancy would drag an authentication and
authorization model into a service that deliberately has none. The cheap-looking feature is not cheap:
it changes what the product is.

**3. Filtering inside one deployment would add risk without adding a boundary.** Every QR Code there
belongs to the same operator, which already holds all of that data. A per-biller filter would
reintroduce the missing-predicate risk of point 1 while protecting a boundary the operator already
sits on both sides of.

**4. The license already assumes it.** `HIGH-AVAILABILITY.md` §4 records that the Annex A production
limits are *"global to the deployment"* — at most 2 payment rails per 24-hour period, at most 100,000
paid QR codes per calendar month. Per operator those are a sane commercial unit. Shared between
institutions they would be pooled across unrelated parties, and §1(b) is worse than a quota: the
two-rail limit applies to *"the same set for all QR codes in that period"*, so one institution's
choice of rails would dictate the rails available to every other one in the installation.

**5. The simple things stay simple.** One monotonic cursor over one event log; one retention policy;
one lag metric. With tenants, each becomes per-tenant — a filtered cursor, a per-tenant retention
question, a per-tenant gauge — for no gain over running a second copy.

## Consequences

- **The events API is not merchant-facing.** It returns every QR Code's events in that deployment.
  Fan-out and per-merchant access control belong to the consuming system. This has to be stated
  plainly in `ENDPOINTS.md`, `SECURITY.md` and `EVENTS.md`, because the endpoint looks self-service
  and is not.
- **Operational toil grows per deployment, not per merchant** — the right axis, and much flatter. The
  Helm chart must make a per-deployment install a values-file change and nothing more: release name,
  database name, public host.
- **Each deployment needs its own public host**, and the EMV tag-26 budget (host ≤ 37 characters)
  applies per deployment.
- **No cross-deployment view from inside X9.150.** QR ids are ULIDs and event ids are UUIDs, so an
  operator running several deployments can merge their event streams downstream unambiguously.
- **Nothing to migrate later.** Adding a tenancy model would be a breaking change to the data model
  and the events API; splitting one deployment in two is a data move with no contract change. The
  reversible direction is the one we chose.

## Terminology note for earlier ADRs

[ADR-0003](0003-two-phase-payment-notification-approval.md) says "the biller" polls for approvals and
returns the verdict. Read that as **the consuming system, acting for the biller whose QR Code it is**
— the actor holding the long-poll is always the operator's platform, which may consult its merchant
or apply the merchant's rules itself. The protocol is unchanged; only the name of the actor is
sharpened here.

## Alternatives rejected

**`tenantId` on the QR Code plus a filter on the events reader.** Cheaper in infrastructure, far more
expensive in assurance: it needs an authenticated caller (which the product deliberately lacks), and
every present and future read path becomes a place where a missing predicate leaks payment data
between banks. The saving is a few pods; the risk is a confidentiality incident.

**One application over a database per institution.** A middle ground that removes the query-filter
risk but keeps a single process holding every institution's credentials and connections, so a
configuration or connection-routing mistake still crosses the boundary. It also still pools the
Annex A limits. Half the isolation for most of the operational savings is the wrong point on the curve.

---

<sub>Copyright © 2026 Matera Systems, Inc. Licensed under the Matera Source License v1.0 (source-available; not open source) — see LICENSE.md at the repository root.</sub>

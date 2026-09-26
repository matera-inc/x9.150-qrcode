# ADR-0009 — One deployment per PSP; no tenancy model inside X9.150

- **Status:** Accepted
- **Date:** 2026-09-26
- **Context:** `supporting-payment-notifications`

## Context

[ADR-0001](0001-events-leave-by-pull-not-push.md) makes a single pull API the only way payment events
leave the system. That raises the obvious question: who may read it, and does the reader need to be
told apart from other readers?

**The tenant is a PSP** — a bank or payment processor — playing the Payee-PSP role this service
implements. It is *not* a biller. **Many billers coexist inside one deployment**: a PSP serves all of
its merchants from the one installation, and their QR Codes, payments and events all live together.

So there are two candidate boundaries, and they are not equally important:

| Boundary | Between | Consequence of a leak |
|---|---|---|
| **PSP ↔ PSP** | Competing financial institutions | A confidentiality incident between banks |
| Biller ↔ biller, within one PSP | A PSP's own merchants | Data the PSP already holds, by definition |

## Decision

**One deployment per PSP.** One application instance, one database, one events API that returns
everything that deployment holds — every biller's events, for that PSP.

There is **no `tenantId` on a QR Code and no tenant filter on the events reader.** Serving a second
PSP means running a second deployment.

The events and approval APIs are therefore **PSP-internal interfaces**. Distributing an event to the
biller it belongs to is the PSP platform's job, downstream of X9.150 — the same platform that created
the QR Code and already knows which biller it was for.

### The consumer may be multi-tenant; X9.150 does not need to know

Nothing here constrains the platform that consumes the events. A PSP platform is free to be
multi-tenant and serve many banks from one installation of *its own* software — that is its
architecture, not ours. What follows from this ADR is only:

- **All of that deployment's notifications are available to that platform**, undifferentiated.
- **The platform maps each event to the right bank itself**, and it already can: it created the QR
  Code, so it holds that mapping. It does not need X9.150 to carry its tenant model.
- Every event carries the correlation keys that make this a lookup rather than a guess — `qrId`,
  `locationId`, `paymentId`, and the biller's own `invoiceNumber` / `orderNumber`.

This is the payoff of the decision: X9.150 stays ignorant of a consumer's tenancy while giving it
everything needed to route. A `tenantId` would be X9.150 storing someone else's model of the world,
and going stale the first time that model changed.

### Exactly one consumer polls

**Two systems must not poll the same deployment.** For the read-only events API this is a matter of
clarity — two cursors each see everything, so a second poller gains no isolation and only invites the
belief that it has some.

For the **approval channel** ([ADR-0003](0003-two-phase-payment-notification-approval.md)) it is a
correctness requirement. A pending approval is a row, not a queued message, so two pollers both see
it and both may post a verdict. The first wins and the second is told the outcome that was already
applied — nothing is corrupted, but **the decision becomes whichever system answered first**. For a
vote on whether money may move, a nondeterministic winner is not an acceptable outcome, and two
systems that disagree would produce a different result on every run.

So: one deployment, one consuming platform, one poller. A consumer that needs internal redundancy
should make its *own* pollers mutually exclusive (a lease, a leader election) before calling X9.150 —
the same posture X9.150 itself takes for its outbox drain.

## Rationale

**1. A filter that is never written can never be wrong.** For the boundary that matters — PSP to PSP —
the failure mode of a tenant filter is one bank reading another bank's payment facts: amounts, QR ids,
transaction ids, creditor references. A single missing predicate on any read path, now or in any
future one, is a confidentiality incident between competing institutions. Separate deployments make
that structurally impossible rather than conditionally prevented.

**2. There is no caller identity to filter *by*.** The API is open and unauthenticated by design, and
that is a stated product position, not an oversight — access control belongs at the deployer's edge.
Any filter needs an authenticated principal to filter on, so tenancy would drag an authentication and
authorization model into a service that deliberately has none. The cheap-looking feature is not cheap:
it changes what the product is.

**3. Per-biller filtering would add risk without adding a boundary.** Inside one deployment every
biller belongs to the same PSP, which already holds all of that data and is the party X9.150 is
operated by. Filtering by biller inside X9.150 would reintroduce exactly the missing-predicate risk of
point 1 while protecting a boundary that the operator already sits on both sides of. The PSP routes
events to its billers with the mapping it already owns.

**4. The license already assumes it.** `HIGH-AVAILABILITY.md` §4 records that the Annex A production
limits are *"global to the deployment"* — at most 2 payment rails per 24-hour period, at most 100,000
paid QR codes per calendar month. Per PSP those are a sane commercial unit. Shared between PSPs they
would be pooled across unrelated institutions, and §1(b) is worse than a quota: the two-rail limit
applies to *"the same set for all QR codes in that period"*, so one PSP's choice of rails would
dictate the rails available to every other PSP in the installation.

**5. The simple things stay simple.** One monotonic cursor over one event log; one retention policy;
one lag metric. With tenants, each becomes per-tenant — a filtered cursor, a per-tenant retention
question, a per-tenant gauge — for no gain over running a second copy.

## Consequences

- **The events API is not biller-facing.** A PSP must not expose it directly to its merchants: it
  returns every biller's events for that PSP. Fan-out and per-biller access control belong to the PSP
  platform. This has to be stated plainly in `ENDPOINTS.md`, `SECURITY.md` and `EVENTS.md`, because
  the endpoint looks self-service and is not.
- **Operational toil grows per PSP, not per biller** — which is the right axis, and much flatter than
  per-merchant would be. N PSPs means N deployments, N databases, N keystores, N public hosts, N
  upgrades. The Helm chart must make a per-PSP install a values-file change and nothing more: release
  name, database name, public host.
- **Each PSP needs its own public host**, and the EMV tag-26 budget (host ≤ 37 characters) applies per
  deployment.
- **No cross-PSP view from inside X9.150.** QR ids are ULIDs and event ids are UUIDs, so an operator
  running several deployments can merge their event streams downstream unambiguously.
- **Nothing to migrate later.** Adding a tenancy model would be a breaking change to the data model
  and the events API; splitting one deployment in two is a data move with no contract change. The
  reversible direction is the one we chose.

## Terminology note for earlier ADRs

[ADR-0003](0003-two-phase-payment-notification-approval.md) says "the biller" polls for approvals and
returns the verdict. Read that as **the PSP platform, acting for the biller whose QR Code it is** —
the actor holding the long-poll is always the PSP's system, which may consult its merchant or apply
the merchant's rules itself. The protocol is unchanged; only the name of the actor is sharpened here.

## Alternatives rejected

**`tenantId` on the QR Code plus a filter on the events reader.** Cheaper in infrastructure, far more
expensive in assurance: it needs an authenticated caller (which the product deliberately lacks), and
every present and future read path becomes a place where a missing predicate leaks payment data
between banks. The saving is a few pods; the risk is a confidentiality incident.

**One application, one MongoDB cluster, a database per PSP.** A middle ground that removes the
query-filter risk but keeps a single process holding every PSP's credentials and connections, so a
configuration or connection-routing mistake still crosses the boundary. It also still pools the
Annex A limits. Half the isolation for most of the operational savings is the wrong point on the curve.

---

<sub>Copyright © 2026 Matera Systems, Inc. Licensed under the Matera Source License v1.0 (source-available; not open source) — see LICENSE.md at the repository root.</sub>

# High Availability & the Matera Source License

This document explains how High Availability (HA) can be achieved for X9 QRCode and how HA relates to the production-use limits in the [Matera Source License v1.0](LICENSE.md) (Annex A).

> **Short answer:** Yes — you can run X9 QRCode in a highly available topology **without breaching the license**. PostgreSQL replication and failover (database HA) are not restricted at all, and the license explicitly permits **standby / fast-failover** copies of the application. The only thing the license forbids is running **two application instances that serve production traffic at the same time** (active-active).

---

## 1. Two independent layers of HA

HA for this system is achieved at two separate tiers. They are governed very differently by the license.

| Tier | Component | HA mechanism | License treatment |
|------|-----------|--------------|-------------------|
| **Database** | PostgreSQL | A primary with streaming-replication standbys and automatic failover | **Not restricted.** PostgreSQL is third-party software (the PostgreSQL License), not the "Licensed Work". |
| **Application** | X9 QRCode | Active-passive failover (1 active + standby installs) | **Permitted** by Annex A §1(a), as long as only one instance actively serves traffic. |

### Why "Licensed Work" matters
The license defines:

> *"Licensed Work" means the software, documentation, and any other materials made available under this License.*

PostgreSQL is **not** made available under the Matera Source License — it is an independent database under its own license (the PostgreSQL License). Therefore the production-use limits in Annex A apply to the **X9 QRCode application**, **not** to PostgreSQL. You may run as many PostgreSQL replicas as you like.

---

## 2. Database HA — PostgreSQL primary and standbys

The service connects to **one PostgreSQL primary** and does all its reads and writes there (two
pools, both on the primary; see [db/README.md](db/README.md)). Database HA is the usual PostgreSQL
arrangement, run by you or your provider:

- One **primary** accepts all writes.
- One or more **standbys** replay the primary's write-ahead log (**WAL**) by streaming replication,
  so they are continuously up to date — not just "updated at failover time". Synchronous
  replication makes a commit wait for a standby, so an acknowledged write survives losing the
  primary.
- On failure, a failover manager (a Kubernetes operator, or a managed service) promotes a
  standby and moves the address the service connects to.

```
              writes
                │
            ┌───▼─────┐   WAL (streaming)      ┌─────────┐
            │ PRIMARY │ ─────────────────────► │ STANDBY │
            └───┬─────┘                        └─────────┘
                │             WAL (streaming)  ┌─────────┐
                └────────────────────────────► │ STANDBY │
                                               └─────────┘
   primary fails ─► a standby is promoted ─► the service reconnects to the same address
```

The service needs nothing special for this. While the database is unreachable its readiness is
DOWN (503) and it keeps retrying; once the address answers again, it re-checks the schema and
serves. Point `DATABASE_HOST` at the address that follows the primary (a service name, a virtual
IP or the provider's endpoint), never at one node. A read-only standby is of no use to it: every
query goes to the primary.

This database-tier HA is fully compatible with the license and is not counted against any Annex A limit.

---

## 3. Application HA — active-passive failover

Annex A §1(a) states:

> **a) Single active instance.** The Licensed Work may run on only one server in active production operation at any given time. It may be installed on additional servers solely to provide standby or fast-failover capacity, provided that no more than one instance actively serves production traffic at the same time.

Interpretation for HA:

- ✅ **Allowed — active-passive:** Run **one** active X9 QRCode instance. Keep one or more **standby** installs ready. If the active instance fails, promote a standby to serve traffic. Because only one instance serves traffic at a time, this stays within the license.
- ❌ **Not allowed — active-active:** Running two or more X9 QRCode instances that **simultaneously** serve production traffic (e.g., load-balanced across replicas, or horizontal autoscaling to >1 serving pod) exceeds the single-active-instance limit and requires a commercial license from Matera.

### Kubernetes / Helm implications
The Helm chart defaults to a single instance, which is the compliant configuration:

- **One replica, always.** The chart renders `replicas: 1` and refuses to render more, or a HorizontalPodAutoscaler, so it cannot be scaled into an active-active topology by mistake.
- **Strategy `Recreate`.** An update stops the old pod before starting the new one, so two instances never serve at once, not even during a rollout.
- Failover is provided by Kubernetes itself: if the single pod dies, the Deployment reschedules it (a new active instance replaces the failed one — still only one active at a time).

For faster failover than a pod reschedule, a warm standby (a second install kept stopped/idle, or in another cluster) can be promoted on failure — still one active instance at any moment.

---

## 4. Do the other Annex A limits change under HA?

No. The remaining production limits are **global to the deployment**, independent of topology:

- **§1(b)** — at most **2** payment rails/blockchains combined per 24-hour period (same set for all QR codes in that period).
- **§1(c)** — at most **100,000** paid QR codes per calendar month.

Running standby copies or more PostgreSQL replicas does **not** raise these ceilings, and (because standbys don't serve traffic) does not consume them either. They are measured across the single active production system.

---

## 5. Summary

- **PostgreSQL replication and failover → yes, use them freely.** They are the recommended way to get database redundancy and automatic failover, and they are outside the license's scope entirely.
- **Application → active-passive only.** One serving instance plus standby/failover copies is permitted; two-or-more simultaneously serving instances is not.
- **Failover is continuous-sync, not copy-on-failure.** Standbys are always current; on failure an up-to-date node takes over.
- **One replica, no autoscaling**: the Helm chart enforces both.

---

> **Disclaimer:** This document is an operational explanation of how HA maps to the license text; it is not legal advice. The binding terms are those in [`LICENSE.md`](LICENSE.md). For production deployments or any topology beyond a single active instance, confirm the arrangement with Matera and, where applicable, obtain a commercial license.

---

<sub>Copyright © 2026 Matera Systems, Inc. Licensed under the Matera Source License v1.0 (source-available; not open source) — see LICENSE.md at the repository root. Creating a Derivative Work from this document — by AI/ML generation or by manual re-implementation based on it — is governed by that license (see the "Derivative Work" definition and Annex A).</sub>

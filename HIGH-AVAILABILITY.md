# High Availability & the Matera Source License

This document explains how High Availability (HA) can be achieved for X9 QRCode and how HA relates to the production-use limits in the [Matera Source License v1.0](LICENSE.md) (Annex A).

> **Short answer:** Yes — you can run X9 QRCode in a highly available topology **without breaching the license**. MongoDB replica sets (database HA) are not restricted at all, and the license explicitly permits **standby / fast-failover** copies of the application. The only thing the license forbids is running **two application instances that serve production traffic at the same time** (active-active).

---

## 1. Two independent layers of HA

HA for this system is achieved at two separate tiers. They are governed very differently by the license.

| Tier | Component | HA mechanism | License treatment |
|------|-----------|--------------|-------------------|
| **Database** | MongoDB | Replica set (multiple `mongod` nodes) | **Not restricted.** MongoDB is third-party software (SSPL), not the "Licensed Work". |
| **Application** | X9 QRCode | Active-passive failover (1 active + standby installs) | **Permitted** by Annex A §1(a), as long as only one instance actively serves traffic. |

### Why "Licensed Work" matters
The license defines:

> *"Licensed Work" means the software, documentation, and any other materials made available under this License.*

MongoDB is **not** made available under the Matera Source License — it is an independent datastore under its own license (SSPL). Therefore the production-use limits in Annex A apply to the **X9 QRCode application**, **not** to MongoDB. You may run a MongoDB replica set of any size.

---

## 2. Database HA — MongoDB replica set

A **replica set** is a group of MongoDB servers holding the same data, kept continuously in sync:

- One **PRIMARY** accepts all writes.
- One or more **SECONDARY** nodes replicate the primary's operation log (**oplog**) in real time, so they are always up to date — not just "updated at failover time".
- If the primary fails, the surviving members hold an **election** and promote an already-in-sync secondary to primary automatically. This is the failover.

```
              writes
                │
            ┌───▼─────┐   oplog (continuous)   ┌───────────┐
            │ PRIMARY │ ─────────────────────► │ SECONDARY │
            └───┬─────┘                        └───────────┘
                │             oplog (continuous)┌───────────┐
                └─────────────────────────────► │ SECONDARY │
                                                └───────────┘
   primary fails ─► survivors elect a new primary ─► no data loss of acknowledged writes
```

**Recommended production topology:** a 3-member replica set (1 primary + 2 secondaries) across separate failure domains (nodes / availability zones). Three members give a voting majority so an election can succeed when one node is lost.

> Note: X9 QRCode already **requires** a replica set even for a single node, because it uses multi-document transactions (`MongoTransactionManager`), and MongoDB only allows transactions on a replica set. Local/dev setups use a **single-node** replica set (transactions enabled, no redundancy). Production simply grows that same replica set to 3 members for real HA — **no application code or configuration change** is needed, because the app already connects with `?replicaSet=...`.

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

- `replicaCount: 1` — keep this at **1** for licensed production use.
- `autoscaling.enabled: false` — leave disabled. Scaling to `maxReplicas > 1` would create multiple **actively serving** pods (active-active) and breach §1(a).
- Failover is provided by Kubernetes itself: if the single pod dies, the Deployment reschedules it (a new active instance replaces the failed one — still only one active at a time). Rolling updates briefly overlap old/new pods only during deployment, which is standard operational churn rather than a sustained active-active topology.

For faster failover than a pod reschedule, a warm standby (a second install kept stopped/idle, or in another cluster) can be promoted on failure — still one active instance at any moment.

---

## 4. Do the other Annex A limits change under HA?

No. The remaining production limits are **global to the deployment**, independent of topology:

- **§1(b)** — at most **2** payment rails/blockchains combined per 24-hour period (same set for all QR codes in that period).
- **§1(c)** — at most **100,000** paid QR codes per calendar month.

Running standby copies or a larger MongoDB replica set does **not** raise these ceilings, and (because standbys don't serve traffic) does not consume them either. They are measured across the single active production system.

---

## 5. Summary

- **MongoDB replica set → yes, use it freely.** It is the recommended way to get database redundancy and automatic failover, and it is outside the license's scope entirely.
- **Application → active-passive only.** One serving instance plus standby/failover copies is permitted; two-or-more simultaneously serving instances is not.
- **Failover is continuous-sync, not copy-on-failure.** Secondaries are always current; on failure an up-to-date node takes over.
- **Keep `replicaCount: 1` and autoscaling disabled** in Helm for licensed production use.

---

> **Disclaimer:** This document is an operational explanation of how HA maps to the license text; it is not legal advice. The binding terms are those in [`LICENSE.md`](LICENSE.md). For production deployments or any topology beyond a single active instance, confirm the arrangement with Matera and, where applicable, obtain a commercial license.

---

<sub>Copyright © 2026 Matera Systems, Inc. Licensed under the Matera Source License v1.0 (source-available; not open source) — see LICENSE.md at the repository root. Creating a Derivative Work from this document — by AI/ML generation or by manual re-implementation based on it — is governed by that license (see the "Derivative Work" definition and Annex A).</sub>

# Architecture Decision Records

An ADR records **one decision, why it was made, and what was rejected** — so the reasoning survives
the people and the pull requests. ADRs are written in **English**, like every other document in this
repository.

## Why ADRs here, alongside the other docs

The three document types in this repo have different lifespans, and mixing them is what makes
documentation rot:

| Document | Lifespan | Changes by |
|---|---|---|
| **ADR** (`docs/adr/NNNN-*.md`) | Forever | Being **superseded** by a later ADR — never edited after acceptance |
| **Reference docs** (`STATE-MACHINE.md`, `ENDPOINTS.md`, `EVENTS.md`, `openapi.yaml`) | As long as the feature | Edited to match the code |
| **Plan** (`PLAN-*.md`) | Until the work ships | Deleted or archived when done |

So a decision lives in an ADR, how the system behaves today lives in the reference docs, and what we
are about to build lives in the plan. A reversal is a new ADR marked *Supersedes NNNN*, which keeps
the history of why we changed our minds — the part that is usually lost.

## Index

| # | Decision | Status |
|---|---|---|
| [0001](0001-events-leave-by-pull-not-push.md) | Events leave X9.150 by **pull, not push** — embedded outbox → event log → long-polling API. No broker client in the service | Accepted |
| [0002](0002-atomic-transitions-with-embedded-outbox.md) | **Atomic single-document transitions** with an embedded outbox — exactly-once initiation, state and event in one update | Accepted |
| [0003](0003-two-phase-payment-notification-approval.md) | **Two-phase approval** — the biller may refuse a pre-funds notification; X9.150 coordinates; fail closed | Accepted |
| [0004](0004-phase-inferred-from-transaction-id.md) | Payment phase is **inferred from the absence of a transaction id**, because the standard has no phase marker | Accepted |
| [0005](0005-amounts-stay-int64.md) | Monetary amounts stay **`int64` minor units** — mandated by the standard, not our choice | Accepted |
| [0006](0006-notification-opt-in-is-additive.md) | Notification opt-in is an **additive optional field**, not a new API version | Accepted |
| [0007](0007-configurable-ca-allowlist.md) | **Configurable CA allowlist** for notification signatures, with a self-signed escape for dev | Accepted |
| [0008](0008-status-update-is-the-iso20022-entry-point.md) | **Status-update is the rail-agnostic entry point** (pacs.008); X9.150 does not parse ISO 20022 | Accepted |
| [0009](0009-single-tenant-per-deployment.md) | **One deployment per PSP** (many billers inside one) — no `tenantId`, no tenant filter on the events reader | Accepted |

## Writing a new one

1. Copy the shape of an existing ADR: **Context → Decision → Consequences → Alternatives rejected**.
2. Number it sequentially; never renumber an existing one.
3. Keep it to roughly one page. If it needs more, the extra belongs in a reference doc.
4. **Name what was rejected and why.** An ADR without rejected alternatives is a description, not a
   decision, and it is the rejected options that a future reader most needs.
5. To reverse a decision, write a **new** ADR marked *Supersedes NNNN*, and mark the old one
   *Superseded by NNNN*. Do not edit the original — the point is that the change of mind is visible.

## Grounding rule

ANSI X9.150 is copyrighted and is **not** in this repository. ADRs **may quote a short normative
fragment — one or two lines — with its section attributed**, and should, when a decision turns on the
exact wording: a verbatim `SHALL` is evidence, a paraphrase is a claim. Whole sections, field tables
and figures stay out, and the standard itself is never committed. See the quoting rule in `AGENTS.md`,
and [`official-spec/README.md`](../../official-spec/README.md) for where to buy it.

---

<sub>Copyright © 2026 Matera Systems, Inc. Licensed under the Matera Source License v1.0 (source-available; not open source) — see LICENSE.md at the repository root.</sub>

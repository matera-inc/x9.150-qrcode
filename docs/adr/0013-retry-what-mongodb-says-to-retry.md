# ADR-0013 — Retry what MongoDB says to retry, and nothing else

- **Status:** Accepted
- **Date:** 2026-09-27
- **Context:** `fix/retry-transient-transaction`

## Context

CI failed once, on a loaded runner, in `QRCodesApisFlowTest.testQRCodeLocationReuse`:

> Command failed with error 112 (**WriteConflict**) … `"errorLabels": ["TransientTransactionError"]`

The same commit passed on rerun and three times locally. It would have been easy to file that under
"flaky test" and move on.

It is not a test problem. MongoDB's message says what to do — *"Please retry your operation or
multi-document transaction"* — and the label is the server's formal way of saying **this would
probably succeed if you ran it again**. The drivers ship a `withTransaction` helper that does
precisely that. Spring Data's transaction manager does not, and we had no retry of our own, so the
conflict reached the caller as a **500** for a write that was never attempted a second time, for a
reason that was never the caller's fault.

The flow that hit it is not obscure either. Creating a QR Code against an existing `locationId`
releases the previous holder and then saves the new one — two documents, hence the transaction. Two
concurrent creates against one location is an ordinary thing for a merchant terminal to do.

There is a second cost to leaving it. A flaky gate teaches people to re-run on red, and the next
genuine regression gets re-run too.

## Decision

**Retry a transaction if and only if MongoDB labelled it `TransientTransactionError`**, up to four
attempts with a short randomised backoff, in advice that sits **outside** the transaction.

Three parts, each load-bearing.

**The label, not the exception type.** Spring translates a write conflict to
`DataIntegrityViolationException` — which is also what a **duplicate key** produces, and this schema
has a unique index on `locationId`. Both are live possibilities on the same code path. Retrying by
type would keep re-running a permanently doomed write until the attempts ran out, turning a clean
409 into a slow 500. The label travels on the driver exception underneath Spring's translation, so
the check walks the cause chain for a `MongoException` carrying it. That separates precisely the two
cases Spring makes look identical.

**Outside the transaction.** The advice orders one step ahead of Spring's transaction interceptor so
each attempt gets a fresh transaction. Retrying inside is worse than not retrying: the server has
already aborted that transaction, every subsequent operation in it fails, and the code looks correct
right up until the day it matters. The ordering *is* the correctness argument, which is why there is
a test asserting the advisor chain rather than trusting an `@Order` constant.

**Bounded, with jitter, surfacing the original error.** Four attempts, because a conflict resolves in
milliseconds. Randomised backoff, because two transactions that conflict and retry in lockstep
conflict again. And on exhaustion the original exception is rethrown unchanged: the server's
diagnosis is the one worth reading, not a wrapper saying we gave up.

## Consequences

- A conflict under contention is retried instead of surfacing as a 500.
- A duplicate `locationId` still fails immediately, with its own error — unchanged.
- `TransientTransactionRetryTest` is deterministic: it constructs the labelled and unlabelled
  exceptions directly. Reproducing a real conflict would need two racing transactions and would
  reintroduce exactly the flakiness that prompted this.
- The notification path is untouched and needs nothing. It writes a **single document**, so state and
  outbox event are atomic by MongoDB's own guarantee — which is why
  [ADR-0002](0002-atomic-transitions-with-embedded-outbox.md) embedded the outbox in the first
  place. Only multi-document flows carry a transaction, and only they can conflict this way.

## Alternatives rejected

**Call it a flaky test and re-run.** The symptom would go away and the 500 would stay. It also trains
everyone to re-run on red.

**Retry on `DataIntegrityViolationException`.** Simple, and wrong in the one case that matters: it
would retry a duplicate key, which cannot ever succeed.

**`spring-retry` with `@Retryable`.** It would work, but stacking `@Retryable` and `@Transactional`
on the same method makes the relative order implicit — and here the order is the entire correctness
argument. A dependency that obscures the one thing a reader needs to check is a poor trade.

**Retry inside the transaction.** Achieves nothing; the transaction is already aborted.

**Make `treatLocationInfo` single-document to avoid the transaction.** Tempting, but it would trade a
solved problem for an unsolved one: releasing one QR Code's location and giving it to another is
genuinely two writes, and doing it non-atomically risks two QR Codes holding one location — much
worse than a retry.

---

<sub>Copyright © 2026 Matera Systems, Inc. Licensed under the Matera Source License v1.0
(source-available; not open source) — see LICENSE.md at the repository root.</sub>

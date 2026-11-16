# Benchmark: Java + MongoDB vs Go + PostgreSQL

This compares **two complete builds of one product**, the Java build on MongoDB and the Go build on
PostgreSQL, under the same demand on the same machine. It is **not a database benchmark**: each
payment is a handful of keyed rows, and signing and verifying JWS dominate the cost. Why the service
moved to Go and PostgreSQL is told in [HISTORY.md](HISTORY.md); those reasons do not depend on these
numbers. Every number below is published as measured, whichever way it falls.

Campaign `2026-11-15T2347Z`: 3 runs per build, interleaved Java, Go, Java, Go, Java, Go. Each figure
is the **median of the 3 runs**, with the range (min–max) beside it.

## Summary

| | Java + MongoDB | Go + PostgreSQL | Go vs Java |
|---|---|---|---|
| Full payment cycle (S2), payments per second | 161.8 | 163.2 | +1% |
| Full payment cycle (S2), p99 of one request | 229 ms | 163 ms | −29% |
| Full payment cycle (S2), p99 of a whole payment | 808 ms | 647 ms | −20% |
| Signed payload fetch (S2), median | 44.1 ms | 6.5 ms | 6.8× faster |
| App memory, peak under full load (S2) | 688 MiB | 35 MiB | 20× less |
| App memory at rest (S7) | 282 MiB | 4.0 MiB | 70× less |
| Image size, uncompressed (S7) | 393 MB | 16.3 MB | 24× smaller |
| Image size, compressed (S7) | 176 MB | 5.5 MB | 32× smaller |
| Cold start to healthy (S7) | 2.64 s | 0.14 s | 20× faster |
| Database connections held after S2 | 107 | 13 | 8× fewer |
| Database on disk after S2, per 1,000 payments | 1.2 MB | 15.1 MB | **12.6× larger** |
| Failed requests / failed signature checks | 0 / 0 | 0 / 0 | |

Throughput is the same: both apps run at their 2 vCPU limit through S2, signing and verifying every
step, and both pay that cost equally. Go's advantage is in the tail, in memory, and in the size of
what an operator runs. **Java wins on storage**: MongoDB compresses its data, and the Go build keeps
every signed notification in full (see [Database](#database)).

## What was compared

| | Java build | Go build |
|---|---|---|
| Image | `x9-qrcode:git-66d113c` (Spring Boot, buildpacks) | `x9-qrcode-go:git-66d113c` (static binary, distroless) |
| Database | MongoDB 7.0.45, single-node replica set | PostgreSQL 16.16, schema applied by Liquibase |
| Durability | `w: majority`, `j: true` (the database reports `writeConcernMajorityJournalDefault: true`) | `synchronous_commit = on`, `fsync = on` |
| Source commit | `66d113c` | `66d113c` |

Both images were built from the same commit. The harness (`others/benchmark/`) ran from a later
commit, `743f160`, which carries one harness fix (see [Caveats](#caveats)); the services measured
are those of `66d113c`.

## Environment

| | |
|---|---|
| Host | Apple M4, 10 logical CPUs, 16 GiB, macOS; nothing else running |
| Virtualisation | Linux VM (Colima, Apple Virtualization.framework), 8 vCPU / 12 GiB, Ubuntu 24.04, kernel 6.8 |
| Kubernetes | k3s v1.35.0, one node, Docker 29.5 runtime |
| Load generator | k6 v2.4.0, as an in-cluster Job; no request crosses a network outside the VM |
| Architecture | arm64 |

## Fairness rules

| Rule | Setting |
|---|---|
| Resources | app 2 vCPU / 2 GiB, database 2 vCPU / 2 GiB, k6 2 vCPU; requests equal limits |
| One system under test at a time | each scenario deploys, measures and tears down before the next |
| Empty database | a fresh volume (same size, same storage class) for every scenario |
| Same crypto | the shipped demo signing key (PS512, `JWK_SET`), the same test-CA trust; payers alternate ES256 and PS512 |
| Revocation | checked for real against the test CA's CRL, served next to the app |
| Warm-up | the JVM gets 2 minutes of the same script before each measured Java run, excluded from measurement; its data is deleted before the run. Go gets none |
| Go memory | `GOMEMLIMIT` at 80% of the limit |
| Test clock | off in both builds |

## Results

### S1: create only

Ramp to 500 virtual users, sustain 3 minutes, think time 1–3 s between requests. The rate is set by
the think time, so this measures response time at a fixed load, not capacity.

| | Java | Go |
|---|---|---|
| Requests per second | 189.4 (189.1–190.2) | 190.0 (187.2–190.1) |
| Median | 1.5 ms (1.4–1.5) | 1.7 ms (1.7–1.7) |
| p95 | 4.6 ms (4.4–4.8) | 3.2 ms (3.1–3.2) |
| p99 | 7.9 ms (7.6–8.1) | 4.1 ms (4.1–4.1) |
| App CPU, median | 224 m | 196 m |
| App memory, peak | 387 MiB | 31 MiB |
| Database CPU, median | 239 m | 119 m |
| Failed requests | 0 | 0 |

### S2: the full payment cycle

Each iteration is one payment: create → signed payload fetch → pre-commit notification →
post-commit notification (Solana, with a `transactionId`) → status update to `PAID`. Every request
and answer is signed, and k6 verifies every signed answer as a payer must. Same load shape as S1.

| | Java | Go |
|---|---|---|
| Payments per second | 161.8 (160.1–162.2) | 163.2 (162.9–164.0) |
| HTTP requests per second | 809 (800–811) | 816 (814–820) |
| Payments completed | 48,201 (47,750–48,308) | 48,654 (48,415–49,394) |
| One request: median | 63.6 ms (62.1–67.1) | 68.8 ms (58.8–75.1) |
| One request: p95 | 160 ms (154–172) | 140 ms (130–141) |
| One request: p99 | 229 ms (211–244) | 163 ms (159–163) |
| Whole payment: median | 354 ms (344–372) | 340 ms (309–362) |
| Whole payment: p95 | 648 ms (615–686) | 576 ms (538–576) |
| Whole payment: p99 | 808 ms (768–860) | 647 ms (639–654) |
| App CPU, median | 2,022 m | 1,909 m |
| App memory, peak | 688 MiB | 35 MiB |
| Database CPU, median | 777 m | 494 m |
| Database memory, peak | 1,124 MiB | 1,929 MiB |
| Failed requests / failed signature checks | 0 / 0 | 0 / 0 |

Per step, median / p95 / p99 (medians of the 3 runs):

| Step | Java | Go |
|---|---|---|
| Create | 53 / 136 / 201 ms | 76 / 138 / 159 ms |
| Signed payload fetch | 44 / 129 / 199 ms | 6.5 / 13 / 17 ms |
| Pre-commit notification | 74 / 171 / 249 ms | 85 / 147 / 168 ms |
| Post-commit notification | 74 / 170 / 251 ms | 86 / 147 / 168 ms |
| Status update `PAID` | 78 / 170 / 231 ms | 82 / 143 / 165 ms |

Java's median is lower on create and on both notifications; Go's p95 and p99 are lower on every
step. The payload fetch is a read and a signature: Go serves it in a fraction of Java's time.

The database's memory is its working set, which includes the operating system's file cache for its
files; PostgreSQL's rises toward its limit as the tables grow.

### S7: footprint

Run on the VM's container engine after the cluster is torn down, with the same limits
(`--cpus 2 --memory 2g`).

| | Java | Go |
|---|---|---|
| Image, uncompressed | 392.8 MB | 16.3 MB (corrected, see below) |
| Image, compressed | 176.1 MB | 5.5 MB |
| Cold start to `/actuator/health` UP | 2,635 ms (2,616–2,697) | 135 ms (124–163) |
| Memory at rest, after 5 idle minutes | 282 MiB (263–314) | 4.0 MiB (4.0–4.1) |

**Correction (Story 14.1).** The campaign read the uncompressed size from `docker image inspect
--format '{{.Size}}'`. On Docker's containerd image store, which the VM's engine uses, that field
is the image's content size: the layer blobs as stored. The Java image's blobs are stored
uncompressed, so its 392.8 MB is right; the Go image's are gzip-compressed, so the campaign recorded
its compressed size, 5.5 MB, as uncompressed (it is still in the results' `sizeBytes`). The Go image
rebuilt from the campaign's commit measures 16.3 MB unpacked and 5.5 MB compressed. S7 now reads
both sizes from `docker save` with
[`others/scripts/image-size.py`](others/scripts/image-size.py), the same on either store.

## Database

After one S2 run (about 48,000 payments, run 1 of each build):

| | MongoDB | PostgreSQL |
|---|---|---|
| On disk | 57.5 MB (37.0 MB data, 20.5 MB indexes) | 731 MB |
| QR Codes | 47,750 documents | 48,450 rows, 65 MB |
| Stored notifications | inside the QR Code documents | 96,990 rows, 604 MB |
| Connections held | 107 | 13 |

The Go build keeps each payer notification as a row of its own, with the signed message in full,
so a payee can count and audit them (ADR-0036). Those rows are 83% of its database. MongoDB
compresses what it stores; PostgreSQL does not by default. **Per payment, the Go build uses about 12
times the disk.** Making that smaller is a follow-up, not something this benchmark hides.

Event counts are not compared: the two builds keep their event logs differently.

## Caveats

- **Virtualised, arm64, one node.** Absolute numbers are lower than on bare metal; both builds pay
  the same virtualisation cost on the same host, so the comparison holds.
- **Demo crypto.** The shipped PS512 demo key signs every answer. A bank's own key and algorithm
  change the absolute numbers for both builds.
- **The harness was fixed during this campaign.** Before `743f160`, k6 could start a moment before
  the cluster routed traffic to a freshly deployed app; without a warm-up (Go's case) its first
  requests were refused and S2's setup aborted. The harness now waits until the Service answers from
  inside the cluster. This campaign ran entirely on the fixed harness.
- **Scenarios not run:** S3 (an events consumer under load), S4 (contention), S5 (soak), S6
  (saturation). They are run if a question needs them.
- **One campaign, one machine.** A release that could move a number gets a new campaign, in a new
  results directory; this one is not edited.

## Raw data and how to reproduce

- Summaries of every run, with their environment and image records:
  [`others/benchmark/results/`](others/benchmark/results/) (`*/2026-11-15T2347Z-r*`).
- Each run's `SHA256SUMS` lists the checksum of its raw k6 output. The raw files are published on a
  GitHub release, `bench-2026-11-15T2347Z`, after review.
- The harness, the manifests and the steps to run a campaign on any Mac:
  [`others/benchmark/README.md`](others/benchmark/README.md).

---

<sub>Copyright © 2026 Matera Systems, Inc. Licensed under the Matera Source License v1.0 (source-available; not open source) — see LICENSE.md at the repository root. Creating a Derivative Work from this document — by AI/ML generation or by manual re-implementation based on it — is governed by that license (see the "Derivative Work" definition and Annex A).</sub>

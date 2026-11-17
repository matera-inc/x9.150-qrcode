# Benchmark: Java + MongoDB vs Go + PostgreSQL

This compares **two complete builds of one product**, the Java build on MongoDB and the Go build on
PostgreSQL, under the same demand on the same machine. It is **not a database benchmark**: each
payment is a handful of keyed rows, and signing and verifying JWS dominate the cost.

What matters most to a small bank is not in a latency table. The Go build needs **no second
database**: it lives in one schema of the PostgreSQL the bank already runs, with three roles and a
bounded number of connections, where the Java build needs a MongoDB replica set of its own. It is
also far **lighter** to run. Why the service moved is told in [HISTORY.md](HISTORY.md); those
reasons do not depend on these numbers. The speed numbers below confirm that nothing was given up
for them. Every number is published as measured, whichever way it falls.

**Campaign of record: `2026-11-17T1119Z`**, both builds from commit `a943630`. Three runs per
build, interleaved Java, Go, Java, Go, Java, Go. Each figure is the **median of the three runs**,
with the range (min–max) beside it.

## Summary

| | Java + MongoDB | Go + PostgreSQL | Go vs Java |
|---|---|---|---|
| A second database engine to run | MongoDB replica set | none: a schema in the bank's PostgreSQL | |
| App memory at rest (S7) | 308 MiB | 4.1 MiB | 75× less |
| App memory, peak under full load (S2) | 662 MiB | 37 MiB | 18× less |
| Image, uncompressed / compressed (S7) | 418 / 176 MB | 16.3 / 5.6 MB | 26× / 32× smaller |
| Cold start to healthy (S7) | 2.62 s | 0.13 s | 20× faster |
| Database connections held after S2 | 107 | 13 | 8× fewer |
| Database CPU during S2 | 760 m | 494 m | 35% less |
| Full payment cycle (S2): payments per second | 160.7 | 163.2 | +2% |
| Full payment cycle (S2): one request, median | 59.4 ms | 57.0 ms | −4% |
| Full payment cycle (S2): one request, p99 | 214 ms | 174 ms | −19% |
| Signed payload fetch (S2), median | 38.2 ms | 6.5 ms | 5.9× faster |
| Database on disk after S2, per 1,000 payments | 1.2 MB | 15.0 MB | **12.5× larger** |
| Failed requests / failed signature checks | 0 / 0 | 0 / 0 | |

Throughput is the same within 2%: both apps run at their 2 vCPU limit through S2, signing and
verifying every step. Go's tail is shorter and steadier. **Java wins on storage**: MongoDB compresses
what it stores, and the Go build keeps every signed notification in full (see [Database](#database)).

## What was compared

| | Java build | Go build |
|---|---|---|
| Image | `x9-qrcode:git-a943630` (Spring Boot, buildpacks) | `x9-qrcode-go:git-a943630` (static binary, distroless) |
| Database | MongoDB 7.0.45, single-node replica set | PostgreSQL 16, schema applied by Liquibase |
| Durability | `w: majority`, `j: true` | `synchronous_commit = on`, `fsync = on` |

Both images and the harness were built from the same commit, `a943630`.

## Environment

| | |
|---|---|
| Host | Apple M4, 10 logical CPUs, 16 GiB, macOS; nothing else running |
| Virtualisation | Linux VM (Colima, Apple Virtualization.framework), 8 vCPU / 12 GiB, Ubuntu 24.04, kernel 6.8 |
| Kubernetes | k3s v1.35.0, one node, Docker runtime |
| Load generator | k6 v2.4.0, as an in-cluster Job; no request leaves the VM |
| Architecture | arm64 |

The Go image was also checked, outside this benchmark, on a real amd64 machine (AMD Ryzen 5 3600):
the whole black-box suite passes there.

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
| Ready before load | k6 starts only once the app's Service answers inside the cluster |

## Results

### S1: create only

Ramp to 500 virtual users, sustain 3 minutes, think time 1–3 s between requests. The rate is set by
the think time, so this measures response time at a fixed load, not capacity.

| | Java | Go |
|---|---|---|
| Requests per second | 187.0 (186.5–187.1) | 187.2 (186.8–189.7) |
| Median | 1.5 ms (1.4–1.5) | 1.7 ms (1.7–1.7) |
| p95 | 4.5 ms (4.5–4.7) | 3.1 ms (3.1–3.1) |
| p99 | 7.5 ms (7.4–7.9) | 4.0 ms (4.0–4.1) |
| App CPU, median | 227 m | 194 m |
| App memory, peak | 387 MiB | 31 MiB |
| Database CPU, median | 237 m | 118 m |
| Failed requests | 0 | 0 |

### S2: the full payment cycle

Each iteration is one payment: create → signed payload fetch → pre-commit notification →
post-commit notification (Solana, with a `transactionId`) → status update to `PAID`. Every request
and answer is signed, and k6 verifies every signed answer as a payer must. Same load shape as S1.

| | Java | Go |
|---|---|---|
| Payments per second | 160.7 (158.5–171.4) | 163.2 (162.8–163.3) |
| HTTP requests per second | 804 (793–857) | 816 (814–816) |
| Payments completed | 48,630 (47,931–51,746) | 49,143 (48,775–49,246) |
| One request: median | 59.4 ms (29.1–66.2) | 57.0 ms (55.2–63.1) |
| One request: p95 | 153 ms (89–171) | 143 ms (134–148) |
| One request: p99 | 214 ms (141–255) | 174 ms (166–184) |
| Whole payment: median | 332 ms (164–366) | 310 ms (303–333) |
| Whole payment: p95 | 619 ms (434–686) | 611 ms (559–618) |
| Whole payment: p99 | 909 ms (770–2,585) | 726 ms (668–2,707) |
| App CPU, median | 1,997 m | 1,908 m |
| App memory, peak | 662 MiB | 37 MiB |
| Database CPU, median | 760 m | 494 m |
| Database memory, peak | 999 MiB | 1,938 MiB |
| Failed requests / failed signature checks | 0 / 0 | 0 / 0 |

Per step, median / p95 / p99 (medians of the 3 runs):

| Step | Java | Go |
|---|---|---|
| Create | 50 / 129 / 188 ms | 68 / 142 / 171 ms |
| Signed payload fetch | 38 / 120 / 193 ms | 6.5 / 13 / 18 ms |
| Pre-commit notification | 70 / 162 / 237 ms | 77 / 151 / 181 ms |
| Post-commit notification | 70 / 165 / 233 ms | 77 / 151 / 180 ms |
| Status update `PAID` | 72 / 163 / 218 ms | 74 / 148 / 177 ms |

Java's median is lower on create and on the two notifications; Go's p95 and p99 are lower on every
step, and its runs vary far less. Java's third run reached 857 req/s with a 29 ms median, its first
two about 800 req/s and 60–66 ms: at this load the Java build, too, switches between a faster and a
slower regime from run to run, which is why every figure carries its range.

The database's memory is its working set, which includes the operating system's file cache for its
files; PostgreSQL's rises toward its limit as the tables grow.

### S7: footprint

Run on the VM's container engine after the cluster is torn down, with the same limits
(`--cpus 2 --memory 2g`). Image sizes are read from the image's own layers (`docker save`), the
same way for both builds.

| | Java | Go |
|---|---|---|
| Image, uncompressed | 418.1 MB | 16.3 MB |
| Image, compressed | 176.4 MB | 5.6 MB |
| Cold start to `/actuator/health` UP | 2,622 ms (2,616–2,641) | 132 ms (128–139) |
| Memory at rest, after 5 idle minutes | 308 MiB (266–313) | 4.1 MiB (4.1–4.2) |

## Database

After one S2 run (about 49,000 payments, run 1 of each build):

| | MongoDB | PostgreSQL |
|---|---|---|
| On disk | 58.2 MB (37.3 MB data, 21.0 MB indexes) | 739 MB |
| QR Codes | 48,630 documents | 48,618 rows, 65 MB |
| Stored notifications | inside the QR Code documents | 97,410 rows, 612 MB |
| Connections held | 107 | 13 |

The Go build keeps each payer notification as a row of its own, with the signed message in full, so
a payee can count and audit them (ADR-0036). Those rows are 83% of its database. MongoDB compresses
what it stores; PostgreSQL does not by default. **Per payment, the Go build uses about 12 times the
disk.** Retention (ADR-0029) bounds how much accumulates; making each row smaller is a possible
follow-up, not something this benchmark hides.

Event counts are not compared: the two builds keep their event logs differently.

## What changed since the first campaign

The first campaign (`2026-11-15T2347Z`, commit `66d113c`) is kept in
[`others/benchmark/results/`](others/benchmark/results/) and its raw output on the release
`bench-2026-11-15T2347Z`. Three things changed since:

1. **The Go build's image size was misreported.** The first campaign read Docker's `.Size` field,
   which on the newer containerd image store is the compressed content, not the unpacked image.
   Both builds are now measured from the image's own layers.
2. **Go got slower, then faster.** A later change (request metrics, `081d967`) made the Go build's
   writes about 28 ms slower at the median under this load. Bisecting showed that even an empty
   middleware in its place did the same, and CPU profiles of slow and fast builds were identical:
   the cost was waiting, not work. The app's CPU is saturated by RSA, and a write transaction holds
   a database connection across several round trips; after each, its goroutine waited in a long run
   queue behind JWS work, so the small write pool became the bottleneck. The fix (`internal/cpu`,
   PRs #206 and #207) lets at most one goroutine per CPU sign or verify at a time, and gives the turn
   back during every network fetch. Measured on this machine, Go only, two runs each:

   | Go | HTTP req/s | median |
   |---|---|---|
   | without the bound | 779 | 91 ms |
   | bound around the signature arithmetic only | 781–784 | 89–97 ms |
   | bound around the whole verification and signature | 812–813 | 64–65 ms |

   The code where this lives carries the same explanation, so it is not undone by accident.
3. **The harness** waits until the app answers inside the cluster before starting k6, and no
   longer reports a clean campaign as failed.

## Caveats

- **Virtualised, arm64, one node.** Absolute numbers are lower than on bare metal; both builds pay
  the same virtualisation cost on the same host, so the comparison holds.
- **Near saturation, both builds are bimodal.** Small changes in scheduling move latency by tens of
  percent. Compare builds only within one campaign, and a change only with an A/B on the same
  machine.
- **Demo crypto.** The shipped PS512 demo key signs every answer. A bank's own key and algorithm
  change the absolute numbers for both builds.
- **Scenarios not run:** S3 (an events consumer under load), S4 (contention), S5 (soak), S6
  (saturation). They are run if a question needs them.

## Raw data and how to reproduce

- Summaries of every run, with their environment and image records:
  [`others/benchmark/results/`](others/benchmark/results/) (`*/2026-11-17T1119Z-r*`).
- Each run's `SHA256SUMS` lists the checksum of its raw k6 output. The raw files are on the GitHub
  release `bench-2026-11-17T1119Z`.
- The harness, the manifests and the steps to run a campaign on any Mac:
  [`others/benchmark/README.md`](others/benchmark/README.md).
- Since 3.0.0 the Java build is not on `main`
  ([ADR-0039](docs/adr/0039-java-retired-at-3-0-0.md)). The harness runs the Go build alone by
  default; to repeat this comparison, run `SEQUENCE="J G J G J G"`, whose Java turns use the
  `materainc/x9-qrcode:java-final` image.

---

<sub>Copyright © 2026 Matera Systems, Inc. Licensed under the Matera Source License v1.0 (source-available; not open source) — see LICENSE.md at the repository root. Creating a Derivative Work from this document — by AI/ML generation or by manual re-implementation based on it — is governed by that license (see the "Derivative Work" definition and Annex A).</sub>

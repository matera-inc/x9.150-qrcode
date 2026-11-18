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

The comparison was run on **two machines, two architectures and two signing algorithms**:

| Campaign | Machine | Signing algorithm and key |
|---|---|---|
| **`2026-11-17T1119Z`**, of record | Apple M4, **arm64**, in a Linux VM | **PS512 with RSA-3072** |
| `2026-11-17T2005Z` | AMD Ryzen 5 3600, **amd64**, bare metal | **PS512 with RSA-3072** |
| `2026-11-17T2211Z` | AMD Ryzen 5 3600, **amd64**, bare metal | **ES256 with P-256** |
| `soak-2026-11-18T0017Z` | AMD Ryzen 5 3600, **amd64**, bare metal | **PS512 with RSA-3072**, Go only, 30 minutes |

PS512 is RSA-PSS with SHA-512; the 512 names the hash, not the key, which is RSA-3072. ES256 is ECDSA
on the P-256 curve with SHA-256. Both keys give about 128 bits of security. In every campaign, three
runs per build, interleaved Java, Go, Java, Go, Java, Go; each figure is the **median of the three
runs**, with the range (min–max) beside it where it matters.

## The request people wait for: fetching the QR Code to pay

When a customer scans a QR Code at the counter, their bank's app fetches the **signed payload**
from the payee's service: the amount, the payee and the ways to pay. It is the most frequent request
in real use and the one a person waits for, phone in hand. On every machine and with either
algorithm, **the Go build serves it several times faster**, with a far shorter tail:

| Signed payload fetch, under full load (S2) | Java: median / p99 | Go: median / p99 | Go vs Java, median |
|---|---|---|---|
| arm64, PS512 with RSA-3072 | 38.2 / 193 ms | **6.5 / 18 ms** | **5.9× faster** |
| amd64, PS512 with RSA-3072 | 253 / 1,570 ms | **11.5 / 36 ms** | **22× faster** |
| amd64, ES256 with P-256 | 55.6 / 207 ms | **9.5 / 103 ms** | **5.9× faster** |

Both builds do the same work on each fetch: verify the payer's signed request, check its signer's
certificate and revocation, and sign a fresh answer. The Go build reads one row and writes nothing
([`internal/paymentrequest/payload.go`](internal/paymentrequest/payload.go)), so while the writes
of other payments queue for the database, a fetch does not wait behind them. The measurement shows
the result; this run does not isolate which part of the Java build's path makes the difference.

## Summary

### arm64, PS512 with RSA-3072 (campaign of record)

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

### amd64, both algorithms

| | Java, PS512 | Go, PS512 | Java, ES256 | Go, ES256 |
|---|---|---|---|---|
| Full payment cycle (S2): payments per second | 88.2 | **95.5** (+8%) | 156.0 | 157.2 (+1%) |
| One request: median | **356 ms** | 533 ms | 70.1 ms | 70.5 ms |
| One request: p99 | 1,862 ms | **707 ms** | **238 ms** | 262 ms |
| Whole payment: p99 | 5,168 ms | **2,712 ms** | **889 ms** | 1,093 ms |
| Signed payload fetch, median | 253 ms | **11.5 ms** | 55.6 ms | **9.5 ms** |
| App CPU during S2 (limit 2,000 m) | 1,998 m | 1,886 m | 1,973 m | **1,548 m** |
| Database CPU during S2 | 831 m | **505 m** | 1,292 m | **778 m** |
| App memory, peak under load / at rest | 800 / 310 MiB | **50 / 4.3 MiB** | 667 / 275 MiB | **41 / 4.1 MiB** |
| Cold start to healthy (S7) | 5.71 s | **0.26 s** | 5.87 s | **0.29 s** |
| Failed requests / failed signature checks | 0 / 0 | 0 / 0 | 0 / 0 | 0 / 0 |

On the older amd64 machine, **PS512 puts both builds past their capacity** at 500 users: requests
queue, and the Go build keeps 8% more throughput and a tail 2.6 times shorter, while the Java build's
median is lower. **With ES256 both builds have room again**: they do the same work at the same speed,
and the Java build's tail is a little shorter, but **the Go build does it with 22% less app CPU and
40% less database CPU**, the headroom it has for more load.

**The signing algorithm matters more than the build.** On the same machine, ES256 raises
throughput by 77% for the Java build and by 65% for the Go build, and cuts the median request from
hundreds of milliseconds to 70. This is why, since 3.0.1, the image's bundled demo identity signs
ES256 ([ADR-0040](docs/adr/0040-the-bundled-demo-identity-signs-es256.md)); an operator's own RSA
key still signs PS512 unless told otherwise.

## What was compared

| | Java build | Go build |
|---|---|---|
| arm64 campaign | `x9-qrcode:git-a943630` (Spring Boot, buildpacks) | `x9-qrcode-go:git-a943630` (static binary, distroless) |
| amd64 campaigns | `x9-qrcode:git-ff6bc43`, built from the git tag `java-final` | `materainc/x9-qrcode:3.0.0` from Docker Hub (`c9b77ca`) |
| Database | MongoDB 7.0.45, single-node replica set | PostgreSQL 16, schema applied by Liquibase |
| Durability | `w: majority`, `j: true` | `synchronous_commit = on`, `fsync = on` |

The arm64 images and harness were built from the same commit, `a943630`. On amd64, the Java image is
the last Java build of the service, and the Go image is the published 3.0.0, pulled as anyone would.

An earlier amd64 attempt used the then-current Docker Hub image `materainc/x9-qrcode:java-final`. It
had been built before Java signed its answers to payment notifications, so every payer correctly
refused its pre-commit answer and its runs were discarded. The amd64 campaigns use an image built
from the git tag instead, and the Docker Hub tag now points at that build.

## Environment

| | arm64 | amd64 |
|---|---|---|
| Host | Apple M4, 10 logical CPUs, 16 GiB, macOS; nothing else running | AMD Ryzen 5 3600, 12 logical CPUs, 16 GiB, SATA SSD; nothing else running |
| Virtualisation | Linux VM (Colima, Apple Virtualization.framework), 8 vCPU / 12 GiB, Ubuntu 24.04, kernel 6.8 | none: bare metal, Ubuntu 24.04, kernel 7.0 |
| Kubernetes | k3s v1.35.0, one node, Docker runtime | k3s v1.37.2, one node, Docker runtime |
| Load generator | k6 v2.4.0, as an in-cluster Job; no request leaves the machine | the same |

## Fairness rules

| Rule | Setting |
|---|---|
| Resources | app 2 vCPU / 2 GiB, database 2 vCPU / 2 GiB, k6 2 vCPU; requests equal limits |
| One system under test at a time | each scenario deploys, measures and tears down before the next |
| Empty database | a fresh volume (same size, same storage class) for every scenario |
| Same crypto | both builds sign with the same identity and algorithm: the RSA-3072 demo key both images shipped until 3.0.0 (PS512, `SIGNING=ps512`), or the test CA's P-256 leaf (ES256, `SIGNING=es256`, the harness's default since 3.0.1); the same test-CA trust; payers alternate ES256 and PS512. k6 refuses an answer signed with any other algorithm than the campaign's |
| Revocation | checked for real against the test CA's CRL, served next to the app |
| Warm-up | the JVM gets 2 minutes of the same script before each measured Java run, excluded from measurement; its data is deleted before the run. Go gets none |
| Go memory | `GOMEMLIMIT` at 80% of the limit |
| Test clock | off in both builds |
| Ready before load | k6 starts only once the app's Service answers inside the cluster |

## Results on arm64

Campaign of record `2026-11-17T1119Z`, PS512 with RSA-3072.

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

## Results on amd64

The same scenarios, limits and load shape as on arm64, on bare metal. Two campaigns, one per
signing algorithm, run back to back on the same machine.

### S2: the full payment cycle

| | Java, PS512 | Go, PS512 | Java, ES256 | Go, ES256 |
|---|---|---|---|---|
| Payments per second | 88.2 (87.8–88.9) | 95.5 (95.3–95.6) | 156.0 (155.9–161.0) | 157.2 (157.1–157.4) |
| HTTP requests per second | 441 (439–445) | 478 (477–478) | 780 (780–805) | 786 (786–787) |
| Payments completed | 26,650 (26,526–26,913) | 28,826 (28,822–28,855) | 47,166 (47,066–48,564) | 47,514 (47,419–47,538) |
| One request: median | 356 ms (344–358) | 533 ms (523–535) | 70.1 ms (54.0–70.6) | 70.5 ms (69.5–71.1) |
| One request: p95 | 1,230 ms (1,215–1,291) | 666 ms (653–675) | 167 ms (140–168) | 170 ms (167–175) |
| One request: p99 | 1,862 ms (1,852–1,912) | 707 ms (690–716) | 238 ms (219–249) | 262 ms (252–265) |
| Whole payment: median | 2,299 ms | 2,289 ms | 404 ms | 381 ms |
| Whole payment: p95 | 4,242 ms | 2,608 ms | 725 ms | 759 ms |
| Whole payment: p99 | 5,168 ms | 2,712 ms | 889 ms | 1,093 ms |
| App CPU, median | 1,998 m | 1,886 m | 1,973 m | 1,548 m |
| App memory, peak | 800 MiB | 50 MiB | 667 MiB | 41 MiB |
| Database CPU, median | 831 m | 505 m | 1,292 m | 778 m |
| Database memory, peak | 974 MiB | 1,230 MiB | 1,078 MiB | 1,868 MiB |
| Failed requests / failed signature checks | 0 / 0 | 0 / 0 | 0 / 0 | 0 / 0 |

Per step, median / p95 / p99 (run 1 of each; the other runs agree within a few percent):

| Step | Java, PS512 | Go, PS512 | Java, ES256 | Go, ES256 |
|---|---|---|---|---|
| Create | 331 / 1,207 / 1,822 ms | 549 / 647 / 681 ms | 66 / 154 / 213 ms | 79 / 180 / 271 ms |
| Signed payload fetch | 253 / 968 / 1,547 ms | **11.5 / 27 / 36 ms** | 56 / 142 / 207 ms | **9.5 / 65 / 101 ms** |
| Pre-commit notification | 371 / 1,186 / 1,839 ms | 566 / 664 / 698 ms | 76 / 177 / 254 ms | 88 / 189 / 269 ms |
| Post-commit notification | 388 / 1,227 / 1,884 ms | 566 / 663 / 698 ms | 77 / 176 / 254 ms | 89 / 190 / 277 ms |
| Status update `PAID` | 472 / 1,396 / 2,056 ms | 561 / 659 / 693 ms | 80 / 179 / 248 ms | 87 / 187 / 269 ms |

With PS512, both apps are at their 2 vCPU limit and requests queue: the Java build answers most
writes sooner, but one request in twenty waits more than 1.2 seconds; the Go build's writes all wait
about the same half-second, and its fetch stays near 10 ms. With ES256, neither app is saturated,
and the writes are within about 15 ms of each other.

### S1: create only

| | Java, PS512 | Go, PS512 | Java, ES256 | Go, ES256 |
|---|---|---|---|---|
| Requests per second | 186.8 | 186.7 | 186.5 | 186.6 |
| Median | 4.3 ms | 1.7 ms | 4.2 ms | 1.7 ms |
| p95 | 6.7 ms | 3.0 ms | 6.9 ms | 3.0 ms |
| p99 | 15.6 ms | 4.1 ms | 15.4 ms | 4.1 ms |
| App CPU, median | 426 m | 214 m | 438 m | 214 m |
| App memory, peak | 390 MiB | 34 MiB | 370 MiB | 34 MiB |
| Database CPU, median | 521 m | 127 m | 520 m | 126 m |

Create signs nothing, so the algorithm does not change S1. On this machine the Go build answers a
create in less than half the Java build's time, with half the app CPU and a quarter of the database
CPU.

### S7: footprint

| | Java | Go |
|---|---|---|
| Image, uncompressed / compressed | 410.0 / 179.2 MB | 17.3 / 6.0 MB |
| Cold start to `/actuator/health` UP | 5,709 ms (5,593–5,813) | 257 ms (254–271) |
| Memory at rest, after 5 idle minutes | 310 MiB (283–337) | 4.3 MiB (4.2–4.4) |

The S7 figures are from the PS512 campaign; the ES256 campaign's agree (Java 5.87 s and 275 MiB, Go
0.29 s and 4.1 MiB).

## Soak: 30 minutes at full load

The Go build only (`materainc/x9-qrcode:3.0.0`), amd64, PS512 with RSA-3072: the full payment cycle
with 500 virtual users held for 30 minutes, after a 1-minute ramp. It looks for what a 3-minute run
cannot show: memory that grows, latency that drifts, a database that slows as it fills.

| | |
|---|---|
| Payments completed | **211,980**, at 112 per second |
| Failed requests / failed signature checks | **0 / 0** |
| One request: median / p95 / p99 | 572 / 684 / 766 ms |
| Signed payload fetch: median / p99 | 11.9 / 39 ms |
| Database on disk at the end | 3.1 GB (207,682 QR Codes, 403,129 stored notifications) |
| Database connections held | 13 |

Over time, in 5-minute windows:

| Minutes | 0–5 | 5–10 | 10–15 | 15–20 | 20–25 | 25–30 |
|---|---|---|---|---|---|---|
| HTTP requests per second | 542 | 569 | 568 | 566 | 566 | 568 |
| One request: median | 559 ms | 575 ms | 577 ms | 574 ms | 577 ms | 575 ms |
| One request: p99 | 720 ms | 735 ms | 744 ms | 776 ms | 817 ms | 764 ms |
| App memory | 47 MiB | 49 MiB | 49 MiB | 48 MiB | 48 MiB | 48 MiB |

Throughput and the median are flat from the fifth minute to the thirtieth while the database grows
past 3 GB; the app's memory stays at 47–49 MiB. The p99 moves by about 10% from window to window,
without a trend. The app logged no error or warning, and did not restart. The database's memory
reaches its 2 GiB limit, which is the operating system's file cache filling, as expected.

The soak shows more requests per second than the campaign's S2 (560 against 478) mostly because the
campaign's average includes its ramp up and down, where fewer users are active.

## Database

After one S2 run (run 1 of each build):

| | MongoDB, arm64 PS512 | PostgreSQL, arm64 PS512 | MongoDB, amd64 ES256 | PostgreSQL, amd64 ES256 |
|---|---|---|---|---|
| Payments completed | 48,630 | 49,143 | 47,066 | 47,514 |
| On disk | 58.2 MB | 739 MB | 55.7 MB | 714 MB |
| Stored notifications | inside the QR Code documents | 97,410 rows, 612 MB | inside the QR Code documents | 93,920 rows, 590 MB |
| Connections held | 107 | 13 | 107 | 13 |

The Go build keeps each payer notification as a row of its own, with the signed message in full, so
a payee can count and audit them (ADR-0036). Those rows are about 83% of its database. MongoDB
compresses what it stores; PostgreSQL does not by default. **Per payment, the Go build uses about 12
to 13 times the disk**, on both machines. Retention (ADR-0029) bounds how much accumulates; making
each row smaller is a possible follow-up, not something this benchmark hides.

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

- **One node per machine.** The arm64 campaign ran in a VM, the amd64 campaigns on bare metal.
  Absolute numbers differ between the machines; within a campaign, both builds ran on the same host
  under the same limits, so the comparison holds.
- **Near saturation, both builds are bimodal.** Small changes in scheduling move latency by tens of
  percent. Compare builds only within one campaign, and a change only with an A/B on the same
  machine.
- **Demo crypto.** The campaigns sign with the shipped demo key or the test CA's P-256 leaf. A bank's
  own key and algorithm change the absolute numbers for both builds; as the amd64 campaigns show, the
  algorithm changes them a great deal.
- **Scenarios not run:** S3 (an events consumer under load), S4 (contention), S6 (saturation). S5,
  the soak, was run on the Go build only. They are run if a question needs them.

## Raw data and how to reproduce

- Summaries of every run, with their environment and image records:
  [`others/benchmark/results/`](others/benchmark/results/), under each campaign's name.
- Each run's `SHA256SUMS` lists the checksum of its raw k6 output. The raw files are on one GitHub
  release per campaign: `bench-2026-11-17T1119Z`, `bench-2026-11-17T2005Z`,
  `bench-2026-11-17T2211Z` and `bench-soak-2026-11-18T0017Z`.
- The harness, the manifests and the steps to run a campaign:
  [`others/benchmark/README.md`](others/benchmark/README.md). `SIGNING=es256` runs a campaign with
  ES256.
- Since 3.0.0 the Java build is not on `main`
  ([ADR-0039](docs/adr/0039-java-retired-at-3-0-0.md)). The harness runs the Go build alone by
  default; to repeat this comparison, run `SEQUENCE="J G J G J G"`, whose Java turns use the
  `materainc/x9-qrcode:java-final` image, built from the git tag `java-final`.

---

<sub>Copyright © 2026 Matera Systems, Inc. Licensed under the Matera Source License v1.0 (source-available; not open source) — see LICENSE.md at the repository root. Creating a Derivative Work from this document — by AI/ML generation or by manual re-implementation based on it — is governed by that license (see the "Derivative Work" definition and Annex A).</sub>

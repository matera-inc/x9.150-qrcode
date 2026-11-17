# 39. The Java build is retired at 3.0.0: removed from `main`, kept in git history and as `java-final`

Date: 2026-11-17

## Status

Accepted 2026-11-17 (owner). Supersedes the `legacy/java/` freeze of
[0022](0022-rewrite-in-go-as-a-standalone-product.md) (and of the plan's Epic 17). Amends
[0028](0028-the-http-contract-is-frozen-and-equivalence-is-proved-by-a-differential-run.md),
[0030](0030-a-black-box-suite-defines-done-and-migration-writes-only-runtime-valid-rows.md) and
[0031](0031-same-image-lighter-and-3-0-is-a-configuration-migration.md) where they assume two builds
side by side; each carries a dated pointer here. Part of 0022.

## Context

ADR-0022 kept the Java build in this repository "for history": its modules were to move unchanged
to `legacy/java/` with `git mv`, frozen, excluded from CI, nothing deleted. Until then, both builds
lived side by side on `main`, and CI built and tested both: the Maven `build` job, the black-box
suite against each (`blackbox`, `blackbox-go`), the `differential` run that diffed their answers
(ADR-0028), and the four Java/Go pairings of a payment between two instances.

That work is finished. The Go build passes the whole black-box suite; the differential run has
shown every difference to be either tolerated and written down
([TOLERATED-DIFFERENCES.md](../../others/blackbox/TOLERATED-DIFFERENCES.md)) or fixed; the
benchmark of record ([BENCHMARK.md](../../BENCHMARK.md)) has measured the two builds against each
other. The Java build has no production users (ADR-0038).

A frozen copy under `legacy/java/` would cost something every day and give nothing that git does
not already give: every search, every review, every licence and dependency scan, and every agent
reading the repository would meet 20k lines of code that nobody runs, beside the code that runs.

The owner, 2026-11-17: "The Java will be retired, left in GitHub for the history (only for whom get
an old commit), you can tag as v3.0.0 and the main will have just the Go/PostgreSQL code — plus the
benchmark (raw and consolidated data)."

## Decision

**`main` holds the Go build on PostgreSQL only, from 3.0.0.** The Java build is removed from it:
the Maven modules, `pom.xml` and its wrapper, the Java Compose files and Spring secrets samples, the
Java parts of the Helm chart, and every CI job that needed a Java image (`build`, `blackbox`,
`differential`, and the Java pairings).

**The Java build stays reachable, two ways:**

- **In git:** the last commit that holds it is tagged **`java-final`**. `git checkout java-final`
  builds and tests it exactly as before (`./mvnw clean install`).
- **On Docker Hub:** its last image is **`materainc/x9-qrcode:java-final`**, as ADR-0031 planned.

**What both builds shared moves out of the Java modules,** unchanged: the OpenAPI contract to
`api/openapi.yaml`, and the NON-PRODUCTION test PKI to `testdata/pki/`.

**The records of the comparison stay:** every ADR, `others/blackbox/differential.py` with its
recording code and `TOLERATED-DIFFERENCES.md`, the golden JWS and EMV sets Java produced
(`others/blackbox/golden/`), and the benchmark harness with both campaigns' results. The
differential run, the golden export and the benchmark's Java turns can still be run against the
`java-final` image; nothing on `main` builds Java.

**The image:** `materainc/x9-qrcode` publishes the Go build from 3.0.0 (`3.0.0`, `3.0`, `3`,
`git-<sha>`, and `latest`), built for amd64 and arm64 from the root `Dockerfile` and verified on
both before any push (`others/scripts/publish-image.sh`). The plain `make` targets (`build`, `test`,
`image`, `up`, `blackbox-image`, …) are the Go ones; `docker-compose.yml` is the Go stack.

## Consequences

- One build, one CI, one set of commands. A contributor cloning `main` meets only the code that
  runs.
- The required checks change: `build` and `differential` no longer exist. `blackbox-go` stays and
  stays required.
- Rules whose only test was a JUnit test now cite a Go or black-box test that proves them, or are
  listed as gaps in [RULE-COVERAGE.md](../RULE-COVERAGE.md) with the reason.
- A Java-only behaviour pinned by a test is gone with the test (X9-TIME-013, MongoDB's TTL
  deletion). Tests that were strict expected failures on Java (X9-LOC-421) are ordinary tests.
- The golden sets cannot be re-exported from `main`'s code; `others/blackbox/golden/export.sh`
  runs against `materainc/x9-qrcode:java-final`. That is needed only if the test PKI must be
  regenerated.
- Anyone on 2.x follows [UPGRADING-TO-3.md](../../UPGRADING-TO-3.md); anyone who must stay on Java
  pins `java-final`, which no longer receives fixes.

## Alternatives rejected

- **Freeze the Java modules under `legacy/java/` (ADR-0022, Epic 17).** History without git's
  help, at the cost of every search, scan and review of `main`. Git already keeps every line, and a
  tag names the place.
- **Keep the Java build on `main` until a cutover is proven in production.** There is no Java
  production deployment to cut over (ADR-0038).
- **Keep the differential job, comparing Go with the `java-final` image on every pull request.**
  The comparison is finished; re-running it against an image that never changes proves nothing new
  and doubles the cost of every run. It can still be run by hand when a doubt needs it.
- **Delete the comparison's records (differential tooling, tolerated differences, golden export).**
  They are how equivalence was proved, and the reason some behaviours are what they are.

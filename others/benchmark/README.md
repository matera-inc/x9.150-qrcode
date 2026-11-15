# Benchmark harness: Java + MongoDB vs Go + PostgreSQL

One harness that sends the same demand to either build, so the comparison is the same test run
twice (Story 5.1, plan [§9](../../PLAN-GO-POSTGRES-REWRITE.md#9-benchmark-java--mongodb-vs-go--postgresql--benchmarkmd)).
The scripts never ask which build is answering. The target is a URL, and the build is chosen by
which manifests are deployed.

This is not a database benchmark. It compares two complete builds of one product, deployed the way
an operator would run them. Signing and verification dominate the cost of a payment.

| Scenario | What runs | Script |
|---|---|---|
| **S1** | Create only, with the README's profile: ramp to 500 VUs, sustain 3 minutes, think time 1–3 s | [`k6/s1-create.js`](k6/s1-create.js) |
| **S2** | The full payment cycle per iteration: create → signed payload fetch → pre-commit → post-commit (Solana, with a `transactionId`) → status-update `PAID`. Same profile | [`k6/s2-cycle.js`](k6/s2-cycle.js) |
| **S7** | Footprint: image size, cold start to `/actuator/health` UP, resting memory after 5 idle minutes | [`footprint.sh`](footprint.sh) |

Today only Java serves the API. The Go build answers `/actuator/health` (Epic 7), so **S7 runs
against both builds**. S1 and S2 run against Java only until `GO_SERVES_API=1` (Epics 9–10).
Until then, each Go turn at S1 or S2 still deploys PostgreSQL, the Liquibase schema and the Go
build, waits for them to become healthy, and records `durability.json` and `resources.json` beside
a `NOT-RUN.txt`. That way the Go manifests are exercised on every campaign.

S1 sends the README script's body (USD on FedNow) with two corrections. Today's API refuses
`editable: {payerCanEdit: false}` and a FedNow method without a `protectionType`, so S1 drops
`editable` and sends `protectionType: "plaintext"`. The README script itself still sends both and
gets a 400 for every request.

## Layout

```
others/benchmark/
├── README.md            this file
├── prepare.sh           test-CA payers PKCS#12 → PKCS#8 + x5c for k6; CRL; truststores → build/ (git-ignored)
├── build-images.sh      x9-qrcode:git-<sha>, x9-qrcode-go:git-<sha>, x9-qrcode-liquibase:git-<sha>; never pushed
├── collect.sh           runs a campaign: J-G-J-G-J-G, S1/S2/S7, results/<build>/<campaign>-r<n>/
├── footprint.sh         S7 for one build
├── lib.sh               shared by collect.sh and footprint.sh
├── k6/                  s1-create.js, s2-cycle.js, lib/jws.js (WebCrypto signer and verifier), lib/x9.js,
│                        selftest.js (the verifier's own refusal cases, no service needed)
└── k8s/
    ├── java-mongo/      MongoDB 7.0.45 single-node replica set + the Java build
    ├── go-postgres/     PostgreSQL 16 + the Liquibase schema Job + the Go build
    ├── k6/              the k6 Job
    └── overlays/small/  SMOKE ONLY: the same, scaled down for a 4 vCPU / 4 GiB laptop VM
```

## Fairness (plan §9.2), and where each rule is enforced

| Rule | Where |
|---|---|
| App **2 vCPU / 2 GiB**, database **2 vCPU / 2 GiB**, requests = limits | `k8s/*/app.yaml`, `mongo.yaml`, `postgres.yaml` |
| k6 is an in-cluster **Job on its own 2 vCPU**, talking to the ClusterIP Service | `k8s/k6/job.yaml` |
| Durability parity: MongoDB `{w: "majority", j: true}` (URI and cluster default; one node, so majority is its journal) vs PostgreSQL `synchronous_commit=on`, `fsync=on` | `mongo.yaml`, `java-mongo/app.yaml`, `postgres.yaml`; the database's own report of these settings is saved as `durability.json` for every run |
| Same volume size and storage class for both databases; **an empty volume each run** | `volumeClaimTemplates` (10 Gi, the cluster's default class); collect.sh deletes the namespace after every scenario |
| One system under test at a time | each scenario deploys, measures and tears down before the next |
| Same crypto: the shipped demo signing key (PS512, `JWK_SET`), the same test-CA trust | both `app.yaml`; Java reads the black-box JKS and Go reads the same certificates as PEM, because Go does not read JKS |
| Each build as shipped; the JVM's **2-minute warm-up is excluded** | `JAVA_WARMUP` (default: the `warmup` profile, 2 minutes); recorded in `warmup.json`; `GOMEMLIMIT` = 80% of the limit for Go |
| Test clock **off** | `X9_TESTING_CLOCKCONTROL=false` in both builds |
| Images by `git-<sha>` tag and OCI revision; k6 **v2.4.0** | `build-images.sh`; `images.json`, and the pods' image digests in `resources.json` |
| **3 runs per build, interleaved J-G-J-G-J-G** | `SEQUENCE` (default `J G J G J G`) |

## Running a campaign

### 1. Build the images

```bash
others/benchmark/build-images.sh            # java, go, liquibase; refuses a tree with uncommitted changes
```

Each image is tagged `git-<sha>` with the commit's full SHA as its OCI revision label. Nothing is
pushed to Docker Hub or any other registry.

### 2. Make them visible to the cluster ("Images")

Our pods use `imagePullPolicy: Never`, so a pod whose image the cluster's container runtime does not
already hold fails at once and never pulls. `IMAGE_LOADER` is a command that reads a `docker save`
tar on stdin and imports it into that runtime. collect.sh runs it for every image the campaign
needs, saved by its exact `git-<sha>` tag (it refuses `:latest`, which would move the cluster's own
`latest` tag):

| Cluster | `IMAGE_LOADER` |
|---|---|
| Colima with Kubernetes on the **docker** runtime (k3s uses the same Docker) | empty: nothing to load |
| Colima with Kubernetes on the **containerd** runtime | `colima ssh -- sudo nerdctl -n k8s.io load` |
| k3s on this machine | `sudo k3s ctr images import -` |
| k3s in a Docker container (the local smoke run below) | `docker exec -i <container> ctr images import -` |

`PRELOAD_PUBLIC_IMAGES=1` also pulls `mongo:7.0.45`, `postgres:16`, `python:3.13.9-slim` and
`grafana/k6:2.4.0` on this machine and loads them, so the cluster pulls nothing during a campaign.

### 3. Run

```bash
KUBE_CONTEXT=<your context> \
BENCH_VIRTUALISATION="<generic: e.g. Linux VM on a macOS hypervisor, k3s, 8 vCPU / 12 GiB>" \
others/benchmark/collect.sh
```

`KUBE_CONTEXT` is required and is never defaulted or written into results. The defaults:
`SEQUENCE="J G J G J G"`, `SCENARIOS="s1 s2 s7"`, `PROFILE=readme`, `JAVA_WARMUP=warmup`,
`GO_WARMUP=none`, `IDLE_SECONDS=300`, `GO_SERVES_API=0`. Use `SEQUENCE="J J J"` for the Java
baseline alone (Story 5.2). The rest are documented at the top of [`collect.sh`](collect.sh).

**The cluster needs room for all three at once:** app 2 vCPU / 2 GiB, database 2 vCPU / 2 GiB, k6
2 vCPU / 2 GiB, and two 50 m / 64 MiB sidecars. That is 6 vCPU and about 6.2 GiB of allocatable
capacity on one node, plus whatever the node's own system needs. The VM's size is recorded in
`environment.json` and must not change during a campaign.

### 4. What a run writes

```
results/<build>/<campaign>-r<n>/
├── environment.json      Kubernetes version; per node: architecture, kernel, OS image, runtime, CPU and
│                         memory capacity; host CPU model, logical CPUs, memory, OS/arch; BENCH_VIRTUALISATION
├── images.json           app (and Liquibase) image: tag, ID, OCI revision and version, size; the harness's
│                         own commit; profile, stages, overlay; position in the sequence
├── s1/ and s2/
│   ├── summary.json      the full k6 summary: every metric, p50/p90/p95/p99, per step for S2
│   ├── raw.ndjson.gz     k6's raw output, every sample
│   ├── k6.log, exit-code k6's console (99 = a threshold was crossed: recorded, not fatal)
│   ├── warmup/, warmup.json   the excluded warm-up's own k6 output, its stages and duration
│   ├── pod-stats.csv     CPU (millicores) and memory (working set) of every container, every 5 s
│   ├── durability.json   the database's own report of its write concern / synchronous_commit
│   ├── db-after.json     on-disk size, document or row counts, connections, after the run
│   ├── resources.json    every container's requests, limits, image and image digest
│   └── app-tail.log      the service's last 200 log lines
└── s7/
    ├── s7.json           image size (uncompressed, compressed), cold start ms, resting memory bytes
    └── idle-memory.csv   memory every 30 s while idle
```

## Method notes

**Payer signatures in k6.** k6 v2.4.0 signs with WebCrypto, the global `crypto.subtle`: no
extension, no remote module, no sidecar. k6 cannot read PKCS#12, so `prepare.sh` converts the
repository's **test-CA payers** (`x9-test-payer-ec.p12`, ES256, and `x9-test-payer.p12`, PS512)
to PKCS#8, with their chains for `x5c`. They are NON-PRODUCTION keys that are already public in
this repository. Virtual users alternate between the two payers (`PAYERS=ec,rsa`). The
certificate travels in `x5c`, so the service never fetches a payer certificate.

**Revocation is checked for real.** The payers' certificates name a CRL at
`http://127.0.0.1:18081/test-ca.crl`. A `crl` sidecar in the app's pod (a native sidecar, so
Kubernetes 1.29 or later) serves the test CA's CRL at that address. The JVM caches it, and no
request leaves the pod.

**Every signed answer is checked as a payer must check it** (`k6/lib/jws.js`, `verifyAnswer`): it
is a compact JWS; `crit` names `correlationId`, `iat`, `ttl` and `statusCode`; the signing
certificate is the payee's (the shipped demo certificate, pinned by `prepare.sh`); `x5t#S256` names it;
the PS512 or ES256 signature verifies with its key; `iat`/`ttl` hold; the `correlationId` is the
request's; and `statusCode` is 200 in the header and, for notification answers, in the body. A
failed check counts in `s2_errors{step,reason}` and `s2_signature_failures`, the cycle stops there,
and the `s2_signature_failures` threshold is `count<1`.

As shipped (`endpoint-type: JWK_SET`), the service names its key **by reference**. Its answers
carry `jku` (its JWK set URL) and `x5t#S256`, and no `x5c`. A real payer would resolve the `jku`
once and cache the certificate. The harness does the same thing ahead of time: S2's `setup()`
fetches the service's `/pub/.well-known/jwks` once and fails the run unless that set holds the
pinned certificate. After that, each answer is verified with the pinned certificate that its
`x5t#S256` names, and no answer costs a fetch. An answer that does carry `x5c` must have the
pinned certificate first.

`k6 run others/benchmark/k6/selftest.js` (after `prepare.sh`; no service needed) proves the
verifier refuses what it must. It signs answers as each test payer and checks that each case gets
its own code: an altered body or signature, another `correlationId`, another payee, expiry,
`statusCode` missing from `crit` or wrong, and a key given by `jku`.

k6 v2.4.0 verifies RSA-PSS (PS512) and ECDSA P-256 (ES256). One quirk: an RSA public key
imported as a **JWK** cannot verify ("given CryptoKey is not a public RSA key"). One imported as
**SPKI** can. So the verifier reads the `subjectPublicKeyInfo` out of the certificate's DER and
imports that.

**What S2's timings include.** Each step's trend (`s2_create`, `s2_payload`, `s2_pre_commit`,
`s2_post_commit`, `s2_status_update`) is the HTTP time alone. `s2_cycle` is the wall time of a
whole cycle, including k6's own signing and verification. Both builds pay that cost equally.

**The JVM warm-up.** Before each measured S1 or S2 run against Java, the same script runs for 2
minutes under the `warmup` profile (ramp to 100 VUs in 30 s, hold 90 s). Its output is kept in
`warmup/` and is not part of the measurement. Then the data it created is deleted
(`deleteMany` on `qrcodes`, `qrcode_history` and `payment_events`). The indexes the service built
at startup stay, and the service keeps running, so the measured run starts from empty collections
on a warm JVM. `warmup.json` records this.

**S7 runs on the host's container engine, not in the cluster.** Kubernetes stamps container
start times to the second, which is too coarse for a Go build that starts in well under one.
footprint.sh times `docker run` to the first `/actuator/health` UP in milliseconds, the same
method as `others/scripts/go-image-smoke.sh`. It applies the same cgroup limits as the manifests
(`--cpus 2 --memory 2g`) and runs only after the campaign's namespace is gone.

**Never recorded (S5.1-AC6).** The host's name, the kube context's name or server, node names, and
any IP address. The environment is described by CPU model, architecture and virtualisation layers.
k6 drops its `error` tag (which can carry an address) from raw samples, collect.sh redacts
addresses in logs, and `scrub_check` fails the campaign if an IPv4 address is left anywhere in its
results, compressed files included.

## A smoke run on a small local cluster

To check the harness end to end on a laptop, use `OVERLAY=small` and `PROFILE=smoke`. This is never
a measurement. It halves the limits (app 1 vCPU / 1 GiB, database 0.5 vCPU / 512 MiB, k6 1 vCPU)
and runs 5 VUs for under a minute. Its results say so in `environment.json` and `images.json`.

```bash
# a throwaway single-node k3s in Docker, if the machine has no local Kubernetes
docker run -d --privileged --name x9bench-k3s --tmpfs /run --tmpfs /var/run \
  -p 127.0.0.1:16443:6443 rancher/k3s:v1.33.5-k3s1 server \
  --disable=traefik --disable=servicelb --disable=metrics-server --tls-san localhost
docker exec x9bench-k3s cat /etc/rancher/k3s/k3s.yaml |
  sed -e 's#https://127.0.0.1:6443#https://localhost:16443#' -e 's/: default$/: x9bench-local/' > /tmp/x9bench.kubeconfig

KUBECONFIG=/tmp/x9bench.kubeconfig KUBE_CONTEXT=x9bench-local \
IMAGE_LOADER="docker exec -i x9bench-k3s ctr images import -" PRELOAD_PUBLIC_IMAGES=1 \
OVERLAY=small PROFILE=smoke JAVA_WARMUP=smoke IDLE_SECONDS=60 SEQUENCE="J G" \
RESULTS_DIR=/tmp/x9bench-results others/benchmark/collect.sh

docker rm -f x9bench-k3s
```

---

<sub>Copyright © 2026 Matera Systems, Inc. Licensed under the Matera Source License v1.0 (source-available; not open source) — see LICENSE.md at the repository root. Creating a Derivative Work from this document — by AI/ML generation or by manual re-implementation based on it — is governed by that license (see the "Derivative Work" definition and Annex A).</sub>

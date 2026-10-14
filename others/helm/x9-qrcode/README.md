# X9 QRCode — Helm Chart

Deploy the X9 QRCode backend to Kubernetes. It needs a reachable **MongoDB replica set** (for
multi-document transactions) and the signing **keystore/secrets** (see the repo `secrets/` folder
and [`secrets/README.md`](../../../secrets/README.md)).

> **MongoDB version:** this chart bundles no MongoDB — you bring your own, so the version is your
> choice and your risk. Note that **MongoDB 8.x refuses to start on Linux kernels 6.19 and newer**
> ([SERVER-121912](https://jira.mongodb.org/browse/SERVER-121912)); if your nodes are on a current
> kernel, 8.x will not come up. The compose files in this repository pin `mongo:7.0.45` for that
> reason, and the same constraint applies to whatever you point `mongodb.uri` at.

## Values

- `chart/values.yaml` — full configuration (all tunables).
- `chart/values-minimal.yaml` — a minimal starting point.

Copy one and adjust it for your environment: image, MongoDB connection, `x9.public-endpoints.host`
(see [`ENDPOINTS.md`](../../../ENDPOINTS.md)), secrets, resources, and replica count.

## Install / upgrade

Dry-run first:

```shell
helm upgrade --install x9-qrcode others/helm/x9-qrcode/chart \
  -n <namespace> --create-namespace \
  -f others/helm/x9-qrcode/chart/values.yaml --dry-run
```

Then apply (drop `--dry-run`):

```shell
helm upgrade --install x9-qrcode others/helm/x9-qrcode/chart \
  -n <namespace> --create-namespace \
  -f others/helm/x9-qrcode/chart/values.yaml
```

Replace `<namespace>` with your target namespace and point `-f` at your customized values file.

> Keep `replicaCount: 1` — see [HIGH-AVAILABILITY.md](../../../HIGH-AVAILABILITY.md) for the
> single-active-instance posture and how it relates to the license limits.

## Lint

```shell
helm lint others/helm/x9-qrcode/chart
```

---

<sub>Copyright © 2026 Matera Systems, Inc. Licensed under the Matera Source License v1.0 (source-available; not open source) — see LICENSE.md at the repository root. Creating a Derivative Work from this document — by AI/ML generation or by manual re-implementation based on it — is governed by that license (see the "Derivative Work" definition and Annex A).</sub>

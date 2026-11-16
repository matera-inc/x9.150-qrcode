#!/usr/bin/env bash
# Copyright © 2026 Matera Systems, Inc.
# Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
# Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
# based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
#
# Installs the chart with values-go.yaml on a throwaway kind cluster and checks it (Story 14.3):
# a PostgreSQL 16 in the cluster stands in for the bank's own; the DBA's one-time step is done by
# hand, with passwords; the Liquibase init container applies the changelog as the owner; the
# service starts as the application role, reading its password from the mounted Secret; health is
# UP and one create answers 201. CI runs it on a fresh kind cluster (deploy.yml, `helm go on kind`).
#
#   others/scripts/helm-go-kind.sh <image>     # the Go image, already loaded into the kind cluster
#
# It refuses to run unless kubectl's current context is a kind cluster: it creates and deletes a
# namespace, and must never do that anywhere else. Every password here is a throwaway for CI.

set -euo pipefail
cd "$(dirname "$0")/../.."

IMAGE="${1:?usage: helm-go-kind.sh <image loaded into kind>}"
ctx=$(kubectl config current-context)
case "$ctx" in kind-*) ;; *) echo "refusing: the current context '$ctx' is not a kind cluster"; exit 2 ;; esac

NS=x9-helm-go
CHART=others/helm/x9-qrcode/chart
k() { kubectl -n "$NS" "$@"; }
fail() {
  echo "FAIL  $*"
  k get all || true
  k describe pods || true
  for p in $(k get pods -o name); do
    k logs "$p" --all-containers --tail 60 || true
  done
  exit 1
}

kubectl create namespace "$NS"

echo "### PostgreSQL 16 (the bank's database, here a throwaway in the cluster)"
k apply -f - <<'EOF'
apiVersion: apps/v1
kind: Deployment
metadata: {name: postgres}
spec:
  replicas: 1
  selector: {matchLabels: {app: postgres}}
  template:
    metadata: {labels: {app: postgres}}
    spec:
      containers:
        - name: postgres
          image: postgres:16.14
          env:
            - {name: POSTGRES_PASSWORD, value: ci-only-superuser}
          ports: [{containerPort: 5432}]
          readinessProbe:
            exec: {command: ["pg_isready", "-h", "127.0.0.1", "-U", "postgres"]}
            periodSeconds: 2
---
apiVersion: v1
kind: Service
metadata: {name: postgres}
spec:
  selector: {app: postgres}
  ports: [{port: 5432}]
EOF
k rollout status deploy/postgres --timeout=180s || fail "PostgreSQL did not start"

echo "### the DBA's one-time step (db/README.md): owner and schema, and the application role"
k exec -i deploy/postgres -- psql -v ON_ERROR_STOP=1 -U postgres -d postgres <<'EOF'
CREATE ROLE x9_qrcode_owner LOGIN CREATEROLE PASSWORD 'ci-only-owner';
CREATE SCHEMA x9_qrcode AUTHORIZATION x9_qrcode_owner;
CREATE ROLE x9_qrcode_app LOGIN PASSWORD 'ci-only-app';
ALTER ROLE x9_qrcode_app SET search_path = x9_qrcode;
EOF
k create secret generic x9-qrcode-go \
  --from-literal=database-password=ci-only-app --from-literal=liquibase-password=ci-only-owner

echo "### helm install, values-go.yaml"
helm install x9 "$CHART" -n "$NS" -f "$CHART/values-go.yaml" \
  --set image.repository="${IMAGE%:*}" --set image.tag="${IMAGE##*:}" --set image.pullPolicy=Never \
  --set imagePullSecrets=null \
  --set go.database.host=postgres --set go.database.name=postgres --set go.database.sslmode=disable \
  --wait --timeout 5m 2> >(grep -v 'found symbolic link in path' >&2) || fail "the release did not become ready"

k logs deploy/x9-qrcode -c liquibase | grep -q "Update has been successful" ||
  fail "the Liquibase init container did not apply the changelog"
echo "ok  Liquibase applied the changelog, as the owner"

echo "### health and one create, through the Service"
k port-forward svc/x9-qrcode 18080:8080 >/dev/null 2>&1 &
pf=$!
trap 'kill $pf 2>/dev/null || true' EXIT
for _ in $(seq 1 30); do curl -fsS -o /dev/null http://127.0.0.1:18080/actuator/health && break; sleep 1; done
health=$(curl -fsS http://127.0.0.1:18080/actuator/health) || fail "/actuator/health"
case "$health" in *'"status":"UP"'*) ;; *) fail "health is $health" ;; esac
echo "ok  /actuator/health: $health"
code=$(curl -sS -o /dev/null -w '%{http_code}' -H 'Content-Type: application/json' \
  --data-binary @playground/requests/qr-burger-createqr.json http://127.0.0.1:18080/api/v1/payment-request)
[ "$code" = 201 ] || fail "create answered $code, want 201"
echo "ok  create answered 201"

kubectl delete namespace "$NS" --wait=false >/dev/null
echo "PASS  the chart deploys the Go build: Liquibase, then the service as the application role"

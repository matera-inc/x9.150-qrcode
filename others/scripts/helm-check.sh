#!/usr/bin/env bash
# Copyright © 2026 Matera Systems, Inc.
# Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
# Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
# based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
#
# The Helm chart (Story 14.3, S14.3-AC2): `helm lint` and `helm template` with values-minimal.yaml and
# with values.yaml (the defaults, plus the three required values), then checks of the rendering. The
# chart deploys the Go build only since the Java build was retired at 3.0.0 (ADR-0039):
#   - no SPRING_*, MONGO* or JASYPT_* anywhere in it (the service refuses them at start);
#   - one replica, strategy Recreate, no HorizontalPodAutoscaler, no Secret rendered by the chart;
#   - Liquibase 4.33.0 as an init container; GOMEMLIMIT set; probes on the health paths;
#   - every password comes from a Secret (valueFrom or a mounted file), never a value;
# and that the chart refuses what the service cannot run (two replicas, an HPA, a Java variable,
# a 2.x `runtime: java`, a missing required value).
# Renders are kept in <out> (default go-ci-out/helm) for a look afterwards.
#
#   others/scripts/helm-check.sh [out-dir]

set -euo pipefail
cd "$(dirname "$0")/../.."

CHART=others/helm/x9-qrcode/chart
OUT="${1:-go-ci-out/helm}"
mkdir -p "$OUT"
fails=0
ok() { echo "ok    $*"; }
bad() { echo "FAIL  $*"; fails=$((fails + 1)); }
# Helm says, at INFO, that it followed files/changelog to db/changelog; that is expected.
helm_() { helm "$@" 2> >(grep -v 'found symbolic link in path' >&2); }

# values.yaml leaves the public host and the database empty: they are required.
REQUIRED=(--set application.x9.public-endpoints.host=x9.example.com
          --set go.database.host=postgres.example.com --set go.database.name=x9)

echo "### lint"
helm_ lint "$CHART" -f "$CHART/values-minimal.yaml"
helm_ lint "$CHART" "${REQUIRED[@]}"

echo "### template"
helm_ template x9 "$CHART" -f "$CHART/values-minimal.yaml" > "$OUT/minimal.yaml"
helm_ template x9 "$CHART" "${REQUIRED[@]}" > "$OUT/default.yaml"
ok "rendered $OUT/minimal.yaml and $OUT/default.yaml"

for go in "$OUT/minimal.yaml" "$OUT/default.yaml"; do
echo "### the rendering $go"
if refused=$(grep -nE 'SPRING_|MONGO|JASYPT_' "$go"); then
  bad "a refused variable is rendered:"; printf '%s\n' "$refused"
else
  ok "no SPRING_*, MONGO* or JASYPT_*"
fi
grep -q '^  replicas: 1$' "$go" && ok "one replica" || bad "replicas is not 1"
grep -q '^    type: Recreate$' "$go" && ok "strategy Recreate" || bad "strategy is not Recreate"
grep -q '^kind: HorizontalPodAutoscaler' "$go" && bad "an HPA is rendered" || ok "no HPA"
grep -q '^kind: Secret' "$go" && bad "the chart renders a Secret" || ok "no Secret rendered (go.existingSecret)"
grep -q 'image: "liquibase/liquibase:4.33.0"' "$go" && grep -q -- '- name: liquibase' "$go" &&
  ok "Liquibase 4.33.0 init container" || bad "no Liquibase 4.33.0 init container"
# values.yaml: 256Mi, 80% -> 214748364 bytes.
grep -A1 -- '- name: GOMEMLIMIT' "$go" | grep -q 'value: "214748364"' &&
  ok "GOMEMLIMIT is 80% of the memory limit" || bad "GOMEMLIMIT is not 80% of 256Mi"
for p in /actuator/health/liveness /actuator/health/readiness; do
  grep -q "path: $p" "$go" && ok "probe on $p" || bad "no probe on $p"
done
grep -q 'port: 12345' "$go" && bad "a probe or port uses the Java management port" || ok "probes on the API port"
grep -q 'name: DATABASE_PASSWORD_FILE' "$go" && ok "database password from a mounted Secret" ||
  bad "no DATABASE_PASSWORD_FILE"
# Every *PASSWORD* variable (but the _FILE path) is followed by valueFrom, never by a value.
inline=$(awk '/- name: [A-Z_]*PASSWORD[A-Z_]*$/ && $3 !~ /_FILE$/ { n = $3; getline; if ($1 != "valueFrom:") print n }' "$go")
[ -z "$inline" ] && ok "every password from a Secret" || bad "inline password: $inline"
# The changelog the init container applies is db/changelog, file for file.
for f in db/changelog/db.changelog-master.yaml db/changelog/changes/*.yaml; do
  key=$(printf '%s' "${f#db/changelog/}" | sed 's|/|__|g')
  grep -q "^  $key: |-" "$go" || bad "the changelog ConfigMap lacks $f"
done
ok "the changelog ConfigMap carries every file of db/changelog"
done

echo "### the chart refuses what the service cannot run"
refuses() {
  local why="$1"; shift
  if helm_ template x9 "$CHART" -f "$CHART/values-minimal.yaml" "$@" >/dev/null 2>&1; then
    bad "renders with $why"
  else
    ok "refuses $why"
  fi
}
refuses "two replicas" --set replicaCount=2
refuses "an HPA" --set autoscaling.enabled=true
refuses "a SPRING_ variable" --set 'customerEnvs[0].name=SPRING_PROFILES_ACTIVE' --set 'customerEnvs[0].value=x'
refuses "a MONGO variable" --set 'go.env[0].name=MONGODB_URI' --set 'go.env[0].value=x'
refuses "a JASYPT_ variable" --set 'go.env[0].name=JASYPT_ENCRYPTOR_PASSWORD' --set 'go.env[0].value=x'
refuses "a password as a value" --set 'go.env[0].name=DATABASE_PASSWORD' --set 'go.env[0].value=x'
refuses "no Secret named" --set go.existingSecret=
refuses "a grace period below the shutdown delay plus the drain" --set deploymentStrategy.terminationGracePeriodSeconds=20
refuses "the 2.x runtime: java" --set runtime=java
refuses "no public host" --set application.x9.public-endpoints.host=
refuses "no database host" --set go.database.host=
if helm_ template x9 "$CHART" -f "$CHART/values-minimal.yaml" --set runtime=go >/dev/null 2>&1; then
  ok "accepts a 2.x runtime: go, which changes nothing"
else
  bad "refuses a 2.x runtime: go"
fi

if [ "$fails" -gt 0 ]; then
  echo "FAIL  $fails check(s)"
  exit 1
fi
echo "PASS  the chart renders the service, and only the service's variables"

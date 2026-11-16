#!/usr/bin/env bash
# Copyright © 2026 Matera Systems, Inc.
# Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
# Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
# based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
#
# The Helm chart, for both builds (Story 14.3, S14.3-AC2): `helm lint` and `helm template` with the
# Java values (the default, values-minimal.yaml) and the Go values (values-go.yaml), then checks of
# the Go rendering:
#   - no SPRING_*, MONGO* or JASYPT_* anywhere in it (the Go build refuses them at start);
#   - one replica, strategy Recreate, no HorizontalPodAutoscaler, no Secret rendered by the chart;
#   - Liquibase 4.33.0 as an init container; GOMEMLIMIT set; probes on the health paths;
#   - every password comes from a Secret (valueFrom or a mounted file), never a value;
# and that the chart refuses what the Go build cannot run (two replicas, an HPA, a Java variable).
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

echo "### lint"
helm_ lint "$CHART" -f "$CHART/values-minimal.yaml"
helm_ lint "$CHART" -f "$CHART/values-go.yaml"

echo "### template"
helm_ template x9 "$CHART" -f "$CHART/values-minimal.yaml" > "$OUT/java.yaml"
helm_ template x9 "$CHART" -f "$CHART/values-go.yaml" > "$OUT/go.yaml"
ok "rendered $OUT/java.yaml and $OUT/go.yaml"

echo "### the Java rendering (the default) is the Java build's"
grep -q 'name: SPRING_PROFILES_ACTIVE' "$OUT/java.yaml" && ok "Java: SPRING_PROFILES_ACTIVE" || bad "Java: no SPRING_PROFILES_ACTIVE"
grep -q 'type: RollingUpdate' "$OUT/java.yaml" && ok "Java: RollingUpdate, as before" || bad "Java: strategy changed"
grep -q 'liquibase' "$OUT/java.yaml" && bad "Java: renders Liquibase" || ok "Java: no Liquibase"

echo "### the Go rendering"
go="$OUT/go.yaml"
if refused=$(grep -nE 'SPRING_|MONGO|JASYPT_' "$go"); then
  bad "Go: a refused variable is rendered:"; printf '%s\n' "$refused"
else
  ok "Go: no SPRING_*, MONGO* or JASYPT_*"
fi
grep -q '^  replicas: 1$' "$go" && ok "Go: one replica" || bad "Go: replicas is not 1"
grep -q '^    type: Recreate$' "$go" && ok "Go: strategy Recreate" || bad "Go: strategy is not Recreate"
grep -q '^kind: HorizontalPodAutoscaler' "$go" && bad "Go: an HPA is rendered" || ok "Go: no HPA"
grep -q '^kind: Secret' "$go" && bad "Go: the chart renders a Secret" || ok "Go: no Secret rendered (go.existingSecret)"
grep -q 'image: "liquibase/liquibase:4.33.0"' "$go" && grep -q -- '- name: liquibase' "$go" &&
  ok "Go: Liquibase 4.33.0 init container" || bad "Go: no Liquibase 4.33.0 init container"
# values-go.yaml: 256Mi, 80% -> 214748364 bytes.
grep -A1 -- '- name: GOMEMLIMIT' "$go" | grep -q 'value: "214748364"' &&
  ok "Go: GOMEMLIMIT is 80% of the memory limit" || bad "Go: GOMEMLIMIT is not 80% of 256Mi"
for p in /actuator/health/liveness /actuator/health/readiness; do
  grep -q "path: $p" "$go" && ok "Go: probe on $p" || bad "Go: no probe on $p"
done
grep -q 'port: 12345' "$go" && bad "Go: a probe or port uses the Java management port" || ok "Go: probes on the API port"
grep -q 'name: DATABASE_PASSWORD_FILE' "$go" && ok "Go: database password from a mounted Secret" ||
  bad "Go: no DATABASE_PASSWORD_FILE"
# Every *PASSWORD* variable (but the _FILE path) is followed by valueFrom, never by a value.
inline=$(awk '/- name: [A-Z_]*PASSWORD[A-Z_]*$/ && $3 !~ /_FILE$/ { n = $3; getline; if ($1 != "valueFrom:") print n }' "$go")
[ -z "$inline" ] && ok "Go: every password from a Secret" || bad "Go: inline password: $inline"
# The changelog the init container applies is db/changelog, file for file.
for f in db/changelog/db.changelog-master.yaml db/changelog/changes/*.yaml; do
  key=$(printf '%s' "${f#db/changelog/}" | sed 's|/|__|g')
  grep -q "^  $key: |-" "$go" || bad "Go: the changelog ConfigMap lacks $f"
done
ok "Go: the changelog ConfigMap carries every file of db/changelog"

echo "### the chart refuses what the Go build cannot run"
refuses() {
  local why="$1"; shift
  if helm_ template x9 "$CHART" -f "$CHART/values-go.yaml" "$@" >/dev/null 2>&1; then
    bad "Go: renders with $why"
  else
    ok "Go: refuses $why"
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

if [ "$fails" -gt 0 ]; then
  echo "FAIL  $fails check(s)"
  exit 1
fi
echo "PASS  the chart renders both builds; the Go rendering is the Go build's only"

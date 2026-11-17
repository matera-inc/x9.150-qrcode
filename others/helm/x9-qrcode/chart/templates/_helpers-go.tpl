{{/*
The service's own helpers: GOMEMLIMIT, the refused variables, and the checks run before anything
renders. The file keeps its 2.x name; since 3.0.0 there is no other build (ADR-0039).
*/}}

{{/*
GOMEMLIMIT (ADR-0031): go.memLimitPercent (default 80) of resources.limits.memory, in bytes, so the
Go runtime collects harder before the kernel's OOM killer acts. go.gomemlimit, when set, is used as
it is. The limit must be a whole number with an optional unit: "1024Mi", "1Gi", "536870912".
*/}}
{{- define "x9-qrcode.goMemLimit" -}}
{{- if .Values.go.gomemlimit -}}
{{- .Values.go.gomemlimit -}}
{{- else -}}
{{- $limit := toString .Values.resources.limits.memory -}}
{{- if not (regexMatch "^[0-9]+(Ki|Mi|Gi|Ti|K|M|G|T)?$" $limit) -}}
{{- fail (printf "resources.limits.memory '%s' cannot be turned into GOMEMLIMIT: use a whole number with Ki, Mi, Gi, Ti, K, M, G or T, or set go.gomemlimit" $limit) -}}
{{- end -}}
{{- $num := regexFind "^[0-9]+" $limit | int64 -}}
{{- $unit := regexReplaceAll "^[0-9]+" $limit "" -}}
{{- $units := dict "none" 1 "Ki" 1024 "Mi" 1048576 "Gi" 1073741824 "Ti" 1099511627776 "K" 1000 "M" 1000000 "G" 1000000000 "T" 1000000000000 -}}
{{- $mult := get $units (default "none" $unit) | int64 -}}
{{- $pct := .Values.go.memLimitPercent | default 80 | int64 -}}
{{- if or (lt $pct 1) (gt $pct 100) -}}
{{- fail (printf "go.memLimitPercent must be between 1 and 100, not %d" $pct) -}}
{{- end -}}
{{- div (mul $num $mult $pct) 100 -}}
{{- end -}}
{{- end -}}

{{/*
The Go build refuses SPRING_*, MONGO* and JASYPT_* variables at start
(docs/CONFIGURATION-FROM-JAVA.md), so the chart refuses to render one; and a password is never a
plain value. Input: a list of {name, value | valueFrom}.
*/}}
{{- define "x9-qrcode.refuseJavaEnv" -}}
{{- range . -}}
{{- if regexMatch "^(SPRING_|MONGO|JASYPT_)" (toString .name) -}}
{{- fail (printf "%s is a Java build variable: the Go build refuses SPRING_*, MONGO* and JASYPT_* at start. docs/CONFIGURATION-FROM-JAVA.md gives its Go name" .name) -}}
{{- end -}}
{{- if and (regexMatch "PASSWORD" (toString .name)) (not (regexMatch "_FILE$" (toString .name))) (hasKey . "value") -}}
{{- fail (printf "%s is given as a value: a password comes from a Secret (valueFrom.secretKeyRef, or go.existingSecret), never from the chart's values" .name) -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{/*
What the Go build needs from its values, checked before anything renders.
*/}}
{{- define "x9-qrcode.validateGo" -}}
{{- if and (hasKey .Values "runtime") (ne (toString .Values.runtime) "go") -}}
{{- fail (printf "runtime: %s is a 2.x value: the Java build was retired at 3.0.0 (ADR-0039) and this chart deploys only the Go build. Remove runtime from your values (UPGRADING-TO-3.md); the 2.x chart is in git history under the tag java-final" (toString .Values.runtime)) -}}
{{- end -}}
{{- if ne (int .Values.replicaCount) 1 -}}
{{- fail "replicaCount must be 1 : X9.150 runs one active instance (ADR-0031)" -}}
{{- end -}}
{{- if .Values.autoscaling.enabled -}}
{{- fail "autoscaling.enabled must be false : X9.150 runs one active instance, so there is no HPA (ADR-0031)" -}}
{{- end -}}
{{- $delay := int .Values.go.shutdownDelaySeconds -}}
{{- if le (int .Values.deploymentStrategy.terminationGracePeriodSeconds) (add $delay 20) -}}
{{- fail (printf "deploymentStrategy.terminationGracePeriodSeconds must exceed go.shutdownDelaySeconds (%d) plus the 20 s drain (ADR-0031)" $delay) -}}
{{- end -}}
{{- if not .Values.go.existingSecret -}}
{{- fail "go.existingSecret is required: the Secret holding the database password (and the owner's, for Liquibase). The chart never takes a password as a value" -}}
{{- end -}}
{{- include "x9-qrcode.refuseJavaEnv" (default list .Values.go.env) -}}
{{- include "x9-qrcode.refuseJavaEnv" (default list .Values.customerEnvs) -}}
{{- end -}}

{{/*
The JDBC URL Liquibase connects with: the same database, TLS mode and CA as the service.
*/}}
{{- define "x9-qrcode.liquibaseUrl" -}}
{{- $db := .Values.go.database -}}
{{- $url := printf "jdbc:postgresql://%s:%d/%s?sslmode=%s" (required "go.database.host is required" $db.host) (int $db.port) (required "go.database.name is required" $db.name) (required "go.database.sslmode is required" $db.sslmode) -}}
{{- if $db.caSecret -}}
{{- $url = printf "%s&sslrootcert=/etc/x9/database-ca/%s" $url $db.caSecretKey -}}
{{- end -}}
{{- $url -}}
{{- end -}}

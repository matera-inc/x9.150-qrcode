{{/* vim: set filetype=mustache: */}}
{{/*
Expand the name of the chart.
*/}}
{{- define "x9-qrcode.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
Create a default fully qualified app name.
We truncate at 63 chars because some Kubernetes name fields are limited to this (by the DNS naming spec).
If release name contains chart name it will be used as a full name.
*/}}
{{- define "x9-qrcode.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- $name := default .Chart.Name .Values.nameOverride -}}
{{- if contains $name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{/*
Create chart name and version as used by the chart label.
*/}}
{{- define "x9-qrcode.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
Common labels
*/}}
{{- define "x9-qrcode.labels" -}}
helm.sh/chart: {{ include "x9-qrcode.chart" . }}
{{ include "x9-qrcode.selectorLabels" . }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
{{- end -}}

{{/*
Selector labels
*/}}
{{- define "x9-qrcode.selectorLabels" -}}
app.kubernetes.io/product: {{ include "x9-qrcode.name" . }}
app.kubernetes.io/application: {{ include "x9-qrcode.fullname" . }}

{{- end -}}

{{/*
Create the name of the service account to use
*/}}
{{- define "x9-qrcode.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
    {{ default (include "x9-qrcode.fullname" .) .Values.serviceAccount.name }}
{{- else -}}
    {{ default "default" .Values.serviceAccount.name }}
{{- end -}}
{{- end -}}

{{- define "x9-qrcode.image" -}}
{{- $tag := .Values.image.tag | default .Chart.AppVersion -}}
{{- printf "%s:%s" .Values.image.repository $tag -}}
{{- end -}}

{{/*
Validate resources.strategy is valid ("critical" or "optimized")
*/}}
{{- define "x9-qrcode.validateResourcesStrategy" -}}
{{- $strategy := .Values.resources.strategy | default "critical" }}
{{- if not (has $strategy (list "critical" "optimized")) }}
{{- fail (printf "resources.strategy must be 'critical' or 'optimized', not '%s'" $strategy) }}
{{- end }}
{{- end }}

{{/*
Validate resources configuration based on strategy
*/}}
{{- define "x9-qrcode.validateResourcesConfig" -}}
{{- $strategy := .Values.resources.strategy | default "critical" }}

{{- /* Validate memory limit always required */ -}}
{{- if not .Values.resources.limits.memory }}
{{- fail "resources.limits.memory is required." }}
{{- end }}

{{- /* If critical strategy */ -}}
{{- if eq $strategy "critical" }}
  {{- /* CPU limit is required in critical */ -}}
  {{- if not .Values.resources.limits.cpu }}
  {{- fail "resources.limits.cpu is required when resources.strategy is 'critical'." }}
  {{- end }}

  {{- /* requests not allowed in critical */ -}}
  {{- if .Values.resources.requests }}
  {{- fail "resources.requests must not be set when resources.strategy is 'critical': requests equal limits (Guaranteed QoS)." }}
  {{- end }}
{{- end }}

{{- end }}

{{/*
Calculate a percentage of a Kubernetes resource value with rounding to nearest integer.
Input dict: value (string e.g. "128Mi", "100m"), percentage (int e.g. 90)
Returns the formatted string with the same unit (e.g. "115Mi", "70m").
Formula: round(numeric * percentage / 100) using integer arithmetic:
  result = (numeric * percentage + 50) / 100
*/}}
{{- define "x9-qrcode.calculatePercentageRequest" -}}
{{- $value := .value }}
{{- $percentage := .percentage | int }}
{{- $numStr := regexReplaceAll "[^0-9]" $value "" }}
{{- $unit := regexReplaceAll "[0-9]" $value "" }}
{{- $num := $numStr | int }}
{{- $result := div (add (mul $num $percentage) 50) 100 }}
{{- printf "%d%s" $result $unit }}
{{- end }}

{{/*
Calculate resources block (limits + requests) based on strategy
Handles:
- critical: requests = limits (Guaranteed QoS)
- optimized: manual override or percentage-based calculation
*/}}
{{- define "x9-qrcode.calculateResourcesBlock" -}}
{{- include "x9-qrcode.validateResourcesStrategy" . -}}
{{- include "x9-qrcode.validateResourcesConfig" . -}}

{{- $strategy := .Values.resources.strategy | default "critical" -}}
{{- $limits := .Values.resources.limits -}}
{{- $requests := .Values.resources.requests -}}

resources:
  limits:
    {{- if $limits.memory }}
    memory: {{ $limits.memory | quote }}
    {{- end }}
    {{- if $limits.cpu }}
    cpu: {{ $limits.cpu | quote }}
    {{- end }}
  requests:
    {{- if eq $strategy "critical" }}
    {{- if $limits.memory }}
    memory: {{ $limits.memory | quote }}
    {{- end }}
    {{- if $limits.cpu }}
    cpu: {{ $limits.cpu | quote }}
    {{- end }}
    {{- else if eq $strategy "optimized" }}
    {{- $percentages := .Values.resources.requestsPercentages }}
    {{- if and $requests $requests.memory }}
    memory: {{ $requests.memory | quote }}
    {{- else if $limits.memory }}
    memory: {{ include "x9-qrcode.calculatePercentageRequest" (dict "value" $limits.memory "percentage" $percentages.memory) | quote }}
    {{- end }}
    {{- if and $requests $requests.cpu }}
    cpu: {{ $requests.cpu | quote }}
    {{- else if $limits.cpu }}
    cpu: {{ include "x9-qrcode.calculatePercentageRequest" (dict "value" $limits.cpu "percentage" $percentages.cpu) | quote }}
    {{- end }}
    {{- end }}
{{- end }}

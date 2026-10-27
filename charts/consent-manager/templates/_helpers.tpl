{{/* Chart name, truncated to the 63-character DNS label limit. */}}
{{- define "consent-manager.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/* Fully qualified app name; the release name alone when it already contains the chart name. */}}
{{- define "consent-manager.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{/* Chart name and version, as used by the helm.sh/chart label. */}}
{{- define "consent-manager.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/* Common labels. */}}
{{- define "consent-manager.labels" -}}
helm.sh/chart: {{ include "consent-manager.chart" . }}
{{ include "consent-manager.selectorLabels" . }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{/* Selector labels; immutable on a Deployment, so never add to them. */}}
{{- define "consent-manager.selectorLabels" -}}
app.kubernetes.io/name: {{ include "consent-manager.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/* Secret holding the credentials: the operator's existingSecret, else the chart's own. */}}
{{- define "consent-manager.secretName" -}}
{{- default (include "consent-manager.fullname" .) .Values.secrets.existingSecret }}
{{- end }}

{{/* Out-of-band Secret holding the receipt signing keys. */}}
{{- define "consent-manager.signingKeysSecretName" -}}
{{- default (printf "%s-signing-keys" .Release.Name) .Values.signingKeys.secretName }}
{{- end }}

{{/* Cluster-internal Service for health and metrics; Step 5's ServiceMonitor selects it too. */}}
{{- define "consent-manager.managementServiceName" -}}
{{- printf "%s-management" (include "consent-manager.fullname" .) | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/* Secret keys every pod requires, space-separated; read with splitList. */}}
{{- define "consent-manager.mandatorySecretKeys" -}}
DB_USER DB_PASSWORD LOG_PSEUDONYM_KEY ERASURE_VERIFICATION_SECRET
{{- end }}

{{/* Secret keys a pod may lack, space-separated; read with splitList. */}}
{{- define "consent-manager.optionalSecretKeys" -}}
SMTP_USER SMTP_PASSWORD
{{- end }}

{{/* ConfigMap holding identity-providers.yml for the additional providers. */}}
{{- define "consent-manager.identityProvidersConfigMapName" -}}
{{- printf "%s-identity-providers" (include "consent-manager.fullname" .) | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/* Directory the additional-provider file is mounted into. */}}
{{- define "consent-manager.identityProvidersDir" -}}
/etc/consent-manager/config
{{- end }}

{{/* Data key and file name of the additional-provider file. */}}
{{- define "consent-manager.identityProvidersFile" -}}
identity-providers.yml
{{- end }}

{{/* Directory the signing-key Secret is mounted into. */}}
{{- define "consent-manager.signingKeysDir" -}}
/etc/consent-manager/keys
{{- end }}

{{/*
Fails rendering when a provider entry declares a key this chart does not map; the application
would otherwise never see it. Takes a dict with "name" and "entry".
*/}}
{{- define "consent-manager.validateProvider" -}}
{{- $path := printf "identityProviders.%s" .name }}
{{- $settingKeys := list "issuer" "discoveryUrl" "audience" "allowInsecureTransport" "clockSkew" "claims" "roleMapping" }}
{{- range $key, $_ := .entry }}
{{- if not (has $key $settingKeys) }}
{{- fail (printf "%s declares unsupported key '%s'; supported: %s" $path $key (join ", " $settingKeys)) }}
{{- end }}
{{- end }}
{{- $claimKeys := list "userIdentifier" "participantIdentifier" "roles" }}
{{- range $key, $_ := (.entry.claims | default dict) }}
{{- if not (has $key $claimKeys) }}
{{- fail (printf "%s.claims declares unsupported key '%s'; supported: %s" $path $key (join ", " $claimKeys)) }}
{{- end }}
{{- end }}
{{- $roleKeys := list "user" "participant" "catalog" }}
{{- range $key, $_ := (.entry.roleMapping | default dict) }}
{{- if not (has $key $roleKeys) }}
{{- fail (printf "%s.roleMapping declares unsupported role '%s'; supported: %s" $path $key (join ", " $roleKeys)) }}
{{- end }}
{{- end }}
{{- end }}

{{/* Claim and role-mapping defaults for an additional provider; application.yml's for primary. */}}
{{- define "consent-manager.additionalProviderDefaults" -}}
claims:
  userIdentifier: [sub]
  participantIdentifier: [participant_id]
  roles: [realm_access, roles]
roleMapping:
  user: consent-user
  participant: consent-participant
  catalog: consent-catalog
{{- end }}

{{/* Quoted env value; a number renders as an integer, never in float notation such as 1e+06. */}}
{{- define "consent-manager.envValue" -}}
{{- if and (kindIs "float64" .) (eq (float64 (int64 .)) .) }}
{{- int64 . | toString | quote }}
{{- else }}
{{- toString . | quote }}
{{- end }}
{{- end }}

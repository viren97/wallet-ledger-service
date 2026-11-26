{{- define "tms.fullname" -}}
{{- printf "%s" .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "tms.labels" -}}
app.kubernetes.io/name: {{ .Chart.Name }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end -}}

{{- define "tms.selectorLabels" -}}
app.kubernetes.io/name: {{ .Chart.Name }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "tms.secretName" -}}
{{- if .Values.externalSecret.enabled -}}
{{ include "tms.fullname" . }}-secrets
{{- else -}}
{{ required "set externalSecret.enabled=true or existingSecret" .Values.existingSecret }}
{{- end -}}
{{- end -}}

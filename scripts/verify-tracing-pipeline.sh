#!/usr/bin/env bash
set -euo pipefail

evidence_dir="target/tracing-it"
mkdir -p "$evidence_dir"

# Keep verification volumes isolated from a developer's normal Compose project.
export COMPOSE_PROJECT_NAME="opspilot-tracing-it-${GITHUB_RUN_ID:-local-$$}"
export OTEL_TRACING_ENABLED=true
export OTEL_TRACING_SAMPLING_PROBABILITY=1.0
export OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=http://otel-collector:4318/v1/traces

compose=(docker compose --profile tracing)

collect_logs() {
  "${compose[@]}" logs --no-color > "$evidence_dir/compose.log" 2>&1 || true
}

cleanup() {
  collect_logs
  "${compose[@]}" down --volumes --remove-orphans >/dev/null 2>&1 || true
}
trap cleanup EXIT

wait_http() {
  local url="$1"
  local description="$2"
  for _ in {1..60}; do
    if curl --fail --silent --show-error "$url" >/dev/null 2>&1; then
      return 0
    fi
    sleep 2
  done
  echo "Timed out waiting for $description at $url" >&2
  return 1
}

"${compose[@]}" up --build --detach mysql tempo-init tempo otel-collector grafana opspilot
wait_http http://localhost:3200/ready "Tempo"
wait_http http://localhost:13133/ "OpenTelemetry Collector"
wait_http http://localhost:3000/api/health "Grafana"
wait_http http://localhost:9900/actuator/health "OpsPilot"

curl --fail --silent --show-error \
  http://localhost:3000/api/datasources/uid/tempo/health \
  > "$evidence_dir/grafana-tempo-health.json"
jq -e '.status == "OK"' "$evidence_dir/grafana-tempo-health.json" >/dev/null

nonce="$(date +%s)-$RANDOM"
sentinel="trace-private-${nonce}"
alert_payload="$(jq -n \
  --arg externalEventId "otel-${nonce}" \
  --arg title "${sentinel}-title" \
  --arg description "${sentinel}-description" \
  --arg label "${sentinel}-label" \
  '{source:"otel-pipeline-it", externalEventId:$externalEventId, resourceCode:"APP-AUTH", severity:"P3", status:"FIRING", title:$title, description:$description, labels:{private:$label}}')"

curl --fail --silent --show-error \
  -H 'Content-Type: application/json' \
  -d "$alert_payload" \
  http://localhost:9900/api/v1/alerts/intake > "$evidence_dir/alert-response.json"
incident_id="$(jq -er '.data.incidentId' "$evidence_dir/alert-response.json")"

curl --fail --silent --show-error \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"OpsPilot@2026"}' \
  http://localhost:9900/api/v1/auth/login > "$evidence_dir/login-response.json"
token="$(jq -er '.data.accessToken' "$evidence_dir/login-response.json")"
rm "$evidence_dir/login-response.json"

curl --fail --silent --show-error \
  -X POST \
  -H "Authorization: Bearer $token" \
  "http://localhost:9900/api/v1/incidents/${incident_id}/investigations?source=OTEL_PIPELINE_IT" \
  > "$evidence_dir/investigation-response.json"
run_id="$(jq -er '.data.runId' "$evidence_dir/investigation-response.json")"
jq -e '.data.status == "COMPLETED"' "$evidence_dir/investigation-response.json" >/dev/null

trace_query="{ resource.service.name = \"opspilot\" && span:name = \"opspilot.agent.run\" && span.\"opspilot.agent.run.id\" = \"${run_id}\" }"
trace_id=""
for _ in {1..45}; do
  if curl --fail --silent --show-error --get \
      --data-urlencode "q=$trace_query" \
      http://localhost:3200/api/search > "$evidence_dir/tempo-search.json"; then
    trace_id="$(jq -r '.traces[0].traceID // empty' "$evidence_dir/tempo-search.json")"
    if [[ -n "$trace_id" ]]; then
      break
    fi
  fi
  sleep 2
done
if [[ -z "$trace_id" ]]; then
  echo "Tempo did not return the Agent trace" >&2
  exit 1
fi

curl --fail --silent --show-error \
  "http://localhost:3200/api/traces/${trace_id}" > "$evidence_dir/trace.json"
curl --fail --silent --show-error \
  "http://localhost:3000/api/datasources/proxy/uid/tempo/api/traces/${trace_id}" \
  > "$evidence_dir/grafana-trace.json"
jq -e '.batches | length > 0' "$evidence_dir/grafana-trace.json" >/dev/null
jq '[.. | objects | select(has("traceId") and has("spanId") and has("name")) |
  {traceId, spanId, parentSpanId, name, attributes}]' \
  "$evidence_dir/trace.json" > "$evidence_dir/spans.json"

jq -e '[.[] | select(.name == "opspilot.agent.run")] | length == 1' "$evidence_dir/spans.json" >/dev/null
jq -e '[.[] | select(.name == "opspilot.agent.tool")] | length == 6' "$evidence_dir/spans.json" >/dev/null
jq -e '[.[] | select(.name == "opspilot.provider.query")] | length == 2' "$evidence_dir/spans.json" >/dev/null
jq -e '
  ([.[] | select(.name == "opspilot.agent.run")][0].spanId) as $run
  | $run != null
    and ([.[] | select(.name == "opspilot.agent.run")][0].parentSpanId | length > 0)
    and ([.[] | select(.name == "opspilot.agent.tool") | .parentSpanId] | all(. == $run))
' "$evidence_dir/spans.json" >/dev/null
jq -e '
  [.[] | select(.name == "opspilot.agent.tool") | .spanId] as $tools
  | [.[] | select(.name == "opspilot.provider.query") | .parentSpanId]
  | all(. as $parent | $tools | index($parent) != null)
' "$evidence_dir/spans.json" >/dev/null

if grep --fixed-strings --quiet "$sentinel" "$evidence_dir/trace.json"; then
  echo "Sensitive alert payload leaked into exported trace" >&2
  exit 1
fi

tool_count="$(jq '[.[] | select(.name == "opspilot.agent.tool")] | length' "$evidence_dir/spans.json")"
provider_count="$(jq '[.[] | select(.name == "opspilot.provider.query")] | length' "$evidence_dir/spans.json")"
jq -n \
  --argjson incidentId "$incident_id" \
  --argjson runId "$run_id" \
  --arg traceId "$trace_id" \
  --argjson toolSpans "$tool_count" \
  --argjson providerSpans "$provider_count" \
  '{incidentId:$incidentId, runId:$runId, traceId:$traceId, collector:"otelcol-contrib", backend:"tempo", queryUi:"grafana", status:"COMPLETED", spanCounts:{agentRun:1, agentTool:$toolSpans, providerQuery:$providerSpans}, parentHierarchyVerified:true, sensitivePayloadExcluded:true, grafanaDatasourceVerified:true}' \
  > "$evidence_dir/result.json"

collect_logs
cat "$evidence_dir/result.json"

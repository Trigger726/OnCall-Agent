#!/usr/bin/env bash
set -euo pipefail

evidence_dir="target/tracing-it"
mkdir -p "$evidence_dir"

# Keep verification volumes isolated from a developer's normal Compose project.
export COMPOSE_PROJECT_NAME="opspilot-tracing-it-${GITHUB_RUN_ID:-local-$$}"
export OTEL_TRACING_ENABLED=true
export OTEL_TRACING_SAMPLING_PROBABILITY=1.0
export OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=http://otel-collector:4318/v1/traces
export PROMETHEUS_ENABLED=true
export PROMETHEUS_BASE_URL=http://trace-provider-fixture:9910
export LOKI_ENABLED=true
export LOKI_BASE_URL=http://trace-provider-fixture:9910

compose=(docker compose --profile tracing --profile tracing-test)

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

assert_agent_trace() {
  local trace_file="$1"
  local spans_file="$2"
  local sensitive_sentinel="$3"

  jq '[.. | objects | select(has("traceId") and has("spanId") and has("name")) |
    {traceId, spanId, parentSpanId, name, attributes}]' \
    "$trace_file" > "$spans_file"
  jq -e '[.[] | select(.name == "opspilot.agent.run")] | length == 1' "$spans_file" >/dev/null
  jq -e '[.[] | select(.name == "opspilot.agent.tool")] | length == 6' "$spans_file" >/dev/null
  jq -e '[.[] | select(.name == "opspilot.provider.query")] | length == 2' "$spans_file" >/dev/null
  jq -e '
    ([.[] | select(.name == "opspilot.agent.run")][0].spanId) as $run
    | $run != null
      and ([.[] | select(.name == "opspilot.agent.run")][0].parentSpanId | length > 0)
      and ([.[] | select(.name == "opspilot.agent.tool") | .parentSpanId] | all(. == $run))
  ' "$spans_file" >/dev/null
  jq -e '
    [.[] | select(.name == "opspilot.agent.tool") | .spanId] as $tools
    | [.[] | select(.name == "opspilot.provider.query") | .parentSpanId]
    | all(. as $parent | $tools | index($parent) != null)
  ' "$spans_file" >/dev/null

  if grep --fixed-strings --quiet "$sensitive_sentinel" "$trace_file"; then
    echo "Sensitive alert payload leaked into exported trace" >&2
    return 1
  fi
}

assert_cross_service_trace() {
  local trace_file="$1"
  local spans_file="$2"

  jq '[.batches[] as $batch
    | ($batch.resource.attributes
       | map(select(.key == "service.name") | .value.stringValue)
       | first // "unknown") as $service
    | $batch.scopeSpans[].spans[]
    | {service:$service, traceId, spanId, parentSpanId, name, kind}]' \
    "$trace_file" > "$spans_file"
  jq -e '
    [.[] | select(.service == "opspilot" and .name == "opspilot.provider.query")] as $providers
    | [.[] | select(.service == "opspilot" and .kind == "SPAN_KIND_CLIENT")
       | select(.parentSpanId as $parent | $providers | any(.spanId == $parent))] as $clients
    | [.[] | select(.service == "trace-provider-fixture" and .kind == "SPAN_KIND_SERVER")] as $servers
    | ($providers | length) == 2
      and ($clients | length) == 2
      and ($servers | length) == 2
      and ([$clients[].parentSpanId] | sort) == ([$providers[].spanId] | sort)
      and ([$servers[].parentSpanId] | sort) == ([$clients[].spanId] | sort)
      and ([$servers[].name] | sort) == (["http get /api/v1/query", "http get /loki/api/v1/query_range"] | sort)
      and ([$providers[].traceId, $clients[].traceId, $servers[].traceId] | unique | length) == 1
  ' "$spans_file" >/dev/null
}

"${compose[@]}" up --build --detach mysql tempo-init tempo otel-collector grafana trace-provider-fixture opspilot
wait_http http://localhost:3200/ready "Tempo"
wait_http http://localhost:13133/ "OpenTelemetry Collector"
wait_http http://localhost:3000/api/health "Grafana"
wait_http http://localhost:9910/actuator/health "instrumented provider fixture"
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

cross_service_verified=false
for _ in {1..45}; do
  if curl --fail --silent --show-error \
      "http://localhost:3200/api/traces/${trace_id}" > "$evidence_dir/trace.json" \
      && assert_agent_trace "$evidence_dir/trace.json" "$evidence_dir/spans.json" "$sentinel" \
      && assert_cross_service_trace "$evidence_dir/trace.json" "$evidence_dir/cross-service-spans.json"; then
    cross_service_verified=true
    break
  fi
  sleep 2
done
if [[ "$cross_service_verified" != true ]]; then
  echo "Tempo did not join OpsPilot CLIENT spans to fixture SERVER spans" >&2
  exit 1
fi
curl --fail --silent --show-error \
  "http://localhost:3000/api/datasources/proxy/uid/tempo/api/traces/${trace_id}" \
  > "$evidence_dir/grafana-trace.json"
jq -e '.batches | length > 0' "$evidence_dir/grafana-trace.json" >/dev/null

tool_count="$(jq '[.[] | select(.name == "opspilot.agent.tool")] | length' "$evidence_dir/spans.json")"
provider_count="$(jq '[.[] | select(.name == "opspilot.provider.query")] | length' "$evidence_dir/spans.json")"
client_count="$(jq '[.[] | select(.service == "opspilot" and .kind == "SPAN_KIND_CLIENT")] | length' "$evidence_dir/cross-service-spans.json")"
server_count="$(jq '[.[] | select(.service == "trace-provider-fixture" and .kind == "SPAN_KIND_SERVER")] | length' "$evidence_dir/cross-service-spans.json")"
jq -n \
  --argjson incidentId "$incident_id" \
  --argjson runId "$run_id" \
  --arg traceId "$trace_id" \
  --argjson toolSpans "$tool_count" \
  --argjson providerSpans "$provider_count" \
  --argjson clientSpans "$client_count" \
  --argjson serverSpans "$server_count" \
  '{incidentId:$incidentId, runId:$runId, traceId:$traceId, collector:"otelcol-contrib", backend:"tempo", queryUi:"grafana", status:"COMPLETED", spanCounts:{agentRun:1, agentTool:$toolSpans, providerQuery:$providerSpans, client:$clientSpans, server:$serverSpans}, parentHierarchyVerified:true, crossServiceParentHierarchyVerified:true, sensitivePayloadExcluded:true, grafanaDatasourceVerified:true}' \
  > "$evidence_dir/result.json"

# Prove a bounded downstream outage does not block incident work and that the
# Collector exports the queued trace after Tempo returns within the retry window.
"${compose[@]}" stop tempo
outage_started_at="$(date --utc +%Y-%m-%dT%H:%M:%SZ)"
outage_nonce="$(date +%s)-$RANDOM"
outage_sentinel="trace-outage-private-${outage_nonce}"
outage_alert_payload="$(jq -n \
  --arg externalEventId "otel-outage-${outage_nonce}" \
  --arg title "${outage_sentinel}-title" \
  --arg description "${outage_sentinel}-description" \
  --arg label "${outage_sentinel}-label" \
  '{source:"otel-outage-it", externalEventId:$externalEventId, resourceCode:"APP-AUTH", severity:"P3", status:"FIRING", title:$title, description:$description, labels:{private:$label}}')"

curl --fail --silent --show-error \
  -H 'Content-Type: application/json' \
  -d "$outage_alert_payload" \
  http://localhost:9900/api/v1/alerts/intake > "$evidence_dir/outage-alert-response.json"
outage_incident_id="$(jq -er '.data.incidentId' "$evidence_dir/outage-alert-response.json")"

curl --fail --silent --show-error \
  -X POST \
  -H "Authorization: Bearer $token" \
  "http://localhost:9900/api/v1/incidents/${outage_incident_id}/investigations?source=OTEL_TEMPO_OUTAGE_IT" \
  > "$evidence_dir/outage-investigation-response.json"
outage_run_id="$(jq -er '.data.runId' "$evidence_dir/outage-investigation-response.json")"
jq -e '.data.status == "COMPLETED"' "$evidence_dir/outage-investigation-response.json" >/dev/null
curl --fail --silent --show-error \
  http://localhost:9900/actuator/health > "$evidence_dir/outage-app-health.json"
jq -e '.status == "UP"' "$evidence_dir/outage-app-health.json" >/dev/null

collector_failure_observed=false
for _ in {1..10}; do
  "${compose[@]}" logs --since "$outage_started_at" --no-color otel-collector \
    > "$evidence_dir/outage-collector.log" 2>&1
  if grep --extended-regexp --ignore-case --quiet \
      'Exporting failed|connection refused|Unavailable' "$evidence_dir/outage-collector.log"; then
    collector_failure_observed=true
    break
  fi
  sleep 1
done
if [[ "$collector_failure_observed" != true ]]; then
  echo "Collector did not observe an export failure while Tempo was stopped" >&2
  exit 1
fi

"${compose[@]}" start tempo
wait_http http://localhost:3200/ready "Tempo after injected outage"

outage_trace_query="{ resource.service.name = \"opspilot\" && span:name = \"opspilot.agent.run\" && span.\"opspilot.agent.run.id\" = \"${outage_run_id}\" }"
outage_trace_id=""
for _ in {1..45}; do
  if curl --fail --silent --show-error --get \
      --data-urlencode "q=$outage_trace_query" \
      http://localhost:3200/api/search > "$evidence_dir/outage-tempo-search.json"; then
    outage_trace_id="$(jq -r '.traces[0].traceID // empty' "$evidence_dir/outage-tempo-search.json")"
    if [[ -n "$outage_trace_id" ]]; then
      break
    fi
  fi
  sleep 2
done
if [[ -z "$outage_trace_id" ]]; then
  echo "Tempo did not return the trace created during its outage" >&2
  exit 1
fi

outage_cross_service_verified=false
for _ in {1..45}; do
  if curl --fail --silent --show-error \
      "http://localhost:3200/api/traces/${outage_trace_id}" > "$evidence_dir/outage-trace.json" \
      && assert_agent_trace "$evidence_dir/outage-trace.json" "$evidence_dir/outage-spans.json" "$outage_sentinel" \
      && assert_cross_service_trace "$evidence_dir/outage-trace.json" "$evidence_dir/outage-cross-service-spans.json"; then
    outage_cross_service_verified=true
    break
  fi
  sleep 2
done
if [[ "$outage_cross_service_verified" != true ]]; then
  echo "Tempo did not recover the joined cross-service trace after its outage" >&2
  exit 1
fi

outage_tool_count="$(jq '[.[] | select(.name == "opspilot.agent.tool")] | length' "$evidence_dir/outage-spans.json")"
outage_provider_count="$(jq '[.[] | select(.name == "opspilot.provider.query")] | length' "$evidence_dir/outage-spans.json")"
outage_client_count="$(jq '[.[] | select(.service == "opspilot" and .kind == "SPAN_KIND_CLIENT")] | length' "$evidence_dir/outage-cross-service-spans.json")"
outage_server_count="$(jq '[.[] | select(.service == "trace-provider-fixture" and .kind == "SPAN_KIND_SERVER")] | length' "$evidence_dir/outage-cross-service-spans.json")"
jq -n \
  --argjson incidentId "$outage_incident_id" \
  --argjson runId "$outage_run_id" \
  --arg traceId "$outage_trace_id" \
  --argjson toolSpans "$outage_tool_count" \
  --argjson providerSpans "$outage_provider_count" \
  --argjson clientSpans "$outage_client_count" \
  --argjson serverSpans "$outage_server_count" \
  '{incidentId:$incidentId, runId:$runId, traceId:$traceId, injectedFailure:"tempo-stopped", investigationStatus:"COMPLETED", applicationHealthDuringOutage:"UP", collectorFailureObserved:true, traceRecoveredAfterTempoRestart:true, spanCounts:{agentRun:1, agentTool:$toolSpans, providerQuery:$providerSpans, client:$clientSpans, server:$serverSpans}, parentHierarchyVerified:true, crossServiceParentHierarchyVerified:true, sensitivePayloadExcluded:true}' \
  > "$evidence_dir/outage-result.json"

collect_logs
cat "$evidence_dir/result.json"
cat "$evidence_dir/outage-result.json"

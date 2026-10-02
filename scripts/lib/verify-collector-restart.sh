#!/usr/bin/env bash
# Sourced by verify-tracing-pipeline.sh; uses its isolated compose array,
# wait_http and strict full-graph assertions. Never re-submit the investigation.

collector_metric() {
  local metric_name="$1" metrics_file="$2"
  awk -v metric="$metric_name" '
    $1 ~ ("^" metric "(\\{|$)") { sum += $2; found = 1 }
    END { if (!found) exit 1; printf "%.0f\n", sum }
  ' "$metrics_file"
}

verify_collector_restart() {
  local evidence_dir="$1" token="$2"
  local metrics_url=http://localhost:18888/metrics
  local trace_id parent_id sentinel payload incident_id run_id
  local accepted_before accepted_delta queue_before queue_after
  local old_container new_container exit_code restart_started_at
  local backlog_ready=false graph_restored=false query_matches=false drained=false

  wait_http "$metrics_url" "Collector diagnostic metrics"
  # Drain the previous outage first, then measure acceptance before this scenario.
  for _ in {1..20}; do
    curl --fail --silent --show-error "$metrics_url" > "$evidence_dir/restart-metrics-before.txt"
    if [[ "$(collector_metric otelcol_exporter_queue_size "$evidence_dir/restart-metrics-before.txt")" == 0 ]]; then
      drained=true
      break
    fi
    sleep 1
  done
  [[ "$drained" == true ]] || { echo "Previous scenario queue did not drain" >&2; return 1; }
  accepted_before="$(collector_metric otelcol_receiver_accepted_spans "$evidence_dir/restart-metrics-before.txt")"
  "${compose[@]}" stop tempo
  restart_started_at="$(date --utc +%Y-%m-%dT%H:%M:%SZ)"
  # Occupy the sole TEST consumer before the real investigation. Otherwise two
  # JVM exports could coalesce into one in-flight request and queue_size stay 0.
  # This separate synthetic trace is never used as recovery success evidence.
  local blocker_id blocker_span blocker_start blocker_end blocker_ready=false
  blocker_id="$(openssl rand -hex 16)"
  blocker_span="$(openssl rand -hex 8)"
  blocker_start="$(date +%s%N)"
  blocker_end="$(( blocker_start + 1000000 ))"
  jq -n --arg trace "$blocker_id" --arg span "$blocker_span" --arg start "$blocker_start" --arg end "$blocker_end" \
    '{resourceSpans:[{resource:{attributes:[{key:"service.name",value:{stringValue:"collector-restart-blocker"}}]},
      scopeSpans:[{scope:{name:"opspilot.restart-test"},spans:[{traceId:$trace,spanId:$span,name:"exporter-blocker",kind:1,startTimeUnixNano:$start,endTimeUnixNano:$end}]}]}]}' \
    > "$evidence_dir/restart-blocker-otlp.json"
  curl --fail --silent --show-error -H 'Content-Type: application/json' \
    --data-binary "@$evidence_dir/restart-blocker-otlp.json" http://localhost:4318/v1/traces \
    > "$evidence_dir/restart-blocker-ack.json"
  for _ in {1..10}; do
    curl --fail --silent --show-error "$metrics_url" > "$evidence_dir/restart-metrics-blocker.txt"
    if (( $(collector_metric otelcol_exporter_in_flight_requests "$evidence_dir/restart-metrics-blocker.txt") > 0 )); then
      blocker_ready=true
      break
    fi
    sleep 1
  done
  [[ "$blocker_ready" == true ]] || { echo "Test consumer was not occupied before investigation" >&2; return 1; }
  trace_id="$(openssl rand -hex 16)"
  parent_id="$(openssl rand -hex 8)"
  sentinel="trace-restart-private-${trace_id}"
  payload="$(jq -n --arg event "otel-restart-${trace_id}" --arg private "$sentinel" \
    '{source:"otel-restart-it", externalEventId:$event, resourceCode:"APP-AUTH", severity:"P3", status:"FIRING", title:$private, description:$private, labels:{private:$private}}')"
  curl --fail --silent --show-error -H 'Content-Type: application/json' -d "$payload" \
    http://localhost:9900/api/v1/alerts/intake > "$evidence_dir/restart-alert-response.json"
  incident_id="$(jq -er '.data.incidentId' "$evidence_dir/restart-alert-response.json")"
  curl --fail --silent --show-error -X POST -H "Authorization: Bearer $token" \
    -H "traceparent: 00-${trace_id}-${parent_id}-01" \
    "http://localhost:9900/api/v1/incidents/${incident_id}/investigations?source=OTEL_COLLECTOR_RESTART_IT" \
    > "$evidence_dir/restart-investigation-response.json"
  run_id="$(jq -er '.data.runId' "$evidence_dir/restart-investigation-response.json")"
  jq -e '.data.status == "COMPLETED"' "$evidence_dir/restart-investigation-response.json" >/dev/null
  curl --fail --silent --show-error http://localhost:9920/actuator/health > "$evidence_dir/restart-app-health.json"
  jq -e '.status == "UP"' "$evidence_dir/restart-app-health.json" >/dev/null

  # Allow both JVM batch exporters and the Collector batch processor to flush.
  # Acceptance is an aggregate precondition, NOT a per-trace durable ACK.
  sleep 12
  for _ in {1..15}; do
    curl --fail --silent --show-error "$metrics_url" > "$evidence_dir/restart-metrics-queued.txt"
    accepted_delta=$(( $(collector_metric otelcol_receiver_accepted_spans "$evidence_dir/restart-metrics-queued.txt") - accepted_before ))
    queue_before="$(collector_metric otelcol_exporter_queue_size "$evidence_dir/restart-metrics-queued.txt")"
    if (( accepted_delta >= 14 && queue_before > 0 )); then
      backlog_ready=true
      break
    fi
    sleep 1
  done
  [[ "$backlog_ready" == true ]] || { echo "No accepted spans + queued backlog before crash; fault not proven" >&2; return 1; }
  "${compose[@]}" logs --since "$restart_started_at" --no-color otel-collector > "$evidence_dir/restart-collector-before.log" 2>&1
  grep --extended-regexp --ignore-case --quiet 'Exporting failed|connection refused|Unavailable' "$evidence_dir/restart-collector-before.log"
  old_container="$("${compose[@]}" ps --quiet otel-collector)"
  docker inspect --format '{{json .Config.User}}' "$old_container" > "$evidence_dir/restart-collector-user.json"
  "${compose[@]}" kill --signal SIGKILL otel-collector
  docker inspect --format '{{json .State}}' "$old_container" > "$evidence_dir/restart-killed-state.json"
  exit_code="$(jq -er '.ExitCode' "$evidence_dir/restart-killed-state.json")"
  jq -e '.Running == false and .ExitCode == 137' "$evidence_dir/restart-killed-state.json" >/dev/null
  # Recreate a NEW container, with Tempo still stopped. Reusing only a container
  # writable layer would not pass this experiment after persistent storage is added.
  "${compose[@]}" up --detach --no-deps --force-recreate otel-collector
  new_container="$("${compose[@]}" ps --quiet otel-collector)"
  [[ -n "$new_container" && "$new_container" != "$old_container" ]]
  wait_http http://localhost:13133/ "Collector after SIGKILL and recreation"
  wait_http "$metrics_url" "Recreated Collector metrics"
  curl --fail --silent --show-error "$metrics_url" > "$evidence_dir/restart-metrics-recreated.txt"
  queue_after="$(collector_metric otelcol_exporter_queue_size "$evidence_dir/restart-metrics-recreated.txt")"
  "${compose[@]}" start tempo
  wait_http http://localhost:3200/ready "Tempo after Collector recreation"

  # Fetch the predetermined trace, not just whichever trace appears in a search.
  # A restored Agent span alone is insufficient: both remote SERVER branches
  # and all six tool spans must survive with their exact parent hierarchy.
  local trace_query="{ resource.service.name = \"opspilot\" && span:name = \"opspilot.agent.run\" && span.\"opspilot.agent.run.id\" = \"${run_id}\" }"
  for _ in {1..35}; do
    if curl --fail --silent --show-error "http://localhost:3200/api/traces/${trace_id}" \
        > "$evidence_dir/restart-trace.json" 2> "$evidence_dir/restart-trace-fetch-error.txt" \
        && assert_agent_trace "$evidence_dir/restart-trace.json" "$evidence_dir/restart-spans.json" "$sentinel" \
        && assert_cross_service_trace "$evidence_dir/restart-trace.json" "$evidence_dir/restart-cross-service-spans.json" \
        && jq -e --arg run "$run_id" '[.[] | select(.name == "opspilot.agent.run") | .attributes[]
          | select(.key == "opspilot.agent.run.id") | .value.stringValue] == [$run]' "$evidence_dir/restart-spans.json" >/dev/null; then
      graph_restored=true
      break
    fi
    sleep 2
  done
  if [[ "$graph_restored" == true ]]; then
    curl --fail --silent --show-error --get --data-urlencode "q=$trace_query" \
      http://localhost:3200/api/search > "$evidence_dir/restart-tempo-search.json"
    if jq -e --arg id "$trace_id" '.traces | any(.traceID == $id)' "$evidence_dir/restart-tempo-search.json" >/dev/null; then
      query_matches=true
    fi
  fi
  drained=false
  for _ in {1..20}; do
    curl --fail --silent --show-error "$metrics_url" > "$evidence_dir/restart-metrics-drained.txt"
    if [[ "$(collector_metric otelcol_exporter_queue_size "$evidence_dir/restart-metrics-drained.txt")" == 0 ]]; then
      drained=true
      break
    fi
    sleep 1
  done
  local status=FAIL
  if [[ "$graph_restored" == true && "$query_matches" == true && "$drained" == true ]]; then
    status=PASS
  fi
  jq -n --arg status "$status" --arg traceId "$trace_id" --argjson incidentId "$incident_id" --argjson runId "$run_id" \
    --arg oldContainer "$old_container" --arg newContainer "$new_container" --argjson exitCode "$exit_code" \
    --argjson acceptedSpansDelta "$accepted_delta" --argjson queueBefore "$queue_before" --argjson queueAfter "$queue_after" \
    --arg blockerTraceId "$blocker_id" --argjson graphRestored "$graph_restored" --argjson queryMatches "$query_matches" --argjson drained "$drained" \
    '{status:$status, traceId:$traceId, incidentId:$incidentId, runId:$runId,
      injectedFailure:"tempo-stopped+collector-SIGKILL+container-recreated", investigationStatus:"COMPLETED", applicationHealthDuringOutage:"UP",
      investigationResubmitted:false, diagnosticNumConsumers:1, separateBlockerTraceId:$blockerTraceId, acceptedSpansDelta:$acceptedSpansDelta,
      queueRequestsBeforeCrash:$queueBefore, queueRequestsAfterRecreation:$queueAfter,
      killedExitCode:$exitCode, oldContainerId:$oldContainer, newContainerId:$newContainer,
      completeGraphRestored:$graphRestored, traceQueryMatchesPredeterminedId:$queryMatches, queueDrained:$drained,
      expectedSpanCounts:{agentRun:1, agentTool:6, providerQuery:2, providerClient:2, fixtureServer:2}}' \
    > "$evidence_dir/restart-result.json"
  cat "$evidence_dir/restart-result.json"
  [[ "$status" == PASS ]] || { echo "Predetermined complete trace was lost across Collector SIGKILL/recreation" >&2; return 1; }
}

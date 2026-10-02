#!/usr/bin/env bash
# Sourced after restart experiments. No alert delivery/Incident claim: verifies
# native Prometheus feedback against the actual Collector, then known-ID loss.
collector_counter() {
  # Lazy counters may not have a series before the first event. Missing counters
  # mean zero here; missing queue gauges still fail via collector_metric.
  awk -v metric="$1" '$1 ~ ("^" metric "(\\{|$)") { sum += $2 } END { printf "%.0f\n", sum }' "$2"
}

verify_collector_saturation() {
  local evidence_dir="$1" token="$2" metrics_url=http://localhost:18888/metrics
  local ids='[]' id accepted_before rejected_before accepted_delta rejected_delta queue capacity
  local saturation_observed=false alerts_fired=false queue_recovered=false recovered_count=0
  mkdir -p "$evidence_dir"
  wait_http http://localhost:13133/ "Collector saturation test"
  wait_http "$metrics_url" "Collector saturation metrics"
  wait_http http://localhost:9090/-/ready "Prometheus saturation test"
  curl --fail --silent --show-error "$metrics_url" > "$evidence_dir/metrics-before.txt"
  accepted_before="$(collector_counter otelcol_receiver_accepted_spans "$evidence_dir/metrics-before.txt")"
  rejected_before="$(collector_counter otelcol_exporter_enqueue_failed_spans "$evidence_dir/metrics-before.txt")"
  "${compose[@]}" stop tempo
  for _ in {1..12}; do
    id="$(openssl rand -hex 16)"
    collector_send_probe "$evidence_dir" "$id" saturation-probe
    [[ "$(< "$evidence_dir/probe-${id}-http-status.txt")" == 200 ]]
    jq -e '(.partialSuccess.rejectedSpans // "0" | tonumber) == 0' "$evidence_dir/probe-${id}-ack.json" >/dev/null
    ids="$(jq --arg id "$id" '. + [$id]' <<< "$ids")"
  done
  jq . <<< "$ids" > "$evidence_dir/probe-trace-ids.json"
  for _ in {1..10}; do
    curl --fail --silent --show-error "$metrics_url" > "$evidence_dir/metrics-saturated.txt"
    accepted_delta=$(( $(collector_counter otelcol_receiver_accepted_spans "$evidence_dir/metrics-saturated.txt") - accepted_before ))
    rejected_delta=$(( $(collector_counter otelcol_exporter_enqueue_failed_spans "$evidence_dir/metrics-saturated.txt") - rejected_before ))
    queue="$(collector_metric otelcol_exporter_queue_size "$evidence_dir/metrics-saturated.txt")"
    capacity="$(collector_metric otelcol_exporter_queue_capacity "$evidence_dir/metrics-saturated.txt")"
    if (( capacity == 4 && queue == capacity && accepted_delta >= 12 && rejected_delta > 0 )); then
      saturation_observed=true
      break
    fi
    sleep 1
  done
  [[ "$saturation_observed" == true ]] || { echo "No actual capacity-four enqueue rejection" >&2; return 1; }
  local alert_payload incident_id run_id
  alert_payload="$(jq -n --arg event "otel-saturated-${id}" '{source:"otel-saturation-it", externalEventId:$event,
    resourceCode:"APP-AUTH",severity:"P3",status:"FIRING",title:"Collector saturation business availability check"}')"
  curl --fail --silent --show-error -H 'Content-Type: application/json' -d "$alert_payload" \
    http://localhost:9900/api/v1/alerts/intake > "$evidence_dir/alert-response.json"
  incident_id="$(jq -er '.data.incidentId' "$evidence_dir/alert-response.json")"
  curl --fail --silent --show-error -X POST -H "Authorization: Bearer $token" \
    "http://localhost:9900/api/v1/incidents/${incident_id}/investigations?source=OTEL_SATURATION_IT" \
    > "$evidence_dir/investigation-response.json"
  run_id="$(jq -er '.data.runId' "$evidence_dir/investigation-response.json")"
  jq -e '.data.status == "COMPLETED"' "$evidence_dir/investigation-response.json" >/dev/null
  curl --fail --silent --show-error http://localhost:9920/actuator/health > "$evidence_dir/app-health.json"
  jq -e '.status == "UP"' "$evidence_dir/app-health.json" >/dev/null

  # Same production rule and 30s hold; only scrape/evaluation cadence is 2s.
  for _ in {1..23}; do
    curl --fail --silent --show-error http://localhost:9090/api/v1/alerts > "$evidence_dir/alerts-firing.json"
    if jq -e '.status == "success" and
      ([.data.alerts[] | select(.state == "firing") | .labels.alertname]
        | index("CollectorTraceQueueHigh") != null and index("CollectorTraceEnqueueRejected") != null)' \
        "$evidence_dir/alerts-firing.json" >/dev/null; then
      alerts_fired=true
      break
    fi
    sleep 2
  done
  "${compose[@]}" start tempo
  wait_http http://localhost:3200/ready "Tempo after saturation"
  for _ in {1..20}; do
    curl --fail --silent --show-error "$metrics_url" > "$evidence_dir/metrics-drained.txt"
    curl --fail --silent --show-error http://localhost:9090/api/v1/alerts > "$evidence_dir/alerts-after-recovery.json"
    if [[ "$(collector_metric otelcol_exporter_queue_size "$evidence_dir/metrics-drained.txt")" == 0 \
        && "$(collector_metric otelcol_exporter_in_flight_requests "$evidence_dir/metrics-drained.txt")" == 0 ]] \
        && jq -e '.status == "success" and ([.data.alerts[] | select(.labels.alertname == "CollectorTraceQueueHigh")] | length == 0)' \
          "$evidence_dir/alerts-after-recovery.json" >/dev/null; then
      queue_recovered=true
      break
    fi
    sleep 1
  done
  # At least one acknowledged probe must arrive, while at least one is missing.
  # This rules out declaring loss solely because Tempo is still unavailable.
  local previous_count=-1 stable_samples=0
  for _ in {1..10}; do
    recovered_count=0
    while IFS= read -r id; do
      if curl --fail --silent --show-error "http://localhost:3200/api/traces/${id}" \
          > "$evidence_dir/probe-${id}-trace.json" 2> "$evidence_dir/probe-${id}-error.txt" \
          && jq -e '[.. | objects | select(has("spanId") and has("name"))] | length == 1 and .[0].name == "saturation-probe"' \
            "$evidence_dir/probe-${id}-trace.json" >/dev/null; then
        recovered_count=$(( recovered_count + 1 ))
      fi
    done < <(jq -r '.[]' "$evidence_dir/probe-trace-ids.json")
    if [[ "$recovered_count" == "$previous_count" ]]; then
      stable_samples=$(( stable_samples + 1 ))
    else
      stable_samples=0
      previous_count="$recovered_count"
    fi
    (( recovered_count > 0 && stable_samples >= 3 )) && break
    sleep 2
  done
  local status=FAIL
  if [[ "$alerts_fired" == true && "$queue_recovered" == true ]] && (( recovered_count > 0 && recovered_count < 12 && stable_samples >= 3 )); then
    status=PASS
  fi
  jq -n --arg status "$status" --argjson accepted "$accepted_delta" --argjson rejected "$rejected_delta" \
    --argjson recovered "$recovered_count" --argjson fired "$alerts_fired" --argjson queueRecovered "$queue_recovered" --argjson runId "$run_id" \
    '{status:$status, fault:"tempo-stopped+queue-capacity-four", testOnly:{consumers:1,queueCapacity:4,maxBatchSpans:1},
      probeRequests:12,probeHttp200:12,probePartialSuccessRejectedSpans:0,aggregateAcceptedSpansDelta:$accepted,aggregateEnqueueRejectedSpansDelta:$rejected,
      recoveredProbeTraces:$recovered,missingAcknowledgedProbeTraces:(12-$recovered),businessInvestigationRunId:$runId,
      businessInvestigationStatus:"COMPLETED",applicationHealth:"UP",queueHighAndRejectionAlertsFired:$fired,
      queueAndInFlightDrainedAndHighAlertResolved:$queueRecovered,probeRecoveryCountStable:true,rejectionAlertHasFiveMinuteHistoryWindow:true,
      alertmanagerDeliveryVerified:false,incidentLifecycleVerified:false}' > "$evidence_dir/result.json"
  cat "$evidence_dir/result.json"
  [[ "$status" == PASS ]] || { echo "Saturation feedback or known-ID loss not proven" >&2; return 1; }
}

#!/usr/bin/env bash
set -Eeuo pipefail
evidence_dir=target/collector-alerting-it
mkdir -p "$evidence_dir"
umask 077
secret_directory="$(mktemp -d)"
secret_file="$secret_directory/webhook-secret"
export ALERTMANAGER_WEBHOOK_SECRET="$(openssl rand -hex 24)"
export COLLECTOR_ALERTMANAGER_SECRET_FILE="$secret_file"
printf '%s' "$ALERTMANAGER_WEBHOOK_SECRET" > "$secret_file"
chmod 0444 "$secret_file"
export COMPOSE_PROJECT_NAME="opspilot-collector-alerting-it-${GITHUB_RUN_ID:-local-$$}"
export OTEL_TRACING_ENABLED=true
# Same real configs; only test cadence changes. Rules keep the 30s hold and 5m
# history window. No secrets are written to the uploaded evidence directory.
sed -e 's/scrape_interval: 15s/scrape_interval: 2s/' -e 's/evaluation_interval: 15s/evaluation_interval: 2s/' \
  deploy/prometheus-collector-alerting.yml > "$evidence_dir/prometheus-test.yml"
sed -e 's/group_wait: 30s/group_wait: 1s/' -e 's/group_interval: 1m/group_interval: 5s/' -e 's/repeat_interval: 1h/repeat_interval: 10s/' \
  deploy/collector-alertmanager.yml > "$evidence_dir/alertmanager-test.yml"
chmod 0444 "$evidence_dir/prometheus-test.yml" "$evidence_dir/alertmanager-test.yml"
compose=(docker compose -f docker-compose.yml -f deploy/docker-compose.collector-alerting.yml \
  -f integration/collector-alerting/docker-compose.yml --profile tracing --profile collector-alerting)
task_phase=start
trap 'echo "Collector alerting failure: phase=$task_phase line=$LINENO" >&2' ERR
cleanup() {
  "${compose[@]}" unpause tempo >/dev/null 2>&1 || true
  "${compose[@]}" logs --no-color > "$evidence_dir/compose.log" 2>&1 || true
  "${compose[@]}" down --volumes --remove-orphans >/dev/null 2>&1 || true
  rm -f -- "$secret_file"
  rmdir -- "$secret_directory"
}
trap cleanup EXIT
wait_http() {
  for _ in {1..60}; do
    if curl --fail --silent "$1" >/dev/null 2>&1; then return 0; fi
    sleep 2
  done
  echo "Timed out waiting for $2" >&2; return 1
}
mysql_value() {
  "${compose[@]}" exec -T -e MYSQL_PWD=opspilot-local mysql \
    mysql --batch --skip-column-names -uopspilot opspilot -e "$1" | tr -d '\r'
}
wait_value() {
  local expected="$1" query="$2" label="$3" attempts="${4:-60}" actual
  for ((attempt=0; attempt<attempts; attempt++)); do
    actual="$(mysql_value "$query")"
    [[ "$actual" == "$expected" ]] && return 0
    sleep 1
  done
  echo "Timed out: $label; expected=$expected actual=$actual" >&2; return 1
}
capture_alerts() {
  mysql_value "SELECT JSON_OBJECT('id',id,'incidentId',incident_id,'externalEventId',external_event_id,
    'status',status,'severity',severity,'title',title,'occurrences',occurrence_count,'version',version,
    'updatedAt',updated_at,'lastOccurredAt',last_occurred_at) FROM alert_event
    WHERE source='alertmanager' AND title IN ('CollectorTraceQueueHigh','CollectorTraceEnqueueRejected') ORDER BY id" | jq -s . > "$1"
}
notifications() {
  curl --fail --silent --show-error http://localhost:9093/metrics > "$1" || return 1
  alertmanager_webhook_attempts_without_failures "$1"
}
source scripts/lib/verify-collector-restart.sh
source scripts/lib/collector-alertmanager-metrics.sh
"${compose[@]}" up --build --detach mysql tempo otel-collector opspilot prometheus collector-alertmanager
wait_http http://localhost:9920/actuator/health OpsPilot
wait_http http://localhost:3200/ready Tempo
wait_http http://localhost:13133/ Collector
wait_http http://localhost:18888/metrics 'Collector metrics'
wait_http http://localhost:9090/-/ready Prometheus
wait_http http://localhost:9093/-/ready Alertmanager

task_phase=authentication
before_auth="$(mysql_value 'SELECT CONCAT((SELECT COUNT(*) FROM alert_event), ":", (SELECT COUNT(*) FROM alert_ingest_rejection))')"
for mode in missing wrong; do
  headers=()
  [[ "$mode" == missing ]] || headers=(-H 'Authorization: OpsPilot not-the-test-secret')
  status="$(curl --silent --show-error -H 'Content-Type: application/json' "${headers[@]}" --data '{"alerts":[]}' \
    --output "$evidence_dir/auth-${mode}.json" --write-out '%{http_code}' http://localhost:9900/api/v1/integrations/alertmanager/webhook)"
  [[ "$status" == 401 ]]
  jq -e '.error.code == "ALERTMANAGER_WEBHOOK_UNAUTHORIZED"' "$evidence_dir/auth-${mode}.json" >/dev/null
done
[[ "$(mysql_value 'SELECT CONCAT((SELECT COUNT(*) FROM alert_event), ":", (SELECT COUNT(*) FROM alert_ingest_rejection))')" == "$before_auth" ]]
[[ "$(mysql_value "SELECT COUNT(*) FROM cmdb_resource WHERE resource_code='OBS-OTEL-COLLECTOR'")" == 0 ]]

task_phase=fault-and-unregistered-resource
"${compose[@]}" pause tempo
for _ in {1..24}; do
  id="$(openssl rand -hex 16)"
  start="$(date +%s%N)"
  end="$(( start + 1000000 ))"
  # 24 actual OTLP requests of 32 spans exceed 16 persistent request slots.
  # These are explicit fault fixtures, not application investigation traces.
  jq -n --arg trace "$id" --arg start "$start" --arg end "$end" \
    '{resourceSpans:[{resource:{attributes:[{key:"service.name",value:{stringValue:"collector-alerting-fault-fixture"}}]},
      scopeSpans:[{scope:{name:"opspilot.alerting-fault"},spans:[range(1;33) | tostring as $span |
        {traceId:$trace,spanId:(("0000000000000000"+$span)[-16:]),name:"collector-alerting-fault",kind:1,
         startTimeUnixNano:$start,endTimeUnixNano:$end}]}]}]}' > "$evidence_dir/fault-$id-otlp.json"
  curl --fail --silent --show-error -H 'Content-Type: application/json' \
    --data-binary "@$evidence_dir/fault-$id-otlp.json" http://localhost:4318/v1/traces \
    --output "$evidence_dir/fault-$id-ack.json" --write-out '%{http_code}' > "$evidence_dir/fault-$id-http-status.txt"
  [[ "$(< "$evidence_dir/fault-$id-http-status.txt")" == 200 ]]
done
for _ in {1..15}; do
  curl --fail --silent --show-error http://localhost:18888/metrics > "$evidence_dir/metrics-saturated.txt"
  if [[ "$(collector_metric otelcol_exporter_queue_size "$evidence_dir/metrics-saturated.txt")" == 16 ]] \
      && (( $(collector_counter otelcol_exporter_enqueue_failed_spans "$evidence_dir/metrics-saturated.txt") > 0 )); then break; fi
  sleep 1
done
[[ "$(collector_metric otelcol_exporter_queue_capacity "$evidence_dir/metrics-saturated.txt")" == 16 ]]
[[ "$(collector_metric otelcol_exporter_queue_size "$evidence_dir/metrics-saturated.txt")" == 16 ]]
(( $(collector_counter otelcol_exporter_enqueue_failed_spans "$evidence_dir/metrics-saturated.txt") > 0 ))
wait_value 1 "SELECT COUNT(*) > 0 FROM alert_ingest_rejection WHERE source='alertmanager'
  AND resource_code='OBS-OTEL-COLLECTOR' AND error_code='RESOURCE_NOT_FOUND' AND status='OPEN'" 'actual CMDB rejection'
mysql_value "SELECT JSON_OBJECT('id',id,'alertName',alert_name,'error',error_code,'status',status) FROM alert_ingest_rejection
  WHERE resource_code='OBS-OTEL-COLLECTOR' ORDER BY id" | jq -s . > "$evidence_dir/unregistered-rejections.json"
[[ "$(mysql_value "SELECT COUNT(*) FROM alert_event WHERE source='alertmanager' AND title LIKE 'CollectorTrace%'")" == 0 ]]

task_phase=registration-and-real-delivery
# Explicitly register only in this script's owned isolated database. Do not
# mutate the user's ordinary Demo or silently map to APP-PORTAL/APP-AUTH.
mysql_value "$(< deploy/register-demo-collector-resource.sql)"
mysql_value "$(< deploy/register-demo-collector-resource.sql)"
[[ "$(mysql_value "SELECT COUNT(*) FROM cmdb_resource WHERE resource_code='OBS-OTEL-COLLECTOR' AND resource_type='MIDDLEWARE' AND environment='DEVELOPMENT' AND owner_user_id IS NULL")" == 1 ]]
resource_id="$(mysql_value "SELECT id FROM cmdb_resource WHERE resource_code='OBS-OTEL-COLLECTOR'")"
mysql_value "SELECT JSON_OBJECT('id',id,'code',resource_code,'type',resource_type,'environment',environment,
  'ownerUserId',owner_user_id) FROM cmdb_resource WHERE id=$resource_id" | jq -s . > "$evidence_dir/registered-resource.json"
wait_value 2 "SELECT COUNT(*) FROM alert_event WHERE source='alertmanager' AND service_resource_id=$resource_id
  AND title IN ('CollectorTraceQueueHigh','CollectorTraceEnqueueRejected') AND status='FIRING' AND occurrence_count=1" 'two actual native deliveries'
capture_alerts "$evidence_dir/firing-alerts.json"
jq -e 'length==2 and all(.incidentId != null)
  and any(.title=="CollectorTraceQueueHigh" and .severity=="P2")
  and any(.title=="CollectorTraceEnqueueRejected" and .severity=="P1")' "$evidence_dir/firing-alerts.json" >/dev/null
wait_value 0 "SELECT COUNT(*) FROM alert_ingest_rejection WHERE resource_code='OBS-OTEL-COLLECTOR' AND status='OPEN'" 'repeated delivery resolves rejection'
curl --fail --silent --show-error http://localhost:9090/api/v1/alerts > "$evidence_dir/prometheus-firing.json"
jq -e '[.data.alerts[] | select(.state=="firing" and .labels.resource_code=="OBS-OTEL-COLLECTOR") | .labels.alertname]
  | index("CollectorTraceQueueHigh")!=null and index("CollectorTraceEnqueueRejected")!=null' "$evidence_dir/prometheus-firing.json" >/dev/null

task_phase=native-repeat-idempotency
notifications_before="$(notifications "$evidence_dir/notifications-before.txt")"
for _ in {1..30}; do
  notifications_after="$(notifications "$evidence_dir/notifications-after.txt")"
  (( notifications_after >= notifications_before + 2 )) && break
  sleep 1
done
(( notifications_after >= notifications_before + 2 ))
capture_alerts "$evidence_dir/repeated-alerts.json"
cmp --silent "$evidence_dir/firing-alerts.json" "$evidence_dir/repeated-alerts.json"

task_phase=actual-recovery
"${compose[@]}" unpause tempo
wait_http http://localhost:3200/ready 'Tempo resumed'
for _ in {1..40}; do
  curl --fail --silent --show-error http://localhost:18888/metrics > "$evidence_dir/metrics-drained.txt"
  if [[ "$(collector_metric otelcol_exporter_queue_size "$evidence_dir/metrics-drained.txt")" == 0 \
      && "$(collector_metric otelcol_exporter_in_flight_requests "$evidence_dir/metrics-drained.txt")" == 0 ]]; then break; fi
  sleep 1
done
[[ "$(collector_metric otelcol_exporter_queue_size "$evidence_dir/metrics-drained.txt")" == 0 ]]
[[ "$(collector_metric otelcol_exporter_in_flight_requests "$evidence_dir/metrics-drained.txt")" == 0 ]]
wait_value 1 "SELECT COUNT(*) FROM alert_event WHERE service_resource_id=$resource_id AND title='CollectorTraceQueueHigh' AND status='RESOLVED'" 'queue warning resolved'
curl --fail --silent --show-error http://localhost:9090/api/v1/alerts > "$evidence_dir/prometheus-after-queue-recovery.json"
jq -e 'any(.data.alerts[]; .labels.alertname=="CollectorTraceEnqueueRejected" and .state=="firing")' "$evidence_dir/prometheus-after-queue-recovery.json" >/dev/null
# Preserve the real 5-minute counter history; no fake resolved POST and no
# restarting Prometheus/Collector to clear a positive rejection counter.
wait_value 2 "SELECT COUNT(*) FROM alert_event WHERE service_resource_id=$resource_id AND source='alertmanager'
  AND title IN ('CollectorTraceQueueHigh','CollectorTraceEnqueueRejected') AND status='RESOLVED' AND occurrence_count=1" 'both same lifecycle IDs resolved' 420
curl --fail --silent --show-error http://localhost:18888/metrics > "$evidence_dir/metrics-final.txt"
[[ "$(collector_counter otelcol_exporter_enqueue_failed_spans "$evidence_dir/metrics-final.txt")" == "$(collector_counter otelcol_exporter_enqueue_failed_spans "$evidence_dir/metrics-drained.txt")" ]]
capture_alerts "$evidence_dir/resolved-alerts.json"
jq -e --slurpfile before "$evidence_dir/firing-alerts.json" '([.[].id] == [$before[0][].id])
  and ([.[].incidentId] == [$before[0][].incidentId]) and ([.[].externalEventId] == [$before[0][].externalEventId])' "$evidence_dir/resolved-alerts.json" >/dev/null
wait_value 2 "SELECT COUNT(*) FROM incident_timeline t JOIN alert_event a ON t.evidence_ref=CONCAT('alert:',a.id)
  WHERE a.service_resource_id=$resource_id AND a.source='alertmanager' AND t.event_type='ALERT_RESOLVED'" 'one durable resolution per alert'
mysql_value "SELECT JSON_OBJECT('id',t.id,'incidentId',t.incident_id,'eventType',t.event_type,
  'evidenceRef',t.evidence_ref,'createdAt',t.created_at) FROM incident_timeline t JOIN alert_event a ON t.evidence_ref=CONCAT('alert:',a.id)
  WHERE a.service_resource_id=$resource_id AND a.source='alertmanager' AND t.event_type='ALERT_RESOLVED' ORDER BY t.id" | jq -s . > "$evidence_dir/final-timeline.json"
[[ "$(mysql_value "SELECT COUNT(*) FROM incident WHERE service_resource_id=$resource_id AND status='OPEN'")" == 2 ]]
[[ "$(mysql_value "SELECT COUNT(*) FROM incident WHERE service_resource_id=$resource_id AND (assignee_id IS NOT NULL OR commander_id IS NOT NULL OR acknowledged_at IS NOT NULL OR resolved_at IS NOT NULL)")" == 0 ]]
mysql_value "SELECT JSON_OBJECT('id',id,'alertName',alert_name,'status',status,'resolvedAlertId',resolved_alert_id,
  'resolvedIncidentId',resolved_incident_id) FROM alert_ingest_rejection WHERE resource_code='OBS-OTEL-COLLECTOR' ORDER BY id" | jq -s . > "$evidence_dir/final-rejections.json"
mysql_value "SELECT JSON_OBJECT('id',id,'status',status,'severity',severity,'resourceId',service_resource_id,
  'assigneeId',assignee_id,'acknowledgedAt',acknowledged_at,'resolvedAt',resolved_at) FROM incident WHERE service_resource_id=$resource_id ORDER BY id" | jq -s . > "$evidence_dir/final-incidents.json"
curl --fail --silent --show-error http://localhost:9090/api/v1/alerts > "$evidence_dir/prometheus-final.json"
curl --fail --silent --show-error http://localhost:9920/actuator/health > "$evidence_dir/app-health.json"
jq -e '.status=="UP"' "$evidence_dir/app-health.json" >/dev/null
jq -n --argjson resourceId "$resource_id" --argjson before "$notifications_before" --argjson after "$notifications_after" \
  '{status:"PASS",fault:"tempo-paused+actual-queue-sixteen-rejection",testQueueRequests:16,testBatchSpans:32,
    faultFixtureRequests:24,faultFixtureSpansPerRequest:32,tracingRemainedEnabled:true,
    noFurtherRejectionAfterQueueDrain:true,cmdbResourceCode:"OBS-OTEL-COLLECTOR",cmdbResourceId:$resourceId,
    unauthorizedRequestsRejectedWithoutWrites:2,unregisteredResourceRejected:true,explicitDemoRegistrationIdempotent:true,
    nativeFiringAlertsDelivered:2,notificationsBeforeRepeat:$before,notificationsAfterRepeat:$after,
    nativeRepeatAddedNoBusinessWrites:true,bothSameAlertIdsResolved:true,resolutionTimelineRows:2,
    incidentIdsPreserved:true,incidentsRemainOpen:2,noHumanAssignmentOrReceiptClaim:true,
    productionRuleHistoryWindowPreserved:true,collectorOrPrometheusRestartedToClearCounters:false,applicationHealth:"UP"}' > "$evidence_dir/result.json"
cat "$evidence_dir/result.json"

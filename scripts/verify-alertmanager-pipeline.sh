#!/usr/bin/env bash
set -euo pipefail

evidence_dir="target/alertmanager-it"
mkdir -p "$evidence_dir"
umask 077
secret_file="$(mktemp)"
export ALERTMANAGER_WEBHOOK_SECRET="$(openssl rand -hex 24)"
export ALERTMANAGER_TEST_SECRET_FILE="$secret_file"
printf '%s' "$ALERTMANAGER_WEBHOOK_SECRET" > "$secret_file"
chmod 0444 "$secret_file"
export COMPOSE_PROJECT_NAME="opspilot-alertmanager-it-${GITHUB_RUN_ID:-local-$$}"
compose=(docker compose -f docker-compose.yml -f integration/alertmanager/docker-compose.yml --profile alertmanager-test)

cleanup() {
  "${compose[@]}" logs --no-color > "$evidence_dir/compose.log" 2>&1 || true
  "${compose[@]}" down --volumes --remove-orphans >/dev/null 2>&1 || true
  rm -f -- "$secret_file"
}
trap cleanup EXIT

wait_http() {
  local url="$1"
  local description="$2"
  for _ in {1..60}; do
    if curl --fail --silent "$url" >/dev/null 2>&1; then return 0; fi
    sleep 2
  done
  echo "Timed out waiting for $description" >&2
  return 1
}

mysql_value() {
  "${compose[@]}" exec -T -e MYSQL_PWD=opspilot-local mysql \
    mysql --batch --skip-column-names -uopspilot opspilot -e "$1" | tr -d '\r'
}

wait_value() {
  local expected="$1"
  local query="$2"
  local description="$3"
  local actual=""
  for _ in {1..45}; do
    actual="$(mysql_value "$query" 2>/dev/null || true)"
    if [[ "$actual" == "$expected" ]]; then return 0; fi
    sleep 2
  done
  echo "Timed out waiting for $description: expected '$expected', got '$actual'" >&2
  return 1
}

post_alerts() {
  curl --fail --silent --show-error \
    -H 'Content-Type: application/json' -d "$1" \
    http://localhost:9093/api/v2/alerts >/dev/null
}

"${compose[@]}" up --build --detach mysql opspilot alertmanager
wait_http http://localhost:9920/actuator/health "OpsPilot"
wait_http http://localhost:9093/-/ready "Alertmanager"

unauthorized_status="$(curl --silent --show-error -o "$evidence_dir/unauthorized.json" \
  -w '%{http_code}' -H 'Content-Type: application/json' \
  -d '{"version":"4","alerts":[]}' \
  http://localhost:9900/api/v1/integrations/alertmanager/webhook)"
[[ "$unauthorized_status" == "401" ]]

nonce="$(date +%s)-$RANDOM"
firing_name="OpsPilotPipeline${nonce}"
batch_name="OpsPilotBatch${nonce}"
sentinel="alertmanager-private-${nonce}"
started_at="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
future_at="$(date -u -d '+5 minutes' '+%Y-%m-%dT%H:%M:%SZ')"

firing_payload="$(jq -nc --arg name "$firing_name" --arg start "$started_at" --arg end "$future_at" \
  '[{labels:{alertname:$name,resource_code:"APP-PORTAL",severity:"warning"},
     annotations:{summary:"OpsPilot Alertmanager pipeline smoke"},
     startsAt:$start,endsAt:$end}]')"
post_alerts "$firing_payload"
wait_value 'FIRING:1' \
  "SELECT CONCAT(status, ':', occurrence_count) FROM alert_event WHERE source = 'alertmanager' AND title = '$firing_name'" \
  "firing webhook and single alert"

resolved_at="$(date -u -d '-1 second' '+%Y-%m-%dT%H:%M:%SZ')"
resolved_payload="$(jq -nc --arg name "$firing_name" --arg start "$started_at" --arg end "$resolved_at" \
  '[{labels:{alertname:$name,resource_code:"APP-PORTAL",severity:"warning"},
     annotations:{summary:"OpsPilot Alertmanager pipeline smoke"},
     startsAt:$start,endsAt:$end}]')"
post_alerts "$resolved_payload"
wait_value 'RESOLVED:1' \
  "SELECT CONCAT(status, ':', occurrence_count) FROM alert_event WHERE source = 'alertmanager' AND title = '$firing_name'" \
  "resolved webhook without duplicate alert"
wait_value '1' \
  "SELECT COUNT(*) FROM incident_timeline t JOIN alert_event a ON a.incident_id = t.incident_id WHERE a.source = 'alertmanager' AND a.title = '$firing_name' AND t.event_type = 'ALERT_RESOLVED' AND t.evidence_ref = CONCAT('alert:', a.id)" \
  "single resolved timeline event"

batch_payload="$(jq -nc --arg name "$batch_name" --arg start "$started_at" --arg end "$future_at" --arg sentinel "$sentinel" \
  '[{labels:{alertname:$name,resource_code:"APP-AUTH",severity:"critical"},
     annotations:{summary:"valid batch member"},startsAt:$start,endsAt:$end},
    {labels:{alertname:$name,resource_code:"APP-AM-IT-MISSING",severity:"warning"},
     annotations:{description:("authorization=Bearer " + $sentinel)},startsAt:$start,endsAt:$end}]')"
post_alerts "$batch_payload"
wait_value '1' \
  "SELECT COUNT(*) FROM alert_event WHERE source = 'alertmanager' AND title = '$batch_name'" \
  "valid member of mixed webhook"
wait_value '1' \
  "SELECT COUNT(*) FROM alert_ingest_rejection WHERE source = 'alertmanager' AND alert_name = '$batch_name' AND status = 'OPEN' AND error_code = 'RESOURCE_NOT_FOUND' AND redacted_fields > 0" \
  "redacted rejected member of mixed webhook"
wait_value '0' \
  "SELECT COUNT(*) FROM alert_ingest_rejection WHERE alert_name = '$batch_name' AND payload_json LIKE '%$sentinel%'" \
  "no sensitive sentinel in retained payload"

mysql_value "SELECT status, occurrence_count, external_event_id FROM alert_event WHERE source = 'alertmanager' AND title = '$firing_name'; SELECT status, error_code, delivery_count, redacted_fields FROM alert_ingest_rejection WHERE alert_name = '$batch_name'" \
  > "$evidence_dir/final-db.txt"
curl --fail --silent --show-error http://localhost:9093/api/v2/status > "$evidence_dir/alertmanager-status.json"
echo "PASS: real Alertmanager v0.34.1 delivered firing, resolved, and mixed valid/rejected alerts to OpsPilot"

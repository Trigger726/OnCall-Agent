#!/usr/bin/env bash
set -euo pipefail

evidence_dir="target/service-metrics-it"
mkdir -p "$evidence_dir"
umask 077
secret_file="$(mktemp)"
export ALERTMANAGER_WEBHOOK_SECRET="$(openssl rand -hex 24)"
export ALERTMANAGER_TEST_SECRET_FILE="$secret_file"
printf '%s' "$ALERTMANAGER_WEBHOOK_SECRET" > "$secret_file"
chmod 0444 "$secret_file"
export COMPOSE_PROJECT_NAME="opspilot-service-metrics-it-${GITHUB_RUN_ID:-local-$$}"
compose=(docker compose -f docker-compose.yml
  -f integration/alertmanager/docker-compose.yml
  -f integration/service-metrics/docker-compose.yml
  --profile alertmanager-test --profile service-metrics-test)
prometheus_url="http://localhost:9092"

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
  for _ in {1..60}; do
    actual="$(mysql_value "$query" 2>/dev/null || true)"
    if [[ "$actual" == "$expected" ]]; then return 0; fi
    sleep 2
  done
  echo "Timed out waiting for $description: expected '$expected', got '$actual'" >&2
  return 1
}

prometheus_query() {
  curl --fail --silent --show-error --get --data-urlencode "query=$1" \
    "$prometheus_url/api/v1/query"
}

wait_scraped_metric() {
  local query="$1"
  local output="$2"
  for _ in {1..45}; do
    if prometheus_query "$query" > "$output" \
      && jq -e 'any(.data.result[]?; (.["value"][1] | tonumber) >= 1)' "$output" >/dev/null; then
      return 0
    fi
    sleep 2
  done
  echo "Timed out waiting for scraped metric: $query" >&2
  return 1
}

wait_alert_state() {
  local expected="$1"
  local output="$evidence_dir/prometheus-${expected}.json"
  for _ in {1..75}; do
    if curl --fail --silent "$prometheus_url/api/v1/alerts" > "$output" \
      && jq -e --arg expected "$expected" '
        if $expected == "firing" then
          any(.data.alerts[]?; .labels.alertname == "OpsPilotUnauthorizedBurst" and .state == "firing")
        else
          all(.data.alerts[]?; .labels.alertname != "OpsPilotUnauthorizedBurst"
            or (.state != "firing" and .state != "pending"))
        end
      ' "$output" >/dev/null; then return 0; fi
    sleep 2
  done
  echo "Timed out waiting for Prometheus alert state $expected" >&2
  return 1
}

unauthorized_request() {
  local status
  status="$(curl --silent --show-error -o /dev/null -w '%{http_code}' \
    http://localhost:9900/api/v1/incidents)"
  [[ "$status" == "401" ]] || { echo "Expected 401, got $status" >&2; return 1; }
}

"${compose[@]}" up --build --detach mysql opspilot alertmanager prometheus-service-test
wait_http http://localhost:9920/actuator/health "OpsPilot"
wait_http http://localhost:9093/-/ready "Alertmanager"
wait_http "$prometheus_url/-/ready" "Prometheus service metrics test"

curl --fail --silent --show-error "$prometheus_url/api/v1/rules?type=alert" \
  > "$evidence_dir/prometheus-rules.json"
jq -e 'any(.data.groups[].rules[]?; .name == "OpsPilotUnauthorizedBurst")' \
  "$evidence_dir/prometheus-rules.json" >/dev/null
wait_scraped_metric 'up{job="opspilot"}' "$evidence_dir/prometheus-target-up.json"

unauthorized_request
wait_scraped_metric 'http_server_requests_seconds_count{job="opspilot",status="401"}' \
  "$evidence_dir/prometheus-http-baseline.json"
for _ in {1..12}; do unauthorized_request; done
wait_alert_state firing
wait_value 'FIRING:1' \
  "SELECT CONCAT(status, ':', occurrence_count) FROM alert_event WHERE source = 'alertmanager' AND title = 'OpsPilotUnauthorizedBurst'" \
  "service HTTP metric delivered as an Alertmanager firing alert"
alert_id="$(mysql_value "SELECT id FROM alert_event WHERE source = 'alertmanager' AND title = 'OpsPilotUnauthorizedBurst'")"
[[ "$alert_id" =~ ^[0-9]+$ ]]

wait_alert_state inactive
wait_value 'RESOLVED:1' \
  "SELECT CONCAT(status, ':', occurrence_count) FROM alert_event WHERE id = $alert_id" \
  "same alert resolved after the HTTP metric window clears"
wait_value '1' \
  "SELECT COUNT(*) FROM incident_timeline WHERE event_type = 'ALERT_RESOLVED' AND evidence_ref = 'alert:$alert_id'" \
  "one durable resolved timeline event"
wait_value '0' \
  "SELECT COUNT(*) FROM alert_ingest_rejection WHERE source = 'alertmanager' AND alert_name = 'OpsPilotUnauthorizedBurst'" \
  "no rejected deliveries"

mysql_value "SELECT id, status, occurrence_count, external_event_id FROM alert_event WHERE id = $alert_id; SELECT event_type, evidence_ref FROM incident_timeline WHERE evidence_ref = 'alert:$alert_id'" \
  > "$evidence_dir/final-db.txt"
echo "PASS: real OpsPilot HTTP metric triggered and resolved a Prometheus rule through Alertmanager"

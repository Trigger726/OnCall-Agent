#!/usr/bin/env bash
set -euo pipefail

evidence_dir="target/prometheus-alerting-it"
mkdir -p "$evidence_dir"
umask 077
secret_file="$(mktemp)"
export ALERTMANAGER_WEBHOOK_SECRET="$(openssl rand -hex 24)"
export ALERTMANAGER_TEST_SECRET_FILE="$secret_file"
printf '%s' "$ALERTMANAGER_WEBHOOK_SECRET" > "$secret_file"
chmod 0444 "$secret_file"
export COMPOSE_PROJECT_NAME="opspilot-prom-rule-it-${GITHUB_RUN_ID:-local-$$}"
compose=(docker compose -f docker-compose.yml
  -f integration/alertmanager/docker-compose.yml
  -f integration/prometheus-alertmanager/docker-compose.yml
  --profile alertmanager-test --profile prometheus-rule-test)

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

wait_prometheus_alert() {
  local expected="$1"
  local output="$evidence_dir/prometheus-${expected}.json"
  for _ in {1..60}; do
    if curl --fail --silent http://localhost:9090/api/v1/alerts > "$output" \
      && jq -e --arg expected "$expected" '
        if $expected == "firing" then
          any(.data.alerts[]?; .labels.alertname == "OpsPilotRulePipeline" and .state == "firing")
        else
          all(.data.alerts[]?; .labels.alertname != "OpsPilotRulePipeline"
            or (.state != "firing" and .state != "pending"))
        end
      ' "$output" >/dev/null; then return 0; fi
    sleep 2
  done
  echo "Timed out waiting for Prometheus alert state $expected" >&2
  return 1
}

push_metric() {
  printf '# TYPE opspilot_rule_smoke gauge\nopspilot_rule_smoke %s\n' "$1" \
    | curl --fail --silent --show-error -X PUT --data-binary @- \
      http://localhost:9091/metrics/job/opspilot-rule-smoke >/dev/null
}

"${compose[@]}" up --build --detach mysql opspilot alertmanager pushgateway prometheus-rule-test
wait_http http://localhost:9900/actuator/health "OpsPilot"
wait_http http://localhost:9093/-/ready "Alertmanager"
wait_http http://localhost:9091/metrics "Pushgateway test fixture"
wait_http http://localhost:9090/-/ready "Prometheus"

curl --fail --silent --show-error 'http://localhost:9090/api/v1/rules?type=alert' \
  > "$evidence_dir/prometheus-rules.json"
jq -e 'any(.data.groups[].rules[]?; .name == "OpsPilotRulePipeline")' \
  "$evidence_dir/prometheus-rules.json" >/dev/null

push_metric 1
wait_prometheus_alert firing
wait_value 'FIRING:1' \
  "SELECT CONCAT(status, ':', occurrence_count) FROM alert_event WHERE source = 'alertmanager' AND title = 'OpsPilotRulePipeline'" \
  "Prometheus firing rule delivered through Alertmanager"
alert_id="$(mysql_value "SELECT id FROM alert_event WHERE source = 'alertmanager' AND title = 'OpsPilotRulePipeline'")"
[[ "$alert_id" =~ ^[0-9]+$ ]]

push_metric 0
wait_prometheus_alert inactive
wait_value 'RESOLVED:1' \
  "SELECT CONCAT(status, ':', occurrence_count) FROM alert_event WHERE id = $alert_id" \
  "same OpsPilot alert resolved after rule clears"
wait_value '1' \
  "SELECT COUNT(*) FROM incident_timeline WHERE event_type = 'ALERT_RESOLVED' AND evidence_ref = 'alert:$alert_id'" \
  "one durable resolved timeline event"
wait_value '0' \
  "SELECT COUNT(*) FROM alert_ingest_rejection WHERE source = 'alertmanager' AND alert_name = 'OpsPilotRulePipeline'" \
  "no rejected deliveries"

mysql_value "SELECT id, status, occurrence_count, external_event_id FROM alert_event WHERE id = $alert_id; SELECT event_type, evidence_ref FROM incident_timeline WHERE evidence_ref = 'alert:$alert_id'" \
  > "$evidence_dir/final-db.txt"
curl --fail --silent --show-error http://localhost:9093/api/v2/status \
  > "$evidence_dir/alertmanager-status.json"
echo "PASS: Prometheus evaluated and resolved a real rule through Alertmanager into one OpsPilot alert"

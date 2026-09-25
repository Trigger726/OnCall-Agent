#!/usr/bin/env bash
set -euo pipefail

evidence_dir="target/follow-up-notification-it"
mkdir -p "$evidence_dir"
umask 077
export FOLLOW_UP_NOTIFICATION_TOKEN="$(openssl rand -hex 24)"
export COMPOSE_PROJECT_NAME="opspilot-follow-up-notification-it-${GITHUB_RUN_ID:-local-$$}"
compose=(docker compose -f docker-compose.yml -f integration/follow-up-notification/docker-compose.yml --profile follow-up-notification-test)

cleanup() {
  "${compose[@]}" logs --no-color > "$evidence_dir/compose.log" 2>&1 || true
  "${compose[@]}" down --volumes --remove-orphans >/dev/null 2>&1 || true
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

"${compose[@]}" up --build --detach mysql opspilot follow-up-notification-receiver
wait_http http://localhost:9920/actuator/health "OpsPilot"
wait_http http://127.0.0.1:9911/health "separate notification receiver"

mysql_value "INSERT INTO incident_postmortem(id, incident_id, status, summary, customer_impact, root_cause, contributing_factors, lessons_learned, timeline_snapshot_json, evidence_refs_json, created_by, published_at) VALUES (801, 2, 'PUBLISHED', 'CI summary', 'CI impact', 'CI cause', 'CI factors', 'CI lessons', '[]', '[]', 3, CURRENT_TIMESTAMP); INSERT INTO postmortem_follow_up(id, postmortem_id, title, description, priority, status, owner_id, due_date, created_by) VALUES (901, 801, 'CI delivery follow-up', 'Test separate receiver', 'HIGH', 'OPEN', 2, DATE_SUB(CURDATE(), INTERVAL 2 DAY), 3);" >/dev/null

curl --fail --silent --show-error \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"OpsPilot@2026"}' \
  http://localhost:9900/api/v1/auth/login > "$evidence_dir/login-response.json"
token="$(jq -er '.data.accessToken' "$evidence_dir/login-response.json")"
rm "$evidence_dir/login-response.json"

curl --fail --silent --show-error -X POST \
  -H "Authorization: Bearer $token" \
  http://localhost:9900/api/v1/postmortem-follow-ups/escalations/run \
  > "$evidence_dir/first-scan.json"
jq -e '.data.createdEscalations == 1' "$evidence_dir/first-scan.json" >/dev/null
escalation_id="$(mysql_value 'SELECT id FROM postmortem_follow_up_escalation WHERE follow_up_id = 901')"

for _ in {1..60}; do
  state="$(mysql_value "SELECT CONCAT(status, ':', attempts, ':', COALESCE(last_http_status, 0)) FROM postmortem_follow_up_notification WHERE escalation_id = $escalation_id" 2>/dev/null || true)"
  if [[ "$state" == "DELIVERED:2:204" ]]; then break; fi
  sleep 2
done
[[ "$state" == "DELIVERED:2:204" ]] || { echo "Unexpected notification state: $state" >&2; exit 1; }

curl --fail --silent --show-error http://127.0.0.1:9911/records > "$evidence_dir/receiver-records.json"
jq -e --arg key "follow-up-escalation:$escalation_id" '
  length == 2 and
  .[0].status == 503 and .[1].status == 204 and
  all(.[]; .key == $key and .authValid == true and
    .payload.eventType == "FOLLOW_UP_OVERDUE" and
    .payload.followUpId == 901 and
    .payload.title == "CI delivery follow-up")
' "$evidence_dir/receiver-records.json" >/dev/null

curl --fail --silent --show-error -X POST \
  -H "Authorization: Bearer $token" \
  http://localhost:9900/api/v1/postmortem-follow-ups/escalations/run \
  > "$evidence_dir/repeated-scan.json"
jq -e '.data.createdEscalations == 0' "$evidence_dir/repeated-scan.json" >/dev/null
[[ "$(mysql_value 'SELECT COUNT(*) FROM postmortem_follow_up_notification')" == "1" ]]
sleep 3
curl --fail --silent --show-error http://127.0.0.1:9911/records > "$evidence_dir/receiver-records.json"
jq -e 'length == 2' "$evidence_dir/receiver-records.json" >/dev/null
mysql_value "SELECT status, attempts, last_http_status, delivered_at IS NOT NULL AS delivered FROM postmortem_follow_up_notification WHERE escalation_id = $escalation_id" > "$evidence_dir/final-db.txt"

echo "PASS: separate receiver got 503 then 204 with valid auth, stable key and one outbox row"

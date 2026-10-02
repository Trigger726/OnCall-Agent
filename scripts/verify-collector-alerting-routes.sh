#!/usr/bin/env bash
set -euo pipefail
out=target/collector-alerting-it/routes
mkdir -p "$out"
if [[ -n "${OPSPILOT_AMTOOL:-}" ]]; then
  amtool=("$OPSPILOT_AMTOOL")
  config=deploy/collector-alertmanager.yml
else
  amtool=(docker run --rm -v "$PWD/deploy:/etc/opspilot:ro" --entrypoint /bin/amtool prom/alertmanager:v0.34.1)
  config=/etc/opspilot/collector-alertmanager.yml
fi
"${amtool[@]}" check-config "$config" > "$out/config-check.log"
for name in CollectorTraceQueueHigh CollectorTraceEnqueueRejected; do
  "${amtool[@]}" config routes test --config.file="$config" --verify.receivers=opspilot-collector \
    "alertname=$name" job=otel-collector resource_code=OBS-OTEL-COLLECTOR > "$out/$name.log"
done
for entry in 'OtherAlert otel-collector OBS-OTEL-COLLECTOR' 'CollectorTraceQueueHigh other OBS-OTEL-COLLECTOR' 'CollectorTraceQueueHigh otel-collector APP-PORTAL' 'CollectorTraceEnqueueRejected otel-collector missing'; do
  read -r name job resource <<< "$entry"
  labels=("alertname=$name" "job=$job")
  [[ "$resource" == missing ]] || labels+=("resource_code=$resource")
  "${amtool[@]}" config routes test --config.file="$config" --verify.receivers=unmatched \
    "${labels[@]}" > "$out/unmatched-$job-$resource.log"
done
jq -n '{status:"PASS",mappedCollectorAlertsRouted:2,unrelatedOrUnmappedAlertsNotForwarded:4}' > "$out/result.json"
cat "$out/result.json"

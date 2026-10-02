#!/usr/bin/env bash
set -euo pipefail
source scripts/lib/collector-alertmanager-metrics.sh
out=target/collector-alerting-it/metric-assertions
mkdir -p "$out"
# Actual pinned-source default labels; optional feature uses receiver_name,
# never receiver. Use no live calls in this regression.
samples=$'alertmanager_notifications_total{integration="webhook"} 4\nalertmanager_notifications_total{integration="email"} 90\nalertmanager_notifications_total_extra{integration="webhook"} 900\nalertmanager_notifications_failed_total{integration="webhook",reason="timeout"} 0\nalertmanager_notification_requests_failed_total{integration="webhook"} 0\n'
[[ "$(alertmanager_webhook_metric alertmanager_notifications_total <(printf '%s' "$samples"))" == 4 ]]
[[ "$(alertmanager_webhook_metric alertmanager_notifications_failed_total <(printf '%s' "$samples"))" == 0 ]]
[[ "$(alertmanager_webhook_metric alertmanager_notification_requests_failed_total <(printf '%s' "$samples"))" == 0 ]]
optional=$'alertmanager_notifications_total{integration="webhook",receiver_name="opspilot-collector"} 4\n'
[[ "$(alertmanager_webhook_metric alertmanager_notifications_total <(printf '%s' "$optional"))" == 4 ]]
if alertmanager_webhook_metric alertmanager_notifications_total /dev/null; then exit 1; fi
if alertmanager_webhook_metric invented /dev/null; then exit 1; fi
printf '%s' "$samples" > "$out/default.prom"
[[ "$(alertmanager_webhook_attempts_without_failures "$out/default.prom")" == 4 ]]
failed_notification="${samples/timeout\"\} 0/timeout\"\} 1}"
failed_request="${samples/alertmanager_notification_requests_failed_total\{integration=\"webhook\"\} 0/alertmanager_notification_requests_failed_total\{integration=\"webhook\"\} 1}"
printf '%s' "$failed_notification" > "$out/failed-notification.prom"
printf '%s' "$failed_request" > "$out/failed-request.prom"
printf '%s' "$optional" > "$out/missing-failure-series.prom"
if result="$(alertmanager_webhook_attempts_without_failures "$out/failed-notification.prom")"; then exit 1; fi
if result="$(alertmanager_webhook_attempts_without_failures "$out/failed-request.prom")"; then exit 1; fi
if result="$(alertmanager_webhook_attempts_without_failures "$out/missing-failure-series.prom")"; then exit 1; fi
jq -n '{status:"PASS",checks:10,defaultIntegrationOnlyLabelsVerified:true,missingSeriesRejected:true,unrelatedIntegrationAndSuffixIgnored:true,failedDeliveryRejectedInsideConditionalSubstitution:true}' > "$out/result.json"
cat "$out/result.json"

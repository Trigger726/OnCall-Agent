#!/usr/bin/env bash
set -euo pipefail
source scripts/lib/verify-collector-restart.sh
evidence_dir=target/tracing-it/metric-assertions
mkdir -p "$evidence_dir"
# Unmodified fresh-process response from failed CI run 37002043112.
fixture=integration/tracing/fixtures/collector-fresh-metrics.prom
[[ "$(collector_counter otelcol_receiver_accepted_spans "$fixture")" == 0 ]]
[[ "$(collector_counter otelcol_exporter_enqueue_failed_spans "$fixture")" == 0 ]]
[[ "$(collector_metric otelcol_exporter_queue_capacity "$fixture")" == 2048 ]]
[[ "$(collector_metric otelcol_exporter_queue_size "$fixture")" == 0 ]]
if collector_metric otelcol_exporter_in_flight_requests "$fixture"; then
  echo 'Missing gauge incorrectly accepted' >&2; exit 1
fi
if collector_counter otelcol_exporter_queue_size /dev/null; then
  echo 'Gauge incorrectly treated as a lazy counter' >&2; exit 1
fi
samples=$'otelcol_receiver_accepted_spans{receiver="otlp",transport="grpc"} 3\notelcol_receiver_accepted_spans{receiver="otlp",transport="http"} 5\notelcol_receiver_accepted_spans_total 900\n'
[[ "$(collector_counter otelcol_receiver_accepted_spans <(printf '%s' "$samples"))" == 8 ]]
[[ "$(collector_metric otelcol_receiver_accepted_spans <(printf '%s' "$samples"))" == 8 ]]
if collector_metric otelcol_exporter_queue_capacity /dev/null; then
  echo 'Missing queue capacity incorrectly accepted' >&2; exit 1
fi
jq -n '{status:"PASS",checks:9,freshProcessCountersStartAtZero:true,missingGaugesRejected:true,exactMetricNameAndMultiSeriesSumVerified:true}' > "$evidence_dir/result.json"
cat "$evidence_dir/result.json"

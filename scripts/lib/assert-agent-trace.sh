#!/usr/bin/env bash
# Explicit failure returns are required: callers use these functions in && / if.
assert_agent_trace() {
  local trace_file="$1"
  local spans_file="$2"
  local sensitive_sentinel="$3"

  jq '[.. | objects | select(has("traceId") and has("spanId") and has("name")) |
    {traceId, spanId, parentSpanId, name, attributes}]' \
    "$trace_file" > "$spans_file" || return 1
  jq -e 'length > 0 and ([.[].traceId] | unique | length) == 1
    and ([.[].spanId] | unique | length) == length' "$spans_file" >/dev/null || return 1
  jq -e '[.[] | select(.name == "opspilot.agent.run")] | length == 1' "$spans_file" >/dev/null || return 1
  jq -e '[.[] | select(.name == "opspilot.agent.tool")] | length == 6' "$spans_file" >/dev/null || return 1
  jq -e '[.[] | select(.name == "opspilot.provider.query")] | length == 2' "$spans_file" >/dev/null || return 1
  jq -e '
    ([.[] | select(.name == "opspilot.agent.run")][0].spanId) as $run
    | $run != null
      and ([.[] | select(.name == "opspilot.agent.run")][0].parentSpanId | length > 0)
      and ([.[] | select(.name == "opspilot.agent.tool") | .parentSpanId] | all(. == $run))
  ' "$spans_file" >/dev/null || return 1
  jq -e '
    [.[] | select(.name == "opspilot.agent.tool") | .spanId] as $tools
    | [.[] | select(.name == "opspilot.provider.query") | .parentSpanId]
    | all(. as $parent | $tools | index($parent) != null)
  ' "$spans_file" >/dev/null || return 1

  if grep --fixed-strings --quiet "$sensitive_sentinel" "$trace_file"; then
    echo "Sensitive alert payload leaked into exported trace" >&2
    return 1
  fi
}

assert_cross_service_trace() {
  local trace_file="$1"
  local spans_file="$2"

  jq '[.batches[] as $batch
    | ($batch.resource.attributes
       | map(select(.key == "service.name") | .value.stringValue)
       | first // "unknown") as $service
    | $batch.scopeSpans[].spans[]
    | {service:$service, traceId, spanId, parentSpanId, name, kind}]' \
    "$trace_file" > "$spans_file" || return 1
  jq -e '
    [.[] | select(.service == "opspilot" and .name == "opspilot.provider.query")] as $providers
    | [.[] | select(.service == "opspilot" and .kind == "SPAN_KIND_CLIENT")
       | select(.parentSpanId as $parent | $providers | any(.spanId == $parent))] as $clients
    | [.[] | select(.service == "trace-provider-fixture" and .kind == "SPAN_KIND_SERVER")] as $servers
    | ($providers | length) == 2
      and ($clients | length) == 2
      and ($servers | length) == 2
      and ([$clients[].parentSpanId] | sort) == ([$providers[].spanId] | sort)
      and ([$servers[].parentSpanId] | sort) == ([$clients[].spanId] | sort)
      and ([$servers[].name] | sort) == (["http get /api/v1/query", "http get /loki/api/v1/query_range"] | sort)
      and ([$providers[].traceId, $clients[].traceId, $servers[].traceId] | unique | length) == 1
  ' "$spans_file" >/dev/null || return 1
}

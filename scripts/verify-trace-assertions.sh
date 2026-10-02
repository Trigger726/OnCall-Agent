#!/usr/bin/env bash
set -euo pipefail
source scripts/lib/assert-agent-trace.sh
evidence_dir=target/tracing-it/assertions
mkdir -p "$evidence_dir"
jq -n '
  def span($id; $parent; $name; $kind):
    {traceId:"same-trace",spanId:$id,parentSpanId:$parent,name:$name,kind:$kind,attributes:[]};
  {batches:[
    {resource:{attributes:[{key:"service.name",value:{stringValue:"opspilot"}}]},scopeSpans:[{spans:[
      span("run";"http";"opspilot.agent.run";"SPAN_KIND_INTERNAL"),
      (range(0;6) | span("tool-\(.)";"run";"opspilot.agent.tool";"SPAN_KIND_INTERNAL")),
      span("provider-0";"tool-0";"opspilot.provider.query";"SPAN_KIND_INTERNAL"),
      span("provider-1";"tool-1";"opspilot.provider.query";"SPAN_KIND_INTERNAL"),
      span("client-0";"provider-0";"http get";"SPAN_KIND_CLIENT"),
      span("client-1";"provider-1";"http get";"SPAN_KIND_CLIENT")
    ]}]},
    {resource:{attributes:[{key:"service.name",value:{stringValue:"trace-provider-fixture"}}]},scopeSpans:[{spans:[
      span("server-0";"client-0";"http get /api/v1/query";"SPAN_KIND_SERVER"),
      span("server-1";"client-1";"http get /loki/api/v1/query_range";"SPAN_KIND_SERVER")
    ]}]}
  ]}' > "$evidence_dir/valid.json"
assert_agent_trace "$evidence_dir/valid.json" "$evidence_dir/valid-agent.json" private-sentinel
assert_cross_service_trace "$evidence_dir/valid.json" "$evidence_dir/valid-cross.json"
reject_count=0
expect_rejected() {
  local name="$1" transform="$2"
  jq "$transform" "$evidence_dir/valid.json" > "$evidence_dir/$name.json"
  # Deliberately call in a conditional: reproduces the set -e suppression bug.
  if assert_agent_trace "$evidence_dir/$name.json" "$evidence_dir/$name-agent.json" private-sentinel \
      && assert_cross_service_trace "$evidence_dir/$name.json" "$evidence_dir/$name-cross.json"; then
    echo "Broken trace incorrectly accepted: $name" >&2
    exit 1
  fi
  reject_count=$((reject_count + 1))
  printf 'Rejected broken trace: %s\n' "$name"
}
expect_rejected missing-run '(.batches[0].scopeSpans[0].spans) |= map(select(.spanId != "run"))'
expect_rejected missing-tool '(.batches[0].scopeSpans[0].spans) |= map(select(.spanId != "tool-5"))'
expect_rejected missing-provider '(.batches[0].scopeSpans[0].spans) |= map(select(.spanId != "provider-1"))'
expect_rejected detached-run '(.batches[0].scopeSpans[0].spans[] | select(.spanId == "run").parentSpanId) = ""'
expect_rejected wrong-tool-parent '(.batches[0].scopeSpans[0].spans[] | select(.spanId == "tool-0").parentSpanId) = "wrong"'
expect_rejected wrong-provider-parent '(.batches[0].scopeSpans[0].spans[] | select(.spanId == "provider-0").parentSpanId) = "wrong"'
expect_rejected missing-client '(.batches[0].scopeSpans[0].spans) |= map(select(.spanId != "client-1"))'
expect_rejected wrong-client-parent '(.batches[0].scopeSpans[0].spans[] | select(.spanId == "client-0").parentSpanId) = "wrong"'
expect_rejected missing-server '(.batches[1].scopeSpans[0].spans) |= map(select(.spanId != "server-1"))'
expect_rejected wrong-server-parent '(.batches[1].scopeSpans[0].spans[] | select(.spanId == "server-0").parentSpanId) = "wrong"'
expect_rejected different-trace '(.batches[1].scopeSpans[0].spans[] | select(.spanId == "server-0").traceId) = "different-trace"'
expect_rejected different-tool-trace '(.batches[0].scopeSpans[0].spans[] | select(.spanId == "tool-0").traceId) = "different-trace"'
expect_rejected duplicate-span-id '(.batches[0].scopeSpans[0].spans[] | select(.spanId == "tool-1").spanId) = "tool-0"'
expect_rejected wrong-server-endpoint '(.batches[1].scopeSpans[0].spans[] | select(.spanId == "server-0").name) = "http get /wrong"'
expect_rejected sensitive-payload '.batches[0].resource.attributes += [{key:"private",value:{stringValue:"private-sentinel"}}]'
jq -n --argjson rejected "$reject_count" '{status:"PASS",validGraphAccepted:true,brokenGraphsRejected:$rejected,conditionalInvocationVerified:true}' > "$evidence_dir/result.json"
cat "$evidence_dir/result.json"

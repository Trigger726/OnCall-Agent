const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const root = process.argv[2] || 'target/cp63-alerting-verified';
const raw = name => fs.readFileSync(path.join(root, name), 'utf8');
const json = name => JSON.parse(raw(name));
const result = json('result.json');
assert.equal(result.status, 'PASS');
const firing = json('firing-alerts.json');
assert.equal(firing.length, 2);
assert.equal(raw('firing-alerts.json'), raw('repeated-alerts.json'));
assert.equal(new Set(firing.map(a => a.id)).size, 2);
assert.equal(new Set(firing.map(a => a.incidentId)).size, 2);
for (const a of firing) {
  assert.equal(a.status, 'FIRING');
  assert.equal(a.occurrences, 1);
  assert.equal(a.severity, a.title === 'CollectorTraceQueueHigh' ? 'P2' : 'P1');
  assert.ok(a.incidentId && a.externalEventId && a.updatedAt && a.lastOccurredAt);
}
const resolved = json('resolved-alerts.json');
assert.equal(resolved.length, 2);
for (const a of resolved) {
  const previous = firing.find(f => f.id === a.id);
  assert.ok(previous);
  for (const key of ['id', 'incidentId', 'externalEventId', 'occurrences', 'title', 'severity']) assert.equal(a[key], previous[key]);
  assert.equal(a.status, 'RESOLVED');
}
const resource = json('registered-resource.json');
assert.equal(resource.length, 1);
assert.equal(resource[0].id, result.cmdbResourceId);
assert.equal(resource[0].code, 'OBS-OTEL-COLLECTOR');
assert.equal(resource[0].type, 'MIDDLEWARE');
assert.equal(resource[0].environment, 'DEVELOPMENT');
assert.equal(resource[0].ownerUserId, null);
const incidents = json('final-incidents.json');
assert.equal(incidents.length, 2);
for (const i of incidents) {
  assert.ok(firing.some(a => a.incidentId === i.id));
  assert.equal(i.resourceId, resource[0].id);
  assert.equal(i.status, 'OPEN');
  for (const key of ['assigneeId', 'acknowledgedAt', 'resolvedAt']) assert.equal(i[key], null);
}
const timeline = json('final-timeline.json');
assert.equal(timeline.length, 2);
for (const a of firing) {
  const rows = timeline.filter(t => t.evidenceRef === `alert:${a.id}`);
  assert.equal(rows.length, 1);
  assert.equal(rows[0].eventType, 'ALERT_RESOLVED');
  assert.equal(rows[0].incidentId, a.incidentId);
}
const rejected = json('unregistered-rejections.json');
assert.ok(rejected.length > 0);
for (const r of rejected) {
  assert.equal(r.error, 'RESOURCE_NOT_FOUND');
  assert.equal(r.status, 'OPEN');
  const final = json('final-rejections.json').find(f => f.id === r.id);
  assert.equal(final.status, 'SUCCEEDED');
  assert.ok(firing.some(a => a.title === r.alertName && a.id === final.resolvedAlertId && a.incidentId === final.resolvedIncidentId));
}
for (const name of ['auth-missing.json', 'auth-wrong.json']) assert.equal(json(name).error.code, 'ALERTMANAGER_WEBHOOK_UNAUTHORIZED');
function metric(file, name, label) {
  const rows = raw(file).split(/\r?\n/).filter(r => r.startsWith(`${name}{`) && (!label || r.includes(label)));
  assert.ok(rows.length, `Missing actual metric: ${name}`);
  return rows.reduce((sum, r) => {
    const value = Number(r.split(/\s+/)[1]);
    assert.ok(Number.isFinite(value) && value >= 0);
    return sum + value;
  }, 0);
}
const before = metric('notifications-before.txt', 'alertmanager_notifications_total', 'integration="webhook"');
const after = metric('notifications-after.txt', 'alertmanager_notifications_total', 'integration="webhook"');
assert.ok(after >= before + 2);
for (const f of ['notifications-before.txt', 'notifications-after.txt']) {
  for (const m of ['alertmanager_notifications_failed_total', 'alertmanager_notification_requests_failed_total']) assert.equal(metric(f, m, 'integration="webhook"'), 0);
}
assert.equal(metric('metrics-saturated.txt', 'otelcol_exporter_queue_size'), 16);
assert.equal(metric('metrics-saturated.txt', 'otelcol_exporter_queue_capacity'), 16);
assert.ok(metric('metrics-saturated.txt', 'otelcol_exporter_enqueue_failed_spans') > 0);
for (const m of ['otelcol_exporter_queue_size', 'otelcol_exporter_in_flight_requests']) assert.equal(metric('metrics-drained.txt', m), 0);
assert.equal(metric('metrics-final.txt', 'otelcol_exporter_enqueue_failed_spans'), metric('metrics-drained.txt', 'otelcol_exporter_enqueue_failed_spans'));
const fixtureFiles = fs.readdirSync(root).filter(n => /^fault-[0-9a-f]{32}-otlp\.json$/.test(n));
assert.equal(fixtureFiles.length, 24);
for (const name of fixtureFiles) {
  const spans = json(name).resourceSpans[0].scopeSpans[0].spans;
  assert.equal(spans.length, 32);
  assert.equal(new Set(spans.map(s => s.spanId)).size, 32);
  assert.ok(spans.every(s => s.traceId === name.slice(6, 38) && /^[0-9a-f]{16}$/.test(s.spanId)));
  assert.equal(raw(name.replace('-otlp.json', '-http-status.txt')).trim(), '200');
}
for (const name of ['CollectorTraceQueueHigh', 'CollectorTraceEnqueueRejected']) assert.ok(json('prometheus-firing.json').data.alerts.some(a => a.labels.alertname === name && a.labels.resource_code === resource[0].code && a.state === 'firing'));
assert.ok(json('prometheus-after-queue-recovery.json').data.alerts.some(a => a.labels.alertname === 'CollectorTraceEnqueueRejected' && a.state === 'firing'));
assert.ok(!json('prometheus-final.json').data.alerts.some(a => ['CollectorTraceQueueHigh', 'CollectorTraceEnqueueRejected'].includes(a.labels.alertname) && a.state === 'firing'));
assert.equal(json('app-health.json').status, 'UP');
const proof = { status: 'PASS', actualNativeAlerts: firing.map(a => ({ title: a.title, alertId: a.id, incidentId: a.incidentId, externalEventId: a.externalEventId })),
  notificationsBefore: before, notificationsAfter: after, repeatedRawDatabaseSnapshotUnchanged: true, sameIdsAndLifecycleKeysResolved: true,
  rejectionIdsRecovered: rejected.map(r => r.id), resolutionTimelineRows: timeline.length, incidentsRemainOpen: incidents.length,
  missingMetricNeverTreatedAsZero: true, actualGaugeDrainAndHistoricalFiringVerified: true,
  rejectionCounterStableUntilResolved: true, actualFaultRequests: fixtureFiles.length, spansPerFaultRequest: 32 };
fs.mkdirSync('target', { recursive: true });
fs.writeFileSync('target/cp63-alerting-replay.json', JSON.stringify(proof, null, 2) + '\n');
console.log(JSON.stringify(proof, null, 2));

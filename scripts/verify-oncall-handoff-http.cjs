const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { randomUUID } = require('node:crypto');
const root = process.env.OPSPILOT_BASE_URL || 'http://127.0.0.1:9917';
assert.equal(process.env.OPSPILOT_ACCEPTANCE_ISOLATED, '1', 'Handoff HTTP acceptance requires an owned isolated database');
assert.ok(['localhost', '127.0.0.1'].includes(new URL(root).hostname));
assert.notEqual(new URL(root).port, '9900');
const out = fs.mkdtempSync(path.join(process.env.OPSPILOT_EVIDENCE_DIR || require('node:os').tmpdir(), 'handoff-http-'));
const addHours = (value, hours) => new Date(new Date(value + 'Z').getTime() + hours * 3600000).toISOString().slice(0, 19);

(async () => {
  const api = async (token, route, body, expected = 200) => {
    const r = await fetch(root + '/api/v1' + route, {
      method: body ? 'POST' : 'GET',
      headers: { ...(token ? { Authorization: 'Bearer ' + token } : {}), ...(body ? { 'Content-Type': 'application/json' } : {}) },
      body: body ? JSON.stringify(body) : undefined, signal: AbortSignal.timeout(10000)
    });
    assert.equal(r.status, expected, `${route} returned unexpected HTTP status`);
    const result = await r.json();
    return result.data;
  };
  const login = async username => (await api(null, '/auth/login', { username, password: 'OpsPilot@2026' })).accessToken;
  const admin = await login('admin'), owner = await login('zhangwei'), target = await login('lina'), auditor = await login('auditor');
  const options = await api(admin, '/on-call/roster');
  const scheduleId = options.schedules[1].id;
  // Beyond rotation materialization and the prior coverage browser fixture.
  const startsAt = addHours(options.databaseNow, 25 * 24), endsAt = addHours(startsAt, 4);
  const historyPath = '/on-call/roster?from=2026-08-19T00%3A00&to=2026-08-21T00%3A00';
  const history = JSON.stringify((await api(admin, historyPath)).shifts);
  const source = await api(admin, '/on-call/shifts', { scheduleId, userId: 2, startsAt, endsAt, override: false, note: 'CP49 HTTP 原班次' });
  const draft = { sourceShiftId: source.id, sourceVersion: source.version, targetUserId: 3,
    requestKey: randomUUID(), startsAt, endsAt, reason: 'CP49 HTTP 定向接班' };
  await api(null, '/on-call/handoffs', draft, 401);
  await api(auditor, '/on-call/handoffs', draft, 403);
  const pending = await api(owner, '/on-call/handoffs', draft);
  assert.deepEqual(await api(owner, '/on-call/handoffs', draft), pending);
  await api(owner, '/on-call/handoffs', { ...draft, reason: '不匹配内容' }, 409);
  const decision = { version: pending.version, status: 'ACCEPTED', reason: '本人确认承担责任' };
  await api(owner, `/on-call/handoffs/${pending.id}/decisions`, decision, 403);
  await api(admin, `/on-call/handoffs/${pending.id}/decisions`, decision, 403);
  const accepted = await api(target, `/on-call/handoffs/${pending.id}/decisions`, decision);
  assert.equal(accepted.status, 'ACCEPTED');
  assert.equal(accepted.decidedBy, 3);
  assert.equal(accepted.version, 1);
  assert.deepEqual(await api(target, `/on-call/handoffs/${pending.id}/decisions`, decision), accepted);
  const window = `?scheduleId=${scheduleId}&from=${encodeURIComponent(startsAt)}&to=${encodeURIComponent(endsAt)}`;
  const covered = await api(auditor, '/on-call/coverage' + window);
  assert.equal(covered.coveredSeconds, 14400);
  assert.equal(covered.gapSeconds, 0);
  assert.equal(covered.segments.length, 1);
  assert.equal(covered.segments[0].userId, 3);
  const rows = (await api(admin, '/on-call/roster' + window)).shifts;
  assert.deepEqual(rows.find(s => s.id === source.id), source);
  const replacement = rows.find(s => s.id === accepted.replacementShiftId);
  assert.equal(replacement.override, true);
  assert.equal(replacement.userId, 3);
  await api(admin, `/on-call/shifts/${replacement.id}/cancel`, { version: replacement.version, reason: '取消覆盖，不复活' });
  assert.deepEqual(await api(target, `/on-call/handoffs/${pending.id}/decisions`, decision), accepted);
  const restored = await api(auditor, '/on-call/coverage' + window);
  assert.equal(restored.segments[0].userId, 2);
  assert.equal((await api(admin, '/on-call/roster' + window)).shifts.length, 2);

  const withdrawn = await api(owner, '/on-call/handoffs', { ...draft, requestKey: randomUUID() });
  await api(owner, `/on-call/handoffs/${withdrawn.id}/decisions`, { version: 0, status: 'WITHDRAWN', reason: '本人撤回' });
  await api(target, `/on-call/handoffs/${withdrawn.id}/decisions`, decision, 409);
  const rejected = await api(owner, '/on-call/handoffs', { ...draft, requestKey: randomUUID() });
  const refusal = { version: 0, status: 'REJECTED', reason: '本人无法接班' };
  await api(owner, `/on-call/handoffs/${rejected.id}/decisions`, refusal, 403);
  assert.equal((await api(target, `/on-call/handoffs/${rejected.id}/decisions`, refusal)).status, 'REJECTED');
  const list = await api(auditor, '/on-call/handoffs?scheduleId=' + scheduleId);
  assert.deepEqual(list.requests.filter(r => [pending.id, withdrawn.id, rejected.id].includes(r.id)).map(r => r.status), ['REJECTED', 'WITHDRAWN', 'ACCEPTED']);
  assert.equal(JSON.stringify((await api(admin, historyPath)).shifts), history);
  const result = { status: 'PASS', transport: 'real HTTP to packaged isolated JAR', handoffUiImplemented: false,
    scheduleId, sourceShiftId: source.id, requestId: accepted.id, replacementShiftId: replacement.id,
    startsAt, endsAt, acceptedCoverageUserId: 3, cancelledCoverageFallbackUserId: 2,
    participantPermissions: true, requestAndDecisionIdempotency: true, conflictingKeyRejected: true,
    withdrawalAndRejection: true, cancelledOverrideNotRevived: true, historicalDemoUnchanged: true,
    originalSourceUnchanged: true, tokensIncludedInEvidence: false };
  fs.writeFileSync(path.join(out, 'result.json'), JSON.stringify(result, null, 2));
  console.log(JSON.stringify(result));
})().catch(error => { console.error(error.message); process.exitCode = 1; });

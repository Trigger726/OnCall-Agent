const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { randomUUID } = require('node:crypto');

const root = process.env.OPSPILOT_BASE_URL || 'http://127.0.0.1:9917';
assert.equal(process.env.OPSPILOT_ACCEPTANCE_ISOLATED, '1', 'Revocation HTTP acceptance requires an owned isolated database');
assert.ok(['localhost', '127.0.0.1'].includes(new URL(root).hostname));
assert.notEqual(new URL(root).port, '9900');
const out = fs.mkdtempSync(path.join(process.env.OPSPILOT_EVIDENCE_DIR || require('node:os').tmpdir(), 'handoff-revocation-'));
const addHours = (value, hours) => new Date(new Date(value + 'Z').getTime() + hours * 3600000).toISOString().slice(0, 19);

(async () => {
  const api = async (token, route, body, expected = 200) => {
    const response = await fetch(root + '/api/v1' + route, { method: body ? 'POST' : 'GET',
      headers: { ...(token ? { Authorization: 'Bearer ' + token } : {}), ...(body ? { 'Content-Type': 'application/json' } : {}) },
      body: body ? JSON.stringify(body) : undefined, signal: AbortSignal.timeout(10000) });
    assert.equal(response.status, expected, `${route} unexpected status`);
    return (await response.json()).data;
  };
  const login = async name => (await api(null, '/auth/login', { username: name, password: 'OpsPilot@2026' })).accessToken;
  const admin = await login('admin'), owner = await login('zhangwei'), target = await login('lina'), auditor = await login('auditor');
  const options = await api(admin, '/on-call/roster');
  const scheduleId = options.schedules[1].id;
  const startsAt = addHours(options.databaseNow, 29 * 24), endsAt = addHours(startsAt, 4);
  const historyRoute = '/on-call/roster?from=2026-08-19T00%3A00&to=2026-08-21T00%3A00';
  const history = (await api(auditor, historyRoute)).shifts;
  const source = await api(admin, '/on-call/shifts', { scheduleId, userId: 2, startsAt, endsAt, override: false, note: 'CP53 保留的原班次' });
  const requested = await api(owner, '/on-call/handoffs', { sourceShiftId: source.id, sourceVersion: source.version,
    targetUserId: 3, requestKey: randomUUID(), startsAt, endsAt, reason: 'CP53 接班申请' });
  const decision = { version: 0, status: 'ACCEPTED', reason: '本人承担未来接班' };
  const accepted = await api(target, `/on-call/handoffs/${requested.id}/decisions`, decision);
  const route = `/on-call/handoffs/${accepted.id}/coverage`;
  const before = await api(auditor, route);
  assert.deepEqual(before.request, accepted);
  assert.equal(before.replacement.cancelledAt, null);
  assert.equal(before.revocation, null);
  const window = `?scheduleId=${scheduleId}&from=${encodeURIComponent(startsAt)}&to=${encodeURIComponent(endsAt)}`;
  assert.equal((await api(auditor, '/on-call/coverage' + window)).segments[0].userId, 3);
  const command = { handoffVersion: accepted.version, replacementVersion: before.replacement.version,
    operationKey: randomUUID(), reason: 'CP53 管理确认撤销覆盖，不改接受事实' };
  await api(null, route + '/revoke', command, 401);
  await api(owner, route + '/revoke', command, 403);
  await api(auditor, route + '/revoke', command, 403);
  await api(admin, route + '/revoke', { ...command, handoffVersion: 0 }, 409);
  await api(admin, route + '/revoke', { ...command, replacementVersion: 999 }, 409);
  const after = await api(admin, route + '/revoke', command);
  // Manual same-key retry; response clock can advance, persisted facts must not.
  const retried = await api(admin, route + '/revoke', command);
  for (const field of ['request', 'replacement', 'revocation']) assert.deepEqual(retried[field], after[field]);
  assert.deepEqual(after.request, accepted);
  assert.equal(after.revocation.actorId, 1);
  assert.equal(after.revocation.reason, command.reason);
  assert.equal(after.revocation.operationKey, command.operationKey);
  assert.equal(after.replacement.version, before.replacement.version + 1);
  assert.ok(after.replacement.cancelledAt);
  await api(admin, route + '/revoke', { ...command, reason: '另一意图' }, 409);
  await api(admin, route + '/revoke', { ...command, operationKey: randomUUID() }, 409);
  assert.deepEqual(await api(target, `/on-call/handoffs/${accepted.id}/decisions`, decision), accepted);
  const rows = (await api(auditor, '/on-call/roster' + window)).shifts;
  assert.deepEqual(rows.find(row => row.id === source.id), source);
  assert.equal(rows.length, 2);
  assert.equal((await api(auditor, '/on-call/coverage' + window)).segments[0].userId, 2);
  assert.deepEqual((await api(auditor, historyRoute)).shifts, history);
  const result = { status: 'PASS', transport: 'real HTTP to isolated packaged JAR',
    revocationUiImplemented: false, fixture: 'future 29-day window, not current P1 routing',
    scheduleId, sourceShiftId: source.id, requestId: accepted.id, replacementShiftId: accepted.replacementShiftId,
    acceptedFactUnchanged: true, replacementCancelled: true, revocationActorAndReasonRecorded: true,
    capturedVersionsEnforced: true, managementPermissionsEnforced: true, sameKeyManualRetrySameFacts: true,
    differentIntentRejected: true, originalShiftAndHistoricalDemoUnchanged: true,
    beforeCoverageUserId: 3, afterCoverageUserId: 2, tokensIncludedInEvidence: false };
  fs.writeFileSync(path.join(out, 'result.json'), JSON.stringify(result, null, 2));
  console.log(JSON.stringify(result));
})().catch(error => { console.error(error.message); process.exitCode = 1; });

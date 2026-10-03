const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const http = require('node:http');
const { spawn } = require('node:child_process');
const { randomUUID, randomBytes, createHash } = require('node:crypto');
const { setTimeout: delay } = require('node:timers/promises');
const { requireFreePort, stopProcess, waitForHealth, redact, unexpectedLogLines } = require('./verify-oncall-browser-ci.cjs');

async function verify() {
  const baseline = process.env.OPSPILOT_ASSISTANT_CROSS_NODE_BASELINE === '1';
  if (baseline && process.env.CI) throw new Error('Baseline capture cannot replace cross-node assistant acceptance');
  const root = path.resolve(__dirname, '..');
  const jar = path.join(root, baseline ? 'target/cp75-before/opspilot-cp74.jar' : 'target/opspilot-0.1.0-SNAPSHOT.jar');
  assert.ok(fs.existsSync(jar), 'Build or preserve the scoped JAR first');
  for (const port of [9965, 9966, 9967, 9968, 9970]) await requireFreePort(port);
  const parent = path.join(root, 'target/assistant-cross-node-it'); fs.mkdirSync(parent, { recursive: true });
  const evidence = fs.mkdtempSync(path.join(parent, 'run-'));
  const database = path.join(evidence, 'database'); fs.mkdirSync(database);
  const dbUrl = 'jdbc:h2:file:' + path.join(database, 'opspilot').replaceAll('\\', '/')
    + ';MODE=MySQL;DATABASE_TO_LOWER=TRUE;WRITE_DELAY=0;AUTO_SERVER=TRUE;AUTO_SERVER_PORT=9970';
  const secret = randomBytes(32).toString('hex');
  const FIRST = '  双节点原生事实🙂\n', LAST = '下一步验证。  ';
  const controls = new Map(), children = [], descriptors = [], logs = [];
  const result = { status: 'RUNNING', baselineCapture: baseline,
    fixture: 'two independent owned JVMs, shared H2 automatic mixed mode, real DashScope HTTP',
    jarSha256: createHash('sha256').update(fs.readFileSync(jar)).digest('hex'),
    applicationPorts: [9965, 9967], managementPorts: [9966, 9968], databaseServerPort: 9970,
    checkDelayMs: 100, executionBudgetSeconds: 20, tokensPersistedToEvidence: false,
    signingKeysPersistedToEvidence: false, userFileDatabaseModified: false, cases: [], startedPids: [], stoppedPids: [] };
  const completion = (content, finish_reason) => JSON.stringify({ request_id: randomUUID(), output: { choices: [
    { finish_reason, message: { role: 'assistant', content } }
  ] }, usage: { input_tokens: 1, output_tokens: 1, total_tokens: 2 } });
  function release(control) {
    control.released = true; const response = control.response;
    if (!response || response.destroyed || response.writableEnded) return;
    if (control.native) response.end('data:' + completion(LAST, 'stop') + '\n\n');
    else { response.writeHead(200, { 'Content-Type': 'application/json' }); response.end(completion(FIRST + LAST, 'stop')); }
  }
  const provider = http.createServer((request, response) => {
    if (request.method !== 'POST' || request.url !== '/api/v1/services/aigc/text-generation/generation') { response.writeHead(404); response.end(); return; }
    let raw = ''; request.on('data', chunk => { raw += chunk; }); request.once('end', () => {
      const body = JSON.parse(raw), question = body.input?.messages?.at(-1)?.content;
      const control = [...controls.values()].find(c => typeof question === 'string' && question.endsWith(c.question));
      if (!control) { response.writeHead(400); response.end(); return; }
      control.calls++; control.response = response; control.native = request.headers['x-dashscope-sse'] === 'enable';
      control.incremental = body.parameters?.incremental_output === true;
      response.once('close', () => { control.closed = true; });
      if (control.native) { response.writeHead(200, { 'Content-Type': 'text/event-stream' }); response.write('data:' + completion(FIRST, 'null') + '\n\n'); }
      control.entered = true; if (control.released) release(control);
    });
  });
  await new Promise((resolve, reject) => { provider.once('error', reject); provider.listen(0, '127.0.0.1', resolve); });
  const java = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java';
  const interrupt = () => { for (const child of children) if (child.exitCode === null && child.signalCode === null) child.kill('SIGTERM'); };
  process.once('SIGINT', interrupt); process.once('SIGTERM', interrupt);
  async function start(port, management) {
    const file = path.join(evidence, 'jar-' + (children.length + 1) + '.log'), descriptor = fs.openSync(file, 'w');
    descriptors.push(descriptor); logs.push(file);
    const providerUrl = 'http://127.0.0.1:' + provider.address().port;
    const child = spawn(java, ['-Duser.timezone=UTC', '-Dh2.bindAddress=127.0.0.1', '-jar', jar,
      '--server.address=127.0.0.1', '--server.port=' + port, '--management.server.address=127.0.0.1', '--management.server.port=' + management,
      '--spring.datasource.url=' + dbUrl, '--spring.datasource.username=sa', '--spring.datasource.password=', '--spring.h2.console.enabled=false',
      '--opspilot.ai.enabled=true', '--spring.ai.dashscope.api-key=cp76-controlled-not-a-real-key',
      '--spring.ai.dashscope.base-url=' + providerUrl, '--spring.ai.dashscope.chat.base-url=' + providerUrl,
      '--opspilot.assistant.workers=1', '--opspilot.assistant.queue-capacity=1', '--opspilot.assistant.execution-timeout=20s',
      '--opspilot.assistant.authorization-check-delay=100', '--opspilot.agent.recovery.enabled=false',
      '--opspilot.oncall.rotation.enabled=false', '--opspilot.oncall.escalation.enabled=false'],
      { cwd: root, env: { ...process.env, JWT_SECRET: secret }, stdio: ['ignore', descriptor, descriptor], windowsHide: true });
    child.once('error', error => { child.launchError = error; }); children.push(child); result.startedPids.push(child.pid);
    await waitForHealth('http://127.0.0.1:' + management + '/actuator/health', child);
  }
  async function until(predicate, budget = 4000) {
    const deadline = Date.now() + budget;
    do { if (await predicate()) return; await delay(25); } while (Date.now() < deadline);
    assert.fail('Cross-node controlled condition did not settle');
  }
  const url = (node, route) => 'http://127.0.0.1:' + (node === 'A' ? 9965 : 9967) + '/api/v1' + route;
  async function api(node, route, token, body, key, method = body ? 'POST' : 'GET') {
    const response = await fetch(url(node, route), { method, headers: { ...(token ? { Authorization: 'Bearer ' + token } : {}),
      ...(key ? { 'Idempotency-Key': key } : {}), ...(body ? { 'Content-Type': 'application/json' } : {}) },
      body: body ? JSON.stringify(body) : undefined, signal: AbortSignal.timeout(12000) });
    return { status: response.status, json: await response.json() };
  }
  async function login(node, username = 'admin') { const r = await api(node, '/auth/login', null, { username, password: 'OpsPilot@2026' }); assert.equal(r.status, 200); return r.json.data.accessToken; }
  async function session(token) { const r = await api('A', '/assistant/sessions', token, {}); assert.equal(r.status, 200); return r.json.data.session.id; }
  const route = id => '/assistant/sessions/' + id;
  const state = (node, id, token, key) => api(node, route(id) + '/request', token, undefined, key);
  const cancel = (node, id, token, key) => api(node, route(id) + '/request/cancel', token, undefined, key, 'POST');
  async function counts(node, id, token) {
    const r = await api(node, route(id), token); assert.equal(r.status, 200);
    return { user: r.json.data.messages.filter(m => m.role === 'USER').length, assistant: r.json.data.messages.filter(m => m.role === 'ASSISTANT').length };
  }
  function control(name, released = false) { const c = { question: 'cp76-' + name + '-' + randomUUID(), calls: 0, released, entered: false, closed: false }; controls.set(c.question, c); return c; }
  function stream(node, id, token, key, c) {
    const observed = { events: [], settled: false };
    observed.pending = (async () => {
      const response = await fetch(url(node, route(id) + '/stream'), { method: 'POST', headers: {
        Authorization: 'Bearer ' + token, 'Idempotency-Key': key, 'Content-Type': 'application/json', Accept: 'text/event-stream, application/json' },
        body: JSON.stringify({ content: c.question }), signal: AbortSignal.timeout(25000) });
      observed.status = response.status; observed.replayed = response.headers.get('X-OpsPilot-Idempotent-Replay') === 'true';
      const reader = response.body.getReader(), decoder = new TextDecoder(); let buffer = '';
      while (true) {
        const { value, done } = await reader.read(); buffer += decoder.decode(value, { stream: !done });
        let boundary; while ((boundary = buffer.indexOf('\n\n')) >= 0) {
          const frame = buffer.slice(0, boundary); buffer = buffer.slice(boundary + 2);
          for (const line of frame.split('\n')) if (line.startsWith('data:')) observed.events.push(JSON.parse(line.slice(5)));
        }
        if (done) break;
      }
      observed.settled = true; return observed;
    })();
    observed.pending.catch(error => { observed.error = redact(error.message); });
    return observed;
  }
  const text = observed => observed.events.filter(e => ['token', 'delta'].includes(e.type)).map(e => e.content || '').join('');
  const done = observed => observed.events.find(e => e.type === 'done');
  async function preview(c, observed) {
    await until(() => c.entered);
    assert.equal(c.native, !baseline);
    if (!baseline) { await until(() => observed.events.some(e => e.type === 'token')); assert.equal(c.incremental, true); assert.equal(text(observed), FIRST); }
    assert.equal(c.released, false);
  }
  try {
    await start(9965, 9966); await start(9967, 9968); assert.notEqual(children[0].pid, children[1].pid);
    let token = await login('A');
    const completedId = await session(token), completedKey = randomUUID(), completed = control('completed', true);
    assert.equal((await api('B', route(completedId), token)).status, 200); // B must see A's committed session, not a private memory copy.
    const original = await stream('A', completedId, token, completedKey, completed).pending;
    assert.equal(original.status, 200); assert.ok(done(original)); assert.equal(text(original), baseline ? (FIRST + LAST).trim() : FIRST + LAST);
    const saved = await state('B', completedId, token, completedKey); assert.equal(saved.json.data.status, 'COMPLETED');
    assert.equal(saved.json.data.answerMessageId, done(original).messageId);
    const replay = await stream('B', completedId, token, completedKey, completed).pending;
    assert.equal(replay.replayed, true); assert.equal(done(replay).messageId, done(original).messageId); assert.equal(completed.calls, 1);
    result.cases.push({ name: 'shared-sql-completed-replay', nodes: ['A', 'B'], originalAnswerIdPreserved: true, modelCalls: completed.calls });

    const id = await session(token), key = randomUUID(), held = control('cancel-held'), running = stream('A', id, token, key, held);
    await preview(held, running); assert.deepEqual(await counts('B', id, token), { user: 1, assistant: 0 });
    const duplicate = await api('B', route(id) + '/stream', token, { content: held.question }, key);
    assert.equal(duplicate.status, 409); assert.equal(duplicate.json.error.code, 'ASSISTANT_REQUEST_IN_PROGRESS'); assert.equal(held.calls, 1);
    const other = await login('B', 'zhangwei'); assert.equal((await cancel('B', id, other, key)).status, 404);
    assert.equal((await cancel('B', id, token, key)).json.data.status, 'CANCELLED');
    await running.pending; assert.ok(running.events.some(e => e.type === 'cancelled')); assert.ok(!done(running));
    const freshId = await session(token), freshKey = randomUUID(), fresh = control('fresh-after-remote-cancel', true);
    if (!baseline) await until(() => held.closed);
    const freshStream = stream('A', freshId, token, freshKey, fresh);
    if (baseline) {
      await until(async () => (await state('B', freshId, token, freshKey)).json.data?.status === 'QUEUED');
      assert.equal(fresh.calls, 0); assert.equal(held.closed, false);
    } else { await freshStream.pending; assert.ok(done(freshStream)); assert.equal(held.released, false); }
    const beforeReleaseClosed = held.closed, beforeReleaseReused = freshStream.settled;
    release(held); await freshStream.pending; assert.ok(done(freshStream));
    for (const node of ['A', 'B']) { assert.equal((await state(node, id, token, key)).json.data.status, 'CANCELLED'); assert.deepEqual(await counts(node, id, token), { user: 1, assistant: 0 }); }
    assert.equal((await cancel('A', id, token, key)).json.data.status, 'CANCELLED');
    const audits = await api('B', '/audit-logs?limit=500', token); assert.equal(audits.status, 200);
    const auditCount = audits.json.data.filter(a => a.action === 'ASSISTANT_REQUEST_CANCEL' && a.targetType === 'ASSISTANT_SESSION' && a.targetId === String(id)).length;
    assert.equal(auditCount, 1);
    result.cases.push({ name: 'cancel-on-B-releases-A', duplicateStatus: duplicate.status, otherActorStatus: 404,
      persistedStatusBothNodes: 'CANCELLED', cancelledAnswers: 0, cancellationAudits: auditCount,
      physicalHttpClosedBeforeProviderRelease: beforeReleaseClosed, sameWorkerReusedBeforeProviderRelease: beforeReleaseReused });

    if (!baseline) {
      const clearId = await session(token), clearKey = randomUUID(), cleared = control('clear-held'), clearing = stream('A', clearId, token, clearKey, cleared);
      await preview(cleared, clearing); assert.equal((await api('B', route(clearId) + '/messages', token, undefined, null, 'DELETE')).status, 200);
      await clearing.pending; await until(() => cleared.closed); assert.equal(cleared.released, false);
      assert.ok(clearing.events.some(e => e.type === 'error')); assert.ok(!done(clearing)); assert.ok(!text(clearing).includes(LAST));
      assert.equal((await state('A', clearId, token, clearKey)).json.data.status, 'SUPERSEDED'); assert.deepEqual(await counts('B', clearId, token), { user: 0, assistant: 0 });
      result.cases.push({ name: 'clear-on-B-fences-A', persistedStatus: 'SUPERSEDED', questionRows: 0, answerRows: 0, physicalHttpClosedBeforeProviderRelease: true }); release(cleared);

      const revokeId = await session(token), revokeKey = randomUUID(), revoked = control('revoke-held'), revoking = stream('A', revokeId, token, revokeKey, revoked);
      await preview(revoked, revoking); const before = revoking.events.length;
      assert.equal((await api('B', '/auth/logout-all', token, {})).status, 200);
      await revoking.pending; await until(() => revoked.closed); assert.equal(revoked.released, false);
      assert.equal(revoking.events.length, before); assert.ok(!done(revoking));
      assert.equal((await api('A', '/auth/me', token)).status, 401); assert.equal((await api('B', '/auth/me', token)).status, 401);
      token = await login('B'); assert.equal((await state('B', revokeId, token, revokeKey)).json.data.status, 'REVOKED');
      assert.deepEqual(await counts('A', revokeId, token), { user: 1, assistant: 0 });
      const next = control('fresh-after-remote-revoke', true), nextStream = await stream('A', await session(token), token, randomUUID(), next).pending;
      assert.ok(done(nextStream)); assert.equal(revoked.released, false);
      result.cases.push({ name: 'revoke-on-B-fences-A', persistedStatus: 'REVOKED', oldTokensRejectedBothNodes: true,
        noPostRevocationPayload: true, answerRows: 0, physicalHttpClosedBeforeProviderRelease: true, sameWorkerReusedBeforeProviderRelease: true }); release(revoked);
    }
    result.providerCalls = [...controls.values()].reduce((sum, c) => sum + c.calls, 0); assert.equal(result.providerCalls, baseline ? 3 : 6);
    result.status = baseline ? 'BASELINE_CAPTURED' : 'PASS';
  } catch (error) { result.status = 'FAIL'; result.failure = redact(error.stack); throw error; }
  finally {
    process.off('SIGINT', interrupt); process.off('SIGTERM', interrupt); for (const c of controls.values()) release(c);
    try { for (const child of [...children].reverse()) { await stopProcess(child); result.stoppedPids.push(child.pid); } result.ownedProcessesStopped = true; }
    finally {
      provider.closeAllConnections(); await new Promise(resolve => provider.close(resolve)); result.ownedProviderStopped = true;
      for (const descriptor of descriptors) fs.closeSync(descriptor);
      result.unexpectedJarErrors = 0; for (const file of logs) { const content = redact(fs.readFileSync(file, 'utf8')); fs.writeFileSync(file, content); result.unexpectedJarErrors += unexpectedLogLines(content); }
      if (result.unexpectedJarErrors) { result.status = 'FAIL'; result.failure = 'Owned JAR reported unexpected errors; see sanitized evidence'; }
      fs.writeFileSync(path.join(evidence, 'result.json'), JSON.stringify(result, null, 2));
      console.log(JSON.stringify({ ...result, evidenceDirectory: path.relative(root, evidence) }));
      if (result.status === 'FAIL') throw new Error(result.failure);
    }
  }
}
if (require.main === module) verify().catch(error => { console.error(redact(error.message)); process.exitCode = 1; });

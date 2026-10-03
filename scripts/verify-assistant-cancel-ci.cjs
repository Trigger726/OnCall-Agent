const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const http = require('node:http');
const { spawn } = require('node:child_process');
const { randomUUID, createHash } = require('node:crypto');
const { setTimeout: delay } = require('node:timers/promises');
const { requireFreePort, stopProcess, waitForHealth, redact, unexpectedLogLines } = require('./verify-oncall-browser-ci.cjs');

async function verify() {
  const baseline = process.env.OPSPILOT_ASSISTANT_CANCEL_BASELINE === '1';
  if (baseline && process.env.CI) throw new Error('Baseline capture cannot replace assistant cancellation acceptance');
  const root = path.resolve(__dirname, '..'), archived = path.join(root, 'target/cp72-before/opspilot-cp71.jar');
  const jar = path.join(root, 'target/opspilot-0.1.0-SNAPSHOT.jar');
  assert.ok(fs.existsSync(baseline ? archived : jar), 'Build or preserve the scoped JAR first');
  const upgrade = !baseline && !process.env.CI && fs.existsSync(archived); // CI always exercises its own newly built JAR.
  await requireFreePort(9943); await requireFreePort(9944);
  const parent = path.join(root, 'target/assistant-cancel-it'); fs.mkdirSync(parent, { recursive: true });
  const evidence = fs.mkdtempSync(path.join(parent, 'run-')), database = path.join(evidence, 'database'); fs.mkdirSync(database);
  const digest = file => createHash('sha256').update(fs.readFileSync(file)).digest('hex');
  const result = { status: 'RUNNING', baselineCapture: baseline, tokensPersistedToEvidence: false,
    jarSha256: digest(baseline ? archived : jar), upgradeFixtureExecuted: upgrade,
    fixture: 'owned production HTTP adapter; explicit cancellation, lost cancel response and queued SIGKILL recovery',
    database: 'fresh owned H2 file with WRITE_DELAY=0', userFileDatabaseModified: false,
    startedPids: [], stoppedPids: [], cases: [] };
  const sentinel = 'CP72-controlled-HTTP-answer';
  let holding = false, calls = 0, relayStatus;
  const held = new Set(), children = [], descriptors = [], logFiles = [];
  const nativeResponses = new WeakSet();
  function reply(response) {
    require('./assistant-provider-fixture.cjs').reply(response, sentinel, nativeResponses.has(response));
  }
  const provider = http.createServer((request, response) => {
    if (request.headers['x-dashscope-sse'] === 'enable') nativeResponses.add(response);
    if (request.method !== 'POST' || request.url !== '/api/v1/services/aigc/text-generation/generation') {
      response.writeHead(404); response.end(); return;
    }
    request.resume(); request.once('end', () => {
      calls++; if (!holding) reply(response);
      else { held.add(response); response.once('close', () => held.delete(response)); }
    });
  });
  await new Promise((resolve, reject) => { provider.once('error', reject); provider.listen(0, '127.0.0.1', resolve); });
  const release = () => { holding = false; for (const response of held) reply(response); held.clear(); };
  const relay = http.createServer(async (incoming, response) => {
    incoming.resume();
    try {
      const upstream = await fetch('http://127.0.0.1:9943' + incoming.url, { method: 'POST',
        headers: { Authorization: incoming.headers.authorization, 'Idempotency-Key': incoming.headers['idempotency-key'] },
        signal: AbortSignal.timeout(16000) });
      const body = await upstream.json(); relayStatus = upstream.status;
      assert.equal(body.data?.status, 'CANCELLED'); response.destroy();
    } catch (error) { result.relayFailure = redact(error.message); response.destroy(); }
  });
  await new Promise((resolve, reject) => { relay.once('error', reject); relay.listen(0, '127.0.0.1', resolve); });
  const java = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java';
  async function start(file) {
    await requireFreePort(9943); await requireFreePort(9944);
    const log = path.join(evidence, 'jar-' + (children.length + 1) + '.log'), descriptor = fs.openSync(log, 'w');
    descriptors.push(descriptor); logFiles.push(log);
    const providerUrl = 'http://127.0.0.1:' + provider.address().port;
    const child = spawn(java, ['-Duser.timezone=UTC', '-jar', file,
      '--server.address=127.0.0.1', '--server.port=9943', '--management.server.address=127.0.0.1', '--management.server.port=9944',
      '--spring.datasource.url=jdbc:h2:file:' + path.join(database, 'opspilot').replaceAll('\\', '/') + ';MODE=MySQL;DATABASE_TO_LOWER=TRUE;WRITE_DELAY=0',
      '--spring.datasource.username=sa', '--spring.datasource.password=', '--spring.h2.console.enabled=false',
      '--opspilot.ai.enabled=true', '--spring.ai.dashscope.api-key=cp72-controlled-local-provider',
      '--spring.ai.dashscope.base-url=' + providerUrl, '--spring.ai.dashscope.chat.base-url=' + providerUrl,
      '--spring.ai.dashscope.read-timeout=20000', '--opspilot.assistant.workers=1', '--opspilot.assistant.queue-capacity=1',
      '--opspilot.assistant.execution-timeout=8s', '--opspilot.assistant.authorization-check-delay=100',
      '--opspilot.agent.recovery.enabled=false', '--opspilot.oncall.rotation.enabled=false', '--opspilot.oncall.escalation.enabled=false'],
      { cwd: root, stdio: ['ignore', descriptor, descriptor], windowsHide: true });
    child.once('error', error => { child.launchError = error; }); children.push(child); result.startedPids.push(child.pid);
    await waitForHealth('http://127.0.0.1:9944/actuator/health', child); return child;
  }
  async function stop(child) { await stopProcess(child); if (!result.stoppedPids.includes(child.pid)) result.stoppedPids.push(child.pid); }
  const interrupt = () => { for (const child of children) if (child.exitCode === null && child.signalCode === null) child.kill('SIGTERM'); };
  process.once('SIGINT', interrupt); process.once('SIGTERM', interrupt);
  async function waitFor(predicate, budget = 4000) {
    const until = Date.now() + budget; while (!predicate() && Date.now() < until) await delay(25);
    assert.ok(predicate(), 'Controlled HTTP condition did not settle before its budget');
  }
  async function request(route, token, body, key, method = body ? 'POST' : 'GET') {
    const response = await fetch('http://127.0.0.1:9943/api/v1' + route, { method,
      headers: { ...(token ? { Authorization: 'Bearer ' + token } : {}), ...(key ? { 'Idempotency-Key': key } : {}),
        ...(body ? { 'Content-Type': 'application/json' } : {}) }, body: body ? JSON.stringify(body) : undefined,
      signal: AbortSignal.timeout(16000) });
    const text = await response.text(); return { status: response.status, text,
      json: response.headers.get('content-type')?.includes('application/json') ? JSON.parse(text) : null };
  }
  async function login(username = 'admin') {
    const response = await request('/auth/login', null, { username, password: 'OpsPilot@2026' });
    assert.equal(response.status, 200); return response.json.data.accessToken;
  }
  async function session(token) { const r = await request('/assistant/sessions', token, {}); assert.equal(r.status, 200); return r.json.data.session.id; }
  const route = (id, stream = false) => '/assistant/sessions/' + id + (stream ? '/stream' : '/messages');
  const state = (id, token, key) => request('/assistant/sessions/' + id + '/request', token, undefined, key);
  const cancel = (id, token, key) => request('/assistant/sessions/' + id + '/request/cancel', token, undefined, key, 'POST');
  async function awaitState(id, token, key, expected) {
    const until = Date.now() + 3000; let r;
    do { r = await state(id, token, key); if (r.status === 200 && r.json.data.status === expected) return r.json.data; await delay(25); }
    while (Date.now() < until);
    assert.fail('Request did not reach controlled state ' + expected);
  }
  async function counts(id, token) {
    const r = await request('/assistant/sessions/' + id, token); assert.equal(r.status, 200);
    return { user: r.json.data.messages.filter(x => x.role === 'USER').length, assistant: r.json.data.messages.filter(x => x.role === 'ASSISTANT').length };
  }
  async function cancellationAudits(id, token) {
    const r = await request('/audit-logs?limit=500', token); assert.equal(r.status, 200);
    return r.json.data.filter(x => x.action === 'ASSISTANT_REQUEST_CANCEL' && x.targetType === 'ASSISTANT_SESSION' && x.targetId === String(id)).length;
  }
  try {
    let current = await start(baseline || upgrade ? archived : jar), token = await login();
    const completedId = await session(token), completedKey = randomUUID();
    const control = await request(route(completedId), token, { content: 'positive completed control' }, completedKey);
    assert.equal(control.status, 200); assert.equal(control.json.data.content, sentinel); assert.equal(calls, 1);
    if (upgrade) {
      await stop(current); current = await start(jar); token = await login();
      assert.equal((await state(completedId, token, completedKey)).json.data.status, 'COMPLETED');
      assert.equal((await request(route(completedId), token, { content: 'positive completed control' }, completedKey)).json.data.id, control.json.data.id);
      assert.equal(calls, 1); result.upgradedArchiveSha256 = digest(archived);
      result.cases.push({ name: 'v32-to-v33-history-upgrade', completedRequestAndOriginalAnswerPreserved: true, modelReexecuted: false });
    }
    const lateCancel = await cancel(completedId, token, completedKey); assert.equal(lateCancel.status, baseline ? 404 : 200);
    if (!baseline) { assert.equal(lateCancel.json.data.status, 'COMPLETED'); assert.equal(await cancellationAudits(completedId, token), 0); }
    const runningId = await session(token), queuedId = await session(token), runningKey = randomUUID(), queuedKey = randomUUID();
    holding = true;
    const running = request(route(runningId), token, { content: 'held synchronous cancellation' }, runningKey);
    await waitFor(() => held.size === 1);
    const queued = request(route(queuedId), token, { content: 'queued cancellation must never call model' }, queuedKey);
    if (baseline) {
      await delay(100); assert.equal((await state(queuedId, token, queuedKey)).status, 404);
      const queueCancel = await cancel(queuedId, token, queuedKey), runCancel = await cancel(runningId, token, runningKey);
      assert.equal(queueCancel.status, 404); assert.equal(runCancel.status, 404); assert.equal(held.size, 1);
      release(); assert.equal((await running).status, 200); assert.equal((await queued).status, 200);
      assert.deepEqual(await counts(queuedId, token), { user: 1, assistant: 1 }); assert.equal(calls, 3);
      result.cases.push({ name: 'old-cancellation-gap', queuedStateStatus: 404, queuedCancelStatus: 404, runningCancelStatus: 404,
        queuedQuestionExecutedAfterRelease: true, completedCancelStatus: lateCancel.status });
    } else {
      await awaitState(queuedId, token, queuedKey, 'QUEUED'); assert.deepEqual(await counts(queuedId, token), { user: 0, assistant: 0 });
      assert.equal((await cancel(runningId, await login('auditor'), runningKey)).status, 404);
      let lost = false;
      try {
        await fetch('http://127.0.0.1:' + relay.address().port + '/api/v1/assistant/sessions/' + queuedId + '/request/cancel',
          { method: 'POST', headers: { Authorization: 'Bearer ' + token, 'Idempotency-Key': queuedKey }, signal: AbortSignal.timeout(16000) });
      } catch (error) { assert.equal(error.name, 'TypeError'); lost = true; }
      assert.equal(lost, true); assert.equal(relayStatus, 200); assert.equal((await queued).status, 409); assert.equal(held.size, 1);
      assert.equal((await cancel(queuedId, token, queuedKey)).json.data.status, 'CANCELLED');
      assert.equal(await cancellationAudits(queuedId, token), 1); assert.deepEqual(await counts(queuedId, token), { user: 0, assistant: 0 });
      const replacementId = await session(token), replacementKey = randomUUID();
      const replacement = request(route(replacementId), token, { content: 'replacement after freed queue slot' }, replacementKey);
      await awaitState(replacementId, token, replacementKey, 'QUEUED');
      assert.equal((await cancel(runningId, token, runningKey)).json.data.status, 'CANCELLED');
      assert.equal((await running).status, 409); assert.equal(held.size, 1); assert.equal(calls, 2);
      release(); assert.equal((await replacement).status, 200); assert.equal(calls, 3);
      assert.deepEqual(await counts(runningId, token), { user: 1, assistant: 0 }); assert.equal(await cancellationAudits(runningId, token), 1);
      result.cases.push({ name: 'lost-queued-cancel-and-running-cancel', cancelResponseActuallyLost: true, relayBackendStatus: relayStatus,
        repeatedCancelAuditRows: 1, queuedQuestionWrites: 0, freedQueueSlotReused: true, synchronousStatusBeforeProviderRelease: 409, lateAssistantMessages: 0 });

      const streamId = await session(token), streamKey = randomUUID(); holding = true;
      const stream = request(route(streamId, true), token, { content: 'held stream cancellation' }, streamKey);
      await waitFor(() => held.size === 1); assert.equal((await cancel(streamId, token, streamKey)).json.data.status, 'CANCELLED');
      const ended = await stream; assert.ok(ended.text.includes('event:cancelled')); assert.ok(!ended.text.includes('event:done')); assert.ok(!ended.text.includes(sentinel));
      await waitFor(() => held.size === 0);
      holding = false; // Native cancellation must close the HTTP connection without releasing the old provider.
      assert.equal((await request(route(await session(token)), token, { content: 'actual worker settlement after stream cancel' }, randomUUID())).status, 200);
      release();
      assert.deepEqual(await counts(streamId, token), { user: 1, assistant: 0 }); assert.equal(await cancellationAudits(streamId, token), 1);
      result.cases.push({ name: 'stream-cancel', cancelledEventBeforeProviderRelease: true, doneEventSent: false, lateAssistantMessages: 0,
        nativeHttpConnectionClosedBeforeProviderRelease: true, workerReusedBeforeProviderRelease: true, actualWorkerSettledByFreshAnswer: true });

      const crashRunningId = await session(token), crashQueuedId = await session(token), crashRunningKey = randomUUID(), crashQueuedKey = randomUUID();
      holding = true;
      const crashRunning = request(route(crashRunningId), token, { content: 'running at crash' }, crashRunningKey).then(r => ({ status: r.status }), e => ({ error: e.name }));
      await waitFor(() => held.size === 1);
      const crashQueued = request(route(crashQueuedId), token, { content: 'queued at crash' }, crashQueuedKey).then(r => ({ status: r.status }), e => ({ error: e.name }));
      const persistedQueue = await awaitState(crashQueuedId, token, crashQueuedKey, 'QUEUED');
      assert.equal(current.kill('SIGKILL'), true); result.hardKilledPid = current.pid; await stop(current); release();
      assert.equal((await crashRunning).error, 'TypeError'); assert.equal((await crashQueued).error, 'TypeError');
      current = await start(jar); token = await login();
      for (const [id, key] of [[queuedId, queuedKey], [runningId, runningKey], [streamId, streamKey]]) {
        assert.equal((await state(id, token, key)).json.data.status, 'CANCELLED'); assert.equal(await cancellationAudits(id, token), 1);
      }
      await waitFor(() => Date.now() >= persistedQueue.deadlineEpochMs, 9000);
      assert.equal((await state(crashQueuedId, token, crashQueuedKey)).json.data.status, 'TIMED_OUT');
      assert.equal((await request(route(crashQueuedId), token, { content: 'queued at crash' }, crashQueuedKey)).status, 409);
      assert.deepEqual(await counts(crashQueuedId, token), { user: 0, assistant: 0 }); assert.equal(calls, 6);
      result.cases.push({ name: 'queued-sigkill-recovery', statusBeforeCrash: 'QUEUED', statusAfterOriginalBudget: 'TIMED_OUT',
        questionWrites: 0, modelReexecuted: false, cancelledFactsAndSingleAuditSurviveRestart: true });
    }
    assert.equal(calls, baseline ? 3 : 6); result.providerTransportCalls = calls;
    assert.equal(new Set(result.startedPids).size, baseline ? 1 : upgrade ? 3 : 2);
    result.status = baseline ? 'BASELINE_CAPTURED' : 'PASS';
  } catch (error) { result.status = 'FAIL'; result.failure = redact(error.message); throw error; }
  finally {
    process.off('SIGINT', interrupt); process.off('SIGTERM', interrupt); release();
    try { for (const child of children) await stop(child); result.ownedProcessesStopped = true; }
    finally {
      relay.closeAllConnections(); await new Promise(resolve => relay.close(resolve)); result.ownedRelayStopped = true;
      provider.closeAllConnections(); await new Promise(resolve => provider.close(resolve)); result.ownedProviderStopped = true;
      for (const descriptor of descriptors) fs.closeSync(descriptor);
      result.unexpectedJarErrors = 0;
      for (const log of logFiles) {
        const text = redact(fs.readFileSync(log, 'utf8')); fs.writeFileSync(log, text);
        result.unexpectedJarErrors += unexpectedLogLines(text) + (text.match(/^.*(?:escaped|Unexpected error occurred in scheduled task|NoClassDefFoundError).*$/gm) || []).length;
      }
      if (result.unexpectedJarErrors) { result.status = 'FAIL'; result.failure = 'Unexpected owned JAR errors including shutdown'; }
      fs.writeFileSync(path.join(evidence, 'result.json'), JSON.stringify(result, null, 2));
      console.log(JSON.stringify({ ...result, evidenceDirectory: path.relative(root, evidence) }));
      if (result.unexpectedJarErrors) throw new Error(result.failure);
    }
  }
}
if (require.main === module) verify().catch(error => { console.error(redact(error.message)); process.exitCode = 1; });

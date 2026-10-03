const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const http = require('node:http');
const { spawn } = require('node:child_process');
const { randomUUID, createHash } = require('node:crypto');
const { setTimeout: delay } = require('node:timers/promises');
const { requireFreePort, stopProcess, waitForHealth, redact, unexpectedLogLines } = require('./verify-oncall-browser-ci.cjs');

async function verify() {
  const baseline = process.env.OPSPILOT_ASSISTANT_IDEMPOTENCY_BASELINE === '1';
  if (baseline && process.env.CI) throw new Error('Baseline capture cannot replace assistant idempotency acceptance');
  const root = path.resolve(__dirname, '..');
  const jar = path.join(root, 'target', baseline ? 'cp71-before/opspilot-cp70.jar' : 'opspilot-0.1.0-SNAPSHOT.jar');
  assert.ok(fs.existsSync(jar), 'Build or preserve the scoped JAR first');
  await requireFreePort(9941); await requireFreePort(9942);
  const parent = path.join(root, 'target', 'assistant-idempotency-it'); fs.mkdirSync(parent, { recursive: true });
  const evidence = fs.mkdtempSync(path.join(parent, 'run-'));
  const database = path.join(evidence, 'database'); fs.mkdirSync(database);
  const result = { status: 'RUNNING', baselineCapture: baseline, tokensPersistedToEvidence: false,
    jarSha256: createHash('sha256').update(fs.readFileSync(jar)).digest('hex'),
    fixture: 'actual production DashScope HTTP adapter, dropped completed HTTP response, independent JVMs and SIGKILL',
    database: 'fresh owned H2 file with WRITE_DELAY=0', userFileDatabaseModified: false, startedPids: [], stoppedPids: [], cases: [] };
  const sentinel = 'CP71-controlled-durable-HTTP-answer';
  let holding = false, calls = 0, relayStatus, relayAnswerId;
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
    const chunks = []; for await (const chunk of incoming) chunks.push(chunk);
    try {
      const upstream = await fetch('http://127.0.0.1:9941' + incoming.url, { method: 'POST',
        headers: { Authorization: incoming.headers.authorization, 'Content-Type': 'application/json',
          'Idempotency-Key': incoming.headers['idempotency-key'] }, body: Buffer.concat(chunks), signal: AbortSignal.timeout(12000) });
      const body = await upstream.json(); relayStatus = upstream.status; relayAnswerId = body.data?.id;
      response.destroy(); // Actual completed server result is intentionally lost before any response reaches the client.
    } catch (error) { response.destroy(); result.relayFailure = redact(error.message); }
  });
  await new Promise((resolve, reject) => { relay.once('error', reject); relay.listen(0, '127.0.0.1', resolve); });
  const java = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java';
  async function start() {
    await requireFreePort(9941); await requireFreePort(9942);
    const log = path.join(evidence, 'jar-' + (children.length + 1) + '.log');
    const descriptor = fs.openSync(log, 'w'); descriptors.push(descriptor); logFiles.push(log);
    const providerUrl = 'http://127.0.0.1:' + provider.address().port;
    const child = spawn(java, ['-Duser.timezone=UTC', '-jar', jar,
      '--server.address=127.0.0.1', '--server.port=9941', '--management.server.address=127.0.0.1', '--management.server.port=9942',
      '--spring.datasource.url=jdbc:h2:file:' + path.join(database, 'opspilot').replaceAll('\\', '/') + ';MODE=MySQL;DATABASE_TO_LOWER=TRUE;WRITE_DELAY=0',
      '--spring.datasource.username=sa', '--spring.datasource.password=', '--spring.h2.console.enabled=false',
      '--opspilot.ai.enabled=true', '--spring.ai.dashscope.api-key=cp71-controlled-local-provider',
      '--spring.ai.dashscope.base-url=' + providerUrl, '--spring.ai.dashscope.chat.base-url=' + providerUrl,
      '--spring.ai.dashscope.read-timeout=15000', '--opspilot.assistant.workers=1', '--opspilot.assistant.queue-capacity=1',
      '--opspilot.assistant.execution-timeout=5s', '--opspilot.assistant.authorization-check-delay=100',
      '--opspilot.agent.recovery.enabled=false', '--opspilot.oncall.rotation.enabled=false', '--opspilot.oncall.escalation.enabled=false'],
      { cwd: root, stdio: ['ignore', descriptor, descriptor], windowsHide: true });
    child.once('error', error => { child.launchError = error; });
    children.push(child); result.startedPids.push(child.pid);
    await waitForHealth('http://127.0.0.1:9942/actuator/health', child); return child;
  }
  async function stop(child) {
    await stopProcess(child); if (!result.stoppedPids.includes(child.pid)) result.stoppedPids.push(child.pid);
  }
  const interrupt = () => { for (const child of children) if (child.exitCode === null && child.signalCode === null) child.kill('SIGTERM'); };
  process.once('SIGINT', interrupt); process.once('SIGTERM', interrupt);
  async function waitFor(predicate, budget = 4000) {
    const until = Date.now() + budget; while (!predicate() && Date.now() < until) await delay(25);
    assert.ok(predicate(), 'Controlled HTTP condition did not settle before its budget');
  }
  async function request(route, token, body, key, method = body ? 'POST' : 'GET') {
    const response = await fetch('http://127.0.0.1:9941/api/v1' + route, { method,
      headers: { ...(token ? { Authorization: 'Bearer ' + token } : {}), ...(key ? { 'Idempotency-Key': key } : {}),
        ...(body ? { 'Content-Type': 'application/json' } : {}) }, body: body ? JSON.stringify(body) : undefined, signal: AbortSignal.timeout(12000) });
    const text = await response.text();
    return { status: response.status, text, replay: response.headers.get('x-opspilot-idempotent-replay'),
      json: response.headers.get('content-type')?.includes('application/json') ? JSON.parse(text) : null };
  }
  async function login() {
    const response = await request('/auth/login', null, { username: 'zhangwei', password: 'OpsPilot@2026' });
    assert.equal(response.status, 200); return response.json.data.accessToken;
  }
  async function session(token) {
    const response = await request('/assistant/sessions', token, {}); assert.equal(response.status, 200); return response.json.data.session.id;
  }
  const route = (id, stream = false) => '/assistant/sessions/' + id + (stream ? '/stream' : '/messages');
  const state = (id, token, key) => request('/assistant/sessions/' + id + '/request', token, undefined, key);
  async function counts(id, token) {
    const response = await request('/assistant/sessions/' + id, token); assert.equal(response.status, 200);
    return { user: response.json.data.messages.filter(x => x.role === 'USER').length,
      assistant: response.json.data.messages.filter(x => x.role === 'ASSISTANT').length };
  }
  try {
    const firstJvm = await start(); let token = await login();
    const control = await request(route(await session(token)), token, { content: 'native adapter positive control' });
    assert.equal(control.status, 200); assert.equal(control.json.data.content, sentinel); assert.equal(calls, 1);
    const id = await session(token), key = randomUUID(), question = 'recover exactly this accepted question';
    let lost = false;
    try {
      await fetch('http://127.0.0.1:' + relay.address().port + '/api/v1' + route(id), { method: 'POST',
        headers: { Authorization: 'Bearer ' + token, 'Content-Type': 'application/json', 'Idempotency-Key': key },
        body: JSON.stringify({ content: question }), signal: AbortSignal.timeout(12000) });
    } catch (error) { assert.equal(error.name, 'TypeError'); lost = true; }
    assert.equal(lost, true); assert.equal(relayStatus, 200); assert.ok(relayAnswerId > 0); assert.equal(calls, 2);
    await stop(firstJvm); const secondJvm = await start(); token = await login();
    const recoveredState = await state(id, token, key); assert.equal(recoveredState.status, baseline ? 404 : 200);
    if (!baseline) assert.equal(recoveredState.json.data.status, 'COMPLETED');
    const retry = await request(route(id), token, { content: question }, key); assert.equal(retry.status, 200);
    assert.equal(retry.json.data.id === relayAnswerId, !baseline); if (!baseline) assert.equal(retry.replay, 'true');
    const stream = await request(route(id, true), token, { content: question }, key);
    assert.equal(stream.status, 200); assert.ok(stream.text.includes('event:done'));
    if (!baseline) { assert.equal(stream.replay, 'true'); assert.ok(stream.text.includes('"messageId":' + relayAnswerId)); }
    const changed = await request(route(id), token, { content: 'different question under the same key' }, key);
    assert.equal(changed.status, baseline ? 200 : 409);
    const completedCounts = await counts(id, token);
    assert.deepEqual(completedCounts, baseline ? { user: 4, assistant: 4 } : { user: 1, assistant: 1 });
    assert.equal(calls, baseline ? 5 : 2);
    result.cases.push({ name: 'lost-completed-response-and-jvm-restart', backendStatusBeforeResponseLoss: relayStatus,
      actualClientResponseLost: lost, sameMessageIdAfterRestart: retry.json.data.id === relayAnswerId,
      changedContentStatus: changed.status, requestStateAfterRestart: baseline ? 'NOT_AVAILABLE' : recoveredState.json.data.status,
      counts: completedCounts, modelCallsForQuestionIncludingRetries: baseline ? 4 : 1 });
    if (!baseline) {
      const timeoutId = await session(token), timeoutKey = randomUUID(); holding = true;
      const pending = request(route(timeoutId), token, { content: 'held keyed timeout question' }, timeoutKey);
      await waitFor(() => held.size === 1);
      assert.equal((await state(timeoutId, token, timeoutKey)).json.data.status, 'RUNNING');
      const duplicate = await request(route(timeoutId), token, { content: 'held keyed timeout question' }, timeoutKey);
      assert.equal(duplicate.status, 409); assert.equal(duplicate.json.error.code, 'ASSISTANT_REQUEST_IN_PROGRESS');
      assert.equal((await pending).status, 504); assert.equal(held.size, 1);
      assert.equal((await state(timeoutId, token, timeoutKey)).json.data.status, 'TIMED_OUT');
      assert.equal((await request(route(timeoutId), token, { content: 'held keyed timeout question' }, timeoutKey)).status, 409);
      release();
      const fresh = await request(route(await session(token)), token, { content: 'fresh keyed settlement' }, randomUUID());
      assert.equal(fresh.status, 200); assert.equal(fresh.json.data.content, sentinel);
      assert.deepEqual(await counts(timeoutId, token), { user: 1, assistant: 0 });
      result.cases.push({ name: 'keyed-timeout', inProgressDuplicateStatus: duplicate.status, timeoutStatus: 504,
        stateBeforeProviderRelease: 'TIMED_OUT', lateAssistantMessages: 0, lateWorkerSettledByFreshKey: true });

      const crashId = await session(token), crashKey = randomUUID(); holding = true;
      const crashedResponse = request(route(crashId), token, { content: 'accepted before process crash' }, crashKey)
        .then(value => ({ response: value }), error => ({ error: error.name }));
      await waitFor(() => held.size === 1);
      assert.equal((await state(crashId, token, crashKey)).json.data.status, 'RUNNING');
      assert.equal(secondJvm.kill('SIGKILL'), true); await stop(secondJvm); release();
      result.hardKilledPid = secondJvm.pid;
      const disconnected = await crashedResponse; assert.equal(disconnected.error, 'TypeError');
      await start(); token = await login();
      const initial = await state(crashId, token, crashKey);
      assert.equal(initial.status, 200);
      if (initial.json.data.status === 'RUNNING') {
        const stillRunning = await request(route(crashId), token, { content: 'accepted before process crash' }, crashKey);
        assert.equal(stillRunning.status, 409); assert.equal(stillRunning.json.error.code, 'ASSISTANT_REQUEST_IN_PROGRESS');
        await waitFor(() => Date.now() >= initial.json.data.deadlineEpochMs, 6000);
      }
      const recovered = await state(crashId, token, crashKey);
      assert.equal(recovered.status, 200); assert.equal(recovered.json.data.status, 'TIMED_OUT');
      assert.equal((await request(route(crashId), token, { content: 'accepted before process crash' }, crashKey)).status, 409);
      assert.deepEqual(await counts(crashId, token), { user: 1, assistant: 0 });
      result.cases.push({ name: 'sigkill-accepted-request-recovery', statusBeforeCrash: 'RUNNING',
        currentStateAfterIndependentJvmRestart: 'TIMED_OUT', sameKeyRetryStatus: 409, modelReexecuted: false, counts: { user: 1, assistant: 0 } });
    }
    assert.equal(calls, 5); result.providerTransportCalls = calls;
    assert.equal(new Set(result.startedPids).size, baseline ? 2 : 3);
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
        result.unexpectedJarErrors += unexpectedLogLines(text)
          + (text.match(/^.*(?:escaped|Unexpected error occurred in scheduled task|NoClassDefFoundError).*$/gm) || []).length;
      }
      if (result.unexpectedJarErrors) { result.status = 'FAIL'; result.failure = 'Unexpected owned JAR errors including shutdown'; }
      fs.writeFileSync(path.join(evidence, 'result.json'), JSON.stringify(result, null, 2));
      console.log(JSON.stringify({ ...result, evidenceDirectory: path.relative(root, evidence) }));
      if (result.unexpectedJarErrors) throw new Error(result.failure);
    }
  }
}
if (require.main === module) verify().catch(error => { console.error(redact(error.message)); process.exitCode = 1; });

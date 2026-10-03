const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const http = require('node:http');
const { spawn } = require('node:child_process');
const { randomUUID, createHash } = require('node:crypto');
const { setTimeout: delay } = require('node:timers/promises');
const { requireFreePort, stopProcess, waitForHealth, redact, unexpectedLogLines } = require('./verify-oncall-browser-ci.cjs');

async function verify() {
  const baseline = process.env.OPSPILOT_ASSISTANT_SESSION_BASELINE === '1';
  const budgetBaseline = process.env.OPSPILOT_ASSISTANT_BUDGET_BASELINE === '1';
  if ((baseline || budgetBaseline) && process.env.CI) throw new Error('Baseline capture cannot replace assistant acceptance');
  if (baseline && budgetBaseline) throw new Error('Choose one preserved baseline');
  const root = path.resolve(__dirname, '..');
  const jar = path.join(root, 'target', baseline ? 'cp68-before/opspilot-cp67-client.jar'
    : budgetBaseline ? 'cp70-before/opspilot-cp69.jar' : 'opspilot-0.1.0-SNAPSHOT.jar');
  assert.ok(fs.existsSync(jar), 'Build or preserve the scoped JAR first');
  await requireFreePort(9937); await requireFreePort(9938);
  const parent = path.join(root, 'target', 'assistant-session-it');
  fs.mkdirSync(parent, { recursive: true });
  const evidence = fs.mkdtempSync(path.join(parent, 'run-'));
  const sentinel = 'CP68-controlled-HTTP-answer-must-not-cross-original-session';
  const result = { status: 'RUNNING', baselineCapture: baseline || budgetBaseline, budgetBaselineCapture: budgetBaseline, tokensPersistedToEvidence: false,
    jarSha256: createHash('sha256').update(fs.readFileSync(jar)).digest('hex'),
    fixture: 'actual production DashScope HTTP adapter against an owned controlled server, not model quality',
    database: 'fresh owned H2 memory database', userFileDatabaseModified: false, cases: [] };
  const held = new Set();
  const nativeResponses = new WeakSet();
  let holding = false, calls = 0;
  function reply(response) {
    require('./assistant-provider-fixture.cjs').reply(response, sentinel, nativeResponses.has(response));
  }
  const provider = http.createServer((request, response) => {
    if (request.headers['x-dashscope-sse'] === 'enable') nativeResponses.add(response);
    if (request.method !== 'POST' || request.url !== '/api/v1/services/aigc/text-generation/generation') {
      response.writeHead(404); response.end(); return;
    }
    request.resume();
    request.once('end', () => {
      calls++;
      if (!holding) reply(response);
      else { held.add(response); response.once('close', () => held.delete(response)); }
    });
  });
  await new Promise((resolve, reject) => { provider.once('error', reject); provider.listen(0, '127.0.0.1', resolve); });
  const release = () => { holding = false; for (const response of held) reply(response); held.clear(); };
  const log = path.join(evidence, 'jar.log');
  const descriptor = fs.openSync(log, 'w');
  const java = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java';
  const providerUrl = 'http://127.0.0.1:' + provider.address().port;
  const child = spawn(java, ['-Duser.timezone=UTC', '-jar', jar,
    '--server.address=127.0.0.1', '--server.port=9937', '--management.server.address=127.0.0.1', '--management.server.port=9938',
    '--spring.datasource.url=jdbc:h2:mem:assistant_' + randomUUID().replaceAll('-', '') + ';MODE=MySQL;DATABASE_TO_LOWER=TRUE',
    '--spring.datasource.username=sa', '--spring.datasource.password=', '--spring.h2.console.enabled=false',
    '--opspilot.ai.enabled=true', '--spring.ai.dashscope.api-key=cp68-controlled-local-provider',
    '--spring.ai.dashscope.base-url=' + providerUrl, '--spring.ai.dashscope.chat.base-url=' + providerUrl,
    '--spring.ai.dashscope.read-timeout=15000', '--opspilot.assistant.workers=1', '--opspilot.assistant.queue-capacity=1',
    '--opspilot.assistant.execution-timeout=5s', '--opspilot.assistant.authorization-check-delay=100',
    '--opspilot.agent.recovery.enabled=false', '--opspilot.oncall.rotation.enabled=false', '--opspilot.oncall.escalation.enabled=false'],
    { cwd: root, stdio: ['ignore', descriptor, descriptor], windowsHide: true });
  child.once('error', error => { child.launchError = error; });
  result.startedPid = child.pid;
  const interrupt = () => child.kill('SIGTERM');
  process.once('SIGINT', interrupt); process.once('SIGTERM', interrupt);
  async function waitFor(predicate, budget = 4000) {
    const until = Date.now() + budget;
    while (!predicate() && Date.now() < until) await delay(25);
    assert.ok(predicate(), 'Controlled condition did not settle before its budget');
  }
  async function request(route, token, body, method = body ? 'POST' : 'GET') {
    const response = await fetch('http://127.0.0.1:9937/api/v1' + route, { method,
      headers: { ...(token ? { Authorization: 'Bearer ' + token } : {}), ...(body ? { 'Content-Type': 'application/json' } : {}) },
      body: body ? JSON.stringify(body) : undefined, signal: AbortSignal.timeout(12000) });
    const text = await response.text();
    return { status: response.status, text, json: response.headers.get('content-type')?.includes('application/json') ? JSON.parse(text) : null };
  }
  async function login() {
    const response = await request('/auth/login', null, { username: 'zhangwei', password: 'OpsPilot@2026' });
    assert.equal(response.status, 200); return response.json.data.accessToken;
  }
  async function session(token) {
    const response = await request('/assistant/sessions', token, {});
    assert.equal(response.status, 200); return response.json.data.session.id;
  }
  const route = (id, stream = false) => '/assistant/sessions/' + id + (stream ? '/stream' : '/messages');
  function streamedText(body) {
    return body.split(/\r?\n/).filter(line => line.startsWith('data:'))
      .map(line => JSON.parse(line.slice(5))).filter(event => ['delta', 'token'].includes(event.type))
      .map(event => event.content || '').join('');
  }
  async function answerCount(id, token) {
    const response = await request('/assistant/sessions/' + id, token);
    assert.equal(response.status, 200);
    return response.json.data.messages.filter(message => message.role === 'ASSISTANT').length;
  }
  async function questionCount(id, token) {
    const response = await request('/assistant/sessions/' + id, token);
    assert.equal(response.status, 200);
    return response.json.data.messages.filter(message => message.role === 'USER').length;
  }
  try {
    await waitForHealth('http://127.0.0.1:9938/actuator/health', child);
    let token = await login();
    const positive = await request(route(await session(token)), token, { content: 'controlled adapter positive control' });
    assert.equal(positive.status, 200); assert.equal(positive.json.data.content, sentinel); assert.equal(calls, 1);
    result.productionHttpAdapterPositiveControl = true;
    for (const name of budgetBaseline ? [] : ['synchronous-logout', 'stream-logout', 'clear-messages']) {
      token = await login();
      const id = await session(token), stream = name === 'stream-logout';
      holding = true;
      let settled = false;
      const pending = request(route(id, stream), token, { content: name }).then(value => { settled = true; return value; });
      await waitFor(() => held.size === 1);
      const action = name === 'clear-messages'
        ? await request(route(id), token, undefined, 'DELETE') : await request('/auth/logout-all', token, {});
      assert.equal(action.status, 200);
      if (!baseline && name !== 'clear-messages') {
        await waitFor(() => settled, 3000);
        if (stream) await waitFor(() => held.size === 0, 3000);
        else assert.equal(held.size, 1); // Synchronous calls retain their own provider I/O budget.
      }
      if (!stream || baseline) release();
      const response = await pending;
      const text = stream ? streamedText(response.text) : response.text;
      const current = name === 'clear-messages' ? token : await login();
      if (!baseline && stream) {
        holding = false; // Do not release the old response; its physical connection must already be closed.
        const fresh = await request(route(await session(current), true), current, { content: 'stream settlement positive control' });
        assert.ok(fresh.text.includes('event:done')); assert.equal(streamedText(fresh.text), sentinel);
        release(); // Fresh work completed on the same worker before this explicit release.
      }
      const count = await answerCount(id, current);
      assert.equal(count, baseline ? 1 : 0);
      assert.equal(text.includes(sentinel), baseline);
      if (!stream) assert.equal(response.status, baseline ? 200 : name === 'clear-messages' ? 409 : 401);
      result.cases.push({ name, responseStatus: response.status, lateAssistantMessages: count,
        lateAnswerSent: text.includes(sentinel), closedBeforeProviderRelease: !baseline && name !== 'clear-messages',
        ...(stream && !baseline ? { nativeHttpConnectionClosedBeforeProviderRelease: true, workerReusedBeforeProviderRelease: true } : {}) });
    }
    if (!baseline && !budgetBaseline) {
      token = await login();
      const id = await session(token);
      holding = true;
      const pending = request(route(id, true), token, { content: 'controlled timeout' });
      await waitFor(() => held.size === 1);
      const response = await pending;
      assert.ok(response.text.includes('event:error') && response.text.includes('回答超时'));
      assert.ok(!response.text.includes('event:done') && !streamedText(response.text).includes(sentinel));
      await waitFor(() => held.size === 0, 3000);
      holding = false;
      const fresh = await request(route(await session(token), true), token, { content: 'fresh-session positive control' });
      assert.equal(fresh.status, 200); assert.ok(fresh.text.includes('event:done')); assert.equal(streamedText(fresh.text), sentinel);
      release();
      assert.equal(await answerCount(id, token), 0);
      result.cases.push({ name: 'timeout', closedBeforeProviderRelease: true, explicitSafeErrorEvent: true,
        nativeHttpConnectionClosedBeforeProviderRelease: true, workerReusedBeforeProviderRelease: true,
        lateWorkerSettledByFreshStream: true, lateAssistantMessages: 0 });
      result.freshSessionProductionHttpAnswer = true;
    }
    if (!baseline) {
      token = await login();
      const runningId = await session(token), queuedId = await session(token), rejectedId = await session(token);
      holding = true;
      const running = request(route(runningId), token, { content: 'shared budget running sync' });
      await waitFor(() => held.size === 1);
      const queued = request(route(queuedId, true), token, { content: 'shared budget queued stream' });
      // No production test endpoint exposes queue admission; this ordering delay is not a latency assertion.
      await delay(300);
      const rejected = request(route(rejectedId), token, { content: 'shared budget rejected sync' });
      if (budgetBaseline) {
        await waitFor(() => held.size === 3);
        assert.equal(await questionCount(rejectedId, token), 1);
        release();
        const rejectedResponse = await rejected;
        assert.equal(rejectedResponse.status, 200); assert.equal(rejectedResponse.json.data.content, sentinel);
        result.cases.push({ name: 'mixed-capacity', responseStatus: 200, rejectedQuestionWrites: 1, simultaneousProviderCalls: 3 });
      } else {
        const rejectedResponse = await rejected;
        assert.equal(rejectedResponse.status, 503); assert.equal(rejectedResponse.json.error.code, 'ASSISTANT_QUEUE_SATURATED');
        assert.equal(held.size, 1); assert.equal(await questionCount(rejectedId, token), 0);
        assert.equal(await questionCount(queuedId, token), 0); assert.equal(await answerCount(rejectedId, token), 0);
        result.cases.push({ name: 'mixed-capacity', responseStatus: 503, rejectedQuestionWrites: 0,
          queuedQuestionWritesBeforeProviderRelease: 0, simultaneousProviderCalls: 1 });
        release();
      }
      assert.equal((await running).status, 200);
      const queuedResponse = await queued; assert.ok(queuedResponse.text.includes('event:done'));
      assert.equal(streamedText(queuedResponse.text), sentinel);

      const timedId = await session(token), queuedTimedId = await session(token);
      holding = true;
      let timedSettled = false, queuedTimedSettled = false;
      const timed = request(route(timedId), token, { content: 'sync budget timeout' }).then(value => { timedSettled = true; return value; });
      await waitFor(() => held.size === 1);
      const queuedTimed = request(route(queuedTimedId), token, { content: 'queued sync budget timeout' })
        .then(value => { queuedTimedSettled = true; return value; });
      if (budgetBaseline) {
        await waitFor(() => held.size === 2); await delay(5500);
        assert.equal(timedSettled, false); assert.equal(queuedTimedSettled, false);
        release();
        assert.equal((await timed).status, 200); assert.equal((await queuedTimed).status, 200);
        assert.equal(await answerCount(timedId, token), 1); assert.equal(await answerCount(queuedTimedId, token), 1);
        result.cases.push({ name: 'synchronous-running-and-queued-timeout', responseStatuses: [200, 200],
          settledBeforeProviderRelease: false, lateAssistantMessages: 2 });
      } else {
        const responses = await Promise.all([timed, queuedTimed]);
        for (const response of responses) {
          assert.equal(response.status, 504); assert.equal(response.json.error.code, 'ASSISTANT_EXECUTION_TIMEOUT');
          assert.ok(!response.text.includes(sentinel));
        }
        assert.equal(held.size, 1); assert.equal(await questionCount(timedId, token), 1);
        assert.equal(await questionCount(queuedTimedId, token), 0); release();
        // Same one-worker pool: completion proves the late timed-out HTTP call really returned.
        const fresh = await request(route(await session(token)), token, { content: 'sync budget settlement positive control' });
        assert.equal(fresh.status, 200); assert.equal(fresh.json.data.content, sentinel);
        assert.equal(await answerCount(timedId, token), 0); assert.equal(await answerCount(queuedTimedId, token), 0);
        result.cases.push({ name: 'synchronous-running-and-queued-timeout', responseStatuses: [504, 504],
          settledBeforeProviderRelease: true, lateAssistantMessages: 0, queuedQuestionWrites: 0,
          lateWorkerSettledByFreshSynchronousAnswer: true });
      }
    }
    assert.equal(calls, baseline ? 4 : budgetBaseline ? 6 : 11, 'Every expected model call must use the actual HTTP adapter exactly once');
    result.providerTransportCalls = calls;
    result.status = baseline || budgetBaseline ? 'BASELINE_CAPTURED' : 'PASS';
  } catch (error) { result.status = 'FAIL'; result.failure = redact(error.message); throw error; }
  finally {
    process.off('SIGINT', interrupt); process.off('SIGTERM', interrupt); release();
    try { await stopProcess(child); result.ownedProcessStopped = true; }
    finally {
      provider.closeAllConnections(); await new Promise(resolve => provider.close(resolve)); result.ownedProviderStopped = true;
      fs.closeSync(descriptor);
      const content = redact(fs.readFileSync(log, 'utf8')); fs.writeFileSync(log, content);
      result.unexpectedJarErrors = unexpectedLogLines(content)
        + (content.match(/^.*(?:escaped|Unexpected error occurred in scheduled task|NoClassDefFoundError).*$/gm) || []).length;
      if (result.unexpectedJarErrors) { result.status = 'FAIL'; result.failure = 'Unexpected owned JAR errors including shutdown'; }
      fs.writeFileSync(path.join(evidence, 'result.json'), JSON.stringify(result, null, 2));
      console.log(JSON.stringify({ ...result, evidenceDirectory: path.relative(root, evidence) }));
      if (result.unexpectedJarErrors) throw new Error(result.failure);
    }
  }
}
if (require.main === module) verify().catch(error => { console.error(redact(error.message)); process.exitCode = 1; });

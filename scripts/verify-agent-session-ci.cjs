const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const http = require('node:http');
const { spawn, spawnSync } = require('node:child_process');
const { randomUUID, createHash } = require('node:crypto');
const { setTimeout: delay } = require('node:timers/promises');
const { requireFreePort, stopProcess, waitForHealth, redact, unexpectedLogLines } = require('./verify-oncall-browser-ci.cjs');

async function verify() {
  const baseline = process.env.OPSPILOT_AGENT_SESSION_BASELINE === '1';
  if (baseline && process.env.CI) throw new Error('Baseline capture cannot replace Agent session acceptance');
  const root = path.resolve(__dirname, '..');
  const jar = path.join(root, 'target', baseline ? 'cp67-before/opspilot-cp66.jar' : 'opspilot-0.1.0-SNAPSHOT.jar');
  assert.ok(fs.existsSync(jar), 'Build or preserve the scoped JAR first');
  await requireFreePort(9935);
  await requireFreePort(9936);
  const parent = path.join(root, 'target', 'agent-session-it');
  fs.mkdirSync(parent, { recursive: true });
  const evidence = fs.mkdtempSync(path.join(parent, 'run-'));
  const result = { status: 'RUNNING', baselineCapture: baseline, tokensPersistedToEvidence: false,
    jarSha256: createHash('sha256').update(fs.readFileSync(jar)).digest('hex'),
    evidenceDirectory: path.relative(root, evidence), fixture: 'owned real HTTP Prometheus response held until logout',
    database: 'fresh runner-owned H2 memory database', userFileDatabaseModified: false };
  const pending = new Set();
  let providerCalls = 0, released = false;
  function reply(response) {
    response.writeHead(200, { 'Content-Type': 'application/json' });
    response.end(JSON.stringify({ status: 'success', data: { resultType: 'vector', result: [
      { metric: { __name__: 'up', job: 'cp67-held-provider' }, value: [Math.floor(Date.now() / 1000), '1'] }
    ] } }));
  }
  const provider = http.createServer((request, response) => {
    if (!request.url.startsWith('/api/v1/query?')) { response.writeHead(404); response.end(); return; }
    providerCalls++;
    if (released) reply(response);
    else { pending.add(response); response.once('close', () => pending.delete(response)); }
  });
  await new Promise((resolve, reject) => {
    provider.once('error', reject); provider.listen(0, '127.0.0.1', resolve);
  });
  const log = path.join(evidence, 'jar.log');
  const descriptor = fs.openSync(log, 'w');
  const java = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java';
  const child = spawn(java, ['-Duser.timezone=UTC', '-jar', jar,
    '--server.address=127.0.0.1', '--server.port=9935', '--management.server.address=127.0.0.1', '--management.server.port=9936',
    '--spring.datasource.url=jdbc:h2:mem:agent_' + randomUUID().replaceAll('-', '') + ';MODE=MySQL;DATABASE_TO_LOWER=TRUE',
    '--spring.datasource.username=sa', '--spring.datasource.password=', '--spring.h2.console.enabled=false',
    '--opspilot.ai.enabled=false', '--opspilot.agent.recovery.enabled=false',
    '--opspilot.oncall.rotation.enabled=false', '--opspilot.oncall.escalation.enabled=false',
    '--opspilot.observability.prometheus.enabled=true',
    '--opspilot.observability.prometheus.base-url=http://127.0.0.1:' + provider.address().port,
    '--opspilot.observability.prometheus.read-timeout=10s', '--opspilot.observability.reliability.max-attempts=1',
    '--opspilot.agent.events.catchup-delay=250'], { cwd: root, stdio: ['ignore', descriptor, descriptor], windowsHide: true });
  child.once('error', error => { child.launchError = error; });
  result.startedPid = child.pid;
  const interrupt = () => child.kill('SIGTERM');
  process.once('SIGINT', interrupt); process.once('SIGTERM', interrupt);
  const streams = [];
  const base = 'http://127.0.0.1:9935/api/v1';
  async function request(route, token, body) {
    const response = await fetch(base + route, { method: body ? 'POST' : 'GET',
      headers: { ...(token ? { Authorization: 'Bearer ' + token } : {}), ...(body ? { 'Content-Type': 'application/json' } : {}) },
      body: body ? JSON.stringify(body) : undefined, signal: AbortSignal.timeout(8000) });
    return { status: response.status, json: await response.json() };
  }
  async function login(username) {
    const response = await request('/auth/login', null, { username, password: 'OpsPilot@2026' });
    assert.equal(response.status, 200); assert.ok(response.json.data.accessToken); return response.json.data.accessToken;
  }
  async function stream(route, token, post = false) {
    const controller = new AbortController();
    const response = await fetch(base + route, { method: post ? 'POST' : 'GET', signal: AbortSignal.any([controller.signal, AbortSignal.timeout(30000)]),
      headers: { Authorization: 'Bearer ' + token, ...(post ? { 'Idempotency-Key': randomUUID() } : {}) } });
    assert.equal(response.status, 200);
    const state = { runId: Number(response.headers.get('X-OpsPilot-Run-Id')), events: [], ended: false, controller };
    streams.push(state);
    state.read = (async () => {
      let buffer = '';
      for await (const chunk of response.body) {
        buffer += Buffer.from(chunk).toString('utf8');
        let index;
        while ((index = buffer.indexOf('\n')) >= 0) {
          const line = buffer.slice(0, index).trim(); buffer = buffer.slice(index + 1);
          if (line.startsWith('event:')) state.events.push(line.slice(6));
        }
      }
      state.ended = true;
    })().catch(error => { state.readError = error.name; });
    return state;
  }
  async function waitFor(predicate, budget = 5000) {
    const until = Date.now() + budget;
    while (!predicate() && Date.now() < until) await delay(25);
    assert.ok(predicate(), 'Owned acceptance condition exceeded its bounded observation budget');
  }
  function release() { released = true; for (const response of pending) reply(response); pending.clear(); }
  try {
    await waitForHealth('http://127.0.0.1:9936/actuator/health', child);
    const token = await login('zhangwei'), peer = await login('auditor');
    const post = await stream('/incidents/1/investigations/stream', token, true);
    assert.ok(post.runId > 0);
    await waitFor(() => providerCalls > 0);
    const revoked = await stream('/agent-runs/' + post.runId + '/events/stream', token);
    const other = await stream('/agent-runs/' + post.runId + '/events/stream', peer);
    await waitFor(() => revoked.events.length > 0 && other.events.length > 0);
    const logout = await request('/auth/logout-all', token, {});
    assert.equal(logout.status, 200);
    if (baseline) await delay(2000);
    else await waitFor(() => revoked.ended && post.ended, 4000);
    Object.assign(result, { logoutStatus: logout.status, revokedGetClosedBeforeProviderRelease: revoked.ended,
      revokedPostClosedBeforeProviderRelease: post.ended, otherAccountStreamStillOpenBeforeRelease: !other.ended });
    assert.equal(other.ended, false, 'An unrelated current session must remain usable');
    if (baseline) { assert.equal(revoked.ended, false); assert.equal(post.ended, false); }
    release();
    let run;
    // Interrupting Future does not guarantee the synchronous HTTP driver's socket read stops.
    // Observe settlement past the configured Provider read budget, not an immediate-stop promise.
    const releasedAt = Date.now(), until = releasedAt + 15000;
    let diagnosed = false;
    do {
      const response = await request('/incidents/1/agent-runs', peer);
      assert.equal(response.status, 200); run = response.json.data.find(value => value.id === post.runId);
      if (run && !['QUEUED', 'RUNNING'].includes(run.status)) break;
      if (!diagnosed && process.env.OPSPILOT_AGENT_DIAGNOSTICS === '1' && Date.now() - releasedAt > 3000) {
        diagnosed = true;
        const jcmd = path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'jcmd.exe' : 'jcmd');
        const dump = spawnSync(jcmd, [String(child.pid), 'Thread.print'], { timeout: 5000, encoding: 'utf8', windowsHide: true });
        const threads = (dump.stdout || '').split(/\r?\n\r?\n/).filter(block => /^"opspilot-(agent|catchup|sse)-/m.test(block));
        fs.writeFileSync(path.join(evidence, 'owned-agent-threads.log'), redact(threads.join('\n\n')));
      }
      await delay(25);
    } while (Date.now() < until);
    Object.assign(result, { finalRunStatus: run.status, reportCreated: Boolean(run.reportId),
      observedTerminationKind: run.terminationKind, providerRequests: providerCalls,
      settlementAfterReleaseMs: Date.now() - releasedAt, providerReadBudgetMs: 10000 });
    const events = await request('/agent-runs/' + post.runId + '/events', peer);
    assert.equal(events.status, 200);
    result.persistedEventTypes = events.json.data.map(value => value.eventType);
    assert.equal(run.status, baseline ? 'COMPLETED' : 'CANCELLED');
    assert.equal(Boolean(run.reportId), baseline);
    await waitFor(() => other.ended);
    assert.equal((await request('/auth/me', token)).status, 401);
    assert.equal((await request('/auth/me', peer)).status, 200);
    const types = events.json.data.map(value => value.eventType);
    if (!baseline) { assert.ok(types.includes('RUN_CANCELLED')); assert.ok(!types.includes('RUN_COMPLETED')); assert.ok(!types.includes('ACTION_PROPOSED')); }
    Object.assign(result, { status: baseline ? 'BASELINE_CAPTURED' : 'PASS', runId: post.runId, finalRunStatus: run.status,
      reportCreated: Boolean(run.reportId), providerRequests: providerCalls, persistedEventTypes: types,
      otherAccountRemainsAuthorized: true, revokedTokenRejectedOnNextRequest: true });
    if (!baseline) {
      const freshToken = await login('zhangwei');
      const fresh = await stream('/incidents/1/investigations/stream', freshToken, true);
      await waitFor(() => fresh.ended, 8000);
      const current = await request('/incidents/1/agent-runs', peer);
      assert.equal(current.status, 200);
      const freshRun = current.json.data.find(value => value.id === fresh.runId);
      assert.equal(freshRun.status, 'COMPLETED'); assert.ok(freshRun.reportId);
      assert.equal(providerCalls, 2, 'Both original and fresh runs must use the actual HTTP Provider');
      result.providerRequests = providerCalls;
      result.freshSessionCompletesAfterInterruptedRun = true;
    }
  } catch (error) { result.status = 'FAIL'; result.failure = redact(error.message); throw error; }
  finally {
    process.off('SIGINT', interrupt); process.off('SIGTERM', interrupt);
    release();
    for (const state of streams) state.controller.abort();
    await Promise.all(streams.map(state => state.read));
    try { await stopProcess(child); result.ownedProcessStopped = true; }
    finally {
      provider.closeAllConnections();
      await new Promise(resolve => provider.close(resolve));
      result.ownedProviderStopped = true;
      fs.closeSync(descriptor);
      const content = redact(fs.readFileSync(log, 'utf8'));
      fs.writeFileSync(log, content);
      result.unexpectedJarErrors = unexpectedLogLines(content)
        + (content.match(/^.*Agent run \d+ escaped .*$/gm) || []).length;
      if (result.unexpectedJarErrors) { result.status = 'FAIL'; result.failure = 'Unexpected owned JAR errors including shutdown'; }
      fs.writeFileSync(path.join(evidence, 'result.json'), JSON.stringify(result, null, 2));
      console.log(JSON.stringify(result));
      if (result.unexpectedJarErrors) throw new Error(result.failure);
    }
  }
}
if (require.main === module) verify().catch(error => { console.error(redact(error.message)); process.exitCode = 1; });

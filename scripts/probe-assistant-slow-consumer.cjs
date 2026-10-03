const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const http = require('node:http');
const net = require('node:net');
const { spawn, execFileSync } = require('node:child_process');
const { randomUUID, createHash } = require('node:crypto');
const { setTimeout: delay } = require('node:timers/promises');
const { requireFreePort, waitForHealth, stopProcess, redact, unexpectedLogLines } = require('./verify-oncall-browser-ci.cjs');

async function probe() {
  if (process.env.CI) throw new Error('Investigation probe cannot replace slow-consumer acceptance');
  const root = path.resolve(__dirname, '..'), jar = path.join(root, 'target/cp77-before/opspilot-cp76.jar');
  assert.ok(fs.existsSync(jar), 'Preserve the current baseline JAR before investigating');
  await requireFreePort(9971); await requireFreePort(9972);
  const parent = path.join(root, 'target/assistant-slow-consumer-it'); fs.mkdirSync(parent, { recursive: true });
  const evidence = fs.mkdtempSync(path.join(parent, 'probe-'));
  const controls = new Map(), sockets = [];
  const frame = (content, finish_reason = 'null') => 'data:' + JSON.stringify({ request_id: randomUUID(), output: {
    choices: [{ finish_reason, message: { role: 'assistant', content } }] }, usage: { input_tokens: 1, output_tokens: 1, total_tokens: 2 } }) + '\n\n';
  function release(c) {
    c.released = true;
    if (c.response && !c.response.destroyed && !c.response.writableEnded) {
      if (c.native) c.response.end(frame('end', 'stop'));
      else require('./assistant-provider-fixture.cjs').reply(c.response, 'fresh-answer', false);
    }
  }
  const provider = http.createServer((request, response) => {
    let raw = ''; request.on('data', chunk => { raw += chunk; }); request.once('end', () => {
      const question = JSON.parse(raw).input?.messages?.at(-1)?.content;
      const c = [...controls.values()].find(x => question?.endsWith(x.question));
      if (!c) { response.writeHead(400); response.end(); return; }
      c.native = request.headers['x-dashscope-sse'] === 'enable'; c.calls++; c.response = response;
      response.once('close', () => { c.closed = true; });
      if (c.native) { response.writeHead(200, { 'Content-Type': 'text/event-stream' }); response.write(frame('preview')); }
      c.entered = true; if (c.released) release(c);
    });
  });
  await new Promise((resolve, reject) => { provider.once('error', reject); provider.listen(0, '127.0.0.1', resolve); });
  const javaHome = process.env.JAVA_HOME, executable = name => javaHome ? path.join(javaHome, 'bin', name + (process.platform === 'win32' ? '.exe' : '')) : name;
  const file = path.join(evidence, 'jar.log'), fd = fs.openSync(file, 'w');
  const providerUrl = 'http://127.0.0.1:' + provider.address().port;
  const child = spawn(executable('java'), ['-Duser.timezone=UTC', '-jar', jar,
    '--server.address=127.0.0.1', '--server.port=9971', '--management.server.address=127.0.0.1', '--management.server.port=9972',
    '--spring.datasource.url=jdbc:h2:mem:slow_' + randomUUID().replaceAll('-', '') + ';MODE=MySQL;DATABASE_TO_LOWER=TRUE',
    '--spring.datasource.username=sa', '--spring.datasource.password=', '--spring.h2.console.enabled=false',
    '--opspilot.ai.enabled=true', '--spring.ai.dashscope.api-key=cp77-not-a-real-key',
    '--spring.ai.dashscope.base-url=' + providerUrl, '--spring.ai.dashscope.chat.base-url=' + providerUrl,
    '--opspilot.assistant.workers=1', '--opspilot.assistant.queue-capacity=1', '--opspilot.assistant.execution-timeout=45s',
    '--opspilot.assistant.authorization-check-delay=100', '--opspilot.agent.recovery.enabled=false',
    '--opspilot.oncall.rotation.enabled=false', '--opspilot.oncall.escalation.enabled=false'],
    { cwd: root, stdio: ['ignore', fd, fd], windowsHide: true });
  child.once('error', e => { child.launchError = e; });
  const result = { status: 'PROBING', jarSha256: createHash('sha256').update(fs.readFileSync(jar)).digest('hex'),
    ownedPid: child.pid, browserSocketPaused: false, outputCharacters: 99907, nativeFramesBeforeRelease: 9991,
    tokensPersisted: false, userFileDatabaseModified: false };
  const interrupt = () => { sockets.forEach(s => s.destroy()); if (child.exitCode === null && child.signalCode === null) child.kill('SIGTERM'); };
  process.once('SIGINT', interrupt); process.once('SIGTERM', interrupt);
  const base = 'http://127.0.0.1:9971/api/v1';
  async function api(route, token, body, key, method = body ? 'POST' : 'GET') {
    const response = await fetch(base + route, { method, headers: { ...(token ? { Authorization: 'Bearer ' + token } : {}),
      ...(body ? { 'Content-Type': 'application/json' } : {}), ...(key ? { 'Idempotency-Key': key } : {}) },
      body: body ? JSON.stringify(body) : undefined, signal: AbortSignal.timeout(12000) });
    return { status: response.status, json: await response.json() };
  }
  async function until(predicate, ms = 4000) { const deadline = Date.now() + ms; do { if (await predicate()) return; await delay(25); } while (Date.now() < deadline); assert.fail('Controlled slow-consumer condition did not settle'); }
  function control(name, released = false) { const c = { question: 'cp77-' + name + '-' + randomUUID(), released, calls: 0, closed: false }; controls.set(c.question, c); return c; }
  function threads() { return execFileSync(executable('jcmd'), [String(child.pid), 'Thread.print'], { encoding: 'utf8', timeout: 4000, windowsHide: true }); }
  try {
    await waitForHealth('http://127.0.0.1:9972/actuator/health', child);
    const token = (await api('/auth/login', null, { username: 'admin', password: 'OpsPilot@2026' })).json.data.accessToken;
    const session = async () => (await api('/assistant/sessions', token, {})).json.data.session.id;
    const id = await session(), key = randomUUID(), c = control('paused');
    const body = JSON.stringify({ content: c.question });
    const socket = net.createConnection({ host: '127.0.0.1', port: 9971 }); sockets.push(socket); socket.on('error', () => {});
    await new Promise(resolve => socket.once('connect', resolve));
    let initial = ''; socket.on('data', chunk => { initial += chunk.toString(); if (initial.includes('event:token')) { socket.pause(); result.browserSocketPaused = true; } });
    socket.write('POST /api/v1/assistant/sessions/' + id + '/stream HTTP/1.1\r\nHost: 127.0.0.1:9971\r\nAuthorization: Bearer ' + token
      + '\r\nIdempotency-Key: ' + key + '\r\nAccept: text/event-stream, application/json\r\nContent-Type: application/json\r\nContent-Length: '
      + Buffer.byteLength(body) + '\r\nConnection: close\r\n\r\n' + body);
    await until(() => result.browserSocketPaused && c.entered); assert.equal(c.native, true);
    const block = frame('\u0001'.repeat(9) + 'X');
    let sent = 0; // Bound the source below the existing model response limits; no STOP until explicit release.
    while (sent < 9990 && !c.closed) {
      const writable = c.response.write(block); sent++;
      if (!writable) await Promise.race([new Promise(resolve => c.response.once('drain', resolve)), delay(500)]);
      if (sent % 100 === 0) await delay(1);
    }
    result.providerFramesWritten = sent;
    let dump = '', section = '';
    for (let i = 0; i < 16; i++) {
      dump = threads(); section = dump.split(/\r?\n\r?\n/).find(s => s.startsWith('"opspilot-assistant-1"')) || '';
      if (/org\.apache\.tomcat\.util\.net\./.test(section)) break; await delay(500);
    }
    fs.writeFileSync(path.join(evidence, 'paused-thread.txt'), redact(dump));
    result.assistantWorkerBlockedInTomcatWrite = /org\.apache\.tomcat\.util\.net\./.test(section);
    if (!result.assistantWorkerBlockedInTomcatWrite) { result.status = 'NOT_REPRODUCED'; return; }
    let cancelSettled = false;
    const cancelled = api('/assistant/sessions/' + id + '/request/cancel', token, undefined, key, 'POST').then(r => { cancelSettled = true; return r; });
    cancelled.catch(() => {}); await delay(1500);
    result.cancelReturnedBeforeSocketResume = cancelSettled;
    result.modelHttpClosedBeforeSocketResume = c.closed;
    const fresh = control('fresh', true), nextId = await session();
    const next = api('/assistant/sessions/' + nextId + '/messages', token, { content: fresh.question }, randomUUID()); next.catch(() => {});
    await delay(1500); result.newWorkEnteredBeforeSocketResume = fresh.calls > 0;
    const state = await api('/assistant/sessions/' + id + '/request', token, undefined, key);
    result.persistedStatusBeforeSocketResume = state.json.data.status;
    result.oldProviderReleasedBeforeObservation = c.released;
    socket.removeAllListeners('data'); socket.on('data', () => {}); socket.resume();
    await cancelled; await until(() => c.closed); assert.equal((await next).status, 200);
    const messages = (await api('/assistant/sessions/' + id, token)).json.data.messages;
    result.cancelledAnswerRows = messages.filter(m => m.role === 'ASSISTANT').length;
    result.status = 'BASELINE_CAPTURED';
  } catch (e) { result.status = 'FAIL'; result.failure = redact(e.stack); throw e; }
  finally {
    process.off('SIGINT', interrupt); process.off('SIGTERM', interrupt); sockets.forEach(s => s.destroy()); controls.forEach(release);
    await stopProcess(child); result.ownedProcessStopped = true; provider.closeAllConnections(); await new Promise(resolve => provider.close(resolve));
    result.ownedProviderStopped = true; fs.closeSync(fd); const logs = redact(fs.readFileSync(file, 'utf8')); fs.writeFileSync(file, logs);
    result.unexpectedJarErrors = unexpectedLogLines(logs); fs.writeFileSync(path.join(evidence, 'result.json'), JSON.stringify(result, null, 2));
    console.log(JSON.stringify({ ...result, evidenceDirectory: path.relative(root, evidence) }));
  }
}
if (require.main === module) probe().catch(e => { console.error(redact(e.message)); process.exitCode = 1; });

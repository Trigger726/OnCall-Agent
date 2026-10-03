const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const http = require('node:http');
const { StringDecoder } = require('node:string_decoder');
const { spawn, execFileSync } = require('node:child_process');
const { randomUUID, createHash } = require('node:crypto');
const { setTimeout: delay } = require('node:timers/promises');
const { requireFreePort, waitForHealth, stopProcess, redact, unexpectedLogLines } = require('./verify-oncall-browser-ci.cjs');

const sections = dump => dump.split(/\r?\n\r?\n/);
const outputBlocked = section => section.startsWith('"opspilot-assistant-output-') && /NioSocketWrapper\.doWrite/.test(section);
const blockedOutputThreads = dump => [...new Set(sections(dump).filter(outputBlocked).map(section => section.match(/^"([^"]+)"/)[1]))];

// Require both writers in ONE actual snapshot, never accumulate separate observations into a passing pair.
async function observeSaturation(capture, budgetMs = 4000) {
  assert.ok(Number.isInteger(budgetMs) && budgetMs > 0 && budgetMs <= 4000);
  const started = performance.now(), samples = [];
  let dump = '', satisfied = false, elapsedMs = 0;
  do {
    dump = capture(); elapsedMs = performance.now() - started;
    const blockedThreads = blockedOutputThreads(dump);
    samples.push({ milliseconds: Math.round(elapsedMs), blockedThreads });
    satisfied = blockedThreads.length === 2 && elapsedMs <= budgetMs;
    if (satisfied || elapsedMs >= budgetMs) break;
    await delay(Math.min(25, budgetMs - elapsedMs));
  } while (performance.now() - started < budgetMs);
  return { dump, satisfied, deadlineMs: budgetMs, elapsedMs, samples };
}

async function verify() {
  if (process.env.OPSPILOT_ASSISTANT_SLOW_CONSUMER_BASELINE) throw new Error('Baseline capture cannot replace slow-consumer acceptance');
  const root = path.resolve(__dirname, '..'), jar = path.join(root, 'target/opspilot-0.1.0-SNAPSHOT.jar');
  assert.ok(fs.existsSync(jar), 'Build the scoped production JAR first');
  await requireFreePort(9971); await requireFreePort(9972);
  const parent = path.join(root, 'target/assistant-slow-consumer-it'); fs.mkdirSync(parent, { recursive: true });
  const evidence = fs.mkdtempSync(path.join(parent, 'run-'));
  const controls = new Map(), sockets = [], logs = [], descriptors = [];
  const result = { status: 'RUNNING', baselineCapture: false,
    fixture: 'owned real native model HTTP and paused TCP SSE clients; actual Tomcat write stacks required',
    jarSha256: createHash('sha256').update(fs.readFileSync(jar)).digest('hex'),
    modelWorkers: 1, modelQueueCapacity: 1, outputWriters: 2, outputQueueCapacity: 1,
    maximumCharactersBeforeRelease: 99907, maximumNativeFramesBeforeRelease: 9991,
    tokensPersistedToEvidence: false, userFileDatabaseModified: false, cases: [], startedPids: [], stoppedPids: [] };
  const executable = name => process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', name + (process.platform === 'win32' ? '.exe' : '')) : name;
  let clientExecutable, clientArguments;
  if (process.platform === 'linux') {
    // Receive buffer alone did not create Linux backpressure within the existing production output limits.
    // A small MSS is advertised by this owned client before connect; no server or global network settings change.
    clientExecutable = 'python3'; clientArguments = [path.join(root, 'scripts/fixtures/assistant_slow_tcp_client.py')];
    execFileSync(clientExecutable, ['-c', 'import socket; assert hasattr(socket, "TCP_MAXSEG")'], { timeout: 15000 });
    result.tcpClientImplementation = 'Linux per-client receive buffer and advertised MSS';
  } else {
    const fixtureClasses = path.join(evidence, 'tcp-client-classes'); fs.mkdirSync(fixtureClasses);
    // Check dependencies before opening the provider or starting any owned process.
    execFileSync(executable('javac'), ['--release', '17', '-d', fixtureClasses,
      path.join(root, 'scripts/fixtures/AssistantSlowTcpClient.java')], { timeout: 15000, windowsHide: true });
    clientExecutable = executable('java'); clientArguments = ['-cp', fixtureClasses, 'AssistantSlowTcpClient'];
    result.tcpClientImplementation = 'JDK per-client receive buffer';
  }
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
    if (request.method !== 'POST' || request.url !== '/api/v1/services/aigc/text-generation/generation') { response.writeHead(404); response.end(); return; }
    let raw = ''; request.on('data', chunk => { raw += chunk; }); request.once('end', () => {
      const body = JSON.parse(raw), question = body.input?.messages?.at(-1)?.content;
      const c = [...controls.values()].find(x => question?.endsWith(x.question));
      if (!c) { response.writeHead(400); response.end(); return; }
      c.native = request.headers['x-dashscope-sse'] === 'enable'; c.incremental = body.parameters?.incremental_output === true;
      c.calls++; c.response = response; response.once('close', () => { c.closed = true; });
      if (c.native) { response.writeHead(200, { 'Content-Type': 'text/event-stream' }); response.write(frame('preview')); }
      c.entered = true; if (c.released) release(c);
    });
  });
  await new Promise((resolve, reject) => { provider.once('error', reject); provider.listen(0, '127.0.0.1', resolve); });
  let child;
  const interrupt = () => { sockets.forEach(s => s.socket.destroy()); if (child?.exitCode === null && child.signalCode === null) child.kill('SIGTERM'); };
  process.once('SIGINT', interrupt); process.once('SIGTERM', interrupt);
  async function start(seconds, outputWriters = 2, outputQueue = 1) {
    const file = path.join(evidence, 'jar-' + (logs.length + 1) + '.log'), fd = fs.openSync(file, 'w'); logs.push(file); descriptors.push(fd);
    const providerUrl = 'http://127.0.0.1:' + provider.address().port;
    child = spawn(executable('java'), ['-Duser.timezone=UTC', '-jar', jar,
      '--server.address=127.0.0.1', '--server.port=9971', '--management.server.address=127.0.0.1', '--management.server.port=9972',
      '--spring.datasource.url=jdbc:h2:mem:slow_' + randomUUID().replaceAll('-', '') + ';MODE=MySQL;DATABASE_TO_LOWER=TRUE',
      '--spring.datasource.username=sa', '--spring.datasource.password=', '--spring.h2.console.enabled=false',
      '--opspilot.ai.enabled=true', '--spring.ai.dashscope.api-key=cp77-not-a-real-key',
      '--spring.ai.dashscope.base-url=' + providerUrl, '--spring.ai.dashscope.chat.base-url=' + providerUrl,
      '--opspilot.assistant.workers=1', '--opspilot.assistant.queue-capacity=1', '--opspilot.assistant.execution-timeout=' + seconds + 's',
      '--opspilot.assistant.stream-writers=' + outputWriters, '--opspilot.assistant.stream-queue-capacity=' + outputQueue,
      '--opspilot.assistant.authorization-check-delay=100', '--opspilot.agent.recovery.enabled=false',
      '--opspilot.oncall.rotation.enabled=false', '--opspilot.oncall.escalation.enabled=false'],
      { cwd: root, stdio: ['ignore', fd, fd], windowsHide: true });
    child.once('error', e => { child.launchError = e; }); result.startedPids.push(child.pid);
    await waitForHealth('http://127.0.0.1:9972/actuator/health', child);
  }
  async function stop() { if (child) { await stopProcess(child); result.stoppedPids.push(child.pid); child = null; } }
  const base = 'http://127.0.0.1:9971/api/v1';
  async function api(route, token, body, key, method = body ? 'POST' : 'GET') {
    const response = await fetch(base + route, { method, headers: { ...(token ? { Authorization: 'Bearer ' + token } : {}),
      ...(body ? { 'Content-Type': 'application/json' } : {}), ...(key ? { 'Idempotency-Key': key } : {}), Accept: 'text/event-stream, application/json' },
      body: body ? JSON.stringify(body) : undefined, signal: AbortSignal.timeout(12000) });
    return { status: response.status, json: await response.json() };
  }
  async function login() { const r = await api('/auth/login', null, { username: 'admin', password: 'OpsPilot@2026' }); assert.equal(r.status, 200); return r.json.data.accessToken; }
  async function session(token) { const r = await api('/assistant/sessions', token, {}); assert.equal(r.status, 200); return r.json.data.session.id; }
  const route = id => '/assistant/sessions/' + id;
  const state = (id, key, token) => api(route(id) + '/request', token, undefined, key);
  async function counts(id, token) { const r = await api(route(id), token); assert.equal(r.status, 200); return { user: r.json.data.messages.filter(m => m.role === 'USER').length, assistant: r.json.data.messages.filter(m => m.role === 'ASSISTANT').length }; }
  async function until(predicate, ms = 4000) { const deadline = Date.now() + ms; do { if (await predicate()) return; await delay(25); } while (Date.now() < deadline); assert.fail('Controlled slow-consumer condition did not settle'); }
  function control(name, released = false) { const c = { question: 'cp77-' + name + '-' + randomUUID(), released, calls: 0, closed: false }; controls.set(c.question, c); return c; }
  const threads = () => execFileSync(executable('jcmd'), [String(child.pid), 'Thread.print'], { encoding: 'utf8', timeout: 4000, windowsHide: true });
  function stream(id, token, key, c) {
    const pending = (async () => {
      const response = await fetch(base + route(id) + '/stream', { method: 'POST', headers: { Authorization: 'Bearer ' + token,
        'Content-Type': 'application/json', Accept: 'text/event-stream, application/json', 'Idempotency-Key': key },
        body: JSON.stringify({ content: c.question }), signal: AbortSignal.timeout(60000) });
      return { status: response.status, body: await response.text() };
    })(); pending.catch(() => {}); return pending;
  }
  async function freshNative(token, name) {
    const c = control(name, true), id = await session(token);
    const r = await stream(id, token, randomUUID(), c); assert.equal(r.status, 200); assert.ok(r.body.includes('event:token') && r.body.includes('event:done'));
    assert.equal(c.calls, 1); assert.equal(c.native, true); assert.deepEqual(await counts(id, token), { user: 1, assistant: 1 });
    const saved = (await api(route(id), token)).json.data.messages.find(m => m.role === 'ASSISTANT');
    const done = r.body.split(/\r?\n/).filter(line => line.startsWith('data:')).map(line => JSON.parse(line.slice(5))).find(event => event.type === 'done');
    assert.ok(saved.id > 0); assert.equal(done.messageId, saved.id); assert.equal(saved.content, 'previewend');
  }
  async function paused(token, name) {
    // Do not misattribute a previous client's blocked thread to the newly paused socket.
    const previous = new Set(sections(threads()).filter(outputBlocked).map(section => section.match(/^"([^"]+)"/)[1]));
    const id = await session(token), key = randomUUID(), c = control(name), body = JSON.stringify({ content: c.question });
    const client = spawn(clientExecutable, clientArguments, { stdio: ['pipe', 'pipe', 'pipe'], windowsHide: true });
    const s = { client, id, key, c, paused: false, closed: false, raw: '', decoder: new StringDecoder('utf8'), stderr: '' };
    s.socket = { destroy: () => client.kill('SIGTERM'), resume: () => client.stdin.write('RESUME\n') }; sockets.push(s);
    let lines = '';
    client.stdout.on('data', chunk => {
      lines += chunk.toString('utf8');
      for (let newline; (newline = lines.indexOf('\n')) !== -1;) {
        const line = lines.slice(0, newline).replace(/\r$/, ''); lines = lines.slice(newline + 1);
        if (line.startsWith('BUFFER ')) s.receiveBufferBytes = Number(line.slice(7));
        else if (line.startsWith('MSS ')) s.maximumSegmentBytes = Number(line.slice(4));
        else if (line.startsWith('PAUSED ')) { s.raw += s.decoder.write(Buffer.from(line.slice(7), 'base64')); s.paused = true; }
        else if (line.startsWith('DRAINED ')) { s.raw += s.decoder.write(Buffer.from(line.slice(8), 'base64')); s.drained = true; }
        else s.protocolError = 'Unexpected TCP fixture output';
      }
    });
    client.stderr.on('data', chunk => { s.stderr += redact(chunk.toString('utf8')); });
    client.stdin.on('error', error => { s.protocolError = 'TCP fixture input closed: ' + error.code; });
    client.once('error', error => { s.launchError = error.message; });
    client.once('close', code => { s.exitCode = code; s.closed = true; });
    const request = 'POST /api/v1/assistant/sessions/' + id + '/stream HTTP/1.1\r\nHost: 127.0.0.1:9971\r\nAuthorization: Bearer ' + token
      + '\r\nIdempotency-Key: ' + key + '\r\nAccept: text/event-stream, application/json\r\nContent-Type: application/json\r\nContent-Length: '
      + Buffer.byteLength(body) + '\r\nConnection: close\r\n\r\n' + body;
    client.stdin.write(Buffer.from(request).toString('base64') + '\n');
    await until(() => s.paused && c.entered);
    assert.ok(s.receiveBufferBytes > 0 && s.receiveBufferBytes <= 16384, 'An actually constrained client receive window is required');
    if (process.platform === 'linux') assert.ok(s.maximumSegmentBytes > 0 && s.maximumSegmentBytes <= 256,
      'The Linux client must observe the actual small MSS negotiated before connect');
    assert.equal(s.protocolError, undefined); assert.equal(c.native, true); assert.equal(c.incremental, true);
    result.tcpClients ??= []; result.tcpClients.push({ case: name, pid: client.pid, receiveBufferBytes: s.receiveBufferBytes, receiveBufferSetBeforeConnect: true,
      ...(process.platform === 'linux' ? { maximumSegmentBytes: s.maximumSegmentBytes, maximumSegmentSetBeforeConnect: true } : {}) });
    const block = frame('\u0001'.repeat(9) + 'X'); let sent = 0;
    while (sent < 9990 && !c.closed) {
      const writable = c.response.write(block); sent++;
      if (!writable) await new Promise(resolve => {
        const ready = () => { clearTimeout(timer); c.response.off('drain', ready); resolve(); };
        const timer = setTimeout(ready, 500); c.response.once('drain', ready);
      });
      if (sent % 100 === 0) await delay(1);
    }
    c.framesWritten = sent + 1; c.charactersWritten = 7 + sent * 10;
    let dump = '', output;
    for (let i = 0; i < 16; i++) {
      dump = threads(); const threadSections = sections(dump);
      output = threadSections.find(section => outputBlocked(section) && !previous.has(section.match(/^"([^"]+)"/)[1]));
      if (output) {
        const worker = threadSections.find(section => section.startsWith('"opspilot-assistant-1"')) || '';
        assert.ok(!/org\.apache\.tomcat\.util\.net\./.test(worker), 'Model worker must not write the servlet socket'); break;
      }
      await delay(500);
    }
    fs.writeFileSync(path.join(evidence, name + '-threads.txt'), redact(dump));
    assert.ok(output, 'An actual blocked OUTPUT thread is required; a fast socket is not a passing slow-consumer test');
    s.blockedObservedAt = performance.now();
    result.blockedOutputs ??= []; result.blockedOutputs.push({ case: name, thread: output.match(/^"([^"]+)"/)[1], previouslyBlockedThreads: [...previous],
      newlyBlockedThreadRequired: true, modelWorkerDirectServletWrite: false });
    assert.equal(c.released, false); assert.equal(s.paused, true); return s;
  }
  async function cancel(s, token) {
    assert.equal((await state(s.id, s.key, token)).json.data.status, 'RUNNING');
    const started = Date.now(), r = await api(route(s.id) + '/request/cancel', token, undefined, s.key, 'POST');
    assert.equal(r.status, 200); assert.equal(r.json.data.status, 'CANCELLED'); assert.ok(Date.now() - started < 4000);
    await until(() => s.c.closed); assert.equal(s.paused, true); assert.equal(s.c.released, false);
    assert.deepEqual(await counts(s.id, token), { user: 1, assistant: 0 });
    assert.equal((await api(route(s.id) + '/request/cancel', token, undefined, s.key, 'POST')).json.data.status, 'CANCELLED');
    const audit = await api('/audit-logs?limit=500', token);
    assert.equal(audit.json.data.filter(a => a.action === 'ASSISTANT_REQUEST_CANCEL' && a.targetId === String(s.id)).length, 1);
  }
  async function drain(s) {
    s.paused = false; s.socket.resume();
    await until(() => s.closed, 12000); assert.equal(s.exitCode, 0, s.stderr); assert.equal(s.drained, true);
    assert.equal(s.protocolError, undefined); assert.ok(!s.raw.includes('event:done'));
  }
  try {
    await start(45); let token = await login();
    const first = await paused(token, 'cancel'); await cancel(first, token);
    await freshNative(token, 'fresh-before-resume'); assert.equal(first.paused, true); assert.equal(first.c.released, false);
    result.cases.push({ name: 'cancel-slow-client', cancelStatus: 200, requestStatus: 'CANCELLED', cancelledAnswerRows: 0, cancellationAudits: 1,
      modelHttpClosedBeforeSocketResume: true, sameWorkerCompletedNativeAnswerBeforeSocketResume: true, providerReleased: false });

    const second = await paused(token, 'saturation'); await cancel(second, token);
    const queued = control('queued-output'), queuedId = await session(token), queuedKey = randomUUID(), queuedStream = stream(queuedId, token, queuedKey, queued);
    let queuedStreamSettled = false; queuedStream.then(() => { queuedStreamSettled = true; }, () => { queuedStreamSettled = true; });
    await until(() => queued.entered); assert.equal((await api(route(queuedId) + '/request/cancel', token, undefined, queuedKey, 'POST')).json.data.status, 'CANCELLED');
    await until(() => queued.closed); assert.equal(queued.released, false);
    const observation = await observeSaturation(() => {
      assert.equal(first.paused, true); assert.equal(first.closed, false); assert.equal(first.c.released, false);
      assert.equal(second.paused, true); assert.equal(second.closed, false); assert.equal(second.c.released, false);
      return threads();
    });
    // Preserve the last real stack and observations BEFORE assertions, including a failed saturation setup.
    fs.writeFileSync(path.join(evidence, 'saturated-output-threads.txt'), redact(observation.dump));
    result.outputSaturationObservation = { ...observation, dump: undefined, queuedStreamSettled,
      firstPaused: first.paused, firstClientExited: first.closed, secondPaused: second.paused, secondClientExited: second.closed };
    assert.equal(observation.satisfied, true, 'Two actual output writers did not block simultaneously within the fixed 4s observation bound');
    assert.equal(blockedOutputThreads(observation.dump).length, 2); assert.equal(queuedStreamSettled, false);
    const rejected = control('rejected-output'), rejectedId = await session(token), rejectedKey = randomUUID();
    const rejectedResponse = await api(route(rejectedId) + '/stream', token, { content: rejected.question }, rejectedKey);
    assert.equal(rejectedResponse.status, 503); assert.equal(rejectedResponse.json.error.code, 'ASSISTANT_STREAM_SATURATED');
    assert.equal((await state(rejectedId, rejectedKey, token)).status, 404); assert.deepEqual(await counts(rejectedId, token), { user: 0, assistant: 0 }); assert.equal(rejected.calls, 0);
    const sync = control('sync-control-during-output-saturation', true);
    assert.equal((await api(route(await session(token)) + '/messages', token, { content: sync.question }, randomUUID())).status, 200);
    assert.equal(first.paused, true); assert.equal(second.paused, true); assert.equal(sync.calls, 1);
    result.cases.push({ name: 'bounded-output-saturation', blockedWriters: 2, queuedTransport: 1, rejectedStatus: 503,
      rejectionCode: rejectedResponse.json.error.code, rejectedQuestionRows: 0, rejectedModelCalls: 0, rejectedKeyStatus: 404,
      queuedRequestCancelledAndModelHttpClosedBeforeWriterAvailable: true, synchronousWorkerStillUsable: true });
    await drain(first); await drain(second); const settled = await queuedStream; assert.ok(settled.body.includes('event:cancelled')); assert.ok(!settled.body.includes('event:done'));
    release(first.c); release(second.c); release(queued);

    const revoked = await paused(token, 'revoke'); assert.equal((await api('/auth/logout-all', token, {})).status, 200);
    await until(() => revoked.c.closed); assert.equal(revoked.paused, true); assert.equal(revoked.c.released, false);
    assert.equal((await api('/auth/me', token)).status, 401); token = await login();
    assert.equal((await state(revoked.id, revoked.key, token)).json.data.status, 'REVOKED'); assert.deepEqual(await counts(revoked.id, token), { user: 1, assistant: 0 });
    await freshNative(token, 'fresh-after-revoke-before-resume'); assert.equal(revoked.paused, true);
    await drain(revoked); assert.ok(!revoked.raw.includes('event:error') && !revoked.raw.includes('event:cancelled')); release(revoked.c);
    result.cases.push({ name: 'revoke-slow-client', persistedStatus: 'REVOKED', oldTokenStatus: 401, answerRows: 0,
      modelHttpClosedBeforeSocketResume: true, sameWorkerCompletedNativeAnswerBeforeSocketResume: true, noRevokedTerminalPayload: true,
      alreadyBufferedBytesRecallClaimed: false });
    await stop(); await requireFreePort(9971); await requireFreePort(9972);

    await start(12); token = await login(); const timed = await paused(token, 'timeout');
    await until(async () => (await state(timed.id, timed.key, token)).json.data.status === 'TIMED_OUT', 16000);
    await until(() => timed.c.closed); assert.equal(timed.paused, true); assert.equal(timed.c.released, false);
    assert.deepEqual(await counts(timed.id, token), { user: 1, assistant: 0 }); await freshNative(token, 'fresh-after-timeout-before-resume'); assert.equal(timed.paused, true);
    await drain(timed); assert.ok(timed.raw.includes('event:error') && timed.raw.includes('回答超时')); release(timed.c);
    result.cases.push({ name: 'timeout-slow-client', originalBudgetSeconds: 12, persistedStatus: 'TIMED_OUT', answerRows: 0,
      modelHttpClosedBeforeSocketResume: true, sameWorkerCompletedNativeAnswerBeforeSocketResume: true, timeoutEventAfterWriterReleased: true });
    {
      // Keep all four earlier cases intact; independently verify the stopped output writer's natural recovery.
      await stop(); await requireFreePort(9971); await requireFreePort(9972);
      await start(45, 1, 0); token = await login();
      const held = await paused(token, 'natural-output-timeout'); await cancel(held, token);
      const rejected = control('natural-timeout-rejected'), rejectedId = await session(token), rejectedKey = randomUUID();
      const refusal = await api(route(rejectedId) + '/stream', token, { content: rejected.question }, rejectedKey);
      assert.equal(refusal.status, 503); assert.equal(refusal.json.error.code, 'ASSISTANT_STREAM_SATURATED');
      assert.equal((await state(rejectedId, rejectedKey, token)).status, 404);
      assert.deepEqual(await counts(rejectedId, token), { user: 0, assistant: 0 }); assert.equal(rejected.calls, 0);
      const sync = control('natural-timeout-sync-before-recovery', true), syncId = await session(token);
      assert.equal((await api(route(syncId) + '/messages', token, { content: sync.question }, randomUUID())).status, 200);
      assert.equal(sync.calls, 1); assert.deepEqual(await counts(syncId, token), { user: 1, assistant: 1 });
      const observation = { deadlineMs: 80000, outputWriters: 1, outputQueueCapacity: 0,
        cancelStatus: 200, modelHttpClosedBeforeSocketResume: true, synchronousWorkerCompletedBeforeOutputRecovery: true,
        rejectedStatus: 503, rejectedKeyStatus: 404, socketResumed: false, providerReleased: false, samples: [] };
      result.outputTimeoutObservation = observation;
      const deadline = held.blockedObservedAt + observation.deadlineMs;
      let recovered = false;
      do {
        assert.equal(held.paused, true); assert.equal(held.closed, false); assert.equal(held.c.released, false);
        const dump = threads(), writer = sections(dump).find(section => section.startsWith('"opspilot-assistant-output-1"')) || '';
        recovered = /ThreadPoolExecutor\.getTask/.test(writer) && !/AssistantStreamTransport\$Transport\.run/.test(writer);
        observation.samples.push({ millisecondsAfterBlockedObservation: Math.round(performance.now() - held.blockedObservedAt),
          actualTomcatWriteBlocked: outputBlocked(writer), outputWorkerIdle: recovered });
        if (recovered) { fs.writeFileSync(path.join(evidence, 'natural-output-recovered-threads.txt'), redact(dump)); break; }
        await delay(1000);
      } while (performance.now() < deadline);
      assert.ok(recovered, 'Cancelled output writer did not return to the bounded pool before the observation deadline');
      observation.writerIdleObservedMs = observation.samples.at(-1).millisecondsAfterBlockedObservation;
      assert.ok(observation.writerIdleObservedMs <= observation.deadlineMs, 'Output recovery observed after the strict monotonic deadline');
      await freshNative(token, 'native-after-natural-output-recovery');
      assert.equal(held.paused, true); assert.equal(held.closed, false); assert.equal(held.c.released, false);
      assert.deepEqual(await counts(held.id, token), { user: 1, assistant: 0 });
      observation.nativeAnswerCompletedBeforeSocketResume = true;
      observation.nativeAnswerCompletedMs = Math.round(performance.now() - held.blockedObservedAt);
      result.cases.push({ name: 'natural-output-timeout-recovery', cancelledAnswerRows: 0,
        writerRecoveredWithoutSocketResumeOrProviderRelease: true, outputCapacityReusedByNativeAnswer: true,
        writerIdleObservedMs: observation.writerIdleObservedMs, nativeAnswerCompletedMs: observation.nativeAnswerCompletedMs,
        absoluteConnectionTtlClaimed: false });
    }
    result.providerCalls = [...controls.values()].reduce((n, c) => n + c.calls, 0); assert.equal(result.providerCalls, 12);
    result.providerFrames = [...controls.values()].map(c => ({ question: c.question, calls: c.calls, framesWritten: c.framesWritten, charactersWritten: c.charactersWritten }));
    result.status = 'PASS';
  } catch (e) { result.status = 'FAIL'; result.failure = redact(e.stack); throw e; }
  finally {
    process.off('SIGINT', interrupt); process.off('SIGTERM', interrupt); controls.forEach(release);
    for (const s of sockets) await stopProcess(s.client);
    result.ownedTcpClientsStopped = sockets.every(s => s.client.exitCode !== null || s.client.signalCode !== null);
    result.providerFrames = [...controls.values()].map(c => ({ question: c.question, calls: c.calls, framesWritten: c.framesWritten, charactersWritten: c.charactersWritten }));
    await stop(); result.ownedProcessesStopped = true; provider.closeAllConnections(); await new Promise(resolve => provider.close(resolve)); result.ownedProviderStopped = true;
    descriptors.forEach(fd => fs.closeSync(fd)); result.unexpectedJarErrors = 0;
    for (const file of logs) { const text = redact(fs.readFileSync(file, 'utf8')); fs.writeFileSync(file, text); result.unexpectedJarErrors += unexpectedLogLines(text); }
    if (result.unexpectedJarErrors) { result.status = 'FAIL'; result.failure = 'Owned JAR has unexpected errors; see sanitized logs'; }
    fs.writeFileSync(path.join(evidence, 'result.json'), JSON.stringify(result, null, 2)); console.log(JSON.stringify({ ...result, evidenceDirectory: path.relative(root, evidence) }));
    if (result.status === 'FAIL') throw new Error(result.failure);
  }
}
module.exports = { blockedOutputThreads, observeSaturation };
if (require.main === module) verify().catch(e => { console.error(redact(e.message)); process.exitCode = 1; });

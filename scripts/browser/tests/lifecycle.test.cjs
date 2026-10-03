const { test } = require('node:test');
const assert = require('node:assert/strict');
const net = require('node:net');
const http = require('node:http');
const { spawn } = require('node:child_process');
const { once } = require('node:events');
const { requireFreePort, stopProcess, waitForHealth, redact, unexpectedLogLines } = require('../../verify-oncall-browser-ci.cjs');

test('cross-node assistant baseline cannot replace acceptance or launch an archived JAR in CI', async () => {
  const child = spawn(process.execPath, [require.resolve('../../verify-assistant-cross-node-ci.cjs')], {
    env: { ...process.env, CI: 'true', OPSPILOT_ASSISTANT_CROSS_NODE_BASELINE: '1' }, windowsHide: true,
  });
  let output = ''; child.stderr.on('data', chunk => { output += chunk; });
  const [code] = await once(child, 'close'); assert.equal(code, 1);
  assert.match(output, /Baseline capture cannot replace cross-node assistant acceptance/);
  assert.doesNotMatch(output, /evidenceDirectory/);
});

test('assistant UI baseline cannot replace acceptance or launch an archived JAR in CI', async () => {
  const child = spawn(process.execPath, [require.resolve('../../verify-assistant-request-ui-ci.cjs')], {
    env: { ...process.env, CI: 'true', OPSPILOT_ASSISTANT_UI_BASELINE: '1' }, windowsHide: true,
  });
  let output = ''; child.stderr.on('data', chunk => { output += chunk; });
  const [code] = await once(child, 'close'); assert.equal(code, 1);
  assert.match(output, /Baseline capture cannot replace assistant UI acceptance/);
  assert.doesNotMatch(output, /evidenceDirectory/);
});

test('assistant baseline cannot replace CI acceptance or launch the archived JAR', async () => {
  const child = spawn(process.execPath, [require.resolve('../../verify-assistant-session-ci.cjs')], {
    env: { ...process.env, CI: 'true', OPSPILOT_ASSISTANT_SESSION_BASELINE: '1' }, windowsHide: true,
  });
  let output = '';
  child.stderr.on('data', chunk => { output += chunk; });
  const [code] = await once(child, 'close');
  assert.equal(code, 1);
  assert.match(output, /Baseline capture cannot replace assistant acceptance/);
  assert.doesNotMatch(output, /evidenceDirectory/);
});

for (const [flag, message] of [
  ['OPSPILOT_STREAM_SESSION_BASELINE', /Stream session baseline must not replace CI acceptance/],
  ['OPSPILOT_STREAM_SESSION_ONLY', /Stream session only mode must not replace full CI acceptance/],
]) {
  test(`${flag} cannot replace full CI acceptance`, async () => {
    const child = spawn(process.execPath, [require.resolve('../../verify-oncall-browser-ci.cjs')], {
      env: { ...process.env, CI: 'true', [flag]: '1' }, windowsHide: true,
    });
    let output = '';
    child.stderr.on('data', chunk => { output += chunk; });
    const [code] = await once(child, 'close');
    assert.equal(code, 1);
    assert.match(output, message);
    assert.doesNotMatch(output, /evidenceDirectory/);
  })
}

test('account baseline cannot replace CI acceptance or launch the archived JAR', async () => {
  const child=spawn(process.execPath,[require.resolve('../../verify-oncall-browser-ci.cjs')],{
    env:{...process.env,CI:'true',OPSPILOT_ACCOUNT_BASELINE:'1'},windowsHide:true,
  });
  let output='';child.stderr.on('data',chunk=>{output+=chunk;});
  const [code]=await once(child,'close');assert.equal(code,1);
  assert.match(output,/Account baseline capture must not replace CI acceptance/);
  assert.doesNotMatch(output,/evidenceDirectory/);
});

test('SLO baseline cannot replace CI acceptance or launch the archived JAR', async () => {
  const child = spawn(process.execPath, [require.resolve('../../verify-oncall-browser-ci.cjs')], {
    env: { ...process.env, CI: 'true', OPSPILOT_SLO_BASELINE: '1' }, windowsHide: true,
  });
  let output = '';
  child.stderr.on('data', chunk => { output += chunk; });
  const [code] = await once(child, 'close');
  assert.equal(code, 1);
  assert.match(output, /SLO baseline capture must not replace CI acceptance/);
  assert.doesNotMatch(output, /evidenceDirectory/);
});

test('publication baseline cannot replace CI acceptance or launch the old published-immediately JAR', async () => {
  const child = spawn(process.execPath, [require.resolve('../../verify-oncall-browser-ci.cjs')], {
    env: { ...process.env, CI: 'true', OPSPILOT_PUBLICATION_BASELINE: '1' }, windowsHide: true,
  });
  let output = '';
  child.stderr.on('data', chunk => { output += chunk; });
  const [code] = await once(child, 'close');
  assert.equal(code, 1);
  assert.match(output, /Publication baseline capture must not replace CI acceptance/);
  assert.doesNotMatch(output, /evidenceDirectory/);
});

test('trend baseline cannot replace CI acceptance or launch an old JAR', async () => {
  const child = spawn(process.execPath, [require.resolve('../../verify-oncall-browser-ci.cjs')], {
    env: { ...process.env, CI: 'true', OPSPILOT_TREND_BASELINE: '1' }, windowsHide: true,
  });
  let output = '';
  child.stderr.on('data', chunk => { output += chunk; });
  const [code] = await once(child, 'close');
  assert.equal(code, 1);
  assert.match(output, /Trend baseline capture must not replace CI acceptance/);
  assert.doesNotMatch(output, /evidenceDirectory/);
});

test('occupied port is rejected without stopping the original listener', async () => {
  const server = net.createServer();
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  try {
    await assert.rejects(requireFreePort(server.address().port), /occupied; no existing process will be stopped/);
    assert.equal(server.listening, true);
  } finally { await new Promise(resolve => server.close(resolve)); }
});

test('strict log scan includes shutdown errors and does not count a disconnected-client debug line', () => {
  const content = 'INFO ready\nDEBUG HTTP response client disconnected; no error body will be written\n'
    + 'INFO Shutdown initiated\n2026-09-27 ERROR scheduler : database closed\nERROR Global : Unhandled request error\n';
  assert.equal(unexpectedLogLines(content), 2);
  assert.equal(unexpectedLogLines('INFO Shutdown completed\nWARN routine startup warning'), 0);
});
test('free port probe closes its own listener', async () => {
  const server = net.createServer();
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  const port = server.address().port;
  await new Promise(resolve => server.close(resolve));
  await requireFreePort(port);
  await requireFreePort(port);
});
test('readiness waits for a real UP response', async () => {
  let calls = 0;
  const server = http.createServer((_req, res) => res.end(JSON.stringify({ status: ++calls < 2 ? 'DOWN' : 'UP' })));
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  try {
    await waitForHealth(`http://127.0.0.1:${server.address().port}`, { exitCode: null, signalCode: null }, 2000);
    assert.equal(calls, 2);
  } finally { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); }
});
test('terminal or failed-launch child cannot be mistaken for readiness', async () => {
  for (const child of [{ exitCode: 1, signalCode: null }, { exitCode: null, signalCode: 'SIGTERM' },
    { exitCode: null, signalCode: null, launchError: new Error('missing java') }]) {
    await assert.rejects(waitForHealth('http://127.0.0.1:1', child), /exited before readiness/);
  }
});
test('readiness deadline is bounded and a non-UP service is not accepted', async () => {
  const server = http.createServer((_req, res) => res.end('{"status":"DOWN"}'));
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  try {
    await assert.rejects(waitForHealth(`http://127.0.0.1:${server.address().port}`, { exitCode: null, signalCode: null }, 50), /before deadline/);
  } finally { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); }
});
test('cleanup stops only the child passed by its owner', async () => {
  const child = spawn(process.execPath, ['-e', 'setInterval(() => {}, 1000)'], { stdio: 'ignore', windowsHide: true });
  await once(child, 'spawn');
  await stopProcess(child);
  assert.ok(child.exitCode !== null || child.signalCode !== null);
  await stopProcess(child);
});
test('already exited child cleanup is a no-op', async () => {
  const child = spawn(process.execPath, ['-e', 'process.exit(0)'], { stdio: 'ignore', windowsHide: true });
  await once(child, 'exit');
  await stopProcess(child);
  assert.equal(child.exitCode, 0);
});
test('diagnostics redact bearer tokens while preserving business identifiers', () => {
  assert.equal(redact('authorization: Bearer header.payload.signature\nrotation #7'), 'authorization: Bearer [REDACTED]\nrotation #7');
  assert.equal(redact('rotation #7 already cancelled'), 'rotation #7 already cancelled');
});

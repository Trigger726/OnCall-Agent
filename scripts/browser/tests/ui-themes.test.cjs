const { test } = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const net = require('node:net');
const { spawnSync } = require('node:child_process');
const { validateBase, classifyObservations, contrast } = require('../verify-ui-themes.cjs');
const { assetFiles, javaArgs, free } = require('../../verify-ui-packaged.cjs');
const html = '<script src="/assets/theme.js"></script><script src="/assets/index-demo.js"></script><link href="/assets/index-demo.css"><link href="/favicon.svg">';

test('only explicit loopback HTTP origins with a valid port are allowed', () => {
  assert.equal(validateBase('http://127.0.0.1:9913'), 'http://127.0.0.1:9913');
  for (const value of [undefined, '', 'https://example.com', 'http://127.0.0.1:9913/login', 'http://localhost:9913', 'http://127.0.0.1:0', 'http://127.0.0.1:65536']) assert.throws(() => validateBase(value));
});
test('direct browser entry refuses to run without the isolated runner marker', () => {
  const env = { ...process.env, OPSPILOT_THEME_QA_BASE: 'http://127.0.0.1:9913' };
  delete env.OPSPILOT_UI_QA_ISOLATED;
  const result = spawnSync(process.execPath, [path.resolve(__dirname, '../verify-ui-themes.cjs')], { env, encoding: 'utf8', timeout: 5000, windowsHide: true });
  assert.equal(result.status, 1); assert.equal(result.stdout, '');
  assert.match(result.stderr, /Start this through verify-ui-packaged/);
});
test('only the exact known empty-evaluation 404 and console entry are expected', () => {
  const report = { responses: [{ path: '/api/v1/runbooks/evaluations/latest', status: 404 }, { path: '/api/v1/runbooks/evaluations/latest', status: 500 }, { path: '/api/v1/incidents', status: 404 }], consoleErrors: [{ url: 'http://127.0.0.1:9913/api/v1/runbooks/evaluations/latest', text: '404' }, { url: 'http://other.invalid/api/v1/runbooks/evaluations/latest', text: '404' }], posts: [] };
  const result = classifyObservations(report, 'http://127.0.0.1:9913');
  assert.equal(result.expectedResponses.length, 1); assert.equal(result.unexpectedResponses.length, 2);
  assert.equal(result.expectedConsoleErrors.length, 1); assert.equal(result.unexpectedConsoleErrors.length, 1);
});
test('login/session creation are recorded but any other business POST is rejected', () => {
  const posts = ['/api/v1/auth/login', '/api/v1/assistant/sessions', '/api/v1/oncall/swaps'];
  assert.deepEqual(classifyObservations({ responses: [], consoleErrors: [], posts }, 'http://127.0.0.1:9913').unexpectedPosts, [posts[2]]);
  assert.equal(posts.length, 3);
});
test('contrast calculation checks white/black and equal colors', () => {
  assert.equal(contrast([255, 255, 255], [0, 0, 0]), 21);
  assert.equal(contrast([60, 60, 60], [60, 60, 60]), 1);
});
test('release assets are deduplicated and include prepaint bootstrap', () => {
  assert.deepEqual(assetFiles(html), ['index.html', 'favicon.svg', 'assets/theme.js', 'assets/index-demo.js', 'assets/index-demo.css']);
  assert.throws(() => assetFiles(html.replace('/assets/theme.js', '/assets/../theme.js')));
  assert.throws(() => assetFiles(html.replace('<script src="/assets/theme.js"></script>', '')));
});
test('missing production JS or CSS cannot become a passing static release', () => {
  assert.throws(() => assetFiles(html.replace('<script src="/assets/index-demo.js"></script>', '')));
  assert.throws(() => assetFiles(html.replace('<link href="/assets/index-demo.css">', '')));
});
test('JVM arguments force isolated H2/loopback and disable outbound automation', () => {
  const args = javaArgs('/existing.jar', path.resolve('temporary-ui-qa'), 9913);
  assert.ok(args.includes('--server.address=127.0.0.1'));
  assert.ok(args.includes('--management.server.port=9914'));
  assert.ok(args.some(arg => arg.startsWith('--spring.datasource.url=jdbc:h2:file:') && arg.endsWith('/demo;MODE=MySQL;DATABASE_TO_LOWER=TRUE;WRITE_DELAY=0')));
  for (const key of ['ai', 'agent.events.outbox', 'agent.recovery', 'oncall.rotation', 'oncall.open.notification', 'oncall.swap.notification', 'alertmanager.rejections.auto-replay', 'runbook.semantic']) assert.ok(args.includes(`--opspilot.${key}-enabled=false`) || args.includes(`--opspilot.${key}.enabled=false`), key);
  assert.ok(args.includes('--spring.datasource.driver-class-name=org.h2.Driver'));
  assert.throws(() => javaArgs('/existing.jar', 'relative', 9913));
  for (const port of [0, 80, 65535, NaN]) assert.throws(() => javaArgs('/existing.jar', path.resolve('temporary-ui-qa'), port));
});
test('port preflight fails for an occupied port without stopping its owner', async () => {
  const owner = net.createServer();
  await new Promise(resolve => owner.listen(0, '127.0.0.1', resolve));
  const port = owner.address().port;
  try { await assert.rejects(free(port), /EADDRINUSE/); assert.ok(owner.listening); }
  finally { await new Promise(resolve => owner.close(resolve)); }
  await free(port);
});

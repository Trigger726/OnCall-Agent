const { test } = require('node:test');
const assert = require('node:assert/strict');
const { blockedOutputThreads, observeSaturation, settledWriterSample } = require('../verify-assistant-slow-consumer-ci.cjs');

const writer = (name, frame = 'NioSocketWrapper.doWrite') => `"${name}" #1 daemon\n\tat org.apache.tomcat.util.net.${frame}(NioEndpoint.java:1395)`;
const first = writer('opspilot-assistant-output-1'), second = writer('opspilot-assistant-output-2');
const pair = first + '\n\n' + second;

test('only distinct actual output-writer socket writes in the same dump form a pair', () => {
  assert.deepEqual(blockedOutputThreads(pair), ['opspilot-assistant-output-1', 'opspilot-assistant-output-2']);
  assert.deepEqual(blockedOutputThreads(pair.replaceAll('\n', '\r\n')), blockedOutputThreads(pair));
  assert.deepEqual(blockedOutputThreads(first + '\n\n' + first), ['opspilot-assistant-output-1']);
  for (const dump of ['', writer('opspilot-assistant-1'), writer('http-nio-exec-1'), writer('opspilot-assistant-output-1', 'ThreadPoolExecutor.getTask')]) {
    assert.deepEqual(blockedOutputThreads(dump), []);
  }
});

test('bounded observation can settle from one writer to a genuine simultaneous pair', async () => {
  let captures = 0;
  const result = await observeSaturation(() => ++captures === 1 ? first : pair, 1000);
  assert.equal(result.satisfied, true); assert.equal(result.dump, pair); assert.equal(captures, 2);
  assert.deepEqual(result.samples.map(s => s.blockedThreads.length), [1, 2]);
  assert.ok(result.elapsedMs <= result.deadlineMs);
});

test('alternating separate writers cannot be aggregated into saturation', async () => {
  let captures = 0;
  const result = await observeSaturation(() => ++captures % 2 ? first : second, 100);
  assert.equal(result.satisfied, false); assert.ok(captures >= 2);
  assert.ok(result.samples.every(s => s.blockedThreads.length === 1));
  assert.equal(blockedOutputThreads(result.dump).length, 1);
});

test('absent, duplicate, model-worker or extra output writers do not pass', async () => {
  for (const dump of ['', first + '\n\n' + first, first + '\n\n' + writer('opspilot-assistant-1'),
    pair + '\n\n' + writer('opspilot-assistant-output-3')]) {
    const result = await observeSaturation(() => dump, 5);
    assert.equal(result.satisfied, false); assert.equal(result.dump, dump);
  }
});

test('a genuine pair returned after the monotonic deadline is still rejected and retained', async () => {
  const result = await observeSaturation(() => {
    const end = performance.now() + 15; while (performance.now() < end) { /* Controlled slow capture, not product execution. */ }
    return pair;
  }, 5);
  assert.equal(result.satisfied, false); assert.equal(result.dump, pair);
  assert.equal(result.samples[0].blockedThreads.length, 2); assert.ok(result.elapsedMs > result.deadlineMs);
});

test('capture errors fail closed without retrying the product or swallowing jcmd failure', async () => {
  let captures = 0;
  await assert.rejects(observeSaturation(() => { captures++; throw new Error('controlled jcmd failure'); }), /controlled jcmd failure/);
  assert.equal(captures, 1);
});

test('invalid or expanded observation budgets cannot weaken the fixed bound', async () => {
  for (const budget of [0, -1, 4001, NaN, Infinity, 1.5, '4000']) {
    await assert.rejects(observeSaturation(() => pair, budget));
  }
});

test('the first writer snapshot never counts as settled', () => {
  assert.deepEqual(settledWriterSample(undefined, 'output-1', 1000), { candidate: { name: 'output-1', observedAt: 1000 }, settled: false });
});

test('the same writer needs at least 500ms of successive samples', () => {
  const first = settledWriterSample(undefined, 'output-1', 1000);
  assert.equal(settledWriterSample(first.candidate, 'output-1', 1499).settled, false);
  assert.equal(settledWriterSample(first.candidate, 'output-1', 1500).settled, true);
});

test('an idle or missing snapshot resets the candidate instead of bridging the gap', () => {
  const first = settledWriterSample(undefined, 'output-1', 1000);
  const idle = settledWriterSample(first.candidate, undefined, 1300);
  assert.deepEqual(idle, { candidate: undefined, settled: false });
  assert.equal(settledWriterSample(idle.candidate, 'output-1', 1600).settled, false);
});

test('alternating writers cannot add their sampled durations together', () => {
  let candidate;
  for (const [name, now] of [['output-1', 1000], ['output-2', 1400], ['output-1', 1800], ['output-2', 2200]]) {
    const result = settledWriterSample(candidate, name, now); candidate = result.candidate; assert.equal(result.settled, false);
  }
});

test('a late idle sample cannot inherit a formerly settled writer', () => {
  const first = settledWriterSample(undefined, 'output-1', 1000);
  const settled = settledWriterSample(first.candidate, 'output-1', 1600); assert.equal(settled.settled, true);
  assert.equal(settledWriterSample(settled.candidate, null, 3000).settled, false);
});

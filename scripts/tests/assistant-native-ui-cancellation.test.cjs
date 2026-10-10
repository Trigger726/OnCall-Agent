const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs'), path = require('node:path');
const { assertCancelledFixtureStream: verify } = require('../verify-assistant-native-ui-ci.cjs');
const frame = 'event:cancelled\ndata:' + JSON.stringify({ type: 'cancelled', content: '回答已取消', messageId: null, evidenceJson: null }) + '\n\n';
const fixture = () => ({ responseStatus: 200, ended: true, contentType: 'text/event-stream;charset=UTF-8', body: frame });

test('complete cancellation after native previews and queued cancellation both pass', () => {
  verify(fixture());
  verify({ ...fixture(), body: 'event:token\ndata:{"type":"token","content":"🙂"}\n\n' + frame });
});
test('green HTTP200 and cancellation prefix cannot replace complete JSON and EOF', () => {
  for (const body of ['event:cancelled\ndata:', 'event:cancelled\ndata:{}\n\n', frame.trimEnd() + '\n', frame.replace('"cancelled"', '"done"'), frame.replace('null', '123'), frame.replace('回答已取消', 'not cancellation')]) {
    assert.throws(() => verify({ ...fixture(), body }));
  }
  assert.throws(() => verify({ ...fixture(), ended: false }));
});
test('duplicate or non-terminal cancellation cannot pass the complete-frame gate', () => {
  for (const body of [frame + frame, frame + 'event:token\ndata:{}\n\n', 'event:done\ndata:{}\n\n' + frame, frame + 'garbage']) {
    assert.throws(() => verify({ ...fixture(), body }));
  }
});
test('wrong status/content type, malformed JSON, and unbounded body are rejected', () => {
  for (const held of [{ ...fixture(), responseStatus: 503 }, { ...fixture(), contentType: 'application/json' },
    { ...fixture(), body: 'event:cancelled\ndata:{bad}\n\n' }, { ...fixture(), body: 'x'.repeat(32768) + '\n\n' + frame }]) assert.throws(() => verify(held));
});
test('CP106 original Linux holder bodies satisfy the stronger gate without editing evidence', () => {
  const file = path.resolve(__dirname, '../../docs/assets/v1.7-cp106/remote-close/oncall-browser-evidence/assistant-native-ui-it/run-Be7NiU/result.json');
  const result = JSON.parse(fs.readFileSync(file, 'utf8'));
  assert.equal(result.status, 'PASS'); assert.equal(result.fixtureHeldStreams.length, 2);
  for (const held of result.fixtureHeldStreams) verify({ ...held, body: held.controlledFixtureBody });
});
test('original native budgets, EOF checks, and all six flows remain in the real runner', () => {
  const source = fs.readFileSync(path.resolve(__dirname, '../verify-assistant-native-ui-ci.cjs'), 'utf8');
  assert.match(source, /AbortSignal\.timeout\(20000\)/);
  assert.match(source, /--opspilot\.assistant\.execution-timeout=12s/);
  assert.match(source, /for await \(const chunk of response\.body\)/);
  assert.match(source, /assertCancelledFixtureStream\(held\)/);
  for (const name of ['preview-before-final-commit', 'explicit-cancel-after-preview', 'truncated-preview', 'idle-after-preview',
    'disconnect-after-preview-manual-query', 'output-capacity-503-and-fixed-key-manual-recovery']) assert.ok(source.includes("name: '" + name + "'"));
  const workflow = fs.readFileSync(path.resolve(__dirname, '../../.github/workflows/ci.yml'), 'utf8');
  assert.match(workflow, /Verify default assistant native previews, final commit and recovery\s+run: \|\s+node --test scripts\/tests\/assistant-native-ui-cancellation.test.cjs\s+node scripts\/verify-assistant-native-ui-ci.cjs/);
});

const { test } = require('node:test');
const assert = require('node:assert/strict');
const { mysqlSettings } = require('../verify-assistant-cross-node-ci.cjs');

const fixture = () => ({ OPSPILOT_ASSISTANT_CROSS_NODE_MYSQL: '1',
  OPSPILOT_CROSS_NODE_JDBC_URL: 'jdbc:mysql://localhost:33061/opspilot_cross_node_012345abcdef?useSSL=false',
  OPSPILOT_CROSS_NODE_DB_USER: 'opspilot', OPSPILOT_CROSS_NODE_DB_PASSWORD: 'test-password' });

test('existing H2 acceptance remains the explicit default without database credentials', () => {
  assert.equal(mysqlSettings({}), null);
});
test('owned loopback MySQL container configuration is accepted without claiming identity verification', () => {
  const input = fixture(), settings = mysqlSettings(input);
  assert.equal(settings.url, input.OPSPILOT_CROSS_NODE_JDBC_URL); assert.equal(settings.schema, 'opspilot_cross_node_012345abcdef');
  assert.equal(mysqlSettings({ ...input, OPSPILOT_CROSS_NODE_JDBC_URL: input.OPSPILOT_CROSS_NODE_JDBC_URL.replace('localhost', '127.0.0.1') }).schema, settings.schema);
});
test('credentials without an explicit mode cannot silently select H2', () => {
  for (const key of ['OPSPILOT_CROSS_NODE_JDBC_URL', 'OPSPILOT_CROSS_NODE_DB_USER', 'OPSPILOT_CROSS_NODE_DB_PASSWORD']) {
    assert.throws(() => mysqlSettings({ [key]: 'unexpected' }), /explicit owned-container mode/);
  }
});
test('invalid mode values never become an implicit H2 fallback', () => {
  for (const mode of ['', '0', 'true', 1]) assert.throws(() => mysqlSettings({ ...fixture(), OPSPILOT_ASSISTANT_CROSS_NODE_MYSQL: mode }), /Invalid MySQL mode/);
});
test('missing URL and H2 URL are rejected before launching a JVM', () => {
  for (const url of ['', 'jdbc:h2:mem:test']) assert.throws(() => mysqlSettings({ ...fixture(), OPSPILOT_CROSS_NODE_JDBC_URL: url }), /owned MySQL test schema/);
});
test('remote hosts, generic schemas and invalid ports cannot be placed in scope', () => {
  for (const url of ['jdbc:mysql://production:3306/opspilot_cross_node_012345abcdef', 'jdbc:mysql://localhost:3306/opspilot',
    'jdbc:mysql://localhost:65536/opspilot_cross_node_012345abcdef', 'jdbc:mysql://localhost:0/opspilot_cross_node_012345abcdef']) {
    assert.throws(() => mysqlSettings({ ...fixture(), OPSPILOT_CROSS_NODE_JDBC_URL: url }), /owned MySQL test schema/);
  }
});
test('only dedicated test credentials are accepted', () => {
  assert.throws(() => mysqlSettings({ ...fixture(), OPSPILOT_CROSS_NODE_DB_USER: 'root' }), /owned test user/);
  assert.throws(() => mysqlSettings({ ...fixture(), OPSPILOT_CROSS_NODE_DB_PASSWORD: '' }), /owned test credential/);
});
test('an archived baseline cannot replace any MySQL scenario', () => {
  assert.throws(() => mysqlSettings({ ...fixture(), OPSPILOT_ASSISTANT_CROSS_NODE_BASELINE: '1' }), /Archived baseline/);
});

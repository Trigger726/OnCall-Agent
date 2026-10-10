const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { verify, suite, cases, lockCases } = require('../verify-oncall-plan-membership-mysql.cjs');
function fixture() {
  const identities = cases.map(name => 'PLAN_MEMBERSHIP_DATABASE ' + JSON.stringify({ case: name, product: 'MySQL', version: '8.4.11', schema: 'opspilot_plan_membership_test', migration39: true }));
  const orders = lockCases.map((name, i) => 'PLAN_MEMBERSHIP_LOCK_ORDER ' + JSON.stringify({ case: name, revocationFirst: i === 0, databaseLockWaitObserved: true, responsibilityMatchesCommitOrder: true }));
  return { xml: '<testsuite name="' + suite + '" tests="22" failures="0" errors="0" skipped="0">'
    + cases.map((name, i) => '<testcase name="' + name + '" classname="' + suite + '"><system-out><![CDATA[' + identities[i] + '\n'
      + (lockCases.includes(name) ? orders[lockCases.indexOf(name)] : '') + ']]></system-out></testcase>').join('') + '</testsuite>',
    log: 'INFO HikariPool-1 - Start completed.\n' + [...identities, ...orders].join('\n') + '\nINFO HikariPool-1 - Shutdown completed.\n[INFO] BUILD SUCCESS\n' };
}
const run = f => verify(f.log, f.xml);
function rejects(mutations) { for (const mutate of mutations) { const f = fixture(); mutate(f); assert.throws(() => run(f)); } }
test('synthetic receipt validates gate shape, not actual MySQL or full membership acceptance', () => {
  const r = run(fixture()); assert.equal(r.executed, 22); assert.equal(r.populatedOldJarUpgradeVerified, false); assert.equal(r.membershipUiVerified, false);
});
test('gate keeps every shared case and both actual database-wait race identities', () => {
  const source = fs.readFileSync(path.resolve(__dirname, '../../src/test/java/org/trigger/opspilot/oncall/PlanMembershipScenarios.java'), 'utf8');
  assert.deepEqual([...source.matchAll(/@Test\s+void\s+(\w+)\(/g)].map(m => m[1]).sort(), [...cases].sort());
  assert.equal(lockCases.length, 2); assert.match(source, /waitingForDatabaseLock\(\)/); assert.match(source, /BLOCKER_ID IS NOT NULL/);
  const mysql = fs.readFileSync(path.resolve(__dirname, '../../src/test/java/org/trigger/opspilot/oncall/MySqlOnCallPlanMembershipIntegrationTest.java'), 'utf8');
  assert.match(mysql, /performance_schema\.data_lock_waits/);
});
test('zero tests skipped failures errors missing cases and duplicate cases fail closed', () => rejects([
  f => f.xml = f.xml.replace('tests="22"', 'tests="0"'),
  ...['skipped', 'errors', 'failures'].map(key => f => f.xml = f.xml.replace(key + '="0"', key + '="1"')),
  f => f.xml = f.xml.replace('<system-out>', '<skipped/><system-out>'),
  f => f.xml = f.xml.replace('name="' + cases[1] + '"', 'name="' + cases[0] + '"'),
  f => f.xml = f.xml.replace('classname="' + suite + '"', 'classname="H2"'),
  f => f.xml = f.xml.replace('</testsuite>', '')
]));
test('wrong product version schema migration and missing per-case identity fail closed', () => rejects([
  f => f.xml = f.xml.replace('PLAN_MEMBERSHIP_DATABASE', 'missing'),
  f => f.xml = f.xml.replace('"product":"MySQL"', '"product":"H2"'),
  f => f.xml = f.xml.replace('8.4.11', '8.0.1'),
  f => f.xml = f.xml.replace('opspilot_plan_membership_test', 'production'),
  f => f.xml = f.xml.replace('"migration39":true', '"migration39":false'),
  f => f.log = f.log.replace('8.4.11', '8.4.12')
]));
test('missing real wait reversed commit order or changed responsibility proof fail closed', () => rejects([
  f => f.xml = f.xml.replace('PLAN_MEMBERSHIP_LOCK_ORDER', 'missing'),
  f => f.xml = f.xml.replace('"databaseLockWaitObserved":true', '"databaseLockWaitObserved":false'),
  f => f.xml = f.xml.replace('"responsibilityMatchesCommitOrder":true', '"responsibilityMatchesCommitOrder":false'),
  f => f.xml = f.xml.replace('"revocationFirst":true', '"revocationFirst":false'),
  f => f.log = f.log.replace('PLAN_MEMBERSHIP_LOCK_ORDER', 'missing')
]));
test('post-summary errors duplicate pools missing shutdown and truncated Maven fail closed', () => rejects([
  ...['ERROR shutdown failed', 'WARN Surefire is going to kill', 'WARN Failed to validate connection', 'native thread creation failed'].map(line => f => f.log += line),
  f => f.log = f.log.replace('Shutdown completed.', 'Shutdown initiated.'),
  f => f.log += 'HikariPool-2 - Start completed.',
  f => f.log = f.log.replace('BUILD SUCCESS', 'missing')
]));
test('WIP workflow enables actual MySQL and executes the strict gate, without changing original CI', () => {
  const source = fs.readFileSync(path.resolve(__dirname, '../../.github/workflows/plan-membership-wip.yml'), 'utf8');
  assert.match(source, /wip\/plan-membership-v39-20261010/);
  assert.match(source, /-Dopspilot\.mysql\.it\.enabled=true/);
  assert.match(source, /-Dtest=MySqlOnCallPlanMembershipIntegrationTest test/);
  assert.match(source, /node scripts\/verify-oncall-plan-membership-mysql\.cjs/);
  assert.match(source, /set -o pipefail/);
});

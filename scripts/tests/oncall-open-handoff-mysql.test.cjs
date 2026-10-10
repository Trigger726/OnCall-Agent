const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { verify, suite, cases, timeCases } = require('../verify-oncall-open-handoff-mysql.cjs');
function fixture() {
  const identities = cases.map(name => 'CP97_OPEN_HANDOFF_DATABASE ' + JSON.stringify({ case: name,
    product: 'MySQL', version: '8.4.11', schema: 'opspilot_open_handoff_test', migration38: true, migration39: true, migration40: true, migration41: true, versionedMigrations: 41 }));
  const barriers = timeCases.map(name => 'CP97_OPEN_HANDOFF_TIME_BARRIER ' + JSON.stringify({ case: name,
    requestedEnd: '2026-10-04T14:20:01', persistedEnd: '2026-10-04T14:20:01',
    releasedDatabaseNow: '2026-10-04T14:20:01.012345', releasedAfterPersistedEnd: true }));
  const xml = '<testsuite name="' + suite + '" tests="24" failures="0" errors="0" skipped="0">'
    + cases.map((name, i) => '<testcase name="' + name + '"><system-out><![CDATA[' + identities[i] + '\n'
      + (timeCases.includes(name) ? barriers[timeCases.indexOf(name)] : '') + ']]></system-out></testcase>').join('') + '</testsuite>';
  return { xml, log: 'INFO HikariPool-1 - Start completed.\n' + identities.concat(barriers).join('\n')
    + '\nINFO HikariPool-1 - Shutdown completed.\n[INFO] BUILD SUCCESS\n' };
}
const run = f => verify(f.log, f.xml);
function rejects(mutations) { for (const mutate of mutations) { const f = fixture(); mutate(f); assert.throws(() => run(f)); } }
test('synthetic gate shape is not a real MySQL product execution', () => assert.equal(run(fixture()).executed, 24));
test('the gate preserves all shared consent concurrency rollback HTTP cases', () => {
  const source = fs.readFileSync(path.join(__dirname, '../../src/test/java/org/trigger/opspilot/oncall/OpenHandoffScenarios.java'), 'utf8');
  assert.deepEqual([...source.matchAll(/@Test\s+void\s+(\w+)\(/g)].map(m => m[1]).sort(), [...cases].sort());
});
test('zero tests conditional skips failures errors and skipped children fail closed', () => rejects([
  f => f.xml = f.xml.replace('tests="24"', 'tests="0"'),
  ...['skipped', 'errors', 'failures'].map(key => f => f.xml = f.xml.replace(key + '="0"', key + '="1"')),
  f => f.xml = f.xml.replace('<system-out>', '<skipped/><system-out>')
]));
test('every testcase must identify real owned MySQL V38 and V39', () => rejects([
  f => f.xml = f.xml.replace('CP97_OPEN_HANDOFF_DATABASE', 'missing'),
  f => f.xml = f.xml.replace('"product":"MySQL"', '"product":"H2"'),
  f => f.xml = f.xml.replace('8.4.11', '8.0.1'),
  f => f.xml = f.xml.replace('opspilot_open_handoff_test', 'production'),
  f => f.xml = f.xml.replace('"migration38":true', '"migration38":false'),
  f => f.xml = f.xml.replace(',"migration39":true', ''),
  f => f.xml = f.xml.replace('"migration39":true', '"migration39":false'),
  f => f.xml = f.xml.replace(',"migration40":true', ''),
  f => f.xml = f.xml.replace('"migration40":true', '"migration40":false'),
  f => f.xml = f.xml.replace('"versionedMigrations":41', '"versionedMigrations":40'),
  f => f.xml = f.xml.replace('"versionedMigrations":41', '"versionedMigrations":42')
]));
test('duplicate renamed cases and log/XML divergence fail closed', () => rejects([
  f => f.xml = f.xml.replace('name="' + cases[13] + '"', 'name="' + cases[0] + '"'),
  f => f.log = f.log.replace('8.4.11', '8.4.12'),
  f => f.log = f.log.replace('CP97_OPEN_HANDOFF_TIME_BARRIER', 'missing')
]));
test('both actual deadlines must be whole-second persisted ends reached before release', () => rejects([
  f => f.xml = f.xml.replace('CP97_OPEN_HANDOFF_TIME_BARRIER', 'missing'),
  f => f.xml = f.xml.replace('"persistedEnd":"2026-10-04T14:20:01"', '"persistedEnd":"2026-10-04T14:20:02"'),
  f => f.xml = f.xml.replace('"requestedEnd":"2026-10-04T14:20:01"', '"requestedEnd":"2026-10-04T14:20:01.900"'),
  f => f.xml = f.xml.replace('14:20:01.012345', '14:20:00.999999'),
  f => f.xml = f.xml.replace('"releasedAfterPersistedEnd":true', '"releasedAfterPersistedEnd":false')
]));
test('post-summary errors missing shutdown duplicate pools and truncated logs cannot pass', () => rejects([
  ...['ERROR shutdown failed', 'WARN Surefire is going to kill', 'WARN Failed to validate connection', 'native thread creation failed'].map(line => f => f.log += line),
  f => f.log = f.log.replace('Shutdown completed.', 'Shutdown initiated.'),
  f => f.log += 'HikariPool-2 - Start completed.',
  f => f.log = f.log.replace('BUILD SUCCESS', 'missing'),
  f => f.xml = f.xml.replace('</testsuite>', '')
]));
test('ANSI and CRLF preserve the strict evidence checks', () => { const f = fixture();
  f.log = '\u001b[32m' + f.log.replaceAll('\n', '\r\n') + '\u001b[0m'; assert.equal(run(f).status, 'PASS'); });

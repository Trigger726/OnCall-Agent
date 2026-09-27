const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const { expectedSuites, verify } = require('../verify-mysql-lifecycle.cjs');

function fixture() {
  const reports = Object.fromEntries(Object.entries(expectedSuites).map(([name, tests]) => [name,
    `<testsuite name="${name}" tests="${tests}" failures="0" errors="0" skipped="0"></testsuite>`]));
  const start = Array.from({ length: 5 }, (_, i) => `INFO HikariPool-${i + 1} - Start completed.`);
  const stop = Array.from({ length: 5 }, (_, i) => `INFO HikariPool-${i + 1} - Shutdown completed.`);
  return { reports, log: [...start, '[INFO] Tests run: 62, Failures: 0, Errors: 0, Skipped: 0', ...stop, '[INFO] BUILD SUCCESS'].join('\n') };
}

test('clean five-suite lifecycle passes with 62 executed and no skips', () => {
  const { log, reports } = fixture();
  const result = verify(log, reports);
  assert.equal(result.executed, 62);
  assert.equal(result.stoppedPools.length, 5);
});

test('preserved real CP51 assertion-green shutdown errors are rejected', () => {
  const diagnostic = fs.readFileSync('docs/acceptance/assets/V1.7-checkpoint-51/mysql-lifecycle-diagnostic.txt', 'utf8');
  assert.match(diagnostic, /BUILD SUCCESS/);
  const { log, reports } = fixture();
  assert.throws(() => verify(log + '\n' + diagnostic, reports), /lifecycle\/error lines/);
});

for (const line of ['ERROR scheduler : failed', '[ERROR] fork failed',
  'WARN Surefire is going to kill self fork JVM', 'WARN Failed to validate connection',
  'WARN No operations allowed after connection closed', 'WARN Communications link failure']) {
  test(`rejects post-summary line: ${line}`, () => {
    const { log, reports } = fixture();
    assert.throws(() => verify(log + '\n' + line, reports), /lifecycle\/error lines/);
  });
}

test('missing build success is not a passing result', () => {
  const { log, reports } = fixture();
  assert.throws(() => verify(log.replace('[INFO] BUILD SUCCESS', ''), reports), /BUILD SUCCESS/);
});

test('missing, duplicate, unknown or reordered shutdown is rejected', () => {
  const { log, reports } = fixture();
  for (const bad of [log.replace('INFO HikariPool-5 - Shutdown completed.', ''),
    log.replace('HikariPool-5 - Shutdown completed.', 'HikariPool-4 - Shutdown completed.'),
    log.replace('HikariPool-5 - Shutdown completed.', 'HikariPool-6 - Shutdown completed.'),
    'INFO HikariPool-5 - Shutdown completed.\n' + log.replace('INFO HikariPool-5 - Shutdown completed.', '')]) {
    assert.throws(() => verify(bad, reports), /pool|shutdown/i);
  }
});

test('missing suite, wrong identity, reduced coverage, malformed counters, skips and failures fail closed', () => {
  const { log, reports } = fixture();
  const name = Object.keys(expectedSuites)[0];
  for (const bad of ['', reports[name].replace(name, 'WrongTest'), reports[name].replace('tests="9"', 'tests="0"'),
    reports[name].replace('errors="0"', ''), reports[name].replace('errors="0"', 'errors="NaN"'),
    reports[name].replace('skipped="0"', 'skipped="9"'), reports[name].replace('failures="0"', 'failures="1"'),
    reports[name].replace('</testsuite>', '')]) {
    assert.throws(() => verify(log, { ...reports, [name]: bad }));
  }
});

test('ANSI colors and CRLF are accepted, future added test counts are included', () => {
  const { log, reports } = fixture();
  const name = Object.keys(expectedSuites)[0];
  reports[name] = reports[name].replace('tests="9"', 'tests="10"');
  assert.equal(verify('\x1b[32m' + log.replaceAll('\n', '\r\n') + '\x1b[0m', reports).executed, 63);
});

const {test} = require('node:test');
const assert = require('node:assert/strict');
const {suiteName, expectedCases, verify} = require('../verify-assistant-native-mysql.cjs');

function fixture() {
  const names = Object.entries(expectedCases).flatMap(([name, count]) => Array(count).fill(name));
  const markers = names.map(name => 'CP80_NATIVE_DATABASE ' + JSON.stringify({product:'MySQL', version:'8.4.9', case:name}));
  const xml = `<testsuite name="${suiteName}" tests="18" failures="0" errors="0" skipped="0">` +
    names.map((name, index) => `<testcase name="${name}" classname="${suiteName}"><system-out><![CDATA[${markers[index]}\n]]></system-out></testcase>`).join('') + '</testsuite>';
  const log = ['INFO HikariPool-1 - Start completed.', ...markers,
    '[INFO] Tests run: 18, Failures: 0, Errors: 0, Skipped: 0',
    'INFO HikariPool-1 - Shutdown completed.', '[INFO] BUILD SUCCESS'].join('\n');
  return {log, xml};
}

test('synthetic gate fixture requires all 18 native executions with per-case MySQL identity', () => {
  const {log, xml} = fixture(); const result = verify(log, xml);
  assert.equal(result.executed,18); assert.equal(result.database,'MySQL');
  assert.equal(result.cases.shouldFailOnNativeEofRatherThanAppendRuleFallback,10);
});

test('H2 green totals or unrelated MySQL metadata cannot pass', () => {
  const {log, xml} = fixture();
  for (const bad of [log.replaceAll('MySQL','H2'), log.replaceAll('8.4.9','8.0.40'),
    log.replaceAll('CP80_NATIVE_DATABASE','UNRELATED_DATABASE'), log.replace(/CP80_NATIVE_DATABASE .*\n/, '')]) {
    assert.throws(()=>verify(bad,xml));
  }
  assert.throws(()=>verify(log,xml.replaceAll('MySQL','H2')));
});

test('each real testcase needs its own matching metadata, not one global marker', () => {
  const {log, xml} = fixture();
  for(const bad of [xml.replace(/CP80_NATIVE_DATABASE [^\n]+/,''), xml.replace('"case":"shouldDeliverRealNativeTokenBeforeCompletionAndCommitExactAnswer"','"case":"nativeDoneMustNotEscapeAFailedFinalAuditTransaction"'),
    xml.replace('"version":"8.4.9"','"version":"8.4.10"'), xml.replace('"version":"8.4.9"','"version":"not-a-version"')]) {
    assert.throws(()=>verify(log,bad));
  }
});

test('each required HTTP scenario is enforced despite unchanged green totals', () => {
  const {log,xml}=fixture();
  for(const name of Object.keys(expectedCases)) {
    assert.throws(()=>verify(log,xml.replace(`name="${name}"`,'name="OtherCase"')));
    assert.throws(()=>verify(log.replace(`"case":"${name}"`,'"case":"OtherCase"'),xml));
  }
});

test('ten EOF repetitions cannot be replaced by duplicates of a happy path', () => {
  const {log,xml}=fixture();
  assert.throws(()=>verify(log,xml.replace('name="shouldFailOnNativeEofRatherThanAppendRuleFallback"',
    'name="shouldDeliverRealNativeTokenBeforeCompletionAndCommitExactAnswer"')));
});

test('wrong suite, malformed counters, incomplete cases, errors and skips fail closed', () => {
  const {log,xml}=fixture();
  for(const bad of ['',xml.replace(`name="${suiteName}"`,'name="OtherSuite"'),xml.replace('tests="18"','tests="17"'),
    xml.replace('errors="0"','errors="NaN"'),xml.replace('skipped="0"','skipped="1"'),xml.replace('failures="0"','failures="1"'),
    xml.replace('</testsuite>',''),xml.replace('<system-out>','<skipped/><system-out>'),xml.replace('<system-out>','<failure/><system-out>'),
    xml.replace(`classname="${suiteName}"`,'classname="OtherSuite"')]) assert.throws(()=>verify(log,bad));
});

for(const line of ['ERROR scheduler : failed','[ERROR] fork failed','WARN Surefire is going to kill self fork JVM',
  'WARN Failed to validate connection','WARN No operations allowed after connection closed','WARN Communications link failure']) {
  test(`post-summary shutdown error is rejected: ${line}`,()=>{
    const {log,xml}=fixture(); assert.throws(()=>verify(log+'\n'+line,xml),/lifecycle\/error/);
  });
}

test('missing, duplicate, wrong or early pool shutdown is rejected',()=>{
  const {log,xml}=fixture();
  for(const bad of [log.replace('INFO HikariPool-1 - Shutdown completed.',''),
    log+'\nINFO HikariPool-1 - Shutdown completed.',log.replace('HikariPool-1 - Shutdown completed.','HikariPool-2 - Shutdown completed.'),
    'INFO HikariPool-1 - Shutdown completed.\n'+log.replace('INFO HikariPool-1 - Shutdown completed.',''),
    log+'\nINFO HikariPool-2 - Start completed.\nINFO HikariPool-2 - Shutdown completed.']) assert.throws(()=>verify(bad,xml),/pool/);
});

test('missing BUILD SUCCESS and invalid JSON metadata are rejected',()=>{
  const {log,xml}=fixture();
  assert.throws(()=>verify(log.replace('[INFO] BUILD SUCCESS',''),xml));
  assert.throws(()=>verify(log.replace('"product":"MySQL"','"product":broken'),xml));
});

test('ANSI colors and CRLF do not bypass validation',()=>{
  const {log,xml}=fixture(); assert.equal(verify('\x1b[32m'+log.replaceAll('\n','\r\n')+'\x1b[0m',xml).executed,18);
});

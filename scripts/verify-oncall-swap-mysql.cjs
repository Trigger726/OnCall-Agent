const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { suiteResult } = require('./verify-mysql-lifecycle.cjs');
const suite = 'org.trigger.opspilot.oncall.MySqlOnCallSwapIntegrationTest';
const cases = [
  'shouldExchangeBothFutureShiftsAtomicallyAndPreserveOrdinaryHistory',
  'shouldExchangeAcrossSchedulesWithoutChangingEitherRotation',
  'shouldRequireActualParticipantsEvenForAdministrators',
  'shouldRecheckBothParticipantsAndPermitRejectionWhenRequesterIsDisabled',
  'shouldRejectEitherStaleSourceAndStillAllowWithdrawal',
  'shouldRejectEitherInactiveScheduleAndStillAllowTargetRejection',
  'shouldRejectExistingOverridesAndLegacyOrdinaryOverlapWithoutHalfSwap',
  'shouldRejectSelfExchangeInvalidKeysAndChangedIdempotentPayload',
  'shouldRejectStartedShiftsAndOverrideSources',
  'shouldRollbackBothOverridesWhenSecondCreationAuditFails',
  'shouldRollbackBothOverridesDecisionAndAuditsWhenFinalAuditFails',
  'shouldSerializeConcurrentSameKeyRequestsAndAcceptRetries',
  'shouldHaveOneWinnerBetweenAcceptanceAndWithdrawal',
  'shouldSerializeReverseCrossScheduleRequestsWithoutDeadlockOrDoubleExchange',
  'shouldAcknowledgeAcceptedHistoryWithoutRevivingCancelledCoverage',
  'shouldFilterParticipantAndEitherScheduleBeforeLimitAndExposeTruncation',
  'shouldEnforceHttpRolesParticipantAndExplicitVersions'
];
function verify(log, xml) {
  log = log.replace(/\u001b\[[0-?]*[ -/]*[@-~]/g, '');
  const result = suiteResult(xml, suite, cases.length); assert.equal(result.tests, cases.length);
  assert.match(log, /\[INFO\]\s+BUILD SUCCESS/);
  const errors = log.split(/\r?\n/).filter(l=>/(?:^|\s)(?:ERROR|\[ERROR\])(?:\s|$)|Unexpected error occurred in scheduled task|Surefire is going to kill|Failed to validate connection|No operations allowed after connection closed|Communications link failure|native thread|EACCES/i.test(l));
  assert.equal(errors.length, 0, 'Complete Maven output including shutdown must be clean');
  const testcases = [...xml.matchAll(/<testcase\b[^>]*>[\s\S]*?<\/testcase>/g)].map(m=>m[0]);
  assert.equal(testcases.length, cases.length); const versions = new Set(), observed = [];
  for (const testcase of testcases) {
    const name = testcase.match(/\bname="([^"]+)"/)[1]; assert.ok(cases.includes(name)); observed.push(name);
    const markers = [...testcase.matchAll(/CP85_SWAP_DATABASE (\{[^\r\n<]*\})/g)]; assert.equal(markers.length, 1);
    const identity = JSON.parse(markers[0][1]); assert.equal(identity.case, name);
    assert.equal(identity.product, 'MySQL'); assert.match(identity.version, /^8\.4\./); assert.equal(identity.schema, 'opspilot_swap_test');
    versions.add(identity.version);
  }
  assert.deepEqual(observed.sort(), [...cases].sort()); assert.equal(versions.size, 1);
  const globalMarkers = [...log.matchAll(/^CP85_SWAP_DATABASE (.+)$/gm)]; assert.equal(globalMarkers.length, cases.length);
  assert.deepEqual(globalMarkers.map(m=>JSON.parse(m[1]).case).sort(), [...cases].sort());
  for (const marker of globalMarkers) assert.ok(xml.includes(marker[0].trimEnd()));
  const start='HikariPool-1 - Start completed.', stop='HikariPool-1 - Shutdown completed.';
  assert.equal(log.split(start).length, 2); assert.equal(log.split(stop).length, 2); assert.ok(log.indexOf(stop)>log.indexOf(start));
  return {status:'PASS',suite:result,executed:cases.length,skipped:0,cases,database:'MySQL',databaseVersion:[...versions][0],
    actualJdbcIdentityPerTest:true,migration34VerifiedPerTest:true,fullMavenAndOrderedPoolShutdownVerified:true};
}
module.exports={verify,suite,cases};
if(require.main===module){try{
  const log=process.argv[2]||'target/oncall-swap-mysql-it/maven.log',reports=process.argv[3]||'target/surefire-reports',output=process.argv[4]||'target/oncall-swap-mysql-it/result.json';
  const result=verify(fs.readFileSync(log,'utf8'),fs.readFileSync(path.join(reports,'TEST-'+suite+'.xml'),'utf8'));
  fs.writeFileSync(output,JSON.stringify(result,null,2)+'\n');console.log(JSON.stringify(result));
}catch(error){console.error('On-call swap MySQL gate failed: '+error.message);process.exitCode=1;}}

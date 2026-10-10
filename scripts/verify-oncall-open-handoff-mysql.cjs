const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { suiteResult } = require('./verify-mysql-lifecycle.cjs');
const suite = 'org.trigger.opspilot.oncall.MySqlOnCallOpenHandoffIntegrationTest';
const cases = [
  'shouldPublishAndClaimThroughHttpWithActualIndependentCoverage',
  'shouldSerializeConcurrentPublicationOriginalKeys',
  'shouldRejectChangedPublicationKeyContentAndMalformedWindows',
  'shouldAcknowledgeOriginalClaimAfterCancellationAndEndWithoutRevival',
  'shouldRecheckCurrentClaimantQualificationEvenForOriginalReceipt',
  'shouldRejectCancelledSourceButPermitExplicitOwnerWithdrawal',
  'shouldRejectInactivePlanAndPublisherWithoutChangingResponsibility',
  'shouldRejectNewOverrideAndLegacyOrdinaryOverlap',
  'shouldClaimOnlyRemainingSubwindowAndKeepOriginalTimes',
  'shouldRejectEndedClaimWithoutInventingAutomaticExpiryStatus',
  'shouldRollbackCoverageCreationAuditFailure',
  'shouldRollbackCoverageOperationAndFinalClaimAuditTogether',
  'shouldRollbackWithdrawalReceiptAndAuditTogether',
  'shouldAllowExactlyOneWinnerBetweenDifferentClaimants',
  'shouldSerializeConcurrentOriginalClaimKeyAcknowledgements',
  'shouldSerializeSameActorOperationKeyAcrossDifferentPlans',
  'shouldSerializeClaimVersusOwnerWithdrawalWithoutHalfCoverage',
  'shouldEnforcePersonalConsentAndOriginalOperationPayload',
  'shouldFilterAvailableAndMineInSqlBeforeBoundedInbox',
  'shouldEnforceHttpAuthenticationAndExplicitVersions',
  'shouldRecheckActualPersistedDeadlineAfterScheduleLockWait',
  'shouldRollbackWhenFinalClaimAuditCrossesPersistedDeadline',
  'shouldReadLatestQualificationAfterScheduleLockWait',
  'shouldRollbackPublicationWhenItsAuditFails'
];
const timeCases = cases.slice(20, 22);
function markers(value, prefix) {
  return [...value.matchAll(new RegExp(prefix + ' (\\{[^\\r\\n<]*\\})', 'g'))].map(m => JSON.parse(m[1]));
}
function instant(value) {
  assert.match(value, /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2}(?:\.\d{1,6})?)?$/);
  const result = Date.parse(value + 'Z'); assert.ok(Number.isFinite(result)); return result;
}
function verify(log, xml) {
  log = log.replace(/\u001b\[[0-?]*[ -/]*[@-~]/g, '');
  const result = suiteResult(xml, suite, cases.length); assert.equal(result.tests, cases.length);
  assert.match(log, /\[INFO\]\s+BUILD SUCCESS/);
  assert.doesNotMatch(log, /(?:^|\s)(?:ERROR|\[ERROR\])(?:\s|$)|Unexpected error occurred in scheduled task|Surefire is going to kill|Failed to validate connection|No operations allowed after connection closed|Communications link failure|native thread|EACCES/im);
  assert.doesNotMatch(xml, /<(?:skipped|failure|error)\b/);
  const testcases = [...xml.matchAll(/<testcase\b[^>]*>[\s\S]*?<\/testcase>/g)].map(m => m[0]);
  assert.equal(testcases.length, cases.length);
  const identities = [], barriers = [], observed = [];
  for (const testcase of testcases) {
    const name = testcase.match(/\bname="([^"]+)"/)[1]; observed.push(name);
    const own = markers(testcase, 'CP97_OPEN_HANDOFF_DATABASE'); assert.equal(own.length, 1);
    const identity = own[0]; assert.equal(identity.case, name);
    assert.equal(identity.product, 'MySQL'); assert.match(identity.version, /^8\.4\./);
    assert.equal(identity.schema, 'opspilot_open_handoff_test'); assert.equal(identity.migration38, true);
    assert.equal(identity.migration39, true); assert.equal(identity.migration40, true); assert.equal(identity.versionedMigrations, 40);
    identities.push(identity);
    const time = markers(testcase, 'CP97_OPEN_HANDOFF_TIME_BARRIER');
    assert.equal(time.length, timeCases.includes(name) ? 1 : 0);
    for (const barrier of time) {
      assert.equal(barrier.case, name); assert.equal(barrier.releasedAfterPersistedEnd, true);
      assert.doesNotMatch(barrier.requestedEnd, /\./); assert.doesNotMatch(barrier.persistedEnd, /\./);
      assert.equal(instant(barrier.requestedEnd), instant(barrier.persistedEnd));
      assert.ok(instant(barrier.releasedDatabaseNow) >= instant(barrier.persistedEnd)); barriers.push(barrier);
    }
  }
  assert.deepEqual(observed.sort(), [...cases].sort());
  const sorted = rows => rows.sort((a, b) => a.case.localeCompare(b.case));
  assert.deepEqual(sorted(markers(log, 'CP97_OPEN_HANDOFF_DATABASE')), sorted(identities));
  assert.deepEqual(sorted(markers(log, 'CP97_OPEN_HANDOFF_TIME_BARRIER')), sorted(barriers));
  assert.equal(new Set(identities.map(row => row.version)).size, 1);
  const starts = [...log.matchAll(/(HikariPool-\d+) - Start completed\./g)];
  const stops = [...log.matchAll(/(HikariPool-\d+) - Shutdown completed\./g)];
  assert.equal(starts.length, 1); assert.equal(stops.length, 1);
  assert.equal(starts[0][1], stops[0][1]); assert.ok(stops[0].index > starts[0].index);
  return { status: 'PASS', suite: result, executed: cases.length, skipped: 0, cases,
    database: 'MySQL', databaseVersion: identities[0].version, actualJdbcIdentityPerTest: true,
    migration38VerifiedPerTest: true, migration39VerifiedPerTest: true, migration40VerifiedPerTest: true, versionedMigrations: 40, persistedWholeSecondDeadlineBarriers: sorted(barriers),
    fullMavenAndOrderedPoolShutdownVerified: true };
}
module.exports = { verify, suite, cases, timeCases };
if (require.main === module) { try {
  const log = process.argv[2] || 'target/oncall-open-handoff-mysql-it/maven.log';
  const reports = process.argv[3] || 'target/surefire-reports';
  const output = process.argv[4] || 'target/oncall-open-handoff-mysql-it/result.json';
  const result = verify(fs.readFileSync(log, 'utf8'), fs.readFileSync(path.join(reports, 'TEST-' + suite + '.xml'), 'utf8'));
  fs.writeFileSync(output, JSON.stringify(result, null, 2) + '\n'); console.log(JSON.stringify(result));
} catch (error) { console.error('Open handoff MySQL gate failed: ' + error.message); process.exitCode = 1; } }

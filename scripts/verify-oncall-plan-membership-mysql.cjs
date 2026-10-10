const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { suiteResult } = require('./verify-mysql-lifecycle.cjs');
const suite = 'org.trigger.opspilot.oncall.MySqlOnCallPlanMembershipIntegrationTest';
const cases = [
  'shouldBackfillOnlyPreexistingOperationalUsersAndNeverEnrolNewPlansOrAccounts',
  'shouldSeparateManagementFromResponseAndKeepAdminRecoveryWithoutSelfClaim',
  'shouldDenyAnotherValidPlanAndPermitItsExplicitMemberControl',
  'shouldRejectMissingExpectedVersionButAcceptExplicitNullAndSerializeEffectivePermissions',
  'shouldRecordActualActorIpAndOldAndNewPermissions',
  'shouldCommitOneImmutableReceiptForConcurrentOriginalCommands',
  'shouldRejectCrossPlanOrChangedKeyAndStaleOrOverflowedVersions',
  'shouldPermitOriginalManagerReceiptAfterOwnPlanManagementWasRevoked',
  'shouldRollbackFirstGrantAfterActualAuditInsertFails',
  'shouldRollbackUpdateAfterActualAuditInsertFails',
  'shouldRollbackRevocationAfterActualAuditInsertFails',
  'shouldRetainAssignedCoverageAndOriginalClaimReceiptAfterMembershipRevocation',
  'shouldSerializeRevocationBeforeClaimWithObservedDatabaseLockWait',
  'shouldPreserveClaimBeforeRevocationWithObservedDatabaseLockWait',
  'shouldFilterDepartedRequestersBeforeAvailableLimitAndAllowOwnWithdrawal',
  'shouldRequireResponseForManualOverridesAndManagementForCancellation',
  'shouldRejectNewDesignatedAcceptanceButAllowDepartedTargetRejection',
  'shouldRequireBothParticipantsInBothPlansForBilateralSwap',
  'shouldBlockOriginalRotationSlotsWithoutReassigningHistoryAndResumeOnlyMissingSlots',
  'shouldNotWriteRotationWarningWhenManualScannerHasNoPlanManagement',
  'shouldRetainBadRotationTargetErrorContractAndRejectInvalidManagementTargets',
  'shouldRecheckCurrentRoleAndNotResurrectRevokedMembershipWhenAccountIsRestored'
];
const lockCases = cases.filter(name => name.includes('ObservedDatabaseLockWait'));
const markers = (text, prefix) => [...text.matchAll(new RegExp(prefix + ' (\\{[^\\r\\n<]+\\})', 'g'))].map(m => JSON.parse(m[1]));
const sorted = rows => [...rows].sort((a, b) => a.case.localeCompare(b.case));
function verify(log, xml) {
  const counts = suiteResult(xml, suite, cases.length); assert.equal(counts.tests, cases.length);
  assert.doesNotMatch(xml, /<(?:skipped|failure|error)\b/);
  assert.match(log, /\[INFO\]\s+BUILD SUCCESS/);
  assert.doesNotMatch(log, /(?:^|\s)(?:ERROR|\[ERROR\])(?:\s|$)|Surefire is going to kill|Failed to validate connection|Communications link failure|native thread/im);
  const observed = [], identities = [], orders = [];
  for (const match of xml.matchAll(/<testcase\b([^>]*)>([\s\S]*?)<\/testcase>/g)) {
    const name = match[1].match(/\bname="([^"]+)"/)?.[1]; assert.ok(cases.includes(name)); observed.push(name);
    assert.ok(match[1].includes('classname="' + suite + '"'));
    const own = markers(match[2], 'PLAN_MEMBERSHIP_DATABASE'); assert.equal(own.length, 1);
    const identity = own[0]; assert.equal(identity.case, name); assert.equal(identity.product, 'MySQL');
    assert.match(identity.version, /^8\.4\./); assert.equal(identity.schema, 'opspilot_plan_membership_test'); assert.equal(identity.migration39, true);
    identities.push(identity);
    const barriers = markers(match[2], 'PLAN_MEMBERSHIP_LOCK_ORDER'); assert.equal(barriers.length, lockCases.includes(name) ? 1 : 0);
    for (const barrier of barriers) {
      assert.equal(barrier.case, name); assert.equal(barrier.databaseLockWaitObserved, true);
      assert.equal(barrier.responsibilityMatchesCommitOrder, true); assert.equal(barrier.revocationFirst, name === lockCases[0]); orders.push(barrier);
    }
  }
  assert.deepEqual(observed.sort(), [...cases].sort());
  assert.deepEqual(sorted(markers(log, 'PLAN_MEMBERSHIP_DATABASE')), sorted(identities));
  assert.deepEqual(sorted(markers(log, 'PLAN_MEMBERSHIP_LOCK_ORDER')), sorted(orders));
  assert.equal(new Set(identities.map(row => row.version)).size, 1);
  const starts = [...log.matchAll(/(HikariPool-\d+) - Start completed\./g)], stops = [...log.matchAll(/(HikariPool-\d+) - Shutdown completed\./g)];
  assert.equal(starts.length, 1); assert.equal(stops.length, 1); assert.equal(starts[0][1], stops[0][1]); assert.ok(stops[0].index > starts[0].index);
  return { status: 'PASS', suite: counts, cases, executed: cases.length, skipped: 0, database: identities[0],
    orderedResponsibilityRaces: sorted(orders), completeMavenAndPoolShutdownVerified: true,
    populatedOldJarUpgradeVerified: false, fullCiMatrixVerified: false, membershipUiVerified: false };
}
module.exports = { verify, suite, cases, lockCases };
if (require.main === module) { try {
  const parent = path.resolve(__dirname, '../target/oncall-plan-membership-mysql-it');
  const result = verify(fs.readFileSync(path.join(parent, 'maven.log'), 'utf8'),
    fs.readFileSync(path.resolve(__dirname, '../target/surefire-reports/TEST-' + suite + '.xml'), 'utf8'));
  fs.writeFileSync(path.join(parent, 'result.json'), JSON.stringify(result, null, 2) + '\n'); console.log(JSON.stringify(result));
} catch (error) { console.error('Plan membership MySQL gate failed: ' + error.message); process.exitCode = 1; } }

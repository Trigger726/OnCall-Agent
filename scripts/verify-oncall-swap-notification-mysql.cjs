const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const {suiteResult}=require('./verify-mysql-lifecycle.cjs');
const suite='org.trigger.opspilot.oncall.MySqlOnCallSwapNotificationIntegrationTest';
const cases=[
  'shouldEnqueueRequestAndBothDecisionRecipientsOnceWithoutDecidingFromReceipt',
  'shouldRollbackRequestAuditAndNotificationTogether',
  'shouldRollbackBothOverridesAndNotificationRowsWhenEnqueueFails',
  'shouldKeepFrozenPayloadAndStableKeyAcrossRealTransientRetry',
  'shouldSkipSupersededRequestButNotifyBothParticipantsOfRejection',
  'shouldSkipStartedCancelledOrCoveredRequestSourcesBeforeSending',
  'shouldSkipIneligibleRecipientWithoutExposingPayload',
  'shouldBoundAttemptsAndNotPersistSensitiveReceiverBody',
  'shouldRejectRedirectAndPermanentFailureWithoutForwardingCredential',
  'shouldSerializeConcurrentDispatchersWithoutDuplicateLiveClaim',
  'shouldFenceExpiredOldReceiptAndReuseSameFrozenKey',
  'shouldStopReclaimingExhaustedExpiredLeaseWithoutAnotherHttp',
  'shouldManuallyRetryOriginalVersionAndAcknowledgeLostReceiptWithoutNewAudit',
  'shouldRollbackManualRetryWhenAuditFails',
  'shouldEnforceAuthenticatedHttpRolesAndExplicitRetryVersion',
  'shouldRejectRetryForForeignSwapIneligibleActorOrSupersededEvent',
  'shouldPreventExternalDeliveryFromInsideBusinessTransactionAndPreserveDisabledHistory',
  'shouldRunOneUnqueuedWorkerWithoutBlockingSharedSchedulerTicks'
];
function identity(marker,name){const row=JSON.parse(marker);assert.equal(row.case,name);assert.equal(row.product,'MySQL');assert.match(row.version,/^8\.4\./);assert.equal(row.schema,'opspilot_swap_notification_test');return row;}
function verify(log,xml){
  log=log.replace(/\u001b\[[0-?]*[ -/]*[@-~]/g,'');
  const result=suiteResult(xml,suite,cases.length);assert.equal(result.tests,cases.length);
  assert.match(log,/\[INFO\]\s+BUILD SUCCESS/);
  const bad=log.split(/\r?\n/).filter(l=>/(?:^|\s)(?:ERROR|\[ERROR\])(?:\s|$)|Unexpected error occurred in scheduled task|Surefire is going to kill|Failed to validate connection|No operations allowed after connection closed|Communications link failure|native thread|EACCES/i.test(l));
  assert.equal(bad.length,0,'Complete Maven output, including shutdown, must be clean');
  const observed=[],versions=new Set();
  const testcases=[...xml.matchAll(/<testcase\b[^>]*>[\s\S]*?<\/testcase>/g)];assert.equal(testcases.length,cases.length);
  for(const match of testcases){const name=match[0].match(/\bname="([^"]+)"/)[1];assert.ok(cases.includes(name));observed.push(name);
    const markers=[...match[0].matchAll(/CP87_SWAP_NOTIFICATION_DATABASE (\{[^\r\n<]*\})/g)];assert.equal(markers.length,1);versions.add(identity(markers[0][1],name).version);}
  assert.deepEqual(observed.sort(),[...cases].sort());assert.equal(versions.size,1);
  const global=[...log.matchAll(/^CP87_SWAP_NOTIFICATION_DATABASE (\{[^\r\n]*\})/gm)];assert.equal(global.length,cases.length);
  assert.deepEqual(global.map(m=>{const row=JSON.parse(m[1]);identity(m[1],row.case);assert.ok(xml.includes(m[0].trimEnd()));return row.case;}).sort(),[...cases].sort());
  const started=[...log.matchAll(/(HikariPool-\d+) - Start completed\./g)],stopped=[...log.matchAll(/(HikariPool-\d+) - Shutdown completed\./g)];
  assert.equal(started.length,1);assert.equal(stopped.length,1);assert.equal(started[0][1],stopped[0][1]);assert.ok(stopped[0].index>started[0].index);
  return {status:'PASS',suite:result,executed:cases.length,skipped:0,cases,database:'MySQL',databaseVersion:[...versions][0],actualJdbcIdentityPerTest:true,migration35VerifiedPerTest:true,fullMavenAndOrderedPoolShutdownVerified:true};
}
module.exports={verify,suite,cases};
if(require.main===module){try{const dir='target/oncall-swap-notification-mysql-it',log=process.argv[2]||dir+'/maven.log',reports=process.argv[3]||'target/surefire-reports',output=process.argv[4]||dir+'/result.json';
  const result=verify(fs.readFileSync(log,'utf8'),fs.readFileSync(path.join(reports,'TEST-'+suite+'.xml'),'utf8'));
  fs.writeFileSync(output,JSON.stringify(result,null,2)+'\n');console.log(JSON.stringify(result));
}catch(error){console.error('Swap notification MySQL gate failed: '+error.message);process.exitCode=1;}}

const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const {suiteResult}=require('./verify-mysql-lifecycle.cjs');
const suite='org.trigger.opspilot.oncall.MySqlOnCallOpenHandoffRecipientIntegrationTest';
const cases=['shouldUseOnlyThisPlansCurrentRespondersAndExcludePublisherAndManagerOnly','shouldFreezeOriginalMemberVersionAndNameWithoutAddingLaterGrants',
  'shouldRecheckRecipientAccountAndNotResurrectRevokedMembership','shouldRecheckPublisherResponseAndAccountBeforeDeliveryObservation',
  'shouldStopCandidatesForInactivePlanAndChangedOrCancelledSource','shouldStopCandidatesWhenAnotherActualOverrideOverlapsRemainingWindow',
  'shouldKeepPublicationAfterClaimAndNeverTreatSnapshotAsActiveCoverage','shouldKeepPublicationAfterWithdrawalAndOriginalKeyAcknowledgement',
  'shouldStopExpiredCandidatesUsingDatabaseTimeWithoutChangingFrozenWindow','shouldPersistEmptyOriginalAudienceAndNeverBackfillNewMembersOnReplay',
  'shouldKeepLegacyPublicationUnavailableWithoutInferringHistoricalRecipients','shouldRollbackRequestAndAuditWhenSnapshotInsertFailsAfterActualInsert',
  'shouldRollbackWholePublicationWhenAuditFailsAfterActualInsert','shouldExposeAuthenticatedReadOnlySnapshotWithoutSecretsAndRequireEnclosingTransaction'];
const markers=s=>[...s.matchAll(/OPEN_RECIPIENT_DATABASE (\{[^\r\n<]+\})/g)].map(m=>JSON.parse(m[1]));
const sorted=rows=>[...rows].sort((a,b)=>a.case.localeCompare(b.case));
function verify(log,xml,mysql=true){
  const name=mysql?suite:suite.replace('MySqlOnCall','OnCall'),counts=suiteResult(xml,name,cases.length);
  assert.equal(counts.tests,cases.length);assert.doesNotMatch(xml,/<(?:skipped|error|failure)\b/);assert.match(log,/\[INFO\]\s+BUILD SUCCESS/);
  assert.doesNotMatch(log,/(?:^|\s)(?:ERROR|\[ERROR\])(?:\s|$)|Surefire is going to kill/im);
  const names=[],identities=[];
  for(const match of xml.matchAll(/<testcase\b([^>]*)>([\s\S]*?)<\/testcase>/g)){
    const caseName=match[1].match(/\bname="([^"]+)"/)?.[1];assert.ok(cases.includes(caseName));assert.ok(match[1].includes('classname="'+name+'"'));names.push(caseName);
    const own=markers(match[2]);assert.equal(own.length,1);const d=own[0];assert.equal(d.case,caseName);assert.equal(d.migration40,true);assert.equal(d.migration41,true);
    assert.equal(d.product,mysql?'MySQL':'H2');assert.match(d.version,mysql?/^8\.4\./:/^2\.2\.220/);assert.equal(d.schema,mysql?'opspilot_open_recipient_test':'opspilot-open-recipient-test');identities.push(d);
  }
  assert.deepEqual(names.sort(),[...cases].sort());assert.deepEqual(sorted(markers(log)),sorted(identities));assert.equal(new Set(identities.map(i=>i.version)).size,1);
  const starts=[...log.matchAll(/(HikariPool-\d+) - Start completed\./g)],stops=[...log.matchAll(/(HikariPool-\d+) - Shutdown completed\./g)];
  assert.ok(starts.length>0);if(mysql)assert.equal(starts.length,1);assert.equal(starts.length,stops.length);assert.equal(new Set(starts.map(s=>s[1])).size,starts.length);
  for(const start of starts){const own=stops.filter(s=>s[1]===start[1]);assert.equal(own.length,1);assert.ok(own[0].index>start.index);}
  return{status:'PASS',suite:counts,executed:14,skipped:0,database:identities[0].product,migration40VerifiedPerCase:true,
    originalPublicationAndCurrentEligibilitySeparated:true,allObservedPoolsStopped:starts.length,actualDeliveryVerified:false,remindersVerified:false,populatedV39JarUpgradeVerified:false};
}
module.exports={verify,suite,cases};
if(require.main===module){try{
  const mysql=process.argv[5]!=='H2',parent='target/oncall-open-recipient-mysql-it',name=mysql?suite:suite.replace('MySqlOnCall','OnCall');
  const r=verify(fs.readFileSync(process.argv[2]||parent+'/maven.log','utf8'),fs.readFileSync(process.argv[3]||'target/surefire-reports/TEST-'+name+'.xml','utf8'),mysql);
  const out=path.resolve(process.argv[4]||parent+'/result.json');assert.ok(out.startsWith(path.resolve('target')+path.sep));fs.mkdirSync(path.dirname(out),{recursive:true});fs.writeFileSync(out,JSON.stringify(r,null,2)+'\n');console.log(JSON.stringify(r));
}catch(e){console.error('Open recipient gate failed: '+e.message);process.exitCode=1;}}

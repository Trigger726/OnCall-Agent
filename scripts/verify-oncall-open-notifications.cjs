const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const {suiteResult}=require('./verify-mysql-lifecycle.cjs');
const suite='org.trigger.opspilot.oncall.MySqlOnCallOpenNotificationIntegrationTest';
const cases={shouldDeliverOnlyCapturedRespondersWithStableKeyAndNoAutomaticClaim:2,shouldKeepOriginalAcknowledgementReadOnlyBeforeAndAfterDelivery:1,
  shouldSkipRevokedOriginalRecipientAndNeverRetargetLaterGrants:1,shouldSkipDisabledRecipientAccountWithoutChangingFrozenPayload:0,
  shouldSkipWhenPublisherResponsePermissionIsRevoked:0,shouldSkipInactivePlan:0,shouldSkipCancelledSource:0,
  shouldSkipAfterActualClaimWithoutRewritingCoverageOrPublication:0,shouldSkipAfterActualWithdrawalAndPreserveOriginalEvent:0,
  shouldPersistEmptyAudienceWithoutLaterGrantBackfill:0,shouldRetryTransient503WithSameFrozenPayloadAndIdempotencyKey:2,
  shouldBound429RetriesAndNeverAutomaticallyRetryFailedEvents:2,shouldNotFollowRedirectsOrLeakChannelToken:1,
  shouldFenceLateOldLeaseReceiptAfterAnotherWorkerSucceeds:2,shouldRefuseNetworkDispatchInsideCallerTransaction:0,
  shouldRollbackRequestPublicationAuditAndOutboxAfterActualEnqueueFails:0,shouldRejectOversizedOriginalPublicationWithoutPartialRows:0,
  shouldExposeAuthenticatedTechnicalMetadataWithoutPayloadTokenOrLeaseOwner:1,shouldBoundTransportTimeoutWithoutClaimingDeliveryOrHumanAcceptance:1,
  shouldFinalizeExpiredMaximumLeaseWithoutSendingAgain:0,shouldFinalizePendingAttemptsAfterConfigurationLimitIsTightened:0,shouldDropBusyWorkerTicksWithoutInMemoryBacklog:1};
const markers=(text,name)=>[...text.matchAll(new RegExp(name+' (\\{[^\\r\\n<]+\\})','g'))].map(m=>JSON.parse(m[1]));
const sorted=rows=>[...rows].sort((a,b)=>a.case.localeCompare(b.case));
function verify(log,xml,mysql=true) {
  const name=mysql?suite:suite.replace('MySqlOnCall','OnCall'),counts=suiteResult(xml,name,22);assert.equal(counts.tests,22);
  assert.doesNotMatch(xml,/<(?:skipped|error|failure)\b/);assert.match(log,/\[INFO\]\s+BUILD SUCCESS/);assert.doesNotMatch(log,/(?:^|\s)(?:ERROR|\[ERROR\])(?:\s|$)|Surefire is going to kill/im);
  const identities=[],outcomes=[],names=[];
  for(const match of xml.matchAll(/<testcase\b([^>]*)>([\s\S]*?)<\/testcase>/g)) {
    const own=match[1].match(/\bname="([^"]+)"/)?.[1];assert.ok(own in cases);assert.ok(match[1].includes('classname="'+name+'"'));names.push(own);
    const ids=markers(match[2],'OPEN_NOTIFICATION_DATABASE'),facts=markers(match[2],'OPEN_NOTIFICATION_OUTCOME');assert.equal(ids.length,1);assert.equal(facts.length,1);
    const d=ids[0],f=facts[0];assert.equal(d.case,own);assert.equal(f.case,own);assert.equal(d.product,mysql?'MySQL':'H2');
    assert.match(d.version,mysql?/^8\.4\./:/^2\.2\.220/);assert.equal(d.schema,mysql?'opspilot_open_notification_test':'opspilot-open-notification-test');assert.equal(d.migration41,true);
    assert.equal(f.httpCalls,cases[own]);assert.equal(f.authenticatedRequests,f.httpCalls);assert.equal(f.keys.length,f.httpCalls);assert.equal(f.payloadSha256.length,f.httpCalls);
    for(const key of f.keys)assert.match(key,/^oncall-open-notification:[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/);for(const hash of f.payloadSha256)assert.match(hash,/^[a-f0-9]{64}$/);
    for(const event of f.events){assert.equal(event.eventVersion,0);assert.ok(['PENDING','DELIVERED','FAILED','SKIPPED'].includes(event.status));assert.equal(event.leaseUntil,null);assert.ok(event.attempts<=2);assert.ok(event.recipientId>0);}
    if(own.startsWith('shouldSkip'))assert.ok(f.events.some(e=>e.status==='SKIPPED'&&e.lastErrorCode==='NO_LONGER_ELIGIBLE'));
    if(['shouldRetryTransient503WithSameFrozenPayloadAndIdempotencyKey','shouldFenceLateOldLeaseReceiptAfterAnotherWorkerSucceeds'].includes(own)){
      assert.equal(new Set(f.keys).size,1);assert.equal(new Set(f.payloadSha256).size,1);assert.deepEqual(f.events.map(e=>e.status),['DELIVERED']);assert.equal(f.events[0].lastHttpStatus,200);assert.equal(f.events[0].attempts,2);
    }
    if(own==='shouldBound429RetriesAndNeverAutomaticallyRetryFailedEvents'){assert.deepEqual(f.events.map(e=>e.status),['FAILED']);assert.equal(f.events[0].lastHttpStatus,429);assert.equal(f.events[0].attempts,2);}
    if(own==='shouldNotFollowRedirectsOrLeakChannelToken'){assert.deepEqual(f.events.map(e=>e.status),['FAILED']);assert.equal(f.events[0].lastHttpStatus,302);}
    if(own==='shouldBoundTransportTimeoutWithoutClaimingDeliveryOrHumanAcceptance'){assert.deepEqual(f.events.map(e=>e.status),['PENDING']);assert.equal(f.events[0].lastErrorCode,'TRANSPORT_ERROR');assert.equal(f.events[0].deliveredAt,null);}
    if(own==='shouldFinalizeExpiredMaximumLeaseWithoutSendingAgain'){assert.deepEqual(f.events.map(e=>e.status),['FAILED']);assert.equal(f.events[0].lastErrorCode,'LEASE_EXPIRED');}
    if(own==='shouldFinalizePendingAttemptsAfterConfigurationLimitIsTightened'){assert.deepEqual(f.events.map(e=>e.status),['FAILED']);assert.equal(f.events[0].lastErrorCode,'ATTEMPTS_EXHAUSTED');}
    if(['shouldRollbackRequestPublicationAuditAndOutboxAfterActualEnqueueFails','shouldRejectOversizedOriginalPublicationWithoutPartialRows','shouldPersistEmptyAudienceWithoutLaterGrantBackfill'].includes(own))assert.deepEqual(f.events,[]);
    identities.push(d);outcomes.push(f);
  }
  assert.deepEqual(names.sort(),Object.keys(cases).sort());assert.deepEqual(sorted(markers(log,'OPEN_NOTIFICATION_DATABASE')),sorted(identities));assert.deepEqual(sorted(markers(log,'OPEN_NOTIFICATION_OUTCOME')),sorted(outcomes));
  const starts=[...log.matchAll(/(HikariPool-\d+) - Start completed\./g)],stops=[...log.matchAll(/(HikariPool-\d+) - Shutdown completed\./g)];
  assert.ok(starts.length>0);if(mysql)assert.equal(starts.length,1);assert.equal(starts.length,stops.length);
  for(const start of starts){const stop=stops.filter(s=>s[1]===start[1]);assert.equal(stop.length,1);assert.ok(stop[0].index>start.index);}
  return {status:'PASS',suite:counts,executed:22,skipped:0,database:identities[0].product,migration41VerifiedPerCase:true,
    observedAuthenticatedHttpRequests:outcomes.reduce((n,f)=>n+f.httpCalls,0),frozenRetryPayloadAndKeyVerified:true,revocationAndClosureSkipped:true,
    lateLeaseReceiptFenced:true,busyWorkerHasNoMemoryBacklog:true,allObservedPoolsStopped:starts.length,humanAcceptanceVerified:false,remindersVerified:false,
    actualThirdPartyAccountDeliveryVerified:false,populatedV40JarUpgradeVerified:false};
}
module.exports={verify,suite,cases};
if(require.main===module){try {
  const mysql=process.argv[5]!=='H2',parent='target/oncall-open-notification-mysql-it',name=mysql?suite:suite.replace('MySqlOnCall','OnCall');
  const result=verify(fs.readFileSync(process.argv[2]||parent+'/maven.log','utf8'),fs.readFileSync(process.argv[3]||'target/surefire-reports/TEST-'+name+'.xml','utf8'),mysql);
  const out=path.resolve(process.argv[4]||parent+'/result.json');assert.ok(out.startsWith(path.resolve('target')+path.sep));fs.mkdirSync(path.dirname(out),{recursive:true});fs.writeFileSync(out,JSON.stringify(result,null,2)+'\n');console.log(JSON.stringify(result));
}catch(e){console.error('Open notification evidence rejected: '+e.message);process.exitCode=1;}}

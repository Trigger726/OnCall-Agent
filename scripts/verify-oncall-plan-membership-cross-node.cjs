const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const {suiteResult}=require('./verify-mysql-lifecycle.cjs'),{unexpectedLogLines}=require('./verify-oncall-browser-ci.cjs');
const suite='org.trigger.opspilot.oncall.MySqlOnCallPlanMembershipCrossNodeIntegrationTest';
const caseNames=['two-live-jvms-concurrent-original-membership-command','remote-revocation-and-immutable-member-ack-without-rebase',
'committed-http-response-loss-remote-manual-ack-cancel-and-jvm-restart','manager-own-revocation-current-role-and-restoration-fences',
'two-valid-plans-response-isolation-and-qualified-control','two-jvm-concurrent-original-claim-one-responsibility'];
function verify(log,xml,result,jarLogs,mysql=true){const name=mysql?suite:suite.replace('MySqlOnCall','OnCall');
 const counts=suiteResult(xml,name,1);assert.equal(counts.tests,1);assert.doesNotMatch(xml,/<(?:failure|error|skipped)\b/);
 assert.match(xml,/name="shouldVerifyCurrentMembershipAndOriginalReceiptsAcrossTwoIndependentJvms"/);assert.match(log,/\[INFO\]\s+BUILD SUCCESS/);
 assert.doesNotMatch(log,/(?:^|\s)(?:ERROR|\[ERROR\])(?:\s|$)|Surefire is going to kill/im);assert.equal(result.status,'PASS');assert.match(result.jarSha256,/^[a-f0-9]{64}$/);
 assert.equal(result.database.product,mysql?'MySQL':'H2');if(mysql){assert.match(result.database.version,/^8\.4\./);assert.match(result.database.schema,/^opspilot_member_cross_[a-f0-9]{12}$/);assert.match(result.database.serverUuid,/^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/);}
 else assert.equal(result.database.schema,'owned-h2-tcp');assert.equal(result.versionedMigrations,39);assert.deepEqual(result.cases.map(c=>c.name),caseNames);
 const facts=[['sameOriginalOperation','oneReceipt','oneAudit'],['claim403','management403','originalOperationRetained','changedAndCrossPlanKey409','staleVersion409','noWrites'],
 ['statusLine200BeforeSocketClosed','bodyNotRead','cancelledCoverageNotRevived','snapshotUnchangedAfterRestartAck'],
 ['ownOriginalKey200','newManagement403','bothOldJwtAfterRoleLoss403','roleRestorationDoesNotRestorePlanManagement'],
 ['bothPlansValid','plan1Accepted','plan2SameActor403','plan2QualifiedControl200'],['sameOriginalOperation','oneReplacement','oneReceipt','exactlyTwoResponsibilityAudits']];
 for(const [i,keys] of facts.entries())for(const key of keys)assert.equal(result.cases[i][key],true,key);
 const original=result.cases[2].originalOperation;assert.equal(original.operation,'CLAIM');assert.equal(original.actorId,3);assert.equal(original.capturedVersion,0);assert.match(original.operationKey,/^[a-f0-9-]{36}$/);assert.equal(original.handoffId,result.cases[2].requestId);assert.ok(original.handoffId>0);
 assert.deepEqual(result.finalSql,{requests:5,claimed:4,open:1,claimOperations:4,memberOperations:5,memberAudits:5});
 for(const key of ['twoApplicationJvmsObservedAliveTogether','allApplicationPidsAbsent','portsFree','databaseOwnerStopped'])assert.equal(result[key],true);
 assert.equal(result.crossMachineOrHaClaimed,false);assert.equal(result.tokensPersistedToEvidence,false);
 assert.equal(result.startedPids.length,3);assert.equal(new Set(result.startedPids).size,3);assert.ok(result.startedPids.every(p=>Number.isInteger(p)&&p>1));assert.equal(result.applicationPorts.length,3);assert.equal(result.managementPorts.length,3);
 for(const list of [result.applicationPorts,result.managementPorts]){assert.ok(list.every(p=>Number.isInteger(p)&&p>0&&p<65536));assert.notEqual(list[0],list[1]);}
 assert.equal(result.pools.length,3);assert.equal(jarLogs.length,3);
 for(const [i,text] of jarLogs.entries()){assert.equal(result.pools[i].pid,result.startedPids[i]);assert.equal(result.pools[i].file,'jar-'+(i+1)+'.log');assert.equal(result.pools[i].completePoolShutdown,true);
 const start='HikariPool-1 - Start completed.',stop='HikariPool-1 - Shutdown completed.';assert.equal(text.split(start).length,2);assert.equal(text.split(stop).length,2);assert.ok(text.indexOf(stop)>text.indexOf(start));assert.equal(unexpectedLogLines(text),0);
 assert.ok(text.includes(mysql?'/'+result.database.schema+' (MySQL 8.4)':'jdbc:h2:tcp://127.0.0.1:'));}
 return {status:'PASS',suite:counts,httpCases:6,database:result.database,threeJvmLifecycles:true,twoLiveApplicationJvms:true,versionedMigrations:39,
  allPoolsAndOwnedDatabaseStopped:true,finalSql:result.finalSql,crossMachineOrHaVerified:false,membershipUiVerified:false};
}
module.exports={verify,suite,caseNames};
if(require.main===module){try{assert.ok(process.argv[2]===undefined||process.argv[2]==='H2');const mysql=process.argv[2]!=='H2',root=path.resolve(__dirname,'..'),folder=mysql?'oncall-plan-membership-cross-node-mysql-it':'oncall-plan-membership-cross-node-h2-it';
 const parent=path.join(root,'target',folder),log=fs.readFileSync(path.join(parent,'maven.log'),'utf8');
 const paths=[...log.matchAll(/PLAN_MEMBERSHIP_CROSS_NODE_RESULT ([^\r\n]+)/g)].map(m=>path.resolve(root,m[1].trim()));assert.equal(paths.length,1);assert.equal(path.dirname(path.dirname(paths[0])),parent);
 const result=JSON.parse(fs.readFileSync(paths[0],'utf8')),xml=fs.readFileSync(path.join(root,'target/surefire-reports/TEST-'+(mysql?suite:suite.replace('MySqlOnCall','OnCall'))+'.xml'),'utf8');
 const logs=Array.from({length:3},(_,i)=>fs.readFileSync(path.join(path.dirname(paths[0]),'jar-'+(i+1)+'.log'),'utf8'));const receipt=verify(log,xml,result,logs,mysql);
 fs.writeFileSync(path.join(parent,'gate-result.json'),JSON.stringify(receipt,null,2)+'\n');console.log(JSON.stringify(receipt));
}catch(error){console.error('Membership cross-node gate failed: '+error.message);process.exitCode=1;}}

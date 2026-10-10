const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const {suiteResult}=require('./verify-mysql-lifecycle.cjs'),{assertMigrations}=require('./verify-oncall-open-handoff-http-ci.cjs');
const {oldSource,caseNames}=require('./verify-oncall-plan-membership-upgrade-http-ci.cjs');
const {unexpectedLogLines}=require('./verify-oncall-browser-ci.cjs');
const suite='org.trigger.opspilot.oncall.MySqlPlanMembershipUpgradeHttpIntegrationTest';
const testName='shouldUpgradePopulatedV38AndRetainOriginalReceiptsAndMembershipAcrossRestarts';
const markers=(text,prefix)=>[...text.matchAll(new RegExp(prefix+' (\\{[^\\r\\n<]+\\})','g'))].map(m=>JSON.parse(m[1]));
function verify(log,xml,audit,result,jarLogs) {
  const counts=suiteResult(xml,suite,1);assert.equal(counts.tests,1);assert.doesNotMatch(xml,/<(?:skipped|failure|error)\b/);
  const tests=[...xml.matchAll(/<testcase\b[^>]*>/g)];assert.equal(tests.length,1);assert.ok(tests[0][0].includes('name="'+testName+'"'));assert.ok(tests[0][0].includes('classname="'+suite+'"'));
  assert.match(log,/\[INFO\]\s+BUILD SUCCESS/);assert.doesNotMatch(log,/(?:^|\s)(?:ERROR|\[ERROR\])(?:\s|$)|Surefire is going to kill|Failed to validate connection|Communications link failure/im);
  assert.equal(audit.status,'PASS');assert.equal(result.status,'PASS');assert.equal(result.databaseMode,'MYSQL_TESTCONTAINER');assert.equal(result.oldSource,oldSource);
  for(const key of ['oldJarSha256','jarSha256'])assert.match(result[key],/^[a-f0-9]{64}$/);assert.notEqual(result.oldJarSha256,result.jarSha256);
  const identity=audit.database;assert.equal(identity.product,'MySQL');assert.match(identity.version,/^8\.4\./);assert.match(identity.schema,/^opspilot_member_upgrade_[a-f0-9]{12}$/);
  assert.match(identity.serverUuid,/^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/);assert.equal(result.mysqlSchema,identity.schema);
  for(const text of [log,xml]){assert.deepEqual(markers(text,'PLAN_MEMBERSHIP_UPGRADE_DATABASE'),[identity]);assert.deepEqual(markers(text,'PLAN_MEMBERSHIP_UPGRADE_CONTAINER_STOPPED'),[{stopped:true}]);}
  assert.deepEqual(result.cases.map(c=>c.name),caseNames);const [old,upgrade,backfill,restart,revocation,restore,final]=result.cases;
  assert.equal(old.oldMemberApi404,true);assert.equal(old.threeStates,true);assert.equal(old.cancelledOriginalReceipt,true);
  assertMigrations(old.before,38);assertMigrations(upgrade.after,40);assertMigrations(final.final,40);
  assert.deepEqual(old.before.requests.map(r=>r.status).sort(),['CLAIMED','OPEN','WITHDRAWN']);assert.deepEqual(upgrade.after.requests,old.before.requests);
  for(const key of ['shiftHash','designatedHash','swapHash','swapRevocationHash','onCallAuditHash','openRequestHash','openOperationHash','v38MigrationHash']){
    assert.match(old.before[key],/^[a-f0-9]{64}$/);assert.equal(upgrade.after[key],old.before[key]);}
  assert.equal(upgrade.after.shifts,old.before.shifts);assert.equal(upgrade.allOldBusinessRowsPreserved,true);assert.equal(upgrade.all38MigrationChecksumsPreserved,true);
  assert.equal(old.before.members.length,0);assert.equal(backfill.oneTimeBackfill,true);assert.equal(backfill.auditorExcluded,true);assert.deepEqual(backfill.members,upgrade.after.members);
  assert.equal(backfill.members.length,6);const plans=[...new Set(backfill.members.map(m=>m.scheduleId))];assert.equal(plans.length,2);
  assert.deepEqual(backfill.members,plans.flatMap(scheduleId=>[1,2,3].map(userId=>({scheduleId,userId,active:true,canRespond:true,canManage:userId!==2,version:0,origin:'MIGRATED_GLOBAL_V38'}))));
  for(const key of ['memberHash','memberOperationHash'])assert.match(upgrade.after[key],/^[a-f0-9]{64}$/);
  assert.equal(restart.snapshotUnchanged,true);assert.equal(restart.cancelledNotRevived,true);
  assert.deepEqual(revocation.actualHttpStatuses,[403,403,200,409]);assert.equal(revocation.oldReceiptUnchanged,true);assert.equal(revocation.noNewResponsibility,true);
  assert.equal(restore.newClaimSucceeded,true);assert.equal(restore.oldCancelledCoverageUnchanged,true);assert.equal(final.snapshotUnchanged,true);assert.equal(final.originalReceiptsUnchanged,true);
  assert.equal(result.sqlFixtures.length,5);for(const snapshot of result.sqlFixtures){assert.equal(snapshot.actualProduct,'MySQL');assert.equal(snapshot.actualVersion,identity.version);assert.equal(snapshot.schema,identity.schema);assert.equal(snapshot.serverUuid,identity.serverUuid);assert.equal(snapshot.ownerConfirmed,true);}
  assert.deepEqual(result.sqlFixtures[0],old.before);assert.deepEqual(result.sqlFixtures[1],upgrade.after);assert.deepEqual(result.sqlFixtures[2],upgrade.after);
  assert.deepEqual(result.sqlFixtures[3],final.final);assert.deepEqual(result.sqlFixtures[4],final.final);
  assert.equal(final.final.v38MigrationHash,old.before.v38MigrationHash);assert.deepEqual(final.final.requests.map(r=>r.status).sort(),['CLAIMED','CLAIMED','WITHDRAWN']);
  assert.deepEqual(audit.finalJdbcCounts,{versionedMigrations:40,requests:3,claimed:2,withdrawn:1,operations:3,members:6,memberOperations:2});
  assert.equal(result.tokensPersistedToEvidence,false);assert.equal(result.userFileDatabaseModified,false);assert.equal(result.ownedProcessesStopped,true);assert.equal(result.unexpectedJarErrors,0);
  assert.equal(result.startedPids.length,5);assert.equal(new Set(result.startedPids).size,5);assert.deepEqual([...result.stoppedPids].sort(),[...result.startedPids].sort());
  for(const key of ['ownedContainerStopped','twoScopedPortsVerifiedFree','recordedJvmPidsVerifiedAbsent'])assert.equal(audit[key],true);
  assert.equal(jarLogs.length,5);assert.equal(audit.nodeConnections.length,5);
  for(const [i,raw] of jarLogs.entries()){const connection=audit.nodeConnections[i];assert.equal(connection.jar,i+1);assert.equal(connection.pid,result.startedPids[i]);assert.equal(connection.poolStartedAndStopped,true);
    assert.ok(raw.includes(connection.flywayDatabaseLine));assert.ok(connection.flywayDatabaseLine.includes('/'+identity.schema+' (MySQL 8.4)'));assert.equal(unexpectedLogLines(raw),0);
    const start='HikariPool-1 - Start completed.',stop='HikariPool-1 - Shutdown completed.';assert.equal(raw.split(start).length,2);assert.equal(raw.split(stop).length,2);assert.ok(raw.indexOf(stop)>raw.indexOf(start));}
  return {status:'PASS',suite:counts,httpCases:7,oldSource,database:identity,nonemptyOldV38ToV39:true,all38MigrationChecksumsPreserved:true,originalCancelledReceiptRetained:true,
    explicitRevocationAndRestorationVerified:true,fiveJvmPoolsStopped:true,ownedContainerStopped:true,membershipUiVerified:false,fullOriginalCiMatrixVerified:false};
}
module.exports={verify,suite,testName};
if(require.main===module){try{
  const root=path.resolve(__dirname,'..'),parent=path.join(root,'target/oncall-plan-membership-upgrade-mysql-it'),log=fs.readFileSync(path.join(parent,'maven.log'),'utf8');
  const paths=[...log.matchAll(/PLAN_MEMBERSHIP_UPGRADE_AUDIT ([^\r\n]+)/g)].map(m=>path.resolve(root,m[1].trim()));assert.equal(paths.length,1);assert.equal(path.dirname(path.dirname(paths[0])),parent);
  const audit=JSON.parse(fs.readFileSync(paths[0],'utf8')),resultFile=path.resolve(root,audit.runnerResultFile);assert.equal(path.dirname(path.dirname(resultFile)),parent);
  const result=JSON.parse(fs.readFileSync(resultFile,'utf8')),xml=fs.readFileSync(path.join(root,'target/surefire-reports/TEST-'+suite+'.xml'),'utf8');
  const jarLogs=Array.from({length:5},(_,i)=>fs.readFileSync(path.join(path.dirname(resultFile),'jar-'+(i+1)+'.log'),'utf8'));
  const receipt=verify(log,xml,audit,result,jarLogs);fs.writeFileSync(path.join(parent,'result.json'),JSON.stringify(receipt,null,2)+'\n');console.log(JSON.stringify(receipt));
}catch(e){console.error('Plan membership old/new MySQL gate failed: '+e.message);process.exitCode=1;}}

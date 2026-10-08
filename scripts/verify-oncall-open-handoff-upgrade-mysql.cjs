const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const {suiteResult}=require('./verify-mysql-lifecycle.cjs');
const {caseNames,assertMigrations,assertSqlRows}=require('./verify-oncall-open-handoff-http-ci.cjs');
const {unexpectedLogLines}=require('./verify-oncall-browser-ci.cjs');
const suite='org.trigger.opspilot.oncall.MySqlOpenHandoffUpgradeHttpIntegrationTest';
const testName='shouldUpgradePopulatedV37AndRecoverOriginalClaimsAfterRestartOnOwnedMySql';
const upgradeNames=['old-v37-omits-open-claim-api','populated-v37-upgrade-preserves-source-designated-receipts-and-cancelled-coverage'];
const fingerprints=['shiftHash','designatedHash','swapHash','swapRevocationHash','onCallAuditHash','legacyMigrationHash'];
function markers(text,prefix){return [...text.matchAll(new RegExp(prefix+' (\\{[^\\r\\n<]+\\})','g'))].map(m=>JSON.parse(m[1]));}
function verify(log,xml,audit,result,jarLogs){
  const counts=suiteResult(xml,suite,1);assert.equal(counts.tests,1);
  assert.doesNotMatch(xml,/<(?:skipped|failure|error)\b/);
  const testcases=[...xml.matchAll(/<testcase\b[^>]*>/g)];assert.equal(testcases.length,1);
  assert.ok(testcases[0][0].includes('name="'+testName+'"'));assert.ok(testcases[0][0].includes('classname="'+suite+'"'));
  assert.match(log,/\[INFO\]\s+BUILD SUCCESS/);
  assert.doesNotMatch(log,/(?:^|\s)(?:ERROR|\[ERROR\])(?:\s|$)|Surefire is going to kill|Failed to validate connection|Communications link failure|native thread/im);
  assert.equal(audit.status,'PASS');assert.equal(result.status,'PASS');assert.equal(result.databaseMode,'MYSQL_TESTCONTAINER');
  assert.equal(result.comparison,true);assert.equal(result.oldSource,'a1262f6765b46416a7fdc192642cf631465f4caf');
  assert.match(result.jarSha256,/^[a-f0-9]{64}$/);assert.match(result.oldJarSha256,/^[a-f0-9]{64}$/);assert.notEqual(result.jarSha256,result.oldJarSha256);
  const identity=audit.database;assert.equal(identity.product,'MySQL');assert.match(identity.version,/^8\.4\./);
  assert.match(identity.schema,/^opspilot_open_upgrade_[a-f0-9]{12}$/);assert.equal(result.mysqlSchema,identity.schema);
  assert.match(identity.serverUuid,/^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/);
  for(const text of [log,xml]){assert.deepEqual(markers(text,'CP104_OPEN_UPGRADE_DATABASE'),[identity]);
    const probe={exactId:true,leadingZero:false,differentId:false,trailingSpace:false,numericAlias:false};
    assert.deepEqual(audit.binaryAuditKeyProbe,probe);assert.deepEqual(markers(text,'CP105_OPEN_UPGRADE_BINARY_COMPARE'),[probe]);
    assert.deepEqual(markers(text,'CP104_OPEN_UPGRADE_CONTAINER_STOPPED'),[{stopped:true}]);}
  assert.deepEqual(result.cases.map(c=>c.name),[...upgradeNames,...caseNames]);
  const [omitted,upgrade,...cases]=result.cases;
  assert.equal(omitted.get404,true);assert.equal(omitted.post404,true);assert.equal(upgrade.legacyRowsPreserved,true);
  assert.equal(upgrade.oldBilateralReceiptAndResponsibilitiesPreserved,true);
  assertMigrations(upgrade.before,37);assertMigrations(upgrade.after,38);
  for(const key of fingerprints){assert.match(upgrade.before[key]||'',/^[a-f0-9]{64}$/);assert.equal(upgrade.before[key],upgrade.after[key]);}
  assert.equal(upgrade.before.shifts,upgrade.after.shifts);assert.deepEqual(upgrade.after.requests,[]);
  for(const key of ['designatedRows','swapRows','swapRevocationRows'])assert.ok(upgrade.before[key]>0);
  assert.deepEqual(result.sqlFixtures.map(f=>f.mode),['SNAPSHOT','SNAPSHOT','SNAPSHOT','DEMOTE','SNAPSHOT']);
  for(const fixture of result.sqlFixtures){assert.equal(fixture.exitCode,0);const r=fixture.receipt;
    assert.equal(r.actualProduct,identity.product);assert.equal(r.actualVersion,identity.version);assert.equal(r.schema,identity.schema);
    assert.equal(r.serverUuid,identity.serverUuid);assert.equal(r.ownerConfirmed,true);}
  assert.deepEqual(result.sqlFixtures[0].receipt,upgrade.before);assert.deepEqual(result.sqlFixtures[1].receipt,upgrade.after);
  assert.deepEqual(result.sqlFixtures[2].receipt,result.sqlFixtures[4].receipt);assert.deepEqual(result.finalSql,result.sqlFixtures[4].receipt);
  assertMigrations(result.finalSql,38);
  for(const key of [...fingerprints,'openRequestHash','openOperationHash'])assert.match(result.finalSql[key]||'',/^[a-f0-9]{64}$/);
  assert.equal(result.sqlFixtures[3].receipt.actorId,3);assert.equal(result.sqlFixtures[3].receipt.changed,1);assert.equal(result.sqlFixtures[3].receipt.role,'AUDITOR');
  assertSqlRows(result.finalSql.requests,result.sqlExpectations);assert.equal(result.finalSql.requests.length,5);
  assert.equal(cases[1].loss.actualSocketDestroyed,true);assert.equal(cases[1].loss.bodyRead,false);assert.equal(cases[1].loss.status,200);
  for(const key of ['sameReceiptAfterRestart','cancelledCoverageNotRevived','historicalClaimedPreserved'])assert.equal(cases[2][key],true);
  assert.deepEqual(cases[3].actualHttpStatuses,[200,409]);assert.equal(cases[3].oneOverride,true);
  assert.equal(cases[7].http403,true);assert.equal(cases[7].allBusinessSqlFingerprintsUnchanged,true);
  assert.equal(result.ownedProcessesStopped,true);assert.equal(result.unexpectedJarErrors,0);
  assert.equal(result.tokensPersistedToEvidence,false);assert.equal(result.userFileDatabaseModified,false);
  assert.equal(result.startedPids.length,5);assert.equal(new Set(result.startedPids).size,5);
  assert.deepEqual([...result.stoppedPids].sort(),[...result.startedPids].sort());
  for(const field of ['recordedJvmPidsVerifiedAbsent','twoScopedPortsVerifiedFree','ownedContainerStopped'])assert.equal(audit[field],true);
  assert.deepEqual(audit.finalJdbcCounts,{versionedMigrations:38,openRequests:5,claimed:3,withdrawn:1,open:1,operations:4,oldBilateralRevocations:1});
  assert.equal(jarLogs.length,5);assert.equal(audit.nodeConnections.length,5);
  for(const [i,raw] of jarLogs.entries()){
    const connection=audit.nodeConnections[i];assert.equal(connection.jar,i+1);assert.equal(connection.pid,result.startedPids[i]);assert.equal(connection.poolStartedAndStopped,true);
    assert.ok(raw.includes(connection.flywayDatabaseLine));assert.ok(connection.flywayDatabaseLine.includes('/'+identity.schema+' (MySQL 8.4)'));
    assert.equal(unexpectedLogLines(raw),0);assert.doesNotMatch(raw,/Failed to validate connection|Communications link failure|Surefire is going to kill/);
    const start='HikariPool-1 - Start completed.',stop='HikariPool-1 - Shutdown completed.';
    assert.equal(raw.split(start).length,2);assert.equal(raw.split(stop).length,2);assert.ok(raw.indexOf(stop)>raw.indexOf(start));
  }
  return{status:'PASS',suite:counts,executed:1,skipped:0,httpCases:10,oldSource:result.oldSource,database:identity,
    populatedOldJarUpgradeVerified:true,oldBilateralReceiptsAndMigrationChecksumsPreserved:true,originalClaimAfterRestartVerified:true,
    fullFiveJarPoolShutdownVerified:true,ownedContainerStopped:true};
}
module.exports={verify,suite,testName,upgradeNames};
if(require.main===module){try{
  const root=path.resolve(__dirname,'..'),parent=path.join(root,'target/oncall-open-handoff-upgrade-mysql-it');
  const log=fs.readFileSync(path.join(parent,'maven.log'),'utf8');
  const paths=[...log.matchAll(/CP104_OPEN_UPGRADE_AUDIT ([^\r\n]+)/g)].map(m=>m[1].trim());assert.equal(paths.length,1);
  const file=path.resolve(root,paths[0]);assert.equal(path.dirname(path.dirname(file)),parent);assert.match(path.basename(path.dirname(file)),/^audit-/);
  const audit=JSON.parse(fs.readFileSync(file,'utf8')),resultFile=path.resolve(root,audit.runnerResultFile);
  assert.equal(path.dirname(path.dirname(resultFile)),parent);assert.match(path.basename(path.dirname(resultFile)),/^run-/);
  const result=JSON.parse(fs.readFileSync(resultFile,'utf8'));
  const xml=fs.readFileSync(path.join(root,'target/surefire-reports/TEST-'+suite+'.xml'),'utf8');
  const logs=Array.from({length:5},(_,i)=>fs.readFileSync(path.join(path.dirname(resultFile),'jar-'+(i+1)+'.log'),'utf8'));
  const receipt=verify(log,xml,audit,result,logs);fs.writeFileSync(path.join(parent,'result.json'),JSON.stringify(receipt,null,2)+'\n');console.log(JSON.stringify(receipt));
}catch(error){console.error('Owned MySQL populated upgrade gate failed: '+error.message);process.exitCode=1;}}

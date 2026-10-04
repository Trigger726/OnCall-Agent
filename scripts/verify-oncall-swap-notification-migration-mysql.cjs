const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const {suiteResult}=require('./verify-mysql-lifecycle.cjs');
const suite='org.trigger.opspilot.oncall.MySqlOnCallSwapNotificationMigrationTest';
const requiredCase='shouldFreezeLegacyDeadlineWithoutErasingV35PayloadOrTechnicalHistory';
const states=['PENDING','CLAIMED','DELIVERED','FAILED','SKIPPED'];
function markers(text,prefix){return [...text.matchAll(new RegExp(prefix+' (\\{[^\\r\\n<]*\\})','g'))].map(m=>JSON.parse(m[1]));}
function verify(log,xml){
  log=log.replace(/\u001b\[[0-?]*[ -/]*[@-~]/g,'');const counts=suiteResult(xml,suite,1);assert.equal(counts.tests,1);
  assert.match(log,/\[INFO\]\s+BUILD SUCCESS/);assert.ok(!/<(?:skipped|failure|error)\b/.test(xml));
  const cases=[...xml.matchAll(/<testcase\b[^>]*>[\s\S]*?<\/testcase>/g)];assert.equal(cases.length,1);assert.equal(cases[0][0].match(/\bname="([^"]+)"/)[1],requiredCase);
  assert.ok(!/(?:^|\s)(?:ERROR|\[ERROR\])(?:\s|$)|Surefire is going to kill|Communications link failure|native thread|EACCES/i.test(log));
  const databasePrefix='CP91_SWAP_NOTIFICATION_MIGRATION_DATABASE',resultPrefix='CP91_SWAP_NOTIFICATION_MIGRATION_RESULT',stopPrefix='CP91_SWAP_NOTIFICATION_MIGRATION_CONTAINER_STOPPED';
  for(const prefix of [databasePrefix,resultPrefix,stopPrefix])assert.deepEqual(markers(log,prefix),markers(cases[0][0],prefix),'Log and exact testcase must contain the same evidence');
  const databases=markers(log,databasePrefix);assert.equal(databases.length,2);assert.deepEqual(databases.map(d=>d.phase),['V35_BEFORE','V36_AFTER']);
  for(const d of databases){assert.equal(d.case,requiredCase);assert.equal(d.product,'MySQL');assert.match(d.version,/^8\.4\./);assert.equal(d.schema,'opspilot_swap_notification_migration_test');assert.equal(d.rows,10);}
  assert.equal(databases[0].version,databases[1].version);
  const results=markers(log,resultPrefix);assert.equal(results.length,1);const result=results[0];assert.equal(result.case,requiredCase);assert.equal(result.rows,10);assert.equal(result.legacyColumns,21);
  assert.deepEqual(result.states,states);assert.deepEqual(result.stateCounts,Object.fromEntries(states.map(s=>[s,2])));assert.equal(result.pastRows,5);assert.equal(result.futureRows,5);
  assert.equal(result.upgradeMigrations,1);assert.equal(result.repeatMigrations,0);assert.match(result.legacyHistoryBeforeSha256,/^[0-9a-f]{64}$/);assert.equal(result.legacyHistoryBeforeSha256,result.legacyHistoryAfterSha256);
  for(const flag of ['allDeadlinesEqualCreatedPlus30Days','microsecondsPreserved','allPayloadsNotErased','parentCountsUnchanged','pastAndFutureRows'])assert.equal(result[flag],true,flag);
  assert.deepEqual(result.retentionIndexColumns,['payload_erased_at','payload_expires_at','id']);
  assert.deepEqual(Object.keys(result.parentCounts).sort(),['audit_log','oncall_shift','oncall_shift_swap','sys_user']);for(const n of Object.values(result.parentCounts))assert.ok(Number.isSafeInteger(n)&&n>=0);assert.equal(result.parentCounts.oncall_shift_swap,1);assert.ok(result.parentCounts.oncall_shift>=2&&result.parentCounts.sys_user>=3);
  assert.deepEqual(markers(log,stopPrefix),[{case:requiredCase,stopped:true}]);
  assert.ok(log.indexOf(databasePrefix)<log.indexOf(resultPrefix)&&log.indexOf(resultPrefix)<log.indexOf(stopPrefix));
  return {status:'PASS',suite:counts,executed:1,skipped:0,database:'MySQL',databaseVersion:databases[0].version,schema:databases[0].schema,actualJdbcBeforeAndAfter:databases,upgrade:result,ownedContainerStoppedAfterAssertions:true,completeMavenOutputVerified:true};
}
module.exports={verify,suite,requiredCase,states};
if(require.main===module){try{const folder='target/oncall-swap-notification-migration-mysql-it';const log=process.argv[2]||folder+'/maven.log',reports=process.argv[3]||'target/surefire-reports',output=process.argv[4]||folder+'/result.json';
  const result=verify(fs.readFileSync(log,'utf8'),fs.readFileSync(path.join(reports,'TEST-'+suite+'.xml'),'utf8'));fs.writeFileSync(output,JSON.stringify(result,null,2)+'\n');console.log(JSON.stringify(result));
}catch(error){console.error('Populated notification MySQL migration gate failed: '+error.message);process.exitCode=1;}}

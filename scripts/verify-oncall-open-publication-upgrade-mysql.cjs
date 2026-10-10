const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const {suiteResult}=require('./verify-mysql-lifecycle.cjs');
const {verify:verifyEvidence}=require('./verify-oncall-open-publication-upgrade-evidence.cjs');
const suite='org.trigger.opspilot.oncall.MySqlOpenPublicationUpgradeHttpIntegrationTest';
const testName='shouldUpgradeNonemptyV39AndRetainFrozenPublicationAcrossHttpAndRestart';
const markers=(text,name)=>[...text.matchAll(new RegExp(name+' (\\{[^\\r\\n<]+\\})','g'))].map(m=>JSON.parse(m[1]));
function verify(log,xml,audit,result,read) {
  const counts=suiteResult(xml,suite,1);assert.equal(counts.tests,1);assert.doesNotMatch(xml,/<(?:skipped|error|failure)\b/);
  const tests=[...xml.matchAll(/<testcase\b[^>]*>/g)];assert.equal(tests.length,1);assert.ok(tests[0][0].includes('name="'+testName+'"'));assert.ok(tests[0][0].includes('classname="'+suite+'"'));
  assert.match(log,/\[INFO\]\s+BUILD SUCCESS/);assert.doesNotMatch(log,/(?:^|\s)(?:ERROR|\[ERROR\])(?:\s|$)|Surefire is going to kill|Communications link failure/im);
  assert.equal(audit.status,'PASS');const identity=audit.database;
  assert.equal(identity.product,'MySQL');assert.match(identity.version,/^8\.4\./);assert.equal(identity.schema,result.mysqlSchema);
  assert.match(identity.serverUuid,/^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/);
  for(const text of [log,xml]){assert.deepEqual(markers(text,'OPEN_PUBLICATION_UPGRADE_DATABASE'),[identity]);assert.deepEqual(markers(text,'OPEN_PUBLICATION_UPGRADE_CONTAINER_STOPPED'),[{stopped:true}]);}
  for(const s of result.sqlFixtures){assert.equal(s.actualVersion,identity.version);assert.equal(s.serverUuid,identity.serverUuid);}
  for(const key of ['ownedContainerStopped','twoScopedPortsVerifiedFree','recordedJvmPidsVerifiedAbsent'])assert.equal(audit[key],true);
  assert.deepEqual(audit.finalJdbcCounts,{versionedMigrations:41,requests:4,withdrawn:2,operations:2,publications:2,memberOperations:4});
  return {...verifyEvidence(result,read,true),suite:counts,ownedContainerStopped:true};
}
module.exports={verify,suite,testName};
if(require.main===module){try {
  const root=path.resolve(__dirname,'..'),parent=path.join(root,'target/oncall-open-publication-upgrade-mysql-it');
  const log=fs.readFileSync(path.join(parent,'maven.log'),'utf8');
  const paths=[...log.matchAll(/OPEN_PUBLICATION_UPGRADE_AUDIT ([^\r\n]+)/g)].map(m=>path.resolve(root,m[1].trim()));assert.equal(paths.length,1);assert.equal(path.dirname(path.dirname(paths[0])),parent);
  const audit=JSON.parse(fs.readFileSync(paths[0],'utf8')),resultFile=path.resolve(root,audit.runnerResultFile);assert.equal(path.dirname(path.dirname(resultFile)),parent);
  const result=JSON.parse(fs.readFileSync(resultFile,'utf8')),xml=fs.readFileSync(path.join(root,'target/surefire-reports/TEST-'+suite+'.xml'),'utf8');
  const proof=verify(log,xml,audit,result,name=>fs.readFileSync(path.join(path.dirname(resultFile),name),'utf8'));
  fs.writeFileSync(path.join(parent,'result.json'),JSON.stringify(proof,null,2)+'\n');console.log(JSON.stringify(proof));
}catch(e){console.error('Publication MySQL upgrade rejected: '+e.message);process.exitCode=1;}}

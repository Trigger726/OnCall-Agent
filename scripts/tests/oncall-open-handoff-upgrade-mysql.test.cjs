const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const {configuration}=require('../verify-oncall-open-handoff-upgrade-mysql-ci.cjs');
const {verify,suite,testName,upgradeNames}=require('../verify-oncall-open-handoff-upgrade-mysql.cjs');
const {caseNames,assertMigrations}=require('../verify-oncall-open-handoff-http-ci.cjs');
const {createHash}=require('node:crypto');
const digest=value=>createHash('sha256').update(value).digest('hex');
const identity={product:'MySQL',version:'8.4.11',schema:'opspilot_open_upgrade_012345abcdef',serverUuid:'01234567-0123-4567-89ab-0123456789ab'};
function fixture(){
  const snapshot=maximum=>({actualProduct:identity.product,actualVersion:identity.version,schema:identity.schema,serverUuid:identity.serverUuid,ownerConfirmed:true,
    migrationCount:maximum,successfulHistoryRows:maximum,migration38:maximum===38,
    history:Array.from({length:maximum},(_,i)=>({version:String(i+1),type:[29,33].includes(i+1)?'JDBC':'SQL',success:true})),
    shifts:9,shiftHash:digest('shifts'),designatedHash:digest('designated'),swapHash:digest('swaps'),swapRevocationHash:digest('revocations'),onCallAuditHash:digest('audits'),legacyMigrationHash:digest('checksums'),
    designatedRows:1,swapRows:1,swapRevocationRows:1,requests:[]});
  const before=snapshot(37),after=snapshot(38),final=snapshot(38);
  final.openRequestHash=digest('open requests');final.openOperationHash=digest('operations');
  final.requests=Array.from({length:5},(_,i)=>({id:i+1,status:'CLAIMED',operations:1}));
  const original=caseNames.map(name=>({name}));
  original[1].loss={actualSocketDestroyed:true,bodyRead:false,status:200};
  Object.assign(original[2],{sameReceiptAfterRestart:true,cancelledCoverageNotRevived:true,historicalClaimedPreserved:true});
  Object.assign(original[3],{actualHttpStatuses:[200,409],oneOverride:true});
  Object.assign(original[7],{http403:true,allBusinessSqlFingerprintsUnchanged:true});
  const result={status:'PASS',databaseMode:'MYSQL_TESTCONTAINER',comparison:true,mysqlSchema:identity.schema,
    oldSource:'a1262f6765b46416a7fdc192642cf631465f4caf',oldJarSha256:'a'.repeat(64),jarSha256:'b'.repeat(64),
    cases:[{name:upgradeNames[0],get404:true,post404:true},{name:upgradeNames[1],before,after,legacyRowsPreserved:true,oldBilateralReceiptAndResponsibilitiesPreserved:true},...original],
    sqlFixtures:[{mode:'SNAPSHOT',receipt:before},{mode:'SNAPSHOT',receipt:after},{mode:'SNAPSHOT',receipt:final},
      {mode:'DEMOTE',receipt:{actualProduct:identity.product,actualVersion:identity.version,schema:identity.schema,serverUuid:identity.serverUuid,ownerConfirmed:true,actorId:3,changed:1,role:'AUDITOR'}},
      {mode:'SNAPSHOT',receipt:structuredClone(final)}].map(row=>({...row,exitCode:0})),finalSql:structuredClone(final),sqlExpectations:structuredClone(final.requests),
    ownedProcessesStopped:true,unexpectedJarErrors:0,tokensPersistedToEvidence:false,userFileDatabaseModified:false,startedPids:[11,12,13,14,15],stoppedPids:[11,12,13,14,15]};
  const line='INFO Database: jdbc:mysql://localhost:33061/'+identity.schema+' (MySQL 8.4)';
  const audit={status:'PASS',database:identity,ownedContainerStopped:true,recordedJvmPidsVerifiedAbsent:true,twoScopedPortsVerifiedFree:true,
    finalJdbcCounts:{versionedMigrations:38,openRequests:5,claimed:3,withdrawn:1,open:1,operations:4,oldBilateralRevocations:1},
    nodeConnections:result.startedPids.map((pid,i)=>({jar:i+1,pid,flywayDatabaseLine:line,poolStartedAndStopped:true}))};
  const markers='CP104_OPEN_UPGRADE_DATABASE '+JSON.stringify(identity)+'\nCP104_OPEN_UPGRADE_CONTAINER_STOPPED {"stopped":true}\n';
  const log=markers+'[INFO] BUILD SUCCESS\n';
  const xml='<testsuite name="'+suite+'" tests="1" failures="0" errors="0" skipped="0"><testcase classname="'+suite+'" name="'+testName+'"><system-out><![CDATA['+markers+']]></system-out></testcase></testsuite>';
  const jarLogs=Array(5).fill(line+'\nINFO HikariPool-1 - Start completed.\nINFO HikariPool-1 - Shutdown completed.\n');
  return{log,xml,audit,result,jarLogs};
}
const run=f=>verify(f.log,f.xml,f.audit,f.result,f.jarLogs);
function rejects(mutations){for(const mutate of mutations){const f=fixture();mutate(f);assert.throws(()=>run(f));}}
test('synthetic evidence validates only gate shape, not an actual MySQL execution',()=>assert.equal(run(fixture()).httpCases,10));
test('entry requires dedicated loopback Testcontainers schema and actual owner server UUID',()=>{
  const java=fs.readFileSync(path.join(__dirname,'../../src/test/java/org/trigger/opspilot/oncall/MySqlOpenHandoffUpgradeHttpIntegrationTest.java'),'utf8');
  for(const [name,value] of [['connectionTimeZone','UTC'],['forceConnectionTimeZoneToSession','true'],['useSSL','false'],['allowPublicKeyRetrieval','true']])assert.ok(java.includes('.withUrlParam("'+name+'","'+value+'")'));
  assert.doesNotMatch(java,/getJdbcUrl\(\)\s*\+/);
  const env={OPSPILOT_OPEN_UPGRADE_MYSQL_OWNER:'TESTCONTAINERS',OPSPILOT_UPGRADE_SCHEMA:identity.schema,OPSPILOT_UPGRADE_JDBC_URL:'jdbc:mysql://localhost:33061/'+identity.schema+'?useSSL=false',
    OPSPILOT_UPGRADE_SERVER_UUID:identity.serverUuid,OPSPILOT_UPGRADE_DB_USER:'opspilot',OPSPILOT_UPGRADE_DB_PASSWORD:'test-only'};
  assert.equal(configuration(env).schema,identity.schema);
  for(const changes of [{OPSPILOT_OPEN_UPGRADE_MYSQL_OWNER:''},{OPSPILOT_UPGRADE_SCHEMA:'production'},{OPSPILOT_UPGRADE_JDBC_URL:'jdbc:h2:mem:test'},
    {OPSPILOT_UPGRADE_JDBC_URL:'jdbc:mysql://remote-host:3306/'+identity.schema+'?useSSL=false'},{OPSPILOT_UPGRADE_SERVER_UUID:''},{OPSPILOT_UPGRADE_DB_PASSWORD:''},{OPSPILOT_OPEN_HANDOFF_UPGRADE:'1'}])assert.throws(()=>configuration({...env,...changes}));
});
test('MySQL history has no H2 TABLE marker; neither wrong history shape can pass',()=>{
  const before=fixture().result.cases[1].before;assertMigrations(before,37);
  for(const row of [{...before,actualProduct:'H2'},{...before,successfulHistoryRows:38},{...before,history:[{version:null,type:'TABLE',success:true},...before.history]}])assert.throws(()=>assertMigrations(row,37));
});
test('one skipped failed empty or renamed JUnit execution cannot pass',()=>rejects([
  ...['skipped','errors','failures'].map(k=>f=>f.xml=f.xml.replace(k+'="0"',k+'="1"')),
  f=>f.xml=f.xml.replace('tests="1"','tests="0"'),f=>f.xml=f.xml.replace(testName,'renamed'),f=>f.xml=f.xml.replace('</testsuite>',''),
  f=>f.xml=f.xml.replace('</testsuite>','<testcase name="extra"/></testsuite>'),f=>f.xml=f.xml.replace('classname="'+suite+'"','classname="wrong"')
]));
test('real identity is required in both Maven and XML and every SQL fixture',()=>rejects([
  f=>f.log=f.log.replace('MySQL','H2'),f=>f.xml=f.xml.replace('8.4.11','8.0.1'),f=>f.result.databaseMode='H2_OWNED_FILE',
  f=>f.result.sqlFixtures[2].receipt.ownerConfirmed=false,f=>f.result.sqlFixtures[3].receipt.serverUuid='other',f=>f.result.sqlFixtures[1].exitCode=1
]));
test('all ten HTTP cases and old/new immutable identities are mandatory',()=>rejects([
  f=>f.result.cases.pop(),f=>f.result.oldSource='HEAD',f=>f.result.oldJarSha256=f.result.jarSha256,
  f=>f.result.cases[0].post404=false,f=>f.result.cases[1].oldBilateralReceiptAndResponsibilitiesPreserved=false,
  f=>f.result.cases[3].loss.bodyRead=true,f=>f.result.cases[4].cancelledCoverageNotRevived=false
]));
test('empty old tables, changed migration checksums and changed historical receipts fail closed',()=>rejects([
  f=>f.result.cases[1].before.swapRows=0,f=>f.result.cases[1].before.designatedRows=0,f=>f.result.cases[1].before.swapRevocationRows=0,
  f=>f.result.cases[1].after.legacyMigrationHash='changed',f=>f.result.cases[1].after.swapRevocationHash='changed',f=>f.result.cases[1].after.shiftHash='changed',
  f=>{delete f.result.cases[1].before.legacyMigrationHash;delete f.result.cases[1].after.legacyMigrationHash;},f=>delete f.result.finalSql.openOperationHash
]));
test('role rejection and final independent JDBC counts cannot conceal extra or partial commits',()=>rejects([
  f=>f.result.sqlFixtures[4].receipt.requests.pop(),f=>f.result.finalSql.requests.pop(),f=>f.audit.finalJdbcCounts.operations=5,
  f=>f.result.cases[9].http403=200,f=>f.result.sqlFixtures[3].receipt.changed=0,f=>f.result.sqlExpectations[0].operations=0
]));
test('all five distinct owned JVMs, ports, complete raw pool logs and container shutdown are required',()=>rejects([
  f=>f.result.startedPids[1]=11,f=>f.result.stoppedPids.pop(),f=>f.audit.ownedContainerStopped=false,f=>f.audit.twoScopedPortsVerifiedFree=false,
  f=>f.jarLogs.pop(),f=>f.jarLogs[4]=f.jarLogs[4].replace('Shutdown completed.','Shutdown initiated.'),
  f=>f.jarLogs[2]+='\n ERROR late failure',f=>f.jarLogs[0]+='\nHikariPool-1 - Start completed.',f=>f.log+='\nWARN Surefire is going to kill',f=>f.log=f.log.replace('BUILD SUCCESS','missing')
]));
test('CI preserves fresh H2 and shared MySQL24 while adding a separately pinned full-JAR upgrade job',()=>{
  const yaml=fs.readFileSync(path.join(__dirname,'../../.github/workflows/ci.yml'),'utf8');
  const block=yaml.match(/  oncall-open-handoff-upgrade-mysql-integration:([\s\S]*?)(?=\n  [\w-]+:)/)[1];
  assert.match(block,/ref: a1262f6765b46416a7fdc192642cf631465f4caf/);assert.match(block,/path: target\/cp104-v37-source/);
  assert.match(block,/opspilot\.oncall\.open\.upgrade\.mysql\.enabled=true/);assert.match(block,/node scripts\/verify-oncall-open-handoff-upgrade-mysql\.cjs/);
  assert.doesNotMatch(block,/continue-on-error|OPSPILOT_OPEN_HANDOFF_UPGRADE|git reset|git checkout/);
  assert.match(yaml,/needs: \[[^\n]*oncall-open-handoff-upgrade-mysql-integration/);
  assert.match(yaml,/-Dtest=MySqlOnCallOpenHandoffIntegrationTest test/);assert.match(yaml,/node scripts\/verify-oncall-open-handoff-http-ci\.cjs/);
});

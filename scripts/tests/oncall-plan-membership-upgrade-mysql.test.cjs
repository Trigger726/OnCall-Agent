const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const {verify,suite,testName}=require('../verify-oncall-plan-membership-upgrade-mysql.cjs');
const {configuration,oldSource,caseNames}=require('../verify-oncall-plan-membership-upgrade-http-ci.cjs');
function fixture() {
  // Actual saved H2 run is used only as a gate-shape fixture, never as MySQL product proof.
  const result=JSON.parse(fs.readFileSync(path.resolve(__dirname,'../../docs/assets/v1.7-cp110/h2-upgrade/result.json'),'utf8'));
  const identity={product:'MySQL',version:'8.4.11',schema:'opspilot_member_upgrade_012345abcdef',serverUuid:'01234567-0123-4567-89ab-0123456789ab'};
  const patch=snapshot=>{snapshot.actualProduct='MySQL';snapshot.actualVersion=identity.version;snapshot.schema=identity.schema;snapshot.serverUuid=identity.serverUuid;
    snapshot.ownerConfirmed=true;snapshot.history=snapshot.history.filter(r=>r.version!==null);snapshot.successfulHistoryRows=snapshot.migrationCount;return snapshot;};
  for(const row of result.sqlFixtures)patch(row);
  for(const row of result.cases){if(row.before)patch(row.before);if(row.after)patch(row.after);if(row.final)patch(row.final);}
  result.databaseMode='MYSQL_TESTCONTAINER';result.mysqlSchema=identity.schema;
  const line='INFO Database: jdbc:mysql://localhost:33101/'+identity.schema+' (MySQL 8.4)';
  const audit={status:'PASS',database:identity,ownedContainerStopped:true,twoScopedPortsVerifiedFree:true,recordedJvmPidsVerifiedAbsent:true,
    finalJdbcCounts:{versionedMigrations:39,requests:3,claimed:2,withdrawn:1,operations:3,members:6,memberOperations:2},
    nodeConnections:result.startedPids.map((pid,i)=>({jar:i+1,pid,flywayDatabaseLine:line,poolStartedAndStopped:true}))};
  const markers='PLAN_MEMBERSHIP_UPGRADE_DATABASE '+JSON.stringify(identity)+'\nPLAN_MEMBERSHIP_UPGRADE_CONTAINER_STOPPED {"stopped":true}\n';
  return{result,audit,log:markers+'[INFO] BUILD SUCCESS\n',
    xml:'<testsuite name="'+suite+'" tests="1" failures="0" errors="0" skipped="0"><testcase name="'+testName+'" classname="'+suite+'"><system-out><![CDATA['+markers+']]></system-out></testcase></testsuite>',
    jarLogs:Array(5).fill(line+'\nINFO HikariPool-1 - Start completed.\nINFO HikariPool-1 - Shutdown completed.\n')};
}
const run=f=>verify(f.log,f.xml,f.audit,f.result,f.jarLogs);
function rejects(mutations){for(const change of mutations){const f=fixture();change(f);assert.throws(()=>run(f));}}
test('gate shape from saved real H2 is explicitly not an actual MySQL upgrade',()=>{const r=run(fixture());assert.equal(r.httpCases,7);assert.equal(r.membershipUiVerified,false);});
test('owner configuration cannot target user data remote hosts wrong schemas or implicit MySQL',()=>{
  assert.equal(configuration({}),null);
  const env={OPSPILOT_MEMBER_UPGRADE_MYSQL_OWNER:'TESTCONTAINERS',OPSPILOT_UPGRADE_SCHEMA:'opspilot_member_upgrade_012345abcdef',
    OPSPILOT_UPGRADE_JDBC_URL:'jdbc:mysql://localhost:33101/opspilot_member_upgrade_012345abcdef?useSSL=false',OPSPILOT_UPGRADE_DB_USER:'opspilot',
    OPSPILOT_UPGRADE_DB_PASSWORD:'test-only',OPSPILOT_UPGRADE_SERVER_UUID:'01234567-0123-4567-89ab-0123456789ab'};
  assert.equal(configuration(env).schema,env.OPSPILOT_UPGRADE_SCHEMA);
  for(const change of [{OPSPILOT_MEMBER_UPGRADE_MYSQL_OWNER:''},{OPSPILOT_UPGRADE_SCHEMA:'production'},{OPSPILOT_UPGRADE_JDBC_URL:'jdbc:mysql://remote-host:3306/production'},
    {OPSPILOT_UPGRADE_SERVER_UUID:''},{OPSPILOT_UPGRADE_DB_PASSWORD:''},{OPSPILOT_UPGRADE_DB_USER:'root'}])assert.throws(()=>configuration({...env,...change}));
  assert.throws(()=>configuration({OPSPILOT_UPGRADE_DB_PASSWORD:'x'}));
});
test('omitted skipped failed renamed or truncated real JUnit is rejected',()=>rejects([
  ...['tests','skipped','failures','errors'].map(k=>f=>f.xml=f.xml.replace(k+'="'+(k==='tests'?1:0)+'"',k+'="'+(k==='tests'?0:1)+'"')),
  f=>f.xml=f.xml.replace(testName,'renamed'),f=>f.xml=f.xml.replace('</testsuite>',''),f=>f.xml=f.xml.replace('<system-out>','<skipped/><system-out>'),f=>f.xml=f.xml.replace('classname="'+suite+'"','classname="other"')
]));
test('real owned database identity must agree with every offline snapshot and both logs',()=>rejects([
  f=>f.result.databaseMode='H2_OWNED_FILE',f=>f.audit.database.product='H2',f=>f.audit.database.version='8.0.1',f=>f.result.sqlFixtures[2].ownerConfirmed=false,
  f=>f.result.sqlFixtures[1].serverUuid='other',f=>f.xml=f.xml.replace('8.4.11','8.4.12'),f=>f.log=f.log.replace('PLAN_MEMBERSHIP_UPGRADE_CONTAINER_STOPPED','missing')
]));
test('nonempty three-state old database and exact 38 old checksums cannot be replaced by empty upgrade',()=>rejects([
  f=>f.result.cases[0].before.requests=[],f=>f.result.cases[0].before.openOperationHash=null,f=>f.result.cases[1].after.v38MigrationHash='changed',
  f=>f.result.cases[1].after.openRequestHash='changed',f=>f.result.cases[1].after.shiftHash='changed',f=>f.result.oldSource='HEAD',f=>f.result.oldJarSha256=f.result.jarSha256,
  f=>f.result.cases[1].after.migration39=false,f=>f.result.cases[0].before.migration39=true
]));
test('wrong backfill recipients permissions origin duplicate membership and repeat migration are rejected',()=>rejects([
  f=>f.result.cases[2].members[0].userId=4,f=>f.result.cases[2].members[1].canManage=true,f=>f.result.cases[2].members[0].origin='EXPLICIT',
  f=>f.result.cases[2].members.push(f.result.cases[2].members[0]),f=>f.result.sqlFixtures[2].memberHash='changed'
]));
test('revoked new responsibility or revived cancelled coverage cannot pass on green assertions alone',()=>rejects([
  f=>f.result.cases[4].actualHttpStatuses[0]=200,f=>f.result.cases[4].oldReceiptUnchanged=false,f=>f.result.cases[5].oldCancelledCoverageUnchanged=false,
  f=>f.result.cases[6].originalReceiptsUnchanged=false,f=>f.audit.finalJdbcCounts.memberOperations=1,f=>f.result.sqlFixtures[4].memberOperationHash='changed'
]));
test('all five JVMs ports complete pool shutdown and owned container exit are mandatory',()=>rejects([
  f=>f.result.startedPids[1]=f.result.startedPids[0],f=>f.result.stoppedPids.pop(),f=>f.audit.ownedContainerStopped=false,f=>f.audit.twoScopedPortsVerifiedFree=false,
  f=>f.jarLogs.pop(),f=>f.jarLogs[0]=f.jarLogs[0].replace('Shutdown completed.','Shutdown initiated.'),f=>f.jarLogs[1]+='\n ERROR failure',f=>f.log+='\nWARN Surefire is going to kill'
]));
test('WIP adds immutable V38 upgrade without replacing shared 22-case job or original HTTP cases',()=>{
  const yaml=fs.readFileSync(path.resolve(__dirname,'../../.github/workflows/plan-membership-wip.yml'),'utf8');
  assert.match(yaml,new RegExp('ref: '+oldSource));assert.match(yaml,/opspilot\.oncall\.membership\.upgrade\.mysql\.enabled=true/);
  assert.match(yaml,/node scripts\/verify-oncall-plan-membership-upgrade-mysql\.cjs/);assert.match(yaml,/-Dtest=MySqlOnCallPlanMembershipIntegrationTest test/);
  assert.match(yaml,/cd target\/cp110-v38-source && bash mvnw/);
  assert.doesNotMatch(yaml,/chmod[^\n]*target\/cp110-v38-source\/mvnw/);
  const source=fs.readFileSync(path.resolve(__dirname,'../verify-oncall-plan-membership-upgrade-http-ci.cjs'),'utf8');
  for(const name of caseNames)assert.ok(source.includes(name));assert.doesNotMatch(yaml,/continue-on-error|git reset|git checkout/);
});

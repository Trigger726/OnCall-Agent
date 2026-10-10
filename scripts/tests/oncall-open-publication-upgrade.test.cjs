const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),{createHash}=require('node:crypto');
const {verify}=require('../verify-oncall-open-publication-upgrade-evidence.cjs');
const {configuration,oldSource}=require('../verify-oncall-open-publication-upgrade-http-ci.cjs');
const mysqlGate=require('../verify-oncall-open-publication-upgrade-mysql.cjs');
function fixture(mysql=false) {
  // Saved actual H2 is authoritative only for its H2 scope. MySQL conversion below is synthetic gate shape, not a MySQL run.
  const folder=path.resolve(__dirname,'../../docs/assets/v1.7-cp116/h2-upgrade');
  const result=JSON.parse(fs.readFileSync(path.join(folder,'result.json'),'utf8')),files={};
  const identity={product:'MySQL',version:'8.4.11',schema:'opspilot_publication_upgrade_012345abcdef',serverUuid:'01234567-0123-4567-89ab-0123456789ab'};
  if(mysql){result.databaseMode='MYSQL_TESTCONTAINER';result.mysqlSchema=identity.schema;
    for(const s of result.sqlFixtures){Object.assign(s,{actualProduct:'MySQL',actualVersion:identity.version,schema:identity.schema,serverUuid:identity.serverUuid,ownerConfirmed:true});
      if(s.migrationCount===40){s.migrationCount=41;s.migration41=true;s.history.push({version:'41',type:'SQL',success:true});}
      s.history=s.history.filter(h=>h.version!==null);s.successfulHistoryRows=s.migrationCount;}}
  if(mysql)for(const view of [result.cases[2].first,result.cases[2].later,result.cases[2].second,result.cases[3].revoked,result.cases[3].publisherRevoked,result.cases[3].ended,...result.cases[5].restarted])view.deliveryImplemented=true;
  result.cases[0].before=result.sqlFixtures[0];result.cases[1].upgraded=result.sqlFixtures[1];result.cases[4].committed=result.sqlFixtures[2];result.cases[5].final=result.sqlFixtures[3];
  for(let i=1;i<=4;i++) {
    let text=fs.readFileSync(path.join(folder,'jar-'+i+'.log'),'utf8');
    if(mysql)text=text.replace(/Database: jdbc:h2:file:[^\r\n]+\(H2 2\.2\)/g,'Database: jdbc:mysql://localhost:33101/'+identity.schema+' (MySQL 8.4)');
    files['jar-'+i+'.log']=text;result.completeJarLogs[i-1].sha256=createHash('sha256').update(text).digest('hex');
  }
  const read=name=>/^sql-\d+\.log$/.test(name)?JSON.stringify(result.sqlFixtures[Number(name.match(/\d+/)[0])]):files[name];
  const audit={status:'PASS',database:identity,ownedContainerStopped:true,twoScopedPortsVerifiedFree:true,recordedJvmPidsVerifiedAbsent:true,
    finalJdbcCounts:{versionedMigrations:41,requests:4,withdrawn:2,operations:2,publications:2,memberOperations:4}};
  const markers='OPEN_PUBLICATION_UPGRADE_DATABASE '+JSON.stringify(identity)+'\nOPEN_PUBLICATION_UPGRADE_CONTAINER_STOPPED {"stopped":true}\n';
  const xml='<testsuite name="'+mysqlGate.suite+'" tests="1" failures="0" errors="0" skipped="0"><testcase name="'+mysqlGate.testName+'" classname="'+mysqlGate.suite+'"><system-out><![CDATA['+markers+']]></system-out></testcase></testsuite>';
  return {result,files,read,audit,xml,log:markers+'[INFO] BUILD SUCCESS\n'};
}
const run=f=>verify(f.result,f.read,false,40),runMysql=f=>mysqlGate.verify(f.log,f.xml,f.audit,f.result,f.read);
const rejects=changes=>{for(const change of changes){const f=fixture();change(f);assert.throws(()=>run(f));}};
test('saved real H2 upgrade evidence replays with six cases and four gracefully stopped JARs',()=>{
  const r=run(fixture());assert.equal(r.cases,6);assert.equal(r.database,'H2');assert.equal(r.actualDeliveryVerified,false);
});
test('synthetic MySQL gate shape passes but actual saved H2 cannot count as MySQL',()=>{
  assert.equal(runMysql(fixture(true)).database,'MySQL');assert.throws(()=>verify(fixture().result,fixture().read,true));
});
test('owned configuration rejects remote arbitrary implicit or missing database ownership',()=>{
  assert.equal(configuration({}),null);
  const env={OPSPILOT_PUBLICATION_UPGRADE_MYSQL_OWNER:'TESTCONTAINERS',OPSPILOT_UPGRADE_SCHEMA:'opspilot_publication_upgrade_012345abcdef',
    OPSPILOT_UPGRADE_JDBC_URL:'jdbc:mysql://localhost:33101/opspilot_publication_upgrade_012345abcdef?useSSL=false',
    OPSPILOT_UPGRADE_DB_USER:'opspilot',OPSPILOT_UPGRADE_DB_PASSWORD:'test-only',OPSPILOT_UPGRADE_SERVER_UUID:'01234567-0123-4567-89ab-0123456789ab'};
  assert.equal(configuration(env).schema,env.OPSPILOT_UPGRADE_SCHEMA);
  for(const patch of [{OPSPILOT_PUBLICATION_UPGRADE_MYSQL_OWNER:''},{OPSPILOT_UPGRADE_JDBC_URL:'jdbc:mysql://remote:3306/production'},
    {OPSPILOT_UPGRADE_SCHEMA:'production'},{OPSPILOT_UPGRADE_DB_USER:'root'},{OPSPILOT_UPGRADE_DB_PASSWORD:''},{OPSPILOT_UPGRADE_SERVER_UUID:''}])assert.throws(()=>configuration({...env,...patch}));
  assert.throws(()=>configuration({OPSPILOT_UPGRADE_DB_PASSWORD:'test-only'}));
});
test('exact nonempty old V39 history and checksums are mandatory',()=>rejects([
  f=>f.result.sqlFixtures[0].requests=[],f=>f.result.sqlFixtures[0].members=[],f=>f.result.sqlFixtures[1].v39MigrationHash='1'.repeat(64),
  f=>f.result.sqlFixtures[1].memberOperationHash='1'.repeat(64),f=>f.result.oldSource='HEAD',f=>f.result.oldJarSha256=f.result.jarSha256,
  f=>f.result.sqlFixtures[0].migration40=true,f=>f.result.sqlFixtures[1].history.pop()
]));
test('legacy backfill missing case or later grant retargeting is rejected',()=>rejects([
  f=>f.result.cases.pop(),f=>f.result.sqlFixtures[1].publicationRows=1,f=>f.result.cases[1].legacyPublication.publication={},
  f=>f.result.cases[2].later.eligibleOriginalRecipientIds.push(3),f=>f.result.cases[2].later.publication.recipients=[]
]));
test('persisted payload sender eligibility and closed-version separation are mandatory',()=>rejects([
  f=>f.result.sqlFixtures[2].publications[0].snapshotJson='{}',f=>f.result.cases[3].revoked.eligibleOriginalRecipientIds.push(3),
  f=>f.result.cases[3].publisherRevoked.eligibleOriginalRecipientIds.push(1),f=>f.result.cases[3].ended.currentRequestVersion=0,
  f=>f.result.cases[3].ended.currentRequestStatus='OPEN',f=>f.result.cases[3].ended.publication.eventVersion=1
]));
test('restart exact business fingerprints and read-only original-key acknowledgement are mandatory',()=>rejects([
  f=>f.result.sqlFixtures[3].publicationHash='1'.repeat(64),f=>f.result.sqlFixtures[3].openOperationHash='1'.repeat(64),
  f=>f.result.cases[5].restarted[0].publication.recipients=[],f=>f.result.httpObservations=f.result.httpObservations.filter(h=>h.status!==401)
]));
test('four distinct owned lifecycles raw logs hashes and graceful pool exits are mandatory',()=>rejects([
  f=>f.result.startedPids[1]=f.result.startedPids[0],f=>f.result.stoppedPids.pop(),f=>f.result.shutdowns[1].graceful=false,
  f=>f.files['jar-1.log']=f.files['jar-1.log'].replace('Shutdown completed.','Shutdown initiated.'),
  f=>f.files['jar-2.log']+='\n ERROR unexplained',f=>f.result.completeJarLogs.pop(),f=>f.result.userFileDatabaseModified=true
]));
test('actual MySQL wrapper requires one executed JUnit same server identity and owned container shutdown',()=>{
  for(const change of [f=>f.xml=f.xml.replace('skipped="0"','skipped="1"'),f=>f.xml=f.xml.replace(mysqlGate.testName,'renamed'),
    f=>f.log+='\n ERROR failure',f=>f.audit.database.serverUuid='other',f=>f.audit.finalJdbcCounts.publications=0,f=>f.audit.ownedContainerStopped=false]){
    const f=fixture(true);change(f);assert.throws(()=>runMysql(f));
  }
});
test('WIP adds immutable V39 publication MySQL job without weakening original jobs or published migrations',()=>{
  const root=path.resolve(__dirname,'../..'),yaml=fs.readFileSync(path.join(root,'.github/workflows/plan-membership-wip.yml'),'utf8');
  for(const value of ['ref: '+oldSource,'opspilot.oncall.publication.upgrade.mysql.enabled=true','-Dtest=MySqlOpenPublicationUpgradeHttpIntegrationTest test',
    'node scripts/verify-oncall-open-publication-upgrade-mysql.cjs','cd target/cp116-v39-source && bash mvnw'])assert.ok(yaml.includes(value));
  assert.doesNotMatch(yaml,/continue-on-error|git reset|git checkout/);
  assert.match(yaml.slice(yaml.indexOf('  publication-upgrade-mysql:'),yaml.indexOf('  membership-ui:')),/fetch-depth: 0/);
  const {execFileSync}=require('node:child_process');
  for(const file of fs.readdirSync(path.join(root,'src/main/resources/db/migration'))){
    const match=file.match(/^V(\d+)__/);if(match&&Number(match[1])<=40){const relative='src/main/resources/db/migration/'+file;
      assert.equal(execFileSync('git',['rev-parse','e13cecf53595e131bbb5ee891e3845934733d6b6:'+relative],{cwd:root,encoding:'utf8'}).trim(),
        execFileSync('git',['hash-object','--path='+relative,relative],{cwd:root,encoding:'utf8'}).trim());}}
});

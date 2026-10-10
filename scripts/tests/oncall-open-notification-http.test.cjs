const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const {verify}=require('../verify-oncall-open-notification-http-evidence.cjs');
function fixture(){const dir=path.resolve(__dirname,'../../docs/assets/v1.7-cp118/final-notification-jar');return{r:JSON.parse(fs.readFileSync(dir+'/result.json','utf8')),read:name=>fs.readFileSync(dir+'/'+name,'utf8')};}
const run=f=>verify(f.r,f.read),rejects=changes=>{for(const change of changes){const f=fixture();change(f);assert.throws(()=>run(f));}};
test('actual owned H2 populated V40 upgrade real JAR retries and manual claim replay seven scopes',()=>{const p=run(fixture());assert.equal(p.cases,7);assert.equal(p.observedAuthenticatedHttpRequests,2);assert.equal(p.actualMysqlWholeJarVerified,false);});
test('missing renamed cases SQL or actual MySQL substitution cannot pass H2 proof',()=>rejects([f=>f.r.cases.pop(),f=>f.r.cases[0].name='invented',f=>f.r.databaseMode='MYSQL_TESTCONTAINER',f=>f.r.sqlFixtures.pop()]));
test('missing original business or checksum preservation rejects a green result',()=>rejects([f=>f.r.oldJarSha256='0'.repeat(64),f=>f.r.sqlFixtures[1].publicationHash='changed',f=>f.r.sqlFixtures[6].v40MigrationHash='changed',f=>f.r.sqlFixtures[2].notificationRows=1]));
test('different payload key observed HTTP credentials or implied acceptance are rejected',()=>rejects([f=>f.r.received[1].payloadSha256='0'.repeat(64),f=>f.r.received[1].key='changed',f=>f.r.received[0].authenticated=false,f=>f.r.cases[4].stillOpen.currentRequestStatus='CLAIMED']));
test('late grant cannot rewrite captured original audience',()=>rejects([f=>f.r.cases[2].later.eligibleOriginalRecipientIds.push(3),f=>f.r.cases[2].later.publication.recipients.push({userId:3}),f=>f.r.cases[2].queued.deliveries[0].attempts=1]));
test('skipped recipient or delivered original acknowledgement cannot be invented',()=>rejects([f=>f.r.cases[5].skipped.deliveries[0].lastErrorCode='HTTP_200',f=>f.r.cases[6].deliveredAfterRestart.deliveries[0].attempts=3,f=>f.r.cases[6].humanClaim.claimedBy=3]));
test('truncated complete JVM logs missing shutdown or live receiver cannot pass',()=>rejects([f=>f.r.shutdowns.pop(),f=>f.r.stoppedPids.pop(),f=>f.r.receiverStopped=false,f=>{const read=f.read;f.read=name=>read(name).replace('Shutdown completed.','Shutdown initiated.');}]));
test('MySQL exhaustion reads original status before setting FAILED and guards both due states',()=>{
  const source=fs.readFileSync(path.resolve(__dirname,'../../src/main/java/org/trigger/opspilot/oncall/OnCallOpenNotifications.java'),'utf8'),sql=source.slice(source.indexOf('int exhausted='),source.indexOf('if(exhausted==1)'));
  assert.ok(sql.indexOf('last_error_code=CASE WHEN status=')<sql.indexOf("status='FAILED'"));assert.match(sql,/status='CLAIMED' AND lease_until<=:now/);assert.match(sql,/status='PENDING' AND next_attempt_at<=:now/);
});

const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const {spawn,spawnSync,execFileSync}=require('node:child_process'),{randomUUID,randomBytes,createHash}=require('node:crypto');
const {requireFreePort,stopProcess,waitForHealth,redact,unexpectedLogLines}=require('./verify-oncall-browser-ci.cjs');
const {assertMigrations}=require('./verify-oncall-open-handoff-http-ci.cjs');
const oldSource='ce5fc51aee215e4eebccfd9e02bccf5271f6fea5';
const caseNames=[
  'old-v38-populates-open-claimed-withdrawn-and-cancelled-original-receipts',
  'v39-upgrade-preserves-all-old-business-fingerprints-and-38-checksums',
  'v39-backfills-only-preexisting-plan-operational-members-once',
  'original-cancelled-claim-and-withdrawal-survive-new-jvm-restart',
  'revoked-member-original-receipt-is-read-only-and-new-responsibility-is-denied',
  'explicit-restoration-permits-new-claim-without-reviving-old-cancelled-coverage',
  'second-v39-restart-preserves-members-and-both-original-receipts'
];
function configuration(env) {
  if(env.OPSPILOT_MEMBER_UPGRADE_MYSQL_OWNER===undefined) {
    assert.ok(!env.OPSPILOT_UPGRADE_JDBC_URL && !env.OPSPILOT_UPGRADE_DB_PASSWORD,'No implicit database switch'); return null;
  }
  assert.equal(env.OPSPILOT_MEMBER_UPGRADE_MYSQL_OWNER,'TESTCONTAINERS');
  const schema=env.OPSPILOT_UPGRADE_SCHEMA,url=env.OPSPILOT_UPGRADE_JDBC_URL;
  assert.match(schema||'',/^opspilot_member_upgrade_[a-f0-9]{12}$/);
  assert.match(url||'',new RegExp('^jdbc:mysql://(?:localhost|127\\.0\\.0\\.1):[0-9]+/'+schema+'\\?[^\\s]+$'));
  assert.match(env.OPSPILOT_UPGRADE_SERVER_UUID||'',/^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/);
  assert.equal(env.OPSPILOT_UPGRADE_DB_USER,'opspilot');assert.ok(env.OPSPILOT_UPGRADE_DB_PASSWORD);
  return {schema,url,user:env.OPSPILOT_UPGRADE_DB_USER,password:env.OPSPILOT_UPGRADE_DB_PASSWORD,serverUuid:env.OPSPILOT_UPGRADE_SERVER_UUID};
}
async function verify(mysql=configuration(process.env)) {
  const root=path.resolve(__dirname,'..'),jar=path.join(root,'target/opspilot-0.1.0-SNAPSHOT.jar'),old=path.join(root,'target/cp110-v38-source/target/opspilot-0.1.0-SNAPSHOT.jar');
  const hash=file=>createHash('sha256').update(fs.readFileSync(file)).digest('hex');
  const provenance=JSON.parse(fs.readFileSync(path.join(root,'target/cp110-v38-source/old-v38-jar-provenance.json'),'utf8'));
  assert.equal(provenance.source,oldSource);assert.equal(provenance.jarSha256,hash(old));assert.notEqual(hash(old),hash(jar));
  for(const port of [9983,9984])await requireFreePort(port);
  const parent=path.join(root,mysql?'target/oncall-plan-membership-upgrade-mysql-it':'target/oncall-plan-membership-upgrade-http-it');fs.mkdirSync(parent,{recursive:true});
  const out=fs.mkdtempSync(path.join(parent,'run-')),database=path.join(out,'database');fs.mkdirSync(database);
  const java=tool=>process.env.JAVA_HOME?path.join(process.env.JAVA_HOME,'bin',tool+(process.platform==='win32'?'.exe':'')):tool;
  const result={status:'RUNNING',oldSource,oldJarSha256:hash(old),jarSha256:hash(jar),cases:[],databaseMode:mysql?'MYSQL_TESTCONTAINER':'H2_OWNED_FILE',
    mysqlSchema:mysql?.schema,startedPids:[],stoppedPids:[],sqlFixtures:[],tokensPersistedToEvidence:false,userFileDatabaseModified:false};
  const children=[],logs=[],fds=[],secret=randomBytes(32).toString('hex'),base='http://127.0.0.1:9983/api/v1';let current,admin,owner,claimant,auditor;
  async function start(file) {
    const log=path.join(out,'jar-'+(children.length+1)+'.log'),fd=fs.openSync(log,'w');logs.push(log);fds.push(fd);
    const child=spawn(java('java'),['-Duser.timezone=UTC','-jar',file,'--server.address=127.0.0.1','--server.port=9983',
      '--management.server.address=127.0.0.1','--management.server.port=9984',
      ...(mysql?['--spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver']:[
        '--spring.datasource.url=jdbc:h2:file:'+path.join(database,'opspilot').replaceAll('\\','/')+';MODE=MySQL;DATABASE_TO_LOWER=TRUE;WRITE_DELAY=0',
        '--spring.datasource.username=sa','--spring.datasource.password=','--spring.datasource.driver-class-name=org.h2.Driver']),
      '--spring.h2.console.enabled=false','--opspilot.ai.enabled=false','--opspilot.agent.recovery.enabled=false',
      '--opspilot.oncall.rotation.enabled=false','--opspilot.oncall.escalation.enabled=false','--opspilot.oncall.swap.notification.enabled=false'],
      {cwd:root,env:{...process.env,JWT_SECRET:secret,...(mysql?{SPRING_DATASOURCE_URL:mysql.url,SPRING_DATASOURCE_USERNAME:mysql.user,SPRING_DATASOURCE_PASSWORD:mysql.password}:{})},stdio:['ignore',fd,fd],windowsHide:true});
    child.once('error',error=>{child.launchError=error;});children.push(child);result.startedPids.push(child.pid);current=child;
    await waitForHealth('http://127.0.0.1:9984/actuator/health',child);
  }
  async function stop(){await stopProcess(current);result.stoppedPids.push(current.pid);}
  async function api(route,token,body,status=200,code) {
    const response=await fetch(base+route,{method:body?'POST':'GET',headers:{...(token?{Authorization:'Bearer '+token}:{}),...(body?{'Content-Type':'application/json'}:{})},
      body:body?JSON.stringify(body):undefined,signal:AbortSignal.timeout(12000)});assert.equal(response.status,status,route);
    const payload=await response.json();if(code)assert.equal(payload.error.code,code);return payload.data;
  }
  async function accounts(){const login=async user=>(await api('/auth/login',null,{username:user,password:'OpsPilot@2026'})).accessToken;
    admin=await login('admin');owner=await login('zhangwei');claimant=await login('lina');auditor=await login('auditor');}
  const route=id=>'/on-call/open-handoffs/'+id,parts=view=>({request:view.request,replacement:view.replacement,operation:view.operation});
  const operation=()=>({version:0,operationKey:randomUUID(),reason:'captured old/new voluntary command🙂'});
  const jdbc=path.join(out,'jdbc-fixture');fs.mkdirSync(jdbc);
  const entries=execFileSync(java('jar'),['tf',jar],{encoding:'utf8',timeout:15000,windowsHide:true}).split(/\r?\n/);
  const drivers=entries.filter(name=>mysql?/^BOOT-INF\/lib\/mysql-connector-j-[\d.]+\.jar$/.test(name):name==='BOOT-INF/lib/h2-2.2.220.jar');assert.equal(drivers.length,1);
  execFileSync(java('jar'),['xf',jar,drivers[0]],{cwd:jdbc,timeout:15000,windowsHide:true});
  execFileSync(java('javac'),['--release','17','-d',jdbc,path.join(root,'scripts/fixtures/OpenHandoffSqlFixture.java')],{timeout:15000,windowsHide:true});
  function sql(){assert.ok(children.every(c=>c.exitCode!==null||c.signalCode!==null),'All owned JARs must stop before SQL snapshot');
    const child=spawnSync(java('java'),['-Duser.timezone=UTC','-cp',[jdbc,path.join(jdbc,drivers[0])].join(path.delimiter),'OpenHandoffSqlFixture',mysql?'MYSQL_TESTCONTAINER':path.join(database,'opspilot'),'SNAPSHOT'],
      {encoding:'utf8',timeout:15000,windowsHide:true,env:{...process.env,OPSPILOT_OPEN_HANDOFF_FIXTURE_ROOT:out,...(mysql?{
        OPSPILOT_UPGRADE_JDBC_URL:mysql.url,OPSPILOT_UPGRADE_DB_USER:mysql.user,OPSPILOT_UPGRADE_DB_PASSWORD:mysql.password,
        OPSPILOT_UPGRADE_SCHEMA:mysql.schema,OPSPILOT_UPGRADE_SERVER_UUID:mysql.serverUuid}:{})}});
    fs.writeFileSync(path.join(out,'sql-'+result.sqlFixtures.length+'.log'),redact((child.stdout||'')+(child.stderr||'')));
    assert.equal(child.status,0);const receipt=JSON.parse(child.stdout.trim());assert.equal(receipt.actualProduct,mysql?'MySQL':'H2');result.sqlFixtures.push(receipt);return receipt;}
  const interrupt=()=>{for(const c of children)if(c.exitCode===null&&c.signalCode===null)c.kill('SIGTERM');};process.once('SIGINT',interrupt);process.once('SIGTERM',interrupt);
  try {
    await start(old);await accounts();const roster=await api('/on-call/roster',admin),plans=roster.schedules.map(s=>s.id);
    const create=async index=>{
      const at=Date.parse(roster.databaseNow+'Z')+(24+index*24)*3600000,iso=n=>new Date(at+n*3600000).toISOString().slice(0,19);
      const source=await api('/on-call/shifts',admin,{scheduleId:plans[0],userId:2,startsAt:iso(0),endsAt:iso(4),override:false,note:'old V38 source '+index});
      const draft={sourceShiftId:source.id,sourceVersion:0,requestKey:randomUUID(),startsAt:source.startsAt,endsAt:source.endsAt,reason:'old V38 publication🙂'};
      return {source,draft,request:await api('/on-call/open-handoffs',owner,draft)};
    };
    const pending=await create(0),claimed=await create(1),withdrawn=await create(2),oldClaim=operation(),oldWithdraw=operation();
    const accepted=await api(route(claimed.request.id)+'/claims',claimant,oldClaim);
    await api('/on-call/shifts/'+accepted.replacement.id+'/cancel',admin,{version:0,reason:'old independent cancellation🙂'});
    const cancelled=parts(await api(route(claimed.request.id)+'/claims',claimant,oldClaim));
    const withdrawnReceipt=parts(await api(route(withdrawn.request.id)+'/withdrawals',owner,oldWithdraw));
    await api('/on-call/schedules/'+plans[0]+'/members',admin,undefined,404);
    await stop();const before=sql();assertMigrations(before,38);assert.deepEqual(before.requests.map(r=>r.status).sort(),['CLAIMED','OPEN','WITHDRAWN']);
    result.cases.push({name:caseNames[0],before,oldMemberApi404:true,threeStates:true,cancelledOriginalReceipt:true});
    await start(jar);await accounts();assert.deepEqual(parts(await api(route(claimed.request.id)+'/claims',claimant,oldClaim)),cancelled);
    assert.deepEqual(parts(await api(route(withdrawn.request.id)+'/withdrawals',owner,oldWithdraw)),withdrawnReceipt);
    await stop();const after=sql();assertMigrations(after,39);
    for(const key of ['shifts','shiftHash','designatedHash','swapHash','swapRevocationHash','onCallAuditHash','openRequestHash','openOperationHash','v38MigrationHash'])assert.equal(after[key],before[key]);
    assert.deepEqual(after.requests,before.requests);
    result.cases.push({name:caseNames[1],after,allOldBusinessRowsPreserved:true,all38MigrationChecksumsPreserved:true});
    const expected=plans.flatMap(scheduleId=>[1,2,3].map(userId=>({scheduleId,userId,active:true,canRespond:true,canManage:userId!==2,version:0,origin:'MIGRATED_GLOBAL_V38'})));
    assert.deepEqual(after.members,expected);result.cases.push({name:caseNames[2],members:after.members,oneTimeBackfill:true,auditorExcluded:true});
    await start(jar);await accounts();assert.deepEqual(parts(await api(route(claimed.request.id)+'/claims',claimant,oldClaim)),cancelled);
    assert.deepEqual(parts(await api(route(withdrawn.request.id)+'/withdrawals',owner,oldWithdraw)),withdrawnReceipt);
    await stop();const restarted=sql();assert.deepEqual(restarted,after);result.cases.push({name:caseNames[3],snapshotUnchanged:true,cancelledNotRevived:true});
    await start(jar);await accounts();const memberRoute='/on-call/schedules/'+plans[0]+'/members';
    const revoke={userId:3,expectedVersion:0,active:false,canRespond:false,canManage:false,operationKey:randomUUID(),reason:'explicit removal🙂'};
    await api(memberRoute,admin,revoke);await api(route(pending.request.id)+'/claims',claimant,operation(),403,'ONCALL_PLAN_RESPONSE_FORBIDDEN');
    await api('/on-call/shifts/'+pending.source.id+'/cancel',claimant,{version:0,reason:'not a plan manager'},403,'ONCALL_PLAN_MANAGEMENT_FORBIDDEN');
    assert.deepEqual(parts(await api(route(claimed.request.id)+'/claims',claimant,oldClaim)),cancelled);
    await api(route(claimed.request.id)+'/claims',claimant,operation(),409,'ONCALL_OPEN_HANDOFF_VERSION_CONFLICT');
    assert.equal((await api(route(pending.request.id)+'/coverage',auditor)).replacement,null);
    result.cases.push({name:caseNames[4],actualHttpStatuses:[403,403,200,409],oldReceiptUnchanged:true,noNewResponsibility:true});
    await api(memberRoute,admin,{...revoke,expectedVersion:1,active:true,canRespond:true,canManage:true,operationKey:randomUUID(),reason:'explicit restoration🙂'});
    const newClaim=operation(),newReceipt=parts(await api(route(pending.request.id)+'/claims',claimant,newClaim));
    assert.deepEqual(parts(await api(route(claimed.request.id)+'/claims',claimant,oldClaim)),cancelled);
    assert.ok(cancelled.replacement.cancelledAt);result.cases.push({name:caseNames[5],newClaimSucceeded:true,oldCancelledCoverageUnchanged:true});
    await stop();const committed=sql();await start(jar);await accounts();
    assert.deepEqual(parts(await api(route(pending.request.id)+'/claims',claimant,newClaim)),newReceipt);
    assert.deepEqual(parts(await api(route(claimed.request.id)+'/claims',claimant,oldClaim)),cancelled);
    await stop();const final=sql();assert.deepEqual(final,committed);assertMigrations(final,39);
    assert.deepEqual(final.requests.map(r=>r.status).sort(),['CLAIMED','CLAIMED','WITHDRAWN']);
    result.cases.push({name:caseNames[6],snapshotUnchanged:true,originalReceiptsUnchanged:true,final});
    assert.deepEqual(result.cases.map(r=>r.name),caseNames);result.status='PASS';
  } catch(error) {result.status='FAIL';result.failure=redact(error.stack);throw error;}
  finally {
    process.off('SIGINT',interrupt);process.off('SIGTERM',interrupt);
    for(const c of [...children].reverse())if(!result.stoppedPids.includes(c.pid)){await stopProcess(c);result.stoppedPids.push(c.pid);}
    for(const fd of fds)fs.closeSync(fd);
    result.unexpectedJarErrors=0;result.completeJarLogs=logs.map(file=>{const text=redact(fs.readFileSync(file,'utf8'));fs.writeFileSync(file,text);result.unexpectedJarErrors+=unexpectedLogLines(text);return{file:path.relative(root,file),sha256:hash(file)};});
    for(const port of [9983,9984])await requireFreePort(port);result.ownedProcessesStopped=true;
    if(result.unexpectedJarErrors){result.status='FAIL';result.failure='Unexpected complete JAR log error';}
    fs.writeFileSync(path.join(out,'result.json'),JSON.stringify(result,null,2));console.log(JSON.stringify({...result,evidenceDirectory:path.relative(root,out)}));
    if(result.status==='FAIL')throw Error(result.failure);
  }
}
module.exports={configuration,verify,oldSource,caseNames};
if(require.main===module)verify().catch(e=>{console.error(redact(e.message));process.exitCode=1;});

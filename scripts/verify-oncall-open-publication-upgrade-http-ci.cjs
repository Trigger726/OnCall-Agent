const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const {spawn,spawnSync,execFileSync}=require('node:child_process'),{randomUUID,randomBytes,createHash}=require('node:crypto');
const {setTimeout:delay}=require('node:timers/promises');
const {requireFreePort,stopProcess,waitForHealth,redact,unexpectedLogLines}=require('./verify-oncall-browser-ci.cjs');
const {assertMigrations}=require('./verify-oncall-open-handoff-http-ci.cjs');
const oldSource='915e3f29557bd8019c79793b12f73f990215ad71';
const caseNames=['old-v39-nonempty-requests-and-member-operations',
  'v40-preserves-all-old-business-rows-and-39-checksums-with-no-backfill',
  'real-http-freezes-original-audience-and-does-not-retarget-later-grants',
  'real-http-rechecks-revocation-and-closure-without-changing-publication',
  'offline-sql-matches-http-publication-payloads',
  'new-jar-restart-and-original-key-replay-preserve-all-business-hashes'];
const preservedKeys=['shiftHash','designatedHash','swapHash','swapRevocationHash','onCallAuditHash',
  'openRequestHash','openOperationHash','memberHash','memberOperationHash','v39MigrationHash'];
const hash=file=>createHash('sha256').update(fs.readFileSync(file)).digest('hex');
function configuration(env) {
  if(env.OPSPILOT_PUBLICATION_UPGRADE_MYSQL_OWNER===undefined){
    assert.ok(!env.OPSPILOT_UPGRADE_JDBC_URL&&!env.OPSPILOT_UPGRADE_DB_PASSWORD,'No implicit database switch');return null;
  }
  assert.equal(env.OPSPILOT_PUBLICATION_UPGRADE_MYSQL_OWNER,'TESTCONTAINERS');
  const schema=env.OPSPILOT_UPGRADE_SCHEMA,url=env.OPSPILOT_UPGRADE_JDBC_URL;
  assert.match(schema||'',/^opspilot_publication_upgrade_[a-f0-9]{12}$/);
  assert.match(url||'',new RegExp('^jdbc:mysql://(?:localhost|127\\.0\\.0\\.1):[0-9]+/'+schema+'\\?[^\\s]+$'));
  assert.match(env.OPSPILOT_UPGRADE_SERVER_UUID||'',/^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/);
  assert.equal(env.OPSPILOT_UPGRADE_DB_USER,'opspilot');assert.ok(env.OPSPILOT_UPGRADE_DB_PASSWORD);
  return {schema,url,user:env.OPSPILOT_UPGRADE_DB_USER,password:env.OPSPILOT_UPGRADE_DB_PASSWORD,serverUuid:env.OPSPILOT_UPGRADE_SERVER_UUID};
}
function baseline(root,mysql) {
  const checkout=path.join(root,'target/cp116-v39-source'),provenance=path.join(checkout,'old-v39-jar-provenance.json');
  if(fs.existsSync(provenance)) {
    const saved=JSON.parse(fs.readFileSync(provenance,'utf8')),jar=path.join(checkout,'target/opspilot-0.1.0-SNAPSHOT.jar');
    assert.equal(saved.source,oldSource);assert.equal(saved.jarSha256,hash(jar));
    assert.equal(execFileSync('git',['-C',checkout,'rev-parse','HEAD'],{encoding:'utf8'}).trim(),oldSource);return jar;
  }
  assert.equal(mysql,null,'Actual MySQL requires rebuilt immutable old Git checkout');
  const jar=path.join(root,'target/cp115-before/opspilot-cp114.jar');
  assert.equal(hash(jar),'1a1aed70f34ff8ca3e97e66e47ac81fd2fccf8bc4365fcb8a698103224e9fc9b');
  assert.equal(JSON.parse(fs.readFileSync(path.join(root,'docs/assets/v1.7-cp114/local-proof.json'),'utf8')).newJarSha256,hash(jar));return jar;
}
async function verify(mysql=configuration(process.env)) {
  const root=path.resolve(__dirname,'..'),jar=path.join(root,'target/opspilot-0.1.0-SNAPSHOT.jar'),old=baseline(root,mysql);
  assert.notEqual(hash(old),hash(jar));for(const port of [9961,9962])await requireFreePort(port);
  const parent=path.join(root,mysql?'target/oncall-open-publication-upgrade-mysql-it':'target/oncall-open-publication-upgrade-http-it');fs.mkdirSync(parent,{recursive:true});
  const out=fs.mkdtempSync(path.join(parent,'run-')),database=path.join(out,'database');fs.mkdirSync(database);
  const java=tool=>process.env.JAVA_HOME?path.join(process.env.JAVA_HOME,'bin',tool+(process.platform==='win32'?'.exe':'')):tool;
  const result={status:'RUNNING',oldSource,oldJarSha256:hash(old),jarSha256:hash(jar),cases:[],
    databaseMode:mysql?'MYSQL_TESTCONTAINER':'H2_OWNED_FILE',mysqlSchema:mysql?.schema,startedPids:[],stoppedPids:[],shutdowns:[],sqlFixtures:[],
    httpObservations:[],tokensPersistedToEvidence:false,userFileDatabaseModified:false,actualDeliveryVerified:false,remindersVerified:false};
  const children=[],logs=[],fds=[],secret=randomBytes(32).toString('hex'),base='http://127.0.0.1:9961/api/v1';let current,admin,owner,auditor;
  async function start(file) {
    const log=path.join(out,'jar-'+(children.length+1)+'.log'),fd=fs.openSync(log,'w');logs.push(log);fds.push(fd);
    const child=spawn(java('java'),['-Duser.timezone=UTC','-jar',file,'--server.address=127.0.0.1','--server.port=9961',
      '--management.server.address=127.0.0.1','--management.server.port=9962','--management.endpoint.shutdown.enabled=true',
      '--management.endpoints.web.exposure.include=health,shutdown',
      ...(mysql?['--spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver']:[
        '--spring.datasource.url=jdbc:h2:file:'+path.join(database,'opspilot').replaceAll('\\','/')+';MODE=MySQL;DATABASE_TO_LOWER=TRUE;WRITE_DELAY=0',
        '--spring.datasource.username=sa','--spring.datasource.password=','--spring.datasource.driver-class-name=org.h2.Driver']),
      '--spring.h2.console.enabled=false','--opspilot.ai.enabled=false','--opspilot.agent.recovery.enabled=false',
      '--opspilot.oncall.rotation.enabled=false','--opspilot.oncall.escalation.enabled=false','--opspilot.oncall.swap.notification.enabled=false','--opspilot.oncall.open.notification.enabled=false'],
      {cwd:root,env:{...process.env,JWT_SECRET:secret,...(mysql?{SPRING_DATASOURCE_URL:mysql.url,SPRING_DATASOURCE_USERNAME:mysql.user,SPRING_DATASOURCE_PASSWORD:mysql.password}:{})},stdio:['ignore',fd,fd],windowsHide:true});
    child.once('error',error=>{child.launchError=error;});children.push(child);result.startedPids.push(child.pid);current=child;
    await waitForHealth('http://127.0.0.1:9962/actuator/health',child);
  }
  async function stop() {
    const child=current,response=await fetch('http://127.0.0.1:9962/actuator/shutdown',{method:'POST',headers:{Authorization:'Bearer '+admin},signal:AbortSignal.timeout(12000)});
    assert.equal(response.status,200,'Authenticated own-JVM graceful shutdown');
    const until=Date.now()+15000;while(child.exitCode===null&&child.signalCode===null&&Date.now()<until)await delay(100);
    assert.ok(child.exitCode!==null||child.signalCode!==null,'Owned JVM graceful shutdown must finish');
    result.stoppedPids.push(child.pid);result.shutdowns.push({pid:child.pid,httpStatus:response.status,graceful:true});
  }
  async function api(route,token,body,status=200) {
    const response=await fetch(base+route,{method:body?'POST':'GET',headers:{...(token?{Authorization:'Bearer '+token}:{}),...(body?{'Content-Type':'application/json'}:{})},body:body?JSON.stringify(body):undefined,signal:AbortSignal.timeout(12000)});
    const payload=await response.json();result.httpObservations.push({method:body?'POST':'GET',route,status:response.status,...(payload.error?{errorCode:payload.error.code}:{})});
    assert.equal(response.status,status,route+(payload.error?' '+payload.error.code:''));return payload.data;
  }
  async function accounts(){const login=async user=>(await api('/auth/login',null,{username:user,password:'OpsPilot@2026'})).accessToken;
    admin=await login('admin');owner=await login('zhangwei');auditor=await login('auditor');}
  const route=id=>'/on-call/open-handoffs/'+id,publication=id=>route(id)+'/publication';
  const receipt=view=>({request:view.request,replacement:view.replacement,operation:view.operation});
  const operation=()=>({version:0,operationKey:randomUUID(),reason:'publication upgrade command🙂'});
  const jdbc=path.join(out,'jdbc-fixture');fs.mkdirSync(jdbc);
  const entries=execFileSync(java('jar'),['tf',jar],{encoding:'utf8',timeout:15000,windowsHide:true}).split(/\r?\n/);
  const drivers=entries.filter(name=>mysql?/^BOOT-INF\/lib\/mysql-connector-j-[\d.]+\.jar$/.test(name):name==='BOOT-INF/lib/h2-2.2.220.jar');assert.equal(drivers.length,1);
  execFileSync(java('jar'),['xf',jar,drivers[0]],{cwd:jdbc,timeout:15000,windowsHide:true});
  execFileSync(java('javac'),['--release','17','-d',jdbc,path.join(root,'scripts/fixtures/OpenHandoffSqlFixture.java')],{timeout:15000,windowsHide:true});
  function sql() {
    assert.ok(children.every(c=>c.exitCode!==null||c.signalCode!==null),'All owned JARs must stop before SQL snapshot');
    const child=spawnSync(java('java'),['-Duser.timezone=UTC','-cp',[jdbc,path.join(jdbc,drivers[0])].join(path.delimiter),'OpenHandoffSqlFixture',mysql?'MYSQL_TESTCONTAINER':path.join(database,'opspilot'),'SNAPSHOT'],
      {encoding:'utf8',timeout:15000,windowsHide:true,env:{...process.env,OPSPILOT_OPEN_HANDOFF_FIXTURE_ROOT:out,...(mysql?{
        OPSPILOT_UPGRADE_JDBC_URL:mysql.url,OPSPILOT_UPGRADE_DB_USER:mysql.user,OPSPILOT_UPGRADE_DB_PASSWORD:mysql.password,
        OPSPILOT_UPGRADE_SCHEMA:mysql.schema,OPSPILOT_UPGRADE_SERVER_UUID:mysql.serverUuid}:{})}});
    fs.writeFileSync(path.join(out,'sql-'+result.sqlFixtures.length+'.log'),redact((child.stdout||'')+(child.stderr||'')));
    assert.equal(child.status,0);const receipt=JSON.parse(child.stdout.trim());assert.equal(receipt.actualProduct,mysql?'MySQL':'H2');result.sqlFixtures.push(receipt);return receipt;
  }
  const interrupt=()=>{for(const c of children)if(c.exitCode===null&&c.signalCode===null)c.kill('SIGTERM');};process.once('SIGINT',interrupt);process.once('SIGTERM',interrupt);
  try {
    await start(old);await accounts();const roster=await api('/on-call/roster',admin),plan=roster.schedules[0].id;
    const memberRoute='/on-call/schedules/'+plan+'/members';
    const change=async(userId,expectedVersion,canRespond)=>api(memberRoute,admin,{userId,expectedVersion,active:canRespond||userId!==2,canRespond,canManage:userId!==2,operationKey:randomUUID(),reason:'explicit audience change🙂'});
    const create=async index=>{
      const at=Date.parse(roster.databaseNow+'Z')+(48+index*24)*3600000,iso=n=>new Date(at+n*3600000).toISOString().slice(0,19);
      const source=await api('/on-call/shifts',admin,{scheduleId:plan,userId:2,startsAt:iso(0),endsAt:iso(4),override:false,note:'publication upgrade source '+index});
      const draft={sourceShiftId:source.id,sourceVersion:0,requestKey:randomUUID(),startsAt:source.startsAt,endsAt:source.endsAt,reason:'publication🙂'};
      return {source,draft,request:await api('/on-call/open-handoffs',owner,draft)};
    };
    await change(3,0,false);const legacy=await create(0),closed=await create(1),withdraw=operation();
    const oldClosed=receipt(await api(route(closed.request.id)+'/withdrawals',owner,withdraw));
    await api(publication(legacy.request.id),auditor,undefined,404);await stop();const before=sql();assertMigrations(before,39);
    assert.equal(before.requests.length,2);assert.deepEqual(before.requests.map(r=>r.status).sort(),['OPEN','WITHDRAWN']);assert.equal(before.publicationRows,0);
    result.cases.push({name:caseNames[0],oldPublicationHttpStatus:404,legacyId:legacy.request.id,closedId:closed.request.id,before});
    await start(jar);await accounts();
    const missing=await api(publication(legacy.request.id),auditor);
    assert.equal(missing.publicationSnapshotAvailable,false);assert.equal(missing.publication,null);assert.deepEqual(missing.eligibleOriginalRecipientIds,[]);
    assert.equal(missing.deliveryImplemented,true);assert.deepEqual(await api('/on-call/open-handoffs',owner,legacy.draft),legacy.request);
    assert.deepEqual(receipt(await api(route(closed.request.id)+'/withdrawals',owner,withdraw)),oldClosed);
    await stop();const upgraded=sql();assertMigrations(upgraded,41);for(const key of preservedKeys)assert.equal(upgraded[key],before[key],key);
    assert.deepEqual(upgraded.requests,before.requests);assert.deepEqual(upgraded.members,before.members);assert.equal(upgraded.publicationRows,0);
    result.cases.push({name:caseNames[1],legacyPublication:missing,upgraded});
    await start(jar);await accounts();const first=await create(2),firstView=await api(publication(first.request.id),auditor);
    assert.deepEqual(firstView.publication.recipients.map(r=>r.userId),[1]);assert.deepEqual(firstView.eligibleOriginalRecipientIds,[1]);
    await change(3,1,true);const later=await api(publication(first.request.id),auditor);
    assert.deepEqual(later.publication,firstView.publication);assert.deepEqual(later.eligibleOriginalRecipientIds,[1]);
    assert.deepEqual(await api('/on-call/open-handoffs',owner,first.draft),first.request);
    const second=await create(3),secondView=await api(publication(second.request.id),auditor);
    assert.deepEqual(secondView.publication.recipients.map(r=>r.userId),[1,3]);assert.deepEqual(secondView.eligibleOriginalRecipientIds,[1,3]);
    await api(publication(first.request.id),null,undefined,401);await api(publication(999999),auditor,undefined,404);
    assert.ok(!/requestKey|operationKey|password|accessToken|Authorization|reason/.test(JSON.stringify(firstView)));
    result.cases.push({name:caseNames[2],first:firstView,later,second:secondView});
    await change(3,2,false);const revoked=await api(publication(second.request.id),auditor);assert.deepEqual(revoked.eligibleOriginalRecipientIds,[1]);assert.deepEqual(revoked.publication,secondView.publication);
    await change(2,0,false);const publisherRevoked=await api(publication(second.request.id),auditor);assert.deepEqual(publisherRevoked.eligibleOriginalRecipientIds,[]);
    const newWithdraw=operation();await api(route(second.request.id)+'/withdrawals',owner,newWithdraw);
    const ended=await api(publication(second.request.id),auditor);assert.equal(ended.currentRequestStatus,'WITHDRAWN');assert.equal(ended.currentRequestVersion,1);
    assert.deepEqual(ended.publication,secondView.publication);assert.deepEqual(ended.eligibleOriginalRecipientIds,[]);
    await api('/on-call/open-handoffs',owner,second.draft);assert.deepEqual((await api(publication(second.request.id),auditor)).publication,secondView.publication);
    result.cases.push({name:caseNames[3],revoked,publisherRevoked,ended});
    await stop();const committed=sql();assert.equal(committed.publicationRows,2);
    for(const view of [firstView,secondView]) {
      const stored=committed.publications.find(p=>p.handoffId===view.publication.handoffId);assert.ok(stored);assert.equal(stored.eventVersion,0);
      assert.deepEqual(JSON.parse(stored.snapshotJson),view.publication);
    }
    result.cases.push({name:caseNames[4],committed});
    await start(jar);await accounts();const restarted=[];
    for(const entry of [first,second]){await api('/on-call/open-handoffs',owner,entry.draft);restarted.push(await api(publication(entry.request.id),auditor));}
    assert.deepEqual(restarted.map(v=>v.publication),[firstView.publication,secondView.publication]);assert.deepEqual(restarted.map(v=>v.eligibleOriginalRecipientIds),[[],[]]);
    await stop();const final=sql();assert.deepEqual(final,committed);assert.equal(final.v39MigrationHash,before.v39MigrationHash);assertMigrations(final,41);
    result.cases.push({name:caseNames[5],restarted,final});assert.deepEqual(result.cases.map(c=>c.name),caseNames);result.status='PASS';
  } catch(error){result.status='FAIL';result.failure=redact(error.stack);throw error;}
  finally {
    process.off('SIGINT',interrupt);process.off('SIGTERM',interrupt);
    for(const c of [...children].reverse())if(!result.stoppedPids.includes(c.pid)){await stopProcess(c);result.stoppedPids.push(c.pid);}
    for(const fd of fds)fs.closeSync(fd);
    result.unexpectedJarErrors=0;result.completeJarLogs=logs.map(file=>{const content=redact(fs.readFileSync(file,'utf8'));fs.writeFileSync(file,content);result.unexpectedJarErrors+=unexpectedLogLines(content);return{file:path.relative(root,file).replaceAll('\\','/'),sha256:hash(file)};});
    for(const port of [9961,9962])await requireFreePort(port);result.ownedProcessesStopped=true;
    if(result.unexpectedJarErrors){result.status='FAIL';result.failure='Unexpected complete JAR log error';}
    fs.writeFileSync(path.join(out,'result.json'),JSON.stringify(result,null,2));console.log(JSON.stringify({...result,evidenceDirectory:path.relative(root,out).replaceAll('\\','/')}));
    if(result.status==='FAIL')throw Error(result.failure);
  }
}
module.exports={verify,configuration,baseline,oldSource,caseNames,preservedKeys,hash};
if(require.main===module)verify().catch(e=>{console.error(redact(e.message));process.exitCode=1;});

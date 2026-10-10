const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),http=require('node:http');
const {spawn,spawnSync,execFileSync}=require('node:child_process');
const {randomUUID,randomBytes,createHash}=require('node:crypto');
const {requireFreePort,stopProcess,waitForHealth,redact,unexpectedLogLines}=require('./verify-oncall-browser-ci.cjs');
const caseNames=[
  'real-http-consent-explicit-versions-and-keyed-publication',
  'real-committed-claim-response-loss-and-original-manual-ack',
  'same-original-receipt-after-jvm-restart-and-independent-cancel',
  'different-http-claimants-one-winner-with-one-override',
  'owner-withdrawal-original-receipt-and-reject-other-claim',
  'changed-source-blocks-claim-without-new-responsibility',
  'ongoing-subwindow-preserves-past-and-future-owner',
  'current-role-loss-blocks-original-claim-without-writes'
];
function configuration(env){const comparison=env.OPSPILOT_OPEN_HANDOFF_UPGRADE==='1';
  assert.ok(!comparison||!env.CI,'Archived comparison must not replace fresh own-source CI');return{comparison};}
function assertSqlRows(actual,expected){assert.equal(actual.length,expected.length);
  for(const row of expected)assert.deepEqual(actual.find(item=>item.id===row.id),row);}
function assertMigrations(snapshot,maximum){const versioned=snapshot.history.filter(row=>row.version!==null);
  assert.ok([37,38,39,40,41].includes(maximum),'Require explicitly supported migration boundary');
  assert.ok(snapshot.history.every(row=>row.success===true));assert.equal(snapshot.migrationCount,maximum);
  assert.deepEqual(versioned.map(row=>row.version),Array.from({length:maximum},(_,i)=>String(i+1)));
  for(const row of versioned)assert.equal(row.type,['29','33'].includes(row.version)?'JDBC':'SQL');
  const nonversioned=snapshot.actualProduct==='MySQL'?[]:[{version:null,type:'TABLE',success:true}];
  assert.deepEqual(snapshot.history.filter(row=>row.version===null),nonversioned);
  assert.equal(snapshot.successfulHistoryRows,maximum+nonversioned.length);assert.equal(snapshot.migration38,maximum>=38);
  assert.equal(snapshot.migration39,maximum>=39);assert.equal(snapshot.migration40,maximum>=40);
  if(maximum===41)assert.equal(snapshot.migration41,true);}
async function verify(mysql){
  // MySQL is supplied only by the separate Testcontainers entry point. Default CI remains fresh H2.
  const {comparison}=mysql?{comparison:true}:configuration(process.env),root=path.resolve(__dirname,'..');
  const jar=path.join(root,'target/opspilot-0.1.0-SNAPSHOT.jar'),old=path.join(root,mysql?'target/cp104-v37-source/target/opspilot-0.1.0-SNAPSHOT.jar':'target/cp97-before/opspilot-cp96.jar');
  assert.ok(fs.existsSync(jar));if(comparison)assert.ok(fs.existsSync(old));
  for(const port of [9981,9982])await requireFreePort(port);
  const parent=path.join(root,mysql?'target/oncall-open-handoff-upgrade-mysql-it':'target/oncall-open-handoff-http-it');fs.mkdirSync(parent,{recursive:true});
  const out=fs.mkdtempSync(path.join(parent,'run-')),database=path.join(out,'database');fs.mkdirSync(database);
  const java=tool=>process.env.JAVA_HOME?path.join(process.env.JAVA_HOME,'bin',tool+(process.platform==='win32'?'.exe':'')):tool;
  const hash=file=>createHash('sha256').update(fs.readFileSync(file)).digest('hex');
  const result={status:'RUNNING',comparison,cases:[],jarSha256:hash(jar),startedPids:[],stoppedPids:[],sqlFixtures:[],sqlExpectations:[],
    databaseMode:mysql?'MYSQL_TESTCONTAINER':'H2_OWNED_FILE',mysqlSchema:mysql?.schema,
    transport:mysql?'real HTTP to old/new production JARs and owned MySQL Testcontainer':'real HTTP to independent production JAR and owned H2 file database',tokensPersistedToEvidence:false,userFileDatabaseModified:false};
  if(mysql){const provenance=JSON.parse(fs.readFileSync(path.join(root,'target/cp104-v37-source/old-jar-provenance.json'),'utf8'));
    assert.equal(provenance.source,'a1262f6765b46416a7fdc192642cf631465f4caf');assert.equal(provenance.jarSha256,hash(old));result.oldSource=provenance.source;}
  const children=[],logs=[],fds=[],secret=randomBytes(32).toString('hex'),base='http://127.0.0.1:9981/api/v1',endpoint='/on-call/open-handoffs';
  let current,admin,owner,claimant,auditor,offset=48;
  async function start(file){const log=path.join(out,'jar-'+(children.length+1)+'.log'),fd=fs.openSync(log,'w');fds.push(fd);logs.push(log);
    const child=spawn(java('java'),['-Duser.timezone=UTC','-jar',file,'--server.address=127.0.0.1','--server.port=9981',
      '--management.server.address=127.0.0.1','--management.server.port=9982',
      ...(mysql?['--spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver']:[
      '--spring.datasource.url=jdbc:h2:file:'+path.join(database,'opspilot').replaceAll('\\','/')+';MODE=MySQL;DATABASE_TO_LOWER=TRUE;WRITE_DELAY=0',
      '--spring.datasource.username=sa','--spring.datasource.password=','--spring.datasource.driver-class-name=org.h2.Driver']),
      '--spring.h2.console.enabled=false','--opspilot.ai.enabled=false','--opspilot.agent.recovery.enabled=false',
      '--opspilot.oncall.rotation.enabled=false','--opspilot.oncall.escalation.enabled=false','--opspilot.oncall.swap.notification.enabled=false','--opspilot.oncall.open.notification.enabled=false'],
      {cwd:root,env:{...process.env,JWT_SECRET:secret,...(mysql?{SPRING_DATASOURCE_URL:mysql.url,SPRING_DATASOURCE_USERNAME:mysql.user,SPRING_DATASOURCE_PASSWORD:mysql.password}:{})},stdio:['ignore',fd,fd],windowsHide:true});
    child.once('error',error=>{child.launchError=error;});children.push(child);result.startedPids.push(child.pid);
    await waitForHealth('http://127.0.0.1:9982/actuator/health',child);return child;}
  async function stop(){await stopProcess(current);result.stoppedPids.push(current.pid);}
  async function request(route,token,body){const response=await fetch(base+route,{method:body?'POST':'GET',
    headers:{...(token?{Authorization:'Bearer '+token}:{}),...(body?{'Content-Type':'application/json'}:{})},
    body:body?JSON.stringify(body):undefined,signal:AbortSignal.timeout(12000)});return{status:response.status,payload:await response.json()};}
  async function api(route,token,body,expected=200,code){const r=await request(route,token,body);assert.equal(r.status,expected,route);
    if(code)assert.equal(r.payload.error.code,code);return r.payload.data;}
  async function accounts(){const login=async name=>(await api('/auth/login',null,{username:name,password:'OpsPilot@2026'})).accessToken;
    admin=await login('admin');owner=await login('zhangwei');claimant=await login('lina');auditor=await login('auditor');}
  async function source(label,ongoing=false){const roster=await api('/on-call/roster',admin);
    const now=Date.parse(roster.databaseNow+'Z'),start=ongoing?now-3600000:now+offset*3600000;offset+=48;
    const iso=hours=>new Date(start+hours*3600000).toISOString().slice(0,19);
    return api('/on-call/shifts',admin,{scheduleId:roster.schedules[0].id,userId:2,startsAt:iso(0),endsAt:iso(4),override:false,note:label});}
  const publication=s=>({sourceShiftId:s.id,sourceVersion:s.version,requestKey:randomUUID(),startsAt:s.startsAt,endsAt:s.endsAt,reason:'本人开放接班🙂'});
  const operation=()=>({version:0,operationKey:randomUUID(),reason:'本人主动认领🙂'});
  const route=id=>endpoint+'/'+id;
  const facts=id=>api(route(id)+'/coverage',auditor);
  const parts=view=>({request:view.request,replacement:view.replacement,operation:view.operation});
  const window=s=>'?'+new URLSearchParams({scheduleId:String(s.scheduleId),from:s.startsAt,to:s.endsAt});
  async function responsibility(s,user){const coverage=await api('/on-call/coverage'+window(s),auditor);
    assert.ok(coverage.segments.length);assert.equal(coverage.gapSeconds,0);assert.ok(coverage.segments.every(row=>row.userId===user));}
  async function onCallAudits(id){return(await api('/audit-logs?limit=500',admin)).filter(a=>a.targetType==='ONCALL_OPEN_HANDOFF'&&a.targetId===String(id));}
  const expectation=view=>({id:view.request.id,sourceShiftId:view.request.sourceShiftId,status:view.request.status,version:view.request.version,
    claimedBy:view.request.claimedBy,replacementShiftId:view.request.replacementShiftId,operations:view.operation?1:0,audits:view.operation?2:1,overrides:view.replacement?1:0});
  const jdbc=path.join(out,'jdbc-fixture');fs.mkdirSync(jdbc);
  const entries=execFileSync(java('jar'),['tf',jar],{encoding:'utf8',timeout:15000,windowsHide:true}).split(/\r?\n/);
  const drivers=entries.filter(name=>mysql?/^BOOT-INF\/lib\/mysql-connector-j-[\d.]+\.jar$/.test(name):name==='BOOT-INF/lib/h2-2.2.220.jar');
  assert.equal(drivers.length,1);const driver=drivers[0];
  execFileSync(java('jar'),['xf',jar,driver],{cwd:jdbc,timeout:15000,windowsHide:true});
  execFileSync(java('javac'),['--release','17','-d',jdbc,path.join(root,'scripts/fixtures/OpenHandoffSqlFixture.java')],{timeout:15000,windowsHide:true});
  function sql(mode){assert.ok(children.every(c=>c.exitCode!==null||c.signalCode!==null),'SQL fixture runs only when every owned JAR is stopped');
    const child=spawnSync(java('java'),['-Duser.timezone=UTC','-cp',[jdbc,path.join(jdbc,driver)].join(path.delimiter),'OpenHandoffSqlFixture',mysql?'MYSQL_TESTCONTAINER':path.join(database,'opspilot'),mode],
      {encoding:'utf8',timeout:15000,windowsHide:true,env:{...process.env,OPSPILOT_OPEN_HANDOFF_FIXTURE_ROOT:out,...(mysql?{
        OPSPILOT_UPGRADE_JDBC_URL:mysql.url,OPSPILOT_UPGRADE_DB_USER:mysql.user,OPSPILOT_UPGRADE_DB_PASSWORD:mysql.password,
        OPSPILOT_UPGRADE_SCHEMA:mysql.schema,OPSPILOT_UPGRADE_SERVER_UUID:mysql.serverUuid}:{})}});
    fs.writeFileSync(path.join(out,'sql-'+result.sqlFixtures.length+'.log'),redact((child.stdout||'')+(child.stderr||'')));
    assert.equal(child.status,0);const receipt=JSON.parse(child.stdout.trim());assert.equal(receipt.actualProduct,mysql?'MySQL':'H2');
    if(mysql){assert.match(receipt.actualVersion,/^8\.4\./);assert.equal(receipt.schema,mysql.schema);assert.equal(receipt.ownerConfirmed,true);}
    result.sqlFixtures.push({mode,pid:child.pid,exitCode:child.status,receipt});return receipt;}
  function loseBody(id,body){return new Promise((resolve,reject)=>{const req=http.request(base+route(id)+'/claims',
    {method:'POST',headers:{Authorization:'Bearer '+claimant,'Content-Type':'application/json'}},res=>{const status=res.statusCode;
      res.destroy();req.destroy();const actualSocketDestroyed=Boolean(res.destroyed&&req.destroyed&&req.socket?.destroyed);
      if(!actualSocketDestroyed){reject(new Error('Owned response-loss socket was not actually destroyed'));return;}
      resolve({status,bodyRead:false,actualSocketDestroyed});});
    req.on('error',reject);req.setTimeout(12000,()=>req.destroy(new Error('Owned response-loss request timed out')));req.end(JSON.stringify(body));});}
  const interrupt=()=>{for(const child of children)if(child.exitCode===null&&child.signalCode===null)child.kill('SIGTERM');};
  process.once('SIGINT',interrupt);process.once('SIGTERM',interrupt);
  try{
    current=await start(comparison?old:jar);await accounts();let mainSource=await source('CP98开放认领源');
    const legacySource=await source('CP98旧指定接班历史'),legacyDraft={...publication(legacySource),targetUserId:3};
    const legacyPending=await api('/on-call/handoffs',owner,legacyDraft);
    const legacyDecision={version:0,status:'ACCEPTED',reason:'旧指定目标本人确认'};
    const legacy=await api('/on-call/handoffs/'+legacyPending.id+'/decisions',claimant,legacyDecision);
    await api('/on-call/shifts/'+legacy.replacementShiftId+'/cancel',admin,{version:0,reason:'保留旧指定接班已取消历史'});
    const legacyRows=(await api('/on-call/roster'+window(legacySource),auditor)).shifts;await responsibility(legacySource,2);
    if(comparison){result.oldJarSha256=hash(old);assert.notEqual(result.oldJarSha256,result.jarSha256);
      // Populate old bilateral receipts as well; an empty swap-table hash is insufficient upgrade evidence.
      const first=await source('旧双方互换第一段🙂'),roster=await api('/on-call/roster',admin);
      const second=await api('/on-call/shifts',admin,{scheduleId:roster.schedules[1].id,userId:3,startsAt:first.startsAt,endsAt:first.endsAt,override:false,note:'旧双方互换第二段🙂'});
      const swapDraft={firstShiftId:first.id,firstVersion:0,secondShiftId:second.id,secondVersion:0,requestKey:randomUUID(),reason:'旧双方共同确认🙂'};
      const pendingSwap=await api('/on-call/swaps',owner,swapDraft),swapDecision={version:0,status:'ACCEPTED',reason:'旧接班人明确接受🙂'};
      const swap=await api('/on-call/swaps/'+pendingSwap.id+'/decisions',claimant,swapDecision);
      const revocationCommand={swapVersion:swap.version,firstReplacementVersion:0,secondReplacementVersion:0,operationKey:randomUUID(),reason:'旧双方覆盖成对撤销🙂'};
      const swapReceipt=await api('/on-call/swaps/'+swap.id+'/coverage/revoke',admin,revocationCommand);
      await responsibility(first,2);await responsibility(second,3);
      await api(endpoint,owner,undefined,404);await api(endpoint,owner,publication(mainSource),404);
      result.cases.push({name:'old-v37-omits-open-claim-api',get404:true,post404:true,sourcePreserved:true});
      await stop();const before=sql('SNAPSHOT');assertMigrations(before,37);
      current=await start(jar);await accounts();assert.deepEqual((await api('/on-call/handoffs?scope=MINE',owner)).requests.find(row=>row.id===legacy.id),legacy);
      assert.deepEqual((await api('/on-call/roster'+window(legacySource),auditor)).shifts,legacyRows);
      const replayedSwap=await api('/on-call/swaps/'+swap.id+'/coverage/revoke',admin,revocationCommand);
      for(const key of ['accepted','firstReplacement','secondReplacement','revocation'])assert.deepEqual(replayedSwap[key],swapReceipt[key]);
      assert.deepEqual(await api('/on-call/swaps',owner,swapDraft),swap);assert.deepEqual(await api('/on-call/swaps/'+swap.id+'/decisions',claimant,swapDecision),swap);
      await responsibility(first,2);await responsibility(second,3);
      await stop();const after=sql('SNAPSHOT');assertMigrations(after,41);
      for(const key of ['shifts','shiftHash','designatedHash','swapHash','swapRevocationHash','onCallAuditHash','legacyMigrationHash'])assert.equal(after[key],before[key]);
      assert.ok(before.designatedRows>0&&before.swapRows>0&&before.swapRevocationRows>0);
      assert.deepEqual(after.requests,[]);result.cases.push({name:'populated-v37-upgrade-preserves-source-designated-receipts-and-cancelled-coverage',before,after,legacyRowsPreserved:true,oldBilateralReceiptAndResponsibilitiesPreserved:true});
      current=await start(jar);await accounts();}
    const draft=publication(mainSource);await api(endpoint,null,draft,401);await api(endpoint,auditor,draft,403);await api(endpoint,admin,draft,403);
    const noSourceVersion={...draft};delete noSourceVersion.sourceVersion;await api(endpoint,owner,noSourceVersion,400);
    const published=await api(endpoint,owner,draft);assert.equal(published.status,'OPEN');assert.deepEqual(await api(endpoint,owner,draft),published);
    await api(endpoint,owner,{...draft,reason:'改变原内容'},409,'ONCALL_OPEN_HANDOFF_KEY_REUSED');
    const c=operation(),noVersion={...c};delete noVersion.version;
    await api(route(published.id)+'/claims',claimant,noVersion,400);await api(route(published.id)+'/withdrawals',owner,noVersion,400);
    await api(route(published.id)+'/claims',owner,c,403);await api(route(published.id)+'/claims',auditor,c,403);
    await api(route(published.id)+'/withdrawals',admin,c,403);
    assert.equal((await onCallAudits(published.id)).length,1);await responsibility(mainSource,2);
    result.cases.push({name:caseNames[0],anonymous401:true,auditor403:true,noManagerImpersonation:true,eachMissingVersion400:true,originalPublicationAck:true,changedKey409:true});
    const loss=await loseBody(published.id,c);assert.equal(loss.status,200);const committed=await facts(published.id);
    assert.equal(committed.request.status,'CLAIMED');assert.equal(committed.request.claimedBy,3);assert.equal(committed.request.version,1);
    assert.equal(committed.operation.operationKey,c.operationKey);assert.equal(committed.operation.capturedVersion,0);assert.equal(committed.replacement.userId,3);
    assert.deepEqual(parts(await api(route(published.id)+'/claims',claimant,c)),parts(committed));
    assert.equal((await onCallAudits(published.id)).length,2);await responsibility(mainSource,3);
    assert.deepEqual((await api('/on-call/roster'+window(mainSource),auditor)).shifts.find(s=>s.id===mainSource.id),mainSource);
    result.cases.push({name:caseNames[1],loss,manualOriginalAck:true,originalVersion:0,committedVersion:1,oneReceipt:true,audits:2,actualResponsibilityUser:3});
    await stop();current=await start(jar);await accounts();assert.deepEqual(parts(await api(route(published.id)+'/claims',claimant,c)),parts(committed));
    await api('/on-call/shifts/'+committed.replacement.id+'/cancel',admin,{version:0,reason:'独立取消，不复活认领覆盖'});
    const cancelled=await api(route(published.id)+'/claims',claimant,c);assert.deepEqual(cancelled.request,committed.request);assert.deepEqual(cancelled.operation,committed.operation);
    assert.ok(cancelled.replacement.cancelledAt);assert.equal(cancelled.replacement.version,1);await responsibility(mainSource,2);
    await api(route(published.id)+'/claims',claimant,{...c,reason:'改变原说明'},409,'ONCALL_OPEN_HANDOFF_OPERATION_KEY_REUSED');
    await api(route(published.id)+'/claims',admin,operation(),409,'ONCALL_OPEN_HANDOFF_VERSION_CONFLICT');
    assert.equal((await onCallAudits(published.id)).length,2);result.sqlExpectations.push(expectation(cancelled));
    result.cases.push({name:caseNames[2],sameReceiptAfterRestart:true,cancelledCoverageNotRevived:true,historicalClaimedPreserved:true,actualFallbackUser:2,audits:2});
    const racedSource=await source('CP98两人真实HTTP争抢'),raced=await api(endpoint,owner,publication(racedSource));
    const racedReplies=await Promise.all([request(route(raced.id)+'/claims',claimant,operation()),request(route(raced.id)+'/claims',admin,operation())]);
    assert.deepEqual(racedReplies.map(r=>r.status).sort(),[200,409]);assert.equal(racedReplies.find(r=>r.status===409).payload.error.code,'ONCALL_OPEN_HANDOFF_VERSION_CONFLICT');
    const racedFacts=await facts(raced.id);assert.ok([1,3].includes(racedFacts.request.claimedBy));await responsibility(racedSource,racedFacts.request.claimedBy);
    assert.equal((await onCallAudits(raced.id)).length,2);assert.equal((await api('/on-call/roster'+window(racedSource),auditor)).shifts.length,2);
    result.sqlExpectations.push(expectation(racedFacts));result.cases.push({name:caseNames[3],actualHttpStatuses:[200,409],oneOverride:true,oneReceipt:true,audits:2,winner:racedFacts.request.claimedBy});
    const withdrawnSource=await source('CP98本人撤回'),withdrawn=await api(endpoint,owner,publication(withdrawnSource)),withdrawCommand=operation();
    const withdrawnFacts=await api(route(withdrawn.id)+'/withdrawals',owner,withdrawCommand);
    assert.deepEqual(parts(await api(route(withdrawn.id)+'/withdrawals',owner,withdrawCommand)),parts(withdrawnFacts));
    await api(route(withdrawn.id)+'/claims',claimant,operation(),409,'ONCALL_OPEN_HANDOFF_VERSION_CONFLICT');
    assert.equal(withdrawnFacts.request.status,'WITHDRAWN');assert.equal(withdrawnFacts.replacement,null);await responsibility(withdrawnSource,2);
    result.sqlExpectations.push(expectation(withdrawnFacts));result.cases.push({name:caseNames[4],originalWithdrawAck:true,subsequentClaim409:true,noOverride:true,audits:2});
    const invalidSource=await source('CP98取消源'),invalid=await api(endpoint,owner,publication(invalidSource));
    await api('/on-call/shifts/'+invalidSource.id+'/cancel',admin,{version:0,reason:'源已独立取消'});
    await api(route(invalid.id)+'/claims',claimant,operation(),409,'ONCALL_OPEN_HANDOFF_SOURCE_CHANGED');const invalidFacts=await facts(invalid.id);
    assert.equal(invalidFacts.request.status,'OPEN');assert.equal(invalidFacts.replacement,null);assert.equal(invalidFacts.operation,null);
    result.sqlExpectations.push(expectation(invalidFacts));result.cases.push({name:caseNames[5],sourceChanged409:true,requestStillOpen:true,noOverrideOrReceipt:true,audits:1});
    const ongoingSource=await source('CP98进行中子时段',true),ongoingDraft=publication(ongoingSource);
    ongoingDraft.startsAt=new Date(Date.parse(ongoingSource.startsAt+'Z')+900000).toISOString().slice(0,19);
    ongoingDraft.endsAt=new Date(Date.parse(ongoingSource.endsAt+'Z')-900000).toISOString().slice(0,19);
    const ongoing=await api(endpoint,owner,ongoingDraft),ongoingFacts=await api(route(ongoing.id)+'/claims',claimant,operation());
    assert.equal(ongoingFacts.request.startsAt,ongoingDraft.startsAt);assert.equal(ongoingFacts.replacement.endsAt,ongoingDraft.endsAt);
    assert.ok(Date.parse(ongoingFacts.replacement.startsAt+'Z')>=Date.parse(ongoingFacts.request.createdAt+'Z'));
    await responsibility({...ongoingSource,endsAt:ongoingFacts.replacement.startsAt},2);
    await responsibility({...ongoingSource,startsAt:ongoingFacts.replacement.startsAt,endsAt:ongoingDraft.endsAt},3);
    await responsibility({...ongoingSource,startsAt:ongoingDraft.endsAt},2);result.sqlExpectations.push(expectation(ongoingFacts));
    result.cases.push({name:caseNames[6],originalTimesPreserved:true,remainingWholeSecondsOnly:true,pastOwner:2,remainingOwner:3,futureOwner:2});
    assert.deepEqual((await api('/on-call/handoffs?scope=MINE',owner)).requests.find(row=>row.id===legacy.id),legacy);assert.deepEqual((await api('/on-call/roster'+window(legacySource),auditor)).shifts,legacyRows);
    await stop();const beforeRole=sql('SNAPSHOT');assertMigrations(beforeRole,41);assertSqlRows(beforeRole.requests,result.sqlExpectations);
    const staleClaimant=claimant,demotion=sql('DEMOTE');assert.equal(demotion.actorId,3);assert.equal(demotion.changed,1);assert.equal(demotion.role,'AUDITOR');
    current=await start(jar);admin=(await api('/auth/login',null,{username:'admin',password:'OpsPilot@2026'})).accessToken;
    assert.equal((await api('/auth/me',staleClaimant)).roleCode,'AUDITOR');
    await api(route(published.id)+'/claims',staleClaimant,c,403);assert.deepEqual(parts(await api(route(published.id)+'/coverage',staleClaimant)),parts(cancelled));
    await stop();const afterRole=sql('SNAPSHOT');assert.deepEqual(afterRole,beforeRole);result.finalSql=afterRole;
    result.cases.push({name:caseNames[7],staleJwtUsesLatestRole:true,http403:true,allBusinessSqlFingerprintsUnchanged:true});
    assert.deepEqual(result.cases.filter(row=>caseNames.includes(row.name)).map(row=>row.name),caseNames);result.status='PASS';
  }catch(error){result.status='FAIL';result.failure=redact(error.stack);throw error;}
  finally{process.off('SIGINT',interrupt);process.off('SIGTERM',interrupt);
    try{for(const child of [...children].reverse())if(!result.stoppedPids.includes(child.pid)){await stopProcess(child);result.stoppedPids.push(child.pid);}result.ownedProcessesStopped=true;}
    finally{for(const fd of fds)fs.closeSync(fd);result.unexpectedJarErrors=0;result.completeJarLogs=[];
      for(const file of logs){const log=redact(fs.readFileSync(file,'utf8'));fs.writeFileSync(file,log);result.unexpectedJarErrors+=unexpectedLogLines(log);result.completeJarLogs.push({file:path.relative(root,file),sha256:hash(file)});}
      if(result.unexpectedJarErrors){result.status='FAIL';result.failure='Unexpected owned JAR error including shutdown';}
      fs.writeFileSync(path.join(out,'result.json'),JSON.stringify(result,null,2));console.log(JSON.stringify({...result,evidenceDirectory:path.relative(root,out)}));
      if(result.status==='FAIL')throw Error(result.failure);}}
}
module.exports={configuration,caseNames,assertSqlRows,assertMigrations,verify};
if(require.main===module)verify().catch(error=>{console.error(redact(error.message));process.exitCode=1;});

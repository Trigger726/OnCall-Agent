const assert = require('node:assert/strict');
const fs = require('node:fs'), path = require('node:path'), http = require('node:http');
const {spawn} = require('node:child_process');
const {randomUUID, randomBytes, createHash} = require('node:crypto');
const {requireFreePort, stopProcess, waitForHealth, redact, unexpectedLogLines} = require('./verify-oncall-browser-ci.cjs');

function configuration(env) {
  const comparison = env.OPSPILOT_SWAP_REVOCATION_UPGRADE === '1';
  assert.ok(!comparison || !env.CI, 'Archived comparison cannot replace own-source CI acceptance');
  return {comparison};
}
async function verify() {
  const {comparison} = configuration(process.env), root = path.resolve(__dirname, '..');
  const jar = path.join(root, 'target/opspilot-0.1.0-SNAPSHOT.jar'), old = path.join(root, 'target/cp92-before/opspilot-cp91.jar');
  assert.ok(fs.existsSync(jar)); if (comparison) assert.ok(fs.existsSync(old));
  for (const port of [9978,9979]) await requireFreePort(port);
  const parent = path.join(root, 'target/oncall-swap-revocation-http-it'); fs.mkdirSync(parent, {recursive:true});
  const evidence = fs.mkdtempSync(path.join(parent, 'run-')), database = path.join(evidence, 'database'); fs.mkdirSync(database);
  const children = [], descriptors = [], logs = [], secret = randomBytes(32).toString('hex');
  const hash = file => createHash('sha256').update(fs.readFileSync(file)).digest('hex');
  const result = {status:'RUNNING', comparison, cases:[], jarSha256:hash(jar), startedPids:[], stoppedPids:[],
    transport:'real HTTP to owned production JAR and H2 file database', tokensPersistedToEvidence:false, userFileDatabaseModified:false};
  const base = 'http://127.0.0.1:9978/api/v1';
  async function start(file) {
    const log = path.join(evidence, 'jar-'+(children.length+1)+'.log'), fd = fs.openSync(log, 'w'); descriptors.push(fd); logs.push(log);
    const java = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', process.platform==='win32'?'java.exe':'java') : 'java';
    const child = spawn(java, ['-Duser.timezone=UTC','-jar',file,'--server.address=127.0.0.1','--server.port=9978',
      '--management.server.address=127.0.0.1','--management.server.port=9979',
      '--spring.datasource.url=jdbc:h2:file:'+path.join(database,'opspilot').replaceAll('\\','/')+';MODE=MySQL;DATABASE_TO_LOWER=TRUE;WRITE_DELAY=0',
      '--spring.datasource.username=sa','--spring.datasource.password=','--spring.datasource.driver-class-name=org.h2.Driver',
      '--spring.h2.console.enabled=false','--opspilot.ai.enabled=false','--opspilot.agent.recovery.enabled=false',
      '--opspilot.oncall.rotation.enabled=false','--opspilot.oncall.escalation.enabled=false',
      '--opspilot.oncall.swap.notification.enabled=true','--opspilot.oncall.swap.notification.url=http://127.0.0.1:9978/owned-not-dispatched',
      '--opspilot.oncall.swap.notification.token='+secret],
      {cwd:root, env:{...process.env,JWT_SECRET:secret,ONCALL_SWAP_NOTIFICATION_DISPATCH_INITIAL_DELAY:'86400000',ONCALL_SWAP_NOTIFICATION_DISPATCH_DELAY:'86400000'},
        stdio:['ignore',fd,fd], windowsHide:true});
    child.once('error', error=>{child.launchError=error;}); children.push(child); result.startedPids.push(child.pid);
    await waitForHealth('http://127.0.0.1:9979/actuator/health', child); return child;
  }
  async function api(route, token, body, expected=200) {
    const response = await fetch(base+route, {method:body?'POST':'GET', headers:{...(token?{Authorization:'Bearer '+token}:{}),
      ...(body?{'Content-Type':'application/json'}:{})}, body:body?JSON.stringify(body):undefined, signal:AbortSignal.timeout(12000)});
    assert.equal(response.status, expected, route); return (await response.json()).data;
  }
  const login = async name => (await api('/auth/login',null,{username:name,password:'OpsPilot@2026'})).accessToken;
  let admin, owner, target, auditor, offset=48;
  async function accounts(){admin=await login('admin');owner=await login('zhangwei');target=await login('lina');auditor=await login('auditor');}
  async function pair(label) {
    const roster=await api('/on-call/roster',admin), start=Date.parse(roster.databaseNow+'Z')+offset*3600000; offset+=72;
    const iso=hours=>new Date(start+hours*3600000).toISOString().slice(0,19);
    const first=await api('/on-call/shifts',admin,{scheduleId:roster.schedules[0].id,userId:2,startsAt:iso(0),endsAt:iso(4),override:false,note:label+'第一段'});
    const second=await api('/on-call/shifts',admin,{scheduleId:roster.schedules[1].id,userId:3,startsAt:iso(24),endsAt:iso(28),override:false,note:label+'第二段'});
    const request={firstShiftId:first.id,firstVersion:0,secondShiftId:second.id,secondVersion:0,requestKey:randomUUID(),reason:label};
    const pending=await api('/on-call/swaps',owner,request), decision={version:0,status:'ACCEPTED',reason:'本人明确接受双方覆盖'};
    const accepted=await api('/on-call/swaps/'+pending.id+'/decisions',target,decision); return {first,second,request,decision,accepted};
  }
  const route = f => '/on-call/swaps/'+f.accepted.id+'/coverage';
  const command = f => ({swapVersion:f.accepted.version,firstReplacementVersion:0,secondReplacementVersion:0,operationKey:randomUUID(),reason:'原子撤销双方覆盖🙂'});
  async function responsibility(source,user) {
    const data=await api('/on-call/coverage?'+new URLSearchParams({scheduleId:String(source.scheduleId),from:source.startsAt,to:source.endsAt}),admin);
    assert.ok(data.segments.length);assert.ok(data.segments.every(s=>s.userId===user));
  }
  async function pairAudits(f) {
    const ids=[f.accepted.firstReplacementShiftId,f.accepted.secondReplacementShiftId].map(String);
    return (await api('/audit-logs?limit=500',admin)).filter(a=>a.action==='ONCALL_SWAP_COVERAGE_REVOKED'&&a.targetId===String(f.accepted.id)&&a.targetType==='ONCALL_SWAP'
      || a.action==='ONCALL_SHIFT_CANCELLED'&&a.targetType==='ONCALL_SHIFT'&&ids.includes(a.targetId));
  }
  function discardResponseBody(url,token,body) {
    return new Promise((resolve,reject)=>{
      const request=http.request(base+url,{method:'POST',headers:{Authorization:'Bearer '+token,'Content-Type':'application/json'}},response=>{
        const status=response.statusCode;response.destroy();request.destroy();resolve({status,bodyRead:false,actualSocketDestroyed:true});
      });request.on('error',reject);request.setTimeout(12000,()=>request.destroy(new Error('Owned response-loss request timed out')));request.end(JSON.stringify(body));
    });
  }
  const interrupt=()=>{for(const child of children)if(child.exitCode===null&&child.signalCode===null)child.kill('SIGTERM');};
  process.once('SIGINT',interrupt);process.once('SIGTERM',interrupt);
  try {
    let current=await start(comparison?old:jar);await accounts();
    if(comparison) {
      result.oldJarSha256=hash(old);const legacy=await pair('CP92旧版独立取消对照');
      await api(route(legacy)+'/revoke',admin,command(legacy),404);
      await api('/on-call/shifts/'+legacy.accepted.firstReplacementShiftId+'/cancel',admin,{version:0,reason:'旧版仅撤销一段'});
      await responsibility(legacy.first,2);await responsibility(legacy.second,2);
      const notifications=await api('/on-call/swaps/'+legacy.accepted.id+'/notifications',admin);
      result.cases.push({name:'old-v36-missing-paired-command-and-one-sided-coverage',pairedHttpStatus:404,acceptedHistory:legacy.accepted,firstRestored:true,secondStillExchanged:true});
      await stopProcess(current);result.stoppedPids.push(current.pid);current=await start(jar);await accounts();
      assert.deepEqual(await api('/on-call/swaps/'+legacy.accepted.id,admin),legacy.accepted);
      const facts=await api(route(legacy),admin);assert.ok(facts.firstReplacement.cancelledAt);assert.equal(facts.secondReplacement.cancelledAt,null);assert.equal(facts.revocation,null);
      const nextNotifications=await api('/on-call/swaps/'+legacy.accepted.id+'/notifications',admin);
      assert.ok(Array.isArray(notifications.deliveries));assert.equal(notifications.deliveries.length,3);
      assert.deepEqual(nextNotifications.deliveries,notifications.deliveries);
      await api(route(legacy)+'/revoke',admin,command(legacy),409);await responsibility(legacy.first,2);await responsibility(legacy.second,2);
      result.cases.push({name:'v36-to-v37-keeps-old-accepted-partial-cancellation-and-notifications',acceptedPreserved:true,oldPartialStateNotFabricatedAsRevoked:true,notificationRowsPreserved:true});
    }
    const f=await pair('CP92生产JAR成对撤销'), c=command(f), before=await api(route(f),auditor);
    assert.deepEqual(before.accepted,f.accepted);assert.equal(before.revocation,null);await responsibility(f.first,3);await responsibility(f.second,2);
    await api(route(f)+'/revoke',null,c,401);for(const token of [owner,auditor])await api(route(f)+'/revoke',token,c,403);
    for(const key of ['swapVersion','firstReplacementVersion','secondReplacementVersion']) {const bad={...c};delete bad[key];await api(route(f)+'/revoke',admin,bad,400);}
    await api(route(f)+'/revoke',admin,{...c,secondReplacementVersion:9},409);assert.equal((await pairAudits(f)).length,0);
    const loss=await discardResponseBody(route(f)+'/revoke',admin,c);assert.equal(loss.status,200);
    const committed=await api(route(f),auditor);assert.deepEqual(committed.accepted,f.accepted);assert.equal(committed.revocation.operationKey,c.operationKey);
    assert.equal(committed.firstReplacement.version,1);assert.equal(committed.secondReplacement.version,1);assert.ok(committed.firstReplacement.cancelledAt&&committed.secondReplacement.cancelledAt);
    const ack=await api(route(f)+'/revoke',admin,c);for(const key of ['accepted','firstReplacement','secondReplacement','revocation'])assert.deepEqual(ack[key],committed[key]);
    await api(route(f)+'/revoke',admin,{...c,reason:'不同说明'},409);await api(route(f)+'/revoke',target,c,409);
    assert.deepEqual(await api('/on-call/swaps',owner,f.request),f.accepted);assert.deepEqual(await api('/on-call/swaps/'+f.accepted.id+'/decisions',target,f.decision),f.accepted);
    await responsibility(f.first,2);await responsibility(f.second,3);const audits=await pairAudits(f);assert.equal(audits.length,3);
    assert.deepEqual(audits.map(a=>a.action).sort(),['ONCALL_SHIFT_CANCELLED','ONCALL_SHIFT_CANCELLED','ONCALL_SWAP_COVERAGE_REVOKED']);
    result.cases.push({name:'real-http-paired-revocation-and-response-body-loss',swapId:f.accepted.id,capturedVersions:[1,0,0],loss,
      manualOriginalAck:true,noDuplicateAudit:true,audits:3,bothCurrentResponsibilitiesRestored:true,acceptedAndOriginalCommandsPreserved:true,anonymous401:true,nonManager403:true,eachMissingVersion400:true,staleVersion409:true});
    await stopProcess(current);result.stoppedPids.push(current.pid);current=await start(jar);await accounts();
    const restarted=await api(route(f)+'/revoke',admin,c);for(const key of ['accepted','firstReplacement','secondReplacement','revocation'])assert.deepEqual(restarted[key],committed[key]);
    assert.equal((await pairAudits(f)).length,3);await responsibility(f.first,2);await responsibility(f.second,3);
    result.cases.push({name:'persisted-original-ack-after-jvm-restart',sameOriginalReceipt:true,audits:3,noRevival:true});
    const partial=await pair('CP92独立取消竞争事实');await api('/on-call/shifts/'+partial.accepted.secondReplacementShiftId+'/cancel',admin,{version:0,reason:'独立旧取消'});
    await api(route(partial)+'/revoke',admin,command(partial),409);const still=await api(route(partial),admin);
    assert.equal(still.firstReplacement.cancelledAt,null);assert.ok(still.secondReplacement.cancelledAt);assert.equal(still.revocation,null);assert.equal((await pairAudits(partial)).length,1);
    result.cases.push({name:'one-independent-cancel-blocks-paired-command',httpStatus:409,uncancelledCounterpartPreserved:true,noRevocationOrNewAudit:true});result.status='PASS';
  } catch(error) {result.status='FAIL';result.failure=redact(error.stack);throw error;}
  finally {
    process.off('SIGINT',interrupt);process.off('SIGTERM',interrupt);
    try {for(const child of [...children].reverse())if(!result.stoppedPids.includes(child.pid)){await stopProcess(child);result.stoppedPids.push(child.pid);}result.ownedProcessesStopped=true;}
    finally {for(const fd of descriptors)fs.closeSync(fd);result.unexpectedJarErrors=0;
      for(const file of logs){const text=redact(fs.readFileSync(file,'utf8'));fs.writeFileSync(file,text);result.unexpectedJarErrors+=unexpectedLogLines(text);}
      if(result.unexpectedJarErrors){result.status='FAIL';result.failure='Unexpected owned JAR errors';}
      fs.writeFileSync(path.join(evidence,'result.json'),JSON.stringify(result,null,2));console.log(JSON.stringify({...result,evidenceDirectory:path.relative(root,evidence)}));
      if(result.status==='FAIL')throw new Error(result.failure);}
  }
}
module.exports={configuration};
if(require.main===module)verify().catch(error=>{console.error(redact(error.message));process.exitCode=1;});

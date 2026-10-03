const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const {spawn}=require('node:child_process');
const {randomUUID,randomBytes,createHash}=require('node:crypto');
const {requireFreePort,stopProcess,waitForHealth,redact,unexpectedLogLines}=require('./verify-oncall-browser-ci.cjs');

async function verify(){
  const upgrade=process.env.OPSPILOT_SWAP_UPGRADE==='1';
  if(upgrade&&process.env.CI)throw new Error('Archived swap comparison cannot replace CI acceptance');
  const root=path.resolve(__dirname,'..'),jar=path.join(root,'target/opspilot-0.1.0-SNAPSHOT.jar'),old=path.join(root,'target/cp85-before/opspilot-cp84.jar');
  assert.ok(fs.existsSync(jar));if(upgrade)assert.ok(fs.existsSync(old));
  for(const port of [9973,9974])await requireFreePort(port);
  const parent=path.join(root,'target/oncall-swap-http-it');fs.mkdirSync(parent,{recursive:true});
  const evidence=fs.mkdtempSync(path.join(parent,'run-')),database=path.join(evidence,'database');fs.mkdirSync(database);
  const children=[],descriptors=[],logs=[],secret=randomBytes(32).toString('hex');
  const result={status:'RUNNING',upgradeComparison:upgrade,cases:[],startedPids:[],stoppedPids:[],tokensPersistedToEvidence:false,userFileDatabaseModified:false};
  const digest=file=>createHash('sha256').update(fs.readFileSync(file)).digest('hex');result.jarSha256=digest(jar);
  async function start(file){
    const log=path.join(evidence,'jar-'+(children.length+1)+'.log'),fd=fs.openSync(log,'w');descriptors.push(fd);logs.push(log);
    const java=process.env.JAVA_HOME?path.join(process.env.JAVA_HOME,'bin',process.platform==='win32'?'java.exe':'java'):'java';
    const child=spawn(java,['-Duser.timezone=UTC','-jar',file,'--server.address=127.0.0.1','--server.port=9973',
      '--management.server.address=127.0.0.1','--management.server.port=9974',
      '--spring.datasource.url=jdbc:h2:file:'+path.join(database,'opspilot').replaceAll('\\','/')+';MODE=MySQL;DATABASE_TO_LOWER=TRUE;WRITE_DELAY=0',
      '--spring.datasource.username=sa','--spring.datasource.password=','--spring.datasource.driver-class-name=org.h2.Driver',
      '--spring.h2.console.enabled=false','--opspilot.ai.enabled=false','--opspilot.agent.recovery.enabled=false',
      '--opspilot.oncall.rotation.enabled=false','--opspilot.oncall.escalation.enabled=false'],
      {cwd:root,env:{...process.env,JWT_SECRET:secret},stdio:['ignore',fd,fd],windowsHide:true});
    child.once('error',error=>{child.launchError=error;});children.push(child);result.startedPids.push(child.pid);
    await waitForHealth('http://127.0.0.1:9974/actuator/health',child);return child;
  }
  async function api(route,token,body){const response=await fetch('http://127.0.0.1:9973/api/v1'+route,{method:body?'POST':'GET',
    headers:{...(token?{Authorization:'Bearer '+token}:{}),...(body?{'Content-Type':'application/json'}:{})},
    body:body?JSON.stringify(body):undefined,signal:AbortSignal.timeout(12000)});return {status:response.status,json:await response.json()};}
  async function login(username){const r=await api('/auth/login',null,{username,password:'OpsPilot@2026'});assert.equal(r.status,200);return r.json.data.accessToken;}
  async function responsibility(source,token,user){const q=new URLSearchParams({scheduleId:String(source.scheduleId),from:source.startsAt,to:source.endsAt});
    const r=await api('/on-call/coverage?'+q,token);assert.equal(r.status,200);assert.ok(r.json.data.segments.length);
    assert.ok(r.json.data.segments.every(s=>s.userId===user));}
  const interrupt=()=>{for(const child of children)if(child.exitCode===null&&child.signalCode===null)child.kill('SIGTERM');};
  process.once('SIGINT',interrupt);process.once('SIGTERM',interrupt);
  try{
    let current=await start(upgrade?old:jar),admin=await login('admin');
    const roster=await api('/on-call/roster',admin);assert.equal(roster.status,200);
    const shiftStart=new Date(Date.parse(roster.json.data.databaseNow+'Z')+2*86400000);
    const iso=offset=>new Date(shiftStart.getTime()+offset*3600000).toISOString().slice(0,19);
    const first=(await api('/on-call/shifts',admin,{scheduleId:1,userId:2,startsAt:iso(0),endsAt:iso(4),override:false,note:'换班真实HTTP本人班次'}));
    const second=(await api('/on-call/shifts',admin,{scheduleId:1,userId:3,startsAt:iso(24),endsAt:iso(28),override:false,note:'换班真实HTTP对方班次'}));
    assert.equal(first.status,200);assert.equal(second.status,200);
    if(upgrade){
      const absent=await api('/on-call/swaps',admin);assert.equal(absent.status,404);
      result.oldJarSha256=digest(old);result.cases.push({name:'old-v33-capability-gap',swapsHttpStatus:absent.status,sourceShiftIds:[first.json.data.id,second.json.data.id]});
      await stopProcess(current);result.stoppedPids.push(current.pid);current=await start(jar);admin=await login('admin');
      result.oldSourceShiftsPreservedThroughV34=true;
    }
    const owner=await login('zhangwei'),target=await login('lina'),auditor=await login('auditor');
    const command={firstShiftId:first.json.data.id,firstVersion:0,secondShiftId:second.json.data.id,secondVersion:0,requestKey:randomUUID(),reason:'双方互换真实HTTP未来班次'};
    assert.equal((await api('/on-call/swaps',null,command)).status,401);assert.equal((await api('/on-call/swaps',auditor,command)).status,403);
    const pending=await api('/on-call/swaps',owner,command);assert.equal(pending.status,200);assert.equal(pending.json.data.status,'PENDING');
    assert.equal((await api('/on-call/swaps',owner,command)).json.data.id,pending.json.data.id);
    const decision={version:0,status:'ACCEPTED',reason:'双方确认交换两段值班'},route='/on-call/swaps/'+pending.json.data.id+'/decisions';
    assert.equal((await api(route,admin,decision)).status,403);assert.equal((await api(route,owner,decision)).status,403);
    assert.equal((await api(route,target,{status:'ACCEPTED',reason:'缺版本'})).status,400);
    const accepted=await api(route,target,decision);assert.equal(accepted.status,200);assert.equal(accepted.json.data.status,'ACCEPTED');
    assert.equal(accepted.json.data.version,1);assert.notEqual(accepted.json.data.firstReplacementShiftId,accepted.json.data.secondReplacementShiftId);
    assert.deepEqual((await api(route,target,decision)).json.data,accepted.json.data);
    assert.equal((await api('/on-call/swaps',owner,command)).json.data.id,pending.json.data.id);
    await responsibility(first.json.data,admin,3);await responsibility(second.json.data,admin,2);
    const history=await api('/on-call/roster?scheduleId=1&from='+iso(0)+'&to='+iso(29),admin);assert.equal(history.status,200);
    const ids=[first.json.data.id,second.json.data.id,accepted.json.data.firstReplacementShiftId,accepted.json.data.secondReplacementShiftId];
    const shifts=history.json.data.shifts.filter(s=>ids.includes(s.id));assert.equal(shifts.length,4);
    assert.ok(shifts.filter(s=>!s.override).every(s=>s.version===0&&s.cancelledAt===null));
    const audits=await api('/audit-logs?limit=500',admin);assert.equal(audits.status,200);
    const swapAudits=audits.json.data.filter(a=>a.targetType==='ONCALL_SWAP'&&a.targetId===String(pending.json.data.id));
    assert.deepEqual(swapAudits.map(a=>a.action).sort(),['ONCALL_SWAP_ACCEPTED','ONCALL_SWAP_REQUESTED']);
    result.cases.push({name:'real-http-bilateral-swap',swapId:pending.json.data.id,sourceShiftIds:ids.slice(0,2),replacementShiftIds:ids.slice(2),
      status:'ACCEPTED',version:1,ownerThenTargetConfirmed:true,adminAndOwnerCannotAccept:true,anonymous401:true,auditor403:true,missingDecisionVersion400:true,
      bothEffectiveResponsibilitiesExchanged:true,ordinaryHistoryPreserved:true,idempotentRequestAndDecision:true,swapAudits:swapAudits.length});
    assert.equal((await api('/on-call/shifts/'+accepted.json.data.firstReplacementShiftId+'/cancel',admin,{version:0,reason:'管理撤销第一覆盖'})).status,200);
    assert.equal((await api(route,target,decision)).json.data.firstReplacementShiftId,accepted.json.data.firstReplacementShiftId);
    await responsibility(first.json.data,admin,2);await responsibility(second.json.data,admin,2);
    result.cases.push({name:'accepted-history-does-not-revive-cancelled-coverage',acceptedFactRetained:true,firstResponsibilityRestored:true,secondCoverageStillActive:true});
    result.status='PASS';
  }catch(error){result.status='FAIL';result.failure=redact(error.stack);throw error;}
  finally{
    process.off('SIGINT',interrupt);process.off('SIGTERM',interrupt);
    try{for(const child of [...children].reverse())if(!result.stoppedPids.includes(child.pid)){await stopProcess(child);result.stoppedPids.push(child.pid);}result.ownedProcessesStopped=true;}
    finally{for(const fd of descriptors)fs.closeSync(fd);result.unexpectedJarErrors=0;
      for(const file of logs){const text=redact(fs.readFileSync(file,'utf8'));fs.writeFileSync(file,text);result.unexpectedJarErrors+=unexpectedLogLines(text);}
      if(result.unexpectedJarErrors){result.status='FAIL';result.failure='Unexpected owned JAR errors';}
      fs.writeFileSync(path.join(evidence,'result.json'),JSON.stringify(result,null,2));console.log(JSON.stringify({...result,evidenceDirectory:path.relative(root,evidence)}));
      if(result.status==='FAIL')throw new Error(result.failure);}
  }
}
if(require.main===module)verify().catch(error=>{console.error(redact(error.message));process.exitCode=1;});

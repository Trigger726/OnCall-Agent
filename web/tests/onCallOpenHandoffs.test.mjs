import {beforeEach,test,mock} from 'node:test'
import assert from 'node:assert/strict'
import {build} from 'esbuild'
import {fileURLToPath} from 'node:url'
const built=await build({entryPoints:[fileURLToPath(new URL('../src/services/onCallOpenHandoffs.ts',import.meta.url))],bundle:true,write:false,platform:'node',format:'esm'})
const open=await import(`data:text/javascript;base64,${Buffer.from(built.outputFiles[0].text).toString('base64')}`)
const uuid='2757c5b4-aa17-4f08-8368-4e8294e13628'
const source=()=>({id:10,scheduleId:1,scheduleName:'原计划',userId:2,userName:'本人',startsAt:'2026-10-05T10:00:00',endsAt:'2026-10-05T14:00:00',override:false,version:0,cancelledAt:null})
const row=()=>({id:7,scheduleId:1,sourceShiftId:10,sourceVersion:0,requesterId:2,requestKey:uuid,startsAt:'2026-10-05T11:00:00',endsAt:'2026-10-05T13:00:00',reason:'原发布',status:'OPEN',version:0,createdAt:'2026-10-04T10:00:00',closedAt:null,claimedBy:null,replacementShiftId:null})
const facts=()=>({databaseNow:'2026-10-05T12:00:00.000001',request:row(),replacement:null,operation:null})
const roster=()=>({databaseNow:facts().databaseNow,shifts:[source()],schedules:[{id:1}],users:[{id:2,roleCode:'ON_CALL'},{id:3,roleCode:'OPS_MANAGER'}],truncated:false})
const publish=()=>({schema:1,actorId:2,blocked:false,action:'PUBLISH',source:source(),command:{sourceShiftId:10,sourceVersion:0,requestKey:uuid,startsAt:row().startsAt,endsAt:row().endsAt,reason:'原发布'}})
const claim=()=>({schema:1,actorId:3,blocked:false,action:'CLAIM',row:row(),command:{version:0,operationKey:uuid,reason:'本人接班'}})
const withdraw=()=>({...claim(),actorId:2,action:'WITHDRAW'})
const closed=(i=claim())=>{const f=facts(),at='2026-10-05T12:00:00';Object.assign(f.request,{status:i.action==='CLAIM'?'CLAIMED':'WITHDRAWN',version:i.command.version+1,closedAt:at,claimedBy:i.action==='CLAIM'?i.actorId:null,replacementShiftId:i.action==='CLAIM'?11:null});
  f.operation={handoffId:7,actorId:i.actorId,operation:i.action,operationKey:uuid,capturedVersion:i.command.version,reason:i.command.reason,committedAt:at};if(i.action==='CLAIM')f.replacement={id:11,scheduleId:1,userId:i.actorId,version:0,startsAt:at,endsAt:f.request.endsAt,cancelledAt:null,cancellationReason:null};return f}
const storage=()=>{const data=new Map();return {getItem:k=>data.get(k)??null,setItem:(k,v)=>data.set(k,v),removeItem:k=>data.delete(k),data}}
beforeEach(()=>{mock.restoreAll();globalThis.localStorage={getItem:()=> 'unit-public-token'};globalThis.window=new EventTarget()})
test('publication is owned ordinary live source for all three operational roles, never manager impersonation',()=>{
  for(const role of ['ADMIN','OPS_MANAGER','ON_CALL'])assert.ok(open.canPublishOpen(source(),2,role,facts().databaseNow))
  for(const role of ['AUDITOR','USER',undefined])assert.equal(open.canPublishOpen(source(),2,role,facts().databaseNow),false)
  assert.equal(open.canPublishOpen(source(),3,'ADMIN',facts().databaseNow),false)
  for(const change of [s=>s.override=true,s=>s.cancelledAt='2026-10-04T12:00:00',s=>delete s.cancelledAt,s=>s.version=-1]){const s=source();change(s);assert.equal(open.canPublishOpen(s,2,'ON_CALL',facts().databaseNow),false)}
})
test('strict naive time accepts minute inputs and whole seconds, rejects invalid dates and fractional commands',()=>{
  assert.equal(open.openTime('2026-10-05T12:00'),'2026-10-05T12:00:00');assert.equal(open.openTime('2026-10-05T12:00:00.000000'),'2026-10-05T12:00:00')
  for(const s of ['2026-02-29T12:00','2026-10-05T24:00','2026-10-05T12:00:00.000001','2026-10-05T12:00:00Z','2026-10-05T12:00:00+08:00'])assert.equal(open.openTime(s),'')
})
test('claimants must be themselves and cannot self-claim; readers cannot write',()=>{
  assert.equal(open.openActionError(facts(),'CLAIM',3,'OPS_MANAGER'),'')
  assert.match(open.openActionError(facts(),'CLAIM',2,'ADMIN'),/不能认领/)
  assert.match(open.openActionError(facts(),'CLAIM',3,'AUDITOR'),/只读/)
  assert.match(open.openActionError(facts(),'WITHDRAW',3,'ADMIN'),/只有发布本人/)
})
test('ongoing request allows remaining claim, exact DB end rejects new claim but owner withdrawal remains',()=>{
  const f=facts();assert.equal(open.openActionError(f,'CLAIM',3,'ON_CALL'),'');f.databaseNow=f.request.endsAt
  assert.match(open.openActionError(f,'CLAIM',3,'ON_CALL'),/已结束/);assert.equal(open.openActionError(f,'WITHDRAW',2,'ON_CALL'),'')
  mock.method(Date,'now',()=>{throw Error('Browser clock not authority')});assert.equal(open.openActionError(facts(),'CLAIM',3,'ON_CALL'),'')
})
test('closed unknown or inconsistent receipt facts do not offer a new operation',()=>{
  for(const change of [f=>f.request.status='CLAIMED',f=>f.request.status='WITHDRAWN',f=>f.request.version=2147483647,f=>f.operation={},f=>delete f.operation,f=>f.replacement={},f=>f.databaseNow='invalid']){const f=facts();change(f);assert.ok(open.openActionError(f,'CLAIM',3,'ON_CALL'))}
  assert.ok(open.openActionError(null,'CLAIM',3,'ON_CALL'))
})
test('all command versions explicit integers, lower UUID, bounded reason and original identity mandatory',()=>{
  assert.equal(open.openIntentError(publish()),'');assert.equal(open.openIntentError(claim()),'');assert.equal(open.openIntentError(withdraw()),'')
  for(const bad of [undefined,null,-1,1.5,'0',2147483648]){const p=publish();p.command.sourceVersion=bad;assert.ok(open.openIntentError(p));const c=claim();c.command.version=bad;assert.ok(open.openIntentError(c))}
  for(const change of [i=>i.actorId=0,i=>i.blocked='true',i=>i.command.operationKey=uuid.toUpperCase(),i=>i.command.reason=' ',i=>i.command.reason='x'.repeat(501),i=>i.row.id=8.5]){const c=claim();change(c);assert.ok(open.openIntentError(c))}
})
test('publication subwindow must stay within captured source and retain canonical seconds',()=>{
  for(const change of [c=>c.sourceShiftId=11,c=>c.startsAt='2026-10-05T09:00:00',c=>c.endsAt='2026-10-05T15:00:00',c=>c.endsAt=c.startsAt,c=>c.requestKey='not-uuid',c=>c.startsAt='2026-10-05T11:00']){const p=publish();change(p.command);assert.ok(open.openIntentError(p))}
})
test('preflight reads exact source version, owner and active plan/users; no silent rebase',()=>{
  assert.equal(open.openPreflightError(publish(),null,roster(),'ON_CALL'),'');assert.equal(open.openPreflightError(claim(),facts(),roster(),'OPS_MANAGER'),'')
  for(const change of [r=>r.shifts[0].version++,r=>r.shifts[0].cancelledAt='2026-10-04T12:00:00',r=>r.shifts[0].userId=3,r=>r.shifts[0].scheduleId=2,r=>r.shifts=[],r=>r.schedules=[],r=>r.users=r.users.filter(u=>u.id!==2),r=>r.users[1].roleCode='AUDITOR',r=>r.truncated=true]){const r=roster();change(r);assert.ok(open.openPreflightError(claim(),facts(),r,'OPS_MANAGER'))}
})
test('latest coverage ID/version or deadline changes block a first claim without rebasing',()=>{
  for(const change of [f=>f.request.id=9,f=>f.request.version=1,f=>f.databaseNow=f.request.endsAt]){const f=facts();change(f);assert.ok(open.openPreflightError(claim(),f,roster(),'OPS_MANAGER'))}
})
test('remaining-window conflicts reject while an earlier ended override does not',()=>{
  const r=roster();r.shifts.push({...source(),id:11,userId:3,override:true,startsAt:'2026-10-05T10:00:00',endsAt:'2026-10-05T11:30:00'});assert.equal(open.openPreflightError(claim(),facts(),r,'OPS_MANAGER'),'')
  r.shifts[1].endsAt='2026-10-05T12:30:00';assert.match(open.openPreflightError(claim(),facts(),r,'OPS_MANAGER'),/冲突/)
  r.shifts[1].cancelledAt='2026-10-04T10:00:00';assert.equal(open.openPreflightError(claim(),facts(),r,'OPS_MANAGER'),'')
})
test('owner withdrawal needs request but not ongoing source/active schedule',()=>{
  const f=facts();f.databaseNow='2026-10-06T10:00:00';assert.equal(open.openPreflightError(withdraw(),f,null,'ON_CALL'),'')
  assert.ok(open.openPreflightError(withdraw(),null,null,'ON_CALL'))
})
test('original per actor intent roundtrips with blocked flag and no network/retry',()=>{
  let posts=0;mock.method(globalThis,'fetch',()=>{posts++;throw Error('No network permitted')});const s=storage(),c={...claim(),blocked:true};open.saveOpenIntent(s,c);assert.deepEqual(open.readOpenIntent(s,3),c);assert.equal(open.readOpenIntent(s,2),null);assert.equal(posts,0)
  open.saveOpenIntent(s,publish());open.clearOpenIntent(s,3);assert.deepEqual(open.readOpenIntent(s,2),publish())
})
test('corrupt foreign empty or structurally invalid saved intent retained and rejected',()=>{
  for(const value of ['','{bad',{...claim(),schema:2},{...claim(),actorId:2},{...claim(),action:'IMPERSONATE'},{...claim(),command:{reason:'missing keys'}}]){const s=storage(),raw=typeof value==='string'?value:JSON.stringify(value);s.setItem('opspilot_open_handoff_intent:v1:3',raw);assert.throws(()=>open.readOpenIntent(s,3));assert.equal(s.getItem('opspilot_open_handoff_intent:v1:3'),raw)}
})
test('quota read denial silent no-op save and removal errors propagate without POST',()=>{
  assert.throws(()=>open.saveOpenIntent({setItem:()=>{throw Error('quota')},getItem:()=>null},claim()),/quota/)
  assert.throws(()=>open.saveOpenIntent({setItem:()=>{},getItem:()=>null},claim()),/未可靠保存/)
  assert.throws(()=>open.saveOpenIntent({setItem:()=>{},getItem:()=>{throw Error('denied')}},claim()),/denied/)
  assert.throws(()=>open.readOpenIntent({getItem:()=>{throw Error('denied')}},3),/denied/)
  assert.throws(()=>open.clearOpenIntent({removeItem:()=>{throw Error('denied')}},3),/denied/)
})
test('list/coverage/roster actual API paths retain SQL filters and captured naive interval',async()=>{
  const calls=[];mock.method(globalThis,'fetch',async(url,init)=>{calls.push({url,init});return new Response(JSON.stringify({success:true,data:facts()}),{status:200})})
  await open.listOpenHandoffs('AVAILABLE','OPEN','1');assert.equal(calls[0].url,'/api/v1/on-call/open-handoffs?scope=AVAILABLE&status=OPEN&scheduleId=1')
  await open.getOpenCoverage(7);assert.equal(calls[1].url,'/api/v1/on-call/open-handoffs/7/coverage')
  await open.getOpenRoster(claim());const q=new URL(calls[2].url,'http://unit').searchParams;assert.equal(q.get('from'),row().startsAt);assert.equal(q.get('to'),row().endsAt);assert.equal(q.get('scheduleId'),'1')
})
test('foreign or invalid coverage response rejects and cannot masquerade as selected request',async()=>{
  for(const change of [f=>f.request.id=9,f=>f.databaseNow='invalid',f=>f.request.version=null]){const f=facts();change(f);mock.method(globalThis,'fetch',async()=>new Response(JSON.stringify({success:true,data:f}),{status:200}));await assert.rejects(()=>open.getOpenCoverage(7),/异常/);mock.restoreAll()}
})
test('publish sends only exact original command and requires its own matching receipt',async()=>{
  const calls=[],p=publish();mock.method(globalThis,'fetch',async(url,init)=>{calls.push({url,init});return new Response(JSON.stringify({success:true,data:row()}),{status:200})});await open.submitOpenIntent(p);assert.equal(calls[0].url,'/api/v1/on-call/open-handoffs');assert.deepEqual(JSON.parse(calls[0].init.body),p.command)
})
test('claim and withdrawal send original keys/version, never targetUserId or new identity',async()=>{
  for(const i of [claim(),withdraw()]){const f=closed(i);let call
    mock.method(globalThis,'fetch',async(url,init)=>{call={url,init};return new Response(JSON.stringify({success:true,data:f}),{status:200})});await open.submitOpenIntent(i);assert.equal(call.url,`/api/v1/on-call/open-handoffs/7/${i.action==='CLAIM'?'claims':'withdrawals'}`);assert.deepEqual(JSON.parse(call.init.body),i.command);mock.restoreAll()}
})
test('foreign actor key version kind or reason in POST receipt cannot clear original intent',async()=>{
  for(const change of [f=>f.request.id=99,f=>f.operation.actorId=2,f=>f.operation.operationKey='foreign',f=>f.operation.operation='WITHDRAW',f=>f.operation.capturedVersion=1,f=>f.operation.reason='changed']){const f=closed();change(f);mock.method(globalThis,'fetch',async()=>new Response(JSON.stringify({success:true,data:f}),{status:200}));await assert.rejects(()=>open.submitOpenIntent(claim()),/不符/);mock.restoreAll()}
})
test('HTTP409 and transport loss are single calls, with no implicit retry',async()=>{
  let count=0;mock.method(globalThis,'fetch',async()=>{count++;return new Response(JSON.stringify({success:false,error:{code:'CONFLICT',message:'已变化'}}),{status:409})});await assert.rejects(()=>open.submitOpenIntent(claim()),e=>e.status===409);assert.equal(count,1);mock.restoreAll()
  count=0;mock.method(globalThis,'fetch',async()=>{count++;throw new TypeError('lost response')});await assert.rejects(()=>open.submitOpenIntent(claim()),/lost/);assert.equal(count,1)
})
test('silent removal and failed clear readback do not claim original intent was removed',()=>{
  const s=storage(),c=claim();open.saveOpenIntent(s,c);const original=s.getItem('opspilot_open_handoff_intent:v1:3');s.removeItem=()=>{};assert.throws(()=>open.clearOpenIntent(s,3),/未可靠清除/);assert.equal(s.getItem('opspilot_open_handoff_intent:v1:3'),original)
  assert.throws(()=>open.clearOpenIntent({removeItem:()=>{},getItem:()=>{throw Error('readback denied')}},3),/readback denied/)
})
test('successful clear verifies null for exactly this actor and leaves another actor untouched',()=>{
  const s=storage();open.saveOpenIntent(s,claim());open.saveOpenIntent(s,publish());open.clearOpenIntent(s,3);assert.equal(s.getItem('opspilot_open_handoff_intent:v1:3'),null);assert.deepEqual(open.readOpenIntent(s,2),publish())
})
test('history response rejects contradictory request operation replacement relationships',async()=>{
  for(const change of [f=>f.request.status='OPEN',f=>f.request.version=0,f=>f.request.claimedBy=1,f=>f.request.replacementShiftId=99,f=>f.request.closedAt=null,f=>f.operation=null,f=>f.operation.handoffId=99,f=>f.operation.committedAt='invalid',f=>f.operation.committedAt='2026-10-05T12:00:01',f=>f.operation.capturedVersion=1,f=>f.replacement=null,f=>f.replacement.scheduleId=2,f=>f.replacement.userId=1,f=>f.replacement.startsAt=f.replacement.endsAt,f=>f.replacement.endsAt='2026-10-05T14:00:00',f=>f.replacement.cancelledAt='invalid',f=>f.replacement.cancellationReason='no timestamp']){const f=closed();change(f);mock.method(globalThis,'fetch',async()=>new Response(JSON.stringify({success:true,data:f}),{status:200}));await assert.rejects(()=>open.getOpenCoverage(7),/异常/);mock.restoreAll()}
})
test('POST receipt preserves all immutable captured request fields before clearing original intent',async()=>{
  for(const change of [f=>f.request.scheduleId=2,f=>f.request.sourceShiftId=99,f=>f.request.sourceVersion=1,f=>f.request.requesterId=1,f=>f.request.requestKey='11111111-1111-4111-8111-111111111111',f=>f.request.startsAt='2026-10-05T10:30:00',f=>f.request.endsAt='2026-10-05T13:30:00',f=>f.request.reason='changed publication',f=>f.request.createdAt='2026-10-04T11:00:00',f=>f.request.version=0,f=>f.replacement.userId=1]){const f=closed();change(f);mock.method(globalThis,'fetch',async()=>new Response(JSON.stringify({success:true,data:f}),{status:200}));await assert.rejects(()=>open.submitOpenIntent(claim()),/不符/);mock.restoreAll()}
})
test('real cancelled ended claim receipt remains valid without inventing current responsibility',async()=>{
  const f=closed();f.databaseNow='2026-10-06T10:00:00';Object.assign(f.replacement,{version:1,cancelledAt:'2026-10-05T12:30:00',cancellationReason:'independent cancel'});mock.method(globalThis,'fetch',async()=>new Response(JSON.stringify({success:true,data:f}),{status:200}));assert.deepEqual((await open.submitOpenIntent(claim())).coverage,f);assert.deepEqual(await open.getOpenCoverage(7),f)
})
test('withdrawal cannot acknowledge a claim coverage or another actor operation',async()=>{
  for(const change of [f=>f.replacement=closed().replacement,f=>f.request.claimedBy=3,f=>f.request.replacementShiftId=11,f=>f.operation.actorId=3]){const f=closed(withdraw());change(f);mock.method(globalThis,'fetch',async()=>new Response(JSON.stringify({success:true,data:f}),{status:200}));await assert.rejects(()=>open.submitOpenIntent(withdraw()),/不符/);mock.restoreAll()}
})
test('publication original receipt may already be closed but cannot move to another schedule',async()=>{
  const f=closed().request;mock.method(globalThis,'fetch',async()=>new Response(JSON.stringify({success:true,data:f}),{status:200}));assert.equal((await open.submitOpenIntent(publish())).row.status,'CLAIMED');f.scheduleId=2;await assert.rejects(()=>open.submitOpenIntent(publish()),/不符/)
})

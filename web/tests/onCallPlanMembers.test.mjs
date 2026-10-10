import {beforeEach,test,mock} from 'node:test'
import assert from 'node:assert/strict'
import {build} from 'esbuild'
import {fileURLToPath} from 'node:url'
const built=await build({entryPoints:[fileURLToPath(new URL('../src/services/onCallPlanMembers.ts',import.meta.url))],bundle:true,write:false,platform:'node',format:'esm'})
const members=await import(`data:text/javascript;base64,${Buffer.from(built.outputFiles[0].text).toString('base64')}`)
const uuid='2757c5b4-aa17-4f08-8368-4e8294e13628'
const row=()=>({scheduleId:1,userId:3,userName:'经理',active:true,canRespond:true,canManage:true,version:0,origin:'MIGRATED_GLOBAL_V38',accountStatus:'ACTIVE',roleCode:'OPS_MANAGER',createdAt:'2026-10-10T10:00:00',updatedAt:'2026-10-10T10:00:00',effectiveResponse:true,effectiveManagement:true})
const intent=()=>({schema:1,actorId:1,scheduleId:1,blocked:false,command:{userId:3,expectedVersion:0,active:true,canRespond:true,canManage:false,operationKey:uuid,reason:'独立响应授权'}})
const result=(i=intent())=>({current:{...row(),version:i.command.expectedVersion===null?0:i.command.expectedVersion+1,active:i.command.active,canRespond:i.command.canRespond,canManage:i.command.canManage,effectiveResponse:i.command.active&&i.command.canRespond,effectiveManagement:i.command.active&&i.command.canManage,updatedAt:'2026-10-10T11:00:00'},receipt:{...i.command,scheduleId:i.scheduleId,actorId:i.actorId,resultVersion:i.command.expectedVersion===null?0:i.command.expectedVersion+1,committedAt:'2026-10-10T11:00:00'}})
const storage=()=>{const data=new Map();return{data,getItem:k=>data.get(k)??null,setItem:(k,v)=>data.set(k,v),removeItem:k=>data.delete(k)}}
beforeEach(()=>{mock.restoreAll();globalThis.localStorage={getItem:k=>k==='opspilot_user'?JSON.stringify({id:1}):'unit-public-token'};globalThis.window=new EventTarget()})
const respond=data=>mock.method(globalThis,'fetch',async()=>new Response(JSON.stringify({success:true,data}),{status:200}))
test('explicit null first insert is distinct from missing, zero and saturated version',()=>{
  for(const v of [null,0,2147483646]){const i=intent();i.command.expectedVersion=v;assert.equal(members.memberIntentError(i),'')}
  for(const v of [undefined,-1,1.5,'0',2147483647,2147483648]){const i=intent();i.command.expectedVersion=v;assert.ok(members.memberIntentError(i))}
  const i=intent();delete i.command.expectedVersion;assert.ok(members.memberIntentError(i))
})
test('flags independent and tombstone may retain stored permissions',()=>{
  for(const [active,respond,manage]of [[true,true,false],[true,false,true],[false,true,true],[false,false,false]]){const i=intent();Object.assign(i.command,{active,canRespond:respond,canManage:manage});assert.equal(members.memberIntentError(i),'')}
  const i=intent();i.command.canRespond=false;assert.ok(members.memberIntentError(i))
})
test('strict actor plan target boolean canonical UUID and bounded trimmed reason',()=>{
  for(const change of [i=>i.actorId=0,i=>i.scheduleId='1',i=>i.command.userId=1.5,i=>i.blocked='false',i=>i.command.active=null,i=>i.command.operationKey=uuid.toUpperCase(),i=>i.command.reason=' ',i=>i.command.reason=' untrimmed',i=>i.command.reason='x'.repeat(501)]){const i=intent();change(i);assert.ok(members.memberIntentError(i))}
})
test('preflight scopes manager to exact plan, ADMIN recovery does not imply response',()=>{
  const i=intent();i.actorId=3;const manager=row();manager.canRespond=false;manager.effectiveResponse=false
  assert.equal(members.memberPreflightError(i,[manager],'OPS_MANAGER'),'')
  manager.canManage=false;manager.effectiveManagement=false;assert.match(members.memberPreflightError(i,[manager],'OPS_MANAGER'),/管理权限/)
  assert.equal(members.memberPreflightError(intent(),[manager],'ADMIN'),'')
  for(const role of ['ON_CALL','AUDITOR',undefined])assert.ok(members.memberPreflightError(intent(),[row()],role))
})
test('preflight first insert uses explicit absence; stale or tombstone not absent',()=>{
  const i=intent();i.command.expectedVersion=null;assert.equal(members.memberPreflightError(i,[],'ADMIN'),'')
  assert.match(members.memberPreflightError(i,[row()],'ADMIN'),/版本/);assert.match(members.memberPreflightError(intent(),[],'ADMIN'),/版本/)
  const r=row();r.active=false;r.effectiveResponse=false;r.effectiveManagement=false;assert.equal(members.memberPreflightError(intent(),[r],'ADMIN'),'')
})
test('contradictory effectiveness, duplicate/cross-plan and malformed facts rejected',()=>{
  for(const change of [r=>r.effectiveResponse=false,r=>r.scheduleId=2,r=>r.updatedAt='bad',r=>r.origin='unknown',r=>r.version=-1]){const r=row();change(r);assert.ok(members.memberPreflightError(intent(),[r],'ADMIN'))}
  assert.ok(members.memberPreflightError(intent(),[row(),row()],'ADMIN'))
})
test('ON_CALL cannot receive management flag; inactive target cannot be activated',()=>{
  const r=row();r.roleCode='ON_CALL';r.canManage=false;r.effectiveManagement=false;const i=intent();i.command.canManage=true
  assert.match(members.memberPreflightError(i,[r],'ADMIN'),/目标/);r.accountStatus='INACTIVE';r.effectiveResponse=false;i.command.canManage=false;assert.match(members.memberPreflightError(i,[r],'ADMIN'),/目标/)
  i.command.active=false;assert.equal(members.memberPreflightError(i,[r],'ADMIN'),'')
})
test('actor-scoped exact reliable storage round-trip, blocked state retained',()=>{
  const s=storage(),i=intent();i.blocked=true;members.saveMemberIntent(s,i);assert.deepEqual(members.readMemberIntent(s,1),i);assert.equal(members.readMemberIntent(s,3),null)
  members.clearMemberIntent(s,1);assert.equal(members.readMemberIntent(s,1),null)
})
test('quota silent save and readback failure never pass reliable-save gate',()=>{
  const i=intent();for(const s of [{setItem(){throw Error('quota')},getItem(){return null}},{setItem(){},getItem(){return null}},{setItem(){},getItem(){throw Error('denied')}}])assert.throws(()=>members.saveMemberIntent(s,i))
})
test('corrupt or foreign stored intent and denied reads fail closed',()=>{
  for(const raw of ['{','null',JSON.stringify({...intent(),actorId:3}),JSON.stringify({...intent(),command:{}})])assert.throws(()=>members.readMemberIntent({getItem:()=>raw},1))
  assert.throws(()=>members.readMemberIntent({getItem(){throw Error('denied')}},1))
})
test('remove failure silent no-op and readback denial retain original intent',()=>{
  for(const s of [{removeItem(){throw Error('denied')},getItem(){return null}},{removeItem(){},getItem(){return 'original'}},{removeItem(){},getItem(){throw Error('readback')}}])assert.throws(()=>members.clearMemberIntent(s,1))
})
test('manual ack restores only absent exact original after remove succeeded but readback failed',async()=>{
  const s=storage(),i=intent();members.saveMemberIntent(s,i);const get=s.getItem;s.getItem=()=>{throw Error('denied after remove')};assert.throws(()=>members.clearMemberIntent(s,1));s.getItem=get;assert.equal(members.readMemberIntent(s,1),null)
  members.restoreMemberIntentForManualAck(s,i);assert.deepEqual(members.readMemberIntent(s,1),i);respond(result());assert.deepEqual((await members.submitMemberIntent(s,i)).receipt,result().receipt)
})
test('manual recovery never overwrites a different frozen command or a blocked intent',()=>{
  const s=storage(),original=intent(),other=intent();other.command.reason='other';members.saveMemberIntent(s,other);assert.throws(()=>members.restoreMemberIntentForManualAck(s,original),/不同原意图/);assert.deepEqual(members.readMemberIntent(s,1),other)
  const blocked={...original,blocked:true};assert.throws(()=>members.restoreMemberIntentForManualAck(storage(),blocked),/锁定/)
})
test('manual recovery requires reliable storage again and denied reads never trigger a save',()=>{
  let writes=0;assert.throws(()=>members.restoreMemberIntentForManualAck({getItem(){throw Error('denied')},setItem(){writes++}},intent()));assert.equal(writes,0)
  assert.throws(()=>members.restoreMemberIntentForManualAck({getItem:()=>null,setItem(){}},intent()),/可靠保存/)
})
test('submit requires reliable exact original stored content and refuses blocked zero POST',async()=>{
  let calls=0;mock.method(globalThis,'fetch',async()=>{calls++;throw Error('must not send')});const s=storage(),i=intent()
  await assert.rejects(()=>members.submitMemberIntent(s,i));members.saveMemberIntent(s,i);i.command.reason='changed';await assert.rejects(()=>members.submitMemberIntent(s,i));i.blocked=true;await assert.rejects(()=>members.submitMemberIntent(s,i));assert.equal(calls,0)
})
test('captured member session never substitutes a different current token or actor zero POST',async()=>{
  let calls=0;mock.method(globalThis,'fetch',async()=>{calls++;throw Error('must not send')});const i=intent(),s=storage();members.saveMemberIntent(s,i)
  globalThis.localStorage={getItem:k=>k==='opspilot_user'?JSON.stringify({id:3}):'new-actor-token'};await assert.rejects(()=>members.submitMemberIntent(s,i,'unit-public-token'));assert.equal(calls,0)
})
test('member Authorization stays pinned if current token changes after the final synchronous check',async()=>{
  const i=intent(),s=storage();members.saveMemberIntent(s,i);let current='unit-public-token';globalThis.localStorage={getItem:k=>{if(k==='opspilot_user')return JSON.stringify({id:1});const read=current;current='replacement-token';return read}}
  mock.method(globalThis,'fetch',async(_url,options)=>{assert.equal(options.headers.get('Authorization'),'Bearer unit-public-token');assert.equal(current,'replacement-token');return new Response(JSON.stringify({success:true,data:result()}))})
  assert.deepEqual((await members.submitMemberIntent(s,i,'unit-public-token')).receipt,result().receipt)
})
test('POST transmits exact nullable original command, not auto-rebased current version',async()=>{
  const i=intent();i.command.expectedVersion=null;const s=storage();members.saveMemberIntent(s,i);let body
  mock.method(globalThis,'fetch',async(url,options)=>{assert.equal(url,'/api/v1/on-call/schedules/1/members');body=JSON.parse(options.body);return new Response(JSON.stringify({success:true,data:result(i)}))})
  assert.deepEqual(await members.submitMemberIntent(s,i),result(i));assert.deepEqual(body,i.command);assert.ok(Object.hasOwn(body,'expectedVersion'));assert.equal(body.expectedVersion,null)
})
test('immutable original receipt survives higher current version and revoked privileges',async()=>{
  const i=intent(),s=storage();members.saveMemberIntent(s,i);const r=result();Object.assign(r.current,{version:2,active:false,canRespond:false,canManage:false,effectiveResponse:false,effectiveManagement:false,updatedAt:'2026-10-10T12:00:00'});respond(r)
  assert.deepEqual((await members.submitMemberIntent(s,i)).receipt,result().receipt);assert.deepEqual(members.readMemberIntent(s,1),i)
})
test('wrong actor plan original command result version timestamp or missing receipt rejected',async()=>{
  for(const change of [r=>delete r.receipt,r=>r.receipt.actorId=3,r=>r.receipt.scheduleId=2,r=>r.receipt.userId=2,r=>delete r.receipt.expectedVersion,r=>r.receipt.expectedVersion=null,r=>r.receipt.resultVersion=2,r=>r.receipt.reason='other',r=>r.receipt.operationKey=uuid.toUpperCase(),r=>r.receipt.canManage=true,r=>r.receipt.committedAt='bad']){
    mock.restoreAll();const i=intent(),s=storage();members.saveMemberIntent(s,i);const r=result();change(r);respond(r);await assert.rejects(()=>members.submitMemberIntent(s,i),/原回执/);assert.deepEqual(members.readMemberIntent(s,1),i)
  }
})
test('current row cannot precede receipt or contradict flags at same version',async()=>{
  for(const change of [r=>r.current.version=0,r=>r.current.scheduleId=2,r=>r.current.userId=2,r=>r.current.updatedAt='2026-10-10T10:30:00',r=>{r.current.canManage=true;r.current.effectiveManagement=true}]){
    mock.restoreAll();const i=intent(),s=storage();members.saveMemberIntent(s,i);const r=result();change(r);respond(r);await assert.rejects(()=>members.submitMemberIntent(s,i),/原回执/)
  }
})
test('member list only contains matching actual member rows, validates duplicates',async()=>{
  respond([row()]);assert.deepEqual(await members.listPlanMembers(1),[row()]);mock.restoreAll();respond([row(),row()]);await assert.rejects(()=>members.listPlanMembers(1));await assert.rejects(()=>members.listPlanMembers(0))
})
test('plan options do not turn global users into members or tolerate duplicate IDs',async()=>{
  const schedules=[{id:1,name:'生产',resourceName:'系统'}];respond({schedules,users:[{id:99}]});assert.deepEqual(await members.listMemberPlans(),schedules)
  mock.restoreAll();respond({schedules:[...schedules,...schedules]});await assert.rejects(()=>members.listMemberPlans())
})

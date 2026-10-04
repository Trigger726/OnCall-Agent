import {beforeEach,test,mock} from 'node:test'
import assert from 'node:assert/strict'
import {build} from 'esbuild'
import {fileURLToPath} from 'node:url'
const built=await build({entryPoints:[fileURLToPath(new URL('../src/services/onCallSwapRevocations.ts',import.meta.url))],bundle:true,write:false,platform:'node',format:'esm'})
const pair=await import(`data:text/javascript;base64,${Buffer.from(built.outputFiles[0].text).toString('base64')}`)
const facts=()=>({databaseNow:'2026-10-04T12:00:00',accepted:{id:7,requesterId:2,targetUserId:3,status:'ACCEPTED',version:1,firstScheduleId:1,secondScheduleId:2,firstReplacementShiftId:21,secondReplacementShiftId:22,firstStartsAt:'2026-10-05T10:00:00',firstEndsAt:'2026-10-05T12:00:00',secondStartsAt:'2026-10-06T10:00:00',secondEndsAt:'2026-10-06T12:00:00'},
  firstReplacement:{id:21,scheduleId:1,userId:3,version:0,startsAt:'2026-10-05T10:00:00',endsAt:'2026-10-05T12:00:00',cancelledAt:null,cancellationReason:null},
  secondReplacement:{id:22,scheduleId:2,userId:2,version:0,startsAt:'2026-10-06T10:00:00',endsAt:'2026-10-06T12:00:00',cancelledAt:null,cancellationReason:null},revocation:null})
const draft=()=>({schema:1,actorId:1,swapId:7,firstReplacementId:21,secondReplacementId:22,command:{swapVersion:1,firstReplacementVersion:0,secondReplacementVersion:0,operationKey:'2757c5b4-aa17-4f08-8368-4e8294e13628',reason:'原两段说明'},blocked:false})
const storage=()=>{const values=new Map();return {getItem:k=>values.get(k)??null,setItem:(k,v)=>values.set(k,v),removeItem:k=>values.delete(k),values}}
const eligible=f=>pair.pairRevocationEligibility(f,7,1,'ADMIN')===''
beforeEach(()=>{mock.restoreAll();globalThis.localStorage={getItem:()=> 'unit-public-token'};globalThis.window=new EventTarget()})
test('only managers may create paired intent; participants and readers cannot substitute',()=>{
  for(const role of ['ADMIN','OPS_MANAGER'])assert.equal(pair.pairRevocationEligibility(facts(),7,1,role),'')
  for(const role of ['ON_CALL','AUDITOR','USER',undefined])assert.ok(pair.pairRevocationEligibility(facts(),7,2,role))
  for(const actor of [undefined,0,NaN,1.5])assert.ok(pair.pairRevocationEligibility(facts(),7,actor,'ADMIN'))
})
test('historical acceptance does not imply complete or live paired coverage',()=>{
  for(const change of [f=>f.accepted.status='PENDING',f=>f.accepted.status='WITHDRAWN',f=>f.firstReplacement=null,f=>f.secondReplacement=null,f=>f.revocation={},f=>delete f.revocation,f=>f.accepted.id=8]){const f=facts();change(f);assert.equal(eligible(f),false)}
  assert.equal(eligible(null),false)
})
test('every replacement identity and original exchanged participant/schedule must match',()=>{
  for(const change of [f=>f.firstReplacement.id=22,f=>f.firstReplacement.id=99,f=>f.secondReplacement.userId=3,f=>f.firstReplacement.userId=2,f=>f.secondReplacement.scheduleId=1,f=>f.accepted.targetUserId=2,f=>f.accepted.firstReplacementShiftId=null]){const f=facts();change(f);assert.equal(eligible(f),false)}
})
test('either cancelled or unknown cancellation blocks a new pair without repairing counterpart',()=>{
  for(const field of ['firstReplacement','secondReplacement'])for(const value of ['2026-10-04T11:00:00',undefined]){const f=facts();f[field].cancelledAt=value;assert.equal(eligible(f),false)}
})
test('already started remaining pair is eligible but either actual end is not',()=>{
  const f=facts();f.databaseNow='2026-10-05T11:00:00';assert.equal(eligible(f),true)
  f.databaseNow=f.firstReplacement.endsAt;assert.equal(eligible(f),false)
  f.databaseNow='2026-10-06T12:00:00';assert.equal(eligible(f),false)
})
test('strict naive database timestamp comparison retains fractional precision without browser clock',()=>{
  const f=facts();f.databaseNow='2026-10-05T12:00:00';f.firstReplacement.endsAt=f.accepted.firstEndsAt='2026-10-05T12:00:00.000001';assert.equal(eligible(f),true)
  f.databaseNow='2026-10-05T12:00:00.000001000';assert.equal(eligible(f),false)
  for(const bad of ['2026-02-29T12:00:00','2026-10-04T24:00:00','2026-10-04T12:60:00','2026-10-04T12:00:00Z','2026-10-04T12:00:00+08:00','bad',undefined]){const f=facts();f.databaseNow=bad;assert.equal(eligible(f),false)}
  mock.method(Date,'now',()=>{throw Error('Browser wall clock must not decide')});assert.equal(eligible(facts()),true)
})
test('equivalent timestamp precision matches snapshots but changed intervals fail closed',()=>{
  const f=facts();f.firstReplacement.startsAt+='.000';assert.equal(eligible(f),true)
  f.firstReplacement.endsAt='2026-10-05T12:00:00.000001';assert.equal(eligible(f),false)
  f.firstReplacement.startsAt=f.firstReplacement.endsAt;assert.equal(eligible(f),false)
})
test('three explicit integer versions and overflow protection are mandatory',()=>{
  for(const name of ['swapVersion','firstReplacementVersion','secondReplacementVersion'])for(const bad of [undefined,null,-1,1.5,2147483648,'0'])assert.ok(pair.pairCommandError({...draft().command,[name]:bad}))
  for(const name of ['firstReplacement','secondReplacement']){const f=facts();f[name].version=2147483647;assert.equal(eligible(f),false)}
  assert.equal(pair.pairCommandError(draft().command),'')
})
test('canonical stable key and bounded original reason are required',()=>{
  for(const change of [{operationKey:'X'},{operationKey:draft().command.operationKey.toUpperCase()},{reason:''},{reason:'  '},{reason:'a'.repeat(501)}])assert.ok(pair.pairCommandError({...draft().command,...change}))
})
test('all original versions and both IDs must match; no silent version rebase',()=>{
  assert.equal(pair.sameCapturedPair(facts(),draft()),true)
  for(const change of [f=>f.accepted.version++,f=>f.firstReplacement.version++,f=>f.secondReplacement.version++,f=>f.firstReplacement.id++,f=>f.secondReplacement.id++,f=>f.accepted.id++]){const f=facts();change(f);assert.equal(pair.sameCapturedPair(f,draft()),false)}
})
test('per-actor original intent survives storage with no API or automatic retry',()=>{
  let requests=0;mock.method(globalThis,'fetch',()=>{requests++;throw Error('No network permitted')});const s=storage(),d=draft();pair.savePairIntent(s,d);assert.deepEqual(pair.readPairIntent(s,1),d);assert.equal(pair.readPairIntent(s,3),null);assert.equal(requests,0)
  pair.savePairIntent(s,{...d,actorId:3});pair.clearPairIntent(s,1);assert.equal(pair.readPairIntent(s,1),null);assert.equal(pair.readPairIntent(s,3).command.operationKey,d.command.operationKey)
})
test('corrupt foreign missing identity or versions remain stored and fail closed',()=>{
  for(const d of ['{bad',{...draft(),schema:2},{...draft(),actorId:3},{...draft(),blocked:1},{...draft(),secondReplacementId:21},{...draft(),command:{...draft().command,swapVersion:undefined}}]){const s=storage(),raw=typeof d==='string'?d:JSON.stringify(d);s.setItem('opspilot_swap_pair_revocation:v1:1',raw);assert.throws(()=>pair.readPairIntent(s,1));assert.equal(s.getItem('opspilot_swap_pair_revocation:v1:1'),raw)}
})
test('persisted blocked original survives read; storage errors propagate without discard',()=>{
  const s=storage(),d={...draft(),blocked:true};pair.savePairIntent(s,d);assert.deepEqual(pair.readPairIntent(s,1),d)
  assert.throws(()=>pair.savePairIntent({setItem:()=>{throw Error('quota')}},draft()),/quota/)
  assert.throws(()=>pair.readPairIntent({getItem:()=>{throw Error('denied')}},1),/denied/)
  assert.throws(()=>pair.clearPairIntent({removeItem:()=>{throw Error('denied')}},1),/denied/)
})
test('API transmits only original explicit command and uses the keyed swap endpoint',async()=>{
  const calls=[];mock.method(globalThis,'fetch',async(url,init)=>{calls.push({url,init});return new Response(JSON.stringify({success:true,data:facts()}),{status:200})});const d=draft();await pair.revokeSwapPair(d);assert.equal(calls[0].url,'/api/v1/on-call/swaps/7/coverage/revoke');assert.deepEqual(JSON.parse(calls[0].init.body),d.command);assert.equal(calls[0].init.method,'POST')
  await pair.getSwapPair(7);assert.equal(calls[1].url,'/api/v1/on-call/swaps/7/coverage');assert.equal(calls[1].init.method,undefined)
})
test('read identity mismatch is rejected rather than publishing foreign pair facts',async()=>{
  mock.method(globalThis,'fetch',async()=>new Response(JSON.stringify({success:true,data:{...facts(),accepted:{...facts().accepted,id:99}}}),{status:200}));await assert.rejects(()=>pair.getSwapPair(7),/身份异常/)
})

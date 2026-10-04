import { beforeEach, test, mock } from 'node:test'
import assert from 'node:assert/strict'
import { build } from 'esbuild'
import { fileURLToPath } from 'node:url'

const built = await build({entryPoints:[fileURLToPath(new URL('../src/services/onCallSwaps.ts',import.meta.url))],bundle:true,write:false,platform:'node',format:'esm'})
const swaps = await import(`data:text/javascript;base64,${Buffer.from(built.outputFiles[0].text).toString('base64')}`)
const first = () => ({id:7,scheduleId:1,scheduleName:'核心',userId:2,userName:'张伟',version:4,startsAt:'2026-10-05T08:00:00',endsAt:'2026-10-05T16:00:00',override:false,cancelledAt:null})
const second = () => ({...first(),id:8,scheduleId:2,scheduleName:'支付',userId:3,userName:'李娜',version:9,startsAt:'2026-10-06T08:00:00',endsAt:'2026-10-06T16:00:00'})
const command = () => ({firstShiftId:7,firstVersion:4,secondShiftId:8,secondVersion:9,requestKey:'a87265ae-afcc-04f6-6ce7-d27c0c79464d',reason:'双方交换'})
const row = () => ({id:12,requesterId:2,targetUserId:3,firstScheduleId:1,secondScheduleId:2,firstShiftId:7,secondShiftId:8,firstVersion:4,secondVersion:9,
  firstStartsAt:first().startsAt,firstEndsAt:first().endsAt,secondStartsAt:second().startsAt,secondEndsAt:second().endsAt,status:'PENDING',version:5})
const requested = () => ({schema:1,actorId:2,blocked:false,kind:'REQUEST',first:first(),second:second(),command:command()})
const decided = () => ({schema:1,actorId:3,blocked:false,kind:'DECISION',row:row(),command:{version:5,status:'ACCEPTED',reason:'一起确认两段'}})
const storage = () => {const values=new Map();return {setItem:(k,v)=>values.set(k,v),getItem:k=>values.get(k)??null,removeItem:k=>values.delete(k),values}}
beforeEach(()=>{mock.restoreAll();globalThis.localStorage={getItem:()=> 'test-token'};globalThis.window=new EventTarget()})
test('entry only permits actual eligible owner of complete future ordinary source',()=>{
  for(const role of ['ADMIN','OPS_MANAGER','ON_CALL']) assert.equal(swaps.canRequestSwap(first(),2,role,'2026-10-05T07:00:00'),true)
  for(const [change,actor,role,now] of [[{},1,'ADMIN','2026-10-05T07:00'],[{},2,'AUDITOR','2026-10-05T07:00'],[{override:true},2,'ON_CALL','2026-10-05T07:00'],[{cancelledAt:'time'},2,'ON_CALL','2026-10-05T07:00'],[{},2,'ON_CALL','2026-10-05T08:00:00'],[{},2,'ON_CALL',''],[{version:-1},2,'ON_CALL','2026-10-05T07:00']]) assert.equal(swaps.canRequestSwap({...first(),...change},actor,role,now),false)
})
test('candidates allow same or different plans but never owner, same source, started or cancelled overrides',()=>{
  assert.equal(swaps.canChooseSwap(second(),first(),2,'2026-10-05T07:00'),true)
  assert.equal(swaps.canChooseSwap({...second(),scheduleId:1},first(),2,'2026-10-05T07:00'),true)
  for(const change of [{id:7},{userId:2},{override:true},{cancelledAt:'x'},{startsAt:'2026-10-05T06:00:00'}]) assert.equal(swaps.canChooseSwap({...second(),...change},first(),2,'2026-10-05T07:00'),false)
})
test('actual participants only; either started source removes accept but retains reject/withdraw',()=>{
  assert.deepEqual(swaps.swapActions(row(),3,'ON_CALL','2026-10-05T07:00'),['ACCEPTED','REJECTED'])
  assert.deepEqual(swaps.swapActions(row(),2,'ON_CALL','2026-10-07T07:00'),['WITHDRAWN'])
  for(const now of ['2026-10-05T08:00:00','2026-10-06T08:00:00','']) assert.deepEqual(swaps.swapActions(row(),3,'ON_CALL',now),['REJECTED'])
  for(const [actor,role] of [[1,'ADMIN'],[3,'AUDITOR']]) assert.deepEqual(swaps.swapActions(row(),actor,role,'2026-10-05T07:00'),[])
  assert.deepEqual(swaps.swapActions({...row(),status:'ACCEPTED'},3,'ON_CALL','2026-10-05T07:00'),[])
})
test('accepted history never claims coverage is still active and clocks stay database-local',()=>{
  assert.equal(swaps.swapState({...row(),status:'ACCEPTED'},'2026-10-05T07:00'),'已接受 · 历史决定')
  assert.equal(swaps.swapState(row(),'2026-10-05T08:00:00'),'待处理 · 源班次已开始')
  assert.equal(swaps.swapClock('2026-10-05T08:00:12.123456'),'2026-10-05 08:00:12')
})
test('request keeps both explicit versions/canonical key; wrong snapshots and invalid reasons fail',()=>{
  assert.equal(swaps.swapRequestError(command(),first(),second(),2),'')
  for(const change of [{firstShiftId:8},{secondShiftId:7},{firstVersion:0},{secondVersion:10},{requestKey:'BAD'},{reason:' '},{reason:'字'.repeat(501)}]) assert.ok(swaps.swapRequestError({...command(),...change},first(),second(),2))
  assert.ok(swaps.swapRequestError(command(),first(),{...second(),userId:2},2))
})
test('request and decision intents restore exact contents and conflict lock per actor, without credentials',()=>{
  const store=storage(); const request={...requested(),blocked:true}, decision={...decided(),blocked:true}
  swaps.saveSwapIntent(store,request);swaps.saveSwapIntent(store,decision)
  assert.deepEqual(swaps.readSwapIntent(store,2),request);assert.deepEqual(swaps.readSwapIntent(store,3),decision);assert.equal(swaps.readSwapIntent(store,1),null)
  assert.doesNotMatch([...store.values.values()].join(),/test-token|Bearer|accessToken/)
  swaps.clearSwapIntent(store,3);assert.equal(swaps.readSwapIntent(store,3),null);assert.deepEqual(swaps.readSwapIntent(store,2),request)
})
test('corrupt, foreign, missing source facts and malformed decision drafts are retained and fail closed',()=>{
  const bad=[{...requested(),actorId:3},{...requested(),blocked:'no'},{...requested(),kind:'BAD'},
    {...requested(),first:{...first(),version:-1}},{...requested(),second:null},
    {...decided(),command:{...decided().command,version:6}},{...decided(),row:{...row(),firstEndsAt:undefined}},
    {...decided(),row:{...row(),secondScheduleId:0}},{...decided(),row:{...row(),status:'ACCEPTED'}}]
  for(const data of bad){const raw=JSON.stringify(data),store={getItem:()=>raw};assert.throws(()=>swaps.readSwapIntent(store,data.kind==='DECISION'?3:2));assert.equal(store.getItem(),raw)}
  assert.throws(()=>swaps.readSwapIntent({getItem:()=>'{broken'},2))
})
test('storage write/remove failures propagate and cannot silently rotate an intent',()=>{
  assert.throws(()=>swaps.saveSwapIntent({setItem:()=>{throw Error('quota')}},requested()),/quota/)
  assert.throws(()=>swaps.clearSwapIntent({removeItem:()=>{throw Error('denied')}},2),/denied/)
})
test('decisions validate captured version and bounded reason, never imply missing version zero',()=>{
  assert.equal(swaps.swapDecisionError(decided().command),'')
  for(const change of [{version:undefined},{version:-1},{version:1.5},{status:'PENDING'},{reason:' '},{reason:'长'.repeat(501)}]) assert.ok(swaps.swapDecisionError({...decided().command,...change}))
})
test('list filters both-side plan/scope/status on server and retains truncation',async()=>{
  const urls=[];mock.method(globalThis,'fetch',async url=>{urls.push(url);return Response.json({success:true,data:{requests:[],truncated:true}})})
  assert.equal((await swaps.listSwaps('2','MINE','PENDING')).truncated,true);await swaps.listSwaps('','ALL','')
  assert.deepEqual(urls,['/api/v1/on-call/swaps?scope=MINE&scheduleId=2&status=PENDING','/api/v1/on-call/swaps?scope=ALL'])
})
test('ambiguous request and decision responses have no auto-retry; manual retry keeps exact bodies',async()=>{
  for(const [data,action,route] of [[command(),d=>swaps.requestSwap(d),'/api/v1/on-call/swaps'],[decided().command,d=>swaps.decideSwap(12,d),'/api/v1/on-call/swaps/12/decisions']]){
    const bodies=[],original=structuredClone(data),fetcher=mock.method(globalThis,'fetch',async(url,init)=>{assert.equal(url,route);bodies.push(init.body);throw TypeError('lost')})
    await assert.rejects(action(data),/lost/);assert.equal(fetcher.mock.callCount(),1);await assert.rejects(action(data),/lost/);assert.equal(bodies[0],bodies[1]);assert.deepEqual(data,original);fetcher.mock.restore()
  }
})
test('403/409 preserve captured content and do not automatically rebase',async()=>{
  const original=decided().command
  for(const status of [403,409]){const fetcher=mock.method(globalThis,'fetch',async()=>Response.json({success:false,error:{code:'CONFLICT',message:'核对'}},{status}));await assert.rejects(swaps.decideSwap(12,original),{status});assert.equal(fetcher.mock.callCount(),1);assert.deepEqual(original,decided().command);fetcher.mock.restore()}
})
test('detail and current coverage are independent read-only requests with exact database-local window',async()=>{
  const urls=[];mock.method(globalThis,'fetch',async(url,init)=>{assert.equal(init.method,undefined);urls.push(url);return Response.json({success:true,data:{}})})
  await swaps.getSwap(12);await swaps.getSwapCoverage(2,second().startsAt,second().endsAt)
  assert.equal(urls[0],'/api/v1/on-call/swaps/12');assert.equal(urls[1],'/api/v1/on-call/coverage?scheduleId=2&from=2026-10-06T08%3A00%3A00&to=2026-10-06T16%3A00%3A00')
})

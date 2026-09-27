import { beforeEach, test, mock } from 'node:test'
import assert from 'node:assert/strict'
import { build } from 'esbuild'
import { fileURLToPath } from 'node:url'

const built = await build({ entryPoints: [fileURLToPath(new URL('../src/services/onCallHandoffs.ts', import.meta.url))], bundle: true, write: false, platform: 'node', format: 'esm' })
const handoffs = await import(`data:text/javascript;base64,${Buffer.from(built.outputFiles[0].text).toString('base64')}`)
const source = () => ({ id: 7, scheduleId: 1, scheduleName: '值班', userId: 2, userName: '张伟', startsAt: '2026-09-27T08:00', endsAt: '2026-09-27T16:00', override: false, version: 4, cancelledAt: null })
const command = () => ({ sourceShiftId: 7, sourceVersion: 4, targetUserId: 3, requestKey: 'a87265ae-afcc-04f6-6ce7-d27c0c79464d', startsAt: '2026-09-27T08:00:00', endsAt: '2026-09-27T16:00:00', reason: '协调接班' })
const row = () => ({ id: 9, status: 'PENDING', requesterId: 2, targetUserId: 3, endsAt: '2026-09-27T16:00:00', version: 5 })
beforeEach(() => { mock.restoreAll(); globalThis.localStorage = { getItem: () => 'test-token' }; globalThis.window = new EventTarget() })
test('source entry requires eligible owner, ordinary noncancelled and unexpired source', () => {
  for (const role of ['ADMIN','OPS_MANAGER','ON_CALL']) assert.equal(handoffs.canRequestHandoff(source(),2,role,'2026-09-27T09:00:00'),true)
  for (const [change,id,role,now] of [[{},1,'ADMIN','2026-09-27T09:00'],[{},2,'AUDITOR','2026-09-27T09:00'],[{override:true},2,'ON_CALL','2026-09-27T09:00'],[{cancelledAt:'2026-09-27T08:01'},2,'ON_CALL','2026-09-27T09:00'],[{},2,'ON_CALL','2026-09-27T16:00'],[{},2,'ON_CALL','']]) {
    assert.equal(handoffs.canRequestHandoff({...source(),...change},id,role,now),false)
  }
})
test('only actual participants get actions; expired pending permits reject/withdraw but never accept', () => {
  assert.deepEqual(handoffs.handoffActions(row(),2,'ON_CALL','2026-09-27T09:00'),['WITHDRAWN'])
  assert.deepEqual(handoffs.handoffActions(row(),3,'OPS_MANAGER','2026-09-27T09:00'),['ACCEPTED','REJECTED'])
  assert.deepEqual(handoffs.handoffActions(row(),1,'ADMIN','2026-09-27T09:00'),[])
  assert.deepEqual(handoffs.handoffActions(row(),3,'AUDITOR','2026-09-27T09:00'),[])
  assert.deepEqual(handoffs.handoffActions(row(),3,'OPS_MANAGER','2026-09-27T16:00:00'),['REJECTED'])
  assert.deepEqual(handoffs.handoffActions(row(),2,'ON_CALL','2026-09-28T09:00'),['WITHDRAWN'])
  assert.deepEqual(handoffs.handoffActions({...row(),status:'ACCEPTED'},3,'OPS_MANAGER','2026-09-27T09:00'),[])
})
test('clock stays zone-less with seconds and expiry never invents a persisted EXPIRED state', () => {
  assert.equal(handoffs.handoffClock('2026-09-27T08:00:12.123'),'2026-09-27 08:00:12')
  assert.equal(handoffs.handoffTime('2026-09-27T08:00'),'2026-09-27T08:00:00')
  assert.equal(handoffs.handoffState(row(),'2026-09-27T16:00:00.000001'),'待处理 · 时段已结束')
  assert.equal(handoffs.handoffState({...row(),status:'ACCEPTED'},'2026-09-28T09:00'),'已接受')
})
test('canonical draft rejects wrong source/version, self target and out-of-window/fractional timestamps', () => {
  assert.equal(handoffs.handoffDraftError(command(),source(),2),'')
  for (const change of [{sourceShiftId:8},{sourceVersion:5},{targetUserId:2},{targetUserId:0},{requestKey:'BAD'},
    {startsAt:'2026-09-27T07:59:59'},{endsAt:'2026-09-27T16:00:01'},{startsAt:'2026-09-27T08:00:00.1'},
    {startsAt:'2026-09-27T16:00:00'},{reason:' '},{reason:'长'.repeat(501)}]) assert.ok(handoffs.handoffDraftError({...command(),...change},source(),2))
})
test('storage restores exact frozen command per account and never includes credentials', () => {
  const values = new Map(), storage = {setItem:(k,v)=>values.set(k,v),getItem:k=>values.get(k)??null,removeItem:k=>values.delete(k)}
  const saved = {schema:1,actorId:2,source:source(),command:command()}
  handoffs.saveHandoffDraft(storage,saved)
  assert.deepEqual(handoffs.readHandoffDraft(storage,2),saved)
  assert.equal(handoffs.readHandoffDraft(storage,3),null)
  assert.doesNotMatch([...values.values()].join(),/test-token|Bearer|accessToken/)
  handoffs.clearHandoffDraft(storage,3)
  assert.deepEqual(handoffs.readHandoffDraft(storage,2),saved)
  handoffs.clearHandoffDraft(storage,2)
  assert.equal(handoffs.readHandoffDraft(storage,2),null)
})
test('corrupt or foreign drafts fail without erasing recovery data; storage failures propagate', () => {
  const raw = JSON.stringify({schema:1,actorId:3,source:source(),command:command()})
  const storage = {getItem:()=>raw}
  assert.throws(()=>handoffs.readHandoffDraft(storage,2),/草稿损坏/)
  assert.equal(storage.getItem(),raw)
  assert.throws(()=>handoffs.saveHandoffDraft({setItem:()=>{throw Error('quota')}},{schema:1,actorId:2,source:source(),command:command()}),/quota/)
})
test('reads send server-side scope/status filters; all status is omitted and truncation retained', async () => {
  const urls = []
  mock.method(globalThis,'fetch',async url=>{urls.push(url);return Response.json({success:true,data:{requests:[],truncated:true}})})
  assert.equal((await handoffs.listHandoffs('1','MINE','PENDING')).truncated,true)
  await handoffs.listHandoffs('','ALL','')
  assert.deepEqual(urls,['/api/v1/on-call/handoffs?scope=MINE&scheduleId=1&status=PENDING','/api/v1/on-call/handoffs?scope=ALL'])
})
test('ambiguous request failure does not retry, change key or mutate the caller payload', async () => {
  const data = command(), bodies = []
  const fetcher = mock.method(globalThis,'fetch',async (url,init)=>{assert.equal(url,'/api/v1/on-call/handoffs');bodies.push(init.body);throw TypeError('network lost')})
  await assert.rejects(handoffs.requestHandoff(data),/network lost/)
  assert.equal(fetcher.mock.callCount(),1)
  await assert.rejects(handoffs.requestHandoff(data),/network lost/)
  assert.equal(bodies[0],bodies[1])
  assert.deepEqual(data,command())
})
test('decision keeps captured version/reason; 409 propagates without rebasing or automatic retry', async () => {
  const data = {version:5,status:'ACCEPTED',reason:'同意'}
  const fetcher = mock.method(globalThis,'fetch',async (url,init)=>{assert.equal(url,'/api/v1/on-call/handoffs/9/decisions');assert.deepEqual(JSON.parse(init.body),data);return Response.json({success:false,error:{code:'ONCALL_HANDOFF_VERSION_CONFLICT',message:'请核对'}},{status:409})})
  await assert.rejects(handoffs.decideHandoff(9,data),{status:409,code:'ONCALL_HANDOFF_VERSION_CONFLICT'})
  assert.equal(fetcher.mock.callCount(),1)
  assert.equal(data.version,5)
})
test('401 raises auth expiry once without rotating the idempotency key', async () => {
  let expired = 0
  window.addEventListener('opspilot-auth-expired',()=>expired++)
  const data = command()
  const fetcher = mock.method(globalThis,'fetch',async ()=>Response.json({success:false},{status:401}))
  await assert.rejects(handoffs.requestHandoff(data),{status:401,code:'AUTHENTICATION_REQUIRED'})
  assert.equal(expired,1); assert.equal(fetcher.mock.callCount(),1); assert.equal(data.requestKey,command().requestKey)
})

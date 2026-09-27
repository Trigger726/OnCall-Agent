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

const coverage = () => ({ databaseNow:'2026-09-27T09:00:00.123456', request:{...row(),status:'ACCEPTED',replacementShiftId:12},
  replacement:{id:12,userId:3,version:0,startsAt:'2026-09-27T08:00:00',endsAt:'2026-09-27T16:00:00',cancelledAt:null,cancellationReason:null},revocation:null })
const revocationCommand = () => ({handoffVersion:5,replacementVersion:0,operationKey:command().requestKey,reason:'管理确认撤销'})
const savedRevocation = () => ({schema:1,actorId:1,handoffId:9,command:revocationCommand(),blocked:false})
test('coverage display separates accepted consent from scheduled/ended/cancelled coverage facts', () => {
  assert.equal(handoffs.coverageState(coverage()),'覆盖时段内 · 生效须核对日历')
  assert.equal(handoffs.coverageState({...coverage(),databaseNow:'2026-09-27T07:00:00'}),'覆盖尚未开始')
  assert.equal(handoffs.coverageState({...coverage(),databaseNow:'2026-09-27T16:00:00'}),'覆盖时段已结束')
  assert.equal(handoffs.coverageState({...coverage(),replacement:{...coverage().replacement,cancelledAt:'2026-09-27T09:00:00'}}),'覆盖已取消')
  assert.equal(handoffs.coverageState({...coverage(),replacement:null}),'未生成覆盖')
  assert.equal(coverage().request.status,'ACCEPTED')
})
test('new revocation requires management and a fresh accepted unended uncancelled replacement', () => {
  for (const role of ['ADMIN','OPS_MANAGER']) assert.equal(handoffs.canRevokeCoverage(coverage(),role),true)
  for (const role of ['ON_CALL','AUDITOR',undefined]) assert.equal(handoffs.canRevokeCoverage(coverage(),role),false)
  for (const change of [{databaseNow:''},{databaseNow:'2026-09-27T16:00:00'},{replacement:null},
    {request:{...coverage().request,status:'PENDING'}},{replacement:{...coverage().replacement,cancelledAt:'2026-09-27T09:00:00'}},
    {revocation:{actorId:1}}]) assert.equal(handoffs.canRevokeCoverage({...coverage(),...change},'ADMIN'),false)
})
test('revocation payload validates captured versions, canonical key and bounded reason', () => {
  assert.equal(handoffs.revocationCommandError(revocationCommand()),'')
  for (const change of [{handoffVersion:-1},{handoffVersion:1.5},{replacementVersion:-1},{operationKey:'BAD'},{reason:' '},{reason:'长'.repeat(501)}])
    assert.ok(handoffs.revocationCommandError({...revocationCommand(),...change}))
})
test('revocation storage restores exact versions/key/reason and persisted conflict lock per actor', () => {
  const values = new Map(), storage = {setItem:(k,v)=>values.set(k,v),getItem:k=>values.get(k)??null,removeItem:k=>values.delete(k)}
  const saved = {...savedRevocation(),blocked:true}
  handoffs.saveRevocationDraft(storage,saved)
  assert.deepEqual(handoffs.readRevocationDraft(storage,1),saved)
  assert.equal(handoffs.readRevocationDraft(storage,3),null)
  assert.doesNotMatch([...values.values()].join(),/test-token|Bearer|accessToken/)
  handoffs.clearRevocationDraft(storage,3); assert.deepEqual(handoffs.readRevocationDraft(storage,1),saved)
  handoffs.clearRevocationDraft(storage,1); assert.equal(handoffs.readRevocationDraft(storage,1),null)
})
test('corrupt revocation storage is retained and quota failure propagates before any request', () => {
  for (const change of [{schema:2},{actorId:3},{handoffId:0},{blocked:'no'},{command:{...revocationCommand(),replacementVersion:-1}}]) {
    const raw = JSON.stringify({...savedRevocation(),...change}), storage = {getItem:()=>raw}
    assert.throws(()=>handoffs.readRevocationDraft(storage,1),/撤销草稿损坏/); assert.equal(storage.getItem(),raw)
  }
  assert.throws(()=>handoffs.saveRevocationDraft({setItem:()=>{throw Error('quota')}},savedRevocation()),/quota/)
})
test('coverage read uses the independent snapshot endpoint without mutation', async () => {
  const fetcher = mock.method(globalThis,'fetch',async (url,init)=>{
    assert.equal(url,'/api/v1/on-call/handoffs/9/coverage'); assert.equal(init.method,undefined)
    return Response.json({success:true,data:coverage()})
  })
  assert.deepEqual(await handoffs.getHandoffCoverage(9),coverage()); assert.equal(fetcher.mock.callCount(),1)
})
test('revocation ambiguous response manually retries exact frozen payload only', async () => {
  const data = revocationCommand(), bodies = []
  const fetcher = mock.method(globalThis,'fetch',async (url,init)=>{assert.equal(url,'/api/v1/on-call/handoffs/9/coverage/revoke');bodies.push(init.body);throw TypeError('lost')})
  await assert.rejects(handoffs.revokeHandoffCoverage(9,data),/lost/); assert.equal(fetcher.mock.callCount(),1)
  await assert.rejects(handoffs.revokeHandoffCoverage(9,data),/lost/)
  assert.equal(bodies[0],bodies[1]); assert.deepEqual(data,revocationCommand())
})
test('revocation 409 and 403 preserve captured intent without rotating versions or key', async () => {
  const data = revocationCommand()
  for (const status of [409,403]) {
    const fetcher = mock.method(globalThis,'fetch',async ()=>Response.json({success:false,error:{code:'CONFLICT',message:'核对'}},{status}))
    await assert.rejects(handoffs.revokeHandoffCoverage(9,data),{status}); assert.equal(fetcher.mock.callCount(),1)
    fetcher.mock.restore(); assert.deepEqual(data,revocationCommand())
  }
})

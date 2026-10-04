import {beforeEach,test,mock} from 'node:test'
import assert from 'node:assert/strict'
import {build} from 'esbuild'
import {fileURLToPath} from 'node:url'
const built=await build({entryPoints:[fileURLToPath(new URL('../src/services/onCallSwapNotifications.ts',import.meta.url))],bundle:true,write:false,platform:'node',format:'esm'})
const notifications=await import(`data:text/javascript;base64,${Buffer.from(built.outputFiles[0].text).toString('base64')}`)
const draft=()=>({schema:1,actorId:2,swapId:7,id:12,version:3,reason:'只重试原通知',blocked:false})
const storage=()=>{const values=new Map();return{setItem:(k,v)=>values.set(k,v),getItem:k=>values.get(k)??null,removeItem:k=>values.delete(k),values}}
beforeEach(()=>{mock.restoreAll();globalThis.localStorage={getItem:()=> 'test-token'};globalThis.window=new EventTarget()})
test('technical delivered and historical decisions never imply human read or consent',()=>{
  assert.match(notifications.notificationState({status:'DELIVERED'}),/技术送达.*不代表人工已读/)
  assert.match(notifications.notificationState({status:'CLAIMED'}),/尚无回执/)
  assert.match(notifications.notificationState({status:'SKIPPED'}),/不再投递/)
})
test('retry is enabled only for failed delivery and manager or actual participant',()=>{
  for(const [actor,role] of [[1,'ADMIN'],[5,'OPS_MANAGER'],[2,'ON_CALL'],[3,'ON_CALL']])assert.equal(notifications.canRetryNotification({status:'FAILED'},true,actor,role,2,3),true)
  for(const [state,enabled,actor,role] of [['DELIVERED',true,1,'ADMIN'],['PENDING',true,2,'ON_CALL'],['CLAIMED',true,1,'ADMIN'],['SKIPPED',true,1,'ADMIN'],['FAILED',false,1,'ADMIN'],['FAILED',true,8,'ON_CALL'],['FAILED',true,2,'AUDITOR']])assert.equal(notifications.canRetryNotification({status:state},enabled,actor,role,2,3),false)
})
test('explicit valid captured version and nonempty bounded reason are required',()=>{
  assert.equal(notifications.notificationRetryError(draft()),'')
  for(const change of [{version:-1},{version:undefined},{version:1.5},{reason:''},{reason:' '.repeat(4)},{reason:'a'.repeat(501)},{id:0},{swapId:0},{actorId:0}])assert.ok(notifications.notificationRetryError({...draft(),...change}))
})
test('original retry survives read and is isolated by actor without network side effects',()=>{
  const s=storage(),value=draft();notifications.saveNotificationRetry(s,value);assert.deepEqual(notifications.readNotificationRetry(s,2),value);assert.equal(notifications.readNotificationRetry(s,3),null)
  notifications.saveNotificationRetry(s,{...value,actorId:3,id:99});notifications.clearNotificationRetry(s,2);assert.equal(notifications.readNotificationRetry(s,2),null);assert.equal(notifications.readNotificationRetry(s,3).id,99)
})
test('corrupt or mismatched storage remains present and is rejected rather than reset',()=>{
  for(const value of ['{bad',JSON.stringify({...draft(),schema:2}),JSON.stringify({...draft(),actorId:3}),JSON.stringify({...draft(),blocked:1}),JSON.stringify({...draft(),version:undefined})]){
    const s=storage();s.setItem('opspilot_swap_notification_retry:v1:2',value);assert.throws(()=>notifications.readNotificationRetry(s,2));assert.equal(s.getItem('opspilot_swap_notification_retry:v1:2'),value)
  }
})
test('persisted 403/409 lock cannot be silently discarded on refresh',()=>{
  const s=storage(),value={...draft(),blocked:true};notifications.saveNotificationRetry(s,value);assert.deepEqual(notifications.readNotificationRetry(s,2),value)
})
test('storage quota failure is surfaced before an unrecorded request can proceed',()=>{
  assert.throws(()=>notifications.saveNotificationRetry({setItem:()=>{throw new Error('controlled quota')}},draft()),/quota/)
})
test('read only GET and explicit retry use original body, not latest server version',async()=>{
  const requests=[];mock.method(globalThis,'fetch',async(input,init)=>{requests.push({input,init});return new Response(JSON.stringify({success:true,data:{status:'PENDING',version:9}}),{status:200,headers:{'Content-Type':'application/json'}})})
  await notifications.getSwapNotifications(7);await notifications.retrySwapNotification(draft());assert.equal(requests.length,2);assert.equal(requests[0].input,'/api/v1/on-call/swaps/7/notifications');assert.notEqual(requests[0].init.method,'POST')
  assert.equal(requests[1].input,'/api/v1/on-call/swaps/7/notifications/12/retry');assert.equal(requests[1].init.method,'POST');assert.deepEqual(JSON.parse(requests[1].init.body),{version:3,reason:'只重试原通知'})
})
test('failed retry does not automatically post a second time or mutate original payload',async()=>{
  const value=draft();let calls=0;mock.method(globalThis,'fetch',async()=>{calls++;return new Response(JSON.stringify({success:false,error:{code:'ONCALL_SWAP_NOTIFICATION_NOT_RETRYABLE',message:'原版本冲突'}}),{status:409,headers:{'Content-Type':'application/json'}})})
  await assert.rejects(()=>notifications.retrySwapNotification(value));assert.equal(calls,1);assert.deepEqual(value,draft())
})

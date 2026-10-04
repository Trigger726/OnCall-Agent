const {test}=require('node:test'),assert=require('node:assert/strict');
const {configuration,retentionConfiguration,notificationObservation,notificationCaseNames}=require('../verify-oncall-swap-notification-ci.cjs');
test('CI cannot replace notification acceptance with archived baseline',()=>assert.throws(()=>configuration({CI:'true',OPSPILOT_SWAP_NOTIFICATION_BASELINE:'1'})));
test('normal CI and explicit local archived comparison remain distinct',()=>{assert.deepEqual(configuration({CI:'true'}),{baseline:false});assert.deepEqual(configuration({OPSPILOT_SWAP_NOTIFICATION_BASELINE:'1'}),{baseline:true});});
test('retention UI archived baseline cannot replace actual CI acceptance',()=>{assert.throws(()=>retentionConfiguration({CI:'true',OPSPILOT_SWAP_NOTIFICATION_UI_BASELINE:'1'}));assert.deepEqual(retentionConfiguration({CI:'true'}),{uiBaseline:false});});
test('observation preserves request identity across actual phases and distinguishes concurrent equal paths',()=>{
  const observer=notificationObservation('http://127.0.0.1:9975'),request=()=>({url:()=> 'http://127.0.0.1:9975/api/v1/on-call/swaps/2/notifications',method:()=> 'GET'}),a=request(),b=request();
  observer.record('request',a);observer.record('request',b);observer.record('response',b,200);observer.record('finished',a);
  assert.deepEqual(observer.events.map(e=>[e.phase,e.requestId,e.status]),[['request',1,undefined],['request',2,undefined],['response',2,200],['finished',1,undefined]]);
  assert.ok(observer.events.every(e=>e.elapsedMs>=0));assert.equal(observer.omitted,0);
});
test('observation excludes headers body query fragments credentials unrelated routes and other origins',()=>{
  const observer=notificationObservation('http://127.0.0.1:9975'),request=(url)=>({url:()=>url,method:()=> 'POST',headers:()=>{throw Error('Never inspect authorization')},postData:()=>{throw Error('Never inspect command')}});
  observer.record('request',request('http://unused:secret@127.0.0.1:9975/api/v1/on-call/swaps/3/notifications/4/retry?token=private#private'));
  observer.record('request',request('http://127.0.0.1:9975/api/v1/auth/login'));observer.record('request',request('http://elsewhere/api/v1/on-call/swaps/3/notifications'));
  assert.equal(observer.events.length,1);assert.equal(observer.events[0].path,'/api/v1/on-call/swaps/3/notifications/4/retry');assert.ok(!/private|secret|unused|authorization/.test(JSON.stringify(observer.events)));
});
test('observation has a hard event bound with explicit omission instead of unbounded logs',()=>{
  const observer=notificationObservation('http://127.0.0.1:9975',2),request={url:()=> 'http://127.0.0.1:9975/api/v1/on-call/swaps/3/coverage',method:()=> 'GET'};
  for(const phase of ['request','response','finished','request'])observer.record(phase,request);
  assert.equal(observer.events.length,2);assert.equal(observer.omitted,2);
});
test('default notification CI retains all twelve original full case identifiers',()=>{
  const fs=require('node:fs'),path=require('node:path'),proof=JSON.parse(fs.readFileSync(path.join(__dirname,'../../docs/assets/v1.7-cp94/local-proof.json'),'utf8'));
  assert.deepEqual(notificationCaseNames,proof.originalFlows.find(f=>f.scope==='notification').result.cases.map(c=>c.name));assert.equal(notificationCaseNames.length,12);
});

const {test}=require('node:test'),assert=require('node:assert/strict');
const {pairObservation,abortCommand}=require('../oncall-ui-interception.cjs');
function fixture(status=200){const calls=[],request={url:()=> 'http://127.0.0.1:9973/api/v1/on-call/swaps/4/coverage/revoke',method:()=> 'POST'};let handler;
  const route={request:()=>request,fetch:async()=>{calls.push('actual-fetch');return{status:()=>status};},abort:async()=>{calls.push('abort');}};
  const page={route:async(pattern,h)=>{calls.push('registered');handler=h;},unroute:async(pattern,h)=>{assert.equal(h,handler);calls.push('removed');}};
  return{calls,route,page,trigger:async()=>{calls.push('clicked');await handler(route);},settled:async()=>{assert.ok(!calls.includes('removed'));calls.push('read-settled');}};
}
test('unforwarded abort stays registered through facts read and never forwards the command',async()=>{
  const f=fixture();const result=await abortCommand(f.page,'owned-pattern',f.trigger,f.settled);
  assert.deepEqual(f.calls,['registered','clicked','abort','read-settled','removed']);assert.equal(result.actualForwardedCommit200,false);assert.equal(result.posts,1);
});
test('committed response loss requires actual 200 before abort and removal after read',async()=>{
  const f=fixture(),observer=pairObservation('http://127.0.0.1:9973');const result=await abortCommand(f.page,'owned-pattern',f.trigger,f.settled,{commit:true,record:observer.record});
  assert.deepEqual(f.calls,['registered','clicked','actual-fetch','abort','read-settled','removed']);assert.equal(result.actualForwardedCommit200,true);
  assert.deepEqual(observer.events.map(e=>[e.phase,e.status]),[['committed-fetch-start',undefined],['committed-fetch-end',200],['abort-complete',undefined]]);
});
test('forwarding error cannot produce a passing receipt or a settled-read label',async()=>{
  const f=fixture(409);await assert.rejects(abortCommand(f.page,'owned-pattern',f.trigger,f.settled,{commit:true}));assert.ok(!f.calls.includes('read-settled'));assert.ok(!f.calls.includes('removed'));
});
test('missing interception is bounded and cannot remove a route as if the command completed',async()=>{
  const f=fixture();await assert.rejects(abortCommand(f.page,'owned-pattern',async()=>{},f.settled,{timeoutMs:5}),/did not complete/);assert.deepEqual(f.calls,['registered']);
});
test('pair trace filters origin and path, never accesses command headers/body or stores query credentials',()=>{
  const observer=pairObservation('http://127.0.0.1:9973'),request=url=>({url:()=>url,method:()=> 'GET',headers:()=>{throw Error('secret header');},postData:()=>{throw Error('secret body');}});
  const a=request('http://unused:secret@127.0.0.1:9973/api/v1/on-call/swaps/4/coverage?token=private#private'),b=request('http://127.0.0.1:9973/api/v1/on-call/swaps/4/coverage');
  observer.record('request',a);observer.record('request',b);observer.record('response',a,200);observer.record('request',request('http://other/api/v1/on-call/swaps/4/coverage'));observer.record('request',request('http://127.0.0.1:9973/api/v1/auth/login'));
  assert.deepEqual(observer.events.map(e=>e.requestId),[1,2,1]);assert.ok(!/secret|private|unused/.test(JSON.stringify(observer.events)));assert.equal(observer.events.length,3);
});
test('bounded pair trace counts omissions without claiming a complete observation',()=>{
  const observer=pairObservation('http://127.0.0.1:9973',2),request={url:()=> 'http://127.0.0.1:9973/api/v1/on-call/swaps/4/coverage',method:()=> 'GET'};
  for(const phase of ['request','response','finished'])observer.record(phase,request);assert.equal(observer.events.length,2);assert.equal(observer.omitted,1);
});

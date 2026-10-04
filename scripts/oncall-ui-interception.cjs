const assert=require('node:assert/strict');

function pairObservation(base,limit=512){
  const events=[],ids=new WeakMap();let next=0,omitted=0;const started=Date.now();
  function record(phase,request,status){const url=new URL(request.url());if(url.origin!==base||!/^\/api\/v1\/on-call\/swaps\/[1-9]\d*\/coverage(?:\/revoke)?$/.test(url.pathname))return;
    if(!ids.has(request))ids.set(request,++next);if(events.length===limit){omitted++;return;}
    const event={phase,requestId:ids.get(request),method:request.method(),path:url.pathname,elapsedMs:Date.now()-started};if(Number.isInteger(status))event.status=status;events.push(event);}
  return {events,record,get omitted(){return omitted;}};
}

// Keep the interception registered through the post-error facts GET. A browser
// abort without commit is intentionally distinct from actual commit + lost reply.
async function abortCommand(page,pattern,trigger,readSettled,{commit=false,record=()=>{},timeoutMs=12000}={}){
  let calls=0,resolve,reject,timer;const done=new Promise((r,j)=>{resolve=r;reject=j;});done.catch(()=>{});
  const handler=async route=>{try{assert.equal(++calls,1,'No automatic second POST during aborted command');const request=route.request();
    if(commit){record('committed-fetch-start',request);const actual=await route.fetch();assert.equal(actual.status(),200);record('committed-fetch-end',request,actual.status());}
    else record('unforwarded-abort-start',request);
    await route.abort('failed');record('abort-complete',request);resolve();
  }catch(e){reject(e);await route.abort('failed').catch(()=>{});}};
  await page.route(pattern,handler);
  try{await trigger();await Promise.race([done,new Promise((_,j)=>{timer=setTimeout(()=>j(Error('Owned command interception did not complete')),timeoutMs);})]);}
  finally{clearTimeout(timer);}
  await readSettled();assert.equal(calls,1);await page.unroute(pattern,handler);
  return {posts:calls,actualForwardedCommit200:commit,browserRequestAborted:true,removedAfterReadSettled:true};
}
module.exports={pairObservation,abortCommand};

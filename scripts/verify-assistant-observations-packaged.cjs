// Acceptance-only: two fresh H2 databases, actual Prometheus/Loki HTTP providers, original UI/SSE.
const fs=require('node:fs'),path=require('node:path'),os=require('node:os'),assert=require('node:assert/strict'),crypto=require('node:crypto'),http=require('node:http');
const {spawn}=require('node:child_process'),{chromium}=require('./browser/node_modules/playwright'),{javaArgs,free,assetFiles}=require('./verify-ui-packaged.cjs');
const root=path.resolve(__dirname,'..'),sha=b=>crypto.createHash('sha256').update(b).digest('hex'),delay=ms=>new Promise(r=>setTimeout(r,ms));
const out=fs.mkdtempSync(path.join(os.tmpdir(),'opspilot-observations-')),question='查看当前 Incident 的最新指标和日志';
const report={status:'RUNNING',out,question,aiEnabled:false,realCloud:false,userDatabaseModified:false,browser:'Existing Playwright 1.63.0 + Chrome; Browser plugin unavailable',providerCalls:[],providerErrors:[],runs:[]};
let mode='samples',runName='';
const provider=http.createServer((req,res)=>{
 try{
 const url=new URL(req.url,'http://localhost');report.providerCalls.push({run:runName,mode,path:url.pathname,params:Object.fromEntries(url.searchParams)});
 res.setHeader('Content-Type','application/json');
 if(mode==='failed'){res.statusCode=503;return res.end(JSON.stringify({status:'error',message:'controlled unavailable'}))}
 const empty=mode==='empty';
 if(url.pathname==='/api/v1/query'){
  assert.equal(url.searchParams.get('query'),'up{job="APP-SETTLEMENT"}');
  return res.end(JSON.stringify({status:'success',data:{resultType:'vector',result:empty?[]:[{metric:{__name__:'cp03_cpu_usage',job:'APP-SETTLEMENT',api_key:'CP03_SECRET_SENTINEL'},value:[Number(url.searchParams.get('time'))-1,'83.25']}]}}));
 }
 assert.equal(url.pathname,'/loki/api/v1/query_range');assert.equal(url.searchParams.get('query'),'{resource_code="APP-SETTLEMENT"}');assert.equal(url.searchParams.get('limit'),'20');
 const at=(BigInt(url.searchParams.get('end'))-1000000000n).toString();
 res.end(JSON.stringify({status:'success',data:{resultType:'streams',result:empty?[]:[{stream:{resource_code:'APP-SETTLEMENT',level:'ERROR',job:'settlement',token:'CP03_SECRET_SENTINEL'},values:[[at,JSON.stringify({message:'CP03 controlled timeout token=CP03_SECRET_SENTINEL',traceId:'cp03-trace',password:'CP03_SECRET_SENTINEL'})]]}]}}));
 }catch(error){report.providerErrors.push({run:runName,message:error.message});res.statusCode=500;res.end(JSON.stringify({status:'error',message:'Fixture assertion failed; see QA report'}))}
});
async function request(url,options={}){return fetch(url,{...options,signal:AbortSignal.timeout(10000)})}
function exitOf(child){return new Promise(r=>{child.once('error',e=>r({error:e.message}));child.once('close',(code,signal)=>r({code,signal}))})}
async function one(name,relativeJar,expected){
 runName=name;mode='samples';const dir=path.join(out,name);fs.mkdirSync(dir);const jar=path.resolve(root,relativeJar),hash=sha(fs.readFileSync(jar));
 const base='http://127.0.0.1:9913',management='http://127.0.0.1:9914',providerBase='http://127.0.0.1:'+provider.address().port;
 const entry={name,jar,jarSha256:hash,base,status:'RUNNING',screenshots:[],errors:[],assets:[]};report.runs.push(entry);
 let child,closed,browser,fd,admin,page;
 const calls=()=>report.providerCalls.filter(c=>c.run===name).length;
 const api=async(route,token,body,key)=>{const res=await request(base+'/api/v1'+route,{method:body?'POST':'GET',headers:{'Content-Type':'application/json',...(token?{Authorization:'Bearer '+token}:{}),...(key?{'Idempotency-Key':key}:{})},...(body?{body:JSON.stringify(body)}:{})});assert.equal(res.status,200,route);return{res,data:(await res.json()).data}};
 const newSession=async(token,incidentId)=>(await api('/assistant/sessions',token,{incidentId:incidentId??null})).data.session.id;
 try{
  await free(9913);await free(9914);fd=fs.openSync(path.join(dir,'backend.log'),'w');
  const args=javaArgs(jar,dir,9913);args.push('--opspilot.observability.prometheus.enabled=true','--opspilot.observability.prometheus.base-url='+providerBase,'--opspilot.observability.loki.enabled=true','--opspilot.observability.loki.base-url='+providerBase,'--opspilot.observability.reliability.max-attempts=1');
  child=spawn(path.join(process.env.JAVA_HOME,'bin/java.exe'),args,{cwd:dir,env:{...process.env,JWT_SECRET:crypto.randomBytes(48).toString('hex')},stdio:['ignore',fd,fd],windowsHide:true});closed=exitOf(child);
  const deadline=Date.now()+60000;while(true){assert.equal(await Promise.race([closed,delay(0).then(()=>null)]),null,'JAR exited');try{const res=await request(management+'/actuator/health');if(res.ok&&(await res.json()).status==='UP')break}catch{}assert.ok(Date.now()<deadline,'Health deadline');await delay(500)}
  admin=(await api('/auth/login',null,{username:'admin',password:'OpsPilot@2026'})).data.accessToken;
  for(const file of assetFiles(fs.readFileSync(path.join(root,'src/main/resources/static/index.html'),'utf8'))){const res=await request(base+'/'+(file==='index.html'?'':file));assert.equal(res.status,200);const h=sha(Buffer.from(await res.arrayBuffer()));assert.equal(h,sha(fs.readFileSync(path.join(root,'src/main/resources/static',file))));entry.assets.push({file,sha256:h})}
  browser=await chromium.launch({executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true});
  const context=await browser.newContext({viewport:{width:1440,height:1000},reducedMotion:'reduce'});await context.addInitScript(()=>{
   localStorage.setItem('opspilot_theme','light');const original=window.fetch;
   window.fetch=async(...args)=>{const response=await original(...args);if(response.url.endsWith('/stream')&&response.ok)response.clone().text().then(body=>window.__observationsStream={body}).catch(error=>window.__observationsStream={error:String(error)});return response};
  });
  page=await context.newPage();page.on('pageerror',e=>entry.errors.push({type:'page',message:e.message}));page.on('console',m=>{if(['error','warning'].includes(m.type()))entry.errors.push({type:m.type(),message:m.text()})});page.on('response',r=>{if(r.status()>=400)entry.errors.push({type:'http',status:r.status(),url:r.url()})});
  await page.goto(base+'/login',{waitUntil:'networkidle'});await page.locator('input[autocomplete="username"]').fill('zhangwei');await page.locator('input[autocomplete="current-password"]').fill('OpsPilot@2026');await page.getByRole('button',{name:'进入控制台',exact:true}).click();await page.waitForURL(base+'/');
  const token=await page.evaluate(()=>localStorage.getItem('opspilot_token')),session=await newSession(token,1);
  await page.goto(base+'/assistant?session='+session,{waitUntil:'networkidle'});assert.equal(new URL(page.url()).pathname,'/assistant');assert.equal(Number(new URL(page.url()).searchParams.get('session')),session);assert.equal(await page.title(),'OpsPilot 智能运维平台');assert.equal(await page.locator('vite-error-overlay').count(),0);assert.ok((await page.locator('.assistant-conversation').innerText()).length>30);
  await page.locator('.assistant-composer textarea').fill(question);const pending=page.waitForResponse(r=>r.url().endsWith('/stream')&&r.request().method()==='POST');await page.getByTitle('发送消息',{exact:true}).click();const response=await pending;
  assert.equal(response.status(),200);assert.match(response.headers()['content-type'],/text\/event-stream/);await page.waitForFunction(()=>window.__observationsStream);const stream=await page.evaluate(()=>window.__observationsStream);assert.equal(stream.error,undefined);
  const events=stream.body.split(/\r?\n/).filter(l=>l.startsWith('data:')).map(l=>JSON.parse(l.slice(5).trim()));const done=events.find(e=>e.type==='done');assert.ok(done?.messageId);
  const content=events.filter(e=>['delta','token'].includes(e.type)).map(e=>e.content).join(''),refs=JSON.parse(done.evidenceJson),operational=refs.filter(r=>['METRIC','LOG'].includes(r.type));
  assert.equal(operational.length,expected?2:0);assert.equal(calls(),expected?2:0);entry.latestCalls=calls();entry.answer=content;entry.operationalEvidence=operational;
  if(expected){assert.ok(content.includes('cp03_cpu_usage=83.25'));assert.ok(content.includes('CP03 controlled timeout token=***'));assert.ok(content.includes('最新近 30 分钟'));assert.ok(content.includes('Prometheus 使用窗口末端 instant query'));assert.ok(!content.includes('CP03_SECRET_SENTINEL'));assert.ok(!done.evidenceJson.includes('CP03_SECRET_SENTINEL'));for(const ref of operational){assert.equal(ref.snapshot.window,'RECENT_30_MINUTES');assert.ok(Date.parse(ref.snapshot.observedAt)>=Date.parse(ref.snapshot.windowStart));assert.ok(Date.parse(ref.snapshot.observedAt)<=Date.parse(ref.snapshot.windowEnd))}}
  else{assert.ok(!content.includes('cp03_cpu_usage'));assert.ok(content.includes('关联告警'))}
  await page.locator('.assistant-message.assistant .assistant-copy').last().waitFor();const answer=page.locator('.assistant-message.assistant .chat-markdown').last();assert.equal((await answer.innerText()).includes('cp03_cpu_usage=83.25'),expected);
  const saved=(await api('/assistant/sessions/'+session,token)).data.messages.find(m=>m.id===done.messageId);assert.equal(saved.content,content);assert.deepEqual(JSON.parse(saved.evidenceJson),refs);entry.streamEqualsSaved=true;
  const key=response.request().headers()['idempotency-key'];assert.ok(key);const replay=await api('/assistant/sessions/'+session+'/messages',token,{content:question},key);assert.equal(replay.res.headers.get('x-opspilot-idempotent-replay'),'true');assert.deepEqual(replay.data,saved);assert.equal(calls(),entry.latestCalls);entry.replayWithoutProviderCall=true;
  const normalId=await newSession(token,1),normal=(await api('/assistant/sessions/'+normalId+'/messages',token,{content:question},crypto.randomUUID())).data;
  assert.equal(normal.content.includes('cp03_cpu_usage=83.25'),expected);assert.equal(JSON.parse(normal.evidenceJson).filter(r=>['METRIC','LOG'].includes(r.type)).length,expected?2:0);assert.equal(calls(),expected?4:0);entry.normalGrounded=true;
  await page.reload({waitUntil:'networkidle'});await page.locator('.assistant-message.assistant .assistant-copy').last().waitFor();assert.equal((await page.locator('.assistant-message.assistant .chat-markdown').last().innerText()).includes('cp03_cpu_usage=83.25'),expected);assert.equal(Number(new URL(page.url()).searchParams.get('session')),session);entry.refreshPersisted=true;
  for(const[width,height,theme]of[[1440,1000,'light'],[390,844,'dark']]){await page.setViewportSize({width,height});if(theme==='dark')await page.getByRole('button',{name:'切换到深色主题',exact:true}).click();assert.equal(await page.locator('html').getAttribute('data-theme'),theme);assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),'Horizontal overflow');await page.locator('.assistant-message-viewport').evaluate(el=>el.scrollTop=0);const file=path.join(dir,`latest-${theme}-${width}.png`);await page.screenshot({path:file,fullPage:false});entry.screenshots.push(file)}
  if(expected){
   const before=calls(),unbound=await newSession(token),without=(await api('/assistant/sessions/'+unbound+'/messages',token,{content:question})).data;assert.ok(without.content.includes('未绑定 Incident，未查询 Provider'));assert.equal(calls(),before);entry.unboundNoProvider=true;
   const unsupported=await newSession(token,1),range=(await api('/assistant/sessions/'+unsupported+'/messages',token,{content:'过去24小时指标日志'})).data;assert.ok(range.content.includes('暂不支持，未查询 Provider'));assert.equal(calls(),before);entry.unsupportedNoProvider=true;
   const auditor=(await api('/auth/login',null,{username:'auditor',password:'OpsPilot@2026'})).data.accessToken,auditSession=await newSession(auditor,1),denied=(await api('/assistant/sessions/'+auditSession+'/messages',auditor,{content:question})).data;assert.ok(denied.content.includes('无运维取证权限'));assert.equal(calls(),before);entry.auditorNoProvider=true;
   mode='empty';const emptyId=await newSession(token,1),empty=(await api('/assistant/sessions/'+emptyId+'/messages',token,{content:question})).data;assert.equal(calls(),before+2);assert.ok(empty.content.includes('METRIC · EMPTY'));assert.ok(empty.content.includes('LOG · EMPTY'));assert.ok(empty.content.includes('不代表系统健康'));assert.equal(JSON.parse(empty.evidenceJson).filter(r=>['METRIC','LOG'].includes(r.type)).length,0);entry.emptyNotHealth=true;
   mode='failed';const faultId=await newSession(token,1),fallback=(await api('/assistant/sessions/'+faultId+'/messages',token,{content:'查询故障窗口指标日志'})).data;assert.equal(calls(),before+4);assert.ok(fallback.content.includes('local-metrics'));assert.ok(fallback.content.includes('local-logs'));assert.ok(fallback.content.includes('prometheus-metrics unavailable'));assert.ok(fallback.content.includes('loki-logs unavailable'));assert.ok(fallback.content.includes('redis_pool_pending=84'));assert.ok(fallback.content.includes('Incident 故障窗口（不是实时）'));assert.ok(!fallback.content.includes('fake-demo-token-123'));entry.providerFailoverDisclosed=true;entry.fallbackAnswer=fallback.content;
  }
  entry.providerCalls=calls();assert.deepEqual(entry.errors,[]);entry.status='PASS';
 }catch(error){entry.status='FAIL';entry.failure=error.stack;if(page)try{const file=path.join(dir,'failure.png');await page.screenshot({path:file});entry.screenshots.push(file)}catch{}throw error}
 finally{
  if(browser)await browser.close();
  if(child){try{if(admin&&child.exitCode===null){const res=await request(management+'/actuator/shutdown',{method:'POST',headers:{Authorization:'Bearer '+admin}});assert.equal(res.status,200);entry.shutdown=true}let timer;const result=await Promise.race([closed,new Promise(r=>timer=setTimeout(()=>r({error:'Shutdown deadline'}),10000))]);clearTimeout(timer);if(result.error){child.kill();await closed;throw Error(result.error)}assert.equal(result.code,0);entry.childExit=result.code}finally{if(child.exitCode===null&&child.signalCode===null){child.kill();await closed}}}
  if(fd!==undefined)fs.closeSync(fd);assert.equal(sha(fs.readFileSync(jar)),hash);await free(9913);await free(9914);entry.portsFree=true;
 }
}
(async()=>{assert.equal(process.argv.length,6,'Usage --before JAR --after JAR');assert.equal(process.argv[2],'--before');assert.equal(process.argv[4],'--after');await new Promise(r=>provider.listen(0,'127.0.0.1',r));const providerPort=provider.address().port;try{await one('before',process.argv[3],false);await one('after',process.argv[5],true);assert.deepEqual(report.providerErrors,[]);report.status='PASS'}catch(error){report.status='FAIL';report.failure=error.stack;process.exitCode=1}finally{await new Promise(r=>provider.close(r));await free(providerPort);report.providerPortFree=true;fs.writeFileSync(path.join(out,'qa.json'),JSON.stringify(report,null,2)+'\n');console.log(JSON.stringify({status:report.status,out,providerCalls:report.providerCalls.length,runs:report.runs.map(r=>({name:r.name,status:r.status,providerCalls:r.providerCalls,shutdown:r.shutdown,portsFree:r.portsFree})),failure:report.failure}))}})();

// Real SDK/local provider, two immutable JARs and fresh H2 databases. No paid AI or user data.
const fs=require('node:fs'),path=require('node:path'),os=require('node:os'),http=require('node:http'),crypto=require('node:crypto'),assert=require('node:assert/strict');
const {spawn}=require('node:child_process'),{chromium}=require('./browser/node_modules/playwright'),{javaArgs,free,assetFiles}=require('./verify-ui-packaged.cjs');
const root=path.resolve(__dirname,'..'),sha=b=>crypto.createHash('sha256').update(b).digest('hex'),delay=ms=>new Promise(r=>setTimeout(r,ms));
const out=fs.mkdtempSync(path.join(os.tmpdir(),'opspilot-grounding-'));
const VALID='runbook:legacy-runbook-2:v1#chunk-0',BAD='runbook:legacy-runbook-2:v99#chunk-0',SECRET='CP02_PRIVATE_SENTINEL';
const report={status:'RUNNING',out,browser:'Browser plugin not available; existing Playwright/Chrome',realCloudModel:false,userDatabaseModified:false,runs:[],providerCalls:[]};
let currentRun;
const completion=(content,reason)=>({output:{choices:[{finish_reason:reason,message:{role:'assistant',content}}]},usage:{input_tokens:1,output_tokens:1,total_tokens:2},request_id:'cp02-local-controlled'});
const provider=http.createServer(async(req,res)=>{
 try{
  assert.equal(req.url,'/api/v1/services/aigc/text-generation/generation');let raw='';for await(const chunk of req){raw+=chunk;assert.ok(raw.length<262144)}
  const body=JSON.parse(raw),text=body.input.messages.find(m=>m.role==='user').content;
  const question=text.split('\n\n值班工程师问题:\n').at(-1),start=text.indexOf('Runbook 检索数据'),historyAt=text.indexOf('\n\n最近对话:',start);
  const knowledge=JSON.parse(text.slice(text.indexOf('\n',start)+1,historyAt));
  const history=text.slice(historyAt,text.indexOf('\n\n值班工程师问题:',historyAt));
  const stream=req.headers['x-dashscope-sse']==='enable';
  report.providerCalls.push({run:currentRun,question,stream,historyContainsPrivate:history.includes(SECRET),historyMasked:history.includes('历史知识库回答未送入模型'),citations:knowledge.results.map(r=>r.citation),incremental:body.parameters?.incremental_output});
  let parts;
  if(question.includes('GUARD_BAD'))parts=['安全说明\nrun','book:legacy-runbook-2:v99#chunk-0'];
  else if(question.includes('GUARD_GOOD')){assert.ok(knowledge.results.some(r=>r.citation===VALID));parts=['İ 已知资料 [run','book:legacy-runbook-2:v1#chunk-','0]。\n']}
  else if(question.includes('HISTORY_FIRST')){const result=knowledge.results.find(r=>r.stableKey==='cp02-history');assert.ok(result?.excerpt.includes(SECRET));parts=['原始资料 '+SECRET+' ['+result.citation+']']}
  else if(question.includes('HISTORY_SECOND')){assert.equal(knowledge.results.length,0);parts=['当前问题没有可见的已发布资料，历史权限已重新核对。']}
  else throw Error('Unexpected controlled question');
  if(stream){assert.equal(body.parameters.incremental_output,true);res.writeHead(200,{'Content-Type':'text/event-stream'});for(const part of parts){res.write('data:'+JSON.stringify(completion(part,'null'))+'\n\n');await delay(20)}res.end('data:'+JSON.stringify(completion('','stop'))+'\n\n')}
  else{res.writeHead(200,{'Content-Type':'application/json'});res.end(JSON.stringify(completion(parts.join(''),'stop')))}
 }catch(error){report.providerFailure=error.stack;if(!res.headersSent)res.writeHead(500);res.end()}
});
async function request(url,options={}){return fetch(url,{...options,signal:AbortSignal.timeout(15000)})}
const exitOf=child=>new Promise(r=>{child.once('error',error=>r({error:error.message}));child.once('close',(code,signal)=>r({code,signal}))});
async function one(name,relativeJar,guarded){
 currentRun=name;const dir=path.join(out,name);fs.mkdirSync(dir);const jar=path.resolve(root,relativeJar),jarHash=sha(fs.readFileSync(jar));
 const base='http://127.0.0.1:9913',management='http://127.0.0.1:9914',providerBase='http://127.0.0.1:'+provider.address().port;
 const entry={name,jar,jarSha256:jarHash,base,status:'RUNNING',screenshots:[],errors:[],assets:[],guarded};report.runs.push(entry);
 let child,closed,fd,admin,browser,page;
 const api=async(route,token,body,key,expected=200)=>{const res=await request(base+'/api/v1'+route,{method:body?'POST':'GET',headers:{'Content-Type':'application/json',...(token?{Authorization:'Bearer '+token}:{}),...(key?{'Idempotency-Key':key}:{})},...(body?{body:JSON.stringify(body)}:{})});assert.equal(res.status,expected,route);return{res,json:await res.json()}};
 const newSession=async token=>(await api('/assistant/sessions',token,{})).json.data.session.id;
 try{
  await free(9913);await free(9914);fd=fs.openSync(path.join(dir,'backend.log'),'w');
  const args=javaArgs(jar,dir,9913).filter(arg=>arg!=='--opspilot.ai.enabled=false');args.push('--opspilot.ai.enabled=true','--spring.ai.dashscope.api-key=local-controlled-not-real','--spring.ai.dashscope.base-url='+providerBase,'--spring.ai.dashscope.chat.base-url='+providerBase);
  child=spawn(path.join(process.env.JAVA_HOME,'bin/java.exe'),args,{cwd:dir,env:{...process.env,JWT_SECRET:crypto.randomBytes(48).toString('hex')},stdio:['ignore',fd,fd],windowsHide:true});closed=exitOf(child);
  const ready=Date.now()+60000;while(true){assert.equal(await Promise.race([closed,delay(0).then(()=>null)]),null,'JAR exited');try{const res=await request(management+'/actuator/health');if(res.ok&&(await res.json()).status==='UP')break}catch{}assert.ok(Date.now()<ready,'Readiness deadline');await delay(500)}
  const login=async username=>(await api('/auth/login',null,{username,password:'OpsPilot@2026'})).json.data.accessToken;
  admin=await login('admin');const token=await login('zhangwei'),reviewer=await login('lina');
  for(const file of assetFiles(fs.readFileSync(path.join(root,'src/main/resources/static/index.html'),'utf8'))){const res=await request(base+'/'+(file==='index.html'?'':file));assert.equal(res.status,200);const hash=sha(Buffer.from(await res.arrayBuffer()));assert.equal(hash,sha(fs.readFileSync(path.join(root,'src/main/resources/static',file))));entry.assets.push({file,sha256:hash})}
  const normalId=await newSession(token),badKey=crypto.randomUUID(),normalQuestion='Redis pending GUARD_BAD_NORMAL';
  const bad=await api('/assistant/sessions/'+normalId+'/messages',token,{content:normalQuestion},badKey,guarded?502:200);entry.normalHttpStatus=bad.res.status;
  if(guarded){assert.equal(bad.json.error.code,'ASSISTANT_CITATION_INVALID');entry.normalErrorCode=bad.json.error.code}else assert.ok(bad.json.data.content.includes(BAD));
  const state=(await api('/assistant/sessions/'+normalId+'/request',token,null,badKey)).json.data;assert.equal(state.status,guarded?'FAILED':'COMPLETED');entry.normalState=state.status;
  const normalSaved=(await api('/assistant/sessions/'+normalId,token)).json.data.messages;assert.equal(normalSaved.filter(m=>m.role==='ASSISTANT').length,guarded?0:1);
  // Valid reference is split over actual SDK HTTP frames; saved text must equal delivered text.
  const validId=await newSession(token),validResponse=await request(base+'/api/v1/assistant/sessions/'+validId+'/stream',{method:'POST',headers:{Authorization:'Bearer '+token,'Content-Type':'application/json','Idempotency-Key':crypto.randomUUID()},body:JSON.stringify({content:'Redis pending GUARD_GOOD'})});assert.equal(validResponse.status,200);
  const events=(await validResponse.text()).split(/\r?\n/).filter(l=>l.startsWith('data:')).map(l=>JSON.parse(l.slice(5)));assert.ok(events.some(e=>e.type==='done'));assert.ok(!events.some(e=>e.type==='error'));
  const delivered=events.filter(e=>e.type==='token'||e.type==='delta').map(e=>e.content).join(''),validMessage=(await api('/assistant/sessions/'+validId,token)).json.data.messages.find(m=>m.role==='ASSISTANT');assert.equal(validMessage.content,delivered);assert.equal(delivered,'İ 已知资料 ['+VALID+']。\n');entry.validNativeExact=true;
  // Real independent publication replaces an ON_CALL-visible version with ADMIN/manager-only v2.
  const publish=async markdown=>{const imported=(await api('/runbooks/imports/markdown',admin,{stableKey:'cp02-history',resourceType:'SERVICE',title:'historyprobe',sourceName:'cp02-controlled.md',markdown,allowedRoles:markdown.includes(SECRET)?['ADMIN','OPS_MANAGER','ON_CALL']:['ADMIN','OPS_MANAGER']})).json.data.document;assert.equal(imported.status,'PENDING_REVIEW');const approved=(await api('/runbooks/publications/'+imported.id+'/decisions',reviewer,{expectedVersion:0,decision:'APPROVE',requestKey:crypto.randomUUID(),reason:'CP02 isolated acceptance'})).json.data;assert.equal(approved.document.status,'PUBLISHED');return imported};
  await publish('# historyprobe\n'+SECRET);const historyId=await newSession(token),historyKey=crypto.randomUUID();const first=(await api('/assistant/sessions/'+historyId+'/messages',token,{content:'historyprobe HISTORY_FIRST'},historyKey)).json.data;assert.ok(first.content.includes(SECRET));await publish('# historyprobe\nREPLACEMENT_MANAGER_ONLY');
  await api('/assistant/sessions/'+historyId+'/messages',token,{content:'historyprobe HISTORY_SECOND'},crypto.randomUUID());const observed=report.providerCalls.find(c=>c.run===name&&c.question.includes('HISTORY_SECOND'));assert.equal(observed.historyContainsPrivate,!guarded);if(guarded)assert.equal(observed.historyMasked,true);entry.historySentPrivate=observed.historyContainsPrivate;
  const replay=await api('/assistant/sessions/'+historyId+'/messages',token,{content:'historyprobe HISTORY_FIRST'},historyKey);assert.equal(replay.res.headers.get('x-opspilot-idempotent-replay'),'true');assert.deepEqual(replay.json.data,first);entry.oldSnapshotReplay=true;
  const markdown=await request(base+'/api/v1/assistant/sessions/'+historyId+'/export',{headers:{Authorization:'Bearer '+token}});assert.equal(markdown.status,200);assert.ok((await markdown.text()).includes(SECRET));entry.privateHistoryExportPreserved=true;
  browser=await chromium.launch({headless:true,executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe'});const context=await browser.newContext({viewport:{width:1440,height:1000},reducedMotion:'reduce'});
  await context.addInitScript(()=>{localStorage.setItem('opspilot_theme','light');const original=window.fetch;window.fetch=async(...args)=>{const response=await original(...args);if(response.ok&&response.url.endsWith('/stream'))response.clone().text().then(body=>window.__guardStream={body}).catch(error=>window.__guardStream={error:String(error)});return response}});
  page=await context.newPage();page.on('pageerror',e=>entry.errors.push(e.message));page.on('console',msg=>{if(['error','warning'].includes(msg.type()))entry.errors.push(msg.text())});page.on('response',res=>{if(res.status()>=400)entry.errors.push(res.status()+' '+res.url())});
  await page.goto(base+'/login',{waitUntil:'networkidle'});await page.locator('input[autocomplete="username"]').fill('zhangwei');await page.locator('input[autocomplete="current-password"]').fill('OpsPilot@2026');await page.getByRole('button',{name:'进入控制台',exact:true}).click();await page.waitForURL(base+'/');
  const uiId=await newSession(token);await page.goto(base+'/assistant?session='+uiId,{waitUntil:'networkidle'});assert.equal(new URL(page.url()).pathname,'/assistant');assert.equal(await page.title(),'OpsPilot 智能运维平台');assert.equal(await page.locator('vite-error-overlay').count(),0);assert.ok((await page.locator('.assistant-conversation').innerText()).length>30);
  await page.locator('.assistant-composer textarea').fill('Redis pending GUARD_BAD_UI');const pending=page.waitForResponse(res=>res.url().endsWith('/stream')&&res.request().method()==='POST');await page.getByTitle('发送消息',{exact:true}).click();const streamResponse=await pending;assert.equal(streamResponse.status(),200);await page.waitForFunction(()=>window.__guardStream);const uiStream=await page.evaluate(()=>window.__guardStream);assert.equal(uiStream.error,undefined);
  const uiEvents=uiStream.body.split(/\r?\n/).filter(l=>l.startsWith('data:')).map(l=>JSON.parse(l.slice(5)));entry.badNativeDone=uiEvents.some(e=>e.type==='done');assert.equal(entry.badNativeDone,!guarded);entry.badNativeContent=uiEvents.filter(e=>e.type==='token'||e.type==='delta').map(e=>e.content).join('');assert.equal(entry.badNativeContent.includes('runbook:'),!guarded);
  const uiKey=streamResponse.request().headers()['idempotency-key'];assert.ok(uiKey);assert.equal((await api('/assistant/sessions/'+uiId+'/request',token,null,uiKey)).json.data.status,guarded?'FAILED':'COMPLETED');
  if(guarded){await page.getByRole('button',{name:'查询原请求',exact:true}).click();await page.waitForFunction(()=>document.querySelector('.assistant-request-status strong')?.textContent==='原请求已停止');assert.equal(await page.locator('.assistant-message.assistant').count(),0)}
  else{await page.locator('.assistant-message.assistant .assistant-copy').last().waitFor();assert.ok((await page.locator('.assistant-message.assistant').innerText()).includes(BAD))}
  for(const [width,height,theme]of [[1440,1000,'light'],[390,844,'dark']]){await page.setViewportSize({width,height});if(theme==='dark')await page.getByRole('button',{name:'切换到深色主题',exact:true}).click();assert.equal(await page.locator('html').getAttribute('data-theme'),theme);assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1));const file=path.join(dir,'invalid-'+theme+'-'+width+'.png');await page.screenshot({path:file,fullPage:false});entry.screenshots.push(file)}
  await page.reload({waitUntil:'networkidle'});const persisted=page.locator('.assistant-message.assistant');assert.equal((await persisted.count()?await persisted.innerText():'').includes(BAD),!guarded);entry.invalidPersistenceVerified=true;
  await page.setViewportSize({width:1440,height:1000});await page.goto(base+'/assistant?session='+validId,{waitUntil:'networkidle'});assert.equal(await page.locator('html').getAttribute('data-theme'),'light');await page.locator('.assistant-message.assistant .assistant-copy').waitFor();assert.ok((await page.locator('.assistant-message.assistant').innerText()).includes(VALID));const positive=path.join(dir,'valid-light-1440.png');await page.screenshot({path:positive,fullPage:false});entry.screenshots.push(positive);entry.validRendered=true;
  assert.deepEqual(entry.errors,[]);assert.equal(report.providerFailure,undefined);entry.status='PASS';
 }catch(error){entry.status='FAIL';entry.failure=error.stack;if(page)try{const file=path.join(dir,'failure.png');await page.screenshot({path:file});entry.screenshots.push(file)}catch{}throw error}
 finally{
  if(browser)await browser.close();
  if(child)try{if(admin&&child.exitCode===null){const res=await request(management+'/actuator/shutdown',{method:'POST',headers:{Authorization:'Bearer '+admin}});assert.equal(res.status,200);entry.shutdown=true}let timer;const result=await Promise.race([closed,new Promise(r=>timer=setTimeout(()=>r({error:'Shutdown deadline'}),10000))]);clearTimeout(timer);if(result.error){child.kill();await closed;throw Error(result.error)}assert.equal(result.code,0);entry.childExit=result.code}finally{if(child.exitCode===null&&child.signalCode===null){child.kill();await closed}}
  if(fd!==undefined)fs.closeSync(fd);assert.equal(sha(fs.readFileSync(jar)),jarHash);await free(9913);await free(9914);entry.portsFree=true;
 }
}
(async()=>{assert.equal(process.argv.length,6,'Usage: --before jar --after jar');assert.equal(process.argv[2],'--before');assert.equal(process.argv[4],'--after');try{await new Promise(r=>provider.listen(0,'127.0.0.1',r));report.providerPort=provider.address().port;await one('before',process.argv[3],false);await one('after',process.argv[5],true);assert.deepEqual(report.runs[0].assets,report.runs[1].assets);report.status='PASS'}catch(error){report.status='FAIL';report.failure=error.stack;process.exitCode=1}finally{await new Promise(r=>provider.close(r));if(report.providerPort){await free(report.providerPort);report.providerPortFree=true}fs.writeFileSync(path.join(out,'qa.json'),JSON.stringify(report,null,2)+'\n');console.log(JSON.stringify({status:report.status,out,runs:report.runs.map(r=>({name:r.name,status:r.status,normal:r.normalHttpStatus,state:r.normalState,badNativeDone:r.badNativeDone,historySentPrivate:r.historySentPrivate,portsFree:r.portsFree})),providerCalls:report.providerCalls.length,failure:report.failure}))}})();

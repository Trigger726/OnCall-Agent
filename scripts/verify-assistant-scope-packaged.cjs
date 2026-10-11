// Same published corpus and frozen questions, old/new packaged runtime + real browser + post-shutdown SQL observation.
const fs=require('node:fs'),path=require('node:path'),os=require('node:os'),assert=require('node:assert/strict'),crypto=require('node:crypto');
const{spawn,execFileSync}=require('node:child_process'),{chromium}=require('./browser/node_modules/playwright'),{javaArgs,free,assetFiles}=require('./verify-ui-packaged.cjs');
const root=path.resolve(__dirname,'..'),sha=b=>crypto.createHash('sha256').update(b).digest('hex'),delay=ms=>new Promise(r=>setTimeout(r,ms));
const out=fs.mkdtempSync(path.join(os.tmpdir(),'opspilot-scope-')),datasetFile=path.join(root,'scripts/fixtures/assistant-runbook-scope-cases.json'),dataset=JSON.parse(fs.readFileSync(datasetFile));
const classes=path.join(out,'observer-classes');fs.mkdirSync(classes);execFileSync(path.join(process.env.JAVA_HOME,'bin/javac.exe'),['-encoding','UTF-8','-d',classes,path.join(root,'scripts/fixtures/AssistantScopeSqlObserver.java')],{windowsHide:true});
const h2='D:/Codex/国网/tmp/m2-repository/com/h2database/h2/2.2.220/h2-2.2.220.jar';assert.ok(fs.existsSync(h2),'Requires existing H2 2.2.220; do not install dependencies');
const report={status:'RUNNING',out,aiEnabled:false,semanticEnabled:false,userDatabaseModified:false,browser:'Existing Playwright 1.63.0 + Chrome; Browser plugin not available',datasetVersion:dataset.version,datasetSha256:sha(fs.readFileSync(datasetFile)),cases:dataset.cases,runs:[]};
async function request(url,options={}){return fetch(url,{...options,signal:AbortSignal.timeout(10000)})}
function exitOf(child){return new Promise(r=>{child.once('error',e=>r({error:e.message}));child.once('close',(code,signal)=>r({code,signal}))})}
function keys(message){return JSON.parse(message.evidenceJson).filter(r=>r.type==='RUNBOOK').map(r=>r.ref.match(/^runbook:(.*):v\d+#chunk-\d+$/)[1])}
function metrics(rows){
 const bound=rows.filter(r=>r.incidentId!==null),judged=rows.filter(r=>r.relevantKeys.length),violationCount=bound.reduce((n,r)=>n+r.returned.filter(k=>r.excludedKeys.includes(k)).length,0),returnedBound=bound.reduce((n,r)=>n+r.returned.length,0);
 return{caseCount:rows.length,boundCases:bound.length,judgedCases:judged.length,scopeViolations:violationCount,boundReturned:returnedBound,scopeViolationRate:returnedBound?violationCount/returnedBound:0,
  hitAt3:judged.reduce((n,r)=>n+(r.returned.some(k=>r.relevantKeys.includes(k))?1:0),0)/judged.length,
  mrr:judged.reduce((n,r)=>{const rank=r.returned.findIndex(k=>r.relevantKeys.includes(k));return n+(rank<0?0:1/(rank+1))},0)/judged.length,
  precisionAt3:judged.reduce((n,r)=>n+r.returned.filter(k=>r.relevantKeys.includes(k)).length/3,0)/judged.length};
}
async function one(name,relativeJar,scoped){
 const dir=path.join(out,name);fs.mkdirSync(dir);const jar=path.resolve(root,relativeJar),hash=sha(fs.readFileSync(jar)),base='http://127.0.0.1:9913',management='http://127.0.0.1:9914';
 const entry={name,jar,jarSha256:hash,base,status:'RUNNING',screenshots:[],errors:[],assets:[],cases:[],publications:[],layout:[]};report.runs.push(entry);
 let child,closed,fd,admin,browser,page;
 const api=async(route,token,body,key)=>{const res=await request(base+'/api/v1'+route,{method:body?'POST':'GET',headers:{'Content-Type':'application/json',...(token?{Authorization:'Bearer '+token}:{}),...(key?{'Idempotency-Key':key}:{})},...(body?{body:JSON.stringify(body)}:{})});assert.equal(res.status,200,route);return{res,data:(await res.json()).data}};
 const session=async(token,incidentId)=>(await api('/assistant/sessions',token,{incidentId:incidentId??null})).data.session.id;
 try{
  await free(9913);await free(9914);fd=fs.openSync(path.join(dir,'backend.log'),'w');
  const args=javaArgs(jar,dir,9913);args.push('--opspilot.observability.prometheus.enabled=false','--opspilot.observability.loki.enabled=false');
  child=spawn(path.join(process.env.JAVA_HOME,'bin/java.exe'),args,{cwd:dir,env:{...process.env,JWT_SECRET:crypto.randomBytes(48).toString('hex')},stdio:['ignore',fd,fd],windowsHide:true});closed=exitOf(child);
  const deadline=Date.now()+60000;while(true){assert.equal(await Promise.race([closed,delay(0).then(()=>null)]),null,'JAR exited');try{const res=await request(management+'/actuator/health');if(res.ok&&(await res.json()).status==='UP')break}catch{}assert.ok(Date.now()<deadline,'Readiness deadline');await delay(500)}
  const login=async name=>(await api('/auth/login',null,{username:name,password:'OpsPilot@2026'})).data.accessToken;admin=await login('admin');const token=await login('zhangwei'),reviewer=await login('lina');
  // Explicit metadata applicability, not inferred aliases; identical content, titles, version and roles on both JARs.
  for(const[stableKey,serviceCode,title]of[['cp04-redis-scope','MID-REDIS-01','结算 Redis 排查手册'],['cp04-foreign-scope','REDIS-FOREIGN','其他服务 Redis 排查手册']]){
   const imported=(await api('/runbooks/imports/markdown',admin,{stableKey,resourceType:'MIDDLEWARE',serviceCode,title,sourceName:'controlled/'+stableKey+'.md',markdown:'# Redis 连接池排查\n核对 Redis active pending 慢命令、连接池上限、等待队列和超时日志。先按当前 Incident 排查，再验证恢复，不直接确认根因。',allowedRoles:['ADMIN','OPS_MANAGER','ON_CALL']})).data.document;
   assert.equal(imported.status,'PENDING_REVIEW');const reviewed=(await api('/runbooks/publications/'+imported.id+'/decisions',reviewer,{expectedVersion:0,decision:'APPROVE',requestKey:crypto.randomUUID(),reason:'CP04 independent controlled acceptance'})).data.document;
   assert.equal(reviewed.status,'PUBLISHED');entry.publications.push({stableKey,serviceCode,version:reviewed.versionNo,writer:'admin',reviewer:'lina',status:reviewed.status});
  }
  const corpus=(await api('/runbooks',token)).data;assert.equal(corpus.length,8);entry.corpus=corpus.map(d=>({stableKey:d.stableKey,versionNo:d.versionNo,resourceType:d.resourceType,serviceCode:d.serviceCode,title:d.title,contentHash:d.contentHash})).sort((a,b)=>a.stableKey.localeCompare(b.stableKey));
  const html=await(await request(base+'/')).text();
  for(const file of assetFiles(html)){const res=await request(base+'/'+(file==='index.html'?'':file));assert.equal(res.status,200);const h=sha(Buffer.from(await res.arrayBuffer()));if(scoped)assert.equal(h,sha(fs.readFileSync(path.join(root,'src/main/resources/static',file))));entry.assets.push({file,sha256:h})}
  for(const c of dataset.cases){
   const id=await session(token,c.incidentId),answer=(await api('/assistant/sessions/'+id+'/messages',token,{content:c.question},crypto.randomUUID())).data,returned=keys(answer);
   const row={...c,sessionId:id,messageId:answer.id,returned,answer:answer.content};entry.cases.push(row);
   assert.equal(answer.content.includes('Incident 范围检索'),scoped&&c.incidentId!==null,c.id+' scope disclosure');
   if(scoped&&c.incidentId!==null)assert.ok(returned.every(k=>!c.excludedKeys.includes(k)),c.id+' outside scope');
   if(c.id==='global-no-match')assert.deepEqual(returned,[]);
   const saved=(await api('/assistant/sessions/'+id,token)).data.messages.find(m=>m.id===answer.id);assert.deepEqual(saved,answer);
  }
  entry.metrics=metrics(entry.cases);
  browser=await chromium.launch({executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true});const context=await browser.newContext({viewport:{width:1440,height:1000},reducedMotion:'reduce'});
  await context.addInitScript(()=>{localStorage.setItem('opspilot_theme','light');const original=window.fetch;window.fetch=async(...args)=>{const response=await original(...args);if(response.url.endsWith('/stream')&&response.ok)response.clone().text().then(body=>window.__scopeStream={body}).catch(e=>window.__scopeStream={error:String(e)});return response}});
  page=await context.newPage();page.on('pageerror',e=>entry.errors.push({type:'page',message:e.message}));page.on('console',m=>{if(['error','warning'].includes(m.type()))entry.errors.push({type:m.type(),message:m.text()})});page.on('response',r=>{if(r.status()>=400)entry.errors.push({type:'http',status:r.status(),url:r.url()})});
  await page.goto(base+'/login',{waitUntil:'networkidle'});await page.locator('input[autocomplete="username"]').fill('zhangwei');await page.locator('input[autocomplete="current-password"]').fill('OpsPilot@2026');await page.getByRole('button',{name:'进入控制台',exact:true}).click();await page.waitForURL(base+'/');
  const uiSession=await session(token,1),question=dataset.cases[0].question;await page.goto(base+'/assistant?session='+uiSession,{waitUntil:'networkidle'});
  assert.equal(new URL(page.url()).pathname,'/assistant');assert.equal(Number(new URL(page.url()).searchParams.get('session')),uiSession);assert.equal(await page.title(),'OpsPilot 智能运维平台');assert.ok((await page.locator('.assistant-conversation').innerText()).length>30);assert.equal(await page.locator('vite-error-overlay').count(),0);
  const geometry=async stage=>{
   const result=await page.evaluate(()=>{const rect=document.querySelector('.assistant-composer').getBoundingClientRect(),list=document.querySelector('.assistant-session-list');return{composerTop:rect.top,composerBottom:rect.bottom,height:innerHeight,visible:rect.top>=0&&rect.bottom<=innerHeight,sessionListHeight:list.clientHeight,sessionListScrollHeight:list.scrollHeight,font:getComputedStyle(document.body).fontFamily}});
   entry.layout.push({stage,...result});if(scoped)assert.equal(result.visible,true,stage+' composer not visible');return result;
  };
  await geometry('eleven-sessions-initial');
  await page.locator('.assistant-composer textarea').fill(question);const pending=page.waitForResponse(r=>r.url().endsWith('/stream')&&r.request().method()==='POST');await page.getByTitle('发送消息',{exact:true}).click();const res=await pending;assert.equal(res.status(),200);assert.match(res.headers()['content-type'],/text\/event-stream/);await page.waitForFunction(()=>window.__scopeStream);
  const stream=await page.evaluate(()=>window.__scopeStream);assert.equal(stream.error,undefined);const events=stream.body.split(/\r?\n/).filter(l=>l.startsWith('data:')).map(l=>JSON.parse(l.slice(5))),done=events.find(e=>e.type==='done');assert.ok(done?.messageId);
  const delivered=events.filter(e=>['delta','token'].includes(e.type)).map(e=>e.content).join(''),saved=(await api('/assistant/sessions/'+uiSession,token)).data.messages.find(m=>m.id===done.messageId);assert.equal(saved.content,delivered);assert.equal(saved.evidenceJson,done.evidenceJson);assert.deepEqual(keys(saved),entry.cases[0].returned);entry.streamNormalEquivalent=true;
  const replay=await api('/assistant/sessions/'+uiSession+'/messages',token,{content:question},res.request().headers()['idempotency-key']);assert.equal(replay.res.headers.get('x-opspilot-idempotent-replay'),'true');assert.deepEqual(replay.data,saved);entry.idempotentReplay=true;
  await page.locator('.assistant-message.assistant .assistant-copy').last().waitFor();const answer=page.locator('.assistant-message.assistant .chat-markdown').last();assert.equal((await answer.innerText()).includes('Incident 范围检索'),scoped);const priorText=await answer.innerText();await page.reload({waitUntil:'networkidle'});await page.locator('.assistant-message.assistant .assistant-copy').last().waitFor();assert.equal(await page.locator('.assistant-message.assistant .chat-markdown').last().innerText(),priorText);entry.refreshPersisted=true;
  for(const[width,height,theme]of[[1440,1000,'light'],[390,844,'dark']]){
   await page.setViewportSize({width,height});if(theme==='dark')await page.getByRole('button',{name:'切换到深色主题',exact:true}).click();assert.equal(await page.locator('html').getAttribute('data-theme'),theme);assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),'Horizontal overflow');await geometry(theme+'-'+width);
   if(width===1440){const list=page.locator('.assistant-session-list');await list.evaluate(el=>el.scrollTop=el.scrollHeight);entry.sessionListScrollable=await list.evaluate(el=>el.scrollTop>0);if(scoped)assert.equal(entry.sessionListScrollable,true,'Session list did not scroll');await list.evaluate(el=>el.scrollTop=0)}
   await page.locator('.assistant-message-viewport').evaluate(el=>el.scrollTop=0);const initial=path.join(dir,`initial-${theme}-${width}.png`);await page.screenshot({path:initial});entry.screenshots.push(initial);
   await page.locator('.assistant-message-viewport').evaluate(el=>{const heading=[...el.querySelectorAll('h3')].find(h=>h.textContent==='Runbook 检索依据');el.scrollTop+=heading.getBoundingClientRect().top-el.getBoundingClientRect().top-12});
   const file=path.join(dir,`scope-${theme}-${width}.png`);await page.screenshot({path:file});entry.screenshots.push(file);await geometry('scrolled-'+theme+'-'+width);
  }
  // Existing public search remains global even while an assistant session is bound.
  const global=(await api('/runbooks/search?q='+encodeURIComponent('Kafka consumer lag group offset commit')+'&topK=3&mode=BM25',token)).data;assert.equal(global.results[0].stableKey,'kafka-consumer-lag');assert.equal(global.candidateChunkCount,8);entry.publicSearchStillGlobal=true;
  assert.deepEqual(entry.errors,[]);entry.status='PASS';
 }catch(error){entry.status='FAIL';entry.failure=error.stack;if(page)try{const file=path.join(dir,'failure.png');await page.screenshot({path:file});entry.screenshots.push(file)}catch{}throw error}
 finally{
  if(browser)await browser.close();
  if(child){try{if(admin&&child.exitCode===null){const res=await request(management+'/actuator/shutdown',{method:'POST',headers:{Authorization:'Bearer '+admin}});assert.equal(res.status,200);entry.shutdown=true}let timer;const result=await Promise.race([closed,new Promise(r=>timer=setTimeout(()=>r({error:'Shutdown deadline'}),10000))]);clearTimeout(timer);if(result.error){child.kill();await closed;throw Error(result.error)}assert.equal(result.code,0);entry.childExit=result.code}finally{if(child.exitCode===null&&child.signalCode===null){child.kill();await closed}}}
  if(fd!==undefined)fs.closeSync(fd);assert.equal(sha(fs.readFileSync(jar)),hash);await free(9913);await free(9914);entry.portsFree=true;
 }
 if(entry.status==='PASS'){
  const stdout=execFileSync(path.join(process.env.JAVA_HOME,'bin/java.exe'),['-cp',classes+path.delimiter+h2,'AssistantScopeSqlObserver',path.join(dir,'demo')],{encoding:'utf8',windowsHide:true});
  entry.sql=stdout.trim().split(/\r?\n/).map(line=>{const[id,q,count,results]=line.split('\t');return{id:Number(id),query:Buffer.from(q,'base64').toString('utf8'),candidateChunkCount:Number(count),results:JSON.parse(Buffer.from(results,'base64').toString('utf8'))}});
  assert.equal(entry.sql.length,dataset.cases.length+1,'Completed replay must not add another retrieval');
  for(const[i,row]of entry.sql.entries()){
   const c=dataset.cases[i]??dataset.cases[0];assert.equal(row.query,c.question);assert.deepEqual(row.results.map(r=>r.documentStableKey??r.stableKey),i===dataset.cases.length?entry.cases[0].returned:entry.cases[i].returned);
   assert.equal(row.candidateChunkCount,scoped&&c.incidentId!==null?(c.incidentId===1?4:2):8);
  }
  entry.readOnlySqlVerified=true;
 }
}
(async()=>{assert.equal(process.argv.length,6,'Usage --before JAR --after JAR');assert.equal(process.argv[2],'--before');assert.equal(process.argv[4],'--after');try{
 await one('before',process.argv[3],false);await one('after',process.argv[5],true);assert.deepEqual(report.runs[0].corpus,report.runs[1].corpus);
 for(const file of ['assets/theme.js','favicon.svg'])assert.deepEqual(report.runs[0].assets.find(a=>a.file===file),report.runs[1].assets.find(a=>a.file===file));
 assert.equal(report.runs[0].layout[0].font,report.runs[1].layout[0].font);assert.equal(report.runs[0].layout[0].visible,false,'Baseline layout problem not reproduced');assert.ok(report.runs[1].layout.every(l=>l.visible));
 for(const c of dataset.cases.filter(c=>c.incidentId===null)){const a=report.runs[0].cases.find(r=>r.id===c.id),b=report.runs[1].cases.find(r=>r.id===c.id);assert.deepEqual(a.returned,b.returned,c.id+' global ordering changed')}
 assert.ok(report.runs[0].metrics.scopeViolations>0,'Baseline scope problem not reproduced');assert.equal(report.runs[1].metrics.scopeViolations,0);report.status='PASS'
 }catch(error){report.status='FAIL';report.failure=error.stack;process.exitCode=1}finally{fs.writeFileSync(path.join(out,'qa.json'),JSON.stringify(report,null,2)+'\n');console.log(JSON.stringify({status:report.status,out,runs:report.runs.map(r=>({name:r.name,status:r.status,metrics:r.metrics,sqlVerified:r.readOnlySqlVerified,shutdown:r.shutdown,portsFree:r.portsFree})),failure:report.failure}))}})();

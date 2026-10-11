// Acceptance-only helper: two fresh local databases, identical UI query, immutable demo inputs.
const fs=require('node:fs'),path=require('node:path'),os=require('node:os'),assert=require('node:assert/strict'),crypto=require('node:crypto');
const {spawn}=require('node:child_process');
const {chromium}=require('./browser/node_modules/playwright');
const {javaArgs,free,assetFiles}=require('./verify-ui-packaged.cjs');
const root=path.resolve(__dirname,'..'),sha=b=>crypto.createHash('sha256').update(b).digest('hex'),delay=ms=>new Promise(r=>setTimeout(r,ms));
const out=fs.mkdtempSync(path.join(os.tmpdir(),'opspilot-assistant-rag-'));
const question='Redis 连接池 active pending 慢命令',citation='runbook:legacy-runbook-2:v1#chunk-0';
const report={status:'RUNNING',out,browserPath:'Browser plugin not available; existing Playwright 1.63.0 + Chrome',question,aiEnabled:false,userDatabaseModified:false,runs:[]};
async function request(url,options={}){return fetch(url,{...options,signal:AbortSignal.timeout(10000)})}
function exitOf(child){return new Promise(r=>{child.once('error',e=>r({error:e.message}));child.once('close',(code,signal)=>r({code,signal}))})}
async function one(name,jar,expected){
 const dir=path.join(out,name);fs.mkdirSync(dir);jar=path.resolve(root,jar);
 const hash=sha(fs.readFileSync(jar)),base='http://127.0.0.1:9913',management='http://127.0.0.1:9914';
 const entry={name,jar,jarSha256:hash,base,status:'RUNNING',screenshots:[],errors:[],assets:[],shutdown:false};report.runs.push(entry);
 let child,closed,browser,fd,admin,page;
 const api=async(route,token,body,extra={})=>{const res=await request(base+'/api/v1'+route,{method:body?'POST':'GET',headers:{Authorization:'Bearer '+token,'Content-Type':'application/json',...extra},...(body?{body:JSON.stringify(body)}:{})});assert.equal(res.status,200,route);return {res,data:(await res.json()).data}};
 try{
  await free(9913);await free(9914);
  fd=fs.openSync(path.join(dir,'backend.log'),'w');
  const java=path.join(process.env.JAVA_HOME,'bin/java.exe');
  child=spawn(java,javaArgs(jar,dir,9913),{cwd:dir,env:{...process.env,JWT_SECRET:crypto.randomBytes(48).toString('hex')},stdio:['ignore',fd,fd],windowsHide:true});closed=exitOf(child);
  const deadline=Date.now()+60000;
  while(true){assert.equal(await Promise.race([closed,delay(0).then(()=>null)]),null,'JAR exited');try{const res=await request(management+'/actuator/health');if(res.ok&&(await res.json()).status==='UP')break}catch{}assert.ok(Date.now()<deadline,'Health deadline');await delay(500)}
  admin=(await api('/auth/login','',{username:'admin',password:'OpsPilot@2026'})).data.accessToken;
  for(const file of assetFiles(fs.readFileSync(path.join(root,'src/main/resources/static/index.html'),'utf8'))){const res=await request(base+'/'+(file==='index.html'?'':file));assert.equal(res.status,200);const hash=sha(Buffer.from(await res.arrayBuffer()));assert.equal(hash,sha(fs.readFileSync(path.join(root,'src/main/resources/static',file))));entry.assets.push({file,sha256:hash})}
  browser=await chromium.launch({executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true});
  const context=await browser.newContext({viewport:{width:1440,height:1000},reducedMotion:'reduce'});await context.addInitScript(()=>{
   localStorage.setItem('opspilot_theme','light');
   // Chrome does not retain a fetch SSE body for Network.getResponseBody. Observe a clone,
   // without stubbing the actual response consumed by the app.
   const original=window.fetch;
   window.fetch=async(...args)=>{const response=await original(...args);if(response.url.endsWith('/stream')&&response.ok){response.clone().text().then(body=>{window.__ragQaStream={body}}).catch(error=>{window.__ragQaStream={error:String(error)}})}return response};
  });
  page=await context.newPage();page.on('pageerror',e=>entry.errors.push({type:'page',message:e.message}));page.on('console',msg=>{if(['error','warning'].includes(msg.type()))entry.errors.push({type:msg.type(),message:msg.text()})});page.on('response',res=>{if(res.status()>=400)entry.errors.push({type:'http',status:res.status(),url:res.url()})});
  await page.goto(base+'/login',{waitUntil:'networkidle'});await page.locator('input[autocomplete="username"]').fill('zhangwei');await page.locator('input[autocomplete="current-password"]').fill('OpsPilot@2026');await page.getByRole('button',{name:'进入控制台',exact:true}).click();await page.waitForURL(base+'/');
  await page.goto(base+'/assistant',{waitUntil:'networkidle'});assert.equal(new URL(page.url()).pathname,'/assistant');assert.ok(Number(new URL(page.url()).searchParams.get('session'))>0);assert.equal(await page.title(),'OpsPilot 智能运维平台');assert.equal(await page.locator('vite-error-overlay').count(),0);assert.ok((await page.locator('.assistant-conversation').innerText()).length>30);
  await page.locator('.assistant-composer textarea').fill(question);
  const pending=page.waitForResponse(res=>res.url().endsWith('/stream')&&res.request().method()==='POST');await page.getByTitle('发送消息',{exact:true}).click();const response=await pending;assert.equal(response.status(),200);
  assert.match(response.headers()['content-type'],/text\/event-stream/);await page.waitForFunction(()=>window.__ragQaStream);const stream=await page.evaluate(()=>window.__ragQaStream);assert.equal(stream.error,undefined);
  const events=stream.body.split(/\r?\n/).filter(l=>l.startsWith('data:')).map(l=>JSON.parse(l.slice(5).trim()));
  const done=events.find(e=>e.type==='done');assert.ok(done?.messageId);const refs=JSON.parse(done.evidenceJson);entry.runbookCitations=refs.filter(r=>r.type==='RUNBOOK').map(r=>r.ref);
  assert.equal(entry.runbookCitations.includes(citation),expected);if(!expected)assert.deepEqual(entry.runbookCitations,[]);
  await page.locator('.assistant-message.assistant .assistant-copy').last().waitFor();const answer=page.locator('.assistant-message.assistant .chat-markdown').last();assert.equal((await answer.innerText()).includes(citation),expected);
  const content=events.filter(e=>['token','delta'].includes(e.type)).map(e=>e.content).join('');entry.answer=content;
  const token=await page.evaluate(()=>localStorage.getItem('opspilot_token'));const sessionId=Number(response.url().match(/sessions\/(\d+)\/stream$/)[1]);
  const saved=(await api('/assistant/sessions/'+sessionId,token)).data.messages.find(m=>m.id===done.messageId);assert.equal(saved.content,content);assert.deepEqual(JSON.parse(saved.evidenceJson),refs);
  const identity=response.request().headers()['idempotency-key'];assert.ok(identity);
  const replay=await api('/assistant/sessions/'+sessionId+'/messages',token,{content:question},{'Idempotency-Key':identity});assert.equal(replay.res.headers.get('x-opspilot-idempotent-replay'),'true');assert.deepEqual(replay.data,saved);entry.idempotentReplay=true;
  const second=(await api('/assistant/sessions',token,{title:'Normal endpoint acceptance'})).data.session.id;
  const normal=(await api('/assistant/sessions/'+second+'/messages',token,{content:question})).data;assert.equal(normal.content,content);assert.deepEqual(JSON.parse(normal.evidenceJson),refs);entry.normalAndSseEquivalent=true;
  const savedUiText=await answer.innerText();await page.reload({waitUntil:'networkidle'});
  // The normal-endpoint session is most recent. Both carry exactly the same answer and citations.
  await page.locator('.assistant-message.assistant .assistant-copy').last().waitFor();assert.equal(await page.locator('.assistant-message.assistant .chat-markdown').last().innerText(),savedUiText);
  assert.equal((await page.locator('.assistant-evidence').innerText().catch(()=>'' )).includes(citation),expected);entry.reloadPersistence=true;
  for(const [width,height,theme]of [[1440,1000,'light'],[390,844,'dark']]){
   await page.setViewportSize({width,height});if(theme==='dark')await page.getByRole('button',{name:'切换到深色主题',exact:true}).click();assert.equal(await page.locator('html').getAttribute('data-theme'),theme);
   assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),'Horizontal overflow');
   await page.locator('.assistant-message-viewport').evaluate(el=>el.scrollTop=0);
   const file=path.join(dir,`assistant-${theme}-${width}.png`);await page.screenshot({path:file,fullPage:false});entry.screenshots.push(file);
  }
  assert.deepEqual(entry.errors,[]);entry.status='PASS';
 }catch(e){entry.status='FAIL';entry.failure=e.stack;if(page)try{const file=path.join(dir,'failure.png');await page.screenshot({path:file,fullPage:false});entry.screenshots.push(file)}catch{}throw e}
 finally{
  if(browser)await browser.close();
  if(child){try{if(admin&&child.exitCode===null){const res=await request(management+'/actuator/shutdown',{method:'POST',headers:{Authorization:'Bearer '+admin}});assert.equal(res.status,200);entry.shutdown=true}let timer;const result=await Promise.race([closed,new Promise(r=>timer=setTimeout(()=>r({error:'Shutdown deadline'}),10000))]);clearTimeout(timer);if(result.error){child.kill();await closed;throw Error(result.error)}assert.equal(result.code,0);entry.childExit=result.code}finally{if(child.exitCode===null&&child.signalCode===null){child.kill();await closed}}}
  if(fd!==undefined)fs.closeSync(fd);assert.equal(sha(fs.readFileSync(jar)),hash,'Demo input changed');await free(9913);await free(9914);entry.portsFree=true;
 }
}
(async()=>{assert.equal(process.argv.length,6,'Usage --before path --after path');assert.equal(process.argv[2],'--before');assert.equal(process.argv[4],'--after');try{await one('before',process.argv[3],false);await one('after',process.argv[5],true);report.status='PASS'}catch(e){report.status='FAIL';report.failure=e.stack;process.exitCode=1}finally{fs.writeFileSync(path.join(out,'qa.json'),JSON.stringify(report,null,2)+'\n');console.log(JSON.stringify({status:report.status,out,runs:report.runs.map(r=>({name:r.name,status:r.status,citations:r.runbookCitations,shutdown:r.shutdown,portsFree:r.portsFree})),failure:report.failure}))}})();

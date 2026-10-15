// Actual saved assistant citations and exact source reads; only isolated controlled data is modified.
const fs=require('node:fs'),path=require('node:path'),os=require('node:os'),crypto=require('node:crypto'),assert=require('node:assert/strict');
const {spawn}=require('node:child_process'),{chromium}=require('./browser/node_modules/playwright'),{javaArgs,free}=require('./verify-ui-packaged.cjs');
const root=path.resolve(__dirname,'..'),ci=Boolean(process.env.CI),port=9913,base='http://127.0.0.1:'+port;
assert.ok(!process.env.OPSPILOT_ASSISTANT_UI_JAR,'Citation acceptance does not accept named JAR overrides');
const parent=ci?path.join(root,'target/runbook-citation-ui-it'):os.tmpdir();fs.mkdirSync(parent,{recursive:true});
const out=fs.mkdtempSync(path.join(parent,'opspilot-citation-ui-'));
const before='target/opspilot-v1.8-assistant-quiet-ui.jar',after=ci?'target/opspilot-0.1.0-SNAPSHOT.jar':'target/opspilot-v1.8-citation-source-final.jar';
const java=process.env.JAVA_HOME?path.join(process.env.JAVA_HOME,'bin',process.platform==='win32'?'java.exe':'java'):'java';
const original='CITATIONTRACE 连接池核对原文。\n'+('核对 pending 与 active 指标，确认连接池是否耗尽，再检查慢命令和回滚条件。\n'.repeat(20))+'<img src=x onerror="window.__sourceXss=true">';
const updated='CITATIONTRACE 这是新版本内容，不能替代旧引用。';
const sha=bytes=>crypto.createHash('sha256').update(bytes).digest('hex'),delay=ms=>new Promise(resolve=>setTimeout(resolve,ms));
const request=(url,options={})=>fetch(url,{...options,signal:AbortSignal.timeout(12000)});
const report={status:'RUNNING',out,mode:ci?'CI_FRESH_BUILD':'LOCAL_COMPARISON',sourceCommit:process.env.GITHUB_SHA??null,browser:'Browser plugin not available; existing Playwright/Chromium',userDatabaseModified:false,realCloudModel:false,runs:[],screenshots:[],errors:[],expectedErrors:[],warnings:[]};
async function run(label,jar,browser){
  const data=path.join(out,label);fs.mkdirSync(data);let child,closed,fd,admin,context,expected=false;
  const entry={label,jar,jarSha256:sha(fs.readFileSync(jar)),fixtureSha256:sha(original+'\n'+updated),cases:[]};report.runs.push(entry);
  try{
    await free(port);await free(port+1);fd=fs.openSync(path.join(data,'backend.log'),'w');
    child=spawn(java,[...javaArgs(path.resolve(root,jar),data,port),'--opspilot.observability.prometheus.enabled=false','--opspilot.observability.loki.enabled=false'],{cwd:data,windowsHide:true,env:{...process.env,JWT_SECRET:crypto.randomBytes(48).toString('hex')},stdio:['ignore',fd,fd]});
    closed=new Promise(resolve=>{child.once('close',code=>resolve({code}));child.once('error',error=>resolve({error:error.message}));});
    const deadline=Date.now()+60000;while(true){assert.equal(child.exitCode,null);try{const res=await request(base.replace(':9913',':9914')+'/actuator/health');if(res.ok&&(await res.json()).status==='UP')break;}catch{}assert.ok(Date.now()<deadline);await delay(500);}
    async function api(route,token,body,key,status=200){const response=await request(base+'/api/v1'+route,{method:body===undefined?'GET':'POST',headers:{'Content-Type':'application/json',...(token?{Authorization:'Bearer '+token}:{}),...(key?{'Idempotency-Key':key}:{})},...(body===undefined?{}:{body:JSON.stringify(body)})});assert.equal(response.status,status,route);return await response.json();}
    const login=async username=>(await api('/auth/login',null,{username,password:'OpsPilot@2026'})).data;
    admin=await login('admin');const reviewer=await login('lina'),oncall=await login('zhangwei');
    async function publish(key,text,roles=['ADMIN','ON_CALL']){const result=(await api('/runbooks/imports/markdown',admin.accessToken,{stableKey:key,resourceType:'CACHE',title:'连接池来源验证手册',sourceName:'citation-source.md',markdown:'# 连接池核对\n'+text,allowedRoles:roles})).data;
      await api('/runbooks/publications/'+result.document.id+'/decisions',reviewer.accessToken,{expectedVersion:0,decision:'APPROVE',requestKey:crypto.randomUUID(),reason:'独立复核来源版本'});return result.document;}
    await publish('citation-browser',original);
    const session=(await api('/assistant/sessions',oncall.accessToken,{incidentId:null,title:'引用原文验证'})).data.session.id;
    context=await browser.newContext({viewport:{width:1440,height:1000},reducedMotion:'reduce'});
    await context.addInitScript(()=>{if(!localStorage.getItem('opspilot_theme'))localStorage.setItem('opspilot_theme','light');});
    const page=await context.newPage();
    page.on('pageerror',error=>report.errors.push({label,type:'page',message:error.message}));
    page.on('console',message=>{if(message.type()==='error')(expected?report.expectedErrors:report.errors).push({label,type:'console',message:message.text()});if(message.type()==='warning')report.warnings.push({label,message:message.text()});});
    await page.goto(base+'/login',{waitUntil:'networkidle'});await page.locator('input[autocomplete="username"]').fill('zhangwei');await page.locator('input[autocomplete="current-password"]').fill('OpsPilot@2026');await page.getByRole('button',{name:'进入控制台',exact:true}).click();await page.waitForURL(base+'/');
    await page.goto(base+'/assistant?session='+session,{waitUntil:'networkidle'});await page.getByRole('textbox',{name:'消息内容'}).fill('CITATIONTRACE 连接池检查怎么验证？');await page.getByRole('button',{name:'发送消息',exact:true}).click();
    await page.waitForFunction(()=>{const input=document.querySelector('.assistant-composer textarea');return input&&!input.disabled&&!document.querySelector('.assistant-request-status');});
    const saved=(await api('/assistant/sessions/'+session,oncall.accessToken)).data;assert.equal(saved.messages.length,2);
    const answer=saved.messages.at(-1),ref=JSON.parse(answer.evidenceJson).find(item=>item.ref==='runbook:citation-browser:v1#chunk-0');assert.ok(ref);
    entry.answerSha256=sha(answer.content);entry.savedMessages=2;
    await publish('citation-browser',updated,['ADMIN']);
    await page.reload({waitUntil:'networkidle'});await page.locator('.assistant-evidence summary').last().click();
    const sourcePath='/runbooks/citation-browser/versions/1/chunks/0',endpoint=base+'/api/v1'+sourcePath;
    const capture=async name=>{await page.evaluate(()=>document.fonts.ready);assert.equal(await page.title(),'OpsPilot 智能运维平台');assert.match(page.url(),/\/assistant\?session=/);assert.equal(await page.locator('vite-error-overlay').count(),0);assert.ok((await page.locator('.assistant-conversation').innerText()).length>30);
      const layout=await page.evaluate(()=>({width:innerWidth,height:innerHeight,scrollWidth:document.documentElement.scrollWidth,theme:document.documentElement.dataset.theme}));assert.ok(layout.scrollWidth<=layout.width);await page.screenshot({path:path.join(out,name)});report.screenshots.push({file:name,...layout});};
    if(label==='before'){
      assert.equal(await page.locator('.assistant-source-link').count(),0);await api(sourcePath,oncall.accessToken,undefined,null,404);await capture('before-reference-desktop.png');entry.cases.push({name:'source-is-non-actionable-and-endpoint-missing',reproduced:true});return;
    }
    const source=(await api(sourcePath,oncall.accessToken)).data;assert.equal(source.citation,ref.ref);assert.equal(source.content,original);assert.equal(source.status,'SUPERSEDED');assert.ok(source.content.length>600);
    await api('/runbooks/citation-browser/versions/2/chunks/0',oncall.accessToken,undefined,null,404);
    await api(sourcePath,null,undefined,null,401);
    const missing=await api('/runbooks/unknown-source/versions/1/chunks/0',oncall.accessToken,undefined,null,404);
    const denied=await api('/runbooks/citation-browser/versions/2/chunks/0',oncall.accessToken,undefined,null,404);assert.deepEqual(missing.error,denied.error);
    const action=()=>page.locator('.assistant-source-link').filter({hasText:'连接池来源验证手册'});
    const open=async()=>{await action().click();await page.locator('.source-content').waitFor();assert.equal(await page.locator('.source-content').textContent(),original);assert.equal(await page.locator('.runbook-source-dialog img').count(),0);assert.equal(await page.evaluate(()=>Boolean(window.__sourceXss)),false);};
    await open();assert.equal(await page.getByRole('button',{name:'关闭引用原文'}).evaluate(node=>document.activeElement===node),true);await capture('after-source-light1440.png');
    await page.keyboard.press('Tab');entry.keyboardFocus=await page.locator('.runbook-source-dialog').evaluate(node=>({insideModal:node.contains(document.activeElement),activeTag:document.activeElement?.tagName,activeClass:document.activeElement?.className,nativeModal:node.matches(':modal')}));assert.equal(entry.keyboardFocus.insideModal,true);assert.equal(entry.keyboardFocus.nativeModal,true);
    await page.keyboard.press('Shift+Tab');assert.equal(await page.getByRole('button',{name:'关闭引用原文'}).evaluate(node=>document.activeElement===node),true);
    await page.keyboard.press('Escape');await page.locator('.runbook-source-dialog').waitFor({state:'detached'});assert.equal(await action().evaluate(node=>document.activeElement===node),true);
    entry.cases.push({name:'exact-historical-source-full-text-escaped-native-modal-and-focus',passed:true,contentLength:original.length});
    await page.evaluate(()=>{localStorage.setItem('opspilot_theme','dark');});
    // The app theme store reads this preference on a real reload.
    await page.reload({waitUntil:'networkidle'});assert.equal(await page.locator('html').getAttribute('data-theme'),'dark');await page.locator('.assistant-evidence summary').last().click();await open();await capture('after-source-dark1440.png');
    await page.setViewportSize({width:390,height:844});await capture('after-source-dark390.png');
    const box=await page.locator('.runbook-source-dialog').boundingBox();assert.ok(box.x>=0&&box.x+box.width<=390);await page.getByRole('button',{name:'关闭引用原文'}).click();
    await page.setViewportSize({width:390,height:600});await open();await capture('after-source-dark390x600.png');await page.keyboard.press('Escape');
    entry.cases.push({name:'dark-desktop-and-two-mobile-heights',passed:true});
    expected=true;
    await page.route(endpoint,route=>route.fulfill({status:503,contentType:'application/json',body:JSON.stringify({success:false,error:{code:'CONTROLLED_UNAVAILABLE'}})}));
    await action().click();await page.getByRole('alert').waitFor();assert.equal(await page.locator('.source-content').count(),0);await capture('source-unavailable-mobile.png');await page.unroute(endpoint);await page.getByRole('button',{name:'重新读取'}).click();await page.locator('.source-content').waitFor();assert.equal(await page.locator('.source-content').textContent(),original);assert.equal(await page.getByRole('button',{name:'关闭引用原文'}).evaluate(node=>document.activeElement===node),true);await page.keyboard.press('Escape');
    await page.route(endpoint,route=>route.fulfill({status:200,contentType:'application/json',body:JSON.stringify({success:true,data:{...source,versionNo:2}})}));
    await action().click();await page.getByRole('alert').waitFor();assert.equal(await page.locator('.source-content').count(),0);await capture('source-mismatch-mobile.png');await page.keyboard.press('Escape');await page.unroute(endpoint);
    expected=false;entry.cases.push({name:'read-failure-retry-and-wrong-version-response-fail-closed',passed:true});
    const hold=async(changeAccount)=>{
      let release,arrived;const gate=new Promise(resolve=>release=resolve),ready=new Promise(resolve=>arrived=resolve);
      await page.route(endpoint,async route=>{const response=await route.fetch();arrived();await gate;await route.fulfill({response}).catch(()=>{});});
      await action().click();await ready;
      if(changeAccount)await page.evaluate(({token,user})=>{localStorage.setItem('opspilot_token',token);localStorage.setItem('opspilot_user',JSON.stringify(user));window.dispatchEvent(new Event('opspilot-auth-session-changed'));},{token:reviewer.accessToken,user:reviewer.user});
      else await page.keyboard.press('Escape');
      release();await page.locator('.runbook-source-dialog').waitFor({state:'detached'});await page.unroute(endpoint);assert.equal(await page.locator('.source-content').count(),0);
    };
    await hold(false);await open();await page.keyboard.press('Escape');entry.cases.push({name:'close-aborts-late-source-and-fresh-open-still-works',passed:true});
    await hold(true);await page.getByText('登录会话已切换，请刷新页面重新载入。原请求仍按本人账号隔离保留。',{exact:true}).waitFor();entry.cases.push({name:'account-switch-discards-late-private-source',passed:true});
    assert.deepEqual((await api('/assistant/sessions/'+session,oncall.accessToken)).data.messages,saved.messages);entry.originalAnswerUnchanged=true;
  } finally {
    if(context)await context.close();
    if(child&&child.exitCode===null){try{entry.shutdown=(await request(base.replace(':9913',':9914')+'/actuator/shutdown',{method:'POST',headers:{Authorization:'Bearer '+admin?.accessToken,'Content-Type':'application/json'},body:'{}'})).status===200;}catch{}
      let timer;const exit=await Promise.race([closed,new Promise(resolve=>{timer=setTimeout(()=>resolve(null),30000);})]);clearTimeout(timer);if(!exit){entry.forcedStop=true;child.kill();await closed;}else entry.exitCode=exit.code;}
    if(fd!==undefined)fs.closeSync(fd);try{await free(port);await free(port+1);entry.portsFree=true;}catch{entry.portsFree=false;}
  }
}
(async()=>{let browser;try{browser=await chromium.launch({headless:true,...(process.env.OPSPILOT_CHROME_PATH?{executablePath:process.env.OPSPILOT_CHROME_PATH}:{})});if(!ci)await run('before',before,browser);await run('after',after,browser);assert.deepEqual(report.errors,[]);if(!ci){assert.equal(report.runs[0].fixtureSha256,report.runs[1].fixtureSha256);assert.equal(report.runs[0].answerSha256,report.runs[1].answerSha256);}assert.equal(report.runs.at(-1).cases.length,5);assert.ok(report.runs.every(run=>run.shutdown&&run.exitCode===0&&run.portsFree&&!run.forcedStop));report.status='PASS';}
  catch(error){report.status='FAIL';report.failure=error.stack;process.exitCode=1;}finally{if(browser)await browser.close();fs.writeFileSync(path.join(out,'qa.json'),JSON.stringify(report,null,2)+'\n');console.log(JSON.stringify(report));}})();

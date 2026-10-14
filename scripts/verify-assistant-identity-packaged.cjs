// Real packaged HTTP/SSE and UI, isolated H2; local DashScope protocol, never paid cloud calls.
const fs=require('node:fs'),path=require('node:path'),os=require('node:os'),http=require('node:http'),crypto=require('node:crypto'),assert=require('node:assert/strict');
const {spawn}=require('node:child_process'),{chromium}=require('./browser/node_modules/playwright'),{javaArgs,free}=require('./verify-ui-packaged.cjs');
const root=path.resolve(__dirname,'..'),out=fs.mkdtempSync(path.join(os.tmpdir(),'opspilot-identity-')),base='http://127.0.0.1:9913',management='http://127.0.0.1:9914';
const report={status:'RUNNING',out,browser:'Browser plugin not available; existing Playwright/Chrome; IAB also used for original reproduction',realCloudModel:false,userDatabaseModified:false,runs:[],screenshots:[],errors:[],warnings:[],externalOrigins:[],providerCalls:[]};
const delay=ms=>new Promise(resolve=>setTimeout(resolve,ms));
const request=(url,options={})=>fetch(url,{...options,signal:AbortSignal.timeout(15000)});
const provider=http.createServer(async(req,res)=>{
  try {
    assert.equal(req.url,'/api/v1/services/aigc/text-generation/generation');
    let raw='';for await(const chunk of req){raw+=chunk;assert.ok(raw.length<262144);}
    const body=JSON.parse(raw);assert.equal(body.model,'qwen-plus');
    assert.ok(body.input.messages.some(message=>message.content.includes('MODEL_SANITY')),'Identity must not call provider');
    report.providerCalls.push({model:body.model,identity:false});
    res.writeHead(200,{'Content-Type':'application/json'});
    res.end(JSON.stringify({output:{choices:[{finish_reason:'stop',message:{role:'assistant',content:'LOCAL_PROTOCOL_OPERATION'}}]},usage:{input_tokens:1,output_tokens:1,total_tokens:2},request_id:'identity-local-test'}));
  }catch(error){report.providerFailure=error.stack;res.writeHead(500);res.end();}
});
function observe(page){
  page.on('pageerror',error=>report.errors.push(error.message));
  page.on('console',message=>{if(message.type()==='error')report.errors.push(message.text());if(message.type()==='warning')report.warnings.push(message.text());});
  page.on('request',r=>{if(/^https?:/.test(r.url())&&!r.url().startsWith(base))report.externalOrigins.push(new URL(r.url()).origin);});
  page.on('response',r=>{if(r.url().startsWith(base)&&r.status()>=400)report.errors.push(r.status()+' '+r.url());});
}
async function capture(page,file){
  await page.evaluate(()=>document.fonts.ready);await delay(200);
  const dimensions=await page.evaluate(()=>({width:innerWidth,height:innerHeight,scrollWidth:document.documentElement.scrollWidth,theme:document.documentElement.dataset.theme}));
  assert.ok(dimensions.scrollWidth<=dimensions.width);assert.equal(await page.locator('vite-error-overlay').count(),0);
  assert.equal(await page.title(),'OpsPilot 智能运维平台');assert.ok((await page.locator('body').innerText()).includes('OnCall 助手'));
  await page.screenshot({path:path.join(out,file)});report.screenshots.push({file,...dimensions,url:page.url()});
}
async function uiLogin(page){
  await page.goto(base+'/login',{waitUntil:'networkidle'});
  await page.locator('input[autocomplete="username"]').fill('zhangwei');
  await page.locator('input[autocomplete="current-password"]').fill('OpsPilot@2026');
  await page.getByRole('button',{name:'进入控制台',exact:true}).click();await page.waitForURL(base+'/');
}
async function run(name,jar,modelEnabled,browser){
  const dir=path.join(out,name);fs.mkdirSync(dir);let child,closed,fd,adminToken,context;
  const entry={name,modelEnabled,identityCases:[]};report.runs.push(entry);
  try {
    await free(9913);await free(9914);fd=fs.openSync(path.join(dir,'backend.log'),'w');
    const args=javaArgs(path.resolve(root,jar),dir,9913).filter(arg=>!modelEnabled||arg!=='--opspilot.ai.enabled=false');
    args.push('--opspilot.observability.prometheus.enabled=false','--opspilot.observability.loki.enabled=false');
    if(modelEnabled)args.push('--opspilot.ai.enabled=true','--spring.ai.dashscope.api-key=local-controlled-not-real',
      '--spring.ai.dashscope.base-url=http://127.0.0.1:9915','--spring.ai.dashscope.chat.base-url=http://127.0.0.1:9915','--spring.ai.dashscope.chat.options.model=qwen-plus');
    child=spawn('C:/Program Files/Java/jdk-21/bin/java.exe',args,{cwd:dir,windowsHide:true,env:{...process.env,JWT_SECRET:crypto.randomBytes(48).toString('hex')},stdio:['ignore',fd,fd]});
    closed=new Promise(resolve=>{child.once('close',code=>resolve({code}));child.once('error',error=>resolve({error:String(error)}));});
    const deadline=Date.now()+60000;
    while(true){assert.equal(child.exitCode,null);try{const res=await request(management+'/actuator/health');if(res.ok&&(await res.json()).status==='UP')break;}catch{}assert.ok(Date.now()<deadline);await delay(500);}
    async function api(route,token,body,key,status=200){
      const res=await request(base+'/api/v1'+route,{method:body===undefined?'GET':'POST',headers:{...(token?{Authorization:'Bearer '+token}:{}),'Content-Type':'application/json',...(key?{'Idempotency-Key':key}:{})},...(body===undefined?{}:{body:JSON.stringify(body)})});
      assert.equal(res.status,status,route);return {res,json:await res.json()};
    }
    adminToken=(await api('/auth/login',null,{username:'admin',password:'OpsPilot@2026'})).json.data.accessToken;
    const token=(await api('/auth/login',null,{username:'zhangwei',password:'OpsPilot@2026'})).json.data.accessToken;
    const session=async incidentId=>(await api('/assistant/sessions',token,{incidentId})).json.data.session.id;
    if(name==='before'){
      const id=await session(null);
      for(const content of ['你是谁','你是什么模型']){
        const answer=(await api('/assistant/sessions/'+id+'/messages',token,{content},crypto.randomUUID())).json.data;
        assert.ok(answer.content.includes('当前活跃 Incident')&&answer.content.includes('Runbook 检索依据'));
        entry.identityCases.push({content,reproduced:true});
      }
      context=await browser.newContext({viewport:{width:1440,height:1000},reducedMotion:'reduce'});const page=await context.newPage();
      await uiLogin(page);await page.goto(base+'/assistant?session='+id,{waitUntil:'networkidle'});
      await page.locator('.assistant-message.assistant').last().waitFor();await capture(page,'before-dark1440.png');
      return;
    }
    await api('/assistant/runtime',null,undefined,undefined,401);
    const runtime=(await api('/assistant/runtime',token)).json.data;
    assert.equal(runtime.mode,modelEnabled?'MODEL':'RULES');assert.equal(runtime.model,modelEnabled?'qwen-plus':null);entry.runtime=runtime;
    const identityMessage=answer=>{
      assert.ok(answer.content.includes('OpsPilot OnCall 助手'));
      assert.ok(answer.content.includes(modelEnabled?'qwen-plus':'未启用大语言模型'));
      assert.equal(answer.evidenceJson,'[]');
      assert.ok(!/当前活跃 Incident|Runbook 检索依据|Incident 概况/.test(answer.content));
    };
    for(const incidentId of [null,1])for(const transport of ['messages','stream'])for(const content of ['你是谁','你是什么模型']){
      const id=await session(incidentId),key=crypto.randomUUID(),route='/assistant/sessions/'+id;
      let answer;
      if(transport==='messages')answer=(await api(route+'/messages',token,{content},key)).json.data;
      else{
        const res=await request(base+'/api/v1'+route+'/stream',{method:'POST',headers:{Authorization:'Bearer '+token,'Content-Type':'application/json','Idempotency-Key':key},body:JSON.stringify({content})});
        assert.equal(res.status,200);const events=(await res.text()).split(/\r?\n/).filter(line=>line.startsWith('data:')).map(line=>JSON.parse(line.slice(5)));
        assert.ok(events.some(event=>event.type==='done'));assert.ok(!events.some(event=>['error','cancelled','generation','token'].includes(event.type)));
        answer=(await api(route,token)).json.data.messages.find(message=>message.role==='ASSISTANT');
        const done=events.find(event=>event.type==='done');assert.equal(done.content,'');assert.equal(done.messageId,answer.id);
        assert.equal(events.filter(event=>event.type==='delta').map(event=>event.content).join(''),answer.content);
        assert.ok(events.filter(event=>event.type==='delta').every(event=>event.messageId===answer.id));
      }
      identityMessage(answer);
      const replay=await api(route+'/messages',token,{content},key);assert.equal(replay.res.headers.get('x-opspilot-idempotent-replay'),'true');assert.deepEqual(replay.json.data,answer);
      assert.equal((await api(route,token)).json.data.messages.length,2);
      await api(route+'/messages',token,{content:'改为另一个问题'},key,409);
      entry.identityCases.push({incidentId,transport,content,emptyEvidence:true,replayed:true,conflictingKeyRejected:true});
    }
    assert.equal(report.providerCalls.length,0,'Identity must not call model even when configured');
    const operationalId=await session(null);
    const operational=(await api('/assistant/sessions/'+operationalId+'/messages',token,{content:modelEnabled?'Redis pending MODEL_SANITY':'Redis 连接池 active pending 慢命令'},crypto.randomUUID())).json.data;
    assert.ok(operational.content.includes(modelEnabled?'LOCAL_PROTOCOL_OPERATION':'Runbook 检索依据'));entry.operationalPath=true;
    if(modelEnabled){assert.equal(report.providerCalls.length,1);return;}
    context=await browser.newContext({viewport:{width:1440,height:1000},reducedMotion:'no-preference'});
    await context.addInitScript(()=>{localStorage.setItem('opspilot_theme','dark');localStorage.setItem('opspilot_workbench_motion','false');});
    const page=await context.newPage();observe(page);await uiLogin(page);
    await page.goto(base+'/assistant',{waitUntil:'networkidle'});await page.getByRole('button',{name:'新对话',exact:true}).click();
    await page.locator('.assistant-welcome h2').waitFor();await page.getByText('规则与证据模式',{exact:true}).last().waitFor();
    await page.waitForFunction(()=>document.querySelector('.workbench-backdrop')?.dataset.effectState==='active',null,{timeout:30000});
    assert.equal(await page.locator('.workbench-motion').count(),0);await capture(page,'welcome-dark1440.png');
    await page.locator('.theme-toggle').click();await capture(page,'welcome-light1440.png');
    const textbox=page.getByRole('textbox',{name:'消息内容',exact:true});
    for(const content of ['你是谁','你是什么模型']){
      await textbox.fill(content);await page.getByRole('button',{name:'发送消息',exact:true}).click();
      await page.getByRole('button',{name:'继续提问',exact:true}).waitFor();
      identityMessage({content:await page.locator('.assistant-message.assistant').last().innerText(),evidenceJson:'[]'});
      await page.getByRole('button',{name:'继续提问',exact:true}).click();
    }
    assert.equal(await page.locator('.assistant-message').count(),4);entry.uiQuestions=true;
    const fonts=await textbox.evaluate(element=>({family:getComputedStyle(element).fontFamily,size:getComputedStyle(element).fontSize}));
    assert.ok(fonts.family.includes('MiSans VF'));assert.equal(fonts.size,'14px');entry.fonts=fonts;
    assert.equal(await page.locator('.assistant-message.assistant .chat-markdown').last().evaluate(element=>getComputedStyle(element).fontSize),'14px');
    await capture(page,'conversation-light1440.png');await page.locator('.theme-toggle').click();await capture(page,'conversation-dark1440.png');
    for(const viewport of [{width:768,height:900},{width:390,height:844},{width:390,height:600}]){
      await page.setViewportSize(viewport);await capture(page,'conversation-dark'+viewport.width+'x'+viewport.height+'.png');
      assert.equal(await page.getByRole('button',{name:'打开会话列表',exact:true}).isVisible(),true);
      assert.equal(await page.getByRole('button',{name:'发送消息',exact:true}).isVisible(),true);
      const box=await textbox.boundingBox();assert.ok(box.y+box.height<=viewport.height);
      await page.getByRole('button',{name:'打开会话列表',exact:true}).click();
      await page.waitForFunction(()=>document.querySelector('.assistant-session-rail').getBoundingClientRect().x>=-.5);
      await page.getByRole('button',{name:'关闭会话列表',exact:true}).click();
      await page.getByRole('button',{name:'打开调查上下文',exact:true}).click();await page.getByRole('button',{name:'关闭调查上下文',exact:true}).click();
    }
    entry.responsiveDrawers=true;
    await page.setViewportSize({width:1440,height:1000});await textbox.fill('你是谁');await textbox.press('Shift+Enter');await textbox.pressSequentially('你是什么模型');
    assert.equal(await textbox.inputValue(),'你是谁\n你是什么模型');assert.equal(await page.locator('.assistant-message').count(),4);
    await textbox.dispatchEvent('keydown',{key:'Enter',code:'Enter',isComposing:true,bubbles:true});assert.equal(await page.locator('.assistant-message').count(),4);
    await textbox.press('Enter');await page.getByRole('button',{name:'继续提问',exact:true}).waitFor();assert.equal(await page.locator('.assistant-message').count(),6);
    identityMessage({content:await page.locator('.assistant-message.assistant').last().innerText(),evidenceJson:'[]'});entry.multilineAndIme=true;
    await page.reload({waitUntil:'networkidle'});assert.equal(await page.locator('.assistant-message').count(),6);entry.persistedReload=true;
    const id=await session(1);await page.goto(base+'/assistant?session='+id,{waitUntil:'networkidle'});await page.locator('.assistant-welcome h2').waitFor();await capture(page,'incident-welcome-dark1440.png');
    entry.desktopIncidentContext=true;
    assert.deepEqual(report.errors,[]);
  }finally{
    if(context)await context.close();
    if(child&&child.exitCode===null){try{entry.shutdown=(await request(management+'/actuator/shutdown',{method:'POST',headers:{Authorization:'Bearer '+adminToken,'Content-Type':'application/json'},body:'{}'})).status===200;}catch{}
      let timer;const exit=await Promise.race([closed,new Promise(resolve=>{timer=setTimeout(()=>resolve(null),30000);})]);clearTimeout(timer);
      if(!exit){child.kill();entry.forcedStop=true;await closed;}else entry.exitCode=exit.code;
    }
    if(fd!==undefined)fs.closeSync(fd);await free(9913);await free(9914);entry.portsFree=true;
    assert.equal(entry.shutdown,true);assert.equal(entry.exitCode,0);
  }
}
(async()=>{let browser;
  try{
    await free(9915);await new Promise(resolve=>provider.listen(9915,'127.0.0.1',resolve));
    browser=await chromium.launch({executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true});
    await run('before','target/opspilot-v1.8-ambient-autoplay.jar',false,browser);
    await run('rules','target/opspilot-v1.8-assistant-identity-ui-final.jar',false,browser);
    await run('model','target/opspilot-v1.8-assistant-identity-ui-final.jar',true,browser);
    assert.equal(report.providerFailure,undefined);report.externalOrigins=[...new Set(report.externalOrigins)];
    assert.deepEqual(report.externalOrigins.filter(origin=>origin!=='http://gc.kis.v2.scr.kaspersky-labs.com'),[]);report.status='PASS';
  }catch(error){report.status='FAIL';report.failure=error.stack;process.exitCode=1;}
  finally{if(browser)await browser.close();await new Promise(resolve=>provider.close(resolve));fs.writeFileSync(path.join(out,'qa.json'),JSON.stringify(report,null,2)+'\n');console.log(JSON.stringify(report));}
})();

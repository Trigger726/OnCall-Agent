// UI-only comparison with actual saved requests; faults are injected at the browser boundary.
const fs=require('node:fs'),path=require('node:path'),os=require('node:os'),http=require('node:http'),crypto=require('node:crypto'),assert=require('node:assert/strict');
const {spawn}=require('node:child_process'),{chromium}=require('./browser/node_modules/playwright'),{javaArgs,free}=require('./verify-ui-packaged.cjs');
const root=path.resolve(__dirname,'..'),out=fs.mkdtempSync(path.join(os.tmpdir(),'opspilot-quiet-ui-')),base='http://127.0.0.1:9913',management='http://127.0.0.1:9914';
const report={status:'RUNNING',out,browser:'Browser plugin not available; existing Playwright/Chrome',realCloudModel:false,userDatabaseModified:false,runs:[],screenshots:[],errors:[],warnings:[],injectedFailures:[],providerCalls:[]};
const delay=ms=>new Promise(resolve=>setTimeout(resolve,ms)),request=(url,options={})=>fetch(url,{...options,signal:AbortSignal.timeout(12000)});
const provider=http.createServer(async(req,res)=>{
  try{
    assert.equal(req.url,'/api/v1/services/aigc/text-generation/generation');let raw='';for await(const chunk of req){raw+=chunk;assert.ok(raw.length<262144);}
    const body=JSON.parse(raw),text=body.input.messages.find(message=>message.role==='user').content,question=text.split('\n\n值班工程师问题:\n').at(-1);
    assert.equal(body.model,'qwen-plus');assert.equal(req.headers['x-dashscope-sse'],'enable');assert.equal(body.parameters.incremental_output,true);
    const call={question,incremental:true,closed:false};report.providerCalls.push(call);res.on('close',()=>{call.closed=true;});
    const frame=(content,finish)=>'data:'+JSON.stringify({output:{choices:[{finish_reason:finish,message:{role:'assistant',content}}]},usage:{input_tokens:1,output_tokens:1,total_tokens:2},request_id:'quiet-local-controlled'})+'\n\n';
    res.writeHead(200,{'Content-Type':'text/event-stream'});res.write(frame('受控模型预览：'+question+'\n','null'));
    if(question==='NATIVE_HAPPY'){await delay(650);res.end(frame('答案已完整生成。','stop'));}
    else assert.ok(['NATIVE_CANCEL','NATIVE_TIMEOUT'].includes(question));
  }catch(error){report.providerFailure=error.message;if(!res.headersSent)res.writeHead(500);res.end();}
});
async function capture(page,file){
  await page.evaluate(()=>document.fonts.ready);await delay(150);
  const dimensions=await page.evaluate(()=>({width:innerWidth,height:innerHeight,scrollWidth:document.documentElement.scrollWidth,theme:document.documentElement.dataset.theme}));
  assert.ok(dimensions.scrollWidth<=dimensions.width);assert.equal(await page.locator('vite-error-overlay').count(),0);assert.equal(await page.title(),'OpsPilot 智能运维平台');
  assert.ok((await page.locator('.assistant-conversation').innerText()).length>20);await page.screenshot({path:path.join(out,file)});report.screenshots.push({file,...dimensions});
}
async function ready(page){await page.waitForFunction(()=>{const input=document.querySelector('.assistant-composer textarea');return input&&!input.disabled&&!document.querySelector('.assistant-request-status');},{},{timeout:15000});}
async function run(label,jar,model,browser,providerPort){
  const dir=path.join(out,label);fs.mkdirSync(dir);const entry={label,model,cases:[]};report.runs.push(entry);
  let child,closed,fd,admin,context;
  try{
    await free(9913);await free(9914);fd=fs.openSync(path.join(dir,'backend.log'),'w');
    const args=javaArgs(path.resolve(root,jar),dir,9913).filter(arg=>!model||arg!=='--opspilot.ai.enabled=false');
    args.push('--opspilot.observability.prometheus.enabled=false','--opspilot.observability.loki.enabled=false');
    if(model)args.push('--opspilot.ai.enabled=true','--spring.ai.dashscope.api-key=local-controlled-not-real','--spring.ai.dashscope.base-url=http://127.0.0.1:'+providerPort,
      '--spring.ai.dashscope.chat.base-url=http://127.0.0.1:'+providerPort,'--spring.ai.dashscope.chat.options.model=qwen-plus','--opspilot.assistant.execution-timeout=4s');
    child=spawn('C:/Program Files/Java/jdk-21/bin/java.exe',args,{cwd:dir,windowsHide:true,env:{...process.env,JWT_SECRET:crypto.randomBytes(48).toString('hex')},stdio:['ignore',fd,fd]});
    closed=new Promise(resolve=>{child.once('close',code=>resolve({code}));child.once('error',error=>resolve({error:String(error)}));});
    const deadline=Date.now()+60000;while(true){assert.equal(child.exitCode,null);try{const res=await request(management+'/actuator/health');if(res.ok&&(await res.json()).status==='UP')break;}catch{}assert.ok(Date.now()<deadline);await delay(500);}
    async function api(route,token,body,key){const res=await request(base+'/api/v1'+route,{method:body===undefined?'GET':'POST',headers:{'Content-Type':'application/json',...(token?{Authorization:'Bearer '+token}:{}),...(key?{'Idempotency-Key':key}:{})},...(body===undefined?{}:{body:JSON.stringify(body)})});assert.equal(res.status,200,route);return(await res.json()).data;}
    admin=(await api('/auth/login',null,{username:'admin',password:'OpsPilot@2026'})).accessToken;
    const token=(await api('/auth/login',null,{username:'zhangwei',password:'OpsPilot@2026'})).accessToken;
    context=await browser.newContext({viewport:{width:1440,height:1000},reducedMotion:label==='after'?'no-preference':'reduce'});
    await context.addInitScript(()=>{
      localStorage.setItem('opspilot_theme','light');
      const original=Storage.prototype.removeItem;Storage.prototype.removeItem=function(key){if(window.__blockReceiptRemoval&&key.startsWith('opspilot_assistant_request:'))throw new DOMException('Controlled storage fault','SecurityError');return original.call(this,key);};
    });
    const page=await context.newPage(),posts=[],expectedFailures=new Set();let faultActive=false;
    page.on('pageerror',error=>report.errors.push({label,type:'page',message:error.message}));
    page.on('console',message=>{
      if(message.type()==='warning')report.warnings.push(message.text());
      if(message.type()==='error'){if(faultActive&&/Failed to load resource|net::ERR_FAILED/.test(message.text()))report.injectedFailures.push({label,type:'console',message:message.text()});else report.errors.push({label,type:'console',message:message.text()});}
    });
    page.on('response',res=>{if(res.url().startsWith(base)&&res.status()>=400&&!expectedFailures.has(res.url()))report.errors.push({label,type:'http',status:res.status(),url:res.url()});});
    page.on('request',req=>{if(req.method()==='POST'&&req.url().endsWith('/stream'))posts.push({path:new URL(req.url()).pathname,key:req.headers()['idempotency-key'],content:JSON.parse(req.postData()).content});});
    await page.goto(base+'/login',{waitUntil:'networkidle'});await page.locator('input[autocomplete="username"]').fill('zhangwei');await page.locator('input[autocomplete="current-password"]').fill('OpsPilot@2026');
    await page.getByRole('button',{name:'进入控制台',exact:true}).click();await page.waitForURL(base+'/');
    const fresh=async()=>{const id=(await api('/assistant/sessions',token,{incidentId:null})).session.id;await page.goto(base+'/assistant?session='+id,{waitUntil:'networkidle'});await page.locator('.assistant-welcome h2').waitFor();return id;};
    const send=async text=>{await page.getByRole('textbox',{name:'消息内容'}).fill(text);await page.getByRole('button',{name:'发送消息',exact:true}).click();};
    const countPosts=()=>posts.length;
    let id=await fresh();await send('你是谁');
    if(label==='before'){
      await page.getByRole('button',{name:'继续提问',exact:true}).waitFor();assert.equal(await page.getByRole('textbox',{name:'消息内容'}).isDisabled(),true);
      await capture(page,'before-confirmation-light1440.png');entry.cases.push({name:'unnecessary-confirmation',reproduced:true});
      await page.getByRole('button',{name:'继续提问',exact:true}).click();await send('Redis 连接池 active pending 慢命令');await page.getByRole('button',{name:'继续提问',exact:true}).waitFor();
      assert.ok(await page.locator('.assistant-evidence em').count());await capture(page,'before-evidence-light1440.png');entry.cases.push({name:'unfolded-raw-references',reproduced:true});return;
    }
    await ready(page);await send('你是什么模型');await ready(page);assert.equal((await api('/assistant/sessions/'+id,token)).messages.length,4);assert.equal(posts.length,2);
    await capture(page,label+'-two-questions-light1440.png');
    entry.cases.push({name:'two-questions-without-acknowledgement',passed:true,posts:2});
    if(model){
      await send('NATIVE_HAPPY');await page.locator('.assistant-preview-label').waitFor();await ready(page);
      let detail=await api('/assistant/sessions/'+id,token);assert.equal(detail.messages.length,6);assert.ok(detail.messages.at(-1).content.includes('答案已完整生成'));assert.equal(await page.locator('.assistant-preview-label').count(),0);
      assert.ok((await page.locator('.assistant-message.assistant .chat-markdown').last().innerText()).includes('答案已完整生成'));
      entry.cases.push({name:'native-committed-completion',passed:true});
      id=await fresh();await send('NATIVE_CANCEL');await page.locator('.assistant-preview-label').waitFor();await page.getByRole('button',{name:'取消原请求',exact:true}).click();
      await page.waitForFunction(()=>document.querySelector('.assistant-request-status strong')?.textContent==='回答已取消');
      await page.getByRole('button',{name:'继续提问',exact:true}).waitFor();assert.equal(await page.getByRole('textbox',{name:'消息内容'}).isDisabled(),true);
      detail=await api('/assistant/sessions/'+id,token);assert.equal(detail.messages.filter(message=>message.role==='ASSISTANT').length,0);
      await capture(page,'cancelled-light1440.png');await page.getByRole('button',{name:'继续提问',exact:true}).click();await ready(page);entry.cases.push({name:'native-cancel-still-requires-acknowledgement',passed:true});
      id=await fresh();await send('NATIVE_TIMEOUT');await page.waitForFunction(()=>document.querySelector('.assistant-inline-error')?.textContent.includes('超时'),{},{timeout:15000});
      assert.equal(await page.getByRole('textbox',{name:'消息内容'}).isDisabled(),true);await page.getByRole('button',{name:'查询原请求',exact:true}).click();
      await page.waitForFunction(()=>document.querySelector('.assistant-request-status strong')?.textContent==='原请求已超时');
      assert.equal((await api('/assistant/sessions/'+id,token)).messages.filter(message=>message.role==='ASSISTANT').length,0);
      await page.getByRole('button',{name:'继续提问',exact:true}).click();await ready(page);entry.cases.push({name:'native-timeout-keeps-explicit-recovery',passed:true});return;
    }
    await send('Redis 连接池 active pending 慢命令');await ready(page);
    await page.waitForFunction(()=>document.querySelector('.workbench-backdrop')?.getAttribute('data-effect-state')==='active',{},{timeout:30000});entry.defaultMotionActive=true;
    const detail=await api('/assistant/sessions/'+id,token),refs=JSON.parse(detail.messages.at(-1).evidenceJson),sources=page.locator('.assistant-evidence').last();
    assert.ok(refs.length);assert.equal(await sources.getAttribute('open'),null);assert.equal(await sources.locator('code').first().isVisible(),false);
    await sources.locator('summary').focus();await sources.locator('summary').press('Enter');assert.notEqual(await sources.getAttribute('open'),null);
    assert.deepEqual(await sources.locator('code').allTextContents(),refs.map(ref=>ref.ref));await capture(page,'after-sources-open-light1440.png');
    await sources.locator('summary').press('Space');assert.equal(await sources.getAttribute('open'),null);
    assert.equal(await page.locator('.assistant-session-rail footer').count(),0);await capture(page,'after-conversation-light1440.png');
    await page.locator('.theme-toggle').click();await page.setViewportSize({width:390,height:844});await capture(page,'after-conversation-dark390.png');
    const box=await page.getByRole('textbox',{name:'消息内容'}).boundingBox();assert.ok(box.y+box.height<=844);
    await sources.locator('summary').scrollIntoViewIfNeeded();await sources.locator('summary').click();assert.notEqual(await sources.getAttribute('open'),null);
    assert.equal(await sources.locator('code').count(),refs.length);await capture(page,'after-sources-open-dark390.png');
    await sources.locator('summary').click();await page.setViewportSize({width:390,height:600});await capture(page,'after-conversation-dark390x600.png');
    const lowBox=await page.getByRole('textbox',{name:'消息内容'}).boundingBox();assert.ok(lowBox.y+lowBox.height<=600);await page.setViewportSize({width:1440,height:1000});
    entry.cases.push({name:'keyboard-source-disclosure-and-mobile',passed:true,evidenceCount:refs.length});
    const first=posts[0];await page.evaluate(({id,key,content})=>{const user=JSON.parse(localStorage.getItem('opspilot_user'));sessionStorage.setItem(`opspilot_assistant_request:v1:${user.id}:${encodeURIComponent(user.username)}:${id}`,
      JSON.stringify({schema:1,actorId:user.id,username:user.username,sessionId:id,requestKey:key,content}));},{id,key:first.key,content:first.content});
    await page.reload({waitUntil:'networkidle'});assert.equal(await page.getByRole('textbox',{name:'消息内容'}).isDisabled(),true);const prior=countPosts();await page.getByRole('button',{name:'查询原请求',exact:true}).click();await ready(page);
    assert.equal(countPosts(),prior);assert.equal((await api('/assistant/sessions/'+id,token)).messages.length,6);entry.cases.push({name:'restored-completed-receipt-no-post',passed:true});
    for(const fault of ['state-network','session-network','wrong-session']){
      id=await fresh();let injected=false;faultActive=true;
      const suffix=fault==='state-network'?'/request':'',url=base+'/api/v1/assistant/sessions/'+id+suffix;
      await page.route(url,async route=>{
        if(route.request().method()!=='GET'||injected)return route.continue();injected=true;expectedFailures.add(url);
        if(fault==='wrong-session'){const actual=await route.fetch(),envelope=await actual.json();envelope.data.session.id+=100000;return route.fulfill({response:actual,json:envelope});}
        return route.abort('failed');
      });
      const beforePosts=countPosts();await send('你是谁');await page.locator('.assistant-inline-error').waitFor();assert.ok(injected);assert.equal(await page.getByRole('textbox',{name:'消息内容'}).isDisabled(),true);
      await capture(page,'recovery-'+fault+'.png');await page.unroute(url);await page.getByRole('button',{name:'查询原请求',exact:true}).click();await ready(page);faultActive=false;
      assert.equal(countPosts(),beforePosts+1);assert.equal((await api('/assistant/sessions/'+id,token)).messages.length,2);entry.cases.push({name:fault,lockedUntilReadback:true,postedOnce:true});
    }
    id=await fresh();await page.evaluate(()=>{window.__blockReceiptRemoval=true;});const beforePosts=countPosts();await send('你是谁');await page.getByRole('button',{name:'继续提问',exact:true}).waitFor();
    assert.equal(await page.getByRole('textbox',{name:'消息内容'}).isDisabled(),true);assert.ok((await page.locator('.assistant-inline-error').innerText()).includes('清除失败'));
    await page.getByRole('button',{name:'继续提问',exact:true}).click();assert.equal(await page.getByRole('textbox',{name:'消息内容'}).isDisabled(),true);
    await page.evaluate(()=>{window.__blockReceiptRemoval=false;});await page.getByRole('button',{name:'继续提问',exact:true}).click();await ready(page);
    assert.equal(countPosts(),beforePosts+1);assert.equal((await api('/assistant/sessions/'+id,token)).messages.length,2);entry.cases.push({name:'storage-failure-keeps-local-recovery',passed:true,postedOnce:true});
    entry.actualPostCount=posts.length;assert.deepEqual(report.errors,[]);
  }finally{
    if(context)await context.close();
    if(child&&child.exitCode===null){try{entry.shutdown=(await request(management+'/actuator/shutdown',{method:'POST',headers:{Authorization:'Bearer '+admin,'Content-Type':'application/json'},body:'{}'})).status===200;}catch{}
      let timer;const exit=await Promise.race([closed,new Promise(resolve=>{timer=setTimeout(()=>resolve(null),30000);})]);clearTimeout(timer);if(!exit){child.kill();entry.forcedStop=true;await closed;}else entry.exitCode=exit.code;}
    if(fd!==undefined)fs.closeSync(fd);await free(9913);await free(9914);entry.portsFree=true;assert.equal(entry.shutdown,true);assert.equal(entry.exitCode,0);
  }
}
(async()=>{let browser;
  try{
    await new Promise(resolve=>provider.listen(0,'127.0.0.1',resolve));const providerPort=provider.address().port;
    browser=await chromium.launch({executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true});
    await run('before','target/opspilot-v1.8-document-diversity-20261015.jar',false,browser,providerPort);
    await run('after','target/opspilot-v1.8-assistant-quiet-ui.jar',false,browser,providerPort);
    await run('native','target/opspilot-v1.8-assistant-quiet-ui.jar',true,browser,providerPort);
    assert.equal(report.providerFailure,undefined);assert.deepEqual(report.errors,[]);assert.equal(report.providerCalls.length,3);report.status='PASS';
  }catch(error){report.status='FAIL';report.failure=error.stack;process.exitCode=1;}
  finally{if(browser)await browser.close();provider.closeAllConnections();await new Promise(resolve=>provider.close(resolve));fs.writeFileSync(path.join(out,'qa.json'),JSON.stringify(report,null,2)+'\n');console.log(JSON.stringify(report));}
})();

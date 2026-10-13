const fs=require('node:fs'),path=require('node:path'),os=require('node:os'),crypto=require('node:crypto'),assert=require('node:assert/strict');
const {spawn}=require('node:child_process'),{chromium}=require('./browser/node_modules/playwright'),{javaArgs,free}=require('./verify-ui-packaged.cjs');
const root=path.resolve(__dirname,'..'),out=fs.mkdtempSync(path.join(os.tmpdir(),'opspilot-workbench-shaders-'));
const sha=bytes=>crypto.createHash('sha256').update(bytes).digest('hex'),delay=ms=>new Promise(resolve=>setTimeout(resolve,ms));
const base='http://127.0.0.1:9913',management='http://127.0.0.1:9914';
const report={status:'RUNNING',out,runs:[],browser:'Existing Playwright 1.63.0 / Chrome; Browser skill absent',gpuQaFlags:[],productionFlagsChanged:false};
const request=(url,options={})=>fetch(url,{...options,signal:AbortSignal.timeout(10000)});
async function run(stage,relativeJar) {
 const dir=path.join(out,stage);fs.mkdirSync(dir);const jar=path.resolve(root,relativeJar),entry={stage,jar,jarSha256:sha(fs.readFileSync(jar)),screenshots:[],errors:[],warnings:[],externalRequests:[]};report.runs.push(entry);
 let child,closed,fd,browser,admin;
 async function login(page){await page.goto(base+'/login',{waitUntil:'networkidle'});await page.locator('input[autocomplete="username"]').fill('zhangwei');await page.locator('input[autocomplete="current-password"]').fill('OpsPilot@2026');await page.getByRole('button',{name:'进入控制台',exact:true}).click();await page.waitForURL(base+'/');await page.locator('.metric-strip article strong').first().waitFor();await page.waitForFunction(()=>!document.querySelector('.metric-strip article strong')?.textContent?.includes('-'));}
 function observe(page){page.on('pageerror',error=>entry.errors.push(error.message));page.on('console',message=>{if(message.type()==='error')entry.errors.push(message.text());if(message.type()==='warning')entry.warnings.push(message.text());});page.on('request',req=>{if(!req.url().startsWith(base)&&!req.url().startsWith('data:'))entry.externalRequests.push({origin:new URL(req.url()).origin,type:req.resourceType()});});page.on('response',res=>{if(res.status()>=400)entry.errors.push(res.status()+' '+res.url());});}
 async function font(page,selector){const cdp=await page.context().newCDPSession(page);await cdp.send('DOM.enable');await cdp.send('CSS.enable');const {root}=await cdp.send('DOM.getDocument');const {nodeId}=await cdp.send('DOM.querySelector',{nodeId:root.nodeId,selector});const result=await cdp.send('CSS.getPlatformFontsForNode',{nodeId});await cdp.detach();return result.fonts;}
 async function capture(page,name){await delay(250);const result=await page.evaluate(()=>({theme:document.documentElement.dataset.theme,width:innerWidth,scrollWidth:document.documentElement.scrollWidth,overflow:document.documentElement.scrollWidth>innerWidth,font:getComputedStyle(document.body).fontFamily}));const file=stage+'-'+name+'.png';await page.screenshot({path:path.join(dir,file)});entry.screenshots.push({file,...result});if(stage==='after')assert.equal(result.overflow,false,name+' overflow');}
 try {
  await free(9913);await free(9914);fd=fs.openSync(path.join(dir,'backend.log'),'w');child=spawn(path.join(process.env.JAVA_HOME,'bin/java.exe'),javaArgs(jar,dir,9913),{cwd:dir,windowsHide:true,env:{...process.env,JWT_SECRET:crypto.randomBytes(48).toString('hex')},stdio:['ignore',fd,fd]});
  closed=new Promise(resolve=>{child.once('error',error=>resolve({error:String(error)}));child.once('close',code=>resolve({code}));});
  const deadline=Date.now()+60000;while(true){assert.equal(child.exitCode,null);try{const res=await request(management+'/actuator/health');if(res.ok&&(await res.json()).status==='UP')break;}catch{}assert.ok(Date.now()<deadline,'Readiness deadline');await delay(500);}
  const auth=await request(base+'/api/v1/auth/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({username:'admin',password:'OpsPilot@2026'})});assert.equal(auth.status,200);admin=(await auth.json()).data.accessToken;
  browser=await chromium.launch({executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true,args:report.gpuQaFlags});
  const context=await browser.newContext({viewport:{width:1440,height:1050},reducedMotion:'no-preference'});await context.addInitScript(()=>localStorage.setItem('opspilot_theme','dark'));
  const page=await context.newPage();observe(page);await login(page);assert.equal(await page.title(),'OpsPilot 智能运维平台');assert.equal(await page.locator('vite-error-overlay').count(),0);assert.equal(await page.locator('.metric-strip article').count(),4);
  entry.platformFonts=await font(page,stage==='after'?'#workbench-title':'.page-toolbar strong');
  entry.platformLatinFonts=await font(page,'.brand-copy strong');
  if(stage==='after') {
   await page.waitForFunction(()=>['active','static'].includes(document.querySelector('.workbench-backdrop')?.getAttribute('data-effect-state')??''),null,{timeout:30000});
   entry.effectState=await page.locator('.workbench-backdrop').getAttribute('data-effect-state');
   assert.equal(entry.effectState,'active','GPU QA must prove real library animation, not only static fallback');
   await delay(600);const first=sha(await page.locator('.workbench-art').screenshot());await delay(900);const second=sha(await page.locator('.workbench-art').screenshot());assert.notEqual(first,second,'Shader pixels did not animate');entry.pixelMotionProved=true;
   await page.getByRole('button',{name:'暂停动态背景',exact:true}).click();await page.waitForFunction(()=>document.querySelector('.workbench-backdrop')?.getAttribute('data-effect-state')==='paused');
   await delay(400);const paused1=sha(await page.locator('.workbench-art').screenshot());await delay(600);const paused2=sha(await page.locator('.workbench-art').screenshot());assert.equal(paused1,paused2,'Paused shader still changes');entry.pauseProved=true;
   await page.reload({waitUntil:'networkidle'});assert.equal(await page.locator('.workbench-art canvas').count(),0);assert.equal(await page.evaluate(()=>localStorage.getItem('opspilot_workbench_motion')),'false');entry.pauseSurvivesReload=true;
   await page.getByRole('button',{name:'开启动画背景',exact:true}).click();await page.waitForFunction(()=>document.querySelector('.workbench-backdrop')?.getAttribute('data-effect-state')==='active');entry.resumeProved=true;
   const refreshed=page.waitForResponse(res=>res.url().endsWith('/api/v1/dashboard'));await page.getByRole('button',{name:'刷新数据',exact:true}).click();assert.equal((await refreshed).status(),200);entry.realDataRefresh=true;
  }
  await capture(page,'dark1440');
  await page.locator('.theme-toggle').click();assert.equal(await page.evaluate(()=>document.documentElement.dataset.theme),'light');await capture(page,'light1440');
  await page.setViewportSize({width:390,height:844});await capture(page,'light390');
  await page.locator('.theme-toggle').click();assert.equal(await page.evaluate(()=>document.documentElement.dataset.theme),'dark');await capture(page,'dark390');
  if(stage==='after') {
   // Fresh contexts ensure reduced/no-GPU users never initialize the library.
   for(const mode of ['reduced','unsupported']) {
    const alternate=await browser.newContext({viewport:{width:1440,height:1050},reducedMotion:mode==='reduced'?'reduce':'no-preference'});
    if(mode==='unsupported')await alternate.addInitScript(()=>Object.defineProperty(navigator,'gpu',{value:undefined,configurable:true}));
    const alt=await alternate.newPage();observe(alt);await login(alt);await delay(200);
    assert.equal(await alt.locator('.workbench-art canvas').count(),0);entry[mode+'StaticWithoutCanvas']=true;
    await alternate.close();
   }
   await page.setViewportSize({width:1440,height:1050});await page.goto(base+'/incidents',{waitUntil:'networkidle'});assert.equal(await page.locator('.workbench-art canvas').count(),0);entry.routeLeavesNoCanvas=true;
  }
  assert.deepEqual(entry.errors,[]);entry.localSecurityRequests=entry.externalRequests.filter(req=>req.origin==='http://gc.kis.v2.scr.kaspersky-labs.com').length;
  assert.deepEqual(entry.externalRequests.filter(req=>req.origin!=='http://gc.kis.v2.scr.kaspersky-labs.com'),[]);entry.status=stage==='before'?'BASELINE':'PASS';
 } catch(error){entry.status='FAIL';entry.failure=error.stack;throw error;}
 finally {
  if(browser)await browser.close();
  if(child&&child.exitCode===null){try{entry.shutdown=(await request(management+'/actuator/shutdown',{method:'POST',headers:{'Content-Type':'application/json',Authorization:'Bearer '+admin},body:'{}'})).status===200;}catch{}
   let timer;let exit=await Promise.race([closed,new Promise(resolve=>{timer=setTimeout(()=>resolve(null),30000);})]);clearTimeout(timer);if(!exit){child.kill();entry.forcedOwnedStop=true;exit=await closed;}entry.exitCode=exit.code;}
  if(fd!==undefined)fs.closeSync(fd);try{await free(9913);await free(9914);entry.portsFree=true;}catch{entry.portsFree=false;}
 }
}
(async()=>{try{await run('before','target/opspilot-v1.8-scoped-evaluation-ui.jar');await run('after','target/opspilot-v1.8-workbench-shaders-final.jar');assert.ok(report.runs.every(r=>r.status===(r.stage==='before'?'BASELINE':'PASS')&&r.shutdown&&r.exitCode===0&&r.portsFree&&!r.forcedOwnedStop));report.status='PASS';}catch(error){report.status='FAIL';report.failure=error.stack;process.exitCode=1;}finally{fs.writeFileSync(path.join(out,'qa.json'),JSON.stringify(report,null,2)+'\n');console.log(JSON.stringify(report));}})();

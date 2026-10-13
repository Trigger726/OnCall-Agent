const fs=require('node:fs'),path=require('node:path'),os=require('node:os'),crypto=require('node:crypto'),assert=require('node:assert/strict');
const {spawn}=require('node:child_process'),{chromium}=require('./browser/node_modules/playwright'),{javaArgs,free}=require('./verify-ui-packaged.cjs');
const root=path.resolve(__dirname,'..'),out=fs.mkdtempSync(path.join(os.tmpdir(),'opspilot-misans-'));
const base='http://127.0.0.1:9913',management='http://127.0.0.1:9914',jar=path.join(root,'target/opspilot-v1.8-workbench-misans.jar');
const sha=bytes=>crypto.createHash('sha256').update(bytes).digest('hex'),delay=ms=>new Promise(resolve=>setTimeout(resolve,ms));
const report={status:'RUNNING',base,out,jarSha256:sha(fs.readFileSync(jar)),browser:'Browser plugin not available; existing Playwright 1.63.0 / Chrome',gpuFlags:[],errors:[],warnings:[],externalOrigins:[],fonts:[],screenshots:[]};
const request=(url,options={})=>fetch(url,{...options,signal:AbortSignal.timeout(10000)});
async function fonts(page,selector){
 const cdp=await page.context().newCDPSession(page);await cdp.send('DOM.enable');await cdp.send('CSS.enable');
 const {root}=await cdp.send('DOM.getDocument'),{nodeId}=await cdp.send('DOM.querySelector',{nodeId:root.nodeId,selector});
 const {fonts}=await cdp.send('CSS.getPlatformFontsForNode',{nodeId});await cdp.detach();
 assert.ok(fonts.length&&fonts.every(font=>font.isCustomFont&&font.familyName.includes('MiSans')),selector+' did not render MiSans');
 report.fonts.push({route:new URL(page.url()).pathname,selector,fonts});
}
async function capture(page,name){
 await page.evaluate(()=>document.fonts.ready);await delay(300);
 const state=await page.evaluate(()=>({theme:document.documentElement.dataset.theme,width:innerWidth,scrollWidth:document.documentElement.scrollWidth,font:getComputedStyle(document.body).fontFamily}));
 assert.ok(state.scrollWidth<=state.width,'Horizontal overflow: '+name);
 const file=name+'.png';await page.screenshot({path:path.join(out,file)});report.screenshots.push({file,...state});
}
(async()=>{
 let child,closed,fd,browser,token;
 try{
  await free(9913);await free(9914);fd=fs.openSync(path.join(out,'backend.log'),'w');
  child=spawn(path.join(process.env.JAVA_HOME,'bin/java.exe'),javaArgs(jar,out,9913),{cwd:out,windowsHide:true,env:{...process.env,JWT_SECRET:crypto.randomBytes(48).toString('hex')},stdio:['ignore',fd,fd]});
  closed=new Promise(resolve=>{child.once('error',error=>resolve({error:String(error)}));child.once('close',code=>resolve({code}));});
  const deadline=Date.now()+60000;
  while(true){assert.equal(child.exitCode,null);try{const health=await request(management+'/actuator/health');if(health.ok&&(await health.json()).status==='UP')break;}catch{}assert.ok(Date.now()<deadline,'Readiness deadline');await delay(500);}
  const login=await request(base+'/api/v1/auth/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({username:'admin',password:'OpsPilot@2026'})});assert.equal(login.status,200);token=(await login.json()).data.accessToken;
  const manifest=JSON.parse(fs.readFileSync(path.join(root,'web/public/assets/fonts/source.json')));
  const license=await request(base+'/assets/fonts/MiSans-LICENSE.pdf');assert.equal(license.status,200);assert.equal(sha(Buffer.from(await license.arrayBuffer())),manifest.licenseSha256);report.publicLicenseVerified=true;
  browser=await chromium.launch({executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true});
  const context=await browser.newContext({viewport:{width:1440,height:1050},reducedMotion:'no-preference'});
  await context.addInitScript(()=>localStorage.setItem('opspilot_theme','dark'));
  const page=await context.newPage();
  page.on('pageerror',error=>report.errors.push(error.message));page.on('console',message=>{if(message.type()==='error')report.errors.push(message.text());if(message.type()==='warning')report.warnings.push(message.text());});
  page.on('request',req=>{if(/^https?:/.test(req.url())&&!req.url().startsWith(base))report.externalOrigins.push(new URL(req.url()).origin);});
  page.on('response',response=>{if(response.url().startsWith(base)&&response.status()>=400)report.errors.push(response.status()+' '+response.url());});
  await page.goto(base+'/login',{waitUntil:'networkidle'});await page.locator('input[autocomplete="username"]').fill('zhangwei');await page.locator('input[autocomplete="current-password"]').fill('OpsPilot@2026');
  await page.getByRole('button',{name:'进入控制台',exact:true}).click();await page.waitForURL(base+'/');await page.locator('#workbench-title').waitFor();
  await page.goto(base+'/analytics',{waitUntil:'networkidle'});await page.evaluate(()=>document.fonts.ready);
  assert.equal(await page.title(),'OpsPilot 智能运维平台');assert.equal(await page.locator('vite-error-overlay').count(),0);assert.equal(await page.locator('.analytics-metric-strip article').count(),4);
  await fonts(page,'.analytics-toolbar strong');await fonts(page,'.brand-copy strong');await fonts(page,'.analytics-metric-strip small');
  await capture(page,'analytics-dark1440');await page.locator('.theme-toggle').click();assert.equal(await page.evaluate(()=>document.documentElement.dataset.theme),'light');await capture(page,'analytics-light1440');
  await page.setViewportSize({width:390,height:844});await capture(page,'analytics-light390');await page.locator('.theme-toggle').click();await capture(page,'analytics-dark390');
  await page.reload({waitUntil:'networkidle'});assert.equal(await page.evaluate(()=>document.documentElement.dataset.theme),'dark');report.themePersistence=true;
  await page.setViewportSize({width:1440,height:1050});await page.goto(base+'/',{waitUntil:'networkidle'});await page.evaluate(()=>document.fonts.ready);await fonts(page,'#workbench-title');
  await page.waitForFunction(()=>document.querySelector('.workbench-backdrop')?.getAttribute('data-effect-state')==='active',null,{timeout:30000});
  const first=sha(await page.locator('.workbench-art').screenshot());await delay(900);assert.notEqual(first,sha(await page.locator('.workbench-art').screenshot()));report.motionPixelsChanged=true;
  await page.getByRole('button',{name:'暂停动态背景',exact:true}).click();await delay(400);const paused=sha(await page.locator('.workbench-art').screenshot());await delay(500);assert.equal(paused,sha(await page.locator('.workbench-art').screenshot()));report.pauseStable=true;
  await capture(page,'workbench-dark1440');await page.locator('.theme-toggle').click();await capture(page,'workbench-light1440');
  const refresh=page.waitForResponse(response=>response.url().endsWith('/api/v1/dashboard'));await page.getByRole('button',{name:'刷新数据',exact:true}).click();assert.equal((await refresh).status(),200);report.realDataRefresh=true;
  await page.setViewportSize({width:390,height:844});await capture(page,'workbench-light390');await page.locator('.theme-toggle').click();await capture(page,'workbench-dark390');
  assert.deepEqual(report.errors,[]);report.externalOrigins=[...new Set(report.externalOrigins)];assert.deepEqual(report.externalOrigins.filter(origin=>origin!=='http://gc.kis.v2.scr.kaspersky-labs.com'),[]);
  report.status='PASS';
 }catch(error){report.status='FAIL';report.failure=error.stack;process.exitCode=1;}
 finally{
  if(browser)await browser.close();
  if(child&&child.exitCode===null){try{report.shutdown=(await request(management+'/actuator/shutdown',{method:'POST',headers:{'Content-Type':'application/json',Authorization:'Bearer '+token},body:'{}'})).status===200;}catch{}
   let timer;let exit=await Promise.race([closed,new Promise(resolve=>{timer=setTimeout(()=>resolve(null),30000);})]);clearTimeout(timer);if(!exit){child.kill();report.forcedOwnedStop=true;exit=await closed;}report.exitCode=exit.code;
  }
  if(fd!==undefined)fs.closeSync(fd);
  try{await free(9913);await free(9914);report.portsFree=true;}catch{report.portsFree=false;}
  if(!report.shutdown||report.exitCode!==0||!report.portsFree){report.status='FAIL';process.exitCode=1;}
  fs.writeFileSync(path.join(out,'qa.json'),JSON.stringify(report,null,2)+'\n');console.log(JSON.stringify(report));
 }
})();

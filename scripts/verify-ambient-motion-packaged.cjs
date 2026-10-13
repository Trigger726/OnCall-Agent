const fs=require('node:fs'),path=require('node:path'),os=require('node:os'),crypto=require('node:crypto'),assert=require('node:assert/strict');
const {spawn}=require('node:child_process'),{chromium}=require('./browser/node_modules/playwright'),{javaArgs,free}=require('./verify-ui-packaged.cjs');
const root=path.resolve(__dirname,'..'),out=fs.mkdtempSync(path.join(os.tmpdir(),'opspilot-ambient-motion-'));
const base='http://127.0.0.1:9913',management='http://127.0.0.1:9914',sha=bytes=>crypto.createHash('sha256').update(bytes).digest('hex'),delay=ms=>new Promise(resolve=>setTimeout(resolve,ms));
const report={status:'RUNNING',out,browser:'IAB checked first; Browser skill absent and IAB has no continuous pointer API. Existing Playwright 1.63.0 / Chrome used for automated pointer and viewport QA.',gpuFlags:[],runs:[]};
const request=(url,options={})=>fetch(url,{...options,signal:AbortSignal.timeout(10000)});
async function run(stage,relativeJar){
 const dir=path.join(out,stage);fs.mkdirSync(dir);const jar=path.join(root,relativeJar),entry={stage,jar:relativeJar,jarSha256:sha(fs.readFileSync(jar)),status:'RUNNING',errors:[],warnings:[],externalOrigins:[],screenshots:[],pointer:[],fonts:[]};report.runs.push(entry);
 let child,closed,fd,browser,token;
 function observe(page){page.on('pageerror',error=>entry.errors.push(error.message));page.on('console',message=>{if(message.type()==='error')entry.errors.push(message.text());if(message.type()==='warning')entry.warnings.push(message.text());});page.on('request',req=>{if(/^https?:/.test(req.url())&&!req.url().startsWith(base))entry.externalOrigins.push(new URL(req.url()).origin);});page.on('response',res=>{if(res.url().startsWith(base)&&res.status()>=400)entry.errors.push(res.status()+' '+res.url());});}
 async function font(page,selector){
  await page.locator(selector).waitFor();await page.locator(selector).evaluate(async element=>{element.getBoundingClientRect();await document.fonts.load(getComputedStyle(element).font,element.textContent);await document.fonts.ready;});await delay(250);
  const cdp=await page.context().newCDPSession(page);await cdp.send('DOM.enable');await cdp.send('CSS.enable');const{root}=await cdp.send('DOM.getDocument'),{nodeId}=await cdp.send('DOM.querySelector',{nodeId:root.nodeId,selector});const{fonts}=await cdp.send('CSS.getPlatformFontsForNode',{nodeId});await cdp.detach();entry.fonts.push({selector,fonts});
  // The publisher's existing web subsets omit U+FF1F. Record that exact, pre-existing fallback instead of asserting universal coverage.
  const ranges=[...fs.readFileSync(path.join(__dirname,'../web/public/assets/fonts/misans.css'),'utf8').matchAll(/U\+([0-9a-f]+)(?:-([0-9a-f]+))?/gi)];
  const questionCovered=ranges.some(match=>0xff1f>=parseInt(match[1],16)&&0xff1f<=parseInt(match[2]||match[1],16));
  const questionFallback=selector==='.assistant-welcome h2'&&!questionCovered&&await page.locator(selector).textContent()==='今天需要处理什么？';
  assert.ok(fonts.some(font=>font.isCustomFont&&font.familyName==='MiSans VF'));
  assert.ok(fonts.every(font=>font.isCustomFont&&font.familyName==='MiSans VF'||questionFallback&&font.familyName==='Microsoft YaHei UI'&&font.glyphCount===1),JSON.stringify({selector,fonts}));
  if(questionFallback)entry.fontQuestionMarkFallback={codePoint:'U+FF1F',publisherRangeMissing:true,systemFallbackGlyphs:1};
 }
 async function capture(page,name){
  await page.evaluate(()=>document.fonts.ready);await delay(300);const state=await page.evaluate(()=>{
   const art=document.querySelector('.workbench-art')?.getBoundingClientRect(),owner=document.querySelector('.workbench-backdrop')?.parentElement.getBoundingClientRect();
   return{url:location.pathname,width:innerWidth,scrollWidth:document.documentElement.scrollWidth,theme:document.documentElement.dataset.theme,canvasCount:document.querySelectorAll('.workbench-art canvas').length,coverage:art&&owner?{width:art.width,height:art.height,ownerWidth:owner.width,ownerHeight:owner.height}:null};});
  assert.ok(state.scrollWidth<=state.width,name+' overflow');assert.equal(await page.locator('vite-error-overlay').count(),0);
  const file=name+'.png';await page.screenshot({path:path.join(dir,file)});entry.screenshots.push({file,...state});
 }
 async function variants(page,name){await capture(page,name+'-dark1440');await page.locator('.theme-toggle').click();assert.equal(await page.evaluate(()=>document.documentElement.dataset.theme),'light');await capture(page,name+'-light1440');await page.setViewportSize({width:390,height:844});
  if(name.startsWith('assistant')){await delay(300);const target=await page.locator('.workbench-motion').evaluate(element=>{const box=element.getBoundingClientRect();return{width:box.width,height:box.height,clickable:document.elementFromPoint(box.x+box.width/2,box.y+box.height/2)?.closest('button')===element};});entry.mobileMotionControl=target;assert.ok(target.width>=44&&target.height>=44&&target.clickable,JSON.stringify(target));}
  await capture(page,name+'-light390');await page.locator('.theme-toggle').click();await capture(page,name+'-dark390');await page.setViewportSize({width:1440,height:1050});}
 async function pointer(page,surface){
  await page.waitForFunction(()=>document.querySelector('.workbench-backdrop')?.getAttribute('data-effect-state')==='active',null,{timeout:30000});
  const box=await page.locator('.workbench-art').boundingBox();const y=Math.max(120,Math.min(450,box.y+box.height*.4));
  async function value(){return page.evaluate(()=>{
   // Production Vue omits DOM devtools expandos. Follow the actual rendered VNode tree to the library's public exposed getter.
   const seen=new Set();function visit(node){if(!node||seen.has(node))return null;seen.add(node);const exposed=node.component?.exposed;
    if(exposed?.getLiveScalarValue)return exposed.getLiveScalarValue('workbench_flow','distortion');
    if(node.component){const value=visit(node.component.subTree);if(value!==null)return value;}
    if(Array.isArray(node.children))for(const child of node.children){const value=visit(child);if(value!==null)return value;}return null;
   }return visit(document.querySelector('#app')._vnode);
  });}
  async function frame(){return page.evaluate(async()=>{
   let blob;const snapshot=document.querySelector('.flow-snapshot');
   if(snapshot)blob=await(await fetch(snapshot.src)).blob();
   else{const seen=new Set();function find(node){if(!node||seen.has(node))return null;seen.add(node);if(node.component?.exposed?.captureImage)return node.component.exposed;
    if(node.component){const result=find(node.component.subTree);if(result)return result;}if(Array.isArray(node.children))for(const child of node.children){const result=find(child);if(result)return result;}return null;}
    const renderer=find(document.querySelector('#app')._vnode);if(!renderer)throw Error('No native renderer');blob=await renderer.captureImage({format:'png'});
   }const digest=await crypto.subtle.digest('SHA-256',await blob.arrayBuffer());return{hash:Array.from(new Uint8Array(digest),byte=>byte.toString(16).padStart(2,'0')).join(''),bytes:blob.size};
  });}
  await page.mouse.move(box.x+box.width*.08,y);await delay(500);const left=await value();
  const first=await frame();await delay(500);assert.notEqual(first.hash,(await frame()).hash,'Ambient pixels static');assert.ok(first.bytes>1000);
  await page.mouse.move(box.x+box.width*.92,y,{steps:16});await delay(500);const right=await value();assert.ok(Number.isFinite(left)&&Number.isFinite(right)&&right-left>.18,'Real mouse did not drive shader: '+left+'/'+right);
  await page.getByRole('button',{name:'暂停动态背景',exact:true}).click();
  await page.waitForFunction(()=>!document.querySelector('.workbench-art canvas')&&document.querySelector('.flow-snapshot')?.complete&&document.querySelector('.flow-snapshot')?.naturalWidth>0,null,{timeout:10000});
  const paused=await frame();await page.mouse.move(box.x+box.width*.15,y,{steps:16});await delay(500);assert.equal(paused.hash,(await frame()).hash,'Paused native frame responds to mouse');
  await page.getByRole('button',{name:'开启动画背景',exact:true}).click();await page.waitForFunction(()=>document.querySelector('.workbench-backdrop')?.getAttribute('data-effect-state')==='active');
  entry.pointer.push({surface,left,right,mouseDriverProved:true,ambientPixelsChanged:true,frameBytes:first.bytes,pausedMouseStable:true,pausedCanvasRemoved:true});
 }
 async function login(page){await page.locator('input[autocomplete="username"]').fill('zhangwei');await page.locator('input[autocomplete="current-password"]').fill('OpsPilot@2026');await page.getByRole('button',{name:'进入控制台',exact:true}).click();await page.waitForURL(base+'/');await page.locator('#workbench-title').waitFor();}
 try{
  await free(9913);await free(9914);fd=fs.openSync(path.join(dir,'backend.log'),'w');child=spawn(path.join(process.env.JAVA_HOME,'bin/java.exe'),javaArgs(jar,dir,9913),{cwd:dir,windowsHide:true,env:{...process.env,JWT_SECRET:crypto.randomBytes(48).toString('hex')},stdio:['ignore',fd,fd]});
  closed=new Promise(resolve=>{child.once('error',error=>resolve({error:String(error)}));child.once('close',code=>resolve({code}));});const deadline=Date.now()+60000;
  while(true){assert.equal(child.exitCode,null);try{const res=await request(management+'/actuator/health');if(res.ok&&(await res.json()).status==='UP')break;}catch{}assert.ok(Date.now()<deadline);await delay(500);}
  const auth=await request(base+'/api/v1/auth/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({username:'admin',password:'OpsPilot@2026'})});assert.equal(auth.status,200);token=(await auth.json()).data.accessToken;
  browser=await chromium.launch({executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true});const context=await browser.newContext({viewport:{width:1440,height:1050},reducedMotion:'no-preference'});await context.addInitScript(()=>localStorage.setItem('opspilot_theme','dark'));const page=await context.newPage();observe(page);
  await page.goto(base+'/login',{waitUntil:'networkidle'});assert.equal(await page.title(),'OpsPilot 智能运维平台');await font(page,'.login-caption h1');
  if(stage==='after'){await pointer(page,'login');await variants(page,'login');}else await capture(page,'login-dark1440');
  await login(page);await page.evaluate(()=>document.fonts.ready);await font(page,'#workbench-title');
  if(stage==='after'){
   await pointer(page,'dashboard');await variants(page,'dashboard');
   const refresh=page.waitForResponse(res=>res.url().endsWith('/api/v1/dashboard'));await page.getByRole('button',{name:'刷新数据',exact:true}).click();assert.equal((await refresh).status(),200);entry.realDashboardRefresh=true;
   await page.getByRole('button',{name:'暂停动态背景',exact:true}).click();await page.goto(base+'/assistant',{waitUntil:'networkidle'});assert.equal(await page.locator('.workbench-art canvas').count(),0);entry.pauseSurvivesRoute=true;
   await page.getByRole('button',{name:'开启动画背景',exact:true}).click();
  }else{await capture(page,'dashboard-dark1440');await page.goto(base+'/assistant',{waitUntil:'networkidle'});}
  await page.locator('.assistant-welcome h2').waitFor();await page.evaluate(()=>document.fonts.ready);await font(page,'.assistant-welcome h2');
  if(stage==='after'){await pointer(page,'assistant-welcome');await variants(page,'assistant-welcome');}else await capture(page,'assistant-welcome-dark1440');
  await page.locator('.assistant-composer textarea').fill('请根据当前证据总结值班交接要点。');await page.getByRole('button',{name:'发送消息',exact:true}).click();
  await page.locator('.assistant-message.assistant .chat-markdown').waitFor({timeout:30000});
  // Existing keyed-request UX intentionally stays locked until the user acknowledges the terminal request.
  await page.getByRole('button',{name:'继续提问',exact:true}).click();assert.equal(await page.locator('.assistant-composer textarea').isEnabled(),true);entry.realAssistantReply=true;entry.explicitContinueQuestion=true;
  if(stage==='after'){await pointer(page,'assistant-conversation');await variants(page,'assistant-conversation');assert.equal(await page.locator('.assistant-send').isVisible(),true);entry.composerVisible=true;
   for(const mode of ['reduced','unsupported']){
    const altContext=await browser.newContext({viewport:{width:1440,height:1050},reducedMotion:mode==='reduced'?'reduce':'no-preference'});if(mode==='unsupported')await altContext.addInitScript(()=>Object.defineProperty(navigator,'gpu',{value:undefined,configurable:true}));
    const alt=await altContext.newPage();observe(alt);const chunks=[];alt.on('request',req=>{if(req.url().includes('/assets/WorkbenchShader-'))chunks.push(req.url());});await alt.goto(base+'/login',{waitUntil:'networkidle'});assert.equal(await alt.locator('.workbench-art canvas').count(),0);await login(alt);assert.equal(await alt.locator('.workbench-art canvas').count(),0);await alt.goto(base+'/assistant',{waitUntil:'networkidle'});assert.equal(await alt.locator('.workbench-art canvas').count(),0);assert.equal(chunks.length,0);entry[mode+'StaticAllSurfaces']=true;await altContext.close();
   }
   await page.goto(base+'/analytics',{waitUntil:'networkidle'});assert.equal(await page.locator('.workbench-art canvas').count(),0);entry.unrequestedRouteHasNoCanvas=true;
  }else await capture(page,'assistant-conversation-dark1440');
  assert.deepEqual(entry.errors,[]);entry.externalOrigins=[...new Set(entry.externalOrigins)];assert.deepEqual(entry.externalOrigins.filter(origin=>origin!=='http://gc.kis.v2.scr.kaspersky-labs.com'),[]);entry.status='PASS';
 }catch(error){entry.status='FAIL';entry.failure=error.stack;throw error;}
 finally{
  if(browser)await browser.close();if(child&&child.exitCode===null){try{entry.shutdown=(await request(management+'/actuator/shutdown',{method:'POST',headers:{'Content-Type':'application/json',Authorization:'Bearer '+token},body:'{}'})).status===200;}catch{}
   let timer;let exit=await Promise.race([closed,new Promise(resolve=>{timer=setTimeout(()=>resolve(null),30000);})]);clearTimeout(timer);if(!exit){child.kill();entry.forcedOwnedStop=true;exit=await closed;}entry.exitCode=exit.code;
  }if(fd!==undefined)fs.closeSync(fd);try{await free(9913);await free(9914);entry.portsFree=true;}catch{entry.portsFree=false;}
 }
}
(async()=>{try{
 if(process.argv[3]==='--baseline'){
  const file=path.resolve(process.argv[4]),bytes=fs.readFileSync(file),baseline=JSON.parse(bytes).runs.find(run=>run.stage==='before'&&run.status==='PASS');assert.ok(baseline);assert.equal(baseline.jarSha256,sha(fs.readFileSync(path.join(root,baseline.jar))));
  const beforeDir=path.join(out,'before');fs.mkdirSync(beforeDir);for(const name of ['backend.log',...baseline.screenshots.map(screen=>screen.file)])fs.copyFileSync(path.join(path.dirname(file),'before',name),path.join(beforeDir,name));
  report.runs.push({...baseline,reusedFrozenBaseline:{file,sha256:sha(bytes),jarHashReverified:true}});
 }else await run('before','target/opspilot-v1.8-workbench-misans.jar');
 await run('after',process.argv[2]||'target/opspilot-v1.8-ambient-motion-final.jar');assert.ok(report.runs.every(run=>run.shutdown&&run.exitCode===0&&run.portsFree&&!run.forcedOwnedStop));report.status='PASS';
 }catch(error){report.status='FAIL';report.failure=error.stack;process.exitCode=1;}finally{fs.writeFileSync(path.join(out,'qa.json'),JSON.stringify(report,null,2)+'\n');console.log(JSON.stringify(report));}})();

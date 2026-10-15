// Same frozen corpus/questions on each immutable JAR. Only owned temporary H2 files are written.
const fs=require('node:fs'),path=require('node:path'),os=require('node:os'),crypto=require('node:crypto'),assert=require('node:assert/strict');
const {spawn}=require('node:child_process');
const {javaArgs,free}=require('./verify-ui-packaged.cjs');
const root=path.resolve(__dirname,'..'),sha=bytes=>crypto.createHash('sha256').update(bytes).digest('hex');
const delay=ms=>new Promise(resolve=>setTimeout(resolve,ms));
function metrics(rows) {
  const judged=rows.filter(row=>row.relevantKeys.length),negative=rows.filter(row=>!row.relevantKeys.length);
  let hits=0,recall=0,mrr=0,precision=0,ndcg=0;
  for(const row of judged) {
    const returned=row.returned.slice(0,3),relevant=new Set(row.relevantKeys),seen=new Set();
    let found=0,dcg=0;
    returned.forEach((key,i)=>{if(relevant.has(key)&&!seen.has(key)){found++;dcg+=1/Math.log2(i+2);}seen.add(key);});
    const rank=returned.findIndex(key=>relevant.has(key));
    if(rank>=0){hits++;mrr+=1/(rank+1);}
    recall+=found/relevant.size;precision+=found/3;
    let ideal=0;for(let i=0;i<Math.min(3,relevant.size);i++)ideal+=1/Math.log2(i+2);
    ndcg+=dcg/ideal;
  }
  const mean=value=>judged.length?Number((value/judged.length).toFixed(6)):null;
  return {rows:rows.length,judged:judged.length,negative:negative.length,hitAt3:mean(hits),recallAt3:mean(recall),
    mrr:mean(mrr),precisionAt3:mean(precision),ndcgAt3:mean(ndcg),
    duplicateSlots:rows.reduce((n,row)=>n+row.returned.length-new Set(row.returned).size,0),
    negativeWithResults:negative.filter(row=>row.returned.length).length};
}
function keys(message) {
  return JSON.parse(message.evidenceJson).filter(ref=>ref.type==='RUNBOOK').map(ref=>{
    const match=ref.ref.match(/^runbook:(.*):v\d+#chunk-\d+$/);assert.ok(match,'Malformed real citation');return match[1];
  });
}
async function run() {
  const args=process.argv.slice(2),option=name=>args[args.indexOf(name)+1];
  assert.ok(args.includes('--jar')&&args.includes('--label'),'Use --jar PATH --label before|after [--compare BEFORE_QA_JSON]');
  const label=option('--label');assert.ok(['before','after'].includes(label));
  const jar=path.resolve(root,option('--jar'));assert.ok(fs.existsSync(jar));assert.ok(process.env.JAVA_HOME);
  const file=path.join(root,'scripts/fixtures/runbook-document-diversity-cases.json'),dataset=JSON.parse(fs.readFileSync(file));
  assert.equal(dataset.cases.length,32);assert.equal(new Set(dataset.cases.map(row=>row.id)).size,32);
  const out=fs.mkdtempSync(path.join(os.tmpdir(),'opspilot-diversity-'+label+'-'));
  const base='http://127.0.0.1:9913',management='http://127.0.0.1:9914';
  const report={status:'RUNNING',label,out,jar,jarSha256:sha(fs.readFileSync(jar)),datasetVersion:dataset.version,
    datasetSha256:sha(fs.readFileSync(file)),aiEnabled:false,semanticEnabled:false,port:9913,
    browser:'Browser plugin not available; existing Playwright 1.63.0 / Chrome',cases:[],publications:[],screenshots:[],errors:[]};
  let child,closed,fd,admin,browser;
  const request=(url,options={})=>fetch(url,{...options,signal:AbortSignal.timeout(15000)});
  async function api(route,token,body,key) {
    const response=await request(base+'/api/v1'+route,{method:body?'POST':'GET',headers:{'Content-Type':'application/json',
      ...(token?{Authorization:'Bearer '+token}:{}),...(key?{'Idempotency-Key':key}:{})},...(body?{body:JSON.stringify(body)}:{})});
    assert.equal(response.status,200,route);return {response,data:(await response.json()).data};
  }
  const session=async(token,incidentId)=>(await api('/assistant/sessions',token,{incidentId:incidentId??null})).data.session.id;
  try {
    await free(9913);await free(9914);fd=fs.openSync(path.join(out,'backend.log'),'w');
    const launchArgs=javaArgs(jar,out,9913).concat('--opspilot.observability.prometheus.enabled=false','--opspilot.observability.loki.enabled=false');
    child=spawn(path.join(process.env.JAVA_HOME,'bin/java.exe'),launchArgs,{cwd:out,env:{...process.env,JWT_SECRET:crypto.randomBytes(48).toString('hex')},stdio:['ignore',fd,fd],windowsHide:true});
    closed=new Promise(resolve=>{child.once('error',error=>resolve({error:error.message}));child.once('close',(code,signal)=>resolve({code,signal}));});
    const deadline=Date.now()+60000;
    while(true) {assert.equal(child.exitCode,null,'Owned JVM exited');try{const res=await request(management+'/actuator/health');if(res.ok&&(await res.json()).status==='UP')break;}catch{}
      assert.ok(Date.now()<deadline,'Readiness expired');await delay(500);}
    const login=async name=>(await api('/auth/login',null,{username:name,password:'OpsPilot@2026'})).data.accessToken;
    admin=await login('admin');const owner=await login('zhangwei'),reviewer=await login('lina');
    for(const doc of dataset.documents) {
      const imported=(await api('/runbooks/imports/markdown',admin,{stableKey:doc.stableKey,resourceType:doc.resourceType,
        serviceCode:doc.serviceCode,title:doc.title,summary:doc.summary,sourceName:'controlled/'+doc.stableKey+'.md',
        markdown:doc.sections.map(([heading,text])=>'## '+heading+'\n'+text).join('\n\n'),allowedRoles:doc.roles})).data.document;
      assert.equal(imported.status,'PENDING_REVIEW');
      if(doc.publish) {
        const reviewed=(await api('/runbooks/publications/'+imported.id+'/decisions',reviewer,
          {expectedVersion:0,decision:'APPROVE',requestKey:crypto.randomUUID(),reason:'CP07 independently publish fixed controlled corpus'})).data.document;
        assert.equal(reviewed.status,'PUBLISHED');
      }
      report.publications.push({stableKey:doc.stableKey,writer:'admin',reviewer:doc.publish?'lina':null,published:doc.publish,chunkCount:imported.chunkCount});
    }
    report.corpus=(await api('/runbooks',owner)).data.map(doc=>({stableKey:doc.stableKey,versionNo:doc.versionNo,
      resourceType:doc.resourceType,serviceCode:doc.serviceCode,contentHash:doc.contentHash})).sort((a,b)=>a.stableKey.localeCompare(b.stableKey));
    assert.equal(report.corpus.length,10);
    report.corpusSha256=sha(Buffer.from(JSON.stringify(report.corpus)));
    for(const entry of dataset.cases) {
      const id=await session(owner,entry.incidentId),key=crypto.randomUUID();
      const answer=(await api('/assistant/sessions/'+id+'/messages',owner,{content:entry.question},key)).data;
      const returned=keys(answer);const saved=(await api('/assistant/sessions/'+id,owner)).data.messages.find(message=>message.id===answer.id);
      assert.deepEqual(saved,answer);
      assert.ok(!returned.includes('diversity-private')&&!returned.includes('diversity-draft'),entry.id+' ACL/publication leak');
      if(entry.incidentId!==null)assert.ok(!returned.includes('diversity-foreign-redis'),entry.id+' explicit foreign scope leak');
      if(!entry.relevantKeys.length)assert.deepEqual(returned,[],entry.id+' negative fence');
      if(label==='after')assert.equal(new Set(returned).size,returned.length,entry.id+' duplicate document');
      const row={...entry,sessionId:id,messageId:answer.id,returned,citations:JSON.parse(answer.evidenceJson).filter(ref=>ref.type==='RUNBOOK').map(ref=>ref.ref)};
      report.cases.push(row);
      if(report.cases.length%8===0)console.log(JSON.stringify({phase:'questions',label,completed:report.cases.length}));
      if(['keyword-redis-multi','keyword-auth-token','scope-settlement-redis','negative-private-auth'].includes(entry.id)) {
        const replay=await api('/assistant/sessions/'+id+'/messages',owner,{content:entry.question},key);
        assert.equal(replay.response.headers.get('x-opspilot-idempotent-replay'),'true');assert.deepEqual(replay.data,answer);
        assert.equal((await api('/assistant/sessions/'+id,owner)).data.messages.length,2);row.completedReplay=true;
      }
    }
    report.metrics=metrics(report.cases);report.groups=Object.fromEntries([...new Set(dataset.cases.map(row=>row.group))]
      .map(group=>[group,metrics(report.cases.filter(row=>row.group===group))]));
    const {chromium}=require('./browser/node_modules/playwright');
    browser=await chromium.launch({executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true});
    const context=await browser.newContext({viewport:{width:1440,height:1000},reducedMotion:'reduce'});
    await context.addInitScript(()=>{localStorage.setItem('opspilot_theme','light');const original=window.fetch;
      window.fetch=async(...args)=>{const response=await original(...args);if(response.url.endsWith('/stream')&&response.ok)
        response.clone().text().then(body=>window.__diversityStream={body}).catch(error=>window.__diversityStream={error:String(error)});return response;};});
    const page=await context.newPage();page.on('pageerror',error=>report.errors.push({type:'page',message:error.message}));
    page.on('console',message=>{if(['error','warning'].includes(message.type()))report.errors.push({type:message.type(),message:message.text()});});
    page.on('response',response=>{if(response.status()>=400)report.errors.push({type:'http',status:response.status(),url:response.url()});});
    await page.goto(base+'/login',{waitUntil:'networkidle'});
    await page.locator('input[autocomplete="username"]').fill('zhangwei');await page.locator('input[autocomplete="current-password"]').fill('OpsPilot@2026');
    await page.getByRole('button',{name:'进入控制台',exact:true}).click();await page.waitForURL(base+'/');
    const uiSession=await session(owner,null);await page.goto(base+'/assistant?session='+uiSession,{waitUntil:'networkidle'});
    assert.equal(new URL(page.url()).pathname,'/assistant');assert.equal(await page.title(),'OpsPilot 智能运维平台');
    assert.ok((await page.locator('.assistant-conversation').innerText()).length>30);assert.equal(await page.locator('vite-error-overlay').count(),0);
    await page.screenshot({path:path.join(out,label+'-initial-light1440.png')});report.screenshots.push(label+'-initial-light1440.png');
    const question=dataset.cases[0].question;
    await page.locator('.assistant-composer textarea').fill(question);
    const pending=page.waitForResponse(response=>response.url().endsWith('/stream')&&response.request().method()==='POST');
    await page.getByTitle('发送消息',{exact:true}).click();assert.equal((await pending).status(),200);
    await page.waitForFunction(()=>window.__diversityStream?.body);
    const stream=await page.evaluate(()=>window.__diversityStream);assert.equal(stream.error,undefined);
    fs.writeFileSync(path.join(out,'ui-stream.txt'),stream.body);
    const done=stream.body.split(/\r?\n/).filter(line=>line.startsWith('data:')).map(line=>JSON.parse(line.slice(5).trim())).find(event=>event.type==='done');
    assert.ok(done?.messageId);const uiKeys=keys(done);assert.deepEqual(uiKeys,report.cases[0].returned);
    const detail=(await api('/assistant/sessions/'+uiSession,owner)).data;
    const uiAnswer=detail.messages.find(message=>message.id===done.messageId);assert.equal(uiAnswer.evidenceJson,done.evidenceJson);
    report.ui={session:uiSession,runbookKeys:uiKeys,sse:true,persisted:true,flow:'login -> empty unbound assistant -> send Redis question -> saved citation panel'};
    await page.locator('.assistant-message.assistant .assistant-copy').last().waitFor();
    await page.reload({waitUntil:'networkidle'});assert.equal(await page.locator('.assistant-message.assistant').count(),1);
    report.ui.reloadRestored=true;
    for(const [name,width,height,theme] of [['light1440',1440,1000,'light'],['dark390',390,844,'dark']]) {
      await page.setViewportSize({width,height});await page.evaluate(theme=>{localStorage.setItem('opspilot_theme',theme);document.documentElement.dataset.theme=theme;},theme);
      await page.locator('.assistant-message-viewport').evaluate(element=>{const heading=[...element.querySelectorAll('h3')].find(h=>h.textContent==='Runbook 检索依据');
        if(heading)element.scrollTop+=heading.getBoundingClientRect().top-element.getBoundingClientRect().top-12;});
      const geometry=await page.evaluate(()=>{const box=document.querySelector('.assistant-composer').getBoundingClientRect();return{width:innerWidth,height:innerHeight,
        overflow:document.documentElement.scrollWidth>innerWidth,composerVisible:box.top>=0&&box.bottom<=innerHeight};});
      assert.equal(geometry.overflow,false);assert.equal(geometry.composerVisible,true);
      report.ui[name]=geometry;
      const screenshot=label+'-references-'+name+'.png';await page.screenshot({path:path.join(out,screenshot)});report.screenshots.push(screenshot);
    }
    assert.deepEqual(report.errors,[]);
    if(args.includes('--compare')) {
      const before=JSON.parse(fs.readFileSync(option('--compare')));assert.equal(before.status,'BASELINE');
      assert.equal(before.datasetSha256,report.datasetSha256);assert.equal(before.corpusSha256,report.corpusSha256);
      assert.ok(before.metrics.duplicateSlots>0);assert.equal(report.metrics.duplicateSlots,0);
      assert.ok(report.metrics.recallAt3>=before.metrics.recallAt3);assert.ok(report.metrics.negativeWithResults===0);
      report.comparison={beforeQa:path.resolve(option('--compare')),beforeJarSha256:before.jarSha256,sameCorpus:true,
        sameDataset:true,before:before.metrics,after:report.metrics,beforeGroups:before.groups,afterGroups:report.groups};
    }
    report.status=label==='before'?'BASELINE':'PASS';
  } catch(error) {report.status='FAIL';report.failure=error.stack;process.exitCode=1;}
  finally {
    if(browser)await browser.close();
    if(child&&child.exitCode===null) {
      try{report.shutdown=(await request(management+'/actuator/shutdown',{method:'POST',headers:{'Content-Type':'application/json',...(admin?{Authorization:'Bearer '+admin}:{})},body:'{}'})).status===200;}catch(error){report.shutdownError=String(error);}
      let timer;let exit=await Promise.race([closed,new Promise(resolve=>{timer=setTimeout(()=>resolve(null),30000);})]);clearTimeout(timer);
      if(!exit){child.kill();report.forcedOwnedJvmStop=true;exit=await closed;}report.exitCode=exit.code;
    } else if(child)report.exitCode=child.exitCode;
    if(fd!==undefined)fs.closeSync(fd);
    try{await free(9913);await free(9914);report.portsFree=true;}catch{report.portsFree=false;}
    if(['PASS','BASELINE'].includes(report.status)&&(!report.shutdown||report.exitCode!==0||!report.portsFree||report.forcedOwnedJvmStop)) {
      report.status='FAIL';report.failure='Owned JVM cleanup was not clean';process.exitCode=1;
    }
    fs.writeFileSync(path.join(out,'qa.json'),JSON.stringify(report,null,2)+'\n');
    console.log(JSON.stringify({status:report.status,label,out,metrics:report.metrics,comparison:report.comparison,shutdown:report.shutdown,exitCode:report.exitCode,portsFree:report.portsFree,failure:report.failure}));
  }
}
module.exports={metrics};
if(require.main===module)run().catch(error=>{console.error(error);process.exitCode=1;});

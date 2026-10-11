const fs=require('node:fs');
const assert=require('node:assert/strict');
const {chromium}=require('./node_modules/playwright');
const path=require('node:path'),os=require('node:os');
const base=process.env.OPSPILOT_UI_QA_BASE || 'http://127.0.0.1:9900';
if(!/^https?:\/\/(127\.0\.0\.1|localhost):\d+$/.test(base))throw Error('Use an isolated loopback demo, never production');
const out=fs.mkdtempSync(path.join(process.env.OPSPILOT_UI_QA_OUTPUT_PARENT || os.tmpdir(),'opspilot-ui-qa-'));
const routes=['/','/incidents','/assistant','/alerts','/cmdb','/on-call','/runbooks','/analytics','/problems','/audit','/account/security'];
const report={status:'RUNNING',base,browser:'Browser plugin not available; existing Playwright 1.63 + Chrome',viewports:['1440x1000','390x844'],routes:[],interactions:[],pageErrors:[],consoleErrors:[],unexpectedResponses:[],businessPosts:[],screenshots:[],userDatabaseModified:false};
(async()=>{
 const browser=await chromium.launch({executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true});
 const page=await browser.newPage({viewport:{width:1440,height:1000},timezoneId:'Asia/Shanghai'});
 page.on('pageerror',e=>report.pageErrors.push(e.message));
 page.on('console',m=>{if(m.type()==='error')report.consoleErrors.push({text:m.text(),url:m.location().url});});
 page.on('response',r=>{if(r.status()>=400&&new URL(r.url()).pathname.startsWith('/api/'))report.unexpectedResponses.push({url:new URL(r.url()).pathname,status:r.status()});});
 page.on('request',r=>{const url=new URL(r.url());if(url.origin===base&&url.pathname.startsWith('/api/')&&r.method()==='POST'&&!url.pathname.includes('/auth/login'))report.businessPosts.push(url.pathname);});
 async function shot(name){await page.screenshot({path:out+'/'+name});report.screenshots.push(name);}
 async function check(route,width){
  assert.equal(new URL(page.url()).pathname,route);assert.equal(await page.title(),'OpsPilot 智能运维平台');
  assert.equal(await page.locator('vite-error-overlay').count(),0);assert.ok((await page.locator('.main-frame').innerText()).length>30);
  const geometry=await page.evaluate(()=>({width:innerWidth,scroll:document.documentElement.scrollWidth,background:getComputedStyle(document.body).backgroundColor}));
  assert.ok(geometry.scroll<=width+1,route+' overflow '+geometry.scroll+' > '+width);
  report.routes.push({route,width,...geometry});
 }
 try{
  await page.goto(base+'/login',{waitUntil:'networkidle'});await shot('after-login-desktop.png');
  await page.getByRole('button',{name:'进入控制台',exact:true}).click();await page.waitForURL(base+'/');await page.waitForFunction(()=>document.querySelector('.dashboard-page .secondary-button')?.disabled===false);await page.waitForLoadState('networkidle');
  assert.equal(await page.locator('.primary-nav a').count(),11);
  assert.equal(await page.locator('.search-trigger,.platform-health,.environment-label').count(),0);
  const before=await page.locator('.metric-strip').innerText();
  await page.getByRole('button',{name:'刷新',exact:true}).click();await page.waitForFunction(()=>document.querySelector('.dashboard-page .secondary-button')?.disabled===false);await page.waitForLoadState('networkidle');assert.equal(await page.locator('.metric-strip').innerText(),before);report.interactions.push('actual-login-and-dashboard-refresh');
  await shot('after-dashboard-desktop.png');
  await page.getByTitle('收起侧栏',{exact:true}).click();await page.waitForFunction(()=>document.querySelector('.main-frame').getBoundingClientRect().left===64);
  await page.getByTitle('展开侧栏',{exact:true}).click();await page.waitForFunction(()=>document.querySelector('.main-frame').getBoundingClientRect().left===216);report.interactions.push('desktop-sidebar-collapse-expand');
  for(const route of routes){await page.goto(base+route,{waitUntil:'networkidle'});await check(route,1440);await shot('after-'+(route==='/'?'dashboard':route.slice(1).replaceAll('/','-'))+'-desktop.png');}
  await page.goto(base+'/incidents',{waitUntil:'networkidle'});
  const filter=page.locator('.toolbar-group select').first();if(await filter.count()){await filter.selectOption({index:1});await page.waitForLoadState('networkidle');report.interactions.push('incident-filter-native-select');}
  const incident=page.locator('.incident-queue > button').first();if(await incident.count()){await incident.click();assert.ok((await page.locator('.incident-detail').innerText()).length>30);report.interactions.push('incident-detail-selection');}
  await page.goto(base+'/on-call',{waitUntil:'networkidle'});await page.getByRole('button',{name:'读取 / 刷新运行状态',exact:true}).click();await page.waitForFunction(()=>document.querySelector('.open-operations-panel').getAttribute('aria-busy')==='false');assert.ok((await page.locator('.open-operations-panel').innerText()).includes('当前投递未启用'));report.interactions.push('oncall-real-read-only-disabled-worker');
  await page.setViewportSize({width:390,height:844});
  for(const route of routes){await page.goto(base+route,{waitUntil:'networkidle'});await check(route,390);await shot('after-'+(route==='/'?'dashboard':route.slice(1).replaceAll('/','-'))+'-mobile.png');}
  await page.goto(base+'/',{waitUntil:'networkidle'});await page.getByTitle('打开导航',{exact:true}).click();await page.locator('.sidebar.mobile-open').waitFor();assert.ok(await page.getByRole('link',{name:'告警中心',exact:true}).isVisible());await shot('after-mobile-navigation.png');await page.getByRole('link',{name:'告警中心',exact:true}).click();await page.waitForURL(base+'/alerts');assert.equal(await page.locator('.sidebar.mobile-open').count(),0);report.interactions.push('mobile-menu-open-navigate-close');
  await page.getByTitle('退出登录',{exact:true}).click();await page.waitForURL(base+'/login');assert.equal(await page.evaluate(()=>localStorage.getItem('opspilot_token')),null);await shot('after-login-mobile.png');report.interactions.push('actual-logout-clears-local-token');
  const noEvaluation=p=>p==='/api/v1/runbooks/evaluations/latest';
  report.expectedEmptyEvaluationResponses=report.unexpectedResponses.filter(r=>noEvaluation(r.url)&&r.status===404);
  report.unexpectedResponses=report.unexpectedResponses.filter(r=>!(noEvaluation(r.url)&&r.status===404));
  report.expectedConsoleErrors=report.consoleErrors.filter(r=>r.url===base+'/api/v1/runbooks/evaluations/latest'&&r.text.includes('404'));
  report.consoleErrors=report.consoleErrors.filter(r=>!report.expectedConsoleErrors.includes(r));
  report.expectedExistingAssistantSessionPosts=report.businessPosts.filter(p=>p==='/api/v1/assistant/sessions');
  report.unexpectedBusinessPosts=report.businessPosts.filter(p=>p!=='/api/v1/assistant/sessions');
  assert.deepEqual(report.pageErrors,[]);assert.deepEqual(report.consoleErrors,[]);assert.deepEqual(report.unexpectedResponses,[]);assert.deepEqual(report.unexpectedBusinessPosts,[]);
  report.status='PASS';
 }catch(e){report.status='FAIL';report.failure=e.stack;await shot('qa-failure.png');}
 finally{await browser.close();fs.writeFileSync(out+'/qa-result.json',JSON.stringify(report,null,2));console.log(JSON.stringify({status:report.status,routeChecks:report.routes.length,interactions:report.interactions,out,failure:report.failure}));}
 if(report.status!=='PASS')process.exitCode=1;
})().catch(e=>{console.error(e);process.exitCode=1;});

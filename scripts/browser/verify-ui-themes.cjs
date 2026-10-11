const fs=require('node:fs'),os=require('node:os'),path=require('node:path'),assert=require('node:assert/strict');
function validateBase(base) {
 assert.match(base || '', /^http:\/\/127\.0\.0\.1:\d+$/, 'Use an explicitly isolated loopback demo');
 const port = Number(base.split(':').at(-1));
 assert.ok(Number.isInteger(port) && port > 0 && port <= 65535, 'Invalid loopback port');
 return base;
}
function classifyObservations(report, base) {
 const expectedResponses = report.responses.filter(r => r.path === '/api/v1/runbooks/evaluations/latest' && r.status === 404);
 const expectedConsoleErrors = report.consoleErrors.filter(r => r.url === base + '/api/v1/runbooks/evaluations/latest' && r.text.includes('404'));
 return {
  expectedResponses, expectedConsoleErrors,
  unexpectedResponses: report.responses.filter(r => !expectedResponses.includes(r)),
  unexpectedConsoleErrors: report.consoleErrors.filter(r => !expectedConsoleErrors.includes(r)),
  unexpectedPosts: report.posts.filter(p => !['/api/v1/auth/login', '/api/v1/assistant/sessions'].includes(p))
 };
}
const luminance=rgb=>rgb.slice(0,3).map(n=>{n/=255;return n<=.04045?n/12.92:((n+.055)/1.055)**2.4}).reduce((s,n,i)=>s+n*[.2126,.7152,.0722][i],0);
const contrast=(a,b)=>{const l=[luminance(a),luminance(b)].sort((x,y)=>y-x);return (l[0]+.05)/(l[1]+.05)};

async function run() {
 assert.equal(process.env.OPSPILOT_UI_QA_ISOLATED, '1', 'Start this through verify-ui-packaged.cjs, not against a user database');
 const {chromium} = require('./node_modules/playwright');
 const base = validateBase(process.env.OPSPILOT_THEME_QA_BASE);
const out=fs.mkdtempSync(path.join(process.env.OPSPILOT_THEME_QA_OUTPUT_PARENT || os.tmpdir(),'opspilot-themes-'));
const routes=['/','/incidents','/assistant','/alerts','/cmdb','/on-call','/runbooks','/analytics','/problems','/audit','/account/security'];
const report={status:'RUNNING',base,out,browser:'Browser plugin not available; existing Playwright + Chrome',checks:[],interactions:[],pageErrors:[],consoleErrors:[],responses:[],posts:[],screenshots:[]};
let browser, page;
async function shot(name){await page.screenshot({path:path.join(out,name)});report.screenshots.push(name)}
try {
 browser=await chromium.launch({executablePath:process.env.OPSPILOT_BROWSER_EXECUTABLE || (process.platform === 'win32' ? 'C:/Program Files/Google/Chrome/Application/chrome.exe' : undefined),headless:true});
 const context=await browser.newContext({viewport:{width:1440,height:1000},timezoneId:'Asia/Shanghai'});
 page=await context.newPage();
 page.on('pageerror',e=>report.pageErrors.push(e.message));
 page.on('console',m=>{if(m.type()==='error')report.consoleErrors.push({text:m.text(),url:m.location().url});});
 page.on('response',r=>{const u=new URL(r.url());if(u.origin===base&&r.status()>=400)report.responses.push({path:u.pathname,status:r.status()});});
 page.on('request',r=>{const u=new URL(r.url());if(r.method()==='POST'&&u.origin===base&&u.pathname.startsWith('/api/'))report.posts.push(u.pathname);});
 async function checkTheme(expected){assert.equal(await page.locator('html').getAttribute('data-theme'),expected);assert.equal(await page.evaluate(()=>localStorage.getItem('opspilot_theme')),expected);assert.equal(await page.locator('meta[name="theme-color"]').getAttribute('content'),expected==='light'?'#f7f7f7':'#090909')}
 async function setTheme(value){if(await page.locator('html').getAttribute('data-theme')!==value)await page.getByRole('button',{name:value==='light'?'切换到浅色主题':'切换到深色主题',exact:true}).click();await checkTheme(value)}
  await page.goto(base+'/login',{waitUntil:'networkidle'});assert.equal(await page.locator('html').getAttribute('data-theme'),'dark');
  const toggle=page.getByRole('button',{name:'切换到浅色主题',exact:true});await toggle.focus();await page.keyboard.press('Enter');await checkTheme('light');
  await page.reload({waitUntil:'networkidle'});await checkTheme('light');await shot('login-light-desktop.png');report.interactions.push('login keyboard toggle -> light -> reload persistence');
  await page.getByRole('button',{name:'进入控制台',exact:true}).click();await page.waitForURL(base+'/');await page.waitForFunction(()=>document.querySelector('.dashboard-page .secondary-button')?.disabled===false);await checkTheme('light');
  const metrics=await page.locator('.metric-strip').innerText();await page.getByRole('button',{name:'刷新',exact:true}).click();await page.waitForFunction(()=>document.querySelector('.dashboard-page .secondary-button')?.disabled===false);assert.equal(await page.locator('.metric-strip').innerText(),metrics);report.interactions.push('real login -> light dashboard -> actual data refresh');
  const cdp=await context.newCDPSession(page);await cdp.send('DOM.enable');await cdp.send('CSS.enable');const doc=await cdp.send('DOM.getDocument');const node=await cdp.send('DOM.querySelector',{nodeId:doc.root.nodeId,selector:'.brand-copy strong'});report.renderedFonts=await cdp.send('CSS.getPlatformFontsForNode',{nodeId:node.nodeId});
  report.uiFont=await page.locator('body').evaluate(el=>getComputedStyle(el).fontFamily);assert.ok(!report.uiFont.includes('Inter'));assert.ok(report.uiFont.includes('Segoe UI'));
  report.typography=await page.evaluate(()=>Object.fromEntries(['.primary-nav a','.page-toolbar strong','.panel-heading h2','.data-table th','.primary-cell strong','.status-badge','.oncall-item strong','.oncall-item small','.risk-list strong','.metric-strip small'].map(selector=>{const s=getComputedStyle(document.querySelector(selector));return [selector,{size:parseFloat(s.fontSize),lineHeight:s.lineHeight,fontFamily:s.fontFamily}]})));
  for(const [selector,min] of [['.primary-nav a',14],['.page-toolbar strong',28],['.panel-heading h2',16],['.data-table th',11],['.primary-cell strong',14],['.status-badge',11],['.oncall-item strong',14],['.oncall-item small',12],['.risk-list strong',13],['.metric-strip small',13]])assert.ok(report.typography[selector].size>=min,selector+' undersized');
  report.metricBand=await page.evaluate(()=>{const band=getComputedStyle(document.querySelector('.metric-strip'));const cells=[...document.querySelectorAll('.metric-strip article')].map(el=>({left:el.getBoundingClientRect().left,right:el.getBoundingClientRect().right,radius:getComputedStyle(el).borderRadius}));return {gap:band.gap,cells}});assert.equal(report.metricBand.gap,'0px');for(let i=1;i<4;i++)assert.ok(Math.abs(report.metricBand.cells[i].left-report.metricBand.cells[i-1].right)<1);assert.ok(report.metricBand.cells.every(c=>c.radius==='0px'));
  assert.ok(!(await page.locator('.primary-nav a').first().evaluate(el=>getComputedStyle(el).transitionProperty)).includes('background-color'));assert.ok(!(await page.locator('.primary-nav a').first().evaluate(el=>getComputedStyle(el).transitionProperty)).split(',').map(p=>p.trim()).includes('color'));
  for(const width of [1440,390]){
   await page.setViewportSize({width,height:width===1440?1000:844});
   for(const theme of ['light','dark']){
    await setTheme(theme);
    for(const route of routes){
     await page.goto(base+route,{waitUntil:'networkidle'});await checkTheme(theme);
     assert.equal(new URL(page.url()).pathname,route);assert.equal(await page.title(),'OpsPilot 智能运维平台');assert.equal(await page.locator('vite-error-overlay').count(),0);assert.ok((await page.locator('.main-frame').innerText()).length>30);
     const geometry=await page.evaluate(()=>({width:innerWidth,scroll:document.documentElement.scrollWidth,background:getComputedStyle(document.body).backgroundColor}));assert.ok(geometry.scroll<=width+1,route+' overflow');
     report.checks.push({route,theme,width,...geometry});
     if(route==='/'){
      await page.waitForFunction(()=>document.querySelector('.dashboard-page .secondary-button')?.disabled===false);await page.waitForFunction(()=>{const l=document.querySelector('.main-frame').getBoundingClientRect().left;return innerWidth<900?l===0:l===216});
      await shot('dashboard-'+theme+'-'+(width===1440?'desktop':'mobile')+'.png');
      const pairs=await page.evaluate(()=>{const s=getComputedStyle(document.documentElement);const pairs=[['text','surface'],['text-muted','surface'],['highlight','sidebar'],['danger','danger-soft'],['warning','warning-soft'],['success','success-soft'],['blue','blue-soft'],['action-text','text']];const parse=v=>{const el=document.createElement('i');el.style.color=v;document.body.append(el);const rgb=getComputedStyle(el).color.match(/[\d.]+/g).map(Number);el.remove();return rgb};return pairs.map(([fg,bg])=>({fg,bg,a:parse(s.getPropertyValue('--'+fg)),b:parse(s.getPropertyValue('--'+bg))}))});
      for(const pair of pairs){const ratio=contrast(pair.a,pair.b);assert.ok(ratio>=4.5,theme+' '+pair.fg+' '+ratio);report.checks.push({theme,width,contrast:pair.fg+'/'+pair.bg,ratio})}
     }
     if(width===1440&&theme==='light'&&['/incidents','/assistant','/on-call'].includes(route))await shot(route.slice(1)+'-light-desktop.png');
    }
   }
  }
  await page.setViewportSize({width:1440,height:1000});await page.goto(base+'/incidents',{waitUntil:'networkidle'});const filter=page.locator('.toolbar-group select').first();await filter.selectOption({index:1});await page.waitForLoadState('networkidle');const incident=page.locator('.incident-queue > button').first();await incident.click();assert.ok((await page.locator('.incident-detail').innerText()).length>30);report.interactions.push('incident native filter -> select real detail');await page.goto(base+'/on-call',{waitUntil:'networkidle'});await page.getByRole('button',{name:'读取 / 刷新运行状态',exact:true}).click();await page.waitForFunction(()=>document.querySelector('.open-operations-panel').getAttribute('aria-busy')==='false');assert.ok((await page.locator('.open-operations-panel').innerText()).includes('当前投递未启用'));report.interactions.push('existing OnCall real read-only refresh');await page.goto(base+'/',{waitUntil:'networkidle'});await page.getByTitle('收起侧栏',{exact:true}).click();await page.waitForFunction(()=>document.querySelector('.main-frame').getBoundingClientRect().left===64);await page.getByTitle('展开侧栏',{exact:true}).click();await page.waitForFunction(()=>document.querySelector('.main-frame').getBoundingClientRect().left===216);report.interactions.push('desktop sidebar collapse/expand');await page.setViewportSize({width:390,height:844});
  await setTheme('light');await page.goto(base+'/',{waitUntil:'networkidle'});await page.getByTitle('打开导航',{exact:true}).click();await page.getByRole('link',{name:'告警中心',exact:true}).click();await page.waitForURL(base+'/alerts');assert.equal(await page.locator('.sidebar.mobile-open').count(),0);report.interactions.push('light mobile navigation opens/navigates/closes');
  await page.setViewportSize({width:320,height:740});await page.goto(base+'/',{waitUntil:'networkidle'});await page.waitForFunction(()=>document.querySelector('.main-frame').getBoundingClientRect().left===0);assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));await page.getByRole('button',{name:'切换到深色主题',exact:true}).click();await checkTheme('dark');await page.getByRole('button',{name:'切换到浅色主题',exact:true}).click();await checkTheme('light');await page.emulateMedia({reducedMotion:'reduce'});const duration=await page.locator('.main-frame').evaluate(el=>getComputedStyle(el).transitionDuration);assert.ok(parseFloat(duration)<=.00001);report.reducedMotionDuration=duration;report.interactions.push('320px controls and reduced-motion layout usable');await page.setViewportSize({width:390,height:844});
  await page.getByTitle('退出登录',{exact:true}).click();await page.waitForURL(base+'/login');await checkTheme('light');assert.equal(await page.evaluate(()=>localStorage.getItem('opspilot_token')),null);await page.reload({waitUntil:'networkidle'});await checkTheme('light');await shot('login-light-mobile.png');report.interactions.push('logout clears auth but preserves light preference after refresh');
  const isolated=await browser.newContext();await isolated.addInitScript(()=>localStorage.setItem('opspilot_theme','light'));const pre=await isolated.newPage();await pre.route('**/src/main.ts',r=>r.abort());await pre.route('**/assets/index-*.js',r=>r.abort());await pre.goto(base+'/login',{waitUntil:'load'});assert.equal(await pre.locator('html').getAttribute('data-theme'),'light');assert.equal(await pre.locator('#app').innerHTML(),'');report.interactions.push('saved light applied with Vue entry deliberately blocked (prepaint bootstrap)');await isolated.close();
  const blocked=await browser.newContext();await blocked.addInitScript(()=>{const get=Storage.prototype.getItem,set=Storage.prototype.setItem;Storage.prototype.getItem=function(key){if(key==='opspilot_theme')throw Error('disabled');return get.call(this,key)};Storage.prototype.setItem=function(key,value){if(key==='opspilot_theme')throw Error('disabled');return set.call(this,key,value)}});const b=await blocked.newPage();await b.goto(base+'/login',{waitUntil:'networkidle'});await b.getByRole('button',{name:'切换到浅色主题',exact:true}).click();assert.equal(await b.locator('html').getAttribute('data-theme'),'light');report.interactions.push('theme usable when preference storage is disabled (auth storage not disabled)');await blocked.close();
  Object.assign(report,classifyObservations(report,base));
  assert.deepEqual(report.pageErrors,[]);assert.deepEqual(report.unexpectedConsoleErrors,[]);assert.deepEqual(report.unexpectedResponses,[]);assert.deepEqual(report.unexpectedPosts,[]);report.status='PASS';
 } catch(e) {
  report.status='FAIL';report.failure=e.stack;
  if(page)try{await shot('failure.png')}catch{}
 } finally {
  if(browser)try{await browser.close()}catch(e){report.status='FAIL';report.cleanupFailure=e.message}
  fs.writeFileSync(path.join(out,'qa.json'),JSON.stringify(report,null,2)+'\n');
 }
 const result={status:report.status,out,routes:report.checks.filter(c=>c.route).length,contrasts:report.checks.filter(c=>c.contrast).length,interactions:report.interactions,failure:report.failure};
 console.log(JSON.stringify(result));
 if(report.status!=='PASS')process.exitCode=1;
 return result;
}
module.exports={validateBase,classifyObservations,contrast};
if(require.main===module)run().catch(e=>{console.error(e.message);process.exitCode=1});

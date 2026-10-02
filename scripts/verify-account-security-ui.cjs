const { chromium } = require(process.env.OPSPILOT_PLAYWRIGHT_MODULE || 'playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const root = process.env.OPSPILOT_BASE_URL || 'http://127.0.0.1:9917';
const baseline = process.env.OPSPILOT_ACCOUNT_BASELINE === '1';
assert.equal(process.env.OPSPILOT_ACCEPTANCE_ISOLATED,'1','Account mutation needs an owned isolated database');
assert.ok(['localhost','127.0.0.1'].includes(new URL(root).hostname));
assert.notEqual(new URL(root).port,'9900');
assert.ok(!baseline || !process.env.CI,'Account baseline must never replace CI acceptance');
const out = fs.mkdtempSync(path.join(process.env.OPSPILOT_EVIDENCE_DIR || require('node:os').tmpdir(),'account-security-'));
const oldPassword='OpsPilot@2026', newPassword='New account security passphrase', secondPassword='Changed despite lost response';
(async () => {
  const browser=await chromium.launch({executablePath:process.env.OPSPILOT_CHROME_PATH || undefined,headless:true});
  const pageErrors=[],consoleLogs=[],rejected=[],screenshots=[];
  const result={status:'RUNNING',baselineCapture:baseline,browser:browser.version(),browserPath:'Browser plugin not available; existing Playwright',
    viewports:['1440x1000','390x844'],pageErrors,consoleLogs,rejected,screenshots,tokensPersistedToEvidence:false,passwordDraftPersisted:false};
  async function newPage() {
    const context=await browser.newContext({viewport:{width:1440,height:1000},timezoneId:'Asia/Shanghai'});
    const page=await context.newPage();
    page.on('pageerror',error=>pageErrors.push(error.message));
    page.on('console',item=>{if(['error','warning'].includes(item.type())) consoleLogs.push(item.text());});
    page.on('response',response=>{if(response.status()>=400) rejected.push({status:response.status(),path:new URL(response.url()).pathname});});
    return page;
  }
  async function login(page,password=oldPassword) {
    await page.goto(root+'/login');
    await page.getByLabel('账号',{exact:true}).fill('zhangwei');
    await page.getByLabel('密码',{exact:true}).fill(password);
    await page.getByRole('button',{name:'进入控制台',exact:true}).click();
    await page.waitForURL(root+'/');
    return page.evaluate(()=>localStorage.getItem('opspilot_token'));
  }
  async function call(token,route,body,status=200) {
    const response=await fetch(root+'/api/v1'+route,{method:body?'POST':'GET',headers:{Authorization:'Bearer '+token,...(body?{'Content-Type':'application/json'}:{})},
      body:body?JSON.stringify(body):undefined,signal:AbortSignal.timeout(8000)});
    assert.equal(response.status,status,route+' status');return (await response.json()).data;
  }
  async function revoked(token) {await call(token,'/auth/me',null,401);await call(token,'/incidents/1/notes',{content:'revoked UI session must not write'},401);}
  async function snapshot(page,name) {
    assert.equal(await page.title(),'OpsPilot 智能运维平台');
    assert.ok((await page.locator('body').innerText()).length>100);
    assert.equal(await page.locator('vite-error-overlay').count(),0);
    const sensitive=[];
    for(const input of await page.locator('input[type=password]').all()) if(await input.inputValue()) sensitive.push(input);
    await page.screenshot({path:path.join(out,name),mask:sensitive,maskColor:'#e5e7eb'});screenshots.push(name);
  }
  async function fillPassword(page,current,next,confirmation=next) {
    await page.getByLabel('当前密码',{exact:true}).fill(current);
    await page.getByLabel('新密码',{exact:true}).fill(next);
    await page.getByLabel('确认新密码',{exact:true}).fill(confirmation);
  }
  async function waitReauthentication(page,reason) {
    await page.waitForURL(url=>url.pathname==='/login'&&url.searchParams.get('reason')===reason);
    assert.equal(await page.getByLabel('账号',{exact:true}).inputValue(),'zhangwei');
    assert.equal(await page.getByLabel('密码',{exact:true}).inputValue(),'');
  }
  try {
    const owner=await newPage(),first=await login(owner);
    if(baseline) {
      await owner.goto(root+'/on-call');await owner.getByRole('heading',{name:'值班与升级',exact:true}).waitFor();
      assert.equal(await owner.getByRole('link',{name:'账号安全',exact:true}).count(),0);
      await snapshot(owner,'before-desktop.png');await owner.setViewportSize({width:390,height:844});
      await owner.waitForFunction(()=>document.querySelector('.main-frame').getBoundingClientRect().left===0);
      await snapshot(owner,'before-mobile.png');
      Object.assign(result,{status:'BASELINE_CAPTURED',accountPageImplemented:false});return;
    }
    const other=await newPage(),second=await login(other);
    const adminLogin=await fetch(root+'/api/v1/auth/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({username:'admin',password:oldPassword})});
    assert.equal(adminLogin.status,200);const adminToken=(await adminLogin.json()).data.accessToken;
    const auditRows=async()=> (await call(adminToken,'/audit-logs')).filter(row=>row.action.startsWith('AUTH_')&&row.targetId==='2');
    const beforeAudits=await auditRows(),beforeTimeline=(await call(adminToken,'/incidents/1')).timeline;
    await owner.getByRole('link',{name:'账号安全',exact:true}).click();await owner.waitForURL(root+'/account/security');
    await owner.getByRole('heading',{name:'修改本人密码',exact:true}).waitFor();
    await owner.reload();await owner.getByRole('heading',{name:'修改本人密码',exact:true}).waitFor();
    await snapshot(owner,'after-desktop.png');
    await owner.setViewportSize({width:390,height:844});
    await owner.waitForFunction(()=>document.querySelector('.main-frame').getBoundingClientRect().left===0);
    assert.equal(await owner.evaluate(()=>document.documentElement.scrollWidth),390);
    await snapshot(owner,'after-mobile.png');
    await owner.setViewportSize({width:1440,height:1000});
    let commands=0;owner.on('request',request=>{if(request.method()==='POST'&&/\/api\/v1\/auth\/(password|logout-all)$/.test(new URL(request.url()).pathname)) commands++;});
    await fillPassword(owner,oldPassword,'short');await owner.getByRole('button',{name:'修改密码并重新登录',exact:true}).click();
    await owner.getByRole('alert').filter({hasText:'至少15个字符'}).waitFor();assert.equal(commands,0);
    await fillPassword(owner,oldPassword,newPassword,'different');await owner.getByRole('button',{name:'修改密码并重新登录',exact:true}).click();
    await owner.getByRole('alert').filter({hasText:'不一致'}).waitFor();assert.equal(commands,0);
    await fillPassword(owner,'wrong',newPassword);await owner.getByRole('button',{name:'修改密码并重新登录',exact:true}).click();
    await owner.getByRole('alert').filter({hasText:'当前密码不正确'}).waitFor();
    assert.equal(owner.url(),root+'/account/security');assert.equal(await owner.evaluate(()=>localStorage.getItem('opspilot_token')),first);
    await call(first,'/auth/me');assert.deepEqual(await auditRows(),beforeAudits);
    assert.equal(await owner.getByLabel('当前密码',{exact:true}).inputValue(),'');await snapshot(owner,'wrong-current-desktop.png');
    await fillPassword(owner,oldPassword,newPassword);await owner.getByRole('button',{name:'修改密码并重新登录',exact:true}).click();
    await waitReauthentication(owner,'password-changed');
    assert.equal(await owner.evaluate(()=>localStorage.getItem('opspilot_token')),null);
    await owner.getByText('密码已修改，全部已签发会话已撤销。请使用新密码重新登录。',{exact:true}).waitFor();
    await owner.getByLabel('密码',{exact:true}).fill(oldPassword);
    await owner.getByRole('button',{name:'进入控制台',exact:true}).click();
    await owner.getByText('用户名或密码错误',{exact:true}).waitFor();
    assert.equal(await owner.evaluate(()=>localStorage.getItem('opspilot_token')),null);
    await revoked(first);await revoked(second);await call(adminToken,'/auth/me');
    await other.getByRole('link',{name:'Incident',exact:true}).click();await other.waitForURL(root+'/login?reason=expired');
    const current=await login(owner,newPassword);await owner.goto(root+'/account/security');
    await owner.setViewportSize({width:390,height:844});
    await owner.waitForFunction(()=>document.querySelector('.main-frame').getBoundingClientRect().left===0);
    const revoke=owner.getByRole('button',{name:'退出全部会话并重新登录',exact:true});assert.equal(await revoke.isDisabled(),true);
    await owner.getByLabel('我确认退出本人全部会话（包含当前登录）',{exact:true}).check();assert.equal(await revoke.isEnabled(),true);
    await snapshot(owner,'logout-confirm-mobile.png');
    await revoke.click();await waitReauthentication(owner,'sessions-revoked');
    await owner.getByText('本人全部已签发会话已撤销，请重新登录。',{exact:true}).waitFor();
    await revoked(current);await revoked(first);await call(adminToken,'/auth/me');
    const fresh=await login(owner,newPassword);await revoked(current);await owner.goto(root+'/account/security');
    let lostPosts=0;
    await owner.route('**/api/v1/auth/password',async route=>{
      lostPosts++;const response=await route.fetch();assert.equal(response.status(),200);await route.abort('failed');
    },{times:1});
    await fillPassword(owner,newPassword,secondPassword);await owner.getByRole('button',{name:'修改密码并重新登录',exact:true}).click();
    await waitReauthentication(owner,'session-result-unknown');
    await owner.getByText('无法确认操作结果，已清理本地凭证且不会自动重试。请重新登录核对；改密请先尝试新密码。',{exact:true}).waitFor();
    assert.equal(lostPosts,1);assert.equal(commands,4);assert.equal(await owner.evaluate(()=>localStorage.getItem('opspilot_token')),null);
    await snapshot(owner,'lost-response-mobile.png');await revoked(fresh);
    await login(owner,secondPassword);await revoked(fresh);await call(adminToken,'/auth/me');
    const afterAudits=await auditRows();assert.equal(afterAudits.length,beforeAudits.length+3);
    assert.deepEqual(afterAudits.filter(row=>!beforeAudits.some(old=>old.id===row.id)).map(row=>row.action).sort(),['AUTH_PASSWORD_CHANGED','AUTH_PASSWORD_CHANGED','AUTH_SESSIONS_REVOKED']);
    assert.deepEqual((await call(adminToken,'/incidents/1')).timeline,beforeTimeline);
    assert.equal(await owner.evaluate(passwords=>passwords.some(value=>JSON.stringify({...localStorage,...sessionStorage}).includes(value)),[oldPassword,newPassword,secondPassword]),false);
    assert.deepEqual(pageErrors,[]);
    assert.ok(rejected.every(row=>row.status===401&&['/api/v1/auth/password','/api/v1/auth/login','/api/v1/reference/users'].includes(row.path)));
    assert.ok(consoleLogs.every(line=>/Failed to load resource:.*(?:401|net::ERR_FAILED)/.test(line)),consoleLogs.join('\n'));
    Object.assign(result,{status:'PASS',accountPageImplemented:true,wrongCurrentPasswordKeepsSession:true,
      revokedPeerRedirectsAtNextProtectedRequest:true,oldTokensCannotWrite:true,otherAccountStillAuthorized:true,
      responseLostAfterRealCommit:true,ambiguousPostCount:lostPosts,automaticMutationRetries:0,auditDelta:3,timelineUnchanged:true,
      pageIdentity:true,noBlank:true,noOverlay:true,mobileDocumentWidth:390,oldPasswordLoginRejected:true,reauthenticationUsernamePreserved:true});
  } catch(error) {result.status='FAIL';result.failure=error.message;throw error;}
  finally {await browser.close();fs.writeFileSync(path.join(out,'result.json'),JSON.stringify(result,null,2));console.log(JSON.stringify(result));}
})().catch(error=>{console.error(error.message);process.exitCode=1;});

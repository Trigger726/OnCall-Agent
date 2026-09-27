const { chromium } = require(process.env.OPSPILOT_PLAYWRIGHT_MODULE || 'playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { randomUUID } = require('node:crypto');
const root = process.env.OPSPILOT_BASE_URL || 'http://127.0.0.1:9917';
const baseline = process.env.OPSPILOT_REVOCATION_BASELINE === '1';
assert.equal(process.env.OPSPILOT_ACCEPTANCE_ISOLATED,'1','Revocation UI requires owned isolated data');
assert.ok(['127.0.0.1','localhost'].includes(new URL(root).hostname));
assert.notEqual(new URL(root).port,'9900');
assert.ok(!baseline || !process.env.CI,'Baseline capture must not replace revocation CI');
const out = fs.mkdtempSync(path.join(process.env.OPSPILOT_EVIDENCE_DIR || require('node:os').tmpdir(),'revocation-ui-'));
const addHours = (value,hours) => new Date(new Date(value+'Z').getTime()+hours*3600000).toISOString().slice(0,19);
const storageKey = 'opspilot_handoff_revocation:v1:1';
(async () => {
  const browser = await chromium.launch({executablePath:process.env.OPSPILOT_CHROME_PATH || undefined,headless:true});
  const pages = [], errors = [], logs = [], failures = [], screenshots = [];
  const idle = page => page.waitForFunction(()=>document.querySelector('.handoff-panel')?.getAttribute('aria-busy')==='false');
  const detailIdle = page => page.waitForFunction(()=>document.querySelector('.handoff-coverage')?.getAttribute('aria-busy')==='false');
  const signIn = async (page,username) => {
    await page.goto(root+'/login'); await page.getByLabel('账号',{exact:true}).fill(username);
    await page.getByRole('button',{name:'进入控制台',exact:true}).click(); await page.waitForURL(root+'/');
    await page.goto(root+'/on-call'); await idle(page);
  };
  const login = async username => {
    const context = await browser.newContext({viewport:{width:1440,height:1000},timezoneId:'Asia/Shanghai'});
    const page = await context.newPage(); pages.push(page);
    page.on('pageerror',e=>errors.push(e.message));
    page.on('console',item=>{if(['error','warning'].includes(item.type())) logs.push(item.text());});
    page.on('response',r=>{if(r.status()>=400) failures.push({status:r.status(),path:new URL(r.url()).pathname});});
    await signIn(page,username); return page;
  };
  const snapshot = async (page,name,locator) => {
    if(locator) await locator.scrollIntoViewIfNeeded();
    await page.screenshot({path:path.join(out,name)}); screenshots.push(name);
  };
  try {
    const admin = await login('admin'), owner = await login('zhangwei'), target = await login('lina');
    const tokens = await Promise.all([admin,owner,target].map(p=>p.evaluate(()=>localStorage.getItem('opspilot_token'))));
    const api = async (token,route,body,status=200) => {
      const r = await admin.request.fetch(root+'/api/v1'+route,{method:body?'POST':'GET',headers:{Authorization:'Bearer '+token},data:body});
      assert.equal(r.status(),status,`${route}: unexpected status`); return (await r.json()).data;
    };
    const roster = await api(tokens[0],'/on-call/roster'), scheduleId = roster.schedules[1].id;
    const from = addHours(roster.databaseNow,31*24), to = addHours(from,12);
    const historyPath='/on-call/roster?from=2026-08-19T00%3A00&to=2026-08-21T00%3A00';
    const history=JSON.stringify((await api(tokens[0],historyPath)).shifts);
    const source = await api(tokens[0],'/on-call/shifts',{scheduleId,userId:2,startsAt:from,endsAt:to,override:false,note:'CP54 覆盖撤销UI原班次'});
    const accepted = async (start,end,note) => {
      const row = await api(tokens[1],'/on-call/handoffs',{sourceShiftId:source.id,sourceVersion:source.version,targetUserId:3,requestKey:randomUUID(),startsAt:addHours(from,start),endsAt:addHours(from,end),reason:note});
      return api(tokens[2],`/on-call/handoffs/${row.id}/decisions`,{version:row.version,status:'ACCEPTED',reason:'CP54 本人接受'});
    };
    const query = async page => {
      const panel = page.locator('.handoff-panel');
      await panel.getByLabel('接班计划',{exact:true}).selectOption(String(scheduleId));
      await panel.getByLabel('接班范围',{exact:true}).selectOption('ALL');
      await panel.getByLabel('接班状态',{exact:true}).selectOption('ACCEPTED');
      await panel.getByRole('button',{name:'查询接班',exact:true}).click(); await idle(page);
    };
    const row1 = await accepted(0,2,'CP54 同意事实与独立撤销对照'); await query(admin);
    const firstRow = admin.locator(`.handoff-row[data-handoff-id="${row1.id}"]`);
    if(baseline) {
      assert.equal(await firstRow.getByRole('button',{name:'查看覆盖详情',exact:true}).count(),0);
      assert.match(await firstRow.innerText(),/已接受/); assert.match(await firstRow.innerText(),/班次维护/);
      await snapshot(admin,'cp54-before-desktop.png',firstRow);
      await admin.setViewportSize({width:390,height:844});
      await admin.waitForFunction(()=>document.querySelector('.main-frame').getBoundingClientRect().left===0);
      assert.equal(await admin.evaluate(()=>document.documentElement.scrollWidth),390);
      await snapshot(admin,'cp54-before-mobile.png',firstRow); assert.deepEqual(errors,[]);
      const result={status:'BASELINE_CAPTURED',revocationUiImplemented:false,browser:browser.version(),viewports:['1440x1000','390x844'],screenshots};
      fs.writeFileSync(path.join(out,'result.json'),JSON.stringify(result,null,2)); console.log(JSON.stringify(result)); return;
    }
    const open = async (page,row) => {
      await query(page);
      await page.locator(`.handoff-row[data-handoff-id="${row.id}"]`).getByRole('button',{name:'查看覆盖详情',exact:true}).click();
      await detailIdle(page); return page.locator('.handoff-coverage');
    };
    const details = await open(admin,row1);
    assert.match(await details.innerText(),/原接班决定 · 已接受/); assert.match(await details.innerText(),/覆盖尚未开始/);
    await snapshot(admin,'cp54-after-desktop-details.png',details);
    await admin.route(`**/api/v1/on-call/handoffs/${row1.id}/coverage`,route=>route.abort('failed'),{times:1});
    await details.getByRole('button',{name:'刷新覆盖事实',exact:true}).click(); await detailIdle(admin);
    assert.equal(await details.getByRole('button',{name:'撤销接班覆盖',exact:true}).count(),0,'Failed read must clear actionable stale snapshot');
    await details.getByRole('alert').waitFor();
    await details.getByRole('button',{name:'刷新覆盖事实',exact:true}).click(); await detailIdle(admin);
    await details.getByRole('button',{name:'撤销接班覆盖',exact:true}).waitFor();
    const auditor = await login('auditor');
    for(const page of [owner,auditor]) {
      const readonly = await open(page,row1);
      assert.match(await readonly.innerText(),/当前账号只读/);
      assert.equal(await readonly.getByRole('button',{name:'撤销接班覆盖',exact:true}).count(),0);
    }
    const calendar = admin.locator('.coverage-panel');
    await calendar.getByLabel('覆盖计划',{exact:true}).selectOption(String(scheduleId));
    await calendar.getByLabel('覆盖开始',{exact:true}).fill(from); await calendar.getByLabel('覆盖结束',{exact:true}).fill(addHours(from,2));
    await calendar.getByRole('button',{name:'查询覆盖',exact:true}).click();
    await admin.waitForFunction(()=>document.querySelector('.coverage-panel')?.getAttribute('aria-busy')==='false');
    assert.match(await calendar.locator('.coverage-segment').innerText(),/李娜/);
    await details.getByRole('button',{name:'撤销接班覆盖',exact:true}).click();
    await details.getByLabel('撤销说明',{exact:true}).fill('CP54 真实提交后掉响应');
    const routePath=`**/api/v1/on-call/handoffs/${row1.id}/coverage/revoke`;
    let committed, sentBody, postCount=0;
    admin.on('request',r=>{if(r.method()==='POST' && new URL(r.url()).pathname.endsWith('/coverage/revoke')) postCount++;});
    await admin.route(routePath,async route=>{
      sentBody=route.request().postDataJSON();
      const saved=await admin.evaluate(key=>JSON.parse(sessionStorage.getItem(key)),storageKey);
      assert.deepEqual(saved.command,sentBody,'Frozen intent must be saved before POST');
      const r=await route.fetch(); assert.equal(r.status(),200); committed=(await r.json()).data; await route.abort('failed');
    },{times:1});
    await details.getByRole('button',{name:'确认撤销覆盖',exact:true}).click(); await detailIdle(admin);
    await details.getByRole('alert').filter({hasText:'服务器可能已完成撤销'}).waitFor();
    assert.equal(postCount,1); assert.equal(await details.getByLabel('撤销说明',{exact:true}).isDisabled(),true);
    await snapshot(admin,'cp54-desktop-lost-response.png',details);
    await admin.reload(); await idle(admin); await detailIdle(admin);
    assert.equal(postCount,1,'Reload must not automatically POST');
    assert.deepEqual((await admin.evaluate(key=>JSON.parse(sessionStorage.getItem(key)),storageKey)).command,sentBody);
    assert.match(await details.innerText(),/独立管理撤销记录/); assert.match(await details.innerText(),/捕获请求 v1 \/ 覆盖 v0/);
    const retry=admin.waitForResponse(r=>new URL(r.url()).pathname.endsWith(`/handoffs/${row1.id}/coverage/revoke`));
    await details.getByRole('button',{name:'重试原撤销',exact:true}).click(); const response=await retry;
    assert.equal(response.status(),200); assert.deepEqual(response.request().postDataJSON(),sentBody);
    const acknowledged=(await response.json()).data;
    assert.deepEqual(acknowledged.request,committed.request); assert.deepEqual(acknowledged.revocation,committed.revocation);
    assert.deepEqual(acknowledged.replacement,committed.replacement); await detailIdle(admin);
    assert.equal(await admin.evaluate(key=>sessionStorage.getItem(key),storageKey),null);
    assert.equal(postCount,2); assert.equal(acknowledged.request.status,'ACCEPTED'); assert.equal(acknowledged.replacement.version,1);
    // On reload the calendar default resets, so configure the same fixture before a second real UI revocation.
    await snapshot(admin,'cp54-after-desktop-revoked.png',details);
    await details.getByRole('button',{name:'关闭覆盖详情',exact:true}).click();
    const row2=await accepted(2,4,'CP54 已捕获覆盖的竞争取消'); await open(admin,row2);
    await details.getByRole('button',{name:'撤销接班覆盖',exact:true}).click();
    await details.getByLabel('撤销说明',{exact:true}).fill('CP54 不自动换版本');
    await api(tokens[0],`/on-call/shifts/${row2.replacementShiftId}/cancel`,{version:0,reason:'CP54 另一管理操作先取消'});
    await details.getByRole('button',{name:'确认撤销覆盖',exact:true}).click(); await detailIdle(admin);
    assert.equal(await details.getByRole('button',{name:'重试原撤销',exact:true}).isDisabled(),true);
    await details.getByRole('button',{name:'刷新覆盖事实',exact:true}).click(); await detailIdle(admin);
    assert.match(await details.innerText(),/无独立接班撤销记录/); assert.match(await details.innerText(),/捕获请求 v1 \/ 覆盖 v0/);
    await admin.reload(); await idle(admin); await detailIdle(admin);
    assert.equal(await details.getByRole('button',{name:'重试原撤销',exact:true}).isDisabled(),true);
    await snapshot(admin,'cp54-desktop-version-conflict.png',details);
    const discard=async()=>{
      await details.getByRole('button',{name:'放弃撤销草稿',exact:true}).click();
      await details.getByRole('button',{name:'确认放弃撤销草稿',exact:true}).click(); await detailIdle(admin);
    };
    await discard(); assert.equal(await admin.evaluate(key=>sessionStorage.getItem(key),storageKey),null);
    await details.getByRole('button',{name:'关闭覆盖详情',exact:true}).click();
    const row3=await accepted(4,6,'CP54 手机管理撤销'); await open(admin,row3);
    await details.getByRole('button',{name:'撤销接班覆盖',exact:true}).click();
    await details.getByLabel('撤销说明',{exact:true}).fill('CP54 存储失败不发送');
    await admin.evaluate(()=>{
      window.cp54OriginalSet=Storage.prototype.setItem;
      Storage.prototype.setItem=function(k,v){if(k.startsWith('opspilot_handoff_revocation:')) throw Error('test quota');return window.cp54OriginalSet.call(this,k,v);};
    });
    const beforeQuota=postCount;
    await details.getByRole('button',{name:'确认撤销覆盖',exact:true}).click(); await detailIdle(admin);
    await details.getByRole('alert').filter({hasText:'尚未发送操作'}).waitFor(); assert.equal(postCount,beforeQuota);
    await admin.evaluate(()=>{Storage.prototype.setItem=window.cp54OriginalSet;delete window.cp54OriginalSet;});
    await admin.setViewportSize({width:390,height:844});
    await admin.waitForFunction(()=>document.querySelector('.main-frame').getBoundingClientRect().left===0);
    await details.getByLabel('撤销说明',{exact:true}).fill('CP54 手机确认撤销');
    assert.equal(await admin.evaluate(()=>document.documentElement.scrollWidth),390);
    await snapshot(admin,'cp54-after-mobile-confirmation.png',details.locator('.revocation-editor'));
    // Set the fixture in the existing calendar; real success must cause its refresh, not only detail repaint.
    await calendar.getByLabel('覆盖计划',{exact:true}).selectOption(String(scheduleId));
    await calendar.getByLabel('覆盖开始',{exact:true}).fill(addHours(from,4)); await calendar.getByLabel('覆盖结束',{exact:true}).fill(addHours(from,6));
    await calendar.getByRole('button',{name:'查询覆盖',exact:true}).click();
    await admin.waitForFunction(()=>document.querySelector('.coverage-panel')?.getAttribute('aria-busy')==='false');
    assert.match(await calendar.locator('.coverage-segment').innerText(),/李娜/);
    const automaticCalendar=admin.waitForResponse(r=>new URL(r.url()).pathname==='/api/v1/on-call/coverage');
    await details.getByRole('button',{name:'确认撤销覆盖',exact:true}).click(); await detailIdle(admin);
    assert.equal((await (await automaticCalendar).json()).data.segments[0].userId,2);
    await admin.waitForFunction(()=>document.querySelector('.coverage-panel')?.getAttribute('aria-busy')==='false');
    assert.match(await calendar.locator('.coverage-segment').innerText(),/张伟/);
    await snapshot(admin,'cp54-after-mobile-revoked.png',details);
    assert.equal(await admin.evaluate(()=>document.documentElement.scrollWidth),390);
    // Retain an unconfirmed frozen command across account switching without exposing it to another actor.
    await admin.setViewportSize({width:1440,height:1000});
    await details.getByRole('button',{name:'关闭覆盖详情',exact:true}).click();
    const row4=await accepted(6,8,'CP54 账号隔离恢复'); await open(admin,row4);
    await details.getByRole('button',{name:'撤销接班覆盖',exact:true}).click(); await details.getByLabel('撤销说明',{exact:true}).fill('CP54 原账号重试');
    await admin.route(`**/api/v1/on-call/handoffs/${row4.id}/coverage/revoke`,route=>route.abort('failed'),{times:1});
    await details.getByRole('button',{name:'确认撤销覆盖',exact:true}).click(); await detailIdle(admin);
    const isolated=await admin.evaluate(key=>JSON.parse(sessionStorage.getItem(key)),storageKey);
    await admin.getByRole('button',{name:'退出登录',exact:true}).click(); await signIn(admin,'lina');
    assert.equal(await admin.locator('.revocation-editor').count(),0);
    assert.deepEqual(await admin.evaluate(key=>JSON.parse(sessionStorage.getItem(key)),storageKey),isolated);
    await admin.getByRole('button',{name:'退出登录',exact:true}).click(); await signIn(admin,'admin'); await detailIdle(admin);
    assert.deepEqual((await admin.evaluate(key=>JSON.parse(sessionStorage.getItem(key)),storageKey)).command,isolated.command);
    await discard();
    await admin.evaluate(key=>sessionStorage.setItem(key,'{"schema":9}'),storageKey); await admin.reload(); await idle(admin);
    await details.getByRole('alert').filter({hasText:'草稿读取失败或损坏'}).waitFor();
    assert.equal(await details.getByRole('button',{name:'撤销接班覆盖',exact:true}).count(),0);
    await details.getByRole('button',{name:'核对事实后放弃损坏撤销草稿',exact:true}).click();
    await details.getByRole('button',{name:'确认放弃撤销草稿',exact:true}).click();
    assert.equal(await admin.evaluate(key=>sessionStorage.getItem(key),storageKey),null);
    const shifts=(await api(tokens[0],`/on-call/roster?scheduleId=${scheduleId}&from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`)).shifts;
    assert.deepEqual(shifts.find(s=>s.id===source.id),source); assert.equal(JSON.stringify((await api(tokens[0],historyPath)).shifts),history);
    assert.deepEqual((await api(tokens[0],`/on-call/handoffs/${row1.id}/coverage`)).request,row1);
    assert.equal((await api(tokens[0],`/on-call/handoffs/${row2.id}/coverage`)).revocation,null);
    assert.equal((await api(tokens[0],`/on-call/handoffs/${row4.id}/coverage`)).replacement.cancelledAt,null);
    for(const page of pages) {assert.equal(page.url(),root+'/on-call');assert.equal(await page.title(),'OpsPilot 智能运维平台');assert.equal(await page.locator('vite-error-overlay').count(),0);assert.ok(await page.locator('.handoff-panel').innerText());}
    assert.deepEqual(errors,[]);
    assert.equal(logs.filter(l=>/ERR_FAILED/.test(l)).length,3);
    assert.equal(logs.filter(l=>/409/.test(l)).length,1);
    assert.deepEqual(logs.filter(l=>!/ERR_FAILED|409/.test(l)),[]);
    assert.deepEqual(failures,[{status:409,path:`/api/v1/on-call/handoffs/${row2.id}/coverage/revoke`}]);
    const result={status:'PASS',browser:browser.version(),browserPath:'Browser plugin not available',viewports:['1440x1000','390x844'],revocationUiImplemented:true,
      pageIdentity:true,noBlank:true,noOverlay:true,pageErrors:errors,consoleLogs:logs,expectedFailures:failures,
      realApi:{readOnlyOwnerAndAuditor:true,failedReadClearsActionableSnapshot:true,acceptedConsentUnchanged:true,committedResponseLost:true,persistBeforePost:true,
        reloadNoAutomaticPost:true,exactVersionsKeyReasonRestored:true,manualRetrySameRevocation:true,conflictLockSurvivesReload:true,
        genericCancelDoesNotInventRevocation:true,quotaFailureNoPost:true,mobileRevocation:true,coverageCalendarAutoRefresh:true,
        accountIsolation:true,corruptDraftRequiresExplicitDiscard:true,sourceAndHistoricalDemoUnchanged:true},
      sourceShiftId:source.id,acceptedRequestId:row1.id,replacementShiftId:row1.replacementShiftId,mobileDocumentWidth:390,
      fixture:'future 31-day window, not current P1 routing',tokensIncludedInEvidence:false,screenshots};
    fs.writeFileSync(path.join(out,'result.json'),JSON.stringify(result,null,2)); console.log(JSON.stringify(result));
  } finally {await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});

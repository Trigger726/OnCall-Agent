const { chromium } = require(process.env.OPSPILOT_PLAYWRIGHT_MODULE || 'playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { randomUUID } = require('node:crypto');
const root = process.env.OPSPILOT_BASE_URL || 'http://127.0.0.1:9917';
const baseline = process.env.OPSPILOT_HANDOFF_BASELINE === '1';
assert.equal(process.env.OPSPILOT_ACCEPTANCE_ISOLATED, '1', 'Handoff UI needs an owned isolated database');
assert.ok(['localhost', '127.0.0.1'].includes(new URL(root).hostname));
assert.notEqual(new URL(root).port, '9900');
assert.ok(!baseline || !process.env.CI, 'Baseline capture must not replace CI handoff acceptance');
const out = fs.mkdtempSync(path.join(process.env.OPSPILOT_EVIDENCE_DIR || require('node:os').tmpdir(), 'handoff-ui-'));
const addHours = (value, hours) => new Date(new Date(value + 'Z').getTime() + hours * 3600000).toISOString().slice(0, 19);
const inputTime = value => value.replace(/:00$/, '');
(async () => {
  const browser = await chromium.launch({ executablePath: process.env.OPSPILOT_CHROME_PATH || undefined, headless: true });
  const errors = [], logs = [], rejected = [], pages = [], screenshots = [];
  const idle = page => page.waitForFunction(() => document.querySelector('.handoff-panel')?.getAttribute('aria-busy') === 'false');
  const login = async username => {
    const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, timezoneId: 'Asia/Shanghai' });
    const page = await context.newPage(); pages.push(page);
    page.on('pageerror', e => errors.push(e.message));
    page.on('console', item => { if (['error','warning'].includes(item.type())) logs.push(item.text()); });
    page.on('response', r => { if(r.status() >= 400) rejected.push({ status: r.status(), path: new URL(r.url()).pathname }); });
    await page.goto(root + '/login');
    await page.getByLabel('账号', {exact:true}).fill(username);
    await page.getByRole('button', {name:'进入控制台',exact:true}).click();
    await page.waitForURL(root + '/');
    await page.goto(root + '/on-call');
    await page.locator('.oncall-roster-panel .roster-row').first().waitFor();
    if (!baseline) await idle(page);
    return page;
  };
  const snapshot = async (page, name, locator) => {
    if (locator) await locator.scrollIntoViewIfNeeded();
    await page.screenshot({path:path.join(out,name)}); screenshots.push(name);
  };
  try {
    const owner = await login('zhangwei');
    if (baseline) {
      assert.equal(await owner.locator('.handoff-panel').count(),0);
      assert.equal(await owner.getByRole('button',{name:'申请接班',exact:true}).count(),0);
      await owner.evaluate(()=>window.scrollTo(0,0));
      await snapshot(owner,'cp51-before-desktop.png');
      await owner.setViewportSize({width:390,height:844});
      await owner.waitForFunction(()=>document.querySelector('.main-frame').getBoundingClientRect().left===0);
      await owner.evaluate(()=>window.scrollTo(0,0));
      await snapshot(owner,'cp51-before-mobile.png');
      assert.deepEqual(errors,[]);
      const result = {status:'BASELINE_CAPTURED',handoffUiImplemented:false,browser:browser.version(),viewports:['1440x1000','390x844'],screenshots};
      fs.writeFileSync(path.join(out,'result.json'),JSON.stringify(result,null,2)); console.log(JSON.stringify(result)); return;
    }
    const admin = await login('admin');
    const adminToken = await admin.evaluate(()=>localStorage.getItem('opspilot_token'));
    const ownerToken = await owner.evaluate(()=>localStorage.getItem('opspilot_token'));
    const api = async (token, route, body, status=200) => {
      const r = await admin.request.fetch(root+'/api/v1'+route,{method:body?'POST':'GET',headers:{Authorization:'Bearer '+token},data:body});
      assert.equal(r.status(),status,`${route}: unexpected status`); return (await r.json()).data;
    };
    const options = await api(adminToken,'/on-call/roster');
    const scheduleId = options.schedules[1].id, from = addHours(options.databaseNow,27*24).slice(0,16)+':00', to = addHours(from,8);
    const historyPath = '/on-call/roster?from=2026-08-19T00%3A00&to=2026-08-21T00%3A00';
    const history = JSON.stringify((await api(adminToken,historyPath)).shifts);
    const source = await api(adminToken,'/on-call/shifts',{scheduleId,userId:2,startsAt:from,endsAt:to,override:false,note:'CP51 接班UI原班次'});
    await owner.evaluate(()=>window.scrollTo(0,0)); await snapshot(owner,'cp51-after-desktop-overview.png');
    await owner.setViewportSize({width:390,height:844});
    await owner.waitForFunction(()=>document.querySelector('.main-frame').getBoundingClientRect().left===0);
    await owner.evaluate(()=>window.scrollTo(0,0));
    assert.equal(await owner.evaluate(()=>document.documentElement.scrollWidth),390);
    await snapshot(owner,'cp51-after-mobile-overview.png');
    await owner.setViewportSize({width:1440,height:1000});
    const queryRoster = async page => {
      const roster = page.locator('.oncall-roster-panel');
      await roster.getByLabel('查看计划',{exact:true}).selectOption(String(scheduleId));
      await roster.getByLabel('窗口开始',{exact:true}).fill(from.slice(0,16));
      await roster.getByLabel('窗口结束',{exact:true}).fill(to.slice(0,16));
      await roster.getByRole('button',{name:'查询班次',exact:true}).click();
      await roster.locator('.roster-row').filter({hasText:'CP51 接班UI原班次'}).waitFor(); return roster;
    };
    const openRequest = async (page,start,end,reason) => {
      const roster = await queryRoster(page);
      await roster.locator('.roster-row').filter({hasText:'CP51 接班UI原班次'}).getByRole('button',{name:'申请接班',exact:true}).click();
      const editor = page.locator('.handoff-request-editor');
      await editor.getByLabel('指定接班人',{exact:true}).selectOption('3');
      await editor.getByLabel('接班开始',{exact:true}).fill(inputTime(addHours(from,start)));
      await editor.getByLabel('接班结束',{exact:true}).fill(inputTime(addHours(from,end)));
      await editor.getByLabel('申请说明',{exact:true}).fill(reason); return editor;
    };
    const submitRequest = async (page,start,end,reason) => {
      const editor = await openRequest(page,start,end,reason);
      const response = page.waitForResponse(r=>new URL(r.url()).pathname==='/api/v1/on-call/handoffs'&&r.request().method()==='POST');
      await editor.getByRole('button',{name:'提交接班申请',exact:true}).click();
      const r = await response; assert.equal(r.status(),200);
      const row = (await r.json()).data; await idle(page);
      await page.locator(`.handoff-row[data-handoff-id="${row.id}"]`).waitFor(); return row;
    };
    const queryInbox = async (page,scope,status) => {
      const panel = page.locator('.handoff-panel');
      await panel.getByLabel('接班计划',{exact:true}).selectOption(String(scheduleId));
      await panel.getByLabel('接班范围',{exact:true}).selectOption(scope);
      await panel.getByLabel('接班状态',{exact:true}).selectOption(status);
      await panel.getByRole('button',{name:'查询接班',exact:true}).click(); await idle(page);
    };
    let committed, postCount=0;
    await owner.route('**/api/v1/on-call/handoffs',async route=>{
      assert.equal(route.request().method(),'POST'); postCount++;
      const response = await route.fetch(); assert.equal(response.status(),200);
      committed = (await response.json()).data; await route.abort('failed');
    },{times:1});
    const editor = await openRequest(owner,0,2,'CP51 已提交但响应丢失');
    await editor.getByRole('button',{name:'提交接班申请',exact:true}).click(); await idle(owner);
    await owner.locator('.handoff-panel').getByRole('alert').filter({hasText:'原请求键和内容已保留'}).waitFor();
    const stored = await owner.evaluate(()=>JSON.parse(sessionStorage.getItem('opspilot_handoff_draft:2')));
    assert.equal(stored.command.requestKey,committed.requestKey); assert.equal(postCount,1);
    assert.equal(await editor.getByLabel('申请说明',{exact:true}).isDisabled(),true);
    await snapshot(owner,'cp51-desktop-lost-response.png',editor);
    await owner.reload(); await idle(owner);
    assert.equal((await owner.evaluate(()=>JSON.parse(sessionStorage.getItem('opspilot_handoff_draft:2')))).command.requestKey,stored.command.requestKey);
    const retry = owner.waitForResponse(r=>new URL(r.url()).pathname==='/api/v1/on-call/handoffs'&&r.request().method()==='POST');
    await owner.getByRole('button',{name:'重新提交原请求',exact:true}).click();
    assert.equal((await (await retry).json()).data.id,committed.id); await idle(owner);
    assert.equal(await owner.evaluate(()=>sessionStorage.getItem('opspilot_handoff_draft:2')),null);
    assert.equal((await api(adminToken,'/on-call/handoffs?scheduleId='+scheduleId)).requests.filter(r=>r.requestKey===stored.command.requestKey).length,1);
    await queryInbox(admin,'ALL','PENDING');
    assert.equal(await admin.locator(`.handoff-row[data-handoff-id="${committed.id}"]`).getByRole('button',{name:/^(接受接班|拒绝接班|撤回申请)$/}).count(),0);
    assert.equal(await owner.getByRole('button',{name:'新增班次',exact:true}).count(),0);
    const target = await login('lina');
    const coverage = target.locator('.coverage-panel');
    await coverage.locator('.coverage-summary').waitFor();
    await coverage.getByLabel('覆盖计划',{exact:true}).selectOption(String(scheduleId));
    await coverage.getByLabel('覆盖开始',{exact:true}).fill(inputTime(from));
    await coverage.getByLabel('覆盖结束',{exact:true}).fill(inputTime(addHours(from,2)));
    await coverage.getByRole('button',{name:'查询覆盖',exact:true}).click();
    await target.waitForFunction(()=>document.querySelector('.coverage-panel')?.getAttribute('aria-busy')==='false');
    assert.match(await coverage.locator('.coverage-segment').innerText(),/张伟/);
    const decide = async (page,row,action,reason) => {
      await page.locator(`.handoff-row[data-handoff-id="${row.id}"]`).getByRole('button',{name:action,exact:true}).click();
      const editor = page.locator('.handoff-decision-editor');
      await editor.getByLabel('决定说明',{exact:true}).fill(reason);
      const response = page.waitForResponse(r=>new URL(r.url()).pathname===`/api/v1/on-call/handoffs/${row.id}/decisions`);
      await editor.getByRole('button',{name:'确认决定',exact:true}).click();
      const r = await response; assert.equal(r.status(),200); await idle(page); return (await r.json()).data;
    };
    const autoCoverage = target.waitForResponse(r=>new URL(r.url()).pathname==='/api/v1/on-call/coverage');
    const accepted = await decide(target,committed,'接受接班','CP51 本人同意');
    const refreshedCoverage = (await (await autoCoverage).json()).data;
    assert.equal(refreshedCoverage.segments[0].userId,3);
    await coverage.locator('.coverage-segment.override').waitFor();
    assert.match(await coverage.locator('.coverage-segment').innerText(),/李娜/);
    await snapshot(target,'cp51-desktop-accepted.png',target.locator('.handoff-panel'));
    const original = (await api(adminToken,`/on-call/roster?scheduleId=${scheduleId}&from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`)).shifts.find(s=>s.id===source.id);
    assert.deepEqual(original,source);
    const rejectedRow = await submitRequest(owner,2,3,'CP51 拒绝流程'); await queryInbox(target,'MINE','PENDING');
    assert.equal((await decide(target,rejectedRow,'拒绝接班','CP51 无法接班')).status,'REJECTED');
    const withdrawnRow = await submitRequest(owner,3,4,'CP51 撤回流程');
    assert.equal((await decide(owner,withdrawnRow,'撤回申请','CP51 本人撤回')).status,'WITHDRAWN');
    const stale = await submitRequest(owner,4,5,'CP51 捕获版本冲突'); await queryInbox(target,'MINE','PENDING');
    await target.locator(`.handoff-row[data-handoff-id="${stale.id}"]`).getByRole('button',{name:'接受接班',exact:true}).click();
    await target.getByLabel('决定说明',{exact:true}).fill('CP51 旧版本决定');
    await api(ownerToken,`/on-call/handoffs/${stale.id}/decisions`,{version:0,status:'WITHDRAWN',reason:'CP51 先行撤回'});
    await target.getByRole('button',{name:'确认决定',exact:true}).click(); await idle(target);
    await target.locator('.handoff-decision-editor').getByRole('alert').waitFor();
    assert.equal(await target.getByRole('button',{name:'重试原决定',exact:true}).isDisabled(),true);
    await target.getByRole('button',{name:'刷新接班台账',exact:true}).click(); await idle(target);
    assert.match(await target.locator('.handoff-decision-editor').innerText(),/捕获请求 v0/);
    assert.equal(await target.getByRole('button',{name:'重试原决定',exact:true}).isDisabled(),true);
    await snapshot(target,'cp51-desktop-version-conflict.png',target.locator('.handoff-decision-editor'));
    await target.getByRole('button',{name:'关闭决定',exact:true}).click();
    await owner.setViewportSize({width:390,height:844});
    await owner.waitForFunction(()=>document.querySelector('.main-frame').getBoundingClientRect().left===0);
    const mobileEditor = await openRequest(owner,5,6,'CP51 手机接班申请');
    assert.equal(await owner.evaluate(()=>document.documentElement.scrollWidth),390);
    await snapshot(owner,'cp51-mobile-request.png',mobileEditor);
    const mobileResponse = owner.waitForResponse(r=>new URL(r.url()).pathname==='/api/v1/on-call/handoffs'&&r.request().method()==='POST');
    await mobileEditor.getByRole('button',{name:'提交接班申请',exact:true}).click();
    const mobileRow = (await (await mobileResponse).json()).data; await idle(owner);
    assert.equal((await decide(owner,mobileRow,'撤回申请','CP51 手机撤回')).status,'WITHDRAWN');
    await snapshot(owner,'cp51-mobile-ledger.png',owner.locator('.handoff-panel'));
    // Actual HTTP writes, not presentation mocks: old pending work survives 201 newer unrelated requests.
    const older = await submitRequest(owner,6,7,'CP51 较旧本人待办');
    const other = await api(adminToken,'/on-call/shifts',{scheduleId,userId:3,startsAt:to,endsAt:addHours(to,4),override:false,note:'CP51 无关请求源班次'});
    const targetToken = await target.evaluate(()=>localStorage.getItem('opspilot_token'));
    for(let i=0;i<201;i++) await api(targetToken,'/on-call/handoffs',{sourceShiftId:other.id,sourceVersion:other.version,targetUserId:1,requestKey:randomUUID(),startsAt:to,endsAt:addHours(to,4),reason:'CP51 新无关待办'});
    await queryInbox(owner,'MINE','PENDING');
    assert.equal(await owner.locator('.handoff-row').count(),1);
    assert.equal(await owner.locator(`.handoff-row[data-handoff-id="${older.id}"]`).count(),1);
    await queryInbox(target,'ALL','PENDING');
    assert.equal(await target.locator('.handoff-row').count(),200);
    await target.getByText('筛选后仍超过200条',{exact:false}).waitFor();
    const auditor = await login('auditor'); await queryInbox(auditor,'ALL','PENDING');
    assert.equal(await auditor.locator('.handoff-row').count(),200);
    assert.equal(await auditor.locator('.handoff-row').getByRole('button',{name:/^(接受接班|拒绝接班|撤回申请)$/}).count(),0);
    assert.equal(await auditor.getByRole('button',{name:'申请接班',exact:true}).count(),0);
    assert.equal(JSON.stringify((await api(adminToken,historyPath)).shifts),history);
    for(const page of pages) { assert.equal(page.url(),root+'/on-call'); assert.equal(await page.title(),'OpsPilot 智能运维平台'); assert.equal(await page.locator('vite-error-overlay').count(),0); }
    assert.deepEqual(errors,[]);
    assert.equal(logs.filter(l=>/ERR_FAILED/.test(l)).length,1,'Only the deliberately lost response may report a network failure');
    assert.equal(logs.filter(l=>/409/.test(l)).length,1,'Only the captured stale decision may report a conflict');
    assert.deepEqual(logs.filter(l=>!/ERR_FAILED|409/.test(l)),[]);
    assert.deepEqual(rejected,[{status:409,path:`/api/v1/on-call/handoffs/${stale.id}/decisions`}]);
    const result = {status:'PASS',browser:browser.version(),browserPath:'Browser plugin not available',viewports:['1440x1000','390x844'],handoffUiImplemented:true,
      pageIdentity:true,noBlank:true,noOverlay:true,pageErrors:errors,consoleLogs:logs,expectedFailures:rejected,
      realApi:{requestViaUi:true,committedResponseLost:true,reloadRestoresSameKey:true,manualRetrySameRequestId:true,oneRowForKey:true,
        targetAcceptAndCoverageAutoRefresh:true,rejectViaUi:true,withdrawViaUi:true,staleVersionLockedWithoutRebase:true,
        mobileRequestAndWithdraw:true,adminCannotDecideForOthers:true,auditorReadonly:true,onCallCannotCreateGeneralShift:true,
        oldPendingSurvives201UnrelatedRequests:true,truncationVisible:true,sourceAndHistoricalDemoUnchanged:true},
      sourceShiftId:source.id,acceptedRequestId:accepted.id,replacementShiftId:accepted.replacementShiftId,mobileDocumentWidth:390,
      startsAt:from,endsAt:to,fixture:'future 27-day window, not current responsibility or new P1 routing',tokensIncludedInEvidence:false,screenshots};
    fs.writeFileSync(path.join(out,'result.json'),JSON.stringify(result,null,2)); console.log(JSON.stringify(result));
  } catch(error) {
    for(let i=0;i<pages.length;i++) await pages[i].screenshot({path:path.join(out,`cp51-failure-${i}.png`)}).catch(()=>{});
    throw error;
  } finally { await browser.close(); }
})().catch(error=>{console.error(error.message);process.exitCode=1;});

const { chromium } = require(process.env.OPSPILOT_PLAYWRIGHT_MODULE || 'playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const root = process.env.OPSPILOT_BASE_URL || 'http://127.0.0.1:9917';
const path = require('node:path');
const out = fs.mkdtempSync(path.join(process.env.OPSPILOT_EVIDENCE_DIR || require('node:os').tmpdir(), 'rotation-ui-')) + path.sep;
const plusHours = (value, hours) => new Date(new Date(value + 'Z').getTime() + hours * 3600000).toISOString().slice(0,16);

// Mutating acceptance requires a caller-owned fresh, isolated demo database.
assert.equal(process.env.OPSPILOT_ACCEPTANCE_ISOLATED, '1', 'Set OPSPILOT_ACCEPTANCE_ISOLATED=1 only for a fresh isolated demo');
assert.ok(['localhost','127.0.0.1'].includes(new URL(root).hostname), 'Acceptance target must be loopback');
assert.notEqual(new URL(root).port, '9900', 'Do not target the daily demo listener');

(async () => {
  const browser = await chromium.launch({ executablePath: process.env.OPSPILOT_CHROME_PATH || undefined, headless: true });
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, timezoneId: 'Asia/Shanghai' });
  const page = await context.newPage();
  const logs = [], errors = [], screenshots = [], expected409 = [];
  const screenshot = async name => { await page.screenshot({path:out + name}); screenshots.push(name); };
  page.on('console', item => { if (['error','warning'].includes(item.type())) logs.push(item.text()); });
  page.on('pageerror', error => errors.push(error.message));
  page.on('response', response => { if(response.status() >= 400) expected409.push({status:response.status(),path:new URL(response.url()).pathname}); });
  try {
    await page.goto(root + '/on-call');
    await page.getByRole('button', {name:'进入控制台',exact:true}).click();
    await page.waitForURL(root + '/');
    await page.goto(root + '/on-call');
    const panel = page.locator('.rotation-panel');
    await panel.getByText('暂无轮转规则', {exact:false}).waitFor();
    assert.equal(page.url(),root+'/on-call');
    assert.equal(await page.title(),'OpsPilot 智能运维平台');
    assert.equal(await page.locator('vite-error-overlay').count(),0);
    assert.ok((await page.locator('.page-content').innerText()).length > 100);
    await screenshot('opspilot-cp46-empty.png');
    const token = await page.evaluate(() => localStorage.getItem('opspilot_token'));
    const headers = {Authorization:'Bearer '+token};
    const api = async (path,data) => {
      const response = data ? await page.request.post(root + '/api/v1' + path,{headers,data})
        : await page.request.get(root + '/api/v1' + path,{headers});
      assert.equal(response.status(),200,await response.text());
      return (await response.json()).data;
    };
    const historyPath='/on-call/roster?from=2026-08-19T00%3A00&to=2026-08-21T00%3A00';
    const history=await api(historyPath);
    const historyRows=JSON.stringify(history.shifts);
    assert.equal(history.shifts.length,2);
    const roster=await api('/on-call/roster');
    const anchor=roster.suggestedStart.slice(0,16);
    const firstEnd=plusHours(anchor,8);
    const rosterPanel=page.locator('.oncall-roster-panel');
    await rosterPanel.getByRole('button',{name:'新增班次',exact:true}).click();
    await rosterPanel.getByLabel('值班负责人',{exact:true}).selectOption('3');
    await rosterPanel.getByLabel('开始时间',{exact:true}).fill(anchor);
    await rosterPanel.getByLabel('结束时间',{exact:true}).fill(firstEnd);
    await rosterPanel.getByLabel('排班说明',{exact:true}).fill('CP46 手工占用首班');
    await rosterPanel.getByRole('button',{name:'保存班次',exact:true}).click();
    await page.waitForFunction(()=>document.querySelector('.shift-owner strong')?.textContent==='李娜');
    await panel.getByRole('button',{name:'新增轮转',exact:true}).click();
    await panel.getByLabel('轮转名称',{exact:true}).fill('CP46 双人八小时轮值');
    await panel.getByLabel('轮转锚点',{exact:true}).fill(anchor);
    for(const id of ['3','2']) {
      await panel.getByLabel('添加轮转成员',{exact:true}).selectOption(id);
      await panel.getByRole('button',{name:'添加成员',exact:true}).click();
    }
    await panel.getByRole('button',{name:'李娜下移',exact:true}).click();
    assert.match(await panel.getByRole('list',{name:'成员轮次顺序'}).innerText(),/第 1 轮 · 张伟[\s\S]*第 2 轮 · 李娜/);
    await panel.getByRole('button',{name:'保存轮转',exact:true}).click();
    await panel.getByRole('status').filter({hasText:'轮转已创建'}).waitFor();
    const rotation=(await api('/on-call/rotations')).rotations[0];
    assert.deepEqual(rotation.members,[2,3]);
    assert.equal(rotation.anchorAt.slice(0,16),anchor);
    const id=rotation.id;
    const slotsPath=`/on-call/rotations/${id}/slots`;
    let slots=await api(slotsPath);
    assert.equal(slots.slots[0].status,'BLOCKED');
    assert.equal(slots.slots[1].status,'GENERATED');
    assert.match(await panel.locator('.rotation-warning').innerText(),/受阻 1 个时段/);
    const window = async (from,to) => {
      await panel.getByLabel('台账窗口开始',{exact:true}).fill(from);
      await panel.getByLabel('台账窗口结束',{exact:true}).fill(to);
      const response=page.waitForResponse(r=>r.url().includes(`/rotations/${id}/slots?`) && r.request().method()==='GET');
      await panel.getByRole('button',{name:'查询轮转台账',exact:true}).click();
      await response;
      await panel.locator('.rotation-slot').first().waitFor();
    };
    await window(anchor,plusHours(anchor,24));
    await panel.locator('.rotation-summary').scrollIntoViewIfNeeded();
    await screenshot('opspilot-cp46-desktop-blocked.png');
    await panel.getByRole('button',{name:'新增轮转',exact:true}).click();
    await panel.getByRole('button',{name:'保存轮转',exact:true}).click();
    await panel.getByRole('alert').filter({hasText:'同一计划只能有一条'}).waitFor();
    assert.equal((await api('/on-call/rotations')).rotations.length,1);
    await panel.getByRole('button',{name:'收起轮转表单',exact:true}).click();
    await panel.getByRole('button',{name:'暂停续排',exact:true}).click();
    await panel.getByLabel('续排状态变更原因',{exact:true}).fill('CP46 暂停不撤销现有班次');
    await panel.getByRole('button',{name:'确认暂停',exact:true}).click();
    await panel.getByRole('status').filter({hasText:'已暂停续排'}).waitFor();
    await page.reload();
    await panel.getByRole('button',{name:'恢复续排',exact:true}).waitFor();
    assert.equal((await api(`/on-call/rotations/${id}`)).version,1);
    const generatedCount=slots.slots.filter(item=>item.status==='GENERATED').length;
    assert.equal((await api(slotsPath)).slots.filter(item=>item.status==='GENERATED').length,generatedCount);
    // Capture v1 in the real UI, then create a real competing v2 via HTTP.
    await panel.getByRole('button',{name:'恢复续排',exact:true}).click();
    await panel.getByLabel('续排状态变更原因',{exact:true}).fill('CP46 旧版本恢复尝试');
    await api(`/on-call/rotations/${id}/state`,{version:1,active:true,reason:'CP46 另一管理会话恢复'});
    await panel.getByRole('button',{name:'确认恢复',exact:true}).click();
    await panel.getByRole('alert').filter({hasText:'轮转已变化'}).waitFor();
    assert.equal(await panel.getByRole('button',{name:'确认恢复',exact:true}).isDisabled(),true);
    await panel.getByText('版本已变化，本次操作未提交。',{exact:false}).waitFor();
    await panel.locator('.rotation-editor').scrollIntoViewIfNeeded();
    await screenshot('opspilot-cp46-version-conflict.png');
    await panel.getByRole('button',{name:'关闭确认',exact:true}).click();
    await panel.getByRole('button',{name:'刷新轮转',exact:true}).click();
    await panel.getByRole('button',{name:'暂停续排',exact:true}).waitFor();
    assert.equal((await api(`/on-call/rotations/${id}`)).version,2);
    // Release the manual shift in the preserved single-shift UI; wait for the real background job, not POST /scan.
    await rosterPanel.locator('.roster-row').filter({hasText:'CP46 手工占用首班'}).getByRole('button',{name:'取消班次',exact:true}).click();
    await rosterPanel.getByLabel('取消原因',{exact:true}).fill('CP46 释放首班由后台补齐');
    await rosterPanel.getByRole('button',{name:'确认取消班次',exact:true}).click();
    for(let attempt=0;attempt<50;attempt++) {
      slots=await api(slotsPath);
      if(slots.slots[0].status==='GENERATED')break;
      await page.waitForTimeout(100);
    }
    assert.equal(slots.slots[0].status,'GENERATED');
    await panel.getByRole('button',{name:'刷新轮转',exact:true}).click();
    await page.waitForFunction(()=>document.querySelector('.shift-owner strong')?.textContent==='张伟');
    const marker='cp46-browser-'+Date.now();
    const incident=await api('/alerts/intake',{source:'acceptance',externalEventId:marker,resourceCode:'APP-SETTLEMENT',severity:'P1',status:'FIRING',title:marker,labels:{}});
    const routed=(await api('/on-call/escalations')).find(item=>item.incidentId===incident.incidentId);
    assert.equal(routed.recipient,'zhangwei');
    const generatedId=slots.slots[0].shiftId;
    const generatedRow=rosterPanel.locator('.roster-row').filter({hasText:`轮转#${id} 时段#0`});
    await generatedRow.getByRole('button',{name:'取消班次',exact:true}).click();
    await rosterPanel.getByLabel('取消原因',{exact:true}).fill('CP46 有意留空不复活');
    await rosterPanel.getByRole('button',{name:'确认取消班次',exact:true}).click();
    await panel.getByText('已取消 · 不再生成',{exact:true}).waitFor();
    await page.waitForFunction(()=>document.querySelector('.shift-owner strong')?.textContent==='暂无排班');
    await panel.getByRole('button',{name:'立即续排（所有活跃规则）',exact:true}).click();
    await panel.getByRole('status').filter({hasText:'扫描 1 条轮转，新生成 0 班'}).waitFor();
    const cancelled=(await api(slotsPath)).slots[0];
    assert.equal(cancelled.shiftId,generatedId);
    assert.ok(cancelled.cancelledAt);
    await page.reload();
    await panel.getByText('已取消 · 不再生成',{exact:true}).waitFor();
    await window(anchor,plusHours(anchor,24));
    await panel.locator('.rotation-summary').scrollIntoViewIfNeeded();
    await screenshot('opspilot-cp46-desktop-cancelled.png');
    await page.setViewportSize({width:390,height:844});
    await page.waitForFunction(()=>document.querySelector('.main-frame').getBoundingClientRect().left===0);
    await panel.getByRole('heading',{name:'轮转与自动续排',exact:true}).scrollIntoViewIfNeeded();
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth),390);
    await screenshot('opspilot-cp46-mobile-summary.png');
    await panel.getByRole('button',{name:'新增轮转',exact:true}).click();
    await panel.getByLabel('轮转名称',{exact:true}).scrollIntoViewIfNeeded();
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth),390);
    await screenshot('opspilot-cp46-mobile-form.png');
    await panel.getByRole('button',{name:'收起轮转表单',exact:true}).click();
    await panel.getByText('已取消 · 不再生成',{exact:true}).scrollIntoViewIfNeeded();
    await screenshot('opspilot-cp46-mobile-ledger.png');
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth),390);
    const readContext=await browser.newContext({viewport:{width:390,height:844}});
    const readonly=await readContext.newPage();
    await readonly.goto(root+'/login');
    await readonly.getByLabel('账号',{exact:true}).fill('auditor');
    await readonly.getByRole('button',{name:'进入控制台',exact:true}).click();
    await readonly.waitForURL(root+'/');
    await readonly.goto(root+'/on-call');
    await readonly.locator('.rotation-panel').getByText('已取消 · 不再生成',{exact:true}).waitFor();
    for(const name of ['新增轮转','暂停续排','恢复续排','立即续排（所有活跃规则）']) assert.equal(await readonly.getByRole('button',{name,exact:true}).count(),0);
    await readonly.locator('.rotation-panel').getByRole('button',{name:'刷新轮转',exact:true}).click();
    await readonly.locator('.rotation-panel').getByText('只读轮转台账：',{exact:false}).waitFor();
    await readonly.waitForFunction(()=>document.querySelector('.rotation-panel')?.getAttribute('aria-busy')==='false');
    await readonly.waitForLoadState('networkidle');
    await page.waitForLoadState('networkidle');
    const historyAfter=await api(historyPath);
    assert.equal(JSON.stringify(historyAfter.shifts),historyRows);
    assert.deepEqual(errors,[]);
    assert.deepEqual(logs.filter(item=>!/409/.test(item)),[]);
    assert.equal(expected409.length,2);
    assert.ok(expected409.every(item=>item.status===409));
    assert.deepEqual(expected409.map(item=>item.path),['/api/v1/on-call/rotations',`/api/v1/on-call/rotations/${id}/state`]);
    const result={browser:`Chromium/Chrome ${browser.version()} via Playwright`,browserPath:'Browser plugin not available',url:root+'/on-call',viewports:['1440x1000','390x844'],browserTimezone:'Asia/Shanghai',jarTimezone:'UTC',database:'isolated H2 memory',userFileDatabaseModified:false,
      rotationId:id,members:rotation.members,anchorPreserved:true,createdFromUI:true,manualConflictVisible:true,duplicateCreateRejected:true,pausedPersisted:true,existingShiftsPreservedDuringPause:true,staleVersionRejectedAndBlocked:true,
      realBackgroundFilled:true,noManualScanBeforeBackground:true,currentOwnerAfterRefresh:'张伟',incident:{id:incident.incidentId,recipient:routed.recipient,status:routed.status},cancelledSlotRetained:true,manualScanCreatedZero:true,readonlyVerified:true,
      historicalShiftsPreserved:history.shifts.length,pageIdentity:true,noBlank:true,noOverlay:true,mobileDocumentWidth:390,consoleLogs:logs,pageErrors:errors,expected409,screenshots};
    fs.writeFileSync(out+'opspilot-cp46-browser-result.json',JSON.stringify(result,null,2));
    console.log(JSON.stringify({...result, outputDirectory:out}));
  } catch(error) { await page.screenshot({path:out+'opspilot-cp46-failure.png'}); throw error; }
  finally { await browser.close(); }
})().catch(error=>{console.error(String(error.message).replace(/Bearer\s+[^\s]+/g,'Bearer [REDACTED]'));process.exit(1)});

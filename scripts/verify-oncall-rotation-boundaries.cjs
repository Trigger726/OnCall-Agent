const { chromium } = require(process.env.OPSPILOT_PLAYWRIGHT_MODULE || 'playwright');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const root=process.env.OPSPILOT_BASE_URL || 'http://127.0.0.1:9917';
const out=fs.mkdtempSync(require('node:path').join(require('node:os').tmpdir(),'opspilot-rotation-boundaries-'))+require('node:path').sep;

// Mutating acceptance requires a caller-owned fresh, isolated demo database.
assert.equal(process.env.OPSPILOT_ACCEPTANCE_ISOLATED, '1', 'Set OPSPILOT_ACCEPTANCE_ISOLATED=1 only for a fresh isolated demo');
assert.ok(['localhost','127.0.0.1'].includes(new URL(root).hostname), 'Acceptance target must be loopback');
assert.notEqual(new URL(root).port, '9900', 'Do not target the daily demo listener');

(async()=>{
  const browser=await chromium.launch({executablePath: process.env.OPSPILOT_CHROME_PATH || undefined,headless:true});
  try {
    const page=await browser.newPage({viewport:{width:1440,height:1000}});
    await page.goto(root+'/login');
    await page.getByRole('button',{name:'进入控制台',exact:true}).click();
    await page.waitForURL(root+'/');
    await page.goto(root+'/on-call');
    const panel=page.locator('.rotation-panel');
    await page.waitForFunction(()=>document.querySelector('.rotation-panel')?.getAttribute('aria-busy')==='false');
    if(!((await panel.getByLabel('查看轮转规则',{exact:true}).innerText()).includes('CP46 一小时台账截断'))) {
    await panel.getByRole('button',{name:'新增轮转',exact:true}).click();
    await panel.getByLabel('轮转计划',{exact:true}).selectOption('2');
    await panel.getByLabel('轮转名称',{exact:true}).fill('CP46 一小时台账截断');
    await panel.getByLabel('每班分钟数',{exact:true}).fill('60');
    await panel.getByLabel('添加轮转成员',{exact:true}).selectOption('2');
    await panel.getByRole('button',{name:'添加成员',exact:true}).click();
    await panel.getByRole('button',{name:'保存轮转',exact:true}).click();
    } else {
      await panel.getByLabel('筛选轮转计划',{exact:true}).selectOption('2');
    }
    await panel.getByText('台账仅显示前 200 条',{exact:false}).waitFor();
    await page.waitForFunction(()=>document.querySelectorAll('.rotation-slot').length===200 && document.querySelector('.rotation-panel')?.getAttribute('aria-busy')==='false');
    assert.equal(await panel.locator('.rotation-slot').count(),200);
    const token=await page.evaluate(()=>localStorage.getItem('opspilot_token'));
    const headers={Authorization:'Bearer '+token};
    const api=async path=>{const r=await page.request.get(root+'/api/v1'+path,{headers});assert.equal(r.status(),200);return(await r.json()).data;};
    const rotation=(await api('/on-call/rotations?scheduleId=2')).rotations[0];
    const first=(await api(`/on-call/rotations/${rotation.id}/slots`)).slots[0];
    const hourEnd=new Date(new Date(first.startsAt.slice(0,16)+'Z').getTime()+3600000).toISOString().slice(0,16);
    await panel.getByLabel('台账窗口开始',{exact:true}).fill(first.startsAt.slice(0,16));
    await panel.getByLabel('台账窗口结束',{exact:true}).fill(hourEnd);
    await panel.getByRole('button',{name:'查询轮转台账',exact:true}).click();
    await page.waitForFunction(()=>document.querySelectorAll('.rotation-slot').length===1);
    assert.equal(await panel.getByText('台账仅显示前 200 条',{exact:false}).count(),0);
    await panel.getByLabel('台账窗口结束',{exact:true}).fill(first.startsAt.slice(0,16));
    await panel.getByRole('button',{name:'查询轮转台账',exact:true}).click();
    await panel.getByRole('alert').filter({hasText:'窗口须为正数且不超过 31 天'}).waitFor();
    assert.equal(await panel.locator('.rotation-slot').count(),0);
    await panel.getByRole('button',{name:'刷新轮转',exact:true}).click();
    await panel.getByLabel('筛选轮转计划',{exact:true}).selectOption('1');
    await panel.locator('.rotation-summary-title').filter({hasText:'CP46 双人八小时轮值'}).waitFor();
    assert.match(await panel.locator('.rotation-summary-title').innerText(),/CP46 双人八小时轮值/);
    await panel.getByLabel('筛选轮转计划',{exact:true}).selectOption('2');
    await panel.getByText('台账仅显示前 200 条',{exact:false}).waitFor();
    await page.waitForFunction(()=>document.querySelectorAll('.rotation-slot').length===200 && document.querySelector('.rotation-panel')?.getAttribute('aria-busy')==='false');
    // Explicit presentation fixtures only; backend eligibility/failure atomicity were proved in CP45.
    await page.route('**/api/v1/on-call/rotations?scheduleId=2',async route=>{
      const res=await route.fetch();const body=await res.json();body.data.truncated=true;
      body.data.rotations[0].lastWarning='GENERATION_FAILED';await route.fulfill({response:res,json:body});
    });
    await page.route(`**/api/v1/on-call/rotations/${rotation.id}/slots?**`,async route=>{
      const res=await route.fetch();const body=await res.json();body.data.slots[0].memberAvailable=false;
      body.data.slots[1]={...body.data.slots[1],status:'MEMBER_UNAVAILABLE',shiftId:null,memberAvailable:false,detail:'成员停用或不再具有运维职责；未跳过其轮次换人'};
      await route.fulfill({response:res,json:body});
    });
    await page.route('**/api/v1/on-call/rotations/scan',route=>route.fulfill({status:200,contentType:'application/json',body:JSON.stringify({success:true,data:{rotations:2,createdShifts:0,blockedSlots:1,failedRotations:[rotation.id]}})}));
    await panel.getByRole('button',{name:'刷新轮转',exact:true}).click();
    await panel.getByText('规则列表仅前 100 条',{exact:false}).waitFor();
    await panel.getByText('已生成 · 成员不可用',{exact:true}).waitFor();
    await panel.getByText('成员不可用 · 未生成',{exact:true}).waitFor();
    await panel.getByText('本轮生成失败并回滚',{exact:false}).waitFor();
    await panel.getByRole('button',{name:'立即续排（所有活跃规则）',exact:true}).click();
    await panel.getByRole('status').filter({hasText:`失败规则 #${rotation.id}（已回滚，其他规则继续）`}).waitFor();
    await page.waitForFunction(()=>document.querySelector('.rotation-panel')?.getAttribute('aria-busy')==='false');
    await page.unrouteAll({behavior:'wait'});
    await page.waitForLoadState('networkidle');
    const result={realApi:{rotationId:rotation.id,slotsTruncatedAt200:true,narrowWindowOneSlot:true,invalidWindowRejectedAndOldSlotsCleared:true,planSelectionMatched:true},presentationFixturesOnly:{listTruncation:true,generatedMemberUnavailable:true,notGeneratedMemberUnavailable:true,generationFailureWarning:true,partialScanFailureIds:true},note:'Presentation fixtures are not real runtime generation failure or account changes; real backend scenarios remain CP45 shared H2/MySQL tests'};
    fs.writeFileSync(out+'opspilot-cp46-boundary-result.json',JSON.stringify(result,null,2));console.log(JSON.stringify({...result, outputDirectory:out}));
  } finally {await browser.close();}
})().catch(error=>{console.error(String(error.message).replace(/Bearer\s+[^\s]+/g,'Bearer [REDACTED]'));process.exit(1)});

const { chromium } = require(process.env.OPSPILOT_PLAYWRIGHT_MODULE || 'playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const root = process.env.OPSPILOT_BASE_URL || 'http://127.0.0.1:9917';
assert.equal(process.env.OPSPILOT_ACCEPTANCE_ISOLATED, '1', 'Coverage acceptance needs an owned isolated database');
assert.ok(['localhost', '127.0.0.1'].includes(new URL(root).hostname));
assert.notEqual(new URL(root).port, '9900');
const out = fs.mkdtempSync(path.join(process.env.OPSPILOT_EVIDENCE_DIR || require('node:os').tmpdir(), 'coverage-ui-'));
const addHours = (value, hours) => new Date(new Date(value + 'Z').getTime() + hours * 3600000).toISOString().slice(0, 19);
(async () => {
  const browser = await chromium.launch({ executablePath: process.env.OPSPILOT_CHROME_PATH || undefined, headless: true });
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, timezoneId: 'Asia/Shanghai' });
  const page = await context.newPage();
  const logs = [], errors = [], rejected = [];
  page.on('console', item => { if (['error','warning'].includes(item.type())) logs.push(item.text()); });
  page.on('pageerror', error => errors.push(error.message));
  page.on('response', r => { if(r.status() >= 400) rejected.push({ status: r.status(), path: new URL(r.url()).pathname }); });
  try {
    await page.goto(root + '/login');
    await page.getByRole('button', { name: '进入控制台', exact: true }).click();
    await page.waitForURL(root + '/');
    const token = await page.evaluate(() => localStorage.getItem('opspilot_token'));
    const headers = { Authorization: 'Bearer ' + token };
    const api = async (route, data) => {
      const r = data ? await page.request.post(root + '/api/v1' + route, { headers, data }) : await page.request.get(root + '/api/v1' + route, { headers });
      assert.equal(r.status(), 200, await r.text());
      return (await r.json()).data;
    };
    const options = await api('/on-call/roster');
    const scheduleId = options.schedules[1].id;
    // Beyond the auto-generation horizon, but still within ordinary roster write bounds.
    const from = addHours(options.databaseNow, 22 * 24), to = addHours(from, 8);
    const historyPath = '/on-call/roster?from=2026-08-19T00%3A00&to=2026-08-21T00%3A00';
    const history = JSON.stringify((await api(historyPath)).shifts);
    const create = (userId, start, end, override) => api('/on-call/shifts', { scheduleId, userId,
      startsAt: addHours(from, start), endsAt: addHours(from, end), override, note: 'CP48 覆盖预览验收' });
    const base = await create(2, 1, 5, false);
    const cover = await create(3, 2, 3, true);
    const handoff = await create(3, 5, 7, false);
    await page.goto(root + '/on-call');
    const panel = page.locator('.coverage-panel');
    await panel.locator('.coverage-summary').waitFor();
    const query = async (start, end) => {
      await panel.getByLabel('覆盖计划', { exact: true }).selectOption(String(scheduleId));
      await panel.getByLabel('覆盖开始', { exact: true }).fill(start);
      await panel.getByLabel('覆盖结束', { exact: true }).fill(end);
      const response = page.waitForResponse(r => new URL(r.url()).pathname === '/api/v1/on-call/coverage');
      await panel.getByRole('button', { name: '查询覆盖', exact: true }).click();
      const r = await response;
      await page.waitForFunction(() => document.querySelector('.coverage-panel')?.getAttribute('aria-busy') === 'false');
      return r;
    };
    let r = await query(from, to);
    assert.equal(r.status(), 200);
    const data = (await r.json()).data;
    assert.equal(data.coveredSeconds, 21600);
    assert.equal(data.gapSeconds, 7200);
    assert.deepEqual(data.segments.map(s => s.shiftId), [null, base.id, cover.id, base.id, handoff.id, null]);
    assert.equal(await panel.locator('.coverage-segment').count(), 6);
    assert.ok(await panel.locator('.coverage-day').count() >= 1);
    await panel.locator('.coverage-day').first().click();
    assert.equal(await panel.locator('.coverage-day[aria-pressed="true"]').count(), 1);
    await panel.getByRole('button', { name: '所有日期', exact: true }).click();
    assert.equal(await panel.locator('.coverage-segment').count(), 6);
    assert.match(await panel.locator('.coverage-summary').innerText(), /已覆盖 6 小时 · 缺班 2 小时/);
    await panel.getByRole('checkbox', { name: '只看缺班', exact: true }).check();
    assert.equal(await panel.locator('.coverage-segment').count(), 2);
    await panel.getByRole('heading', { name: '日历覆盖预览', exact: true }).scrollIntoViewIfNeeded();
    await page.screenshot({ path: path.join(out, 'opspilot-cp48-desktop-gaps.png') });
    await panel.getByRole('checkbox', { name: '只看缺班', exact: true }).uncheck();
    const roster = page.locator('.oncall-roster-panel');
    await roster.getByLabel('查看计划', { exact: true }).selectOption(String(scheduleId));
    await roster.getByLabel('窗口开始', { exact: true }).fill(from.slice(0,16));
    await roster.getByLabel('窗口结束', { exact: true }).fill(to.slice(0,16));
    const rosterResponse = page.waitForResponse(r => r.url().includes('/on-call/roster?'));
    await roster.getByRole('button', { name: '查询班次', exact: true }).click();
    await rosterResponse;
    await roster.locator('.roster-row').filter({ hasText: '临时覆盖' }).waitFor();
    const cancel = async owner => {
      await roster.locator('.roster-row').filter({ hasText: owner }).getByRole('button', { name: '取消班次', exact: true }).click();
      await roster.getByLabel('取消原因', { exact: true }).fill('CP48 核对自动刷新');
      const response = page.waitForResponse(r => new URL(r.url()).pathname === '/api/v1/on-call/coverage');
      await roster.getByRole('button', { name: '确认取消班次', exact: true }).click();
      const r = await response;
      await page.waitForFunction(() => document.querySelector('.coverage-panel')?.getAttribute('aria-busy') === 'false');
      return (await r.json()).data;
    };
    const afterCover = await cancel('李娜 · 临时覆盖');
    assert.deepEqual(afterCover.segments.map(s => s.shiftId), [null, base.id, handoff.id, null]);
    const afterBase = await cancel('张伟 · 普通班次');
    assert.equal(afterBase.gapSeconds, 21600);
    assert.match(await panel.locator('.coverage-summary').innerText(), /已覆盖 2 小时 · 缺班 6 小时/);
    r = await query(from, from);
    assert.equal(r.status(), 400);
    assert.equal(await panel.locator('.coverage-summary').count(), 0);
    assert.equal(await panel.locator('.coverage-segment').count(), 0);
    await panel.getByRole('alert').waitFor();
    await query(from, to);
    await page.setViewportSize({ width: 390, height: 844 });
    await page.waitForFunction(() => document.querySelector('.main-frame').getBoundingClientRect().left === 0);
    await panel.getByRole('heading', { name: '日历覆盖预览', exact: true }).scrollIntoViewIfNeeded();
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth), 390);
    await page.screenshot({ path: path.join(out, 'opspilot-cp48-mobile-summary.png') });
    await panel.locator('.coverage-segment.gap').first().scrollIntoViewIfNeeded();
    await page.screenshot({ path: path.join(out, 'opspilot-cp48-mobile-gap.png') });
    const readContext = await browser.newContext({ viewport: { width: 390, height: 844 } });
    const readonly = await readContext.newPage();
    await readonly.goto(root + '/login');
    await readonly.getByLabel('账号', { exact: true }).fill('auditor');
    await readonly.getByRole('button', { name: '进入控制台', exact: true }).click();
    await readonly.waitForURL(root + '/');
    await readonly.goto(root + '/on-call');
    await readonly.locator('.coverage-summary').waitFor();
    assert.equal(await readonly.locator('.coverage-panel').getByRole('button', { name: '查询覆盖', exact: true }).count(), 1);
    assert.equal(await readonly.getByRole('button', { name: '取消班次', exact: true }).count(), 0);
    await readonly.waitForLoadState('networkidle');
    await page.waitForLoadState('networkidle');
    assert.equal(page.url(), root + '/on-call');
    assert.equal(await page.title(), 'OpsPilot 智能运维平台');
    assert.equal(await page.locator('vite-error-overlay').count(), 0);
    assert.deepEqual(errors, []);
    assert.deepEqual(logs.filter(l => !/400/.test(l)), []);
    assert.deepEqual(rejected, [{ status: 400, path: '/api/v1/on-call/coverage' }]);
    assert.equal(JSON.stringify((await api(historyPath)).shifts), history);
    const result = { browser: browser.version(), browserPath: 'Browser plugin not available', viewports: ['1440x1000','390x844'],
      url: page.url(), pageIdentity: true, noBlank: true, noOverlay: true, pageErrors: errors, consoleLogs: logs, expected400: rejected,
      realApi: { gapsAndOverrides: true, halfOpenHandoff: true, noDoubleCounting: true, gapFilter: true,
        cancelledOverrideFallsBack: true, cancelledBaseCreatesGap: true, crossPanelAutoRefresh: true,
        invalidWindowClearsOldConclusion: true, readonlyQuery: true, historicalRowsUnchanged: true, dailyCalendarSelection: true }, mobileDocumentWidth: 390,
      screenshots: ['opspilot-cp48-desktop-gaps.png','opspilot-cp48-mobile-summary.png','opspilot-cp48-mobile-gap.png'] };
    fs.writeFileSync(path.join(out, 'opspilot-cp48-coverage-result.json'), JSON.stringify(result,null,2));
    console.log(JSON.stringify(result));
  } catch(error) { await page.screenshot({path:path.join(out,'opspilot-cp48-failure.png')}); throw error; }
  finally { await browser.close(); }
})().catch(error => { console.error(String(error.message).replace(/Bearer\s+[^\s]+/g,'Bearer [REDACTED]')); process.exitCode = 1; });

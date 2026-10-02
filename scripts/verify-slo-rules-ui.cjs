const { chromium } = require(process.env.OPSPILOT_PLAYWRIGHT_MODULE || 'playwright');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const assert = require('node:assert/strict');
const { createHash } = require('node:crypto');
const base = process.env.OPSPILOT_BASE_URL || 'http://127.0.0.1:9917';
const baseline = process.env.OPSPILOT_SLO_BASELINE === '1';
assert.equal(process.env.OPSPILOT_ACCEPTANCE_ISOLATED, '1');
assert(['127.0.0.1', 'localhost'].includes(new URL(base).hostname));
assert.notEqual(new URL(base).port, '9900');
assert(!baseline || !process.env.CI);
const out = fs.mkdtempSync(path.join(process.env.OPSPILOT_EVIDENCE_DIR || os.tmpdir(), 'slo-rules-ui-'));
(async () => {
  const browser = await chromium.launch({ headless: true, executablePath: process.env.OPSPILOT_CHROME_PATH || undefined });
  const errors = [], logs = [], failures = [], screenshots = [], exported = [];
  const login = async username => {
    const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, acceptDownloads: true });
    const page = await context.newPage();
    page.on('pageerror', error => errors.push(error.message));
    page.on('console', entry => { if (['error', 'warning'].includes(entry.type())) logs.push(entry.text()); });
    page.on('response', response => { if (response.status() >= 400) failures.push({ status: response.status(), path: new URL(response.url()).pathname }); });
    await page.goto(base + '/login');
    await page.getByLabel('账号', { exact: true }).fill(username);
    await page.getByRole('button', { name: '进入控制台', exact: true }).click();
    await page.waitForURL(base + '/');
    await page.goto(base + '/analytics');
    await page.locator('.analytics-slo-panel tbody tr').first().waitFor();
    return page;
  };
  const screenshot = async (page, name) => { await page.screenshot({ path: path.join(out, name) }); screenshots.push(name); };
  try {
    const page = await login('lina');
    assert.match(await page.title(), /OpsPilot/);
    assert.equal(new URL(page.url()).pathname, '/analytics');
    assert.equal(await page.locator('vite-error-overlay').count(), 0);
    await screenshot(page, baseline ? 'cp58-before-first-viewport.png' : 'cp58-after-first-viewport.png');
    const panel = page.locator('.analytics-slo-panel');
    await panel.scrollIntoViewIfNeeded();
    if (baseline) {
      assert.equal(await panel.getByRole('button', { name: '导出告警规则', exact: true }).count(), 0);
      await screenshot(page, 'cp58-before-desktop.png');
      await page.setViewportSize({ width: 390, height: 844 });
      await panel.scrollIntoViewIfNeeded();
      await screenshot(page, 'cp58-before-mobile.png');
    } else {
      const row = panel.locator('tbody tr').filter({ hasText: 'APP-SETTLEMENT' });
      await row.getByRole('button', { name: '导出告警规则', exact: true }).click();
      const dialog = page.getByRole('dialog', { name: '导出 SLO 告警规则', exact: true });
      await dialog.getByText('23条规则', { exact: false }).waitFor();
      assert.match(await dialog.innerText(), /APP-SETTLEMENT · v0/);
      const download = async () => {
        const promise = page.waitForEvent('download');
        await dialog.getByRole('button', { name: '下载规则文件', exact: true }).click();
        const item = await promise;
        const file = path.join(out, item.suggestedFilename());
        await item.saveAs(file);
        const content = fs.readFileSync(file, 'utf8');
        const digest = createHash('sha256').update(content).digest('hex');
        assert((await dialog.locator('.slo-rules-digest').innerText()).includes(digest));
        exported.push({ file: item.suggestedFilename(), sha256: digest });
        return JSON.parse(content);
      };
      assert.equal((await download()).groups[0].rules.length, 23);
      await screenshot(page, 'cp58-export-desktop.png');
      await page.setViewportSize({ width: 390, height: 844 });
      await page.waitForFunction(() => document.querySelector('.main-frame')?.getBoundingClientRect().left === 0);
      await screenshot(page, 'cp58-export-mobile.png');
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth), 390);
      const bounds = await dialog.boundingBox();
      assert(bounds.x >= 0 && bounds.x + bounds.width <= 390);
      await dialog.getByRole('button', { name: '关闭', exact: true }).click();
      const token = await page.evaluate(() => localStorage.getItem('opspilot_token'));
      const initial = await page.request.get(base + '/api/v1/slo/objectives', { headers: { Authorization: `Bearer ${token}` } });
      const objective = (await initial.json()).data.objectives.find(item => item.id === 1);
      const changed = await page.request.patch(base + '/api/v1/slo/objectives/1', { headers: { Authorization: `Bearer ${token}` }, data: {
        expectedVersion: objective.version, name: objective.name, targetPercent: 99.9, windowDays: 28,
        goodEventsQueryTemplate: objective.goodEventsQueryTemplate, totalEventsQueryTemplate: objective.totalEventsQueryTemplate,
      } });
      assert.equal(changed.status(), 200);
      await row.getByRole('button', { name: '导出告警规则', exact: true }).click();
      await dialog.getByRole('alert').filter({ hasText: '目标已变化或停用' }).waitFor();
      assert(await dialog.getByRole('button', { name: '下载规则文件', exact: true }).isDisabled());
      await screenshot(page, 'cp58-stale-version-mobile.png');
      await dialog.getByRole('button', { name: '关闭', exact: true }).click();
      await page.locator('.analytics-toolbar').getByRole('button', { name: '查询', exact: true }).click();
      await row.getByText('28 天滚动 · v1', { exact: true }).waitFor();
      await row.getByRole('button', { name: '导出告警规则', exact: true }).click();
      await dialog.getByText('23条规则', { exact: false }).waitFor();
      const updated = await download();
      assert(updated.groups[0].rules.find(item => item.alert === 'OpsPilotSloFastPage').expr.includes('>= 13.44'));
      assert.match(await dialog.innerText(), /v1 · 99.9% \/ 28天/);
      await dialog.getByRole('button', { name: '关闭', exact: true }).click();
      await row.getByRole('button', { name: '调整目标', exact: true }).click();
      const edit = page.getByRole('dialog', { name: '调整服务 SLO', exact: true });
      await edit.getByLabel('滚动窗口（天）', { exact: true }).fill('1');
      await edit.getByRole('button', { name: '保存并重新评估', exact: true }).click();
      await edit.waitFor({ state: 'hidden' });
      await row.getByText('1 天滚动 · v2', { exact: true }).waitFor();
      await row.getByRole('button', { name: '导出告警规则', exact: true }).click();
      await dialog.getByText('27条规则', { exact: false }).waitFor();
      const oneDay = await download();
      assert(oneDay.groups[0].rules.some(item => item.record?.endsWith('burn_1d')));
      assert(oneDay.groups[0].rules.some(item => item.record?.endsWith('burn_2h')));
      assert(!oneDay.groups[0].rules.some(item => item.record?.endsWith('burn_3d')));
      await screenshot(page, 'cp58-one-day-mobile.png');
      const readonly = await login('zhangwei');
      assert.equal(await readonly.locator('.analytics-slo-panel').getByRole('button', { name: '导出告警规则', exact: true }).count(), 0);
    }
    assert.deepEqual(errors, []);
    const expected = baseline ? [] : [{ status: 409, path: '/api/v1/slo/objectives/1/versions/0/prometheus-rules' }];
    assert.deepEqual(failures, expected);
    assert.equal(logs.length, expected.length);
    assert(logs.every(line => /server responded with a status of 409/.test(line)));
    const result = { status: baseline ? 'BASELINE_CAPTURED' : 'PASS', browser: browser.version(), url: base + '/analytics',
      viewports: ['1440x1000', '390x844'], screenshots, exported, pageErrors: errors, expectedResponses: expected,
      unexpectedConsoleErrors: [], reason: 'Browser plugin not available', userDatabaseModified: false };
    fs.writeFileSync(path.join(out, 'result.json'), JSON.stringify(result, null, 2));
    console.log(JSON.stringify(result));
  } finally { await browser.close(); }
})().catch(error => { console.error(error.message); process.exitCode = 1; });

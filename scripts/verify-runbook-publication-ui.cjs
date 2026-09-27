const { chromium } = require(process.env.OPSPILOT_PLAYWRIGHT_MODULE || 'playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const root = process.env.OPSPILOT_BASE_URL || 'http://127.0.0.1:9917';
const baseline = process.env.OPSPILOT_PUBLICATION_BASELINE === '1';
assert.equal(process.env.OPSPILOT_ACCEPTANCE_ISOLATED, '1');
assert.ok(['127.0.0.1', 'localhost'].includes(new URL(root).hostname));
assert.notEqual(new URL(root).port, '9900');
assert.ok(!baseline || !process.env.CI);
const out = fs.mkdtempSync(path.join(process.env.OPSPILOT_EVIDENCE_DIR || require('node:os').tmpdir(), 'runbook-publication-'));
(async () => {
  const browser = await chromium.launch({ executablePath: process.env.OPSPILOT_CHROME_PATH || undefined, headless: true });
  const errors = [], logs = [], failures = [], screenshots = [];
  const login = async name => {
    const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
    const page = await context.newPage();
    page.on('pageerror', e => errors.push(e.message));
    page.on('console', m => { if (['error','warning'].includes(m.type())) logs.push(m.text()); });
    page.on('response', r => { if (r.status() >= 400) failures.push({ status: r.status(), path: new URL(r.url()).pathname }); });
    await page.goto(root + '/login'); await page.getByLabel('账号', { exact: true }).fill(name);
    await page.getByRole('button', { name: '进入控制台', exact: true }).click(); await page.waitForURL(root + '/');
    await page.goto(root + '/runbooks'); await page.locator('.retrieval-panel').waitFor();
    return page;
  };
  const shot = async (page, name) => {
    const panel = page.locator(baseline ? '.runbook-toolbar' : '.publication-queue');
    await panel.evaluate(el => window.scrollTo(0, window.scrollY + el.getBoundingClientRect().top - 80));
    await page.screenshot({ path: path.join(out, name) }); screenshots.push(name);
  };
  const token = page => page.evaluate(() => localStorage.getItem('opspilot_token'));
  const request = async (access, route, body, expected = 200) => {
    const r = await fetch(root + '/api/v1' + route, { method: body ? 'POST' : 'GET',
      headers: { Authorization: 'Bearer ' + access, 'Content-Type': 'application/json' }, body: body ? JSON.stringify(body) : undefined });
    assert.equal(r.status, expected, route); return (await r.json()).data;
  };
  try {
    const admin = await login('admin'), adminToken = await token(admin);
    const key = 'browser-publication-' + Date.now();
    const draft = await request(adminToken, '/runbooks/imports/markdown', { stableKey: key,
      resourceType: 'APPLICATION', title: '发布复核验收手册', summary: '隔离数据，不是生产手册', sourceName: 'acceptance.md',
      markdown: '# 前置条件\ncp56browserpublicationprobe\n# 验证\n观察错误率与恢复指标', allowedRoles: ['ADMIN','OPS_MANAGER','ON_CALL'] });
    if (baseline) {
      assert.equal(draft.document.status, 'PUBLISHED');
      assert.equal(await admin.locator('.publication-queue').count(), 0);
      await shot(admin, 'cp56-before-desktop.png'); await admin.setViewportSize({ width: 390, height: 844 });
      await shot(admin, 'cp56-before-mobile.png');
    } else {
      assert.equal(draft.document.status, 'PENDING_REVIEW'); assert.equal(draft.document.publishedAt, null);
      assert.equal((await request(adminToken, '/runbooks/search?q=cp56browserpublicationprobe')).results.length, 0);
      await request(adminToken, `/runbooks/publications/${draft.document.id}/decisions`, {
        expectedVersion: 0, decision: 'APPROVE', requestKey: require('node:crypto').randomUUID(), reason: '本人不能审批' }, 403);
      const panel = admin.locator('.publication-queue');
      await panel.getByRole('button', { name: '刷新审核台账', exact: true }).click();
      await panel.getByRole('button', { name: new RegExp(key) }).click();
      await panel.getByText('本人不能审批自己的候选，只能撤回。').waitFor();
      assert.equal(await panel.getByLabel('发布决定').inputValue(), 'WITHDRAW');
      await shot(admin, 'cp56-own-pending-desktop.png');
      const manager = await login('lina'), review = manager.locator('.publication-queue');
      await review.getByRole('button', { name: new RegExp(key) }).click();
      await review.getByLabel('复核说明').fill('已独立核对前置条件与恢复验证');
      // Commit on the server but drop the browser response, then recover the SAME frozen command after reload.
      let intercepted = 0;
      await manager.route(`**/api/v1/runbooks/publications/${draft.document.id}/decisions`, async route => {
        intercepted++; const response = await route.fetch(); assert.equal(response.status(), 200); await route.abort('failed');
      });
      await review.getByRole('button', { name: '确认提交决定', exact: true }).click();
      await review.getByRole('alert').waitFor(); // Wait for the dropped response, not merely the immediately rendered frozen button.
      await review.getByRole('button', { name: '同键重试原决定', exact: true }).waitFor();
      const saved = await manager.evaluate(() => JSON.parse(sessionStorage.getItem('opspilot_publication_intent_3')));
      assert.equal(saved.expectedVersion, 0); assert.equal(saved.reason, '已独立核对前置条件与恢复验证');
      await manager.unroute(`**/api/v1/runbooks/publications/${draft.document.id}/decisions`);
      await manager.reload();
      await review.getByRole('button', { name: '同键重试原决定', exact: true }).waitFor();
      const restored = await manager.evaluate(() => JSON.parse(sessionStorage.getItem('opspilot_publication_intent_3')));
      assert.deepEqual(restored, saved); assert.equal(intercepted, 1);
      await review.getByRole('button', { name: '同键重试原决定', exact: true }).click();
      await review.getByRole('status').filter({ hasText: '已确认：PUBLISHED' }).waitFor();
      await review.getByText('提交基线 v0 / 当前发布 v1', { exact: false }).waitFor();
      assert.equal((await request(adminToken, `/runbooks/publications/${draft.document.id}`)).reviewVersion, 1);
      assert.ok((await request(adminToken, '/runbooks/search?q=cp56browserpublicationprobe')).results.some(r => r.stableKey === key));
      await shot(manager, 'cp56-approved-desktop.png');
      await manager.setViewportSize({ width: 390, height: 844 });
      await manager.waitForFunction(() => document.querySelector('.main-frame').getBoundingClientRect().left === 0);
      assert.equal(await manager.evaluate(() => document.documentElement.scrollWidth), 390);
      await shot(manager, 'cp56-approved-mobile.png');
      const oncall = await login('zhangwei'); assert.equal(await oncall.locator('.publication-queue').count(), 0);
      assert.equal(await manager.locator('vite-error-overlay').count(), 0);
    }
    assert.deepEqual(errors, []);
    assert.ok(failures.every(f => f.status === 404 && f.path === '/api/v1/runbooks/evaluations/latest'));
    const unexpected = logs.filter(line => !/server responded with a status of 404/.test(line) && !(!baseline && /net::ERR_FAILED/.test(line)));
    assert.deepEqual(unexpected, []);
    const result = { status: baseline ? 'BASELINE_CAPTURED' : 'PASS', publicationImplemented: !baseline,
      importedStatus: draft.document.status,
      browser: browser.version(), viewports: ['1440x1000','390x844'], screenshots, pageErrors: errors,
      expectedMissingEvaluations: failures.length, expectedAbortedDecisionResponses: baseline ? 0 : 1,
      unexpectedConsoleErrors: unexpected, reason: 'Browser plugin not available', productionDataModified: false };
    fs.writeFileSync(path.join(out, 'result.json'), JSON.stringify(result, null, 2)); console.log(JSON.stringify(result));
  } finally { await browser.close(); }
})().catch(e => { console.error(e.message); process.exitCode = 1; });

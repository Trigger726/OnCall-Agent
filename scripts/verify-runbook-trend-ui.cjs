const { chromium } = require(process.env.OPSPILOT_PLAYWRIGHT_MODULE || 'playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const root = process.env.OPSPILOT_BASE_URL || 'http://127.0.0.1:9917';
const baseline = process.env.OPSPILOT_TREND_BASELINE === '1';
assert.equal(process.env.OPSPILOT_ACCEPTANCE_ISOLATED, '1');
assert.ok(['127.0.0.1', 'localhost'].includes(new URL(root).hostname));
assert.notEqual(new URL(root).port, '9900');
assert.ok(!baseline || !process.env.CI);
const out = fs.mkdtempSync(path.join(process.env.OPSPILOT_EVIDENCE_DIR || require('node:os').tmpdir(), 'runbook-trend-'));
(async () => {
  const browser = await chromium.launch({ executablePath: process.env.OPSPILOT_CHROME_PATH || undefined, headless: true });
  const errors = [], logs = [], failedResponses = [], screenshots = [];
  const login = async username => {
    const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, timezoneId: 'America/Los_Angeles' });
    const page = await context.newPage();
    page.on('pageerror', e => errors.push(e.message));
    page.on('console', item => { if (['error', 'warning'].includes(item.type())) logs.push(item.text()); });
    page.on('response', response => { if (response.status() >= 400) failedResponses.push({ status: response.status(), path: new URL(response.url()).pathname }); });
    await page.goto(root + '/login'); await page.getByLabel('账号', { exact: true }).fill(username);
    await page.getByRole('button', { name: '进入控制台', exact: true }).click(); await page.waitForURL(root + '/');
    await page.goto(root + '/runbooks');
    if (username === 'auditor') {
      await page.getByRole('alert').filter({ hasText: '当前角色不能访问 Runbook' }).waitFor();
      assert.equal(await page.locator('.runbook-search').count(), 0);
    } else { await page.locator('.retrieval-panel').waitFor(); }
    return page;
  };
  const snapshot = async (page, name, locator) => {
    if (locator) await locator.evaluate(element => window.scrollTo(0, Math.max(0, window.scrollY + element.getBoundingClientRect().top - 80)));
    await page.screenshot({ path: path.join(out, name) }); screenshots.push(name);
  };
  try {
    const admin = await login('admin');
    assert.match(await admin.title(), /OpsPilot/);
    assert.equal(new URL(admin.url()).pathname, '/runbooks');
    assert.equal(await admin.locator('vite-error-overlay').count(), 0);
    if (baseline) {
      assert.equal(await admin.locator('.retrieval-trend').count(), 0);
      await snapshot(admin, 'cp55-before-desktop.png', admin.getByRole('region', { name: 'Runbook 固定集评测历史' }));
      await admin.setViewportSize({ width: 390, height: 844 });
      await snapshot(admin, 'cp55-before-mobile.png', admin.getByRole('region', { name: 'Runbook 固定集评测历史' }));
      assert.deepEqual(errors, []);
      assert.ok(failedResponses.every(item => item.status === 404 && item.path === '/api/v1/runbooks/evaluations/latest'));
      assert.equal(logs.length, failedResponses.length);
      assert.ok(logs.every(line => /server responded with a status of 404/.test(line)));
      const result = { status: 'BASELINE_CAPTURED', trendImplemented: false, browser: browser.version(), viewports: ['1440x1000','390x844'], screenshots };
      fs.writeFileSync(path.join(out, 'result.json'), JSON.stringify(result, null, 2)); console.log(JSON.stringify(result)); return;
    }
    const panel = admin.locator('.retrieval-trend');
    const idle = () => admin.waitForFunction(() => document.querySelector('.retrieval-trend')?.getAttribute('aria-busy') === 'false');
    await idle();
    const token = await admin.evaluate(() => localStorage.getItem('opspilot_token'));
    const api = async (credential, route, body, expected = 200) => {
      const r = await admin.request.fetch(root + '/api/v1' + route, { method: body ? 'POST' : 'GET', headers: { Authorization: 'Bearer ' + credential }, data: body });
      assert.equal(r.status(), expected, route); return (await r.json()).data;
    };
    const reviewer = await login('lina');
    const reviewerToken = await reviewer.evaluate(() => localStorage.getItem('opspilot_token'));
    const initial = await api(token, '/runbooks/searches/trend?topK=3');
    assert.equal(initial.totals.queryCount, 0);
    const search = q => api(token, '/runbooks/search?q=' + encodeURIComponent(q) + '&topK=3&mode=HYBRID');
    const hit = await search('消息积压 consumer group offset 位点');
    const miss = await search('消息积压 consumer group offset 位点');
    const partial = await search('消息积压 consumer group offset 位点');
    const empty = await search('zzzznotermmatchunique');
    assert.equal(empty.results.length, 0);
    assert.equal(hit.engine, 'BM25_LOCAL_V1'); assert.equal(hit.requestedMode, 'HYBRID');
    const keys = [...new Set(hit.results.map(item => item.stableKey))];
    assert.ok(keys.length > 1, 'Partial review needs more than one distinct document');
    const judge = async (row, doc, grade) => {
      const judgment = await api(token, `/runbooks/searches/${row.searchId}/judgments`, { documentStableKey: doc, relevanceGrade: 3 });
      await api(reviewerToken, `/runbooks/judgments/${judgment.id}/reviews`, { expectedVersion: judgment.versionNo, decision: 'APPROVE', reviewerGrade: grade });
    };
    for (let i = 0; i < keys.length; i++) { await judge(hit, keys[i], i === 0 ? 2 : 0); await judge(miss, keys[i], 0); }
    await judge(partial, keys[0], 3);
    const trend = await api(token, '/runbooks/searches/trend?topK=3');
    assert.equal(trend.totals.queryCount, 4); assert.equal(trend.totals.fullyReviewedQueries, 2);
    assert.equal(trend.totals.partialReviewedQueries, 1); assert.equal(trend.totals.qualityEligibleQueries, 3);
    assert.equal(trend.totals.relevantQueries, 1); assert.equal(trend.totals.reviewedHitRateAtK, 0.333333);
    assert.equal(trend.totals.returnRate, 0.75); assert.equal(trend.totals.reviewCoverage, 0.666667);
    await panel.getByLabel('趋势K', { exact: true }).selectOption('3');
    await panel.getByRole('button', { name: '查询检索趋势', exact: true }).click(); await idle();
    assert.equal(await panel.getByTestId('trend-queries').innerText(), '4');
    assert.equal(await panel.getByTestId('trend-hit-rate').innerText(), '33.3%');
    assert.equal(await panel.getByLabel('趋势结束日', { exact: true }).inputValue(), trend.to);
    assert.match(await panel.locator('tbody tr').first().innerText(), new RegExp(trend.to));
    await snapshot(admin, 'cp55-after-desktop.png', panel);
    await admin.setViewportSize({ width: 390, height: 844 });
    await admin.waitForFunction(() => document.querySelector('.main-frame').getBoundingClientRect().left === 0);
    assert.equal(await admin.evaluate(() => document.documentElement.scrollWidth), 390);
    await snapshot(admin, 'cp55-after-mobile.png', panel);
    await panel.getByLabel('趋势实际引擎', { exact: true }).selectOption('HYBRID_RRF_V1');
    await panel.getByRole('button', { name: '查询检索趋势', exact: true }).click(); await idle();
    assert.equal(await panel.getByTestId('trend-queries').innerText(), '0');
    assert.equal(await panel.getByTestId('trend-hit-rate').innerText(), 'N/A');
    await snapshot(admin, 'cp55-empty-mobile.png', panel);
    await panel.getByLabel('趋势实际引擎', { exact: true }).selectOption('BM25_LOCAL_V1');
    await panel.getByLabel('趋势来源', { exact: true }).selectOption('AGENT');
    await panel.getByRole('button', { name: '查询检索趋势', exact: true }).click(); await idle();
    assert.equal(await panel.getByTestId('trend-queries').innerText(), '0');
    await panel.getByLabel('趋势来源', { exact: true }).selectOption('CONSOLE');
    await admin.route('**/api/v1/runbooks/searches/trend?*', route => route.abort('failed'), { times: 1 });
    await panel.getByRole('button', { name: '查询检索趋势', exact: true }).click(); await idle();
    assert.equal(await panel.locator('.trend-result').count(), 0);
    await panel.getByRole('alert').waitFor(); await snapshot(admin, 'cp55-read-failure-mobile.png', panel);
    await panel.getByRole('button', { name: '查询检索趋势', exact: true }).click(); await idle();
    assert.equal(await panel.getByTestId('trend-hit-rate').innerText(), '33.3%');
    for (const name of ['zhangwei', 'auditor']) {
      const readonly = await login(name);
      assert.equal(await readonly.locator('.retrieval-trend').count(), 0);
      const credential = await readonly.evaluate(() => localStorage.getItem('opspilot_token'));
      await api(credential, '/runbooks/searches/trend', null, 403);
    }
    assert.deepEqual(errors, []);
    assert.ok(failedResponses.every(item => item.status === 404 && item.path === '/api/v1/runbooks/evaluations/latest'));
    assert.equal(logs.filter(line => /server responded with a status of 404/.test(line)).length, failedResponses.length);
    assert.equal(logs.filter(line => !/net::ERR_FAILED|server responded with a status of 404/.test(line)).length, 0);
    assert.equal(logs.filter(line => /net::ERR_FAILED/.test(line)).length, 1);
    const result = { status: 'PASS', browserPlugin: 'Browser plugin not available', browser: browser.version(), browserTimezone: 'America/Los_Angeles', jarTimezone: 'UTC', viewports: ['1440x1000','390x844'], screenshots,
      checks: { identity: true, notBlank: true, noOverlay: true, console: true, screenshots: true, interaction: true },
      verified: { realPersistedSearch: true, requestedHybridActualBm25: true, completeReviewOnly: true, partialPositiveExcluded: true, literalDatabaseDate: true, zeroSampleNA: true, sourceAndEngineFilters: true, failedReadClearsOldCohort: true, manualReadRecovery: true, readonlyRoleGate: true, mobileNoPageOverflow: true },
      fixture: { queries: 4, complete: 2, partial: 1, empty: 1, numerator: 1, denominator: 3, nonProductionFixture: true }, pageErrors: 0, intentionalFailedGetConsoleErrors: 1, expectedMissingOfflineEvaluation404: failedResponses.length };
    fs.writeFileSync(path.join(out, 'result.json'), JSON.stringify(result, null, 2)); console.log(JSON.stringify(result));
  } finally { await browser.close(); }
})().catch(error => { console.error(error.message); process.exitCode = 1; });

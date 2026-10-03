const { chromium } = require(process.env.OPSPILOT_PLAYWRIGHT_MODULE || 'playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { setTimeout: delay } = require('node:timers/promises');
const root = process.env.OPSPILOT_BASE_URL || 'http://127.0.0.1:9917';
const baseline = process.env.OPSPILOT_STREAM_SESSION_BASELINE === '1';
assert.equal(process.env.OPSPILOT_ACCEPTANCE_ISOLATED, '1', 'Revocation requires an owned isolated database');
assert.ok(['localhost', '127.0.0.1'].includes(new URL(root).hostname));
assert.notEqual(new URL(root).port, '9900');
assert.ok(!baseline || !process.env.CI, 'A baseline cannot replace browser acceptance');
const out = fs.mkdtempSync(path.join(process.env.OPSPILOT_EVIDENCE_DIR || require('node:os').tmpdir(), 'stream-session-'));

(async () => {
  const browser = await chromium.launch({ executablePath: process.env.OPSPILOT_CHROME_PATH || undefined, headless: true });
  const result = { status: 'RUNNING', baselineCapture: baseline, browser: browser.version(),
    browserPath: 'Browser plugin not available; existing Playwright', viewports: ['1440x1000', '390x844'],
    fixture: 'actual revoked JWT 401 held until a different account finishes real UI login in a shared-storage tab',
    pageErrors: [], consoleLogs: [], screenshots: [], cases: [], tokensPersistedToEvidence: false };
  async function login(page, username) {
    await page.goto(root + '/login');
    await page.getByLabel('账号', { exact: true }).fill(username);
    await page.getByLabel('密码', { exact: true }).fill('OpsPilot@2026');
    await page.getByRole('button', { name: '进入控制台', exact: true }).click();
    await page.waitForURL(root + '/');
    return page.evaluate(() => localStorage.getItem('opspilot_token'));
  }
  async function call(token, route, body) {
    const response = await fetch(root + '/api/v1' + route, { method: body ? 'POST' : 'GET',
      headers: { Authorization: 'Bearer ' + token, ...(body ? { 'Content-Type': 'application/json' } : {}) },
      body: body ? JSON.stringify(body) : undefined, signal: AbortSignal.timeout(8000) });
    return { status: response.status, json: await response.json() };
  }
  async function snapshot(page, name) {
    assert.equal(await page.title(), 'OpsPilot 智能运维平台');
    assert.ok((await page.locator('body').innerText()).length > 100);
    assert.equal(await page.locator('vite-error-overlay').count(), 0);
    await page.screenshot({ path: path.join(out, name), animations: 'disabled',
      mask: await page.locator('input[type=password]').all() });
    result.screenshots.push(name);
  }
  try {
    for (const flow of ['incident-agent', 'assistant-agent', 'assistant-chat']) {
      const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, timezoneId: 'Asia/Shanghai' });
      const owner = await context.newPage();
      owner.on('pageerror', error => result.pageErrors.push(error.message));
      owner.on('console', item => { if (['error', 'warning'].includes(item.type())) result.consoleLogs.push(item.text()); });
      const original = await login(owner, 'zhangwei');
      await owner.goto(root + (flow === 'incident-agent' ? '/incidents?selected=1' : '/assistant?incident=1'));
      let intercepted, enter;
      const entered = new Promise(resolve => { enter = resolve; });
      let release;
      const released = new Promise(resolve => { release = resolve; });
      const pattern = flow === 'assistant-chat' ? '**/api/v1/assistant/sessions/*/stream'
        : '**/api/v1/incidents/1/investigations/stream?*';
      let commands = 0, delivery = 'not-delivered';
      const heldRoute = async route => {
        commands++; intercepted = route; enter(); await released;
      };
      await owner.route(pattern, heldRoute);
      if (flow === 'incident-agent') await owner.getByRole('button', { name: '运行 Agent 调查', exact: true }).click();
      else if (flow === 'assistant-agent') await owner.getByTitle('运行只读 Agent 调查', { exact: true }).click();
      else {
        await owner.getByPlaceholder('询问当前证据、根因假设或下一步动作', { exact: true }).fill('总结当前证据');
        await owner.getByTitle('发送消息', { exact: true }).click();
      }
      await Promise.race([entered, delay(8000).then(() => { throw new Error('Expected old stream was not intercepted'); })]);
      await snapshot(owner, flow + '-before-response.png');
      const revoked = await call(original, '/auth/logout-all', {});
      assert.equal(revoked.status, 200);
      // The second real tab starts with the revoked JWT in shared localStorage, then signs in as ADMIN.
      const replacement = await context.newPage();
      const current = await login(replacement, 'admin');
      assert.ok(current && current !== original);
      const request = intercepted.request();
      const realUnauthorized = await context.request.fetch(request.url(), { method: request.method(),
        headers: { Authorization: 'Bearer ' + original, 'Content-Type': 'application/json' },
        data: request.postData() || undefined, timeout: 8000 });
      assert.equal(realUnauthorized.status(), 401);
      assert.equal((await realUnauthorized.json()).error.code, 'AUTHENTICATION_REQUIRED');
      try { await intercepted.fulfill({ response: realUnauthorized }); delivery = 'fulfilled'; }
      catch { delivery = 'already-aborted'; }
      release();
      if (baseline) await owner.waitForURL(url => url.pathname === '/login' && url.searchParams.get('reason') === 'expired');
      else await delay(250);
      const kept = await owner.evaluate(() => localStorage.getItem('opspilot_token'));
      assert.equal(kept, baseline ? null : current, 'Old transport must not clear a replacement account credential');
      assert.equal(commands, 1, 'No automatic POST replay under the new account');
      assert.equal((await call(current, '/auth/me')).status, 200, 'The replacement server credential remains valid');
      if (baseline) await snapshot(owner, flow + '-old-expired-desktop.png');
      else {
        assert.ok(!owner.url().includes('/login'));
        await owner.reload();
        await owner.locator('.user-block').filter({ hasText: '系统管理员' }).waitFor();
        if (flow === 'incident-agent') await owner.getByRole('button', { name: '运行 Agent 调查', exact: true }).waitFor();
        else await owner.locator('.assistant-loading').waitFor({ state: 'hidden' });
        await snapshot(owner, flow + '-new-kept-desktop.png');
      }
      await owner.setViewportSize({ width: 390, height: 844 });
      if (!baseline && flow !== 'incident-agent') {
        await owner.waitForFunction(() => {
          const sessions = document.querySelector('.assistant-session-rail')?.getBoundingClientRect();
          const context = document.querySelector('.assistant-context-rail')?.getBoundingClientRect();
          return sessions && context && sessions.right <= 1 && context.left >= innerWidth - 1;
        });
      }
      await snapshot(owner, flow + (baseline ? '-old-expired-mobile.png' : '-new-kept-mobile.png'));
      assert.equal(await owner.evaluate(() => document.documentElement.scrollWidth), 390);
      result.cases.push({ flow, actualUnauthorizedStatus: 401, realLogoutStatus: 200,
        oldResponseDelivery: delivery, commands, replacementCredentialPreserved: !baseline,
        replacementServerCredentialStillValid: true, identityReloadShowsAdmin: !baseline,
        oldExpiredPageShown: baseline, mobileWidth: 390, settledMobileDrawersChecked: !baseline && flow !== 'incident-agent' });
      await context.close();
    }
    assert.deepEqual(result.pageErrors, []);
    assert.ok(result.consoleLogs.every(line => /Failed to load resource:.*(?:401|net::ERR_ABORTED|net::ERR_FAILED)/.test(line)), result.consoleLogs.join('\n'));
    Object.assign(result, { status: baseline ? 'BASELINE_CAPTURED' : 'PASS', pageIdentity: true, noBlank: true,
      noOverlay: true, allThreeFlowsVerified: true, automaticMutationRetries: 0 });
  } catch (error) { result.status = 'FAIL'; result.failure = error.message; throw error; }
  finally {
    await browser.close();
    fs.writeFileSync(path.join(out, 'result.json'), JSON.stringify(result, null, 2));
    console.log(JSON.stringify(result));
  }
})().catch(error => { console.error(error.message); process.exitCode = 1; });

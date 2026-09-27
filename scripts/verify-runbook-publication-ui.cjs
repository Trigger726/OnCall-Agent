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
  const expectedDecisionConflicts = new Set(), expectedDetailFailures = new Set();
  const login = async name => {
    const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
    const page = await context.newPage();
    page.on('pageerror', e => errors.push(e.message));
    page.on('console', m => { if (['error','warning'].includes(m.type())) logs.push(m.text()); });
    page.on('response', r => { if (r.status() >= 400) failures.push({ status: r.status(), path: new URL(r.url()).pathname }); });
    await page.goto(root + '/login'); await page.getByLabel('账号', { exact: true }).fill(name);
    await page.getByRole('button', { name: '进入控制台', exact: true }).click(); await page.waitForURL(root + '/');
    await page.goto(root + '/runbooks'); await page.locator('.retrieval-panel').waitFor();
    assert.match(await page.title(), /OpsPilot/); assert.equal(new URL(page.url()).pathname, '/runbooks');
    assert.equal(await page.locator('vite-error-overlay').count(), 0);
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

      await manager.setViewportSize({ width: 1440, height: 1000 });
      const managerToken = await token(manager);
      const pick = async (page, stableKey, version) => {
        const p = page.locator('.publication-queue');
        await p.getByRole('button', { name: '刷新审核台账', exact: true }).click();
        await p.getByRole('button', { name: new RegExp(version ? stableKey + ' · v' + version + ' ·' : stableKey) }).click();
        await p.locator('.publication-detail h3').filter({ hasText: stableKey }).waitFor();
        return p;
      };
      const decideUi = async (page, kind, reason, expected) => {
        const p = page.locator('.publication-queue');
        await p.getByLabel('发布决定').selectOption(kind); await p.getByLabel('复核说明').fill(reason);
        await p.getByRole('button', { name: '确认提交决定', exact: true }).click();
        await p.getByRole('status').filter({ hasText: `已确认：${expected}` }).waitFor();
      };
      const importUi = async (suffix, pdf) => {
        const stableKey = key + suffix;
        await admin.getByRole('button', { name: '导入 Runbook', exact: true }).click();
        const form = admin.locator('.runbook-import-dialog');
        const keyInput = form.getByLabel('稳定键', { exact: true });
        await keyInput.fill('Invalid_key');
        assert.equal(await keyInput.evaluate(input => input.validity.patternMismatch), true);
        await keyInput.fill(stableKey);
        assert.equal(await keyInput.evaluate(input => input.validity.valid), true);
        await form.getByLabel('标题', { exact: true }).fill('独立发布验收 ' + suffix);
        if (!pdf) {
          await form.getByRole('button', { name: '粘贴 Markdown', exact: true }).click();
          await form.getByLabel('来源路径', { exact: true }).fill('acceptance.md');
          await form.getByLabel('Markdown 内容', { exact: true }).fill('# 验证\ncp56markdownuiprobe');
        } else {
          const stream = 'BT /F1 12 Tf 40 700 Td (cp56pdfuiprobe independent recovery validation) Tj ET';
          const objects = ['<< /Type /Catalog /Pages 2 0 R >>', '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
            '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>',
            `<< /Length ${Buffer.byteLength(stream)} >>\nstream\n${stream}\nendstream`, '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>'];
          let body = '%PDF-1.4\n', offsets = [0];
          objects.forEach((object, i) => { offsets.push(Buffer.byteLength(body)); body += `${i + 1} 0 obj\n${object}\nendobj\n`; });
          const xref = Buffer.byteLength(body);
          body += 'xref\n0 6\n0000000000 65535 f \n' + offsets.slice(1).map(offset => String(offset).padStart(10,'0') + ' 00000 n \n').join('');
          body += `trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`;
          await form.getByRole('button', { name: '上传 .md / .pdf', exact: true }).click();
          await form.locator('input[type=file]').setInputFiles({ name: 'publication-acceptance.pdf', mimeType: 'application/pdf', buffer: Buffer.from(body) });
        }
        await form.getByRole('button', { name: '提交待审候选', exact: true }).click(); await form.waitFor({ state: 'hidden' });
        await admin.locator('.success-banner').filter({ hasText: '已提交待审' }).waitFor();
        const row = (await request(adminToken, '/runbooks/publications')).items.find(item => item.stableKey === stableKey);
        assert.ok(row); const detail = await request(adminToken, `/runbooks/publications/${row.id}`);
        assert.equal(detail.document.status, 'PENDING_REVIEW'); assert.equal(detail.document.sourceType, pdf ? 'PDF' : 'MARKDOWN');
        assert.equal((await request(adminToken, '/runbooks/search?q=' + (pdf ? 'cp56pdfuiprobe' : 'cp56markdownuiprobe'))).results.length, 0);
        return row;
      };
      const markdown = await importUi('-markdown', false);
      await pick(admin, markdown.stableKey); await decideUi(admin, 'WITHDRAW', '补充恢复步骤后另版提交', 'WITHDRAWN');
      assert.equal((await request(adminToken, `/runbooks/publications/${markdown.id}`)).document.status, 'WITHDRAWN');
      const pdf = await importUi('-pdf', true);
      await pick(manager, pdf.stableKey); await decideUi(manager, 'REJECT', '缺少回滚前置条件', 'REJECTED');
      assert.equal((await request(adminToken, '/runbooks/search?q=cp56pdfuiprobe')).results.length, 0);
      const pdfV2 = await importUi('-pdf', true); assert.equal(pdfV2.versionNo, 2);
      await pick(manager, pdfV2.stableKey); await decideUi(manager, 'APPROVE', '独立核对文本提取与恢复验证', 'PUBLISHED');
      assert.ok((await request(adminToken, '/runbooks/search?q=cp56pdfuiprobe')).results.some(item => item.stableKey === pdf.stableKey));

      const stage = async (suffix, body) => (await request(adminToken, '/runbooks/imports/markdown', {
        stableKey: key + suffix, resourceType: 'APPLICATION', title: '恢复边界验收', sourceName: 'fixture.md',
        markdown: '# 验证\n' + body, allowedRoles: ['ADMIN','OPS_MANAGER','ON_CALL'] })).document;
      const winner = await stage('-race', 'winning candidate'), stale = await stage('-race', 'stale candidate');
      await pick(manager, stale.stableKey, stale.versionNo);
      await review.locator('.publication-detail h3').filter({ hasText: 'v' + stale.versionNo }).waitFor();
      await request(managerToken, `/runbooks/publications/${winner.id}/decisions`, { expectedVersion: 0,
        decision: 'APPROVE', requestKey: require('node:crypto').randomUUID(), reason: '并行独立复核另一候选' });
      expectedDecisionConflicts.add(`/api/v1/runbooks/publications/${stale.id}/decisions`);
      await review.getByLabel('复核说明').fill('旧基线不能覆盖后来发布');
      await review.getByRole('button', { name: '确认提交决定', exact: true }).click();
      await review.getByText('冲突或权限拒绝已锁定，不会自动换版本重提。', { exact: false }).waitFor();
      const locked = await manager.evaluate(() => JSON.parse(sessionStorage.getItem('opspilot_publication_intent_3')));
      assert.equal(locked.locked, true); assert.equal(locked.expectedVersion, 0);
      await manager.reload(); await review.getByRole('button', { name: '同键重试原决定', exact: true }).waitFor();
      assert.equal(await review.getByRole('button', { name: '同键重试原决定', exact: true }).isDisabled(), true);
      assert.deepEqual(await manager.evaluate(() => JSON.parse(sessionStorage.getItem('opspilot_publication_intent_3'))), locked);
      await shot(manager, 'cp56-conflict-locked-desktop.png');
      const discard = async () => {
        manager.once('dialog', dialog => dialog.accept());
        await review.getByRole('button', { name: '核对后放弃本地意图', exact: true }).click();
      };
      await discard(); assert.equal((await request(adminToken, `/runbooks/publications/${stale.id}`)).document.status, 'PENDING_REVIEW');

      const fresh = await stage('-recovery', 'recovery candidate'); await pick(manager, fresh.stableKey);
      let recoveryPosts = 0;
      manager.on('request', r => { if (r.method() === 'POST' && r.url().endsWith(`/publications/${fresh.id}/decisions`)) recoveryPosts++; });
      await manager.evaluate(() => {
        window.__publicationOriginalSet = Storage.prototype.setItem;
        Storage.prototype.setItem = function(key, value) { if (key.startsWith('opspilot_publication_intent_')) throw new Error('fixture quota'); return window.__publicationOriginalSet.call(this, key, value); };
      });
      await review.getByLabel('复核说明').fill('存储失败不提交');
      await review.getByRole('button', { name: '确认提交决定', exact: true }).click();
      await review.getByRole('alert').filter({ hasText: '无法保存冻结意图，本次未提交' }).waitFor();
      assert.equal(recoveryPosts, 0);
      await manager.evaluate(() => { Storage.prototype.setItem = window.__publicationOriginalSet; sessionStorage.setItem('opspilot_publication_intent_3', 'broken-json'); });
      await manager.reload(); await review.getByRole('alert').filter({ hasText: '冻结意图损坏' }).waitFor();
      await pick(manager, fresh.stableKey);
      assert.equal(await review.getByRole('button', { name: '确认提交决定', exact: true }).count(), 0);
      assert.equal(await manager.evaluate(() => sessionStorage.getItem('opspilot_publication_intent_3')), 'broken-json');
      await manager.setViewportSize({ width: 390, height: 844 });
      await manager.waitForFunction(() => document.querySelector('.main-frame').getBoundingClientRect().left === 0);
      assert.equal(await manager.evaluate(() => document.documentElement.scrollWidth), 390);
      await shot(manager, 'cp56-corrupt-intent-mobile.png');
      await discard(); assert.equal(recoveryPosts, 0);
      const frozen = { actorId: 3, id: fresh.id, expectedVersion: 0, requestKey: require('node:crypto').randomUUID(), decision: 'APPROVE', reason: '按账号恢复原意图', locked: false };
      await manager.evaluate(value => sessionStorage.setItem('opspilot_publication_intent_3', JSON.stringify(value)), frozen);
      const adminUser = await request(adminToken, '/auth/me'), managerUser = await request(managerToken, '/auth/me');
      await manager.evaluate(({ access, user }) => { localStorage.setItem('opspilot_token', access); localStorage.setItem('opspilot_user', JSON.stringify(user)); }, { access: adminToken, user: adminUser });
      await manager.reload(); await review.getByRole('button', { name: '刷新审核台账', exact: true }).waitFor();
      assert.equal(await review.locator('.publication-recovery').count(), 0);
      assert.deepEqual(await manager.evaluate(() => JSON.parse(sessionStorage.getItem('opspilot_publication_intent_3'))), frozen);
      await manager.evaluate(({ access, user }) => { localStorage.setItem('opspilot_token', access); localStorage.setItem('opspilot_user', JSON.stringify(user)); }, { access: managerToken, user: managerUser });
      await manager.reload(); await review.getByRole('button', { name: '同键重试原决定', exact: true }).waitFor();
      assert.equal(recoveryPosts, 0);
      await discard();
      const missing = { ...frozen, id: 9000000000 };
      expectedDetailFailures.add('/api/v1/runbooks/publications/9000000000');
      await manager.evaluate(value => sessionStorage.setItem('opspilot_publication_intent_3', JSON.stringify(value)), missing);
      await manager.reload(); await review.getByRole('alert').filter({ hasText: '候选手册不存在' }).waitFor();
      assert.equal(await review.getByRole('button', { name: '核对后放弃本地意图', exact: true }).count(), 1);
      await discard(); assert.equal(recoveryPosts, 0);
      assert.equal((await request(adminToken, `/runbooks/publications/${fresh.id}`)).document.status, 'PENDING_REVIEW');
      // Exercise JSON binding through the real owned JAR, not just an in-process controller fixture.
      for (const decision of ['APPROVE', 'REJECT', 'WITHDRAW']) for (const explicitNull of [false, true]) {
        const candidate = await request(adminToken, '/runbooks/imports/markdown', {
          stableKey: key + '-version-' + decision.toLowerCase() + '-' + explicitNull,
          resourceType: 'APPLICATION', title: '显式审核版本 HTTP 验收', sourceName: 'version.md',
          markdown: '# 验证\nCaptured review version is required', allowedRoles: ['ADMIN','OPS_MANAGER','ON_CALL'],
        });
        const id = candidate.document.id, actorToken = decision === 'WITHDRAW' ? adminToken : managerToken;
        const command = { decision, requestKey: require('node:crypto').randomUUID(), reason: '已明确核对审核版本' };
        if (explicitNull) command.expectedVersion = null;
        await request(actorToken, `/runbooks/publications/${id}/decisions`, command, 400);
        const untouched = await request(adminToken, `/runbooks/publications/${id}`);
        assert.equal(untouched.document.status, 'PENDING_REVIEW'); assert.equal(untouched.reviewVersion, 0);
        assert.equal(untouched.currentPublishedVersion, 0); assert.equal(untouched.decision, null);
        command.expectedVersion = 0;
        const accepted = await request(actorToken, `/runbooks/publications/${id}/decisions`, command);
        assert.equal(accepted.reviewVersion, 1);
        assert.equal(accepted.document.status, { APPROVE: 'PUBLISHED', REJECT: 'REJECTED', WITHDRAW: 'WITHDRAWN' }[decision]);
      }
    }
    assert.deepEqual(errors, []);
    assert.ok(failures.every(f => f.status === 404 && (f.path === '/api/v1/runbooks/evaluations/latest' || expectedDetailFailures.has(f.path))
      || f.status === 409 && expectedDecisionConflicts.has(f.path)));
    const unexpected = logs.filter(line => !/server responded with a status of (404|409)/.test(line) && !(!baseline && /net::ERR_FAILED/.test(line)));
    assert.deepEqual(unexpected, []);
    assert.equal(logs.filter(line => /net::ERR_FAILED/.test(line)).length, baseline ? 0 : 1);
    const result = { status: baseline ? 'BASELINE_CAPTURED' : 'PASS', publicationImplemented: !baseline,
      importedStatus: draft.document.status,
      browser: browser.version(), viewports: ['1440x1000','390x844'], screenshots, pageErrors: errors,
      expectedMissingEvaluations: failures.filter(f => f.path === '/api/v1/runbooks/evaluations/latest').length,
      expectedDecisionConflicts: [...expectedDecisionConflicts], expectedMissingDetails: [...expectedDetailFailures],
      expectedAbortedDecisionResponses: baseline ? 0 : 1,
      requiredVersionHttp: baseline ? null : { rejectedMissingOrNull: 6, explicitZeroAccepted: 6, rejectedCandidateUnchanged: true },
      extendedUi: baseline ? null : { markdownWithdraw: true, pdfRejectThenApproveNewVersion: true, baseline409LockSurvivesReload: true,
        quotaFailureNoPost: true, corruptIntentPreservedUntilExplicitDiscard: true, accountIsolation: true,
        missingDetailAllowsExplicitDiscard: true, manualRetryOnly: true },
      unexpectedConsoleErrors: unexpected, reason: 'Browser plugin not available', productionDataModified: false };
    fs.writeFileSync(path.join(out, 'result.json'), JSON.stringify(result, null, 2)); console.log(JSON.stringify(result));
  } catch (error) {
    for (const context of browser.contexts()) for (const page of context.pages()) {
      if (page.viewportSize()?.width === 390 && await page.locator('.publication-queue').count()) {
        await page.screenshot({ path: path.join(out, 'failure-mobile-layout.png') });
        const layout = await page.evaluate(() => ({ documentWidth: document.documentElement.scrollWidth,
          frame: { left: document.querySelector('.main-frame').getBoundingClientRect().left, width: document.querySelector('.main-frame').getBoundingClientRect().width },
          overflowing: [...document.querySelectorAll('.publication-queue *')].filter(el => el.getBoundingClientRect().right > 391)
            .map(el => ({ tag: el.tagName, className: el.className, right: el.getBoundingClientRect().right, whiteSpace: getComputedStyle(el).whiteSpace })).slice(0,20) }));
        fs.writeFileSync(path.join(out, 'failure-mobile-layout.json'), JSON.stringify(layout, null, 2));
      }
      const form = page.locator('.runbook-import-dialog');
      if (await form.isVisible().catch(() => false)) {
        await page.screenshot({ path: path.join(out, 'failure-upload-dialog.png') });
        fs.writeFileSync(path.join(out, 'failure-upload-accessibility.txt'), await form.ariaSnapshot());
      }
    }
    fs.writeFileSync(path.join(out, 'failure.json'), JSON.stringify({ status: 'FAIL', message: error.message }, null, 2));
    throw error;
  } finally { await browser.close(); }
})().catch(e => { console.error(e.message); process.exitCode = 1; });

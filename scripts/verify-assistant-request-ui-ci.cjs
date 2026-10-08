const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const http = require('node:http');
const { spawn } = require('node:child_process');
const { randomUUID, createHash } = require('node:crypto');
const { setTimeout: delay } = require('node:timers/promises');
const { requireFreePort, stopProcess, waitForHealth, redact, unexpectedLogLines } = require('./verify-oncall-browser-ci.cjs');

async function verify() {
  const baseline = process.env.OPSPILOT_ASSISTANT_UI_BASELINE === '1';
  if (baseline && process.env.CI) throw new Error('Baseline capture cannot replace assistant UI acceptance');
  const root = path.resolve(__dirname, '..');
  const jar = path.join(root, baseline ? 'target/cp73-before/opspilot-cp72.jar' : 'target/opspilot-0.1.0-SNAPSHOT.jar');
  assert.ok(fs.existsSync(jar), 'Build or preserve the scoped JAR first');
  await requireFreePort(9945); await requireFreePort(9946);
  const parent = path.join(root, 'target/assistant-request-ui-it'); fs.mkdirSync(parent, { recursive: true });
  const evidence = fs.mkdtempSync(path.join(parent, 'run-'));
  const { chromium } = require(process.env.OPSPILOT_PLAYWRIGHT_MODULE || path.join(root, 'scripts/browser/node_modules/playwright'));
  const base = 'http://127.0.0.1:9945';
  const result = { status: 'RUNNING', baselineCapture: baseline, url: base + '/assistant',
    browserReason: 'Browser plugin not available; existing Playwright', viewports: ['1440x1000', '390x844'],
    jarSha256: createHash('sha256').update(fs.readFileSync(jar)).digest('hex'),
    fixture: 'owned production HTTP adapter and real browser controls, not model quality',
    tokensPersistedToEvidence: false, userFileDatabaseModified: false, cases: [], screenshots: [], pageErrors: [], consoleErrors: [] };
  const sentinel = 'CP73-controlled-HTTP-answer';
  let holding = false, calls = 0, child, browser, descriptor;
  const held = new Set();
  const nativeResponses = new WeakSet();
  function reply(response) {
    require('./assistant-provider-fixture.cjs').reply(response, sentinel, nativeResponses.has(response));
  }
  const provider = http.createServer((request, response) => {
    if (request.headers['x-dashscope-sse'] === 'enable') nativeResponses.add(response);
    if (request.method !== 'POST' || request.url !== '/api/v1/services/aigc/text-generation/generation') {
      response.writeHead(404); response.end(); return;
    }
    request.resume(); request.once('end', () => {
      calls++; if (!holding) reply(response);
      else { held.add(response); response.once('close', () => held.delete(response)); }
    });
  });
  const release = () => { holding = false; for (const response of held) reply(response); held.clear(); };
  await new Promise((resolve, reject) => { provider.once('error', reject); provider.listen(0, '127.0.0.1', resolve); });
  const log = path.join(evidence, 'jar.log');
  const interrupt = () => { if (child && child.exitCode === null && child.signalCode === null) child.kill('SIGTERM'); };
  process.once('SIGINT', interrupt); process.once('SIGTERM', interrupt);
  async function until(predicate, budget = 5000) {
    const end = Date.now() + budget;
    do { if (await predicate()) return; await delay(30); } while (Date.now() < end);
    assert.fail('Controlled browser/HTTP condition did not settle');
  }
  async function api(context, token, route, method = 'GET', body, key) {
    const response = await context.request.fetch(base + '/api/v1' + route, { method,
      headers: { Authorization: 'Bearer ' + token, ...(key ? { 'Idempotency-Key': key } : {}) },
      ...(body ? { data: body } : {}), timeout: 45000 });
    return { status: response.status(), json: await response.json() };
  }
  async function snapshot(page, name) {
    await page.waitForFunction(() => {
      if (document.documentElement.scrollWidth > innerWidth) return false;
      if (innerWidth > 700) return true;
      const left = document.querySelector('.assistant-session-rail').getBoundingClientRect();
      const right = document.querySelector('.assistant-context-rail').getBoundingClientRect();
      return left.right <= 1 && right.left >= innerWidth - 1;
    }, undefined, { timeout: 2500 }).catch(error => { if (!baseline) throw error; });
    assert.match(page.url(), /\/assistant/); assert.equal(await page.title(), 'OpsPilot 智能运维平台');
    assert.ok((await page.locator('body').innerText()).length > 100);
    assert.equal(await page.locator('vite-error-overlay').count(), 0);
    const file = path.join(evidence, name + '.png'); await page.screenshot({ path: file, animations: 'disabled' }); result.screenshots.push(path.basename(file));
    const dimensions = await page.evaluate(() => ({ width: innerWidth, scrollWidth: document.documentElement.scrollWidth }));
    if (dimensions.scrollWidth > dimensions.width) {
      result.layoutFindings ??= []; result.layoutFindings.push({ name, ...dimensions });
      assert.ok(baseline, 'New UI must fit its viewport');
    }
  }
  try {
    descriptor = fs.openSync(log, 'w');
    const java = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java';
    const providerUrl = 'http://127.0.0.1:' + provider.address().port;
    child = spawn(java, ['-Duser.timezone=UTC', '-jar', jar,
      '--server.address=127.0.0.1', '--server.port=9945', '--management.server.address=127.0.0.1', '--management.server.port=9946',
      '--spring.datasource.url=jdbc:h2:mem:assistant_ui_' + randomUUID() + ';MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1',
      '--spring.datasource.username=sa', '--spring.datasource.password=', '--spring.h2.console.enabled=false',
      '--opspilot.ai.enabled=true', '--spring.ai.dashscope.api-key=cp73-controlled-local-provider',
      '--spring.ai.dashscope.base-url=' + providerUrl, '--spring.ai.dashscope.chat.base-url=' + providerUrl,
      '--spring.ai.dashscope.read-timeout=40000', '--opspilot.assistant.workers=1', '--opspilot.assistant.queue-capacity=1',
      '--opspilot.assistant.execution-timeout=30s', '--opspilot.assistant.authorization-check-delay=100',
      '--opspilot.agent.recovery.enabled=false', '--opspilot.oncall.rotation.enabled=false', '--opspilot.oncall.escalation.enabled=false'],
      { cwd: root, stdio: ['ignore', descriptor, descriptor], windowsHide: true });
    child.once('error', error => { child.launchError = error; }); result.startedPid = child.pid;
    await waitForHealth('http://127.0.0.1:9946/actuator/health', child);
    browser = await chromium.launch({ headless: true, ...(process.env.OPSPILOT_CHROME_PATH ? { executablePath: process.env.OPSPILOT_CHROME_PATH } : {}) });
    const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, timezoneId: 'Asia/Shanghai' });
    const page = await context.newPage();
    page.on('pageerror', e => result.pageErrors.push(redact(e.message)));
    page.on('console', m => { if (m.type() === 'error') result.consoleErrors.push(redact(m.text())); });
    await page.goto(base + '/login'); await page.getByLabel('账号').fill('admin'); await page.getByLabel('密码').fill('OpsPilot@2026');
    await page.getByRole('button', { name: '进入控制台' }).click(); await page.waitForURL(base + '/');
    const token = await page.evaluate(() => localStorage.getItem('opspilot_token'));
    const posts = [], cancels = [];
    const responseEvidence = [];
    page.on('response', response => {
      if (response.url().includes('/assistant/sessions/') && response.url().endsWith('/stream')) {
        responseEvidence.push({ status: response.status(), contentType: response.headers()['content-type'] ?? null });
      }
    });
    page.on('request', r => {
      if (r.method() === 'POST' && /\/assistant\/sessions\/\d+\/stream$/.test(r.url())) posts.push({ url: r.url(), key: r.headers()['idempotency-key'], content: r.postDataJSON().content });
      if (r.method() === 'POST' && r.url().endsWith('/request/cancel')) cancels.push(r.headers()['idempotency-key']);
    });
    async function fresh() {
      const response = await api(context, token, '/assistant/sessions', 'POST', {}); assert.equal(response.status, 200);
      const id = response.json.data.session.id;
      await page.goto(base + '/assistant?session=' + id); await page.locator('.assistant-loading').waitFor({ state: 'hidden' });
      return id;
    }
    const counts = async id => (await api(context, token, '/assistant/sessions/' + id)).json.data.messages;
    const state = async (id, key) => api(context, token, '/assistant/sessions/' + id + '/request', 'GET', undefined, key);
    const send = async content => { await page.locator('.assistant-composer textarea').fill(content); await page.getByTitle('发送消息', { exact: true }).click(); };
    if (!baseline) {
      // Hold a real successful session GET: the composer must not accept a question before its context exists.
      const readyId = (await api(context, token, '/assistant/sessions', 'POST', {})).json.data.session.id;
      const routeUrl = base + '/api/v1/assistant/sessions/' + readyId;
      let delayedSession;
      await page.route(routeUrl, async route => {
        const response = await context.request.fetch(route.request()); assert.equal(response.status(), 200);
        delayedSession = { route, response };
      });
      await page.goto(base + '/assistant?session=' + readyId); await until(() => Boolean(delayedSession));
      await page.locator('.assistant-loading').waitFor({ state: 'visible' });
      await snapshot(page, 'context-loading-desktop');
      assert.equal(await page.locator('.assistant-composer textarea').isDisabled(), true);
      assert.equal(await page.getByTitle('发送消息', { exact: true }).isDisabled(), true);
      assert.equal(posts.length, 0); assert.equal(calls, 0);
      await delayedSession.route.fulfill({ response: delayedSession.response }); await page.unroute(routeUrl);
      await page.locator('.assistant-loading').waitFor({ state: 'hidden' });
      await page.locator('.assistant-composer textarea').fill('Context now ready');
      assert.equal(await page.getByTitle('发送消息', { exact: true }).isEnabled(), true);
      result.cases.push({ name: 'composer-waits-for-real-session-context', heldActualSessionStatus: 200,
        inputDisabledBeforeContext: true, noEarlyPosts: true, noEarlyModelCalls: true, sendEnabledAfterContext: true });
    }
    const id = await fresh(); holding = true; await send('held UI cancellation'); await until(() => held.size === 1);
    await snapshot(page, 'waiting-desktop');
    if (baseline) {
      assert.equal(posts[0].key, undefined); await page.getByTitle('停止生成', { exact: true }).click();
      await page.getByText('回答已停止。', { exact: true }).waitFor(); assert.equal(cancels.length, 0);
      await snapshot(page, 'local-stop-desktop');
      await page.setViewportSize({ width: 390, height: 844 }); await snapshot(page, 'local-stop-mobile');
      release(); await until(async () => (await counts(id)).some(x => x.role === 'ASSISTANT'));
      await page.reload(); await page.locator('.assistant-message-list').getByText(sentinel, { exact: true }).waitFor(); await snapshot(page, 'late-answer-mobile');
      result.cases.push({ name: 'local-abort-is-not-cancellation', keySent: false, explicitCancelPosts: 0,
        claimedLocalStop: true, answerCommittedAfterProviderRelease: true, answers: (await counts(id)).filter(x => x.role === 'ASSISTANT').length });
    } else {
      assert.match(posts[0].key, /^[0-9a-f-]{36}$/); await page.getByRole('button', { name: '查询原请求', exact: true }).click();
      await page.getByText('正在回答', { exact: true }).waitFor();
      await page.getByTitle('取消原请求', { exact: true }).click();
      await page.getByText('回答已取消', { exact: true }).waitFor();
      assert.equal(cancels.length, 1); assert.equal(cancels[0], posts[0].key);
      assert.equal((await state(id, posts[0].key)).json.data.status, 'CANCELLED');
      await snapshot(page, 'cancelled-desktop'); await page.setViewportSize({ width: 390, height: 844 }); await snapshot(page, 'cancelled-mobile');
      release(); await until(async () => (await counts(id)).filter(x => x.role === 'ASSISTANT').length === 0);
      await page.getByRole('button', { name: '继续提问', exact: true }).click();
      await send('positive answer after cancellation'); await page.locator('.assistant-message-list').getByText(sentinel, { exact: true }).waitFor();
      result.cases.push({ name: 'explicit-running-cancel', originalKeyUsed: true, serverState: 'CANCELLED', lateAnswers: 0,
        actualWorkerSettledByFreshAnswer: true });
      await page.setViewportSize({ width: 1440, height: 1000 });

      const completedId = await fresh(), beforeCalls = calls, beforePosts = posts.length;
      let dropped = false;
      await page.route('**/api/v1/assistant/sessions/' + completedId + '/stream', async route => {
        const response = await context.request.fetch(route.request(), { timeout: 45000 });
        assert.equal(response.status(), 200); assert.match(await response.text(), /"type":"done"/);
        dropped = true; await route.abort('failed');
      });
      await send('lost completed browser response'); await until(() => dropped);
      await page.getByText('结果待确认', { exact: true }).waitFor();
      await page.unroute('**/api/v1/assistant/sessions/' + completedId + '/stream');
      await page.reload(); await page.getByText('结果待确认', { exact: true }).waitFor();
      await snapshot(page, 'recovery-desktop');
      await page.getByRole('button', { name: '查询原请求', exact: true }).click(); await page.getByText('回答已完成', { exact: true }).waitFor();
      await page.locator('.assistant-message-list').getByText(sentinel, { exact: true }).waitFor();
      assert.equal(posts.length, beforePosts + 1); assert.equal(calls, beforeCalls + 1);
      assert.equal((await state(completedId, posts.at(-1).key)).json.data.answerMessageId, (await counts(completedId)).find(x => x.role === 'ASSISTANT').id);
      await snapshot(page, 'recovered-completed-desktop');
      result.cases.push({ name: 'lost-completed-response-refresh-query', actualCompletedResponseLost: true, originalAnswerIdPreserved: true,
        streamPostsIncludingRecovery: 1, modelCallsIncludingRecovery: 1 });

      const lostCancelId = await fresh(); holding = true; let forward, lostCancel = false;
      await page.route('**/api/v1/assistant/sessions/' + lostCancelId + '/stream', async route => {
        forward = context.request.fetch(route.request(), { timeout: 45000 }).catch(() => null); await route.abort('failed');
      });
      await send('lost start and cancel response'); await until(() => held.size === 1);
      await page.getByText('结果待确认', { exact: true }).waitFor();
      await page.route('**/api/v1/assistant/sessions/' + lostCancelId + '/request/cancel', async route => {
        const response = await context.request.fetch(route.request()); assert.equal((await response.json()).data.status, 'CANCELLED');
        lostCancel = true; await route.abort('failed');
      });
      await page.getByTitle('取消原请求', { exact: true }).click(); await until(() => lostCancel);
      await page.getByText('结果待确认', { exact: true }).waitFor();
      assert.equal(await page.getByText('回答已取消', { exact: true }).count(), 0);
      await page.unroute('**/api/v1/assistant/sessions/' + lostCancelId + '/request/cancel');
      await page.reload(); await page.getByRole('button', { name: '查询原请求', exact: true }).click();
      await page.getByText('回答已取消', { exact: true }).waitFor(); release(); await forward;
      assert.equal((await counts(lostCancelId)).filter(x => x.role === 'ASSISTANT').length, 0);
      const audits = (await api(context, token, '/audit-logs?limit=500')).json.data;
      assert.equal(audits.filter(x => x.action === 'ASSISTANT_REQUEST_CANCEL' && x.targetId === String(lostCancelId)).length, 1);
      result.cases.push({ name: 'lost-cancel-response', backendCancelResponseActuallyLost: true, noFalseCancelledBeforeQuery: true,
        queriedServerState: 'CANCELLED', cancelAuditRows: 1, lateAnswers: 0 });

      const missingId = await fresh(); const beforeMissingPosts = posts.length, beforeMissingCalls = calls;
      await page.route('**/api/v1/assistant/sessions/' + missingId + '/stream', route => route.abort('failed'));
      await send('not yet admitted same frozen question'); await page.getByText('结果待确认', { exact: true }).waitFor();
      await page.locator('.assistant-composer textarea').isDisabled().then(x => assert.equal(x, true));
      await page.getByRole('button', { name: '查询原请求', exact: true }).click(); await page.getByText('暂未查到原请求', { exact: true }).waitFor();
      await page.unroute('**/api/v1/assistant/sessions/' + missingId + '/stream');
      await page.getByRole('button', { name: '继续原请求', exact: true }).click(); await page.locator('.assistant-message-list').getByText(sentinel, { exact: true }).waitFor();
      assert.equal(posts.length, beforeMissingPosts + 2); assert.equal(posts.at(-1).key, posts.at(-2).key);
      assert.equal(posts.at(-1).content, posts.at(-2).content); assert.equal(calls, beforeMissingCalls + 1);
      result.cases.push({ name: 'not-admitted-manual-retry', serverLookupStatus: 404, noAutomaticPost: true,
        exactOriginalKeyAndContent: true, actualModelCalls: 1 });

      const blocker = (await api(context, token, '/assistant/sessions', 'POST', {})).json.data.session.id;
      holding = true;
      const blocking = api(context, token, '/assistant/sessions/' + blocker + '/messages', 'POST', { content: 'held capacity blocker' }, randomUUID())
        .catch(error => ({ transportError: redact(error.message) }));
      await until(() => held.size === 1);
      const queuedId = await fresh(), beforeQueuedCalls = calls;
      await send('queued UI cancellation');
      await until(async () => (await state(queuedId, posts.at(-1).key)).json.data?.status === 'QUEUED');
      await page.getByRole('button', { name: '查询原请求', exact: true }).click(); await page.getByText('正在排队', { exact: true }).waitFor();
      await snapshot(page, 'queued-desktop');
      await page.getByTitle('取消原请求', { exact: true }).click(); await page.getByText('回答已取消', { exact: true }).waitFor();
      assert.equal(calls, beforeQueuedCalls); assert.equal((await counts(queuedId)).length, 0);
      result.cases.push({ name: 'queued-ui-cancel', queriedStateBeforeCancel: 'QUEUED', questionAndAnswerRows: 0, modelCallsForQueuedQuestion: 0 });

      const occupiedQueue = (await api(context, token, '/assistant/sessions', 'POST', {})).json.data.session.id, occupyingKey = randomUUID();
      const occupying = api(context, token, '/assistant/sessions/' + occupiedQueue + '/messages', 'POST', { content: 'occupied queue fixture' }, occupyingKey)
        .catch(error => ({ transportError: redact(error.message) }));
      await until(async () => (await state(occupiedQueue, occupyingKey)).json.data?.status === 'QUEUED');
      const saturatedId = await fresh(), beforeSaturatedPosts = posts.length;
      await send('UI saturated same key manual recovery');
      await page.getByText('结果待确认', { exact: true }).waitFor();
      assert.match(await page.locator('.assistant-inline-error').innerText(), /繁忙|队列|稍后/);
      assert.equal(responseEvidence.at(-1).status, 503);
      assert.match(responseEvidence.at(-1).contentType, /application\/json/);
      assert.equal(posts.length, beforeSaturatedPosts + 1); assert.equal((await counts(saturatedId)).length, 0);
      await page.getByRole('button', { name: '查询原请求', exact: true }).click(); await page.getByText('暂未查到原请求', { exact: true }).waitFor();
      await snapshot(page, 'saturated-desktop');
      release(); assert.equal((await blocking).status, 200); assert.equal((await occupying).status, 200);
      await page.getByRole('button', { name: '继续原请求', exact: true }).click(); await page.getByText('回答已完成', { exact: true }).waitFor();
      assert.equal(posts.at(-1).key, posts.at(-2).key); assert.equal(posts.at(-1).content, posts.at(-2).content);
      result.cases.push({ name: 'real-capacity-feedback', serverCapacity: 'one worker and one queue slot', actualRejectedStatus: 503,
        actualJsonErrorResponse: true, rejectedQuestionRows: 0,
        noAutomaticPost: true, lookupStatus: 404, manualRecoveryWithOriginalKeyAndQuestion: true });

      const timeoutId = await fresh(); holding = true; await send('UI deadline terminal'); await until(() => held.size === 1);
      await page.getByText('结果待确认', { exact: true }).waitFor({ timeout: 35000 });
      await page.getByRole('button', { name: '查询原请求', exact: true }).click(); await page.getByText('原请求已超时', { exact: true }).waitFor();
      assert.equal(await page.getByRole('button', { name: '继续原请求', exact: true }).count(), 0);
      await page.setViewportSize({ width: 390, height: 844 }); await snapshot(page, 'timed-out-mobile');
      release(); assert.equal((await counts(timeoutId)).filter(x => x.role === 'ASSISTANT').length, 0);
      await page.getByRole('button', { name: '继续提问', exact: true }).click(); await send('actual answer after timeout');
      await page.getByText('回答已完成', { exact: true }).waitFor();
      result.cases.push({ name: 'real-budget-timeout', serverBudgetSeconds: 30, queriedTerminal: 'TIMED_OUT',
        originalRequestNotRerun: true, lateAnswers: 0, actualWorkerSettledByFreshAnswer: true });

      await page.getByRole('button', { name: '继续提问', exact: true }).click();
      let delayedClear;
      await page.route('**/api/v1/assistant/sessions/' + timeoutId + '/messages', async route => {
        const response = await context.request.fetch(route.request()); assert.equal(response.status(), 200);
        delayedClear = { route, response };
      });
      page.once('dialog', dialog => dialog.accept()); await page.getByTitle('清空消息', { exact: true }).click();
      await until(() => Boolean(delayedClear));
      assert.equal((await api(context, token, '/auth/logout-all', 'POST', {})).status, 200);
      const replacement = await context.newPage(); await replacement.goto(base + '/login');
      await replacement.getByLabel('账号').fill('zhangwei'); await replacement.getByLabel('密码').fill('OpsPilot@2026');
      await replacement.getByRole('button', { name: '进入控制台' }).click(); await replacement.waitForURL(base + '/');
      const replacementToken = await replacement.evaluate(() => localStorage.getItem('opspilot_token'));
      const received = page.waitForResponse(r => r.request().method() === 'DELETE' && r.url().endsWith('/messages'));
      await delayedClear.route.fulfill({ response: delayedClear.response }); await (await received).finished();
      await page.getByText('登录会话已切换，请刷新页面重新载入。原请求仍按本人账号隔离保留。', { exact: true }).waitFor();
      assert.equal(await page.locator('.assistant-message').count(), 0);
      assert.equal(await page.evaluate(() => localStorage.getItem('opspilot_token')), replacementToken);
      assert.equal((await api(context, replacementToken, '/auth/me')).status, 200);
      result.cases.push({ name: 'late-successful-clear-after-account-change', actualOldClearStatus: 200,
        actualOldLogoutStatus: 200, replacementTokenPreserved: true, replacementServerStatus: 200,
        oldPrivateMessagesNotReintroduced: true });
    }
    assert.equal(result.pageErrors.length, 0);
    result.unexpectedConsoleErrors = result.consoleErrors.filter(x => !/Failed to load resource: net::ERR_(FAILED|ABORTED)|Failed to load resource: the server responded with a status of (409|404|503)/.test(x));
    assert.equal(result.unexpectedConsoleErrors.length, 0);
    result.providerTransportCalls = calls;
    result.streamResponseStatuses = responseEvidence;
    result.status = baseline ? 'BASELINE_CAPTURED' : 'PASS';
  } catch (error) {
    result.status = 'FAIL'; result.failure = redact(error.message);
    for(const page of browser?.contexts().flatMap(context=>context.pages())??[])try{
      result.failurePageText=redact(await page.locator('body').innerText());
      await page.screenshot({path:path.join(evidence,'failure.png')});
    }catch(diagnostic){result.failureDiagnosticError=redact(diagnostic.message)}
    throw error;
  }
  finally {
    process.off('SIGINT', interrupt); process.off('SIGTERM', interrupt); release();
    try {
      const cleanupErrors = [];
      try { if (browser) await browser.close(); } catch (error) { cleanupErrors.push(redact(error.message)); }
      try { if (child) { await stopProcess(child); result.ownedProcessStopped = true; } } catch (error) { cleanupErrors.push(redact(error.message)); }
      try { provider.closeAllConnections(); await new Promise(resolve => provider.close(resolve)); result.ownedProviderStopped = true; }
      catch (error) { cleanupErrors.push(redact(error.message)); }
      if (cleanupErrors.length) { result.status = 'FAIL'; result.cleanupErrors = cleanupErrors; }
    } finally {
      if (descriptor !== undefined) fs.closeSync(descriptor);
      if (fs.existsSync(log)) {
        const content = redact(fs.readFileSync(log, 'utf8')); fs.writeFileSync(log, content);
        result.unexpectedJarErrors = unexpectedLogLines(content);
        if (result.unexpectedJarErrors) {
          result.status = 'FAIL'; result.jarFailure = 'Owned JAR reported unexpected errors including shutdown';
          result.failure ??= result.jarFailure; // Preserve the first interaction failure as well as the shutdown/log failure.
        }
      }
      fs.writeFileSync(path.join(evidence, 'result.json'), JSON.stringify(result, null, 2));
      console.log(JSON.stringify({ status: result.status, evidenceDirectory: path.relative(root, evidence), cases: result.cases }));
      if (result.status === 'FAIL') process.exitCode = 1;
    }
  }
}
if (require.main === module) verify().catch(error => { console.error(redact(error.message)); process.exitCode = 1; });

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const http = require('node:http');
const { spawn } = require('node:child_process');
const { randomUUID, createHash } = require('node:crypto');
const { setTimeout: delay } = require('node:timers/promises');
const { requireFreePort, stopProcess, waitForHealth, redact, unexpectedLogLines } = require('./verify-oncall-browser-ci.cjs');

async function verify() {
  const baseline = process.env.OPSPILOT_ASSISTANT_NATIVE_BASELINE === '1';
  if (baseline && process.env.CI) throw new Error('Baseline capture cannot replace native assistant UI acceptance');
  const root = path.resolve(__dirname, '..');
  const jar = path.join(root, baseline ? 'target/cp75-before/opspilot-cp74.jar' : 'target/opspilot-0.1.0-SNAPSHOT.jar');
  assert.ok(fs.existsSync(jar)); await requireFreePort(9955); await requireFreePort(9956);
  const parent = path.join(root, 'target/assistant-native-ui-it'); fs.mkdirSync(parent, { recursive: true });
  const evidence = fs.mkdtempSync(path.join(parent, 'run-'));
  const { chromium } = require(process.env.OPSPILOT_PLAYWRIGHT_MODULE || path.join(root, 'scripts/browser/node_modules/playwright'));
  const base = 'http://127.0.0.1:9955', FIRST = '  已知事实：受控原生片段🙂\n', LAST = '下一步：核对指标与变更。  ';
  const controls = new Map(), posts = [], cancellations = [];
  const result = { status: 'RUNNING', baselineCapture: baseline, url: base + '/assistant',
    browserReason: 'Browser plugin not available; existing Playwright', viewports: ['1440x1000', '390x844'],
    flow: 'send -> native preview before model completion -> committed answer or explicit terminal',
    fixture: 'controlled real HTTP DashScope provider; not model quality or production capacity',
    jarSha256: createHash('sha256').update(fs.readFileSync(jar)).digest('hex'),
    tokensPersistedToEvidence: false, userFileDatabaseModified: false, cases: [], screenshots: [], pageErrors: [], consoleErrors: [] };
  let child, browser, descriptor;
  const completion = (content, finish_reason) => JSON.stringify({ request_id: randomUUID(), output: { choices: [
    { finish_reason, message: { role: 'assistant', content } }
  ] }, usage: { input_tokens: 1, output_tokens: 1, total_tokens: 2 } });
  function finish(control) {
    control.gated = false;
    const response = control.response;
    if (!response || response.destroyed || response.writableEnded) return;
    if (control.native) response.end(control.truncated ? '' : 'data:' + completion(LAST, 'stop') + '\n\n');
    else { response.writeHead(200, { 'Content-Type': 'application/json' }); response.end(completion(FIRST + LAST, 'stop')); }
  }
  const provider = http.createServer((request, response) => {
    if (request.method !== 'POST' || request.url !== '/api/v1/services/aigc/text-generation/generation') { response.writeHead(404); response.end(); return; }
    let raw = ''; request.on('data', chunk => { raw += chunk; });
    request.on('end', () => {
      const body = JSON.parse(raw), currentQuestion = body.input?.messages?.at(-1)?.content;
      const control = [...controls.values()].find(item => typeof currentQuestion === 'string' && currentQuestion.endsWith(item.question));
      if (!control) { response.writeHead(400); response.end(); return; }
      control.calls++; control.response = response; control.native = request.headers['x-dashscope-sse'] === 'enable';
      control.incremental = body.parameters?.incremental_output === true;
      response.once('close', () => { control.transportClosed = true; });
      if (control.native) {
        response.writeHead(200, { 'Content-Type': 'text/event-stream' });
        response.write('data:' + completion(FIRST, 'null') + '\n\n');
      }
      control.entered = true;
      if (!control.gated) finish(control);
    });
  });
  await new Promise((resolve, reject) => { provider.once('error', reject); provider.listen(0, '127.0.0.1', resolve); });
  const log = path.join(evidence, 'jar.log');
  const interrupt = () => { if (child && child.exitCode === null && child.signalCode === null) child.kill('SIGTERM'); };
  process.once('SIGINT', interrupt); process.once('SIGTERM', interrupt);
  async function until(predicate, budget = 5000) {
    const deadline = Date.now() + budget;
    do { if (await predicate()) return; await delay(30); } while (Date.now() < deadline);
    assert.fail('Native browser/HTTP condition did not settle');
  }
  try {
    descriptor = fs.openSync(log, 'w');
    const java = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java';
    const providerUrl = 'http://127.0.0.1:' + provider.address().port;
    child = spawn(java, ['-Duser.timezone=UTC', '-jar', jar,
      '--server.address=127.0.0.1', '--server.port=9955', '--management.server.address=127.0.0.1', '--management.server.port=9956',
      '--spring.datasource.url=jdbc:h2:mem:native_ui_' + randomUUID() + ';MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1',
      '--spring.datasource.username=sa', '--spring.datasource.password=', '--spring.h2.console.enabled=false',
      '--opspilot.ai.enabled=true', '--spring.ai.dashscope.api-key=cp75-controlled-provider',
      '--spring.ai.dashscope.base-url=' + providerUrl, '--spring.ai.dashscope.chat.base-url=' + providerUrl,
      '--spring.ai.dashscope.read-timeout=30000', '--opspilot.assistant.workers=1', '--opspilot.assistant.queue-capacity=1',
      '--opspilot.assistant.execution-timeout=12s', '--opspilot.assistant.authorization-check-delay=100',
      '--opspilot.agent.recovery.enabled=false', '--opspilot.oncall.rotation.enabled=false', '--opspilot.oncall.escalation.enabled=false'],
      { cwd: root, stdio: ['ignore', descriptor, descriptor], windowsHide: true });
    child.once('error', error => { child.launchError = error; }); result.startedPid = child.pid;
    await waitForHealth('http://127.0.0.1:9956/actuator/health', child);
    browser = await chromium.launch({ headless: true, ...(process.env.OPSPILOT_CHROME_PATH ? { executablePath: process.env.OPSPILOT_CHROME_PATH } : {}) });
    const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, timezoneId: 'Asia/Shanghai' });
    const page = await context.newPage();
    page.on('pageerror', error => result.pageErrors.push(redact(error.message)));
    page.on('console', message => { if (message.type() === 'error') result.consoleErrors.push(redact(message.text())); });
    page.on('request', request => {
      if (request.method() === 'POST' && /\/assistant\/sessions\/\d+\/stream$/.test(request.url()))
        posts.push({ key: request.headers()['idempotency-key'], question: request.postDataJSON().content });
      if (request.method() === 'POST' && request.url().endsWith('/request/cancel')) cancellations.push(request.headers()['idempotency-key']);
    });
    await page.goto(base + '/login'); await page.getByLabel('账号').fill('admin'); await page.getByLabel('密码').fill('OpsPilot@2026');
    await page.getByRole('button', { name: '进入控制台' }).click(); await page.waitForURL(base + '/');
    const token = await page.evaluate(() => localStorage.getItem('opspilot_token'));
    async function api(route, method = 'GET', body, key) {
      const response = await context.request.fetch(base + '/api/v1' + route, { method, timeout: 10000,
        headers: { Authorization: 'Bearer ' + token, ...(key ? { 'Idempotency-Key': key } : {}) }, ...(body ? { data: body } : {}) });
      assert.equal(response.status(), 200); return (await response.json()).data;
    }
    const messages = async id => (await api('/assistant/sessions/' + id)).messages;
    const state = (id, key) => api('/assistant/sessions/' + id + '/request', 'GET', undefined, key);
    async function fresh() {
      const id = (await api('/assistant/sessions', 'POST', {})).session.id;
      await page.goto(base + '/assistant?session=' + id); await page.locator('.assistant-loading').waitFor({ state: 'hidden' }); return id;
    }
    async function send(name, gated = true) {
      const control = { question: 'cp75-' + name + '-' + randomUUID(), gated, calls: 0 }; controls.set(control.question, control);
      await page.locator('.assistant-composer textarea').fill(control.question); await page.getByTitle('发送消息', { exact: true }).click();
      await until(() => control.entered); return control;
    }
    const preview = async () => {
      await page.getByText('模型实时生成 · 片段尚未保存', { exact: true }).waitFor();
      await page.locator('.assistant-message-list').getByText(FIRST.trim(), { exact: true }).waitFor();
      assert.equal(await page.locator('.assistant-message.assistant .assistant-copy').count(), 0);
    };
    async function snapshot(name) {
      assert.match(page.url(), /\/assistant/); assert.equal(await page.title(), 'OpsPilot 智能运维平台');
      assert.ok((await page.locator('body').innerText()).length > 100); assert.equal(await page.locator('vite-error-overlay').count(), 0);
      await page.waitForFunction(() => document.documentElement.scrollWidth <= innerWidth);
      const file = path.join(evidence, name + '.png'); await page.screenshot({ path: file, animations: 'disabled' }); result.screenshots.push(path.basename(file));
    }
    const id = await fresh(), control = await send('first-preview');
    assert.equal((await messages(id)).filter(item => item.role === 'ASSISTANT').length, 0);
    if (baseline) {
      assert.equal(control.native, false); assert.equal(await page.locator('.assistant-preview-label').count(), 0);
      assert.equal(await page.locator('.assistant-message-list').getByText(FIRST.trim(), { exact: true }).count(), 0);
    } else {
      assert.equal(control.native, true); assert.equal(control.incremental, true); await preview();
      assert.equal((await state(id, posts.at(-1).key)).status, 'RUNNING');
    }
    await snapshot(baseline ? 'old-waiting-desktop' : 'native-preview-desktop');
    await page.setViewportSize({ width: 390, height: 844 }); await snapshot(baseline ? 'old-waiting-mobile' : 'native-preview-mobile');
    assert.equal(control.gated, true); finish(control);
    await page.getByText('回答已完成', { exact: true }).waitFor();
    assert.equal(await page.locator('.assistant-preview-label').count(), 0);
    assert.equal((await messages(id)).find(item => item.role === 'ASSISTANT').content, baseline ? (FIRST + LAST).trim() : FIRST + LAST);
    assert.equal(await page.locator('.assistant-message.assistant .assistant-copy').count(), 1);
    await snapshot(baseline ? 'old-completed-mobile' : 'native-completed-mobile');
    result.cases.push({ name: 'preview-before-final-commit', native: control.native, incremental: control.incremental,
      previewVisibleBeforeProviderRelease: !baseline, databaseAnswersBeforeRelease: 0, finalAnswers: 1, modelCalls: control.calls });
    if (!baseline) {
      const cancelledId = await fresh(), cancelled = await send('cancel-preview'); await preview();
      const originalKey = posts.at(-1).key;
      await page.getByTitle('取消原请求', { exact: true }).click(); await page.getByText('回答已取消', { exact: true }).waitFor();
      assert.equal((await state(cancelledId, originalKey)).status, 'CANCELLED');
      assert.equal((await messages(cancelledId)).filter(item => item.role === 'ASSISTANT').length, 0);
      assert.equal(await page.locator('.assistant-preview-label').count(), 0); assert.equal(await page.locator('.assistant-message.assistant').count(), 0);
      assert.equal(cancellations.at(-1), originalKey); await snapshot('native-cancelled-mobile');
      await page.getByRole('button', { name: '继续提问', exact: true }).click(); await send('fresh-before-old-release', false);
      await page.getByText('回答已完成', { exact: true }).waitFor(); assert.equal(cancelled.gated, true);
      await until(() => cancelled.transportClosed === true);
      assert.equal((await messages(cancelledId)).filter(item => item.role === 'ASSISTANT').length, 1); finish(cancelled);
      result.cases.push({ name: 'explicit-cancel-after-preview', status: 'CANCELLED', cancelledAnswers: 0, originalKeyUsed: true,
        workerReusedBeforeOldProviderRelease: true, nativeHttpConnectionClosedBeforeProviderRelease: true });

      const failedId = await fresh(), failed = await send('truncated-preview'); await preview(); failed.truncated = true; finish(failed);
      await page.getByText('结果待确认', { exact: true }).waitFor();
      assert.equal((await state(failedId, posts.at(-1).key)).status, 'FAILED');
      await page.getByRole('button', { name: '查询原请求', exact: true }).click(); await page.getByText('原请求已停止', { exact: true }).waitFor();
      assert.equal((await messages(failedId)).filter(item => item.role === 'ASSISTANT').length, 0);
      assert.equal(await page.locator('.assistant-message.assistant').count(), 0); await snapshot('native-failed-mobile');
      result.cases.push({ name: 'truncated-preview', status: 'FAILED', partialAnswersCommitted: 0, modelCalls: failed.calls });

      const timeoutId = await fresh(), timed = await send('idle-timeout'); await preview();
      await page.getByText('结果待确认', { exact: true }).waitFor({ timeout: 16000 });
      assert.equal((await state(timeoutId, posts.at(-1).key)).status, 'TIMED_OUT');
      await page.getByRole('button', { name: '查询原请求', exact: true }).click(); await page.getByText('原请求已超时', { exact: true }).waitFor();
      assert.equal((await messages(timeoutId)).filter(item => item.role === 'ASSISTANT').length, 0); assert.equal(timed.gated, true);
      await until(() => timed.transportClosed === true);
      await snapshot('native-timeout-mobile'); finish(timed);
      result.cases.push({ name: 'idle-after-preview', status: 'TIMED_OUT', executionBudgetSeconds: 12,
        partialAnswersCommitted: 0, nativeHttpConnectionClosedBeforeProviderRelease: true });

      await page.setViewportSize({ width: 1440, height: 1000 });
      const recoveredId = await fresh(), recovered = await send('disconnect-preview'); await preview(); const beforePosts = posts.length;
      await page.reload(); await page.getByText('结果待确认', { exact: true }).waitFor();
      assert.equal((await state(recoveredId, posts.at(-1).key)).status, 'RUNNING'); finish(recovered);
      await until(async () => (await state(recoveredId, posts.at(-1).key)).status === 'COMPLETED');
      await page.getByRole('button', { name: '查询原请求', exact: true }).click(); await page.getByText('回答已完成', { exact: true }).waitFor();
      assert.equal(posts.length, beforePosts); assert.equal(recovered.calls, 1);
      assert.equal((await state(recoveredId, posts.at(-1).key)).answerMessageId, (await messages(recoveredId)).find(item => item.role === 'ASSISTANT').id);
      await snapshot('native-recovered-desktop');
      result.cases.push({ name: 'disconnect-after-preview-manual-query', status: 'COMPLETED', originalAnswerIdPreserved: true, streamPosts: 1, modelCalls: 1 });
    }
    assert.equal(result.pageErrors.length, 0); assert.equal(result.consoleErrors.length, 0);
    result.providerRequests = [...controls.values()].map(({ question, calls, native, incremental, transportClosed }) => ({ question, calls, native, incremental, transportClosed }));
    result.streamPosts = posts.length; result.explicitCancelPosts = cancellations.length;
    result.status = baseline ? 'BASELINE_CAPTURED' : 'PASS';
  } catch (error) { result.status = 'FAIL'; result.failure = redact(error.message); result.failureStack = redact(error.stack ?? ''); throw error; }
  finally {
    result.providerRequests = [...controls.values()].map(({ question, calls, native, incremental, transportClosed }) => ({ question, calls, native, incremental, transportClosed }));
    result.streamPosts = posts.length; result.explicitCancelPosts = cancellations.length;
    process.off('SIGINT', interrupt); process.off('SIGTERM', interrupt); controls.forEach(finish);
    const cleanupErrors = [];
    try { if (browser) await browser.close(); } catch (error) { cleanupErrors.push(redact(error.message)); }
    try { if (child) { await stopProcess(child); result.ownedProcessStopped = true; } } catch (error) { cleanupErrors.push(redact(error.message)); }
    try { provider.closeAllConnections(); await new Promise(resolve => provider.close(resolve)); result.ownedProviderStopped = true; }
    catch (error) { cleanupErrors.push(redact(error.message)); }
    if (descriptor !== undefined) fs.closeSync(descriptor);
    if (fs.existsSync(log)) {
      const content = redact(fs.readFileSync(log, 'utf8')); fs.writeFileSync(log, content); result.unexpectedJarErrors = unexpectedLogLines(content);
      if (result.unexpectedJarErrors) { result.status = 'FAIL'; result.jarFailure = 'Owned JAR reported unexpected errors including shutdown'; }
    }
    if (cleanupErrors.length) { result.status = 'FAIL'; result.cleanupErrors = cleanupErrors; }
    fs.writeFileSync(path.join(evidence, 'result.json'), JSON.stringify(result, null, 2));
    console.log(JSON.stringify({ status: result.status, evidenceDirectory: path.relative(root, evidence), cases: result.cases }));
    if (result.status === 'FAIL') process.exitCode = 1;
  }
}
if (require.main === module) verify().catch(error => { console.error(redact(error.message)); process.exitCode = 1; });

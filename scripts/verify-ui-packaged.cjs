// Isolated, read-only UI release acceptance. Never copies or overwrites a demo JAR.
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const net = require('node:net');
const crypto = require('node:crypto');
const assert = require('node:assert/strict');
const { spawn } = require('node:child_process');
const root = path.resolve(__dirname, '..');
const sha = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));

function assetFiles(html) {
  const files = new Set(['index.html', 'favicon.svg']);
  for (const match of html.matchAll(/(?:src|href)=["'](\/[^"']+)["']/g)) {
    const file = match[1].slice(1);
    assert.match(file, /^(?:assets\/[A-Za-z0-9._-]+|favicon\.svg)$/, 'Unexpected static asset path');
    files.add(file);
  }
  assert.ok([...files].some(file => /^assets\/index-.*\.js$/.test(file)), 'Missing bundled JS');
  assert.ok([...files].some(file => /^assets\/index-.*\.css$/.test(file)), 'Missing bundled CSS');
  assert.ok(files.has('assets/theme.js'), 'Missing prepaint theme bootstrap');
  return [...files];
}

function javaArgs(jar, out, port) {
  assert.ok(Number.isInteger(port) && port >= 1024 && port < 65535, 'Invalid isolated port');
  assert.ok(path.isAbsolute(out), 'Temporary database directory must be absolute');
  return ['-jar', jar, '--server.address=127.0.0.1', `--server.port=${port}`,
    '--management.server.address=127.0.0.1', `--management.server.port=${port + 1}`,
    '--management.endpoint.shutdown.enabled=true', '--management.endpoints.web.exposure.include=health,shutdown',
    `--spring.datasource.url=jdbc:h2:file:${out.replaceAll('\\', '/')}/demo;MODE=MySQL;DATABASE_TO_LOWER=TRUE;WRITE_DELAY=0`,
    '--spring.datasource.username=sa', '--spring.datasource.password=', '--spring.datasource.driver-class-name=org.h2.Driver',
    '--spring.h2.console.enabled=false', '--opspilot.ai.enabled=false', '--opspilot.agent.events.outbox-enabled=false',
    '--opspilot.agent.recovery.enabled=false', '--opspilot.oncall.rotation.enabled=false',
    '--opspilot.oncall.escalation.enabled=false', '--opspilot.oncall.open.notification.enabled=false',
    '--opspilot.oncall.swap.notification.enabled=false', '--opspilot.alertmanager.rejections.auto-replay-enabled=false',
    '--opspilot.runbook.semantic.enabled=false', '--spring.ai.dashscope.embedding.enabled=false', '--management.tracing.enabled=false'];
}

async function free(port) {
  const server = net.createServer();
  await new Promise((resolve, reject) => server.once('error', reject).listen(port, '127.0.0.1', resolve));
  await new Promise(resolve => server.close(resolve));
}
async function request(url, options = {}) {
  return fetch(url, { ...options, signal: AbortSignal.timeout(5000) });
}
function exitOf(child) {
  // Keep spawn errors as data so cleanup can still run and write an honest report.
  return new Promise(resolve => {
    child.once('error', error => resolve({ code: null, error: error.message }));
    child.once('close', (code, signal) => resolve({ code, signal }));
  });
}

async function run() {
  assert.ok(process.argv.length === 2 || (process.argv.length === 4 && process.argv[2] === '--jar'), 'Usage: node scripts/verify-ui-packaged.cjs [--jar path]');
  const jar = path.resolve(root, process.argv[3] || 'target/opspilot-0.1.0-SNAPSHOT.jar');
  const port = Number(process.env.OPSPILOT_UI_QA_PORT || '9913');
  const out = fs.mkdtempSync(path.join(os.tmpdir(), 'opspilot-ui-package-'));
  const args = javaArgs(jar, out, port);
  const base = `http://127.0.0.1:${port}`, management = `http://127.0.0.1:${port + 1}`;
  const report = { status: 'RUNNING', base, out, jar, assets: [], userDatabaseModified: false, shutdown: false };
  let child, childExit, qa, qaExit, fd, token;
  try {
    report.jarSha256 = sha(fs.readFileSync(jar));
    const staticRoot = path.join(root, 'src/main/resources/static');
    const files = assetFiles(fs.readFileSync(path.join(staticRoot, 'index.html'), 'utf8'));
    // Fail without starting anything if another process owns either port.
    await free(port); await free(port + 1);
    fd = fs.openSync(path.join(out, 'backend.log'), 'w');
    const java = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java';
    child = spawn(java, args, { cwd: out, env: { ...process.env, JWT_SECRET: crypto.randomBytes(48).toString('hex') }, stdio: ['ignore', fd, fd], windowsHide: true });
    childExit = exitOf(child);
    const readyBy = Date.now() + 60000;
    while (true) {
      const exited = await Promise.race([childExit, delay(0).then(() => null)]);
      assert.equal(exited, null, 'JAR exited before readiness; see backend.log');
      try {
        const health = await request(management + '/actuator/health');
        if (health.ok && (await health.json()).status === 'UP') break;
      } catch {}
      assert.ok(Date.now() < readyBy, 'JAR readiness deadline exceeded');
      await delay(500);
    }
    const login = await request(base + '/api/v1/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username: 'admin', password: 'OpsPilot@2026' }) });
    assert.equal(login.status, 200);
    token = (await login.json()).data.accessToken;
    assert.ok(token, 'No shutdown authentication token');
    for (const file of files) {
      const response = await request(base + '/' + (file === 'index.html' ? '' : file));
      assert.equal(response.status, 200, file);
      const hash = sha(Buffer.from(await response.arrayBuffer()));
      assert.equal(hash, sha(fs.readFileSync(path.join(staticRoot, file))), 'JAR differs from current static release: ' + file);
      report.assets.push({ file, sha256: hash });
    }
    qa = spawn(process.execPath, [path.join(root, 'scripts/browser/verify-ui-themes.cjs')], {
      cwd: root, env: { ...process.env, OPSPILOT_UI_QA_ISOLATED: '1', OPSPILOT_THEME_QA_BASE: base, OPSPILOT_THEME_QA_OUTPUT_PARENT: out },
      stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true
    });
    qaExit = exitOf(qa);
    let output = '';
    qa.stdout.on('data', bytes => { output += bytes; process.stdout.write(bytes); });
    qa.stderr.on('data', bytes => process.stderr.write(bytes));
    let deadline;
    const result = await Promise.race([qaExit, new Promise(resolve => { deadline = setTimeout(() => resolve({ error: 'Browser deadline exceeded' }), 180000); })]);
    clearTimeout(deadline);
    assert.equal(result.error, undefined); assert.equal(result.code, 0, 'Packaged browser QA failed');
    report.browser = JSON.parse(output.trim());
    assert.equal(report.browser.status, 'PASS');
    assert.equal(report.browser.routes, 44); assert.equal(report.browser.contrasts, 32); assert.equal(report.browser.interactions.length, 10);
    report.status = 'PASS';
  } catch (error) {
    report.status = 'FAIL'; report.failure = error.stack;
  } finally {
    if (qa && qa.exitCode === null && qa.signalCode === null) { qa.kill(); await qaExit; }
    if (child) {
      try {
        if (token && child.exitCode === null && child.signalCode === null) {
          report.shutdownStatus = (await request(management + '/actuator/shutdown', { method: 'POST', headers: { Authorization: 'Bearer ' + token } })).status;
          const result = await Promise.race([childExit, delay(10000).then(() => null)]);
          report.shutdown = report.shutdownStatus === 200 && result?.code === 0;
        }
      } catch (error) { report.cleanupFailure = error.message; }
      if (child.exitCode === null && child.signalCode === null) { child.kill(); await childExit; }
      if (!report.shutdown) report.status = 'FAIL';
    }
    if (fd !== undefined) fs.closeSync(fd);
    // A pre-existing occupied port is not ours to stop, and must never be reported free.
    try { await free(port); await free(port + 1); report.portsFree = true; }
    catch { report.portsFree = false; report.status = 'FAIL'; }
    if (report.jarSha256 && sha(fs.readFileSync(jar)) !== report.jarSha256) { report.status = 'FAIL'; report.jarModified = true; }
    fs.writeFileSync(path.join(out, 'package.json'), JSON.stringify(report, null, 2) + '\n');
  }
  console.log(JSON.stringify(report));
  if (report.status !== 'PASS') process.exitCode = 1;
  return report;
}
module.exports = { assetFiles, javaArgs, free };
if (require.main === module) run().catch(error => { console.error(error.message); process.exitCode = 1; });

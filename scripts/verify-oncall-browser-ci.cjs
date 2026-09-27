const fs = require('node:fs');
const path = require('node:path');
const net = require('node:net');
const { randomUUID } = require('node:crypto');
const { spawn } = require('node:child_process');
const { setTimeout: delay } = require('node:timers/promises');

const root = path.resolve(__dirname, '..');
const base = 'http://127.0.0.1:9917';
const health = 'http://127.0.0.1:9921/actuator/health';
const redact = value => value.replace(/Bearer\s+[\w.-]+/g, 'Bearer [REDACTED]');
const unexpectedLogLines = value => value.split(/\r?\n/).filter(line => /Unhandled request error|\sERROR\s/.test(line)).length;

async function requireFreePort(port) {
  const probe = net.createServer();
  await new Promise((resolve, reject) => {
    probe.once('error', () => reject(new Error(`Acceptance port ${port} is occupied; no existing process will be stopped`)));
    probe.listen({ host: '127.0.0.1', port, exclusive: true }, resolve);
  });
  await new Promise((resolve, reject) => probe.close(error => error ? reject(error) : resolve()));
}
async function stopProcess(child) {
  if (child.exitCode !== null || child.signalCode !== null) return;
  const exited = new Promise(resolve => child.once('exit', resolve));
  child.kill('SIGTERM');
  if (await Promise.race([exited.then(() => true), delay(5000, null, { ref: false }).then(() => false)])) return;
  child.kill('SIGKILL'); // Only the child launched by this runner, never a PID discovered from a port.
  await Promise.race([exited, delay(2000, null, { ref: false })]);
  if (child.exitCode === null && child.signalCode === null) throw new Error('Owned child did not stop');
}
async function waitForHealth(url, child, timeoutMs = 60000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (child.exitCode !== null || child.signalCode !== null || child.launchError) throw new Error('Owned JAR exited before readiness');
    try {
      const response = await fetch(url, { signal: AbortSignal.timeout(1500) });
      if (response.ok && (await response.json()).status === 'UP') return;
    } catch { /* Startup is bounded; errors are retained in the owned JAR log. */ }
    await delay(200);
  }
  throw new Error('Owned JAR health did not become UP before deadline');
}
async function runScript(name, env, evidence) {
  const log = path.join(evidence, name + '.log');
  const descriptor = fs.openSync(log, 'w');
  const child = spawn(process.execPath, [path.join(__dirname, name)], { cwd: root, env,
    stdio: ['ignore', descriptor, descriptor], windowsHide: true });
  let launchError;
  child.once('error', error => { launchError = error; });
  const finished = new Promise(resolve => child.once('close', (code, signal) => resolve({ code, signal })));
  const interrupted = () => child.kill('SIGTERM');
  process.once('SIGINT', interrupted);
  process.once('SIGTERM', interrupted);
  let outcome;
  try {
    outcome = await Promise.race([finished, delay(180000, null, { ref: false }).then(() => null)]);
    if (!outcome) { await stopProcess(child); throw new Error(`${name} exceeded 180 second budget`); }
    if (launchError || outcome.code !== 0) throw new Error(`${name} failed; see its sanitized evidence log`);
    return { name, exitCode: outcome.code };
  } finally {
    process.off('SIGINT', interrupted);
    process.off('SIGTERM', interrupted);
    try { if (child.pid) await stopProcess(child); }
    finally {
      fs.closeSync(descriptor);
      fs.writeFileSync(log, redact(fs.readFileSync(log, 'utf8')));
    }
  }
}
async function verify() {
  if (process.env.OPSPILOT_TREND_BASELINE === '1' && process.env.CI) throw new Error('Trend baseline capture must not replace CI acceptance');
  if (process.env.OPSPILOT_REVOCATION_BASELINE === '1' && process.env.CI) throw new Error('Baseline capture must not replace CI acceptance');
  const jar = process.env.OPSPILOT_TREND_BASELINE === '1'
    ? path.join(root, 'target', 'cp55-before', 'opspilot-cp54.jar')
    : path.join(root, 'target', 'opspilot-0.1.0-SNAPSHOT.jar');
  if (!fs.existsSync(jar)) throw new Error('Build the fresh frontend and JAR before browser acceptance');
  await requireFreePort(9917);
  await requireFreePort(9921);
  const parent = path.join(root, 'target', 'oncall-browser-it');
  fs.mkdirSync(parent, { recursive: true });
  const evidence = fs.mkdtempSync(path.join(parent, 'run-'));
  const modulePath = process.env.OPSPILOT_PLAYWRIGHT_MODULE || path.join(__dirname, 'browser', 'node_modules', 'playwright');
  const database = 'browser_' + randomUUID().replaceAll('-', '');
  const log = path.join(evidence, 'jar.log');
  const descriptor = fs.openSync(log, 'w');
  const java = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java';
  const child = spawn(java, ['-Duser.timezone=UTC', '-jar', jar, '--server.address=127.0.0.1', '--server.port=9917',
    '--management.server.port=9921', '--management.server.address=127.0.0.1',
    `--spring.datasource.url=jdbc:h2:mem:${database};MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE`,
    '--spring.datasource.username=sa', '--spring.datasource.password=', '--opspilot.ai.enabled=false',
    '--opspilot.oncall.rotation.scan-delay=200', '--opspilot.oncall.rotation.initial-delay=500',
    '--opspilot.oncall.escalation.enabled=false'], { cwd: root, stdio: ['ignore', descriptor, descriptor], windowsHide: true });
  child.once('error', error => { child.launchError = error; });
  let interrupted = false;
  const interrupt = () => { interrupted = true; child.kill('SIGTERM'); };
  process.once('SIGINT', interrupt);
  process.once('SIGTERM', interrupt);
  const result = { status: 'RUNNING', evidenceDirectory: path.relative(root, evidence), database: 'isolated unique H2 memory',
    url: base + '/on-call', jarTimezone: 'UTC', scanDelayMs: 200, defaultScanDelayMs: 60000, scripts: [],
    browserCi: Boolean(process.env.CI), trendBaselineCapture: process.env.OPSPILOT_TREND_BASELINE === '1', revocationBaselineCapture: process.env.OPSPILOT_REVOCATION_BASELINE === '1', userFileDatabaseModified: false, originalProcessesNotStopped: true, ownedProcessStopped: false };
  try {
    await waitForHealth(health, child);
    const env = { ...process.env, OPSPILOT_BASE_URL: base, OPSPILOT_EVIDENCE_DIR: evidence,
      OPSPILOT_ACCEPTANCE_ISOLATED: '1', OPSPILOT_PLAYWRIGHT_MODULE: modulePath };
    for (const name of ['verify-oncall-rotation-ui.cjs', 'verify-oncall-rotation-boundaries.cjs', 'verify-oncall-coverage-ui.cjs', 'verify-oncall-handoff-http.cjs', 'verify-oncall-handoff-ui.cjs', 'verify-oncall-handoff-revocation-http.cjs', 'verify-oncall-handoff-revocation-ui.cjs', 'verify-runbook-trend-ui.cjs']) {
      if (interrupted) throw new Error('Browser acceptance interrupted');
      result.scripts.push(await runScript(name, env, evidence));
    }
    if (interrupted) throw new Error('Browser acceptance interrupted');
    if (unexpectedLogLines(fs.readFileSync(log, 'utf8'))) throw new Error('Owned JAR reported unexpected errors; see sanitized evidence');
    result.unexpectedJarErrors = 0;
    result.status = 'PASS';
  } catch (error) { result.status = 'FAIL'; result.failure = redact(error.message); throw error; }
  finally {
    process.off('SIGINT', interrupt);
    process.off('SIGTERM', interrupt);
    try { if (child.pid) await stopProcess(child); result.ownedProcessStopped = true; }
    catch (error) { result.status = 'FAIL'; result.failure = redact(error.message); throw error; }
    finally {
      fs.closeSync(descriptor);
      const content = redact(fs.readFileSync(log, 'utf8'));
      fs.writeFileSync(log, content);
      result.unexpectedJarErrors = unexpectedLogLines(content); // Include graceful shutdown, not just the running JAR.
      const shutdownError = result.status === 'PASS' && result.unexpectedJarErrors > 0;
      if (shutdownError) { result.status = 'FAIL'; result.failure = 'Owned JAR reported errors during shutdown; see sanitized evidence'; }
      fs.writeFileSync(path.join(evidence, 'runner-result.json'), JSON.stringify(result, null, 2));
      console.log(JSON.stringify(result));
      if (shutdownError) throw new Error(result.failure);
    }
  }
}
module.exports = { requireFreePort, stopProcess, waitForHealth, redact, unexpectedLogLines };
if (require.main === module) verify().catch(error => { console.error(redact(error.message)); process.exitCode = 1; });

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { spawn } = require('node:child_process');
const { requireFreePort, stopProcess, waitForHealth, redact, unexpectedLogLines } = require('./verify-oncall-browser-ci.cjs');

async function verify() {
  const baseline = process.env.OPSPILOT_AUTH_SESSION_BASELINE === '1';
  if (baseline && process.env.CI) throw new Error('Baseline capture must never replace session CI acceptance');
  const root = path.resolve(__dirname, '..');
  const jar = path.join(root, 'target', baseline ? 'cp65-before/opspilot-cp64.jar' : 'opspilot-0.1.0-SNAPSHOT.jar');
  assert.ok(fs.existsSync(jar), 'Build or preserve the scoped JAR before acceptance');
  await requireFreePort(9933);
  await requireFreePort(9934);
  const parent = path.join(root, 'target', 'auth-session-it');
  fs.mkdirSync(parent, { recursive: true });
  const evidence = fs.mkdtempSync(path.join(parent, 'run-'));
  const ownedDatabase = path.join(evidence, 'db', 'opspilot').replaceAll('\\', '/');
  fs.mkdirSync(path.join(evidence, 'db'));
  const base = 'http://127.0.0.1:9933/api/v1';
  const java = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java';
  const result = { status:'RUNNING', baselineCapture:baseline, url:base, database:'fresh runner-owned H2 file',
    userFileDatabaseModified:false, originalProcessesNotStopped:true, evidenceDirectory:path.relative(root,evidence),
    startedPids:[], stoppedPids:[], unexpectedJarErrors:0, tokensPersistedToEvidence:false };
  let child, descriptor, generation = 0, interrupted = false;
  const interrupt = () => { interrupted = true; if (child) child.kill('SIGTERM'); };
  process.once('SIGINT', interrupt);
  process.once('SIGTERM', interrupt);
  async function start() {
    if (interrupted) throw new Error('Acceptance interrupted');
    const log = path.join(evidence, 'jar-' + (++generation) + '.log');
    descriptor = fs.openSync(log, 'w');
    child = spawn(java, ['-Duser.timezone=UTC','-jar',jar,
      '--server.address=127.0.0.1','--server.port=9933','--management.server.address=127.0.0.1','--management.server.port=9934',
      '--spring.datasource.url=jdbc:h2:file:' + ownedDatabase + ';MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0',
      '--spring.datasource.username=sa','--spring.datasource.password=','--spring.h2.console.enabled=false',
      '--opspilot.ai.enabled=false','--opspilot.agent.recovery.enabled=false',
      '--opspilot.oncall.rotation.enabled=false','--opspilot.oncall.escalation.enabled=false'],
      {cwd:root, stdio:['ignore',descriptor,descriptor], windowsHide:true});
    child.once('error', error => { child.launchError = error; });
    if (child.pid) result.startedPids.push(child.pid);
    await waitForHealth('http://127.0.0.1:9934/actuator/health',child);
  }
  async function stop() {
    if (!child) return;
    try {
      if (child.pid) { await stopProcess(child); result.stoppedPids.push(child.pid); }
    } finally {
      fs.closeSync(descriptor);
      const log = path.join(evidence,'jar-' + generation + '.log');
      const content = redact(fs.readFileSync(log,'utf8'));
      fs.writeFileSync(log,content);
      result.unexpectedJarErrors += unexpectedLogLines(content);
      child = null;
    }
  }
  const oldPassword = 'OpsPilot@2026', newPassword = 'A new JAR acceptance passphrase';
  async function request(route, token, body) {
    const response = await fetch(base + route, {method:body ? 'POST':'GET',
      headers:{...(token ? {Authorization:'Bearer ' + token}:{}),...(body ? {'Content-Type':'application/json'}:{})},
      body:body ? JSON.stringify(body):undefined, signal:AbortSignal.timeout(8000)});
    return {status:response.status, json:await response.json()};
  }
  async function login(password) {
    const response = await request('/auth/login',null,{username:'admin',password});
    assert.equal(response.status,200,'login');
    return response.json.data.accessToken;
  }
  async function unauthorized(token) {
    for (const response of [await request('/auth/me',token),
      await request('/incidents/1/notes',token,{content:'revoked JAR token must never write'})]) {
      assert.equal(response.status,401);
      assert.equal(response.json.error.code,'AUTHENTICATION_REQUIRED');
    }
  }
  async function audits(token) {
    const response = await request('/audit-logs',token);
    assert.equal(response.status,200);
    return response.json.data.filter(row => row.action.startsWith('AUTH_'));
  }
  try {
    await start();
    const first = await login(oldPassword), second = await login(oldPassword);
    if (!baseline) assert.notEqual(first,second,'Independent issued sessions have distinct server-generated jti');
    const logout = await request('/auth/logout-all',first,{});
    result.logoutStatus = logout.status;
    if (baseline) {
      const change = await request('/auth/password',first,{currentPassword:oldPassword,newPassword});
      result.passwordStatus = change.status;
      result.oldTokenStillReads = (await request('/auth/me',second)).status;
      assert.equal(logout.status,404);
      assert.equal(change.status,404);
      assert.equal(result.oldTokenStillReads,200);
      result.status = 'BASELINE_CAPTURED';
      return;
    }
    assert.equal(logout.status,200);
    assert.equal(logout.json.data.reauthenticationRequired,true);
    await unauthorized(first);
    await unauthorized(second);
    const fresh = await login(oldPassword);
    assert.equal((await request('/auth/me',fresh)).status,200);
    await unauthorized(first);
    const beforePasswordAudit = await audits(fresh);
    assert.equal(beforePasswordAudit.length,1);
    const changed = await request('/auth/password',fresh,{currentPassword:oldPassword,newPassword});
    result.passwordStatus = changed.status;
    assert.equal(changed.status,200);
    assert.equal((await request('/auth/login',null,{username:'admin',password:oldPassword})).status,401);
    await unauthorized(first);
    await unauthorized(second);
    await unauthorized(fresh);
    const current = await login(newPassword);
    const originalAudits = await audits(current);
    assert.deepEqual(originalAudits.map(row => row.action).sort(),['AUTH_PASSWORD_CHANGED','AUTH_SESSIONS_REVOKED']);
    for (const row of originalAudits) {
      assert.equal(row.ipAddress,'127.0.0.1');
      assert.ok(!JSON.stringify(row).includes(oldPassword) && !JSON.stringify(row).includes(newPassword));
    }
    const incident = await request('/incidents/1',current);
    await stop();
    await requireFreePort(9933);
    await requireFreePort(9934);
    await start(); // Different JVM, the exact same newly-owned persistent database.
    assert.equal((await request('/auth/me',current)).status,200);
    for (const token of [first,second,fresh]) await unauthorized(token);
    const afterRestart = await login(newPassword);
    await unauthorized(first);
    assert.equal((await request('/auth/login',null,{username:'admin',password:oldPassword})).status,401);
    assert.deepEqual(await audits(afterRestart),originalAudits);
    assert.deepEqual((await request('/incidents/1',afterRestart)).json.data.timeline,incident.json.data.timeline);
    const claims = JSON.parse(Buffer.from(afterRestart.split('.')[1],'base64url').toString());
    assert.equal(claims.sv,2);
    assert.equal(new Set(result.startedPids).size,2);
    Object.assign(result,{oldTokensRejectedAfterFreshLogin:true, oldTokensRejectedAfterPasswordChange:true,
      oldTokensRejectedAfterJvmRestart:true, currentTokenSurvivesRestart:true, persistedAuthVersion:2,
      unauthorizedBusinessWritesUnchanged:true, auditRows:originalAudits, auditRowsUnchangedAfterRestart:true,status:'PASS'});
  } catch (error) { result.status='FAIL'; result.failure=redact(error.message); throw error; }
  finally {
    process.off('SIGINT',interrupt);
    process.off('SIGTERM',interrupt);
    await stop();
    result.ownedProcessesStopped = result.startedPids.length === result.stoppedPids.length;
    const badLogs = result.unexpectedJarErrors > 0;
    if (badLogs) { result.status='FAIL'; result.failure='Unexpected owned JAR errors, including shutdown'; }
    fs.writeFileSync(path.join(evidence,'result.json'),JSON.stringify(result,null,2));
    console.log(JSON.stringify(result));
    if (badLogs) throw new Error(result.failure);
  }
}
verify().catch(error => { console.error(redact(error.message)); process.exitCode=1; });

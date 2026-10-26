// HTTP writes target only child JVMs with fresh, owned temporary databases. Never uses an existing demo.
const fs = require('node:fs'), path = require('node:path'), os = require('node:os');
const net = require('node:net'), crypto = require('node:crypto'), assert = require('node:assert/strict');
const { spawn } = require('node:child_process');
const { javaArgs, free } = require('./verify-ui-packaged.cjs');
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
const sha = file => crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
const args = process.argv.slice(2);
assert.equal(args.length, 4, 'Usage: node scripts/verify-alert-intake-packaged.cjs --before old.jar --after new.jar');
assert.equal(args[0], '--before'); assert.equal(args[2], '--after');
const jars = { before: path.resolve(args[1]), after: path.resolve(args[3]) };
assert.notEqual(jars.before, jars.after);
for (const jar of Object.values(jars)) assert.ok(fs.statSync(jar).isFile());
const out = fs.mkdtempSync(path.join(os.tmpdir(), 'opspilot-intake-http-'));
const report = { status: 'RUNNING', out, ownedDatabasesOnly: true, cloudCalls: false, runs: [] };

async function portPair() {
  for (let attempt = 0; attempt < 20; attempt++) {
    const server = net.createServer();
    await new Promise((resolve, reject) => server.once('error', reject).listen(0, '127.0.0.1', resolve));
    const port = server.address().port;
    await new Promise(resolve => server.close(resolve));
    if (port < 65534) { try { await free(port + 1); return port; } catch {} }
  }
  throw Error('No available port pair');
}

async function run(kind) {
  const dir = path.join(out, kind); fs.mkdirSync(dir);
  const port = await portPair(), base = `http://127.0.0.1:${port}`, management = `http://127.0.0.1:${port + 1}`;
  const result = { kind, jarSha256: sha(jars[kind]), port, checks: [] }; report.runs.push(result);
  const secret = crypto.randomBytes(32).toString('hex');
  const fd = fs.openSync(path.join(dir, 'backend.log'), 'w');
  const java = path.join(process.env.JAVA_HOME || 'C:/Program Files/Java/jdk-21', 'bin', 'java.exe');
  const child = spawn(java, javaArgs(jars[kind], dir, port), { cwd: dir, windowsHide: true,
    env: { ...process.env, JWT_SECRET: crypto.randomBytes(48).toString('hex'), ALERTMANAGER_WEBHOOK_SECRET: secret },
    stdio: ['ignore', fd, fd] });
  const exited = new Promise(resolve => {
    child.once('error', error => resolve({ error: error.message }));
    child.once('close', (code, signal) => resolve({ code, signal }));
  });
  let admin;
  async function http(route, { method = 'GET', token, authorization, body } = {}, origin = base) {
    const headers = { 'Content-Type': 'application/json' };
    if (token) headers.Authorization = 'Bearer ' + token;
    if (authorization) headers.Authorization = authorization;
    const response = await fetch(origin + route, { method, headers,
      ...(body === undefined ? {} : { body: typeof body === 'string' ? body : JSON.stringify(body) }),
      signal: AbortSignal.timeout(10000) });
    return { status: response.status, body: await response.json() };
  }
  async function login(username) {
    const response = await http('/api/v1/auth/login', { method: 'POST', body: { username, password: 'OpsPilot@2026' } });
    assert.equal(response.status, 200, `fixture login ${username}`); return response.body.data;
  }
  async function snapshot() {
    const responses = await Promise.all(['/api/v1/alerts?size=100', '/api/v1/incidents?size=100', '/api/v1/audit-logs?limit=500']
      .map(route => http(route, { token: admin.accessToken })));
    assert.ok(responses.every(r => r.status === 200));
    return responses.map(r => r.body.data);
  }
  try {
    const deadline = Date.now() + 60000;
    while (true) {
      assert.equal(child.exitCode, null, 'Owned JAR exited before readiness');
      try { const health = await http('/actuator/health', {}, management); if (health.status === 200 && health.body.status === 'UP') break; } catch {}
      assert.ok(Date.now() < deadline, 'Owned JAR readiness timed out'); await delay(400);
    }
    admin = await login('admin');
    const auditor = await login('auditor');
    const source = 'packaged-auth-' + crypto.randomUUID();
    const payload = id => ({ source, externalEventId: id, resourceCode: 'APP-AUTH', severity: 'P3', status: 'FIRING',
      title: 'controlled-intake-' + id, description: 'private-body-sentinel', labels: { private: 'private-label-sentinel' } });
    const baseline = kind === 'after' ? await snapshot() : null;
    const anonymous = await http('/api/v1/alerts/intake', { method: 'POST', body: payload('anonymous') });
    assert.equal(anonymous.status, kind === 'before' ? 200 : 401);
    result.checks.push({ name: 'anonymous-intake', status: anonymous.status });
    if (kind === 'before') { result.status = 'PASS'; return; }
    for (const [name, options, expected] of [
      ['anonymous', { body: payload('denied') }, 401], ['invalid-token', { token: 'invalid', body: payload('denied') }, 401],
      ['auditor', { token: auditor.accessToken, body: payload('denied') }, 403], ['anonymous-malformed', { body: '{' }, 401]
    ]) {
      const response = await http('/api/v1/alerts/intake', { ...options, method: 'POST' });
      assert.equal(response.status, expected, name); result.checks.push({ name, status: response.status });
    }
    assert.deepEqual(await snapshot(), baseline, 'Rejected HTTP calls changed alert/incident/audit API snapshots');
    result.deniedSnapshotsUnchanged = true;
    for (const username of ['admin', 'lina', 'zhangwei']) {
      const account = username === 'admin' ? admin : await login(username), id = crypto.randomUUID();
      const first = await http('/api/v1/alerts/intake', { method: 'POST', token: account.accessToken, body: payload(id) });
      const replay = await http('/api/v1/alerts/intake', { method: 'POST', token: account.accessToken, body: payload(id) });
      assert.equal(first.status, 200); assert.equal(first.body.data.action, 'CREATED');
      assert.equal(replay.status, 200); assert.equal(replay.body.data.action, 'REPLAYED');
      assert.equal(first.body.data.alertId, replay.body.data.alertId);
      const logs = await http('/api/v1/audit-logs?limit=500', { token: admin.accessToken });
      const rows = logs.body.data.filter(row => row.action === 'ALERT_INTAKE' && row.targetId === String(first.body.data.alertId));
      assert.equal(rows.length, 2); assert.ok(rows.every(row => row.actor === account.user.displayName && row.detail.length < 150));
      assert.ok(rows.every(row => !/private-(?:body|label)-sentinel/.test(row.detail)));
      result.checks.push({ name: username + '-write-replay-audit', actions: [first.body.data.action, replay.body.data.action], auditRows: rows.length });
    }
    const start = new Date(Date.now() - 60000).toISOString();
    const webhook = state => ({ version: '4', groupKey: 'auth-acceptance', status: state, receiver: 'opspilot', alerts: [{
      status: state, fingerprint: 'packaged-' + source, startsAt: start, endsAt: new Date().toISOString(),
      labels: { alertname: 'IntakeAuthorization', resource_code: 'APP-AUTH', severity: 'warning' }, annotations: { summary: 'controlled webhook' }
    }] });
    const endpoint = '/api/v1/integrations/alertmanager/webhook';
    for (const options of [{}, { token: admin.accessToken }]) {
      const denied = await http(endpoint, { ...options, method: 'POST', body: webhook('firing') });
      assert.equal(denied.status, 401); assert.equal(denied.body.error.code, 'ALERTMANAGER_WEBHOOK_UNAUTHORIZED');
    }
    const actions = [];
    for (const state of ['firing', 'firing', 'resolved']) {
      const received = await http(endpoint, { method: 'POST', authorization: 'OpsPilot ' + secret, body: webhook(state) });
      assert.equal(received.status, 200); assert.equal(received.body.data.accepted, 1);
      actions.push(received.body.data.items[0].action);
    }
    assert.deepEqual(actions, ['CREATED', 'REPLAYED', 'UPDATED']);
    result.checks.push({ name: 'independent-webhook-secret', userJwtRejected: true, secretOnlyActions: actions });
    result.status = 'PASS';
  } finally {
    if (child.exitCode === null && admin) {
      try { result.shutdownHttp = (await http('/actuator/shutdown', { method: 'POST', token: admin.accessToken }, management)).status; } catch {}
    }
    let timer;
    const stopped = await Promise.race([exited, new Promise(resolve => { timer = setTimeout(() => resolve(null), 15000); })]);
    clearTimeout(timer);
    if (stopped === null) { child.kill(); result.forcedStop = true; result.exit = await exited; } else result.exit = stopped;
    fs.closeSync(fd);
    try { await free(port); await free(port + 1); result.portsFree = true; } catch { result.portsFree = false; }
    assert.equal(result.forcedStop, undefined, 'Owned process required forced termination');
    assert.equal(result.exit.code, 0); assert.equal(result.portsFree, true);
    assert.equal(sha(jars[kind]), result.jarSha256);
  }
}
(async () => {
  try { await run('before'); await run('after'); report.status = 'PASS'; }
  catch (error) { report.status = 'FAIL'; report.failure = error.stack; process.exitCode = 1; }
  finally { fs.writeFileSync(path.join(out, 'qa.json'), JSON.stringify(report, null, 2) + '\n'); console.log(JSON.stringify(report)); }
})();

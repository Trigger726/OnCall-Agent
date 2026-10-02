const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const http = require('node:http');
const assert = require('node:assert/strict');
const { randomUUID, randomBytes, createHash } = require('node:crypto');
const { spawn, spawnSync } = require('node:child_process');
const { setTimeout: delay } = require('node:timers/promises');
const { requireFreePort, stopProcess, waitForHealth, redact, unexpectedLogLines } = require('./verify-oncall-browser-ci.cjs');

const root = path.resolve(__dirname, '..');
const base = 'http://127.0.0.1:9918';
const prometheusUrl = 'http://127.0.0.1:9094';
const alertmanagerUrl = 'http://127.0.0.1:9095';

async function verify() {
  for (const port of [9918, 9922, 9094, 9095]) await requireFreePort(port);
  const parent = path.join(root, 'target', 'slo-pipeline-it');
  fs.mkdirSync(parent, { recursive: true });
  const evidence = fs.mkdtempSync(path.join(parent, 'run-'));
  const secretDirectory = fs.mkdtempSync(path.join(os.tmpdir(), 'opspilot-slo-secret-'));
  const secretPath = path.join(secretDirectory, 'credential');
  const secret = randomBytes(32).toString('hex');
  fs.writeFileSync(secretPath, secret, { mode: 0o600 });
  const children = [];
  const receipts = [];
  const result = { status: 'RUNNING', evidenceDirectory: path.relative(root, evidence),
    fixture: 'synthetic window event counts; real native rule evaluation and HTTP delivery',
    userDatabaseModified: false, ownedProcessesStopped: false };
  let good = 10000;
  const fixture = http.createServer(async (request, response) => {
    try {
      if (request.url === '/metrics') {
        response.writeHead(200, { 'Content-Type': 'text/plain; version=0.0.4' });
        response.end(['5m', '1h', '30m', '6h', '3d'].map(window =>
          `slo_fixture_good_${window}{service_code="APP-SETTLEMENT"} ${good}\n`
          + `slo_fixture_total_${window}{service_code="APP-SETTLEMENT"} 10000\n`).join(''));
      } else if (request.url === '/webhook' && request.method === 'POST') {
        const chunks = [];
        let length = 0;
        for await (const chunk of request) {
          length += chunk.length;
          if (length > 1024 * 1024) throw new Error('Fixture request too large');
          chunks.push(chunk);
        }
        const body = Buffer.concat(chunks);
        const payload = JSON.parse(body);
        const forwarded = await fetch(base + '/api/v1/integrations/alertmanager/webhook', {
          method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: request.headers.authorization || '' },
          body, signal: AbortSignal.timeout(10000),
        });
        receipts.push({ status: forwarded.status, authValid: request.headers.authorization === `OpsPilot ${secret}`,
          alerts: payload.alerts.map(alert => ({ name: alert.labels.alertname, status: alert.status,
            fingerprint: alert.fingerprint, startsAt: alert.startsAt, sloVersion: alert.labels.slo_version })) });
        response.writeHead(forwarded.status, { 'Content-Type': 'application/json' });
        response.end(await forwarded.text());
      } else { response.writeHead(404); response.end(); }
    } catch { response.writeHead(500); response.end('Fixture failure'); }
  });
  function launch(name, executable, args, env = process.env) {
    const descriptor = fs.openSync(path.join(evidence, name + '.log'), 'w');
    const child = spawn(executable, args, { cwd: evidence, env, stdio: ['ignore', descriptor, descriptor], windowsHide: true });
    child.once('error', error => { child.launchError = error; });
    children.push({ name, child, descriptor });
    return child;
  }
  const interrupt = () => children.forEach(({ child }) => child.kill('SIGTERM'));
  process.once('SIGINT', interrupt);
  process.once('SIGTERM', interrupt);
  async function json(url, token, method = 'GET', body) {
    const response = await fetch(url, { method, headers: {
      ...(token ? { Authorization: `Bearer ${token}` } : {}), ...(body ? { 'Content-Type': 'application/json' } : {}),
    }, body: body ? JSON.stringify(body) : undefined, signal: AbortSignal.timeout(10000) });
    assert.equal(response.status, 200, `${method} ${url}`);
    return response.json();
  }
  async function until(description, operation, predicate, timeout = 150000) {
    const deadline = Date.now() + timeout;
    while (Date.now() < deadline) {
      for (const { name, child } of children) {
        if (child.exitCode !== null || child.signalCode !== null || child.launchError) throw new Error(`${name} exited while waiting for ${description}`);
      }
      try { const value = await operation(); if (predicate(value)) return value; } catch { /* Bounded readiness/state observation. */ }
      await delay(500);
    }
    throw new Error(`Timed out waiting for ${description}`);
  }
  const writeJson = (name, value) => fs.writeFileSync(path.join(evidence, name), JSON.stringify(value, null, 2));
  try {
    await new Promise((resolve, reject) => { fixture.once('error', reject); fixture.listen(0, '127.0.0.1', resolve); });
    const fixturePort = fixture.address().port;
    const rulePath = path.join(evidence, 'captured.rules.yml');
    writeJson('captured.rules.yml', { groups: [] });
    writeJson('alertmanager.yml', {
      route: { receiver: 'opspilot', group_by: ['alertname', 'slo_id', 'slo_version'], group_wait: '1s', group_interval: '2s', repeat_interval: '4s' },
      receivers: [{ name: 'opspilot', webhook_configs: [{ url: `http://127.0.0.1:${fixturePort}/webhook`, send_resolved: true,
        http_config: { authorization: { type: 'OpsPilot', credentials_file: secretPath.replaceAll('\\', '/') } } }] }],
    });
    writeJson('prometheus.yml', {
      global: { scrape_interval: '2s', scrape_timeout: '1s', evaluation_interval: '30s' },
      rule_files: [rulePath.replaceAll('\\', '/')],
      alerting: { alertmanagers: [{ static_configs: [{ targets: ['127.0.0.1:9095'] }] }] },
      scrape_configs: [{ job_name: 'slo-window-fixture', static_configs: [{ targets: [`127.0.0.1:${fixturePort}`] }] }],
    });
    const java = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java';
    const jar = launch('jar', java, ['-Duser.timezone=UTC', '-jar', path.join(root, 'target', 'opspilot-0.1.0-SNAPSHOT.jar'),
      '--server.address=127.0.0.1', '--server.port=9918', '--management.server.port=9922', '--management.server.address=127.0.0.1',
      `--spring.datasource.url=jdbc:h2:mem:slo_pipeline_${randomUUID().replaceAll('-', '')};MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE`,
      '--spring.datasource.username=sa', '--spring.datasource.password=', '--opspilot.ai.enabled=false',
      '--opspilot.agent.recovery.enabled=false', '--opspilot.oncall.rotation.enabled=false', '--opspilot.oncall.escalation.enabled=false',
      '--opspilot.observability.prometheus.enabled=true', `--opspilot.observability.prometheus.base-url=${prometheusUrl}`],
      { ...process.env, ALERTMANAGER_WEBHOOK_SECRET: secret });
    await waitForHealth('http://127.0.0.1:9922/actuator/health', jar);
    launch('alertmanager', process.env.OPSPILOT_ALERTMANAGER || 'alertmanager', [
      '--config.file=alertmanager.yml', '--storage.path=alertmanager-data', '--web.listen-address=127.0.0.1:9095', '--cluster.listen-address=']);
    launch('prometheus', process.env.OPSPILOT_PROMETHEUS || 'prometheus', [
      '--config.file=prometheus.yml', '--storage.tsdb.path=prometheus-data', '--web.listen-address=127.0.0.1:9094', '--web.enable-lifecycle']);
    for (const url of [prometheusUrl, alertmanagerUrl]) {
      await until(url + ' readiness', () => fetch(url + '/-/ready', { signal: AbortSignal.timeout(1000) }), response => response.ok);
    }
    await until('real fixture scrape', () => json(prometheusUrl + '/api/v1/query?query=up'),
      value => value.data.result.some(sample => sample.value[1] === '1'));
    const { data: { accessToken: token } } = await json(base + '/api/v1/auth/login', null, 'POST', { username: 'lina', password: 'OpsPilot@2026' });
    await json(base + '/api/v1/slo/objectives/1', token, 'PATCH', { expectedVersion: 0, name: '原生告警流水线', targetPercent: 99.9, windowDays: 28,
      goodEventsQueryTemplate: 'last_over_time(slo_fixture_good_{{window}}{service_code="{{service}}"}[{{window}}])',
      totalEventsQueryTemplate: 'last_over_time(slo_fixture_total_{{window}}{service_code="{{service}}"}[{{window}}])' });
    const { data: bundle } = await json(base + '/api/v1/slo/objectives/1/versions/1/prometheus-rules', token);
    assert.equal(bundle.sha256, createHash('sha256').update(bundle.rulesYaml).digest('hex'));
    fs.writeFileSync(rulePath, bundle.rulesYaml);
    writeJson('captured.bundle.json', bundle);
    const checked = spawnSync(process.env.OPSPILOT_PROMTOOL || 'promtool', ['check', 'config', 'prometheus.yml'],
      { cwd: evidence, encoding: 'utf8', timeout: 30000, windowsHide: true });
    fs.writeFileSync(path.join(evidence, 'promtool-check.log'), (checked.stdout || '') + (checked.stderr || ''));
    assert.equal(checked.status, 0, 'Native config and captured rules validation');
    const reload = await fetch(prometheusUrl + '/-/reload', { method: 'POST', signal: AbortSignal.timeout(5000) });
    assert.equal(reload.status, 200);
    await until('healthy recorded burn', () => json(prometheusUrl + '/api/v1/query?query=' + encodeURIComponent('opspilot:slo_1_v1:burn_1h')),
      value => value.data.result.length === 1 && Number(value.data.result[0].value[1]) === 0);
    good = 9862; // 13.8x crosses the 28-day threshold (13.44), below the former fixed 14.4 threshold.
    const firing = await until('native SLO fast firing', () => json(prometheusUrl + '/api/v1/alerts'), value =>
      value.data.alerts.some(alert => alert.labels.alertname === 'OpsPilotSloFastPage' && alert.state === 'firing'));
    assert.equal(firing.data.alerts.filter(alert => alert.state === 'firing').length, 1);
    writeJson('native-firing.json', firing);
    const overview = await json(base + '/api/v1/slo/objectives', token);
    assert.equal(overview.data.objectives.find(objective => objective.id === 1).burnRate.status, 'PAGE_FAST');
    writeJson('overview-firing.json', overview);
    const alerts = await until('durable firing alert', () => json(base + '/api/v1/alerts?size=100', token), value =>
      value.data.items.some(alert => alert.title === 'OpsPilotSloFastPage' && alert.status === 'FIRING'));
    const alert = alerts.data.items.find(alert => alert.title === 'OpsPilotSloFastPage');
    assert.equal(alert.occurrenceCount, 1);
    assert.equal(alert.severity, 'P1');
    await until('real repeated firing delivery', async () => receipts, value =>
      value.flatMap(receipt => receipt.alerts).filter(item => item.name === alert.title && item.status === 'firing').length >= 2);
    const repeated = (await json(base + '/api/v1/alerts?size=100', token)).data.items.filter(item => item.title === alert.title);
    assert.equal(repeated.length, 1);
    assert.equal(repeated[0].occurrenceCount, 1);
    good = 10000;
    const resolved = await until('same durable alert resolved', () => json(base + '/api/v1/alerts?size=100', token), value =>
      value.data.items.some(item => item.id === alert.id && item.status === 'RESOLVED'));
    const finalAlert = resolved.data.items.find(item => item.id === alert.id);
    assert.equal(finalAlert.occurrenceCount, 1);
    const detail = await json(base + `/api/v1/incidents/${alert.incidentId}`, token);
    assert.equal(detail.data.timeline.filter(event => event.eventType === 'ALERT_RESOLVED' && event.evidenceRef === `alert:${alert.id}`).length, 1);
    assert.notEqual(detail.data.incident.status, 'CLOSED');
    const rejections = await json(base + '/api/v1/integrations/alertmanager/rejections', token);
    assert.equal(rejections.data.total, 0);
    const fastReceipts = receipts.filter(receipt => receipt.alerts.some(item => item.name === alert.title));
    assert(fastReceipts.every(receipt => receipt.authValid && receipt.status === 200));
    assert(fastReceipts.some(receipt => receipt.alerts.some(item => item.status === 'resolved')));
    assert.equal(new Set(fastReceipts.flatMap(receipt => receipt.alerts.map(item => item.fingerprint + ':' + item.startsAt))).size, 1);
    writeJson('final-alert.json', finalAlert);
    writeJson('final-incident.json', detail.data);
    writeJson('delivery-receipts.json', receipts);
    result.alertId = alert.id;
    result.incidentId = alert.incidentId;
    result.objectiveVersion = bundle.objectiveVersion;
    result.rulesSha256 = bundle.sha256;
    result.repeatedFiringDeliveries = fastReceipts.flatMap(receipt => receipt.alerts).filter(item => item.status === 'firing').length;
    result.resolvedTimelineEvents = 1;
    result.rejections = 0;
    result.status = 'PASS';
  } catch (error) { result.status = 'FAIL'; result.failure = redact(error.message); throw error; }
  finally {
    process.off('SIGINT', interrupt);
    process.off('SIGTERM', interrupt);
    const cleanupErrors = [];
    for (const { name, child, descriptor } of [...children].reverse()) {
      try { if (child.pid) await stopProcess(child); }
      catch { cleanupErrors.push(name); }
      finally {
        fs.closeSync(descriptor);
        const file = path.join(evidence, name + '.log');
        fs.writeFileSync(file, redact(fs.readFileSync(file, 'utf8')).replaceAll(secret, '[REDACTED]'));
      }
    }
    if (fixture.listening) await new Promise(resolve => fixture.close(resolve));
    fs.rmSync(secretPath);
    fs.rmdirSync(secretDirectory);
    result.ownedProcessesStopped = cleanupErrors.length === 0;
    result.unexpectedJarErrors = fs.existsSync(path.join(evidence, 'jar.log')) ? unexpectedLogLines(fs.readFileSync(path.join(evidence, 'jar.log'), 'utf8')) : null;
    if (cleanupErrors.length || result.unexpectedJarErrors) result.status = 'FAIL';
    writeJson('result.json', result);
    console.log(JSON.stringify(result));
    if (result.status !== 'PASS') process.exitCode = 1;
  }
}
verify().catch(error => { console.error(redact(error.message)); process.exitCode = 1; });

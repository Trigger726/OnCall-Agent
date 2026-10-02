const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const { randomUUID, createHash } = require('node:crypto');
const { spawn, spawnSync } = require('node:child_process');
const { requireFreePort, stopProcess, waitForHealth, redact, unexpectedLogLines } = require('./verify-oncall-browser-ci.cjs');

const root = path.resolve(__dirname, '..');
const base = 'http://127.0.0.1:9918';
const promtool = process.env.OPSPILOT_PROMTOOL || 'promtool';
const cases = [
  { name: 'healthy', days: 30 },
  { name: 'scalar-result', days: 30, scalar: true, good: 9850, alert: 'OpsPilotSloFastPage', lane: 'FAST_PAGE', window: '1h', severity: 'P1' },
  { name: 'counter-reset', days: 30, counter: true, good: 9850, alert: 'OpsPilotSloFastPage', lane: 'FAST_PAGE', window: '1h', severity: 'P1' },
  { name: 'fast-priority', days: 30, good: 9850, alert: 'OpsPilotSloFastPage', lane: 'FAST_PAGE', window: '1h', severity: 'P1' },
  { name: 'long-only', days: 30, overrides: { '1h': 9800 } },
  { name: 'slow', days: 30, overrides: { '6h': 9930, '30m': 9930 }, alert: 'OpsPilotSloSlowPage', lane: 'SLOW_PAGE', window: '6h', severity: 'P2' },
  { name: 'ticket', days: 30, overrides: { '3d': 9980, '6h': 9980 }, alert: 'OpsPilotSloTicket', lane: 'TICKET', window: '3d', severity: 'P3' },
  { name: 'period-28', days: 28, good: 9862, alert: 'OpsPilotSloFastPage', lane: 'FAST_PAGE', window: '1h', severity: 'P1' },
  { name: 'period-90', days: 90, good: 9850, alert: 'OpsPilotSloTicket', lane: 'TICKET', window: '3d', severity: 'P3' },
  { name: 'period-1', days: 1, good: 9950, alert: 'OpsPilotSloFastPage', lane: 'FAST_PAGE', window: '1h', severity: 'P1' },
  { name: 'period-2', days: 2, good: 9950, alert: 'OpsPilotSloFastPage', lane: 'FAST_PAGE', window: '1h', severity: 'P1' },
  { name: 'fractional-events', days: 30, good: 0.000001, total: 0.00001, alert: 'OpsPilotSloFastPage', lane: 'FAST_PAGE', window: '1h', severity: 'P1' },
  { name: 'zero-denominator', days: 30, good: 0, total: 0, invalid: true },
  { name: 'contradictory', days: 30, good: 10001, invalid: true },
  { name: 'negative-good', days: 30, good: -1, invalid: true },
  { name: 'nan', days: 30, good: 'NaN', invalid: true },
  { name: 'infinite-good', days: 30, good: '+Inf', invalid: true },
  { name: 'infinite-total', days: 30, total: '+Inf', invalid: true },
  { name: 'multiple-series', days: 30, multiple: true, invalid: true },
  { name: 'missing-good', days: 30, missing: true, invalid: true },
];

async function request(route, token, method = 'GET', body, expected = 200) {
  const response = await fetch(base + route, { method, headers: {
    ...(token ? { Authorization: `Bearer ${token}` } : {}),
    ...(body ? { 'Content-Type': 'application/json' } : {}),
  }, body: body ? JSON.stringify(body) : undefined, signal: AbortSignal.timeout(10000) });
  assert.equal(response.status, expected, `${method} ${route}`);
  const data = await response.json();
  return data.data;
}
function native(args, evidence, name) {
  const result = spawnSync(promtool, args, { cwd: evidence, encoding: 'utf8', windowsHide: true, timeout: 90000 });
  fs.writeFileSync(path.join(evidence, name + '.log'), redact((result.stdout || '') + (result.stderr || '')));
  if (result.error || result.status !== 0) throw new Error(`Native ${name} failed; see retained log`);
}
function windows(days) {
  const ticket = Math.min(3, days);
  return [...new Set(['5m', '1h', '30m', '6h', ticket * 2 + 'h', ticket + 'd'])];
}
function fixture(test, bundle, fileName) {
  const labels = { resource_code: bundle.serviceCode, slo_id: String(bundle.objectiveId), slo_version: String(bundle.objectiveVersion) };
  const input = [];
  for (const window of windows(test.days)) {
    const values = [test.overrides?.[window] ?? test.good ?? 10000, test.total ?? 10000];
    for (let i = 0; i < 2; i++) {
      if (i === 0 && test.missing) continue;
      const metric = `slo_fixture_${i === 0 ? 'good' : 'total'}_${window}`;
      input.push({ series: `${metric}{service_code="${bundle.serviceCode}",region="a"}`,
        values: test.counter ? `0 ${values[i]} ${values[i] * 2} ${values[i]} ${values[i] * 2}` : `${values[i]}+0x4` });
      if (i === 0 && test.multiple) input.push({ series: `${metric}{service_code="${bundle.serviceCode}",region="b"}`, values: `${values[i]}+0x4` });
    }
  }
  const expected = name => test.alert === name ? [{ exp_labels: { ...labels, window: test.window, lane: test.lane, severity: test.severity },
    exp_annotations: { summary: `${bundle.serviceCode} SLO ${test.lane}` } }] : [];
  return { rule_files: [fileName], evaluation_interval: '30s', tests: [{ name: test.name, interval: '30s', input_series: input,
    alert_rule_test: [
      ...['OpsPilotSloFastPage', 'OpsPilotSloSlowPage', 'OpsPilotSloTicket'].map(alertname => ({ eval_time: '2m', alertname, exp_alerts: expected(alertname) })),
      { eval_time: '2m', alertname: 'OpsPilotSloDataUnavailable', exp_alerts: test.invalid ? windows(test.days).map(window => ({
        exp_labels: { ...labels, window, severity: 'P3' }, exp_annotations: { summary: `${bundle.serviceCode} SLO ${window} 数据不可用` },
      })) : [] },
    ] }] };
}

async function verify() {
  // This runner owns only its two checked ports and one unique in-memory database.
  await requireFreePort(9918);
  await requireFreePort(9922);
  const parent = path.join(root, 'target', 'slo-rules-it');
  fs.mkdirSync(parent, { recursive: true });
  const evidence = fs.mkdtempSync(path.join(parent, 'run-'));
  const descriptor = fs.openSync(path.join(evidence, 'jar.log'), 'w');
  const java = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java';
  const child = spawn(java, ['-jar', path.join(root, 'target', 'opspilot-0.1.0-SNAPSHOT.jar'),
    '--server.address=127.0.0.1', '--server.port=9918', '--management.server.address=127.0.0.1', '--management.server.port=9922',
    `--spring.datasource.url=jdbc:h2:mem:slo_${randomUUID().replaceAll('-', '')};MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE`,
    '--spring.datasource.username=sa', '--spring.datasource.password=', '--opspilot.ai.enabled=false',
    '--opspilot.oncall.rotation.enabled=false', '--opspilot.oncall.escalation.enabled=false',
    '--opspilot.agent.recovery.enabled=false'], { cwd: root, stdio: ['ignore', descriptor, descriptor], windowsHide: true });
  child.once('error', error => { child.launchError = error; });
  const interrupt = () => child.kill('SIGTERM');
  process.once('SIGINT', interrupt);
  process.once('SIGTERM', interrupt);
  const result = { status: 'RUNNING', cases: [], evidenceDirectory: path.relative(root, evidence), userDatabaseModified: false, ownedProcessStopped: false };
  try {
    await waitForHealth('http://127.0.0.1:9922/actuator/health', child);
    native(['--version'], evidence, 'promtool-version');
    const { accessToken: token } = await request('/api/v1/auth/login', null, 'POST', { username: 'lina', password: 'OpsPilot@2026' });
    let version = 0;
    for (const test of cases) {
      const template = kind => {
        const source = `slo_fixture_${kind}_{{window}}{service_code="{{service}}"}[{{window}}]`;
        return test.counter ? `sum(increase(${source}))` : test.scalar ? `scalar(sum(last_over_time(${source})))` : `last_over_time(${source})`;
      };
      const objective = await request('/api/v1/slo/objectives/1', token, 'PATCH', { expectedVersion: version,
        name: '原生规则验收 ' + test.name, targetPercent: 99.9, windowDays: test.days,
        goodEventsQueryTemplate: template('good'), totalEventsQueryTemplate: template('total') });
      version = objective.version;
      await request(`/api/v1/slo/objectives/1/versions/${version - 1}/prometheus-rules`, token, 'GET', undefined, 409);
      const route = `/api/v1/slo/objectives/1/versions/${version}/prometheus-rules`;
      const bundle = await request(route, token);
      assert.deepEqual(await request(route, token), bundle);
      assert.equal(createHash('sha256').update(bundle.rulesYaml).digest('hex'), bundle.sha256);
      const file = test.name + '.rules.yml';
      fs.writeFileSync(path.join(evidence, file), bundle.rulesYaml);
      fs.writeFileSync(path.join(evidence, test.name + '.bundle.json'), JSON.stringify(bundle, null, 2));
      fs.writeFileSync(path.join(evidence, test.name + '.test.yml'), JSON.stringify(fixture(test, bundle, file), null, 2));
      native(['check', 'rules', file], evidence, test.name + '-check');
      native(['test', 'rules', test.name + '.test.yml'], evidence, test.name + '-eval');
      result.cases.push({ name: test.name, version, sha256: bundle.sha256, ruleCount: bundle.ruleCount, status: 'PASS' });
    }
    result.status = 'PASS';
  } catch (error) { result.status = 'FAIL'; result.failure = redact(error.message); throw error; }
  finally {
    process.off('SIGINT', interrupt);
    process.off('SIGTERM', interrupt);
    try { if (child.pid) await stopProcess(child); result.ownedProcessStopped = true; }
    finally {
      fs.closeSync(descriptor);
      const log = redact(fs.readFileSync(path.join(evidence, 'jar.log'), 'utf8'));
      fs.writeFileSync(path.join(evidence, 'jar.log'), log);
      result.unexpectedJarErrors = unexpectedLogLines(log);
      if (result.unexpectedJarErrors) result.status = 'FAIL';
      fs.writeFileSync(path.join(evidence, 'result.json'), JSON.stringify(result, null, 2));
      console.log(JSON.stringify(result));
      if (result.status !== 'PASS') process.exitCode = 1;
    }
  }
}
if (require.main === module) verify().catch(error => { console.error(redact(error.message)); process.exitCode = 1; });

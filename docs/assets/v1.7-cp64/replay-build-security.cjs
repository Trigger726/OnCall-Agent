const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

// Read-only proof replay. It does not reproduce the browser or vulnerability.
const root = path.resolve(process.argv[2] || __dirname);
const read = name => JSON.parse(fs.readFileSync(path.join(root, name), 'utf8'));
const manifest = read('ci/manifest.json');
assert.match(manifest.source, /^[a-f0-9]{40}$/);
assert.equal(manifest.runId, 37049527001);
assert.equal(manifest.jobs.length, 14);
assert.equal(new Set(manifest.jobs.map(job => job.name)).size, 14);
for (const job of manifest.jobs) {
  assert.equal(job.status, 'completed');
  assert.equal(job.conclusion, 'success');
}
assert.equal(manifest.verifiedArtifacts.length, 5);
for (const artifact of manifest.verifiedArtifacts) {
  assert.equal(artifact.head_sha, manifest.source);
  assert.match(artifact.sha256, /^[a-f0-9]{64}$/);
}
const auditZero = report => {
  assert.equal(report.auditReportVersion, 2);
  assert.deepEqual(report.vulnerabilities, {});
  for (const level of ['info', 'low', 'moderate', 'high', 'critical', 'total']) {
    assert.equal(report.metadata.vulnerabilities[level], 0, `audit ${level}`);
  }
  assert.ok(report.metadata.dependencies.dev > 0, 'audit includes build dependencies');
};
const before = read('local/cp64BeforeAudit.json');
assert.equal(before.metadata.vulnerabilities.high, 1);
assert.equal(before.vulnerabilities['brace-expansion'].via.length, 3);
auditZero(read('local/cp64AfterAudit.json'));
auditZero(read('ci/frontend-audit.json'));
const old = read('local/cp64OldRegression.json');
assert.equal(old.exit_code, 1);
assert.match(old.output, /pass 1\b/);
assert.match(old.output, /fail 3\b/);
assert.match(old.output, /Maximum call stack size exceeded/);
const compiler = read('local/cp64OldVueCompiler.json');
assert.equal(compiler.exit_code, 0);
assert.match(compiler.output, /RangeError: Maximum call stack/);
const tests = read('local/cp64FrontendTests.json');
assert.equal(tests.exit_code, 0);
assert.match(tests.output, /tests 62\b/);
assert.match(tests.output, /pass 62\b/);
assert.match(tests.output, /fail 0\b/);
const canonical = assets => {
  assert.equal(assets.length, 4);
  assert.equal(new Set(assets.map(a => a.name)).size, 4);
  for (const asset of assets) {
    assert.match(asset.sha256, /^[a-f0-9]{64}$/);
    assert.ok(asset.bytes > 0);
  }
  return assets.map(a => [a.name, a.bytes, a.sha256]).sort((a, b) => a[0].localeCompare(b[0]));
};
assert.deepEqual(canonical(read('local/cp64BeforeAssetManifest.json')), canonical(read('local/cp64AfterAssetManifest.json')));
for (const directory of ['old', 'new']) {
  const runner = read(`${directory}/runner-result.json`);
  assert.equal(runner.status, 'PASS');
  assert.equal(runner.scripts.length, 11);
  runner.scripts.forEach(script => assert.equal(script.exitCode, 0));
  assert.equal(runner.ownedProcessStopped, true);
  assert.equal(runner.userFileDatabaseModified, false);
  assert.equal(runner.unexpectedJarErrors, 0);
  const page = read(`${directory}/rotation-result.json`);
  for (const key of ['pageIdentity', 'noBlank', 'noOverlay', 'createdFromUI', 'pausedPersisted', 'realBackgroundFilled', 'cancelledSlotRetained']) assert.equal(page[key], true);
  assert.deepEqual(page.pageErrors, []);
  assert.equal(page.consoleLogs.length, 2);
  assert.deepEqual(page.expected409, [{status:409,path:'/api/v1/on-call/rotations'}, {status:409,path:'/api/v1/on-call/rotations/1/state'}]);
}
console.log(JSON.stringify({status:'PASS', evidenceReplayOnly:true, audit:'old high1; local and CI zero including dev', tests:'old 1/3; new 62/0', staticAssetsByteIdentical:4, browserSnapshots:'old/new 11 scripts, two expected409, no page errors'}));

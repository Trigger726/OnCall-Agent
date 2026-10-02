const fs = require('node:fs');
const path = require('node:path');

const expectedSuites = {
  'org.trigger.opspilot.MySqlCompatibilityIntegrationTest': 20,
  'org.trigger.opspilot.oncall.MySqlOnCallRoutingSnapshotIntegrationTest': 8,
  'org.trigger.opspilot.oncall.MySqlOnCallRotationIntegrationTest': 10,
  'org.trigger.opspilot.oncall.MySqlOnCallCoverageIntegrationTest': 8,
  'org.trigger.opspilot.oncall.MySqlOnCallHandoffIntegrationTest': 27,
};
const requiredAccountTests = [
  'shouldRejectDisabledReadsAndLogin', 'shouldRejectDisabledWriteWithoutAuditOrTimeline',
  'shouldRejectOldJwtAfterAccountRemoval', 'shouldReloadCurrentRoleButKeepActiveAuthentication',
  'shouldUseRealServletRequestForAuthenticatedHttpWrite',
];

function suiteResult(xml, name, minimum) {
  const header = xml.match(/<testsuite\b([^>]*)>/);
  if (!header || !xml.includes('</testsuite>')) throw new Error(`Missing complete suite: ${name}`);
  const attributes = Object.fromEntries([...header[1].matchAll(/([\w-]+)="([^"]*)"/g)].map(m => [m[1], m[2]]));
  if (attributes.name !== name) throw new Error(`Wrong suite identity: ${name}`);
  const counts = {};
  for (const key of ['tests', 'failures', 'errors', 'skipped']) {
    if (!/^\d+$/.test(attributes[key] || '')) throw new Error(`Invalid ${key}: ${name}`);
    counts[key] = Number(attributes[key]);
  }
  if (counts.tests < minimum || counts.failures || counts.errors || counts.skipped) {
    throw new Error(`Suite must execute at least ${minimum} tests without failures/errors/skips: ${name}`);
  }
  if (name === 'org.trigger.opspilot.MySqlCompatibilityIntegrationTest') {
    for (const required of requiredAccountTests) {
      if (!new RegExp(`<testcase\\b[^>]*\\bname="${required}"`).test(xml)) {
        throw new Error(`Missing real account HTTP case: ${required}`);
      }
    }
  }
  return { name, ...counts };
}

function verify(log, reports) {
  // Scan the complete Maven output, including JVM shutdown after the test summary.
  const lines = log.replace(/\x1b\[[0-9;]*m/g, '').split(/\r?\n/);
  const unexpected = lines.filter(line => /(?:^|\s)(?:ERROR|\[ERROR\])(?:\s|$)|Unexpected error occurred in scheduled task|Surefire is going to kill|Failed to validate connection|No operations allowed after connection closed|Communications link failure/i.test(line));
  if (unexpected.length) throw new Error(`Unexpected MySQL lifecycle/error lines: ${unexpected.length}`);
  if (!lines.some(line => /\[INFO\]\s+BUILD SUCCESS/.test(line))) throw new Error('Missing Maven BUILD SUCCESS');

  const started = [], stopped = [];
  lines.forEach((line, index) => {
    const start = line.match(/(HikariPool-\d+) - Start completed\./);
    const stop = line.match(/(HikariPool-\d+) - Shutdown completed\./);
    if (start) started.push({ pool: start[1], index });
    if (stop) stopped.push({ pool: stop[1], index });
  });
  const pools = new Set(started.map(x => x.pool));
  if (pools.size !== Object.keys(expectedSuites).length || started.length !== pools.size
      || stopped.length !== pools.size || new Set(stopped.map(x => x.pool)).size !== pools.size) {
    throw new Error('Expected five unique started and cleanly stopped MySQL pools');
  }
  for (const start of started) {
    if (!stopped.some(stop => stop.pool === start.pool && stop.index > start.index)) {
      throw new Error(`Missing ordered pool shutdown: ${start.pool}`);
    }
  }
  const suites = Object.entries(expectedSuites).map(([name, minimum]) => suiteResult(reports[name] || '', name, minimum));
  return { status: 'PASS', suites, executed: suites.reduce((sum, suite) => sum + suite.tests, 0),
    skipped: 0, unexpectedLogLines: 0, startedPools: started.map(x => x.pool),
    stoppedPools: stopped.map(x => x.pool), completeMavenOutputIncludingShutdown: true };
}

function main() {
  const logFile = process.argv[2] || 'target/mysql-it/maven.log';
  const reportDirectory = process.argv[3] || 'target/surefire-reports';
  const output = process.argv[4] || 'target/mysql-it/lifecycle-result.json';
  const reports = Object.fromEntries(Object.keys(expectedSuites).map(name => [name,
    fs.readFileSync(path.join(reportDirectory, `TEST-${name}.xml`), 'utf8')]));
  const result = verify(fs.readFileSync(logFile, 'utf8'), reports);
  fs.writeFileSync(output, JSON.stringify(result, null, 2) + '\n');
  process.stdout.write(JSON.stringify(result) + '\n');
}

module.exports = { expectedSuites, requiredAccountTests, suiteResult, verify };
if (require.main === module) {
  try { main(); }
  catch (error) { console.error(`MySQL lifecycle gate failed: ${error.message}`); process.exitCode = 1; }
}

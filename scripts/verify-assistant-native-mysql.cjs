const fs = require('node:fs');
const path = require('node:path');
const { suiteResult } = require('./verify-mysql-lifecycle.cjs');

const suiteName = 'org.trigger.opspilot.assistant.AssistantNativeEndpointIntegrationTest';
const expectedCases = {
  shouldDeliverRealNativeTokenBeforeCompletionAndCommitExactAnswer: 1,
  shouldCancelAfterPreviewAndReuseWorkerBeforeOldProviderIsReleased: 1,
  shouldTimeoutAnIdleProviderAfterPreviewWithoutCommittingPartialAnswer: 1,
  shouldRejectNativeQueueSaturationBeforeAdmissionAndReuseCancelledSlot: 1,
  shouldFailOnNativeEofRatherThanAppendRuleFallback: 10,
  browserDisconnectShouldContinueOriginalDurableRequestWithoutAnotherPost: 1,
  shouldStopRevokedNativePreviewWithoutLateAnswerOrErrorPayload: 1,
  shouldNotResurrectClearedNativePreviewWhenModelCompletesLate: 1,
  nativeDoneMustNotEscapeAFailedFinalAuditTransaction: 1,
};
const executions = Object.values(expectedCases).reduce((sum, count) => sum + count, 0);
const attributes = value => Object.fromEntries([...value.matchAll(/([\w-]+)="([^"]*)"/g)].map(m => [m[1], m[2]]));

function databaseMarkers(text) {
  return [...text.matchAll(/CP80_NATIVE_DATABASE (\{[^\r\n]*\})/g)].map(match => {
    const marker = JSON.parse(match[1]);
    if (marker.product !== 'MySQL' || !/^8\.4\.\d+(?:[-+].*)?$/.test(marker.version || '') || !Object.hasOwn(expectedCases, marker.case)) {
      throw new Error('Every native case must prove actual JDBC MySQL 8.4 metadata');
    }
    return marker;
  });
}

function requireCoverage(names) {
  const counts = Object.fromEntries(Object.keys(expectedCases).map(name => [name, 0]));
  for (const name of names) {
    if (!Object.hasOwn(counts, name)) throw new Error('Unexpected native HTTP case identity');
    counts[name]++;
  }
  for (const [name, expected] of Object.entries(expectedCases)) {
    if (counts[name] !== expected) throw new Error(`Missing or duplicate native HTTP executions: ${name}`);
  }
  return counts;
}

function verify(log, xml) {
  // This selected endpoint suite has no expected ERROR logger; inspect shutdown, not just test counters.
  const clean = log.replace(/\x1b\[[0-9;]*m/g, '');
  const lines = clean.split(/\r?\n/);
  if (lines.some(line => /(?:^|\s)(?:ERROR|\[ERROR\])(?:\s|$)|Unexpected error occurred in scheduled task|Surefire is going to kill|Failed to validate connection|No operations allowed after connection closed|Communications link failure/i.test(line))) {
    throw new Error('Unexpected native MySQL lifecycle/error line');
  }
  if (!lines.some(line => /\[INFO\]\s+BUILD SUCCESS/.test(line))) throw new Error('Missing complete Maven BUILD SUCCESS');
  const suite = suiteResult(xml, suiteName, executions);
  const cases = [...xml.matchAll(/<testcase\b([^>]*?)(?:\/>|>([\s\S]*?)<\/testcase>)/g)];
  if (cases.length !== suite.tests || suite.tests !== executions) throw new Error('Native XML execution counters disagree with complete cases');
  const caseVersions = [];
  const counts = requireCoverage(cases.map(match => {
    const testcase = attributes(match[1]), body = match[2] || '';
    if (testcase.classname !== suiteName || /<(?:skipped|failure|error)\b/.test(body)) throw new Error('Wrong, failed or skipped native XML case');
    const markers = databaseMarkers(body);
    if (markers.length !== 1 || markers[0].case !== testcase.name) throw new Error('Missing per-case JDBC metadata in native XML');
    caseVersions.push(markers[0].version);
    return testcase.name;
  }));
  const markers = databaseMarkers(clean);
  requireCoverage(markers.map(marker => marker.case));
  const versions = new Set([...caseVersions, ...markers.map(marker => marker.version)]);
  if (versions.size !== 1) throw new Error('Native XML and Maven JDBC database versions disagree');

  const started = [], stopped = [];
  lines.forEach((line, index) => {
    const start = line.match(/(HikariPool-\d+) - Start completed\./), stop = line.match(/(HikariPool-\d+) - Shutdown completed\./);
    if (start) started.push({ pool: start[1], index });
    if (stop) stopped.push({ pool: stop[1], index });
  });
  if (started.length !== 1 || stopped.length !== 1 || started[0].pool !== stopped[0].pool || started[0].index >= stopped[0].index) {
    throw new Error('Expected one native MySQL pool with ordered, complete shutdown');
  }
  return { status: 'PASS', suite, executed: executions, cases: counts, database: 'MySQL', databaseVersion: [...versions][0],
    jdbcMetadataVerifiedPerExecution: true, completeMavenOutputIncludingShutdown: true,
    startedPool: started[0].pool, stoppedPool: stopped[0].pool, unexpectedLogLines: 0 };
}

module.exports = { suiteName, expectedCases, verify };
if (require.main === module) {
  try {
    const log = process.argv[2] || 'target/assistant-native-mysql-it/maven.log';
    const reports = process.argv[3] || 'target/surefire-reports';
    const output = process.argv[4] || 'target/assistant-native-mysql-it/result.json';
    const result = verify(fs.readFileSync(log, 'utf8'), fs.readFileSync(path.join(reports, `TEST-${suiteName}.xml`), 'utf8'));
    fs.writeFileSync(output, JSON.stringify(result, null, 2) + '\n');
    console.log(JSON.stringify(result));
  } catch (error) { console.error(`Native MySQL gate failed: ${error.message}`); process.exitCode = 1; }
}

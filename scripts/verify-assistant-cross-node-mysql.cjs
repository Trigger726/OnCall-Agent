const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { suiteResult } = require('./verify-mysql-lifecycle.cjs');
const errors = text => text.split(/\r?\n/).filter(line=>/(?:^|\s)(?:ERROR|\[ERROR\])(?:\s|$)|Unexpected error occurred in scheduled task|Surefire is going to kill|Failed to validate connection|No operations allowed after connection closed|Communications link failure|native thread|EACCES/i.test(line));

const suite = 'org.trigger.opspilot.assistant.AssistantCrossNodeMySqlIntegrationTest';
const method = 'shouldVerifyNativeReplayRemoteCancelClearAndRevocationOnOwnedMySql';
const cases = ['shared-sql-completed-replay', 'cancel-on-B-releases-A', 'clear-on-B-fences-A', 'revoke-on-B-fences-A'];

function verify(mavenLog, xml, audit, node, logs) {
  mavenLog = mavenLog.replace(/\u001b\[[0-?]*[ -/]*[@-~]/g, '');
  const result = suiteResult(xml, suite, 1); assert.equal(result.tests, 1);
  assert.equal([...xml.matchAll(new RegExp('<testcase\\b[^>]*\\bname="' + method + '"', 'g'))].length, 1);
  assert.match(mavenLog, /\[INFO\]\s+BUILD SUCCESS/); assert.equal(errors(mavenLog).length, 0);
  assert.equal(audit.status, 'PASS'); assert.equal(audit.database.product, 'MySQL'); assert.match(audit.database.version, /^8\.4\./);
  const identities = [...mavenLog.matchAll(/^CP83_CROSS_NODE_MYSQL_DATABASE (.+)$/gm)]; assert.equal(identities.length, 1);
  assert.deepEqual(JSON.parse(identities[0][1]), audit.database); assert.ok(xml.includes(identities[0][0].trimEnd()));
  assert.match(audit.database.schema, /^opspilot_cross_node_[0-9a-f]{12}$/);
  assert.equal(node.status, 'PASS'); assert.equal(node.baselineCapture, false); assert.equal(node.databaseMode, 'MYSQL_TESTCONTAINER');
  assert.equal(node.mysqlSchema, audit.database.schema); assert.equal(node.providerCalls, 6);
  for (const key of ['ownedProcessesStopped', 'ownedProviderStopped']) assert.equal(node[key], true);
  assert.equal(node.unexpectedJarErrors, 0);
  assert.equal(node.startedPids.length, 2); assert.equal(new Set(node.startedPids).size, 2); assert.ok(node.startedPids.every(p=>Number.isInteger(p)&&p>0));
  assert.deepEqual([...node.stoppedPids].sort(), [...node.startedPids].sort());
  assert.equal(new Set([...node.applicationPorts, ...node.managementPorts]).size, 4);
  for (const key of ['twoIndependentJvmsSameHost', 'recordedJvmPidsVerifiedAbsent', 'fourScopedPortsVerifiedFree']) assert.equal(audit[key], true);
  assert.equal(audit.crossMachineOrDatabaseHaClaimed, false);
  assert.deepEqual(node.cases.map(c=>c.name), cases); assert.deepEqual(audit.sqlFacts.map(c=>c.name), cases);
  assert.equal(new Set(node.cases.map(c=>c.sessionId)).size, 4);
  for (let i = 0; i < 4; i++) {
    const scenario = node.cases[i], fact = audit.sqlFacts[i];
    assert.ok(Number.isInteger(scenario.sessionId) && scenario.sessionId > 0); assert.equal(fact.sessionId, scenario.sessionId);
    assert.equal(fact.requestRows, 1);
    assert.equal(fact.status, ['COMPLETED', 'CANCELLED', 'SUPERSEDED', 'REVOKED'][i]);
    assert.equal(fact.userRows, i === 2 ? 0 : 1); assert.equal(fact.answerRows, i === 0 ? 1 : 0);
    if (i === 0) { assert.ok(fact.answerMessageId > 0); assert.equal(fact.answerMessageId, scenario.answerMessageId); assert.equal(fact.exactNativeUnicodeAnswerVerified, true); }
    else assert.equal(fact.answerMessageId, null);
  }
  const cancelled = node.cases[1];
  assert.deepEqual(node.cases[0].nodes, ['A','B']); assert.equal(node.cases[0].modelCalls, 1); assert.equal(node.cases[0].originalAnswerIdPreserved, true);
  assert.equal(cancelled.persistedStatusBothNodes, 'CANCELLED'); assert.equal(node.cases[2].persistedStatus, 'SUPERSEDED');
  assert.equal(node.cases[2].questionRows, 0); assert.equal(node.cases[2].answerRows, 0); assert.equal(node.cases[3].persistedStatus, 'REVOKED');
  assert.equal(cancelled.duplicateStatus, 409); assert.equal(cancelled.otherActorStatus, 404); assert.equal(cancelled.cancellationAudits, 1);
  assert.equal(audit.sqlFacts[1].cancellationAudits, 1); assert.equal(cancelled.cancelledAnswers, 0);
  for (const i of [1, 2, 3]) assert.equal(node.cases[i].physicalHttpClosedBeforeProviderRelease, true);
  for (const i of [1, 3]) assert.equal(node.cases[i].sameWorkerReusedBeforeProviderRelease, true);
  assert.equal(node.cases[3].oldTokensRejectedBothNodes, true); assert.equal(node.cases[3].noPostRevocationPayload, true);
  assert.equal(logs.length, 2); assert.equal(audit.nodeConnections.length, 2);
  for (let i = 0; i < 2; i++) {
    const log = logs[i], connection = audit.nodeConnections[i];
    assert.equal(connection.node, i ? 'B' : 'A'); assert.equal(connection.pid, node.startedPids[i]);
    assert.ok(connection.flywayDatabaseLine.includes('jdbc:mysql://') && connection.flywayDatabaseLine.includes('/' + audit.database.schema));
    assert.ok(connection.flywayDatabaseLine.includes('(MySQL 8.4)')); assert.ok(log.includes(connection.flywayDatabaseLine));
    assert.equal(errors(log).length, 0); assert.equal(connection.poolStartedAndStopped, true);
    const start = 'HikariPool-1 - Start completed.', stop = 'HikariPool-1 - Shutdown completed.';
    assert.equal(log.split(start).length, 2); assert.equal(log.split(stop).length, 2); assert.ok(log.indexOf(stop) > log.indexOf(start));
  }
  return { status: 'PASS', executed: 1, skipped: 0, cases, database: audit.database,
    nodePids: node.startedPids, finalSqlFactsVerified: true, completeMavenAndNodeShutdownVerified: true,
    twoIndependentJvmsSameHost: true, crossMachineOrDatabaseHaClaimed: false };
}

function main() {
  const root = process.cwd(), mavenLog = fs.readFileSync(process.argv[2], 'utf8').replace(/\u001b\[[0-?]*[ -/]*[@-~]/g, '');
  const markers = [...mavenLog.matchAll(/^CP83_CROSS_NODE_MYSQL_AUDIT (.+)$/gm)]; assert.equal(markers.length, 1);
  const auditPath = markers[0][1].trim(); assert.match(auditPath, /^target\/assistant-cross-node-mysql-it\/audit-[0-9a-f-]{36}\/audit\.json$/);
  const audit = JSON.parse(fs.readFileSync(path.resolve(root, auditPath), 'utf8'));
  assert.match(audit.runnerResultFile, /^target\/assistant-cross-node-mysql-it\/run-[A-Za-z0-9]+\/result\.json$/);
  const runnerPath = path.resolve(root, audit.runnerResultFile), node = JSON.parse(fs.readFileSync(runnerPath, 'utf8'));
  const logs = [1,2].map(n=>fs.readFileSync(path.join(path.dirname(runnerPath), 'jar-' + n + '.log'), 'utf8'));
  const xml = fs.readFileSync(path.join(process.argv[3], 'TEST-' + suite + '.xml'), 'utf8');
  const result = verify(mavenLog, xml, audit, node, logs);
  fs.writeFileSync(process.argv[4], JSON.stringify(result, null, 2) + '\n'); console.log(JSON.stringify(result));
}
module.exports = { verify, suite, method, cases };
if (require.main === module) { try { main(); } catch (error) { console.error('Cross-node MySQL gate failed: ' + error.message); process.exitCode = 1; } }

const { test } = require('node:test');
const assert = require('node:assert/strict');
const { verify, suite, method, cases } = require('../verify-assistant-cross-node-mysql.cjs');

function fixture() {
  const database = { product: 'MySQL', version: '8.4.11', schema: 'opspilot_cross_node_012345abcdef' };
  const marker = 'CP83_CROSS_NODE_MYSQL_DATABASE ' + JSON.stringify(database);
  const logs = [1,2].map(n=>'INFO HikariPool-1 - Start completed.\nINFO Database: jdbc:mysql://localhost:33061/' + database.schema
    + ' (MySQL 8.4) node-' + n + '\nINFO HikariPool-1 - Shutdown completed.');
  const node = { status: 'PASS', baselineCapture: false, databaseMode: 'MYSQL_TESTCONTAINER', mysqlSchema: database.schema,
    providerCalls: 6, ownedProcessesStopped: true, ownedProviderStopped: true, unexpectedJarErrors: 0,
    startedPids: [101,102], stoppedPids: [102,101], applicationPorts: [9965,9967], managementPorts: [9966,9968], cases: [
      { name: cases[0], sessionId: 1, answerMessageId: 10, nodes: ['A','B'], modelCalls: 1, originalAnswerIdPreserved: true },
      { name: cases[1], sessionId: 2, duplicateStatus: 409, otherActorStatus: 404, cancellationAudits: 1, cancelledAnswers: 0,
        physicalHttpClosedBeforeProviderRelease: true, sameWorkerReusedBeforeProviderRelease: true, persistedStatusBothNodes: 'CANCELLED' },
      { name: cases[2], sessionId: 3, physicalHttpClosedBeforeProviderRelease: true, persistedStatus: 'SUPERSEDED', questionRows: 0, answerRows: 0 },
      { name: cases[3], sessionId: 4, physicalHttpClosedBeforeProviderRelease: true, sameWorkerReusedBeforeProviderRelease: true,
        oldTokensRejectedBothNodes: true, noPostRevocationPayload: true, persistedStatus: 'REVOKED' } ] };
  const audit = { status: 'PASS', database, twoIndependentJvmsSameHost: true, crossMachineOrDatabaseHaClaimed: false,
    recordedJvmPidsVerifiedAbsent: true, fourScopedPortsVerifiedFree: true,
    nodeConnections: logs.map((l,i)=>({ node: i?'B':'A', pid: node.startedPids[i], flywayDatabaseLine: l.split('\n')[1], poolStartedAndStopped: true })),
    sqlFacts: cases.map((name,i)=>({ name, sessionId: i+1, requestRows: 1, status: ['COMPLETED','CANCELLED','SUPERSEDED','REVOKED'][i],
      userRows: i===2?0:1, answerRows: i===0?1:0, answerMessageId: i===0?10:null,
      ...(i===0?{exactNativeUnicodeAnswerVerified:true}:{}), ...(i===1?{cancellationAudits:1}:{}) })) };
  const xml = '<testsuite name="' + suite + '" tests="1" failures="0" errors="0" skipped="0"><testcase name="' + method
    + '"><system-out><![CDATA[' + marker + ']]></system-out></testcase></testsuite>';
  return { mavenLog: marker + '\n[INFO] BUILD SUCCESS\n', xml, audit, node, logs };
}
const run = f=>verify(f.mavenLog, f.xml, f.audit, f.node, f.logs);

test('synthetic complete gate fixture requires real-run evidence shape, not product proof', () => { assert.equal(run(fixture()).status, 'PASS'); });
test('H2, wrong version and wrong owned schema identity fail closed', () => {
  for (const [key,value] of [['product','H2'],['version','8.0.1'],['schema','opspilot']]) {
    const f=fixture(); f.audit.database[key]=value; assert.throws(()=>run(f));
  }
});
test('JDBC identity needs matching Maven and actual testcase output, not a global assertion label', () => {
  for (const mutate of [f=>f.mavenLog='BUILD SUCCESS', f=>f.xml=f.xml.replace('CP83_CROSS_NODE_MYSQL_DATABASE','missing'),
    f=>f.mavenLog=f.mavenLog.replace('8.4.11','8.4.12')]) { const f=fixture(); mutate(f); assert.throws(()=>run(f)); }
});
test('conditional skip, missing scenario method and green zero executions cannot pass', () => {
  for (const mutate of [f=>f.xml=f.xml.replace('skipped="0"','skipped="1"'),f=>f.xml=f.xml.replace(method,'unrelated'),
    f=>f.xml=f.xml.replace('tests="1"','tests="0"')]) { const f=fixture(); mutate(f); assert.throws(()=>run(f)); }
});
test('baseline, H2 runner, single PID and duplicate port are rejected', () => {
  for (const mutate of [f=>f.node.baselineCapture=true,f=>f.node.databaseMode='H2_AUTO_SERVER',f=>f.node.startedPids=[101,101],
    f=>f.node.applicationPorts=[9965,9965]]) { const f=fixture(); mutate(f); assert.throws(()=>run(f)); }
});
test('all four distinct original HTTP cases and matching final SQL sessions are mandatory', () => {
  for (const mutate of [f=>f.node.cases.pop(),f=>f.node.cases[2]=f.node.cases[1],f=>f.audit.sqlFacts[1].sessionId=99]) {
    const f=fixture(); mutate(f); assert.throws(()=>run(f));
  }
});
test('cancelled or revoked late answers, duplicate requests and wrong cancellation audit fail', () => {
  for (const mutate of [f=>f.audit.sqlFacts[1].answerRows=1,f=>f.audit.sqlFacts[3].answerMessageId=99,
    f=>f.audit.sqlFacts[1].requestRows=2,f=>f.audit.sqlFacts[1].cancellationAudits=2]) { const f=fixture(); mutate(f); assert.throws(()=>run(f)); }
});
test('physical close, original worker reuse, authentication fence and exact answer must remain', () => {
  for (const mutate of [f=>f.node.cases[1].physicalHttpClosedBeforeProviderRelease=false,f=>f.node.cases[1].sameWorkerReusedBeforeProviderRelease=false,
    f=>f.node.cases[3].oldTokensRejectedBothNodes=false,f=>f.audit.sqlFacts[0].exactNativeUnicodeAnswerVerified=false]) {
    const f=fixture(); mutate(f); assert.throws(()=>run(f));
  }
});
test('both actual node MySQL logs, ordered unique pool shutdown and independent cleanup are required', () => {
  for (const mutate of [f=>f.logs.pop(),f=>f.logs[0]=f.logs[0].replace('(MySQL 8.4)','(H2 2.2)'),
    f=>f.logs[1]=f.logs[1].replace('Shutdown completed.','Shutdown initiated.'),f=>f.audit.recordedJvmPidsVerifiedAbsent=false,
    f=>f.node.stoppedPids=[101]]) { const f=fixture(); mutate(f); assert.throws(()=>run(f)); }
});
test('post-summary or node shutdown errors and missing build success are never green', () => {
  for (const mutate of [f=>f.mavenLog+='ERROR shutdown failed',f=>f.logs[1]+='\n[ERROR] pool failed',
    f=>f.mavenLog=f.mavenLog.replace('BUILD SUCCESS','missing')]) { const f=fixture(); mutate(f); assert.throws(()=>run(f)); }
});

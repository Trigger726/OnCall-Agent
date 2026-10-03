const { test } = require('node:test');
const assert = require('node:assert/strict');
const { verify, suite, method, cases } = require('../verify-assistant-cross-node-mysql.cjs');

function fixture() {
  const database = { product: 'MySQL', version: '8.4.11', schema: 'opspilot_cross_node_012345abcdef' };
  const marker = 'CP83_CROSS_NODE_MYSQL_DATABASE ' + JSON.stringify(database);
  const logs = [1,2].map(n=>'INFO HikariPool-1 - Start completed.\nINFO Database: jdbc:mysql://localhost:33061/' + database.schema
    + ' (MySQL 8.4) node-' + n + '\nINFO HikariPool-1 - Shutdown completed.');
  const node = { status: 'PASS', baselineCapture: false, databaseMode: 'MYSQL_TESTCONTAINER', mysqlSchema: database.schema,
    providerCalls: 10, ownedProcessesStopped: true, ownedProviderStopped: true, unexpectedJarErrors: 0,
    startedPids: [101,102], stoppedPids: [102,101], applicationPorts: [9965,9967], managementPorts: [9966,9968], cases: [
      { name: cases[0], sessionId: 1, answerMessageId: 10, nodes: ['A','B'], modelCalls: 1, originalAnswerIdPreserved: true },
      { name: cases[1], sessionId: 2, duplicateStatus: 409, otherActorStatus: 404, cancellationAudits: 1, cancelledAnswers: 0,
        physicalHttpClosedBeforeProviderRelease: true, sameWorkerReusedBeforeProviderRelease: true, persistedStatusBothNodes: 'CANCELLED' },
      { name: cases[2], sessionId: 3, physicalHttpClosedBeforeProviderRelease: true, persistedStatus: 'SUPERSEDED', questionRows: 0, answerRows: 0 },
      { name: cases[3], sessionId: 4, physicalHttpClosedBeforeProviderRelease: true, sameWorkerReusedBeforeProviderRelease: true,
        oldTokensRejectedBothNodes: true, noPostRevocationPayload: true, persistedStatus: 'REVOKED' } ] };
  for (const i of [4,5]) node.cases.push({ name: cases[i], sessionId: i+1, executionNode: i===4?'A':'B', cancellationNode: i===4?'B':'A',
    persistedStatusBothNodes: 'CANCELLED', initialStatus: 'QUEUED', modelCalls: 0, questionRows: 0, answerRows: 0,
    cancellationAudits: 1, duplicateStatus: 409, otherActorStatus: 404, cancelledRetryStatus: 409,
    cancelledStreamEvent: true, tokenOrDoneSent: false, saturationStatus: 503, rejectedKeyStatus: 404,
    rejectedQuestionRows: 0, rejectedModelCalls: 0, occupyingModelStillOpenBeforeReplacement: true,
    freedQueueSlotReusedBeforeProviderRelease: true, rejectedSessionAndKeyReused: true, replacementStatusBeforeRelease: 'QUEUED',
    occupyingSessionId: 7+(i-4)*2, replacementSessionId: 8+(i-4)*2,
    occupyingAnswerMessageId: 20+(i-4)*2, replacementAnswerMessageId: 21+(i-4)*2,
    occupyingAndReplacementModelCalls: 2, lateCancelledModelCalls: 0 });
  node.streamAdmissionResponses = node.cases.slice(4).map(c=>({route:'/assistant/sessions/' + c.replacementSessionId + '/stream',status:503,contentType:'application/json;charset=UTF-8'}));
  const audit = { status: 'PASS', database, twoIndependentJvmsSameHost: true, crossMachineOrDatabaseHaClaimed: false,
    recordedJvmPidsVerifiedAbsent: true, fourScopedPortsVerifiedFree: true,
    nodeConnections: logs.map((l,i)=>({ node: i?'B':'A', pid: node.startedPids[i], flywayDatabaseLine: l.split('\n')[1], poolStartedAndStopped: true })),
    sqlFacts: cases.map((name,i)=>({ name, sessionId: i+1, requestRows: 1, status: ['COMPLETED','CANCELLED','SUPERSEDED','REVOKED','CANCELLED','CANCELLED'][i],
      userRows: i===2||i>=4?0:1, answerRows: i===0?1:0, answerMessageId: i===0?10:null,
      ...(i===0?{exactNativeUnicodeAnswerVerified:true}:{}), ...(i===1||i>=4?{cancellationAudits:1}:{}),
      ...(i>=4?{questionMessageId:null,completedControls:['occupying','replacement'].map(prefix=>({role:prefix,sessionId:node.cases[i][prefix+'SessionId'],
        status:'COMPLETED',requestRows:1,userRows:1,answerRows:1,answerMessageId:node.cases[i][prefix+'AnswerMessageId'],exactNativeUnicodeAnswerVerified:true}))}:{}) })) };
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
test('both directions must cancel actually queued work, never replace the original running cases', () => {
  for (const mutate of [f=>f.node.cases.pop(), f=>f.node.cases[5].executionNode='A', f=>f.node.cases[4].initialStatus='RUNNING']) {
    const f=fixture(); mutate(f); assert.throws(()=>run(f));
  }
});
test('queued cancellation forbids model calls, questions, answers and duplicate audits before and after release', () => {
  for (const mutate of [f=>f.node.cases[4].modelCalls=1, f=>f.node.cases[5].lateCancelledModelCalls=1,
    f=>f.audit.sqlFacts[4].userRows=1, f=>f.audit.sqlFacts[5].answerRows=1, f=>f.audit.sqlFacts[4].cancellationAudits=2,
    f=>f.audit.sqlFacts[5].questionMessageId=99,
    f=>f.node.cases[5].tokenOrDoneSent=true]) { const f=fixture(); mutate(f); assert.throws(()=>run(f)); }
});
test('capacity must be reclaimed with the original model held and the previously rejected key unchanged', () => {
  for (const key of ['occupyingModelStillOpenBeforeReplacement','freedQueueSlotReusedBeforeProviderRelease','rejectedSessionAndKeyReused']) {
    const f=fixture(); f.node.cases[4][key]=false; assert.throws(()=>run(f));
  }
  const f=fixture(); f.node.cases[5].replacementStatusBeforeRelease='COMPLETED'; assert.throws(()=>run(f));
});
test('saturation requires retained actual 503 JSON headers, unadmitted key and zero side effects', () => {
  for (const mutate of [f=>f.node.streamAdmissionResponses.pop(),f=>f.node.streamAdmissionResponses[0].contentType='text/event-stream',
    f=>f.node.streamAdmissionResponses[1].status=200, f=>f.node.cases[4].rejectedKeyStatus=200,
    f=>f.node.cases[5].rejectedQuestionRows=1,f=>f.node.cases[4].rejectedModelCalls=1]) {
    const f=fixture(); mutate(f); assert.throws(()=>run(f));
  }
});
test('four final positive SQL controls need distinct sessions, original answer IDs and exact native text', () => {
  for (const mutate of [f=>f.audit.sqlFacts[4].completedControls.pop(),f=>f.audit.sqlFacts[5].completedControls[0].status='QUEUED',
    f=>f.audit.sqlFacts[4].completedControls[1].answerMessageId=99,f=>f.audit.sqlFacts[5].completedControls[1].exactNativeUnicodeAnswerVerified=false,
    f=>{f.node.cases[4].occupyingSessionId=1;f.audit.sqlFacts[4].completedControls[0].sessionId=1;}]) {
    const f=fixture(); mutate(f); assert.throws(()=>run(f));
  }
});

const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),{createHash}=require('node:crypto');
const {caseNames,preservedKeys,oldSource}=require('./verify-oncall-open-publication-upgrade-http-ci.cjs');
const {assertMigrations}=require('./verify-oncall-open-handoff-http-ci.cjs');
const {unexpectedLogLines}=require('./verify-oncall-browser-ci.cjs');
function verify(result,read,mysql=true) {
  assert.equal(result.status,'PASS');assert.equal(result.oldSource,oldSource);
  for(const key of ['oldJarSha256','jarSha256'])assert.match(result[key],/^[a-f0-9]{64}$/);assert.notEqual(result.jarSha256,result.oldJarSha256);
  assert.equal(result.databaseMode,mysql?'MYSQL_TESTCONTAINER':'H2_OWNED_FILE');assert.equal(result.tokensPersistedToEvidence,false);
  assert.equal(result.userFileDatabaseModified,false);assert.equal(result.actualDeliveryVerified,false);assert.equal(result.remindersVerified,false);
  assert.deepEqual(result.cases.map(c=>c.name),caseNames);assert.equal(result.sqlFixtures.length,4);
  const [before,upgraded,committed,final]=result.sqlFixtures;
  for(const [i,s] of result.sqlFixtures.entries()) {
    assertMigrations(s,i===0?39:40);assert.equal(s.actualProduct,mysql?'MySQL':'H2');assert.match(s.actualVersion,mysql?/^8\.4\./:/^2\.2\.220/);
    if(mysql){assert.equal(s.ownerConfirmed,true);assert.equal(s.schema,result.mysqlSchema);assert.match(s.schema,/^opspilot_publication_upgrade_[a-f0-9]{12}$/);assert.equal(s.serverUuid,before.serverUuid);}
    assert.deepEqual(JSON.parse(read('sql-'+i+'.log').trim()),s);
  }
  assert.deepEqual(result.cases[0].before,before);assert.deepEqual(result.cases[1].upgraded,upgraded);
  assert.deepEqual(result.cases[4].committed,committed);assert.deepEqual(result.cases[5].final,final);
  assert.equal(before.requests.length,2);assert.deepEqual(before.requests.map(r=>r.status).sort(),['OPEN','WITHDRAWN']);
  assert.ok(before.members.length>0);assert.ok(before.members.some(m=>m.userId===3&&m.version===1&&!m.canRespond));
  assert.equal(before.publicationRows,0);assert.equal(upgraded.publicationRows,0);assert.equal(before.publicationHash,null);
  for(const key of preservedKeys){assert.match(before[key],/^[a-f0-9]{64}$/);assert.equal(before[key],upgraded[key]);}
  assert.deepEqual(before.requests,upgraded.requests);assert.deepEqual(before.members,upgraded.members);
  const legacy=result.cases[1].legacyPublication;assert.equal(legacy.publicationSnapshotAvailable,false);assert.equal(legacy.publication,null);assert.deepEqual(legacy.eligibleOriginalRecipientIds,[]);
  const {first,later,second}=result.cases[2],{revoked,publisherRevoked,ended}=result.cases[3];
  assert.deepEqual(first.publication.recipients.map(r=>r.userId),[1]);assert.deepEqual(second.publication.recipients.map(r=>r.userId),[1,3]);
  assert.deepEqual(later.publication,first.publication);assert.deepEqual(later.eligibleOriginalRecipientIds,[1]);
  assert.deepEqual(revoked.publication,second.publication);assert.deepEqual(revoked.eligibleOriginalRecipientIds,[1]);
  assert.deepEqual(publisherRevoked.eligibleOriginalRecipientIds,[]);assert.deepEqual(publisherRevoked.publication,second.publication);
  assert.equal(ended.currentRequestStatus,'WITHDRAWN');assert.equal(ended.currentRequestVersion,1);assert.deepEqual(ended.eligibleOriginalRecipientIds,[]);assert.deepEqual(ended.publication,second.publication);
  assert.equal(committed.publicationRows,2);assert.equal(committed.publications.length,2);assert.equal(committed.requests.length,4);
  for(const view of [first,later,second,revoked,publisherRevoked,ended,...result.cases[5].restarted]) {
    assert.equal(view.publicationSnapshotAvailable,true);assert.equal(view.deliveryImplemented,false);assert.equal(view.publication.eventVersion,0);
    assert.equal(view.publication.timeBasis,'DATABASE_SESSION_LOCAL');assert.ok(view.publication.recipients.every(r=>r.userId!==view.publication.requesterId));
    const stored=committed.publications.find(p=>p.handoffId===view.publication.handoffId);assert.ok(stored);assert.equal(stored.eventVersion,0);
    assert.deepEqual(JSON.parse(stored.snapshotJson),view.publication);
    assert.doesNotMatch(JSON.stringify(view.publication),/requestKey|operationKey|password|accessToken|Authorization|reason/);
  }
  assert.deepEqual(result.cases[5].restarted.map(v=>v.publication),[first.publication,second.publication]);
  assert.deepEqual(result.cases[5].restarted.map(v=>v.eligibleOriginalRecipientIds),[[],[]]);
  assert.deepEqual(final,committed);assert.equal(final.v39MigrationHash,before.v39MigrationHash);
  assert.ok(result.httpObservations.some(h=>h.route.endsWith('/publication')&&h.status===401&&h.method==='GET'));
  assert.ok(result.httpObservations.some(h=>h.route==='/on-call/open-handoffs/'+result.cases[0].legacyId+'/publication'&&h.status===404));
  assert.ok(result.httpObservations.filter(h=>h.route.endsWith('/publication')&&h.status===200).length>=10);
  assert.equal(result.startedPids.length,4);assert.equal(new Set(result.startedPids).size,4);
  assert.deepEqual(result.stoppedPids,result.startedPids);assert.equal(result.ownedProcessesStopped,true);assert.equal(result.unexpectedJarErrors,0);
  assert.deepEqual(result.shutdowns,result.startedPids.map(pid=>({pid,httpStatus:200,graceful:true})));assert.equal(result.completeJarLogs.length,4);
  for(const [i,receipt] of result.completeJarLogs.entries()) {
    assert.equal(path.basename(receipt.file),'jar-'+(i+1)+'.log');const log=read('jar-'+(i+1)+'.log');
    assert.equal(createHash('sha256').update(log).digest('hex'),receipt.sha256);assert.equal(unexpectedLogLines(log),0);
    const start='HikariPool-1 - Start completed.',stop='HikariPool-1 - Shutdown completed.';
    assert.equal(log.split(start).length,2);assert.equal(log.split(stop).length,2);assert.ok(log.indexOf(stop)>log.indexOf(start));
    assert.match(log,mysql?/Database: jdbc:mysql:\/\/(?:localhost|127\.0\.0\.1):\d+\/opspilot_publication_upgrade_[a-f0-9]{12}.*\(MySQL 8\.4\)/:/Database: jdbc:h2:file:.*\/database\/opspilot \(H2 2\.2\)/);
    if(mysql)assert.ok(log.includes('/'+result.mysqlSchema));
  }
  return {status:'PASS',cases:6,database:before.actualProduct,original39ChecksumsPreserved:true,nonemptyBusinessUpgradeVerified:true,
    actualPublicationTcpVerified:true,storedPayloadsMatchHttp:2,ownedGracefulJvmShutdowns:4,restartPreserved:true,actualDeliveryVerified:false,remindersVerified:false};
}
module.exports={verify};
if(require.main===module){try {
  const folder=path.resolve(process.argv[2]||''),root=path.resolve(__dirname,'..');assert.ok(folder.startsWith(path.join(root,'target')+path.sep));
  const result=JSON.parse(fs.readFileSync(path.join(folder,'result.json'),'utf8'));
  const proof=verify(result,name=>fs.readFileSync(path.join(folder,name),'utf8'),process.argv[3]!=='H2');
  fs.writeFileSync(path.join(folder,'publication-upgrade-proof.json'),JSON.stringify(proof,null,2)+'\n');console.log(JSON.stringify(proof));
}catch(e){console.error('Publication upgrade evidence rejected: '+e.message);process.exitCode=1;}}

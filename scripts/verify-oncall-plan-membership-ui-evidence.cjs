const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const {caseNames}=require('./verify-oncall-plan-membership-ui-ci.cjs');
function verify(result,log){
  assert.equal(result.status,'PASS');assert.equal(result.baseline,false);assert.deepEqual(result.cases.map(c=>c.name),caseNames);
  for(const key of ['ownedProcessStopped','portsFree','completePoolShutdown'])assert.equal(result[key],true);
  assert.equal(result.tokensPersistedToEvidence,false);assert.equal(result.userDatabaseModified,false);assert.equal(result.unexpectedJarErrors,0);assert.deepEqual(result.pageErrors,[]);
  assert.match(result.jarSha256,/^[a-f0-9]{64}$/);assert.ok(Number.isSafeInteger(result.startedPid)&&result.startedPid>1);assert.deepEqual(result.viewports,['1440x1000','390x844']);
  assert.match(result.browserPath,/Browser plugin not available/);
  const screenshots=['after-first-desktop.png','after-desktop.png','after-first-mobile.png','after-mobile.png','original-vs-current-desktop.png','locked-original-mobile.png','frozen-original-mobile.png'];
  assert.deepEqual(result.screenshots,screenshots);assert.deepEqual(result.visualBounds.map(b=>b.name),screenshots);for(const b of result.visualBounds){assert.equal(b.width,b.name.includes('mobile')?390:1440);assert.equal(b.documentWidth,b.width);}
  for(const i of [2,3,4,9,10])assert.equal(result.cases[i].posts,0);
  const lost=result.cases[5];assert.equal(lost.actualForwardedCommit200,true);assert.equal(lost.browserRequestAborted,true);assert.equal(lost.posts,1);assert.ok(Number.isInteger(lost.originalCommand.expectedVersion));assert.equal(lost.currentVersion,lost.originalCommand.expectedVersion+2);
  assert.equal(result.cases[6].ownPlanManagementRemoved,true);assert.equal(result.cases[6].originalReadOnlyAck200,true);
  assert.equal(result.cases[7].actual409,409);assert.equal(result.cases[7].versionNotRebased,true);assert.equal(result.cases[8].actual403,403);
  for(const [i,fact]of [[11,'originalPreserved'],[12,'newActorDraftPreserved'],[13,'newTokenIdentityProtected']]){assert.equal(result.cases[i].committedLate200,true);assert.equal(result.cases[i][fact],true);}
  for(const c of result.console){assert.match(c.text,/^Failed to load resource:/);assert.match(c.url,/\/api\/v1\/on-call\/schedules\/1\/members$/);}
  for(const e of result.expectedRejections){assert.equal(e.path,'/api/v1/on-call/schedules/1/members');assert.ok([403,409,500].includes(e.status));}
  const start='HikariPool-1 - Start completed.',stop='HikariPool-1 - Shutdown completed.';assert.equal(log.split(start).length,2);assert.equal(log.split(stop).length,2);assert.ok(log.indexOf(stop)>log.indexOf(start));assert.doesNotMatch(log,/\sERROR\s|Unhandled request error/);
  return{status:'PASS',browserCases:14,viewports:result.viewports,separateOriginalReceiptAndCurrentVersion:true,threeLateResponseIdentityFences:true,actual403And409:true,allScreenshotsWidthVerified:true,completeOwnedJvmPoolShutdown:true,realMysqlUiVerified:false,fullMembershipDeliveryVerified:false};
}
module.exports={verify};
if(require.main===module){try{const folder=path.resolve(process.argv[2]||'');assert.ok(folder.startsWith(path.resolve(__dirname,'../target/oncall-plan-membership-ui-it')+path.sep));const result=JSON.parse(fs.readFileSync(path.join(folder,'result.json'),'utf8')),gate=verify(result,fs.readFileSync(path.join(folder,'jar.log'),'utf8'));for(const name of result.screenshots)assert.ok(fs.statSync(path.join(folder,name)).size>1000);fs.writeFileSync(path.join(folder,'gate-result.json'),JSON.stringify(gate,null,2)+'\n');console.log(JSON.stringify(gate));}catch(e){console.error('Member UI gate failed: '+e.message);process.exitCode=1;}}

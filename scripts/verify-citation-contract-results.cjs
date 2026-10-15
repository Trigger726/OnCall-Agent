const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto'),assert=require('node:assert/strict');
const names=['readsExactPersistedChunkWithoutTruncationOrHtmlExecution','oldCitationsRemainOnOriginalVersionAfterPublishingReplacement',
  'checksEachVersionsCurrentAclAndDoesNotRevealMissingVersusDenied','draftRejectedAndUnknownChunksAreNotPublishedSources',
  'requiresAuthenticationSupportedRoleAndValidCoordinates','rechecksCurrentRoleAndRevokedAccountUsingTheSamePreviouslyValidToken'].sort();
const attr=(text,key)=>new RegExp('\\b'+key+'="([^"]*)"').exec(text)?.[1];
function verifyResults(folder,startedAt){
  assert.ok(Number.isFinite(startedAt)&&startedAt>0,'Missing fresh-run start time');
  const suites=['RunbookCitationIntegrationTest','MySqlRunbookCitationIntegrationTest'].map(name=>{
    const file=path.join(folder,'TEST-org.trigger.opspilot.runbook.'+name+'.xml');assert.ok(fs.statSync(file).mtimeMs>=startedAt,'Stale XML: '+name);
    const xml=fs.readFileSync(file,'utf8'),header=/<testsuite\b[^>]*>/.exec(xml)?.[0];assert.ok(header,'Missing suite: '+name);
    assert.equal(attr(header,'name'),'org.trigger.opspilot.runbook.'+name);assert.equal(Number(attr(header,'tests')),6);
    for(const key of ['failures','errors','skipped'])assert.equal(Number(attr(header,key)),0,name+' '+key);
    const cases=[...xml.matchAll(/<testcase\b([^>]*)>/g)].map(match=>attr(match[1],'name')).sort();assert.deepEqual(cases,names);
    const product=name.startsWith('MySql')?'MySQL':'H2';assert.ok(xml.includes('CITATION_DATABASE '+name+'|'+product+'|'),'Missing actual JDBC product proof: '+name);
    return {name,product,tests:6,failures:0,errors:0,skipped:0,cases,xmlSha256:crypto.createHash('sha256').update(xml).digest('hex')};
  });
  return {status:'PASS',tests:12,sameScenarios:true,suites};
}
module.exports={verifyResults,names};
if(require.main===module){const result=verifyResults(process.argv[2],Number(fs.readFileSync(process.argv[3],'utf8')));result.sourceCommit=process.env.GITHUB_SHA??null;fs.writeFileSync(process.argv[4],JSON.stringify(result,null,2)+'\n');console.log(JSON.stringify({status:result.status,tests:result.tests,sameScenarios:result.sameScenarios,sourceCommit:result.sourceCommit}));}

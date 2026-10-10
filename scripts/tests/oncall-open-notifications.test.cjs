const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const {verify,suite}=require('../verify-oncall-open-notifications.cjs'),{assertMigrations}=require('../verify-oncall-open-handoff-http-ci.cjs');
function fixture(mysql=false){const folder=path.resolve(__dirname,'../../docs/assets/v1.7-cp117/notification-h2');
  let log=fs.readFileSync(folder+'/maven.log','utf8'),xml=fs.readFileSync(folder+'/TEST-'+suite.replace('MySqlOnCall','OnCall')+'.xml','utf8');
  // MySQL substitution is only a gate-shape unit fixture, never actual MySQL delivery evidence.
  if(mysql)for(const [before,after] of [[suite.replace('MySqlOnCall','OnCall'),suite],['"product":"H2"','"product":"MySQL"'],
    ['"version":"2.2.220 (2023-07-04)"','"version":"8.4.11"'],['"schema":"opspilot-open-notification-test"','"schema":"opspilot_open_notification_test"']]){log=log.replaceAll(before,after);xml=xml.replaceAll(before,after);}
  return {log,xml,mysql};}
const run=f=>verify(f.log,f.xml,f.mysql);
test('saved actual H2 transport data independently replays 22 cases and fourteen authenticated requests',()=>{assert.equal(run(fixture()).observedAuthenticatedHttpRequests,14);});
test('synthetic MySQL gate shape is not actual MySQL; H2 can never substitute',()=>{assert.equal(run(fixture(true)).database,'MySQL');const f=fixture();assert.throws(()=>verify(f.log,f.xml,true));});
test('omitted skipped renamed failed or truncated real JUnit is rejected',()=>{for(const change of [f=>f.xml=f.xml.replace('tests="22"','tests="21"'),f=>f.xml=f.xml.replace('skipped="0"','skipped="1"'),f=>f.xml=f.xml.replace('shouldSkipInactivePlan','renamed'),f=>f.xml=f.xml.replace('</testsuite>',''),f=>f.log+='\n ERROR failure']){const f=fixture(true);change(f);assert.throws(()=>run(f));}});
test('actual product exact schema migration and identity must match XML and Maven',()=>{for(const change of [f=>f.xml=f.xml.replace('8.4.11','8.0.1'),f=>f.xml=f.xml.replace('opspilot_open_notification_test','production'),f=>f.xml=f.xml.replace('"migration41":true','"migration41":false'),f=>f.log=f.log.replace('8.4.11','8.4.12')]){const f=fixture(true);change(f);assert.throws(()=>run(f));}});
test('invented missing HTTP observations auth or keys cannot pass green suite counts',()=>{for(const change of [f=>f.xml=f.xml.replace('"httpCalls":2','"httpCalls":0'),f=>f.xml=f.xml.replace('oncall-open-notification:','other:'),f=>f.xml=f.xml.replace('"authenticatedRequests":2','"authenticatedRequests":0'),f=>f.xml=f.xml.replace('"eventVersion":0','"eventVersion":1')]){const f=fixture(true);change(f);assert.throws(()=>run(f));}});
test('truncated pool shutdown or extra unexpected errors are rejected',()=>{for(const change of [f=>f.log=f.log.replace('Shutdown completed.','Shutdown initiated.'),f=>f.log+='\nWARN Surefire is going to kill']){const f=fixture(true);change(f);assert.throws(()=>run(f));}});
test('new V41 boundaries remain explicit; old V40 cannot pass current gate nor arbitrary future versions',()=>{
  const s={actualProduct:'MySQL',migrationCount:41,successfulHistoryRows:41,migration38:true,migration39:true,migration40:true,migration41:true,
    history:Array.from({length:41},(_,i)=>({version:String(i+1),type:[29,33].includes(i+1)?'JDBC':'SQL',success:true}))};assertMigrations(s,41);
  for(const change of [x=>delete x.migration41,x=>x.migration41=false,x=>x.history.pop(),x=>x.migrationCount=40,x=>x.history[40].type='JDBC']){const x=structuredClone(s);change(x);assert.throws(()=>assertMigrations(x,41));}assert.throws(()=>assertMigrations(s,42));
});
test('WIP adds owned real notification MySQL without removing the six existing jobs',()=>{
  const yaml=fs.readFileSync(path.resolve(__dirname,'../../.github/workflows/plan-membership-wip.yml'),'utf8');
  assert.match(yaml,/-Dtest=MySqlOnCallOpenNotificationIntegrationTest test/);assert.match(yaml,/node scripts\/verify-oncall-open-notifications.cjs/);
  for(const name of ['membership-mysql','membership-ui','membership-cross-node-mysql','membership-upgrade-mysql','open-recipient-mysql','publication-upgrade-mysql'])assert.ok(yaml.includes('  '+name+':'));
  assert.doesNotMatch(yaml,/continue-on-error|git reset|git checkout/);
});

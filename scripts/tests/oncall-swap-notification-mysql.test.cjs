const {test}=require('node:test');
const assert=require('node:assert/strict');
const {verify,suite,cases}=require('../verify-oncall-swap-notification-mysql.cjs');
function fixture(){const markers=cases.map(name=>'CP87_SWAP_NOTIFICATION_DATABASE '+JSON.stringify({case:name,product:'MySQL',version:'8.4.11',schema:'opspilot_swap_notification_test',migration36:true}));
  return {xml:`<testsuite name="${suite}" tests="${cases.length}" failures="0" errors="0" skipped="0">`+cases.map((name,i)=>`<testcase name="${name}"><system-out><![CDATA[${markers[i]}]]></system-out></testcase>`).join('')+'</testsuite>',
    log:'INFO HikariPool-1 - Start completed.\n'+markers.join('\n')+'\nINFO HikariPool-1 - Shutdown completed.\n[INFO] BUILD SUCCESS\n'};}
const run=f=>verify(f.log,f.xml);
test('synthetic gate fixture is shape proof only, never actual product acceptance',()=>assert.equal(run(fixture()).executed,27));
test('skipped or failed suites cannot pass',()=>{for(const [key,value] of [['skipped','27'],['tests','0'],['errors','1'],['failures','1']]){const f=fixture();f.xml=f.xml.replace(`${key}="${key==='tests'?cases.length:0}"`,`${key}="${value}"`);assert.throws(()=>run(f));}});
test('each actual case requires its own matching MySQL 8.4 owned-schema marker',()=>{for(const from of ['CP87_SWAP_NOTIFICATION_DATABASE','MySQL','8.4.11','opspilot_swap_notification_test']){const f=fixture();f.xml=f.xml.replace(from,'wrong');assert.throws(()=>run(f));}});
test('missing or duplicate lease, worker and rollback cases cannot hide behind counts',()=>{for(const index of [2,9,10,13,17]){const f=fixture();f.xml=f.xml.replace(`name="${cases[index]}"`,`name="${cases[0]}"`);assert.throws(()=>run(f));}});
test('log markers cannot differ from XML or use a global JDBC claim',()=>{for(const from of ['CP87_SWAP_NOTIFICATION_DATABASE','MySQL','8.4.11','opspilot_swap_notification_test']){const f=fixture();f.log=f.log.replace(from,'wrong');assert.throws(()=>run(f));}});
test('complete logs reject post-summary shutdown errors',()=>{for(const line of ['ERROR shutdown failed','[ERROR] fork failed','WARN Surefire is going to kill','WARN Communications link failure','native thread creation failed']){const f=fixture();f.log+=line;assert.throws(()=>run(f));}});
test('no missing duplicate early or mismatched shutdown',()=>{for(const mutate of [f=>f.log=f.log.replace('Shutdown completed.','Shutdown initiated.'),f=>f.log+='HikariPool-1 - Shutdown completed.',f=>f.log=f.log.replace('Start completed.','Shutdown completed.'),f=>f.log=f.log.replace('HikariPool-1 - Shutdown','HikariPool-2 - Shutdown')]){const f=fixture();mutate(f);assert.throws(()=>run(f));}});
test('truncation and missing build success fail closed',()=>{for(const mutate of [f=>f.xml=f.xml.replace('</testsuite>',''),f=>f.log=f.log.replace('BUILD SUCCESS','missing')]){const f=fixture();mutate(f);assert.throws(()=>run(f));}});
test('ANSI CRLF handling preserves evidence assertions',()=>{const f=fixture();f.log='\u001b[32m'+f.log.replaceAll('\n','\r\n')+'\u001b[0m';assert.equal(run(f).status,'PASS');});
test('retention and original cases are all mandatory and V36 is observed per test',()=>{
  assert.equal(cases.length,27);
  for(let i=18;i<cases.length;i++){const f=fixture();f.xml=f.xml.replace(`name="${cases[i]}"`,`name="${cases[0]}"`);assert.throws(()=>run(f));}
  for(const value of [false,null,'true']){const f=fixture();f.xml=f.xml.replace('"migration36":true',`"migration36":${JSON.stringify(value)}`);assert.throws(()=>run(f));}
});

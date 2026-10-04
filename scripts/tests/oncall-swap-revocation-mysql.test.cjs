const {test}=require('node:test');
const assert=require('node:assert/strict');
const {verify,suite,cases}=require('../verify-oncall-swap-revocation-mysql.cjs');
function fixture(){
  const markers=cases.map(name=>'CP92_SWAP_REVOCATION_DATABASE '+JSON.stringify({case:name,product:'MySQL',version:'8.4.11',schema:'opspilot_swap_revocation_test',migration37:true}));
  const xml='<testsuite name="'+suite+'" tests="19" failures="0" errors="0" skipped="0">'+cases.map((name,i)=>'<testcase name="'+name+'"><system-out><![CDATA['+markers[i]+']]></system-out></testcase>').join('')+'</testsuite>';
  return {xml,log:'INFO HikariPool-1 - Start completed.\n'+markers.join('\n')+'\nINFO HikariPool-1 - Shutdown completed.\n[INFO] BUILD SUCCESS\n'};
}
const run=f=>verify(f.log,f.xml);
test('synthetic complete MySQL gate shape passes, not a product claim',()=>assert.equal(run(fixture()).executed,19));
test('default conditional skips and zero executions cannot pass',()=>{for(const [key,value] of [['skipped','19'],['tests','0'],['errors','1'],['failures','1']]){const f=fixture();f.xml=f.xml.replace(key+'="'+(key==='tests'?'19':'0')+'"',key+'="'+value+'"');assert.throws(()=>run(f));}});
test('each actual testcase needs its own matching JDBC identity',()=>{for(const mutate of [f=>f.xml=f.xml.replace('CP92_SWAP_REVOCATION_DATABASE','missing'),f=>f.xml=f.xml.replace('8.4.11','8.0.1'),f=>f.xml=f.xml.replace('MySQL','H2'),f=>f.xml=f.xml.replace('opspilot_swap_revocation_test','production')]){const f=fixture();mutate(f);assert.throws(()=>run(f));}});
test('missing or duplicate concurrency and rollback cases cannot hide behind green counts',()=>{for(const name of [cases[12],cases[13],cases[14],cases[15],cases[17],cases[18]]){const f=fixture();f.xml=f.xml.replace('name="'+name+'"','name="'+cases[0]+'"');assert.throws(()=>run(f));}});
test('Maven and testcase markers must agree without only a global label',()=>{for(const mutate of [f=>f.log=f.log.replace('8.4.11','8.4.12'),f=>f.log=f.log.replace('CP92_SWAP_REVOCATION_DATABASE','missing')]){const f=fixture();mutate(f);assert.throws(()=>run(f));}});
test('post-summary shutdown failures are rejected',()=>{for(const line of ['ERROR shutdown failed','[ERROR] fork failed','WARN Surefire is going to kill','WARN Communications link failure','native thread creation failed']){const f=fixture();f.log+=line;assert.throws(()=>run(f));}});
test('missing duplicate early or incomplete pool shutdown cannot pass',()=>{for(const mutate of [f=>f.log=f.log.replace('Shutdown completed.','Shutdown initiated.'),f=>f.log+='HikariPool-1 - Shutdown completed.',f=>f.log=f.log.replace('Start completed.','Shutdown completed.')]){const f=fixture();mutate(f);assert.throws(()=>run(f));}});
test('missing build success and truncated XML fail closed',()=>{for(const mutate of [f=>f.log=f.log.replace('BUILD SUCCESS','missing'),f=>f.xml=f.xml.replace('</testsuite>','')]){const f=fixture();mutate(f);assert.throws(()=>run(f));}});
test('ANSI and CRLF cannot bypass or spuriously fail the complete gate',()=>{const f=fixture();f.log='\u001b[32m'+f.log.replaceAll('\n','\r\n')+'\u001b[0m';assert.equal(run(f).status,'PASS');});


test('each case must positively assert the actual V37 migration',()=>{const f=fixture();f.xml=f.xml.replace('"migration37":true','"migration37":false');assert.throws(()=>run(f));});

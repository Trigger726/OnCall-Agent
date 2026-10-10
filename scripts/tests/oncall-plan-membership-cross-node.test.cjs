const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const {verify,suite,caseNames}=require('../verify-oncall-plan-membership-cross-node.cjs');
const directory=path.resolve(__dirname,'../../docs/assets/v1.7-cp112/h2-cross-node');
function h2(){return {log:fs.readFileSync(directory+'/maven.log','utf8'),xml:fs.readFileSync(directory+'/TEST-'+suite.replace('MySqlOnCall','OnCall')+'.xml','utf8'),result:JSON.parse(fs.readFileSync(directory+'/result.json','utf8')),logs:[1,2,3].map(n=>fs.readFileSync(directory+'/jar-'+n+'.log','utf8'))};}
function fixture(){const f=h2();f.xml=f.xml.replaceAll(suite.replace('MySqlOnCall','OnCall'),suite);
 f.result.database={product:'MySQL',version:'8.4.11',schema:'opspilot_member_cross_abcdef123456',serverUuid:'12345678-1234-1234-1234-123456789abc'};
 f.logs=f.logs.map(log=>log+'\nDatabase: jdbc:mysql://127.0.0.1:3306/'+f.result.database.schema+' (MySQL 8.4)');return f;}
const run=(f,mysql=true)=>verify(f.log,f.xml,f.result,f.logs,mysql);
function rejects(mutations){for(const change of mutations){const f=fixture();change(f);assert.throws(()=>run(f));}}
test('saved actual H2 TCP two-JVM run replays but does not become an actual MySQL run',()=>{assert.equal(run(h2(),false).httpCases,6);assert.throws(()=>run(h2()));});
test('synthetic MySQL gate shape is explicitly only a gate unit fixture',()=>{assert.equal(run(fixture()).database.product,'MySQL');assert.equal(run(fixture()).membershipUiVerified,false);});
test('wrong suite skips zero tests failed truncated Maven and wrong JDBC identity are rejected',()=>rejects([
 f=>f.xml=f.xml.replace('tests="1"','tests="0"'),f=>f.xml=f.xml.replace('skipped="0"','skipped="1"'),f=>f.xml=f.xml.replace(suite,'WrongSuite'),
 f=>f.log=f.log.replace('BUILD SUCCESS','BUILD FAILURE'),f=>f.result.database.product='H2',f=>f.result.database.schema='production',f=>f.result.database.serverUuid='-'.repeat(36),f=>f.result.versionedMigrations=38
]));
test('all six case IDs and every primary invariant are mandatory even when JSON otherwise says PASS',()=>rejects([
 f=>f.result.cases.pop(),f=>f.result.cases[0].name='renamed',f=>delete f.result.cases[0].oneReceipt,f=>f.result.cases[1].noWrites=false,
 f=>delete f.result.cases[2].bodyNotRead,f=>f.result.cases[3].roleRestorationDoesNotRestorePlanManagement=false,f=>f.result.cases[4].plan2QualifiedControl200=false,f=>f.result.cases[5].oneReplacement=false
]));
test('independent final JDBC counts and original receipt identity cannot be substituted',()=>rejects([
 f=>f.result.finalSql.requests=0,f=>f.result.finalSql.memberOperations=6,f=>f.result.cases[2].originalOperation.actorId=1,f=>f.result.cases[2].originalOperation.capturedVersion=1,f=>f.result.cases[2].originalOperation.handoffId=0
]));
test('three real JVM lifecycles two concurrent applications ports pools and owned database exit are required',()=>rejects([
 f=>f.result.startedPids[1]=f.result.startedPids[0],f=>f.result.startedPids[0]=0,f=>f.result.twoApplicationJvmsObservedAliveTogether=false,
 f=>f.result.allApplicationPidsAbsent=false,f=>f.result.portsFree=false,f=>f.result.databaseOwnerStopped=false,f=>f.result.pools[0].completePoolShutdown=false,
 f=>f.logs.pop(),f=>f.logs[0]=f.logs[0].replace('Shutdown completed.','Shutdown initiated.'),f=>f.logs[0]+='\n ERROR shutdown failure'
]));
test('same inherited HTTP cases preserve independent processes real sockets and captured-key barriers without production bypass',()=>{
 const source=fs.readFileSync(path.resolve(__dirname,'../../src/test/java/org/trigger/opspilot/oncall/PlanMembershipCrossNodeScenarios.java'),'utf8');
 for(const name of caseNames)assert.ok(source.includes(name));assert.ok(source.includes('new ProcessBuilder(command)'));assert.ok(source.includes('CountDownLatch(2)'));assert.ok(source.includes('socket.setSoLinger(true,0)'));
 assert.ok(source.includes('"--management.server.address=127.0.0.1"'));assert.ok(source.includes('header("Authorization","Bearer "+token)'));assert.ok(source.indexOf('for(var child:children)stop(child)')<source.indexOf('owned.close()'));
 assert.doesNotMatch(source,/@Mock|@Spy|Thread\.sleep\(\d{4,}\)/);
});

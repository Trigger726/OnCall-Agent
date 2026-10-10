const {test}=require('node:test'),assert=require('node:assert/strict');
const fs=require('node:fs'),path=require('node:path');
const {configuration,caseNames,assertSqlRows,assertMigrations}=require('../verify-oncall-open-handoff-http-ci.cjs');
test('archived open handoff upgrade cannot substitute for own-source CI',()=>assert.throws(()=>configuration({CI:'true',OPSPILOT_OPEN_HANDOFF_UPGRADE:'1'})));
test('local populated upgrade and complete fresh-source acceptance stay separate',()=>{
  assert.deepEqual(configuration({CI:'true'}),{comparison:false});assert.deepEqual(configuration({OPSPILOT_OPEN_HANDOFF_UPGRADE:'1'}),{comparison:true});});
test('the full real HTTP case set includes loss restart concurrency consent time and current role',()=>{
  assert.equal(caseNames.length,8);assert.equal(new Set(caseNames).size,8);
  for(const fragment of ['consent','response-loss','restart','one-winner','withdrawal','changed-source','subwindow','role-loss'])assert.ok(caseNames.some(name=>name.includes(fragment)));});
test('synthetic SQL equality shape rejects missing extra duplicated and half-committed rows',()=>{
  const expected=[{id:1,status:'CLAIMED',operations:1,audits:2,overrides:1},{id:2,status:'OPEN',operations:0,audits:1,overrides:0}];
  assertSqlRows(expected,expected);for(const actual of [expected.slice(0,1),[...expected,expected[0]],[expected[0],expected[0]],[{...expected[0],operations:0},expected[1]],[{...expected[0],audits:3},expected[1]]])assert.throws(()=>assertSqlRows(actual,expected));});
test('migration evidence includes TABLE and both JDBC migrations without confusing history count with version',()=>{
  const f=()=>({migrationCount:38,successfulHistoryRows:39,migration38:true,migration39:false,migration40:false,history:[{version:null,type:'TABLE',success:true},
    ...Array.from({length:38},(_,i)=>({version:String(i+1),type:[29,33].includes(i+1)?'JDBC':'SQL',success:true}))]});
  assertMigrations(f(),38);
  for(const mutate of [x=>x.migrationCount=39,x=>x.history.splice(29,1),x=>x.history[33].type='SQL',x=>x.history[38].success=false,x=>x.history[38].version='37',x=>x.migration38=false]){
    const x=f();mutate(x);assert.throws(()=>assertMigrations(x,38));}});
test('current V39 requires all 39 exact versions and both explicit migration markers',()=>{
  const f=()=>({actualProduct:'MySQL',migrationCount:39,successfulHistoryRows:39,migration38:true,migration39:true,migration40:false,
    history:Array.from({length:39},(_,i)=>({version:String(i+1),type:[29,33].includes(i+1)?'JDBC':'SQL',success:true}))});
  assertMigrations(f(),39);
  for(const mutate of [x=>x.migration39=false,x=>x.migration38=false,x=>x.history.pop(),x=>x.history[38].type='JDBC',x=>x.history[38].success=false,x=>x.migrationCount=38]){
    const x=f();mutate(x);assert.throws(()=>assertMigrations(x,39));}
  assert.throws(()=>assertMigrations(f(),40));
});
test('current V40 requires exact 40 versions and rejects missing markers or unexpected 41',()=>{
  const f=()=>({actualProduct:'MySQL',migrationCount:40,successfulHistoryRows:40,migration38:true,migration39:true,migration40:true,
    history:Array.from({length:40},(_,i)=>({version:String(i+1),type:[29,33].includes(i+1)?'JDBC':'SQL',success:true}))});
  assertMigrations(f(),40);
  for(const mutate of [x=>delete x.migration40,x=>x.migration40=false,x=>x.migration39=false,x=>x.history.pop(),x=>x.history[39].type='JDBC',x=>x.history[39].success=false,x=>x.migrationCount=39]){const x=f();mutate(x);assert.throws(()=>assertMigrations(x,40));}
  assert.throws(()=>assertMigrations(f(),41));
});
test('own-source CI runs the full HTTP command and retains actual logs without archive mode',()=>{
  const yaml=fs.readFileSync(path.join(__dirname,'../../.github/workflows/ci.yml'),'utf8');
  const block=yaml.match(/  oncall-open-handoff-http-integration:([\s\S]*?)(?=\n  [\w-]+:)/)[1];
  assert.match(block,/node scripts\/verify-oncall-open-handoff-http-ci\.cjs/);assert.match(block,/oncall-open-handoff-http-evidence/);
  assert.doesNotMatch(block,/OPSPILOT_OPEN_HANDOFF_UPGRADE|continue-on-error/);
  assert.match(yaml,/needs: \[[^\n]*oncall-open-handoff-http-integration/);});

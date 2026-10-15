const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),os=require('node:os'),path=require('node:path');
const {verifyResults,names}=require('../verify-citation-contract-results.cjs');
function fixture(){const folder=fs.mkdtempSync(path.join(os.tmpdir(),'citation-gate-'));const start=Date.now()-2000;
  for(const name of ['RunbookCitationIntegrationTest','MySqlRunbookCitationIntegrationTest']){const product=name.startsWith('MySql')?'MySQL':'H2';fs.writeFileSync(path.join(folder,'TEST-org.trigger.opspilot.runbook.'+name+'.xml'),`<testsuite name="org.trigger.opspilot.runbook.${name}" tests="6" failures="0" errors="0" skipped="0">${names.map(name=>`<testcase name="${name}"/>`).join('')}<system-out>CITATION_DATABASE ${name}|${product}|controlled</system-out></testsuite>`);}
  return {folder,start,file:path.join(folder,'TEST-org.trigger.opspilot.runbook.MySqlRunbookCitationIntegrationTest.xml')};}
test('accepts only both fresh, identical six-case products',()=>{const f=fixture();assert.equal(verifyResults(f.folder,f.start).tests,12);});
test('rejects stale XML, skipped cases and wrong database product',()=>{
  const stale=fixture();assert.throws(()=>verifyResults(stale.folder,Date.now()+10000),/Stale XML/);
  for(const[before,after]of [['skipped="0"','skipped="1"'],['|MySQL|','|H2|'],['tests="6"','tests="0"']]){const f=fixture();fs.writeFileSync(f.file,fs.readFileSync(f.file,'utf8').replace(before,after));assert.throws(()=>verifyResults(f.folder,f.start));}
});
test('rejects divergent or duplicate scenarios instead of trusting aggregate counts',()=>{const f=fixture();fs.writeFileSync(f.file,fs.readFileSync(f.file,'utf8').replace(names[0],names[1]));assert.throws(()=>verifyResults(f.folder,f.start));});
test('Linux UI gates retain all flows and force the freshly packaged default JAR in CI',()=>{
  const source=fs.readFileSync(path.join(__dirname,'../verify-runbook-citation-ui.cjs'),'utf8');
  assert.match(source,/after=ci\?'target\/opspilot-0\.1\.0-SNAPSHOT\.jar'/);assert.match(source,/does not accept named JAR overrides/);assert.match(source,/cases\.length,5/);
  assert.match(source,/process\.platform==='win32'\?'java\.exe':'java'/);assert.doesNotMatch(source,/C:\/Program Files/);
  for(const name of ['exact-historical-source-full-text-escaped-native-modal-and-focus','dark-desktop-and-two-mobile-heights','read-failure-retry-and-wrong-version-response-fail-closed','close-aborts-late-source-and-fresh-open-still-works','account-switch-discards-late-private-source'])assert.ok(source.includes(name));
});
test('database CI requires opt-in MySQL plus non-skipping evidence, browser CI builds actual source',()=>{
  const source=fs.readFileSync(path.join(__dirname,'../../.github/workflows/citation-source.yml'),'utf8');
  assert.match(source,/image: mysql:8\.4/);assert.match(source,/-Dopspilot\.scoped\.eval\.mysql\.enabled=true/);assert.match(source,/verify-citation-contract-results\.cjs/);
  assert.match(source,/npm run build --prefix web/);assert.match(source,/\.\/mvnw -B -DskipTests package/);assert.match(source,/node scripts\/verify-runbook-citation-ui\.cjs/);
});

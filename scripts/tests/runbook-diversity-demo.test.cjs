const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),os=require('node:os'),crypto=require('node:crypto'),{spawnSync}=require('node:child_process');
const root=path.resolve(__dirname,'../..'),hash=bytes=>crypto.createHash('sha256').update(bytes).digest('hex');
function fixture(){
  const folder=fs.mkdtempSync(path.join(os.tmpdir(),'opspilot-demo-prepare-unit-'));
  fs.mkdirSync(path.join(folder,'scripts/fixtures'),{recursive:true});fs.mkdirSync(path.join(folder,'target'));
  fs.copyFileSync(path.join(root,'scripts/prepare-runbook-diversity-comparison.cjs'),path.join(folder,'scripts/prepare-runbook-diversity-comparison.cjs'));
  const dataset=fs.readFileSync(path.join(root,'scripts/fixtures/runbook-document-diversity-cases.json'));
  fs.writeFileSync(path.join(folder,'scripts/fixtures/runbook-document-diversity-cases.json'),dataset);
  const reports={};
  for(const label of ['before','after']){
    const input=path.join(folder,'input',label);fs.mkdirSync(input,{recursive:true});
    const jar=path.join(folder,'target',label+'.jar'),binary=Buffer.from(label+' fixture, not a runnable jar');fs.writeFileSync(jar,binary);
    fs.writeFileSync(path.join(input,'demo.mv.db'),Buffer.from(label+' stopped database fixture'));
    reports[label]={status:label==='before'?'BASELINE':'PASS',label,shutdown:true,exitCode:0,portsFree:true,jar,jarSha256:hash(binary),
      datasetSha256:hash(dataset),corpusSha256:'same-frozen-corpus',cases:Array.from({length:32},()=>({})),metrics:{duplicateSlots:label==='after'?0:27},ui:{session:33}};
  }
  function run(){for(const label of ['before','after'])fs.writeFileSync(path.join(folder,'input',label,'qa.json'),JSON.stringify(reports[label]));
    return spawnSync(process.execPath,[path.join(folder,'scripts/prepare-runbook-diversity-comparison.cjs'),'--before',path.join(folder,'input/before/qa.json'),'--after',path.join(folder,'input/after/qa.json')],{encoding:'utf8',windowsHide:true});}
  return {folder,reports,run,output:path.join(folder,'target/document-diversity-comparison-20261015')};
}
test('comparison preparation copies stopped snapshots once and never overwrites an existing demo',()=>{
  const f=fixture(),first=f.run();assert.equal(first.status,0,first.stderr);
  const manifest=JSON.parse(fs.readFileSync(path.join(f.output,'manifest.json')));
  for(const label of ['before','after']){
    assert.equal(fs.readFileSync(path.join(f.output,label,'demo.mv.db'),'utf8'),label+' stopped database fixture');
    assert.equal(manifest[label].sessionId,33);assert.equal(manifest[label].port,label==='before'?9913:9916);
  }
  const original=hash(fs.readFileSync(path.join(f.output,'manifest.json'))),repeated=f.run();assert.equal(repeated.status,1);
  assert.match(repeated.stderr,/already prepared/);assert.equal(hash(fs.readFileSync(path.join(f.output,'manifest.json'))),original);
});
test('different frozen corpora are rejected before any output database is created',()=>{
  const f=fixture();f.reports.after.corpusSha256='different';assert.equal(f.run().status,1);assert.equal(fs.existsSync(f.output),false);
});
test('unclean source lifecycle is rejected without partially copying the other database',()=>{
  const f=fixture();f.reports.after.shutdown=false;assert.equal(f.run().status,1);assert.equal(fs.existsSync(f.output),false);
});

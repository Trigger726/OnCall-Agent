// Preserve two cleanly stopped, controlled-corpus QA databases as independently runnable demos.
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto'),assert=require('node:assert/strict');
const root=path.resolve(__dirname,'..'),args=process.argv.slice(2),sha=file=>crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
function option(name){const index=args.indexOf(name);assert.ok(index>=0&&args[index+1],`Missing ${name}`);return path.resolve(args[index+1]);}
function prepare(){
  const beforePath=option('--before'),afterPath=option('--after'),before=JSON.parse(fs.readFileSync(beforePath)),after=JSON.parse(fs.readFileSync(afterPath));
  assert.equal(before.status,'BASELINE');assert.equal(after.status,'PASS');assert.equal(before.label,'before');assert.equal(after.label,'after');
  assert.equal(before.datasetSha256,after.datasetSha256);assert.equal(before.corpusSha256,after.corpusSha256);
  assert.equal(after.datasetSha256,sha(path.join(root,'scripts/fixtures/runbook-document-diversity-cases.json')));
  assert.equal(before.cases.length,32);assert.equal(after.cases.length,32);assert.equal(after.metrics.duplicateSlots,0);
  const output=path.join(root,'target/document-diversity-comparison-20261015');
  assert.equal(fs.existsSync(path.join(output,'manifest.json')),false,'Comparison demos already prepared; existing files will not be overwritten');
  const manifest={datasetSha256:before.datasetSha256,corpusSha256:before.corpusSha256,isolated:true,realUserDatabaseUsed:false},planned=[];
  for(const[label,file,qa,port]of [['before',beforePath,before,9913],['after',afterPath,after,9916]]){
    assert.equal(qa.shutdown,true);assert.equal(qa.exitCode,0);assert.equal(qa.portsFree,true);assert.ok(!qa.forcedOwnedJvmStop);
    const jar=path.join(root,'target',path.basename(qa.jar)),source=path.join(path.dirname(file),'demo.mv.db'),destination=path.join(output,label,'demo.mv.db');
    assert.equal(sha(jar),qa.jarSha256);assert.ok(fs.existsSync(source));assert.equal(fs.existsSync(destination),false,'Never overwrite a demo database');
    planned.push({source,destination});
    manifest[label]={jar:path.relative(root,jar).replaceAll('\\','/'),jarSha256:qa.jarSha256,port,sessionId:qa.ui.session,initialDatabaseSha256:sha(source),metrics:qa.metrics};
  }
  for(const item of planned){fs.mkdirSync(path.dirname(item.destination),{recursive:true});fs.copyFileSync(item.source,item.destination,fs.constants.COPYFILE_EXCL);}
  fs.writeFileSync(path.join(output,'manifest.json'),JSON.stringify(manifest,null,2)+'\n',{flag:'wx'});
  console.log(JSON.stringify({status:'PREPARED',folder:output,beforePort:9913,afterPort:9916}));
}
try{prepare();}catch(error){console.error(error.message);process.exitCode=1;}

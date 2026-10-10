const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict'),{execFileSync}=require('node:child_process'),{createHash}=require('node:crypto');
const source='83a6842f2218427fa4de401ff0f3af40d96f9b97',checkout=path.resolve(__dirname,'../target/cp118-v40-source');
assert.ok(fs.existsSync(checkout+'/.git'),'Require independent Git checkout');
const git=args=>execFileSync('git',['-C',checkout,...args],{encoding:'utf8'}).trim();assert.equal(git(['rev-parse','HEAD']),source);assert.equal(git(['status','--porcelain']),'');
const migrations=git(['ls-tree','-r','--name-only',source,'--','src/main/resources/db/migration','src/main/java/db/migration']).split('\n');assert.equal(migrations.length,40);assert.deepEqual(migrations.map(f=>Number(path.basename(f).match(/^V(\d+)__/)[1])).sort((a,b)=>a-b),Array.from({length:40},(_,i)=>i+1));
const jarSha256=createHash('sha256').update(fs.readFileSync(checkout+'/target/opspilot-0.1.0-SNAPSHOT.jar')).digest('hex'),proof={source,jarSha256,cleanTrackedCheckout:true,versionedMigrationFiles:40};
fs.writeFileSync(checkout+'/old-v40-jar-provenance.json',JSON.stringify(proof,null,2)+'\n');console.log(JSON.stringify(proof));

const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict'),{execFileSync}=require('node:child_process'),{createHash}=require('node:crypto');
const source='2730e307c542f3c71f0adea2c354dd9a6118ba4e',checkout=path.resolve(__dirname,'../target/cp124-v41-source');
assert.ok(fs.existsSync(checkout+'/.git'));const git=args=>execFileSync('git',['-C',checkout,...args],{encoding:'utf8'}).trim();assert.equal(git(['rev-parse','HEAD']),source);assert.equal(git(['status','--porcelain']),'');
const files=git(['ls-tree','-r','--name-only',source,'--','src/main/resources/db/migration','src/main/java/db/migration']).split('\n');assert.equal(files.length,41);assert.deepEqual(files.map(f=>Number(path.basename(f).match(/^V(\d+)__/)[1])).sort((a,b)=>a-b),Array.from({length:41},(_,i)=>i+1));
const proof={source,jarSha256:createHash('sha256').update(fs.readFileSync(checkout+'/target/opspilot-0.1.0-SNAPSHOT.jar')).digest('hex'),cleanTrackedCheckout:true,versionedMigrationFiles:41};fs.writeFileSync(checkout+'/old-v41-jar-provenance.json',JSON.stringify(proof,null,2)+'\n');console.log(JSON.stringify(proof));


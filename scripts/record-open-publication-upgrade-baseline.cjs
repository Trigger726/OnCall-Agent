const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const {execFileSync}=require('node:child_process'),{createHash}=require('node:crypto');
const source='915e3f29557bd8019c79793b12f73f990215ad71',checkout=path.resolve(__dirname,'../target/cp116-v39-source');
assert.ok(fs.existsSync(path.join(checkout,'.git')),'Require independent actual Git checkout');
assert.equal(execFileSync('git',['-C',checkout,'rev-parse','HEAD'],{encoding:'utf8'}).trim(),source);
assert.equal(execFileSync('git',['-C',checkout,'status','--porcelain'],{encoding:'utf8'}).trim(),'');
const jarSha256=createHash('sha256').update(fs.readFileSync(path.join(checkout,'target/opspilot-0.1.0-SNAPSHOT.jar'))).digest('hex');
fs.writeFileSync(path.join(checkout,'old-v39-jar-provenance.json'),JSON.stringify({source,jarSha256},null,2)+'\n');
console.log(JSON.stringify({source,jarSha256}));

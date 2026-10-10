const fs=require('node:fs'),path=require('node:path'),http=require('node:http'),assert=require('node:assert/strict'),crypto=require('node:crypto');
const {spawn}=require('node:child_process');
const {requireFreePort,stopProcess}=require('../scripts/verify-oncall-browser-ci.cjs');
const root=path.resolve(__dirname,'..'),source='D:/Codex/国网/tmp/m2-repository',parent=path.join(root,'target/cp106-maven-cache-it');fs.mkdirSync(parent,{recursive:true});
const out=fs.mkdtempSync(path.join(parent,'run-')),repository=path.join(out,'repository');fs.mkdirSync(repository);
const prefix='org/apache/maven/plugins/maven-jar-plugin/3.3.0/maven-jar-plugin-3.3.0',requests=[],runs=[],checks=[],hash=b=>crypto.createHash('sha256').update(b).digest('hex');let available=false,server;
function check(name,actual,expected){assert.deepEqual(actual,expected,name);checks.push({name,actual,expected});}
async function maven(name,update){
  const args=['-B','-e',...(update?['-U']:[]),'-f','target/cp106-maven-probe-pom.xml','-s','target/cp106-maven-probe-settings.xml','-Dmaven.repo.local='+repository,'org.apache.maven.plugins:maven-jar-plugin:3.3.0:help'];
  const child=spawn('powershell.exe',['-NoProfile','-Command',"& './mvnw.cmd' "+args.map(x=>"'"+x.replaceAll("'","''")+"'").join(' ')+"; exit $LASTEXITCODE"],{cwd:root,windowsHide:true,env:process.env,stdio:['ignore','pipe','pipe']});
  let text='',timeout=false;const fd=fs.openSync(path.join(out,name+'.log'),'w');for(const stream of [child.stdout,child.stderr])stream.on('data',b=>{fs.writeSync(fd,b);text+=b.toString();});
  const timer=setTimeout(()=>{timeout=true;void stopProcess(child);},45000);let code;
  try{code=await new Promise((resolve,reject)=>{child.once('error',reject);child.once('close',resolve);});}finally{clearTimeout(timer);fs.closeSync(fd);}
  const r={name,args,exitCode:code,timeout,text};runs.push(r);check(name+' bounded',timeout,false);return r;
}
(async()=>{
 const result={status:'RUNNING',scope:'Isolated real Maven 3.9.9 negative-cache experiment. No production JAR/build/repository modifications, no third-party repository requests. Does not prove the historical GitHub failure had a cached miss.',checks,runs,requests};
 try{
  await requireFreePort(9937);
  server=http.createServer((req,res)=>{
   const relative=decodeURIComponent(req.url.slice('/repository/'.length));const file=path.resolve(source,relative);
   if(!req.url.startsWith('/repository/')||!file.startsWith(path.resolve(source)+path.sep)||!/^[-\w./]+\.(?:pom|jar|sha1|sha256|sha512|md5)$/.test(relative)){res.writeHead(400);return res.end();}
   const missing=!available&&relative.startsWith(prefix+'.');const exists=fs.existsSync(file),code=missing||!exists?404:200;
   requests.push({run:runs.length+1,path:relative,method:req.method,status:code});res.writeHead(code);res.end(code===200?fs.readFileSync(file):'owned fixture missing');
  });await new Promise((resolve,reject)=>{server.once('error',reject);server.listen(9937,'127.0.0.1',resolve);});
  const first=await maven('first-missing-release',false);check('First synthetic HTTP404 blocks Maven',first.exitCode===0,false);check('First log plugin resolution error',first.text.includes('PluginResolutionException'),true);
  check('Actual first target 404 observed',requests.some(x=>x.path===prefix+'.jar'&&x.status===404),true);
  const marker=path.join(repository,prefix+'.jar.lastUpdated');check('Actual negative cache file exists',fs.existsSync(marker),true);fs.copyFileSync(marker,path.join(out,'first-jar.lastUpdated'));
  available=true;const before=requests.filter(x=>x.path.startsWith(prefix+'.')).length;
  const second=await maven('available-but-cached-missing',false);check('Without -U cached missing release still fails',second.exitCode===0,false);check('Cached miss explicitly diagnosed',second.text.includes('cached in the local repository'),true);check('Without -U no target network check',requests.filter(x=>x.path.startsWith(prefix+'.')).length,before);
  const third=await maven('force-missing-release-check',true);check('With -U real plugin goal succeeds',third.exitCode,0);check('Complete plugin help build success',third.text.includes('BUILD SUCCESS')&&third.text.includes('Apache Maven JAR Plugin 3.3.0')&&third.text.includes('jar:help'),true);
  const final=fs.readFileSync(path.join(repository,prefix+'.jar'));check('Downloaded plugin bytes match pinned actual Apache JAR',hash(final),'17edc5d0289dc0a9b61bd5db15fdb5804b5fb73d7dbe458771e33fbc1d51fa94');check('No outside-mirror target',requests.every(x=>x.path&&!x.path.includes('://')),true);result.status='PASS';
 }catch(e){result.status='FAIL';result.error=e.message;process.exitCode=1;}
 finally{
  if(server?.listening)await new Promise(resolve=>server.close(resolve));result.ownedServerStopped=!server?.listening;
  result.runs=result.runs.map(({text,...r})=>r);fs.writeFileSync(path.join(out,'result.json'),JSON.stringify(result,null,2)+'\n');console.log(JSON.stringify({status:result.status,out,checks:checks.length,requests:requests.length,error:result.error}));
 }
})();

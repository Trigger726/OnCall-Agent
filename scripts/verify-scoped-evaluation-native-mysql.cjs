// Fresh native MySQL instance + identical H2/MySQL contracts. No installed service is used.
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const net = require('node:net');
const crypto = require('node:crypto');
const { spawn } = require('node:child_process');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '..');
const args = process.argv.slice(2);
function option(name, fallback) { const i=args.indexOf(name); return i<0 ? fallback : args[i+1]; }
const mysqlHome = option('--mysql-home', process.env.MYSQL_HOME);
const javaHome = option('--java-home', process.env.JAVA_HOME);
const maven = option('--maven', process.env.MAVEN_CMD);
const m2 = option('--m2', process.env.MAVEN_REPOSITORY);
for (const [name,value] of Object.entries({mysqlHome,javaHome,maven,m2})) {
  assert.ok(value, `Supply ${name} (see acceptance report)`);
  assert.ok(fs.existsSync(value), `Missing ${name}: ${value}`);
}
assert.equal(process.platform, 'win32', 'This native harness targets Windows; Java contracts are portable.');
const out = fs.mkdtempSync(path.join(os.tmpdir(), 'opspilot-scope-mysql-'));
const data = path.join(out, 'data');
fs.mkdirSync(data);
const password = crypto.randomBytes(24).toString('hex');
const bin = name => path.join(mysqlHome, 'bin', name+'.exe');
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
const report = {status:'RUNNING', out, isolated:true, testSchemas:['opspilot_scope_evaluation','opspilot_scope_upgrade'], tests:[]};
function run(command, argv, logName, env=process.env, input) {
  return new Promise((resolve,reject) => {
    const log=fs.createWriteStream(path.join(out,logName));
    const child=spawn(command,argv,{cwd:root,env,windowsHide:true,stdio:['pipe','pipe','pipe']});
    let output='';
    for(const stream of [child.stdout,child.stderr]) stream.on('data',chunk=>{log.write(chunk);output+=chunk;});
    child.on('error',error=>{log.end();reject(error);});
    child.on('close',code=>{log.end();resolve({code,output});});
    child.stdin.end(input);
  });
}
async function freePort() {
  const server=net.createServer();
  await new Promise((resolve,reject)=>{server.once('error',reject);server.listen(0,'127.0.0.1',resolve);});
  const port=server.address().port;
  await new Promise(resolve=>server.close(resolve));
  return port;
}
async function portIsFree(port) {
  const server=net.createServer();
  return new Promise(resolve=>{server.once('error',()=>resolve(false));server.listen(port,'127.0.0.1',()=>server.close(()=>resolve(true)));});
}
let server, serverExit, port, secured=false;
const connectionArgs=()=>['--no-defaults','--protocol=TCP','--host=127.0.0.1',`--port=${port}`,'--user=root'];
const clientArgs=()=>[...connectionArgs(),'--batch','--raw','--skip-column-names'];
const clientEnv=()=>({...process.env,MYSQL_PWD:secured?password:''});
async function sql(statement,logName) {
  const result=await run(bin('mysql'),clientArgs(),logName,clientEnv(),statement);
  assert.equal(result.code,0,`MySQL SQL failed; inspect ${logName}`);
  return result.output.trim();
}
function psLiteral(value) { return "'"+value.replaceAll("'","''")+"'"; }
(async()=>{
  try {
    const version=await run(bin('mysqld'),['--no-defaults','--version'],'version.log');
    assert.equal(version.code,0); report.version=version.output.trim();
    console.log(JSON.stringify({phase:'initialize',out,version:report.version}));
    const initialized=await run(bin('mysqld'),['--no-defaults','--initialize-insecure','--console',`--basedir=${mysqlHome}`,`--datadir=${data}`],'initialize.log');
    assert.equal(initialized.code,0,'Initialization failed; the fresh data directory is retained.');
    port=await freePort(); report.port=port;
    const log=fs.createWriteStream(path.join(out,'server.log'));
    server=spawn(bin('mysqld'),['--no-defaults','--no-monitor','--console',`--basedir=${mysqlHome}`,`--datadir=${data}`,
      '--bind-address=127.0.0.1',`--port=${port}`,'--mysqlx=0','--skip-log-bin','--innodb-buffer-pool-size=64M',
      '--performance-schema=OFF','--character-set-server=utf8mb4','--collation-server=utf8mb4_unicode_ci'],
      {cwd:out,windowsHide:true,stdio:['ignore','pipe','pipe']});
    report.serverPid=server.pid;
    server.stdout.pipe(log,{end:false}); server.stderr.pipe(log,{end:false});
    serverExit=new Promise((resolve,reject)=>{server.once('error',reject);server.once('close',code=>{log.end();resolve(code);});});
    let ready=false;
    for(let i=0;i<60;i++) {
      assert.equal(server.exitCode,null,'Owned MySQL exited before readiness');
      const ping=await run(bin('mysql'),clientArgs(),'readiness.log',clientEnv(),'SELECT 1;');
      if(ping.code===0){ready=true;break;} await delay(500);
    }
    assert.ok(ready,'Owned MySQL did not become ready');
    await sql(`ALTER USER 'root'@'localhost' IDENTIFIED BY '${password}';`,'secure.log');
    secured=true;
    await sql('CREATE DATABASE opspilot_scope_evaluation; CREATE DATABASE opspilot_scope_upgrade;','schemas.log');
    report.serverIdentity=await sql('SELECT @@version, @@port, @@datadir, @@sql_mode, @@character_set_server, @@collation_server;','identity.log');
    assert.ok(report.serverIdentity.includes(data.replaceAll('\\','/')) || report.serverIdentity.includes(data));
    const jdbc=schema=>`jdbc:mysql://127.0.0.1:${port}/${schema}?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC`;
    const env={...process.env,JAVA_HOME:javaHome,OPSPILOT_SCOPED_MYSQL_URL:jdbc(report.testSchemas[0]),
      OPSPILOT_SCOPED_MYSQL_UPGRADE_URL:jdbc(report.testSchemas[1]),OPSPILOT_SCOPED_MYSQL_USER:'root',OPSPILOT_SCOPED_MYSQL_PASSWORD:password};
    const classes=['RunbookScopedEvaluationIntegrationTest','RunbookRetrievalScopeMigrationTest',
      'MySqlScopedEvaluationIntegrationTest','MySqlRunbookRetrievalScopeMigrationTest'];
    if(args.includes('--include-diversity')) classes.push('RunbookDocumentDiversityIntegrationTest','MySqlRunbookDocumentDiversityIntegrationTest');
    if(args.includes('--include-citations')) classes.push('RunbookCitationIntegrationTest','MySqlRunbookCitationIntegrationTest');
    const expectedTests=16+(args.includes('--include-diversity')?8:0)+(args.includes('--include-citations')?12:0);
    console.log(JSON.stringify({phase:'contracts',port,expectedTests}));
    const mvnArgs=['-o',`-Dmaven.repo.local=${m2}`,`-Dtest=${classes.join(',')}`,'-Dopspilot.scoped.eval.mysql.enabled=true','test'];
    const command=`& ${psLiteral(maven)} ${mvnArgs.map(psLiteral).join(' ')}; exit $LASTEXITCODE`;
    const testedAt=Date.now();
    const tested=await run('powershell.exe',['-NoProfile','-NonInteractive','-Command',command],'maven.log',env);
    for(const name of classes) {
      const filename=`TEST-org.trigger.opspilot.runbook.${name}.xml`;
      const source=path.join(root,'target','surefire-reports',filename);
      if(!fs.existsSync(source) || fs.statSync(source).mtimeMs<testedAt)continue;
      const xml=fs.readFileSync(source,'utf8');
      fs.copyFileSync(source,path.join(out,filename));
      const tag=xml.match(/<testsuite\s[^>]*>/)?.[0]; assert.ok(tag);
      const counts=Object.fromEntries(['tests','errors','failures','skipped'].map(key=>[key,Number(tag.match(new RegExp(`\\b${key}="(\\d+)"`))[1])]));
      report.tests.push({name,...counts});
    }
    assert.equal(tested.code,0,'Maven failed; first failure and database are retained.');
    assert.equal(report.tests.length,classes.length);
    assert.equal(report.tests.reduce((sum,test)=>sum+test.tests,0),expectedTests);
    assert.ok(report.tests.every(test=>test.errors===0&&test.failures===0&&test.skipped===0));
    report.schemaVersions=await sql("SELECT 'evaluation',version FROM opspilot_scope_evaluation.flyway_schema_history WHERE version='43' AND success=1 UNION ALL SELECT 'upgrade',version FROM opspilot_scope_upgrade.flyway_schema_history WHERE version='43' AND success=1;",'versions.log');
    assert.equal(report.schemaVersions.split(/\r?\n/).length,2);
    report.status='PASS';
  } catch(error) {report.status='FAIL';report.failure=error.stack;process.exitCode=1;}
  finally {
    if(server && server.exitCode===null) {
      const stopped=await run(bin('mysqladmin'),[...connectionArgs(),'shutdown'],'shutdown.log',clientEnv());
      report.shutdownCommandExit=stopped.code;
      let timer;
      const result=await Promise.race([serverExit,new Promise(resolve=>{timer=setTimeout(()=>resolve('timeout'),30000);})]);
      clearTimeout(timer);
      if(result==='timeout') {server.kill();report.forcedOwnedServerStop=true;report.serverExit=await serverExit;}
      else report.serverExit=result;
    } else if(server) report.serverExit=server.exitCode;
    if(port) report.portFree=await portIsFree(port);
    if(report.status==='PASS' && (report.serverExit!==0 || report.portFree!==true || report.forcedOwnedServerStop)) {
      report.status='FAIL';report.failure='Owned server cleanup was not clean';process.exitCode=1;
    }
    fs.writeFileSync(path.join(out,'qa.json'),JSON.stringify(report,null,2)+'\n');
    console.log(JSON.stringify(report));
  }
})();

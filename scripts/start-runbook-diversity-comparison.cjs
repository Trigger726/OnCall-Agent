// Independent, labelled comparison demos. Never shares the workbench preview's database or port.
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict'),crypto=require('node:crypto');
const {spawn}=require('node:child_process'),{javaArgs,free}=require('./verify-ui-packaged.cjs');
const root=path.resolve(__dirname,'..'),version=process.argv[2]||'after';
assert.ok(['before','after'].includes(version),'Usage: node scripts/start-runbook-diversity-comparison.cjs [before|after] [manifest.json]');
const manifestFile=path.resolve(root,process.argv[3]||'target/document-diversity-comparison-20261015/manifest.json');
const folder=path.dirname(manifestFile),manifest=JSON.parse(fs.readFileSync(manifestFile)),demo=manifest[version];
const jar=path.join(root,demo.jar),data=path.join(folder,version),java=path.join(process.env.JAVA_HOME||'','bin/java.exe');
assert.ok(process.env.JAVA_HOME&&fs.existsSync(java),'Set JAVA_HOME to JDK 21.');
assert.ok(fs.existsSync(path.join(data,'demo.mv.db')),'Missing prepared comparison database. See CP07 acceptance.');
assert.equal(crypto.createHash('sha256').update(fs.readFileSync(jar)).digest('hex'),demo.jarSha256,'Demo JAR changed');
(async()=>{
  await free(demo.port);await free(demo.port+1);
  console.log(`${version}: http://127.0.0.1:${demo.port}/assistant?session=${demo.sessionId} (isolated controlled corpus, AI disabled)`);
  const child=spawn(java,[...javaArgs(jar,data,demo.port),'--opspilot.observability.prometheus.enabled=false','--opspilot.observability.loki.enabled=false'],{cwd:data,windowsHide:true,env:process.env,stdio:'inherit'});
  child.on('error',error=>{console.error(error.message);process.exitCode=1;});
  child.on('close',code=>{process.exitCode=code??1;});
})().catch(error=>{console.error(error.message);process.exitCode=1;});

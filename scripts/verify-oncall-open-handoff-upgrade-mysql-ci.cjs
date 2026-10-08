const assert=require('node:assert/strict');
const {verify}=require('./verify-oncall-open-handoff-http-ci.cjs');
const {redact}=require('./verify-oncall-browser-ci.cjs');

function configuration(env){
  assert.equal(env.OPSPILOT_OPEN_UPGRADE_MYSQL_OWNER,'TESTCONTAINERS');
  const schema=env.OPSPILOT_UPGRADE_SCHEMA,url=env.OPSPILOT_UPGRADE_JDBC_URL;
  assert.match(schema||'',/^opspilot_open_upgrade_[a-f0-9]{12}$/);
  assert.match(url||'',new RegExp('^jdbc:mysql://(?:localhost|127\\.0\\.0\\.1):[0-9]+/'+schema+'\\?[^\\s]+$'));
  assert.match(env.OPSPILOT_UPGRADE_SERVER_UUID||'',/^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/);
  assert.equal(env.OPSPILOT_UPGRADE_DB_USER,'opspilot');assert.ok(env.OPSPILOT_UPGRADE_DB_PASSWORD);
  assert.ok(!env.OPSPILOT_OPEN_HANDOFF_UPGRADE,'Do not mix local H2 archive mode into owned MySQL CI');
  return{schema,url,user:env.OPSPILOT_UPGRADE_DB_USER,password:env.OPSPILOT_UPGRADE_DB_PASSWORD,serverUuid:env.OPSPILOT_UPGRADE_SERVER_UUID};
}
module.exports={configuration};
if(require.main===module){
  Promise.resolve().then(()=>verify(configuration(process.env))).catch(error=>{console.error(redact(error.message));process.exitCode=1;});
}

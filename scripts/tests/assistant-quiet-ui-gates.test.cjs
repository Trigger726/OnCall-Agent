const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),{spawnSync}=require('node:child_process');
const root=path.resolve(__dirname,'../..');
test('named UI jars cannot replace default CI or historical baseline acceptance',()=>{
  for(const runner of ['verify-assistant-request-ui-ci.cjs','verify-assistant-native-ui-ci.cjs']){
    const result=spawnSync(process.execPath,[path.join(root,'scripts',runner)],{cwd:root,windowsHide:true,encoding:'utf8',env:{...process.env,CI:'1',OPSPILOT_ASSISTANT_UI_BASELINE:'0',OPSPILOT_ASSISTANT_NATIVE_BASELINE:'0',OPSPILOT_ASSISTANT_UI_JAR:'target/not-a-ci-artifact.jar'}});
    assert.equal(result.status,1);assert.match(result.stderr,/local-only and cannot replace CI\/baseline/);
  }
});
test('current UI gates require saved completion and retain native cancellation, saturation and timeout flows',()=>{
  for(const runner of ['verify-assistant-request-ui-ci.cjs','verify-assistant-native-ui-ci.cjs']){
    const source=fs.readFileSync(path.join(root,'scripts',runner),'utf8');
    assert.match(source,/async function waitForCompleted/);assert.match(source,/!document\.querySelector\('\.assistant-request-status'\)/);
    assert.match(source,/answerMessageId/);assert.match(source,/name: '继续提问', exact: true \}\)\.count\(\), 0/);
  }
  const native=fs.readFileSync(path.join(root,'scripts/verify-assistant-native-ui-ci.cjs'),'utf8');
  assert.match(native,/--opspilot\.assistant\.execution-timeout=12s/);assert.match(native,/AbortSignal\.timeout\(20000\)/);assert.match(native,/assertCancelledFixtureStream\(held\)/);
  for(const name of ['preview-before-final-commit','explicit-cancel-after-preview','truncated-preview','idle-after-preview','disconnect-after-preview-manual-query','output-capacity-503-and-fixed-key-manual-recovery'])assert.ok(native.includes("name: '"+name+"'"));
});

const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const read=name=>fs.readFileSync(path.join(__dirname,'..',name),'utf8');
test('answer reader CI uses fresh default package and retains native/code/copy/citation flows',()=>{
  const text=read('verify-answer-reader-ui.cjs');assert.match(text,/after=ci\?'target\/opspilot-0\.1\.0-SNAPSHOT\.jar'/);assert.match(text,/does not accept named JAR overrides/);
  assert.match(text,/osClipboardModified:false/);assert.match(text,/provisional|assistant-preview-label/);assert.match(text,/providerCalls\.length,2/);assert.match(text,/0\),6/);
  for(const name of ['safe-code-original-step-numbers-and-verified-inline-reference','inline-reference-opens-original-source-with-keyboard-focus-return','explicit-exact-code-and-original-answer-copy-with-denial-contained','dual-theme-desktop-mobile-and-contained-long-code-scrolling','preview-has-no-copy-or-citation-actions-until-saved-completion','cancelled-code-preview-is-not-copied-linked-or-saved'])assert.ok(text.includes(name));
});
test('workflow preserves existing citation UI plus new answer reader and seven-case database contract',()=>{
  const text=fs.readFileSync(path.join(__dirname,'../../.github/workflows/citation-source.yml'),'utf8');assert.match(text,/node scripts\/verify-runbook-citation-ui\.cjs/);assert.match(text,/node scripts\/verify-answer-reader-ui\.cjs/);assert.match(text,/same seven scenarios/);
  assert.match(text,/target\/answer-reader-ui-it\//);assert.match(text,/-Dopspilot\.scoped\.eval\.mysql\.enabled=true/);
});

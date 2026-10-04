const {test}=require('node:test'),assert=require('node:assert/strict');
const {configuration,caseNames}=require('../verify-oncall-swap-revocation-ui-ci.cjs');
test('own-source CI defaults to the full real paired revocation UI contract',()=>assert.deepEqual(configuration({CI:'true'}),{baseline:false}));
test('archived old UI may be compared only locally, never replace full CI acceptance',()=>{assert.deepEqual(configuration({OPSPILOT_SWAP_REVOCATION_UI_BASELINE:'1'}),{baseline:true});assert.throws(()=>configuration({CI:'true',OPSPILOT_SWAP_REVOCATION_UI_BASELINE:'1'}),/cannot replace/);});
test('paired interception changes retain every original full fourteen case identifier',()=>{
  const fs=require('node:fs'),path=require('node:path'),old=JSON.parse(fs.readFileSync(path.join(__dirname,'../../docs/assets/v1.7-cp95/pairedUI-result.json'),'utf8'));
  assert.deepEqual(caseNames,old.cases.map(c=>c.name));assert.equal(caseNames.length,14);
});

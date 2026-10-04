const {test}=require('node:test'),assert=require('node:assert/strict');
const {configuration}=require('../verify-oncall-swap-revocation-http-ci.cjs');
test('archived revocation upgrade comparison cannot replace own-source CI',()=>assert.throws(()=>configuration({CI:'true',OPSPILOT_SWAP_REVOCATION_UPGRADE:'1'})));
test('local upgrade comparison and ordinary fresh-source acceptance remain separate',()=>{
  assert.deepEqual(configuration({CI:'true'}),{comparison:false});
  assert.deepEqual(configuration({OPSPILOT_SWAP_REVOCATION_UPGRADE:'1'}),{comparison:true});
});

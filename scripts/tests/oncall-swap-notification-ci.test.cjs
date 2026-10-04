const {test}=require('node:test'),assert=require('node:assert/strict');
const {configuration}=require('../verify-oncall-swap-notification-ci.cjs');
test('CI cannot replace notification acceptance with archived baseline',()=>assert.throws(()=>configuration({CI:'true',OPSPILOT_SWAP_NOTIFICATION_BASELINE:'1'})));
test('normal CI and explicit local archived comparison remain distinct',()=>{assert.deepEqual(configuration({CI:'true'}),{baseline:false});assert.deepEqual(configuration({OPSPILOT_SWAP_NOTIFICATION_BASELINE:'1'}),{baseline:true});});

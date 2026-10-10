const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const {execFileSync}=require('node:child_process');
const root=path.resolve(__dirname,'../..'),baseline='ce5fc51aee215e4eebccfd9e02bccf5271f6fea5';
test('V39 WIP runs exactly the original 21 job definitions without weakening any original runner',()=>{
  const before=execFileSync('git',['show',baseline+':.github/workflows/ci.yml'],{cwd:root,encoding:'utf8'}).replaceAll('\r\n','\n');
  const after=fs.readFileSync(path.join(root,'.github/workflows/ci.yml'),'utf8').replaceAll('\r\n','\n');
  assert.equal(after.slice(after.indexOf('\njobs:\n')),before.slice(before.indexOf('\njobs:\n')));
  assert.equal([...after.matchAll(/^  [\w-]+:\s*$/gm)].filter(m=>m.index>after.indexOf('\njobs:\n')).length,21);
  assert.match(after,/branches: \[main, master, "feat\/\*\*", "wip\/plan-membership-v39-20261010"\]/);
  assert.doesNotMatch(after,/branches:[^\r\n]*"wip\/\*\*"/);
});
test('new membership jobs keep their own explicit MySQL opt-in and strict evidence checks',()=>{
  const yaml=fs.readFileSync(path.join(root,'.github/workflows/plan-membership-wip.yml'),'utf8');
  for(const command of ['-Dtest=MySqlOnCallPlanMembershipIntegrationTest test','-Dtest=MySqlPlanMembershipUpgradeHttpIntegrationTest test',
    'node scripts/verify-oncall-plan-membership-mysql.cjs','node scripts/verify-oncall-plan-membership-upgrade-mysql.cjs'])assert.ok(yaml.includes(command));
  assert.doesNotMatch(yaml,/continue-on-error|git reset|git checkout/);
});
test('membership browser is an additional own-source job, never replaces original 33 cases',()=>{
  const yaml=fs.readFileSync(path.join(root,'.github/workflows/plan-membership-wip.yml'),'utf8');
  assert.equal([...yaml.matchAll(/^  [\w-]+:\s*$/gm)].filter(m=>m.index>yaml.indexOf('\njobs:\n')).length,8);
  for(const command of ['-Dtest=MySqlOnCallPlanMembershipCrossNodeIntegrationTest test','node scripts/verify-oncall-plan-membership-cross-node.cjs','node scripts/verify-oncall-plan-membership-ui-ci.cjs','node scripts/verify-oncall-plan-membership-ui-evidence.cjs','npm test --prefix web','npm run build --prefix web'])assert.ok(yaml.includes(command));
  assert.doesNotMatch(yaml,/OPSPILOT_MEMBER_UI_BASELINE|continue-on-error/);
});

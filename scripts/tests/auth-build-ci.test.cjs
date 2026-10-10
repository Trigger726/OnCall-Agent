const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const {test}=require('node:test');
const workflow=fs.readFileSync(path.join(__dirname,'../../.github/workflows/ci.yml'),'utf8');
const auth=workflow.slice(workflow.indexOf('  auth-session-integration:'),workflow.indexOf('  backend-h2:',workflow.indexOf('  auth-session-integration:')));
test('auth JAR build checks missing releases once and preserves Maven failure through tee',()=>{
  assert.match(auth,/Package real application\s+run: \|\s+mkdir -p target\/auth-build-it\s+set -o pipefail\s+\.\/mvnw -B -U -e -DskipTests package 2>&1 \| tee target\/auth-build-it\/maven\.log/);
  assert.equal((auth.match(/\.\/mvnw .*package/g)||[]).length,1);
  assert.doesNotMatch(auth,/continue-on-error|\|\| true|--fail-never|\bclean\b|\bretry\b/);
});
test('auth build evidence is uploaded even when no business runner was reached',()=>{
  assert.match(auth,/uses: actions\/upload-artifact@v5\s+if: always\(\)/);
  assert.match(auth,/name: auth-session-evidence[\s\S]*target\/auth-build-it\/\*\*\/\*\.log/);
  for(const file of ['verify-auth-session-ci.cjs','verify-agent-session-ci.cjs','verify-assistant-session-ci.cjs','verify-assistant-idempotency-ci.cjs','verify-assistant-cancel-ci.cjs','verify-assistant-cross-node-ci.cjs','verify-assistant-slow-consumer-ci.cjs','verify-oncall-swap-http-ci.cjs'])assert.ok(auth.includes('node scripts/'+file));
});

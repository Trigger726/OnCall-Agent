const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { createHmac } = require('node:crypto');

const base = process.env.OPSPILOT_BASE_URL || 'http://127.0.0.1:9917';
assert.equal(process.env.OPSPILOT_ACCEPTANCE_ISOLATED, '1', 'JWT acceptance requires an owned isolated database');
assert(['127.0.0.1', 'localhost'].includes(new URL(base).hostname));
assert.notEqual(new URL(base).port, '9900');
const out = fs.mkdtempSync(path.join(process.env.OPSPILOT_EVIDENCE_DIR || require('node:os').tmpdir(), 'jwt-date-http-'));

async function verify() {
  const result = { status: 'RUNNING', transport: 'real HTTP to packaged isolated JAR', cases: [],
    signingKey: 'unrelated test-only key; runtime secret not read', tokensIncludedInEvidence: false,
    userDatabaseModified: false };
  const request = async (route, token, body) => {
    const response = await fetch(base + '/api/v1' + route, {
      method: body ? 'POST' : 'GET', headers: { ...(token ? { Authorization: 'Bearer ' + token } : {}),
        ...(body ? { 'Content-Type': 'application/json' } : {}) },
      body: body ? JSON.stringify(body) : undefined, signal: AbortSignal.timeout(10000),
    });
    return { status: response.status, json: await response.json() };
  };
  const data = async route => {
    const response = await request(route, validToken);
    assert.equal(response.status, 200, route);
    return response.json.data;
  };
  let validToken;
  try {
    const login = await request('/auth/login', null, { username: 'admin', password: 'OpsPilot@2026' });
    assert.equal(login.status, 200);
    validToken = login.json.data.accessToken;
    assert.equal((await data('/auth/me')).roleCode, 'ADMIN');
    const timeline = JSON.stringify((await data('/incidents/1')).timeline);
    const notes = async () => (await data('/audit-logs?limit=500')).filter(row => row.action === 'INCIDENT_NOTE');
    const beforeNotes = JSON.stringify(await notes());
    // Raw JSON preserves long extrema exactly; no production signing key is needed for this regression.
    const responses = [];
    for (const field of ['exp', 'iat', 'nbf']) {
      for (const seconds of ['9223372036854775807', '-9223372036854775808']) {
        const future = Math.floor(Date.now() / 1000) + 60;
        const payload = '{"iss":"opspilot","sub":"admin","uid":1'
          + (field === 'exp' ? '' : ',"exp":' + future) + ',"' + field + '":' + seconds + '}';
        const input = Buffer.from('{"alg":"HS256","typ":"JWT"}').toString('base64url')
          + '.' + Buffer.from(payload).toString('base64url');
        const token = input + '.' + createHmac('sha256', 'different-test-key').update(input).digest('base64url');
        const read = await request('/auth/me', token);
        const write = await request('/incidents/1/notes', token, { content: 'CP60 malformed JWT must not write', evidenceRef: 'cp60:date-jar' });
        result.cases.push({ field, seconds, signatureMatchesRuntime: false, read: read.status, write: write.status });
        responses.push(read, write);
      }
    }
    assert.equal(JSON.stringify((await data('/incidents/1')).timeline), timeline);
    assert.equal(JSON.stringify(await notes()), beforeNotes);
    result.businessWritesUnchanged = true;
    for (const response of responses) {
      assert.equal(response.status, 401, 'Malformed NumericDate must not become a server error');
      assert.equal(response.json.success, false);
      assert.equal(response.json.error.code, 'AUTHENTICATION_REQUIRED');
      assert(response.json.data == null);
    }
    assert.equal((await request('/auth/me', validToken)).status, 200);
    result.normalAccountStillUsable = true;
    result.status = 'PASS';
  } catch (error) {
    result.status = 'FAIL';
    result.failure = error.message;
    throw error;
  } finally {
    fs.writeFileSync(path.join(out, 'result.json'), JSON.stringify(result, null, 2));
    console.log(JSON.stringify(result));
  }
}
verify().catch(error => { console.error(error.message); process.exitCode = 1; });

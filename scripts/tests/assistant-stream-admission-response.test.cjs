const { test } = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const { readJsonResponse } = require('../verify-assistant-slow-consumer-ci.cjs');

test('a real JSON rejection retains status and content type before parsing', async () => {
  const observations = [], body = { error: { code: 'ASSISTANT_STREAM_SATURATED' } };
  const response = new Response(JSON.stringify(body), { status: 503, headers: { 'Content-Type': 'application/json;charset=UTF-8' } });
  assert.deepEqual(await readJsonResponse(response, observations, '/sessions/1/stream'), { status: 503, json: body });
  assert.deepEqual(observations, [{ route: '/sessions/1/stream', status: 503, contentType: 'application/json;charset=UTF-8' }]);
});

test('held actual HTTP SSE fails at the headers and disconnects without waiting for JSON or completion', async () => {
  let closed = false;
  const server = http.createServer((request, response) => {
    response.writeHead(200, { 'Content-Type': 'text/event-stream' }); response.write('event:token\ndata:preview\n\n');
    response.once('close', () => { closed = true; });
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  try {
    const response = await fetch('http://127.0.0.1:' + server.address().port, { signal: AbortSignal.timeout(1000) }), observations = [];
    await assert.rejects(readJsonResponse(response, observations, '/sessions/2/stream'), /HTTP 200 Content-Type text\/event-stream/);
    assert.deepEqual(observations, [{ route: '/sessions/2/stream', status: 200, contentType: 'text/event-stream' }]);
    const deadline = performance.now() + 500;
    while (!closed && performance.now() < deadline) await new Promise(resolve => setTimeout(resolve, 5));
    assert.equal(closed, true);
  } finally { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); }
});

test('missing or non-JSON content type fails closed instead of parsing a plausible body', async () => {
  for (const type of [null, 'text/html', 'application/json-wrong']) {
    const response = new Response('{"status":"PASS"}', { status: 503 });
    response.headers.delete('content-type'); if (type) response.headers.set('content-type', type);
    const observations = [];
    await assert.rejects(readJsonResponse(response, observations, '/stream'), /Expected JSON response/);
    assert.equal(observations[0].contentType, type); assert.equal(response.bodyUsed, true);
  }
});

test('headers cannot turn HTTP 200 JSON into a successful saturation assertion', async () => {
  const response = await readJsonResponse(new Response('{}', { status: 200, headers: { 'Content-Type': 'application/json' } }));
  assert.equal(response.status, 200); assert.throws(() => assert.equal(response.status, 503));
});

test('invalid JSON keeps the actual headers and still rejects the parsing error', async () => {
  const observations = [];
  await assert.rejects(readJsonResponse(new Response('invalid', { status: 503, headers: { 'Content-Type': 'application/json' } }), observations, '/stream'), SyntaxError);
  assert.equal(observations[0].status, 503); assert.equal(observations.length, 1);
});

test('diagnostics retain only route, status and content type, never credentials or payload headers', async () => {
  const observations = [];
  await readJsonResponse(new Response('{}', { headers: { 'Content-Type': 'application/json', Authorization: 'secret-token', 'Idempotency-Key': 'secret-key' } }), observations, '/stream');
  assert.deepEqual(Object.keys(observations[0]), ['route', 'status', 'contentType']);
  assert.ok(!JSON.stringify(observations).includes('secret'));
});

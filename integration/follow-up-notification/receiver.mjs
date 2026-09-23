import http from 'node:http';

const expectedToken = process.env.FOLLOW_UP_NOTIFICATION_TOKEN;
if (!expectedToken) throw new Error('FOLLOW_UP_NOTIFICATION_TOKEN is required');

const records = [];
const seenKeys = new Set();

const server = http.createServer(async (request, response) => {
  if (request.method === 'GET' && request.url === '/health') {
    response.writeHead(200).end('ok');
    return;
  }
  if (request.method === 'GET' && request.url === '/records') {
    response.writeHead(200, { 'Content-Type': 'application/json' });
    response.end(JSON.stringify(records));
    return;
  }
  if (request.method !== 'POST' || request.url !== '/follow-ups') {
    response.writeHead(404).end();
    return;
  }

  let body = '';
  for await (const chunk of request) {
    body += chunk;
    if (body.length > 16384) {
      response.writeHead(413).end();
      return;
    }
  }
  const key = request.headers['idempotency-key'];
  const authValid = request.headers.authorization === `Bearer ${expectedToken}`;
  let payload;
  try {
    payload = JSON.parse(body);
  } catch {
    response.writeHead(400).end();
    return;
  }
  const status = !authValid || !key ? 401 : seenKeys.has(key) ? 204 : 503;
  if (authValid && key) seenKeys.add(key);
  records.push({ key, authValid, status, payload });
  response.writeHead(status).end();
});

server.listen(9911, '0.0.0.0');

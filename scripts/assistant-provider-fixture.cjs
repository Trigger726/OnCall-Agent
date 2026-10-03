const { randomUUID } = require('node:crypto');

// Test-only wire fixture: native requests receive actual DashScope SSE, never complete JSON disguised as SSE.
function reply(response, content, native) {
  if (response.destroyed) return;
  const completion = (text, finish_reason) => JSON.stringify({ request_id: randomUUID(), output: { choices: [
    { finish_reason, message: { role: 'assistant', content: text } }
  ] }, usage: { input_tokens: 10, output_tokens: 10, total_tokens: 20 } });
  if (native) {
    response.writeHead(200, { 'Content-Type': 'text/event-stream' });
    const split = Math.max(1, Math.floor(content.length / 2));
    response.write('data:' + completion(content.slice(0, split), 'null') + '\n\n');
    response.end('data:' + completion(content.slice(split), 'stop') + '\n\n');
  } else {
    response.writeHead(200, { 'Content-Type': 'application/json' });
    response.end(completion(content, 'stop'));
  }
}
module.exports = { reply };

import { beforeEach, test, mock } from 'node:test'
import assert from 'node:assert/strict'
import { build } from 'esbuild'
import { fileURLToPath } from 'node:url'

const built = await build({
  entryPoints: [fileURLToPath(new URL('../src/services/agentStream.ts', import.meta.url))],
  bundle: true, write: false, platform: 'node', format: 'esm',
  alias: { '@': fileURLToPath(new URL('../src', import.meta.url)) },
})
const { streamAgentInvestigation: stream } = await import(
  `data:text/javascript;base64,${Buffer.from(built.outputFiles[0].text).toString('base64')}`)

const storage = () => {
  const data = new Map()
  return { getItem: key => data.get(key) ?? null, setItem: (key, value) => data.set(key, value),
    removeItem: key => data.delete(key) }
}
const event = (id, type = 'RUN_STARTED', runId = 42) => ({ id, runId, sequence: id,
  eventType: type, phase: null, toolName: null, status: 'RUNNING', payloadJson: '{"text":"中文"}', createdAt: '' })
const frame = value => `data: ${JSON.stringify(value)}\n\n`
const response = (text, headers = {}) => new Response(text, { headers })
const key = 'opspilot_agent_idempotency_1'

beforeEach(() => {
  mock.restoreAll()
  globalThis.localStorage = storage()
  globalThis.sessionStorage = storage()
  globalThis.window = new EventTarget()
  mock.method(globalThis, 'setTimeout', callback => { queueMicrotask(callback); return 0 })
})

test('truncated POST resumes GET, ignores duplicates and rotates key only on terminal', async () => {
  const calls = []
  mock.method(globalThis, 'fetch', async (url, init) => {
    calls.push({ url, init })
    return calls.length === 1
      ? response(frame(event(1)) + 'data: {"id":2', { 'X-OpsPilot-Run-Id': '42' })
      : response(frame(event(1)) + frame(event(2, 'RUN_COMPLETED')))
  })
  const seen = []
  await stream(1, 'TEST', e => seen.push(e.id))
  assert.deepEqual(seen, [1, 2])
  assert.equal(calls[1].url, '/api/v1/agent-runs/42/events/stream?after=1')
  assert.equal(calls[1].init.method, 'GET')
  assert.equal(sessionStorage.getItem(key), null)
})

test('header recovers an empty reused POST before any event arrives', async () => {
  let calls = 0
  mock.method(globalThis, 'fetch', async url => {
    if (++calls === 1) return response('', { 'X-OpsPilot-Run-Id': '42' })
    assert.equal(url, '/api/v1/agent-runs/42/events/stream?after=0')
    return response(frame(event(1, 'RUN_COMPLETED')))
  })
  await stream(1, 'TEST', () => {})
  assert.equal(calls, 2)
})

test('ambiguous POST network failure reuses the original idempotency key', async () => {
  const keys = []
  mock.method(globalThis, 'fetch', async (_url, init) => {
    keys.push(init.headers['Idempotency-Key'])
    if (keys.length === 1) throw new TypeError('connection reset before headers')
    return response(frame(event(1, 'RUN_COMPLETED')))
  })
  await stream(1, 'TEST', () => {})
  assert.equal(keys.length, 2)
  assert.equal(keys[0], keys[1])
})

test('UTF-8 byte chunks, CRLF and multiline data parse without corruption', async () => {
  const data = JSON.stringify(event(1, 'RUN_COMPLETED')).replace(',"runId"', ',\n"runId"')
  const bytes = new TextEncoder().encode(data.split('\n').map(line => `data: ${line}`).join('\r\n') + '\r\n\r\n')
  mock.method(globalThis, 'fetch', async () => new Response(new ReadableStream({
    start(controller) { for (const byte of bytes) controller.enqueue(new Uint8Array([byte])); controller.close() },
  })))
  const seen = []
  await stream(1, 'TEST', e => seen.push(e))
  assert.equal(seen.length, 1)
  assert.equal(JSON.parse(seen[0].payloadJson).text, '中文')
})

test('all five terminal kinds stop reconnection, queue rejection remains actionable', async () => {
  for (const type of ['RUN_COMPLETED', 'RUN_FAILED', 'RUN_CANCELLED', 'RUN_TIMED_OUT', 'RUN_REJECTED']) {
    const fetcher = mock.method(globalThis, 'fetch', async () => response(frame(event(1, type))))
    const seen = []
    const pending = stream(1, 'TEST', e => seen.push(e.eventType))
    if (type === 'RUN_REJECTED') await assert.rejects(pending, { code: 'AGENT_QUEUE_SATURATED' })
    else await pending
    assert.deepEqual(seen, [type])
    assert.equal(fetcher.mock.callCount(), 1)
    assert.equal(sessionStorage.getItem(key), null)
    fetcher.mock.restore()
  }
})

test('401 expires authentication once without retrying or clearing the run key', async () => {
  let expired = 0
  window.addEventListener('opspilot-auth-expired', () => expired++)
  const fetcher = mock.method(globalThis, 'fetch', async () => new Response('{}', { status: 401 }))
  await assert.rejects(stream(1, 'TEST', () => {}), { status: 401 })
  assert.equal(expired, 1)
  assert.equal(fetcher.mock.callCount(), 1)
  assert.ok(sessionStorage.getItem(key))
})

test('no-progress retries have a hard limit and preserve key for manual recovery', async () => {
  const fetcher = mock.method(globalThis, 'fetch', async () => response('', { 'X-OpsPilot-Run-Id': '42' }))
  await assert.rejects(stream(1, 'TEST', () => {}), { code: 'AGENT_RECONNECT_EXHAUSTED' })
  assert.equal(fetcher.mock.callCount(), 6)
  assert.ok(sessionStorage.getItem(key))
})

test('abort during retry backoff stops requests without calling cancel API', async () => {
  const controller = new AbortController()
  mock.method(globalThis, 'setTimeout', () => { queueMicrotask(() => controller.abort()); return 0 })
  const fetcher = mock.method(globalThis, 'fetch', async () => response('', { 'X-OpsPilot-Run-Id': '42' }))
  await assert.rejects(stream(1, 'TEST', () => {}, controller.signal), { name: 'AbortError' })
  assert.equal(fetcher.mock.callCount(), 1)
  assert.ok(sessionStorage.getItem(key))
})

test('foreign events and handler errors never advance cursor or retry', async () => {
  const fetcher = mock.method(globalThis, 'fetch', async () => response(frame(event(1, 'RUN_STARTED', 9)),
    { 'X-OpsPilot-Run-Id': '42' }))
  await assert.rejects(stream(1, 'TEST', () => assert.fail('must not render foreign event')), { code: 'AGENT_EVENT_INVALID' })
  assert.equal(fetcher.mock.callCount(), 1)
  fetcher.mock.restore()
  mock.method(globalThis, 'fetch', async () => response(frame(event(1))))
  await assert.rejects(stream(1, 'TEST', () => { throw new Error('handler failure') }), { code: 'AGENT_EVENT_HANDLER_FAILED' })
})

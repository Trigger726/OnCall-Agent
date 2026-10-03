import { beforeEach, test, mock } from 'node:test'
import assert from 'node:assert/strict'
import { build } from 'esbuild'
import { fileURLToPath } from 'node:url'
import { getEventListeners } from 'node:events'

const built = await build({ entryPoints: [fileURLToPath(new URL('../src/services/assistantRequests.ts', import.meta.url))],
  bundle: true, write: false, platform: 'node', format: 'esm' })
const api = await import(`data:text/javascript;base64,${Buffer.from(built.outputFiles[0].text).toString('base64')}`)
const storage = () => {
  const data = new Map()
  return { getItem: k => data.get(k) ?? null, setItem: (k, v) => data.set(k, v), removeItem: k => data.delete(k), data }
}
const key = 'opspilot_assistant_request:v1:2:zhangwei:1'
const frame = (type, messageId = 42, content = '') => `data: ${JSON.stringify({ type, content, messageId, evidenceJson: null })}\n\n`
const sse = text => new Response(text, { headers: { 'Content-Type': 'text/event-stream' } })
const state = status => ({ id: 1, status, questionMessageId: 41, answerMessageId: status === 'COMPLETED' ? 42 : null, deadlineEpochMs: 1800000000000 })
const json = value => Response.json({ success: true, data: value })
beforeEach(() => {
  mock.restoreAll(); globalThis.localStorage = storage(); globalThis.sessionStorage = storage(); globalThis.window = new EventTarget()
  localStorage.setItem('opspilot_token', 'original-token')
  localStorage.setItem('opspilot_user', JSON.stringify({ id: 2, username: 'zhangwei' }))
})
test('freezes and persists the original question before POST; refresh uses the same key and cannot overwrite', () => {
  const frozen = api.freezeAssistantIntent(1, '  原问题  ')
  assert.ok(Object.isFrozen(frozen)); assert.equal(frozen.content, '原问题')
  assert.deepEqual(api.readAssistantIntent(1), frozen)
  assert.throws(() => api.freezeAssistantIntent(1, '另一问题'), /不会覆盖/)
  assert.equal(sessionStorage.data.size, 1)
  assert.ok(!sessionStorage.getItem(key).includes('original-token'))
})
test('account and session namespaces isolate pending intents; a refreshed token for the same owner may query', async () => {
  const original = api.freezeAssistantIntent(1, '原问题')
  assert.equal(api.readAssistantIntent(2), null)
  localStorage.setItem('opspilot_user', JSON.stringify({ id: 1, username: 'admin' }))
  assert.equal(api.readAssistantIntent(1), null)
  const fetcher = mock.method(globalThis, 'fetch', async () => json(state('RUNNING')))
  await assert.rejects(api.readAssistantRequest(original), { name: 'AbortError' }); assert.equal(fetcher.mock.callCount(), 0)
  localStorage.setItem('opspilot_user', JSON.stringify({ id: 2, username: 'zhangwei' })); localStorage.setItem('opspilot_token', 'fresh-token')
  await api.readAssistantRequest(api.readAssistantIntent(1)); assert.equal(fetcher.mock.calls[0].arguments[1].headers.Authorization, 'Bearer fresh-token')
})
test('storage failure, damaged intent and missing owner reject before any command', async () => {
  sessionStorage.setItem(key, '{'); assert.throws(() => api.readAssistantIntent(1), /损坏/)
  assert.throws(() => api.freezeAssistantIntent(1, '原问题'), /损坏/)
  api.removeUnreadableAssistantIntent(1); mock.method(sessionStorage, 'setItem', () => { throw new Error('storage full') })
  assert.throws(() => api.freezeAssistantIntent(1, '原问题'), /storage full/)
  localStorage.removeItem('opspilot_user'); assert.throws(() => api.readAssistantIntent(1), /重新登录/)
})
test('network failure is ambiguous, does not retry and retains exactly the frozen payload/key', async () => {
  const intent = api.freezeAssistantIntent(1, '原问题'), calls = []
  mock.method(globalThis, 'fetch', async (url, init) => { calls.push({ url, init }); throw new TypeError('network lost') })
  await assert.rejects(api.streamAssistantRequest(intent, () => {}), /network lost/)
  assert.equal(calls.length, 1); assert.deepEqual(api.readAssistantIntent(1), intent)
  assert.equal(calls[0].init.headers['Idempotency-Key'], intent.requestKey)
  assert.equal(calls[0].init.headers.Accept, 'text/event-stream, application/json')
  assert.deepEqual(JSON.parse(calls[0].init.body), { content: intent.content })
})
test('EOF without done, truncated done and unknown protocol cannot be claimed completed or auto-retried', async () => {
  for (const text of ['', frame('delta', 42, '片段'), frame('delta') + 'data: {"type":"done"}', frame('unknown')]) {
    const intent = api.freezeAssistantIntent(1, '原问题')
    const fetcher = mock.method(globalThis, 'fetch', async () => sse(text))
    await assert.rejects(api.streamAssistantRequest(intent, () => {})); assert.equal(fetcher.mock.callCount(), 1)
    assert.equal(api.readAssistantIntent(1).requestKey, intent.requestKey); api.clearAssistantIntent(intent); fetcher.mock.restore()
  }
})
test('UTF-8 one-byte chunks, CRLF and multi-line data require a real done and stable message ID', async () => {
  const intent = api.freezeAssistantIntent(1, '原问题')
  const bytes = new TextEncoder().encode((frame('meta') + frame('delta', 42, '中文') + frame('done')).replaceAll('\n', '\r\n'))
  mock.method(globalThis, 'fetch', async () => new Response(new ReadableStream({ start(c) { for (const b of bytes) c.enqueue(new Uint8Array([b])); c.close() } }),
    { headers: { 'Content-Type': 'text/event-stream' } }))
  const seen = []; assert.equal(await api.streamAssistantRequest(intent, e => seen.push(e)), 'done')
  assert.equal(seen[1].content, '中文'); assert.equal(seen[2].messageId, 42)
  assert.ok(api.readAssistantIntent(1), 'UI must reconcile state before clearing')
})
test('different answer IDs and non-SSE success are unsafe; explicit cancelled is a distinct terminal', async () => {
  const intent = api.freezeAssistantIntent(1, '原问题')
  const fetcher = mock.method(globalThis, 'fetch', async () => sse(frame('meta') + frame('done', 43)))
  await assert.rejects(api.streamAssistantRequest(intent, () => {}), { code: 'ASSISTANT_INVALID_STREAM' })
  fetcher.mock.restore(); mock.method(globalThis, 'fetch', async () => json(state('COMPLETED')))
  await assert.rejects(api.streamAssistantRequest(intent, () => {}), { code: 'ASSISTANT_INVALID_STREAM' })
  mock.restoreAll(); mock.method(globalThis, 'fetch', async () => sse(frame('cancelled', null, '回答已取消')))
  assert.equal(await api.streamAssistantRequest(intent, () => {}), 'cancelled')
})
test('query and cancellation use original key, no question body, no automatic POST after 404/409/503', async () => {
  const intent = api.freezeAssistantIntent(1, '原问题'), calls = []
  mock.method(globalThis, 'fetch', async (url, init) => { calls.push({ url, init }); return json(state('CANCELLED')) })
  await api.readAssistantRequest(intent); await api.cancelAssistantRequest(intent)
  assert.deepEqual(calls.map(x => x.init.method), ['GET', 'POST']); assert.ok(calls.every(x => x.init.headers['Idempotency-Key'] === intent.requestKey && !x.init.body))
  mock.restoreAll()
  for (const status of [404, 409, 503]) {
    const fetcher = mock.method(globalThis, 'fetch', async () => Response.json({ success: false, error: { code: 'EXACT_CODE', message: '服务反馈' } }, { status }))
    await assert.rejects(api.readAssistantRequest(intent), { status, code: 'EXACT_CODE' }); assert.equal(fetcher.mock.callCount(), 1); fetcher.mock.restore()
  }
  assert.deepEqual(api.readAssistantIntent(1), intent)
})
test('completed before cancellation preserves the original IDs; all documented statuses validate', async () => {
  const intent = api.freezeAssistantIntent(1, '原问题')
  const fetcher = mock.method(globalThis, 'fetch', async () => json(state('COMPLETED')))
  assert.equal((await api.cancelAssistantRequest(intent)).answerMessageId, 42); fetcher.mock.restore()
  for (const status of ['QUEUED', 'RUNNING', 'CANCELLED', 'TIMED_OUT', 'REVOKED', 'SUPERSEDED', 'FAILED']) {
    const next = mock.method(globalThis, 'fetch', async () => json(state(status)))
    assert.equal((await api.readAssistantRequest(intent)).status, status); next.mock.restore()
  }
  mock.method(globalThis, 'fetch', async () => json({ ...state('COMPLETED'), answerMessageId: null }))
  await assert.rejects(api.readAssistantRequest(intent), { code: 'ASSISTANT_INVALID_STATE' })
})
test('lost cancellation response stays ambiguous and can be queried without a second cancellation', async () => {
  const intent = api.freezeAssistantIntent(1, '原问题'), calls = []
  mock.method(globalThis, 'fetch', async (_url, init) => { calls.push(init.method); if (init.method === 'POST') throw new TypeError('cancel response lost'); return json(state('CANCELLED')) })
  await assert.rejects(api.cancelAssistantRequest(intent), /lost/); assert.ok(api.readAssistantIntent(1))
  assert.equal((await api.readAssistantRequest(intent)).status, 'CANCELLED'); assert.deepEqual(calls, ['POST', 'GET'])
})
test('late old 401 and late old success cannot expire or update the replacement account', async () => {
  for (const response of [Response.json({}, { status: 401 }), json(state('COMPLETED'))]) {
    const intent = api.freezeAssistantIntent(1, '原问题'); let expired = 0
    const listener = () => expired++; window.addEventListener('opspilot-auth-expired', listener)
    const fetcher = mock.method(globalThis, 'fetch', async () => {
      localStorage.setItem('opspilot_token', 'new-token'); return response
    })
    await assert.rejects(api.readAssistantRequest(intent), { name: 'AbortError' }); assert.equal(expired, 0)
    assert.ok(api.readAssistantIntent(1)); api.clearAssistantIntent(intent); fetcher.mock.restore()
    window.removeEventListener('opspilot-auth-expired', listener); localStorage.setItem('opspilot_token', 'original-token')
  }
})
test('current 401 expires once and keeps the recoverable intent', async () => {
  const intent = api.freezeAssistantIntent(1, '原问题'); let expired = 0
  window.addEventListener('opspilot-auth-expired', () => expired++)
  mock.method(globalThis, 'fetch', async () => Response.json({}, { status: 401 }))
  await assert.rejects(api.cancelAssistantRequest(intent), { status: 401 }); assert.equal(expired, 1)
  assert.ok(api.readAssistantIntent(1))
})
test('caller abort and identity change while reading cancel the reader without backend cancellation', async () => {
  const intent = api.freezeAssistantIntent(1, '原问题'), controller = new AbortController(); let cancelled = 0, calls = 0
  mock.method(globalThis, 'fetch', async () => { calls++; return new Response(new ReadableStream({ cancel() { cancelled++ } }), { headers: { 'Content-Type': 'text/event-stream' } }) })
  const running = api.streamAssistantRequest(intent, () => {}, controller.signal)
  await new Promise(resolve => setImmediate(resolve)); controller.abort()
  await assert.rejects(running, { name: 'AbortError' }); assert.equal(cancelled, 1); assert.equal(calls, 1)
  assert.ok(api.readAssistantIntent(1))
})
test('owner metadata change after fetch is fenced even if token did not change', async () => {
  const intent = api.freezeAssistantIntent(1, '原问题'), seen = []
  mock.method(globalThis, 'fetch', async () => { localStorage.setItem('opspilot_user', JSON.stringify({ id: 1, username: 'admin' })); return sse(frame('done')) })
  await assert.rejects(api.streamAssistantRequest(intent, e => seen.push(e)), { name: 'AbortError' }); assert.deepEqual(seen, [])
})

test('late token change during JSON decoding fences query and cancel results', async () => {
  const intent = api.freezeAssistantIntent(1, '原问题')
  mock.method(globalThis, 'fetch', async () => ({ ok: true, status: 200, json: async () => {
    localStorage.setItem('opspilot_token', 'replacement'); return { success: true, data: state('CANCELLED') }
  } }))
  await assert.rejects(api.cancelAssistantRequest(intent), { name: 'AbortError' })
  assert.ok(api.readAssistantIntent(1))
})
test('every helper releases account listeners on success and failure', async () => {
  const intent = api.freezeAssistantIntent(1, '原问题')
  for (const response of [json(state('RUNNING')), Response.json({}, { status: 404 }), sse(frame('done')), sse(frame('error', null, '超时'))]) {
    const fetcher = mock.method(globalThis, 'fetch', async () => response)
    if (response.headers.get('content-type')?.includes('text/event-stream')) await api.streamAssistantRequest(intent, () => {}).catch(() => {})
    else await api.readAssistantRequest(intent).catch(() => {})
    assert.equal(getEventListeners(window, 'storage').length, 0)
    assert.equal(getEventListeners(window, 'opspilot-auth-session-changed').length, 0); fetcher.mock.restore()
  }
})
test('already aborted caller never reaches fetch or removes original storage', async () => {
  const intent = api.freezeAssistantIntent(1, '原问题'), controller = new AbortController(); controller.abort()
  const fetcher = mock.method(globalThis, 'fetch', async () => json(state('COMPLETED')))
  await assert.rejects(api.streamAssistantRequest(intent, () => {}, controller.signal), { name: 'AbortError' })
  await assert.rejects(api.cancelAssistantRequest(intent, controller.signal), { name: 'AbortError' })
  assert.equal(fetcher.mock.callCount(), 0); assert.ok(api.readAssistantIntent(1))
})
test('manual continuation after a missing request sends byte-identical payload without rotating key', async () => {
  const intent = api.freezeAssistantIntent(1, '原问题'), calls = []
  mock.method(globalThis, 'fetch', async (url, init) => {
    calls.push({ url, init }); return init.method === 'GET' ? Response.json({}, { status: 404 }) : sse(frame('done'))
  })
  await assert.rejects(api.readAssistantRequest(intent), { status: 404 })
  assert.equal(calls.length, 1)
  await api.streamAssistantRequest(api.readAssistantIntent(1), () => {})
  assert.equal(calls.length, 2); assert.equal(calls[0].init.headers['Idempotency-Key'], calls[1].init.headers['Idempotency-Key'])
  assert.equal(calls[1].init.body, JSON.stringify({ content: '原问题' }))
})

test('empty 503 response preserves HTTP status and gives actionable unavailability feedback, not a guessed terminal', async () => {
  const intent = api.freezeAssistantIntent(1, '原问题')
  const fetcher = mock.method(globalThis, 'fetch', async () => new Response('', { status: 503 }))
  await assert.rejects(api.streamAssistantRequest(intent, () => {}), error => error.status === 503 && /不可用或队列已满/.test(error.message))
  assert.equal(fetcher.mock.callCount(), 1); assert.deepEqual(api.readAssistantIntent(1), intent)
})

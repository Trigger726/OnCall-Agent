import { beforeEach, test, mock } from 'node:test'
import assert from 'node:assert/strict'
import { build } from 'esbuild'
import { fileURLToPath } from 'node:url'
import { getEventListeners } from 'node:events'

const built = await build({
  entryPoints: [fileURLToPath(new URL('../src/services/agentStream.ts', import.meta.url))],
  bundle: true, write: false, platform: 'node', format: 'esm',
  alias: { '@': fileURLToPath(new URL('../src', import.meta.url)) },
})
const { streamAgentInvestigation: stream, subscribeAgentInvestigation: subscribe } = await import(
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
const key = 'opspilot_agent_idempotency_2_zhangwei_1'

beforeEach(() => {
  mock.restoreAll()
  globalThis.localStorage = storage()
  localStorage.setItem('opspilot_token', 'original-session')
  localStorage.setItem('opspilot_user', JSON.stringify({ id: 2, username: 'zhangwei' }))
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
  assert.ok(calls.every(c => c.init.headers.Accept === 'text/event-stream'))
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

test('fresh-page subscription is GET-only, replays history and does not touch POST keys', async () => {
  sessionStorage.setItem(key, 'unrelated-pending-request')
  const calls = []
  mock.method(globalThis, 'fetch', async (url, init) => {
    calls.push({ url, init })
    return calls.length === 1 ? response(frame(event(1)))
      : response(frame(event(1)) + frame(event(2, 'RUN_COMPLETED')))
  })
  const seen = []
  await subscribe(42, e => seen.push(e.id))
  assert.deepEqual(seen, [1, 2])
  assert.deepEqual(calls.map(c => c.url), [
    '/api/v1/agent-runs/42/events/stream?after=0', '/api/v1/agent-runs/42/events/stream?after=1',
  ])
  assert.ok(calls.every(c => c.init.method === 'GET' && !('Idempotency-Key' in c.init.headers)))
  assert.ok(calls.every(c => c.init.headers.Accept === 'text/event-stream'))
  assert.equal(sessionStorage.getItem(key), 'unrelated-pending-request')
})

test('subscription rejects invalid IDs and foreign events without any start request', async () => {
  const fetcher = mock.method(globalThis, 'fetch', async () => response(frame(event(1, 'RUN_STARTED', 9))))
  for (const id of [0, -1, NaN, 1.2, Number.MAX_SAFE_INTEGER + 1]) {
    await assert.rejects(subscribe(id, () => {}), { code: 'AGENT_RUN_INVALID' })
  }
  assert.equal(fetcher.mock.callCount(), 0)
  await assert.rejects(subscribe(42, () => assert.fail('foreign event')), { code: 'AGENT_EVENT_INVALID' })
  assert.equal(fetcher.mock.calls[0].arguments[1].method, 'GET')
})

test('failed auto-attachment has bounded retries and never becomes a POST', async () => {
  const fetcher = mock.method(globalThis, 'fetch', async () => { throw new TypeError('offline') })
  await assert.rejects(subscribe(42, () => {}), { code: 'AGENT_RECONNECT_EXHAUSTED' })
  assert.equal(fetcher.mock.callCount(), 6)
  assert.ok(fetcher.mock.calls.every(c => c.arguments[1].method === 'GET'))
  assert.equal(sessionStorage.getItem(key), null)
})

test('already aborted subscription neither fetches nor touches browser storage', async () => {
  const fetcher = mock.method(globalThis, 'fetch', async () => assert.fail('no request expected'))
  await assert.rejects(subscribe(42, () => {}, AbortSignal.abort()), { name: 'AbortError' })
  assert.equal(fetcher.mock.callCount(), 0)
  assert.equal(sessionStorage.getItem(key), null)
})

for (const kind of ['POST', 'GET']) {
  test(`late ${kind} stream 401 cannot expire a newly signed-in session`, async () => {
    let expired = 0
    window.addEventListener('opspilot-auth-expired', () => expired++)
    const fetcher = mock.method(globalThis, 'fetch', async () => {
      localStorage.setItem('opspilot_token', 'new-session')
      return Response.json({ error: { code: 'AUTHENTICATION_REQUIRED' } }, { status: 401 })
    })
    let failure
    try { await (kind === 'POST' ? stream(1, 'TEST', () => {}) : subscribe(42, () => {})) }
    catch (caught) { failure = caught }
    assert.equal(expired, 0, 'Old response must not expire the replacement credential')
    assert.equal(failure?.name, 'AbortError')
    assert.equal(fetcher.mock.callCount(), 1)
    assert.equal(localStorage.getItem('opspilot_token'), 'new-session')
  })
}

test('late successful stream frames cannot render under a replacement session', async () => {
  const seen = []
  mock.method(globalThis, 'fetch', async () => {
    localStorage.setItem('opspilot_token', 'new-session')
    return response(frame(event(1, 'RUN_COMPLETED')), { 'X-OpsPilot-Run-Id': '42' })
  })
  let failure
  try { await stream(1, 'TEST', value => seen.push(value)) } catch (caught) { failure = caught }
  assert.deepEqual(seen, [])
  assert.equal(failure?.name, 'AbortError')
})

test('a reconnect never switches to newly signed-in credentials', async () => {
  const calls = []
  mock.method(globalThis, 'setTimeout', callback => {
    localStorage.setItem('opspilot_token', 'new-session')
    queueMicrotask(callback); return 0
  })
  mock.method(globalThis, 'fetch', async (url, init) => {
    calls.push({ url, token: init.headers.Authorization })
    return calls.length === 1 ? response(frame(event(1))) : response(frame(event(2, 'RUN_COMPLETED')))
  })
  let failure
  try { await subscribe(42, () => {}) } catch (caught) { failure = caught }
  assert.equal(calls.length, 1)
  assert.equal(failure?.name, 'AbortError')
})

test('pending POST idempotency keys are isolated by the verified UI account identity', async () => {
  const oldKeys = []
  const failed = mock.method(globalThis, 'fetch', async (_url, init) => {
    oldKeys.push(init.headers['Idempotency-Key']); throw new TypeError('response lost')
  })
  await assert.rejects(stream(1, 'TEST', () => {}), { code: 'AGENT_RECONNECT_EXHAUSTED' })
  failed.mock.restore()
  localStorage.setItem('opspilot_token', 'admin-session')
  localStorage.setItem('opspilot_user', JSON.stringify({ id: 1, username: 'admin' }))
  let currentKey
  mock.method(globalThis, 'fetch', async (_url, init) => {
    currentKey = init.headers['Idempotency-Key']; return response(frame(event(1, 'RUN_COMPLETED')))
  })
  await stream(1, 'TEST', () => {})
  assert.notEqual(currentKey, oldKeys[0], 'A different actor must not inherit the ambiguous old command')
  assert.ok(oldKeys.every(value => value === oldKeys[0]))
})

for (const trigger of ['storage', 'opspilot-auth-session-changed', 'caller-abort']) {
  test(`${trigger} cancels a pending reader and releases every session listener`, async () => {
    const controller = new AbortController()
    let cancelled = 0
    let transportSignal
    mock.method(globalThis, 'fetch', async (_url, init) => {
      transportSignal = init.signal
      return new Response(new ReadableStream({
        start(source) { source.enqueue(new TextEncoder().encode(frame(event(1)))) },
        cancel() { cancelled++ },
      }))
    })
    const seen = []
    await assert.rejects(subscribe(42, value => {
      seen.push(value.id)
      queueMicrotask(() => {
        if (trigger === 'caller-abort') controller.abort()
        else {
          localStorage.setItem('opspilot_token', 'replacement-session')
          window.dispatchEvent(new Event(trigger))
        }
      })
    }, controller.signal), { name: 'AbortError' })
    assert.deepEqual(seen, [1])
    assert.equal(cancelled, 1)
    assert.equal(transportSignal.aborted, true)
    assert.equal(getEventListeners(window, 'storage').length, 0)
    assert.equal(getEventListeners(window, 'opspilot-auth-session-changed').length, 0)
    assert.equal(getEventListeners(controller.signal, 'abort').length, 0)
  })
}

test('successful and failed streams release listeners without aborting caller or cancelling server runs', async () => {
  for (const status of [200, 403]) {
    const controller = new AbortController()
    const fetcher = mock.method(globalThis, 'fetch', async () => status === 200
      ? response(frame(event(1, 'RUN_COMPLETED'))) : new Response('{}', { status }))
    const pending = subscribe(42, () => {}, controller.signal)
    if (status === 200) await pending
    else await assert.rejects(pending, { status })
    assert.equal(fetcher.mock.callCount(), 1)
    assert.equal(controller.signal.aborted, false)
    for (const name of ['storage', 'opspilot-auth-session-changed']) {
      assert.equal(getEventListeners(window, name).length, 0)
    }
    assert.equal(getEventListeners(controller.signal, 'abort').length, 0)
    fetcher.mock.restore()
  }
})

test('unrelated storage notifications do not interrupt a current authenticated stream', async () => {
  mock.method(globalThis, 'fetch', async () => {
    window.dispatchEvent(new Event('storage'))
    return response(frame(event(1, 'RUN_COMPLETED')))
  })
  const seen = []
  await subscribe(42, value => seen.push(value.id))
  assert.deepEqual(seen, [1])
})

test('malformed account namespaces fail before POST without inheriting a legacy unowned key', async () => {
  const legacy = 'opspilot_agent_idempotency_1'
  sessionStorage.setItem(legacy, 'unowned-old-request')
  const fetcher = mock.method(globalThis, 'fetch', async () => response(frame(event(1, 'RUN_COMPLETED'))))
  for (const identity of ['broken JSON', 'null', '{}', '{"id":2,"username":7}', '{"id":0,"username":"admin"}']) {
    localStorage.setItem('opspilot_user', identity)
    await assert.rejects(stream(1, 'TEST', () => {}), { code: 'AGENT_ACCOUNT_INVALID' })
    assert.equal(getEventListeners(window, 'storage').length, 0)
  }
  assert.equal(fetcher.mock.callCount(), 0)
  localStorage.setItem('opspilot_user', JSON.stringify({ id: 2, username: 'zhangwei' }))
  await stream(1, 'TEST', () => {})
  assert.notEqual(fetcher.mock.calls[0].arguments[1].headers['Idempotency-Key'], 'unowned-old-request')
  assert.equal(sessionStorage.getItem(legacy), 'unowned-old-request')
})

test('a fresh login of the same actor preserves an ambiguous command for same-key recovery', async () => {
  const failed = mock.method(globalThis, 'fetch', async () => { throw new TypeError('response lost') })
  await assert.rejects(stream(1, 'TEST', () => {}), { code: 'AGENT_RECONNECT_EXHAUSTED' })
  const originalKey = sessionStorage.getItem(key)
  failed.mock.restore()
  localStorage.setItem('opspilot_token', 'replacement-same-actor-session')
  const fetched = mock.method(globalThis, 'fetch', async (_url, init) => {
    assert.equal(init.headers['Idempotency-Key'], originalKey)
    return response(frame(event(1, 'RUN_COMPLETED')))
  })
  await stream(1, 'TEST', () => {})
  assert.equal(fetched.mock.callCount(), 1)
  assert.equal(sessionStorage.getItem(key), null)
})

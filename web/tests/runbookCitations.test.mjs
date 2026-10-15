import { beforeEach, test, mock } from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { build } from 'esbuild'
import { getEventListeners } from 'node:events'
import { fileURLToPath } from 'node:url'

const built = await build({ entryPoints: [fileURLToPath(new URL('../src/services/runbookCitations.ts', import.meta.url))], bundle: true, write: false, platform: 'node', format: 'esm' })
const api = await import(`data:text/javascript;base64,${Buffer.from(built.outputFiles[0].text).toString('base64')}`)
const citation = 'runbook:redis-pool:v1#chunk-0'
const original = { citation, stableKey: 'redis-pool', versionNo: 1, chunkIndex: 0, status: 'SUPERSEDED', title: '连接池', heading: '检查', content: '  旧原文\n<script>不执行</script>🙂  ', sourceName: 'redis.md' }
const reply = data => Response.json({ success: true, data })
beforeEach(() => {
  mock.restoreAll(); const store = new Map([['opspilot_token', 'original-token']])
  globalThis.localStorage = { getItem: key => store.get(key) ?? null, setItem: (key, value) => store.set(key, value) }
  globalThis.window = new EventTarget()
})

test('only canonical bounded runbook coordinates are actionable, never arbitrary links', () => {
  assert.deepEqual(api.parseRunbookCitation(citation), { stableKey: 'redis-pool', versionNo: 1, chunkIndex: 0 })
  for (const invalid of ['https://example.com', 'javascript:alert(1)', 'runbook:../secret:v1#chunk-0', 'runbook:a:v1#chunk-0',
    citation + '\n', citation + '?next=//evil', citation.replace(':v1', ':v01'), citation.replace('chunk-0', 'chunk-01'),
    citation.replace(':v1', ':v0'), citation.replace(':v1', ':v2147483648'), citation.replace('chunk-0', 'chunk-2147483648')]) assert.equal(api.parseRunbookCitation(invalid), null, invalid)
})
test('reads exactly the saved historical version and preserves raw content', async () => {
  const fetcher = mock.method(globalThis, 'fetch', async (url, options) => {
    assert.equal(url, '/api/v1/runbooks/redis-pool/versions/1/chunks/0');assert.equal(options.headers.Authorization, 'Bearer original-token')
    return reply(original)
  })
  assert.deepEqual(await api.readRunbookCitation(citation), original);assert.equal(fetcher.mock.callCount(), 1)
  assert.equal(getEventListeners(window, 'storage').length, 0);assert.equal(getEventListeners(window, 'opspilot-auth-session-changed').length, 0)
})
test('bad coordinates or no login never make an HTTP request', async () => {
  const fetcher = mock.method(globalThis, 'fetch', async () => reply(original))
  await assert.rejects(api.readRunbookCitation('bad'), { code: 'RUNBOOK_CITATION_INVALID' })
  localStorage.setItem('opspilot_token', '');await assert.rejects(api.readRunbookCitation(citation), { code: 'AUTHENTICATION_REQUIRED' })
  assert.equal(fetcher.mock.callCount(), 0)
})
test('mismatched versions, chunks, identifiers, statuses and malformed payloads are never sources', async () => {
  for (const data of [{ ...original, citation: 'wrong' }, { ...original, stableKey: 'wrong' }, { ...original, versionNo: 2 },
    { ...original, chunkIndex: 1 }, { ...original, status: 'PENDING_REVIEW' }, { ...original, content: null }, { ...original, title: null }, null]) {
    const fetcher = mock.method(globalThis, 'fetch', async () => reply(data))
    await assert.rejects(api.readRunbookCitation(citation), { code: 'RUNBOOK_CITATION_MISMATCH' });fetcher.mock.restore()
  }
})
test('denied and missing are the same user-visible failure, no fallback to newest version', async () => {
  const fetcher = mock.method(globalThis, 'fetch', async () => Response.json({ error: { code: 'RUNBOOK_CITATION_NOT_FOUND' } }, { status: 404 }))
  await assert.rejects(api.readRunbookCitation(citation), { code: 'RUNBOOK_CITATION_NOT_FOUND', message: '引用原文不存在或当前无权查看' })
  assert.equal(fetcher.mock.callCount(), 1)
})
test('account change while reading JSON discards stale source and releases listeners', async () => {
  mock.method(globalThis, 'fetch', async () => ({ ok: true, status: 200, json: async () => { localStorage.setItem('opspilot_token', 'replacement');return { success: true, data: original } } }))
  await assert.rejects(api.readRunbookCitation(citation), { name: 'AbortError' })
  assert.equal(getEventListeners(window, 'storage').length, 0)
})
test('close aborts original HTTP read, with no retry and no source', async () => {
  const controller = new AbortController()
  const fetcher = mock.method(globalThis, 'fetch', async (url, options) => { controller.abort();options.signal.throwIfAborted();return reply(original) })
  await assert.rejects(api.readRunbookCitation(citation, controller.signal), { name: 'AbortError' })
  assert.equal(fetcher.mock.callCount(), 1);assert.equal(getEventListeners(window, 'storage').length, 0)
})
test('expired current authentication dispatches the shared expiry event once', async () => {
  let expired = 0;window.addEventListener('opspilot-auth-expired', () => expired++)
  mock.method(globalThis, 'fetch', async () => new Response('', { status: 401 }))
  await assert.rejects(api.readRunbookCitation(citation), { code: 'AUTHENTICATION_REQUIRED' });assert.equal(expired, 1)
})
test('reader uses native modal, escaped original text, original-version warning and abort cleanup', async () => {
  const dialog = await readFile(new URL('../src/components/RunbookCitationDialog.vue', import.meta.url), 'utf8')
  assert.match(dialog, /\.showModal\(\)/);assert.match(dialog, /aria-labelledby="runbook-source-title"/)
  assert.match(dialog, /<pre class="source-content">\{\{ source.content \}\}<\/pre>/);assert.doesNotMatch(dialog, /v-html|innerHTML/)
  assert.match(dialog, /历史版本，非当前处置指引/);assert.match(dialog, /onBeforeUnmount.*abort/)
  assert.match(dialog, /@keydown="onKeydown"/);assert.match(dialog, /event\.shiftKey && document\.activeElement === first/)
  assert.match(dialog, /!event\.shiftKey && document\.activeElement === last/)
})

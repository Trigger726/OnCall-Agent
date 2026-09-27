import { test, mock } from 'node:test'
import assert from 'node:assert/strict'
import { build } from 'esbuild'
import { fileURLToPath } from 'node:url'

const built = await build({ entryPoints: [fileURLToPath(new URL('../src/services/runbookPublications.ts', import.meta.url))], bundle: true, write: false, platform: 'node', format: 'esm' })
const publication = await import(`data:text/javascript;base64,${Buffer.from(built.outputFiles[0].text).toString('base64')}`)
const uuid = '12345678-1234-4234-8234-123456789abc'
const draft = () => publication.capturePublicationIntent(3, 12, 0, 'APPROVE', '  独立复核  ', uuid)
function storage() {
  const entries = new Map()
  return { entries, getItem: key => entries.get(key) ?? null, setItem: (key, value) => entries.set(key, value), removeItem: key => entries.delete(key) }
}

test('capture binds actor document version canonical key decision and normalized reason', () => {
  assert.deepEqual(draft(), { actorId: 3, id: 12, expectedVersion: 0, requestKey: uuid, decision: 'APPROVE', reason: '独立复核', locked: false })
  for (const args of [[0,12,0,'APPROVE','说明',uuid], [3,-1,0,'APPROVE','说明',uuid],
    [3,12,-1,'APPROVE','说明',uuid], [3,12,0,'PUBLISH','说明',uuid], [3,12,0,'APPROVE',' ',uuid],
    [3,12,0,'APPROVE','x'.repeat(501),uuid], [3,12,0,'APPROVE','说明','bad']]) {
    assert.throws(() => publication.capturePublicationIntent(...args))
  }
})

test('storage restores exact frozen command and lock only for its actor', () => {
  const store = storage(), intent = { ...draft(), locked: true }
  publication.persistPublicationIntent(store, intent)
  assert.deepEqual(publication.readPublicationIntent(store, 3), intent)
  assert.equal(publication.readPublicationIntent(store, 1), null)
  assert.equal(intent.expectedVersion, 0)
})

test('corrupt or foreign stored command fails closed and original bytes remain available', () => {
  const store = storage(), key = publication.publicationStorageKey(3)
  for (const raw of ['invalid-json', '{}', JSON.stringify({ ...draft(), actorId: 1 }),
    JSON.stringify({ ...draft(), locked: 'false' }), JSON.stringify({ ...draft(), reason: '' })]) {
    store.setItem(key, raw)
    assert.throws(() => publication.readPublicationIntent(store, 3), /冻结意图/)
    assert.equal(store.getItem(key), raw)
  }
})

test('quota failure propagates before command can be sent', async () => {
  let posts = 0
  mock.method(globalThis, 'fetch', async () => { posts++; throw new Error('unexpected POST') })
  try {
    const store = storage()
    store.setItem = () => { throw new Error('quota') }
    await assert.rejects(async () => {
      publication.persistPublicationIntent(store, draft())
      await publication.sendPublicationDecision(draft(), 3)
    }, /quota/)
    assert.equal(posts, 0)
  } finally { mock.restoreAll() }
})

test('ambiguous response requires manual retry of byte-identical payload without automatic retry', async () => {
  globalThis.localStorage = { getItem: () => 'fixture-access' }
  const payloads = [], intent = draft()
  mock.method(globalThis, 'fetch', async (url, options) => {
    assert.equal(url, '/api/v1/runbooks/publications/12/decisions')
    payloads.push(options.body)
    if (payloads.length === 1) throw new Error('response lost')
    return new Response(JSON.stringify({ success: true, data: { reviewVersion: 1 } }), { status: 200 })
  })
  try {
    await assert.rejects(publication.sendPublicationDecision(intent, 3), /response lost/)
    assert.equal(payloads.length, 1)
    assert.deepEqual(await publication.sendPublicationDecision(intent, 3), { reviewVersion: 1 })
    assert.equal(payloads[0], payloads[1])
    assert.deepEqual(JSON.parse(payloads[0]), { expectedVersion: 0, requestKey: uuid, decision: 'APPROVE', reason: '独立复核' })
  } finally { mock.restoreAll(); delete globalThis.localStorage }
})

test('403 and 409 are terminal intent errors and never rebase or retry', async () => {
  globalThis.localStorage = { getItem: () => 'fixture-access' }
  let posts = 0
  try {
    for (const status of [403,409]) {
      mock.method(globalThis, 'fetch', async () => { posts++; return new Response(JSON.stringify({ success: false,
        error: { code: 'FIXTURE_REJECTION', message: '拒绝' } }), { status }) })
      await assert.rejects(publication.sendPublicationDecision(draft(), 3), error => publication.isPublicationIntentRejected(error))
      mock.restoreAll()
    }
    assert.equal(posts, 2)
    assert.equal(publication.isPublicationIntentRejected(new Error('network')), false)
  } finally { mock.restoreAll(); delete globalThis.localStorage }
})

test('locked command or switched actor cannot reach fetch', async () => {
  let posts = 0
  mock.method(globalThis, 'fetch', async () => { posts++; throw new Error('unexpected POST') })
  try {
    await assert.rejects(publication.sendPublicationDecision({ ...draft(), locked: true }, 3))
    await assert.rejects(publication.sendPublicationDecision(draft(), 1))
    assert.equal(posts, 0)
  } finally { mock.restoreAll() }
})

test('explicit discard removes only that actor local intent and sends no cancellation', () => {
  const store = storage()
  publication.persistPublicationIntent(store, draft())
  publication.persistPublicationIntent(store, { ...draft(), actorId: 1 })
  publication.discardPublicationIntent(store, 3)
  assert.equal(publication.readPublicationIntent(store, 3), null)
  assert.equal(publication.readPublicationIntent(store, 1).actorId, 1)
})

import { test, mock } from 'node:test'
import assert from 'node:assert/strict'
import { build } from 'esbuild'
import { fileURLToPath } from 'node:url'

const built = await build({ entryPoints: [fileURLToPath(new URL('../src/services/runbookTrends.ts', import.meta.url))], bundle: true, write: false, platform: 'node', format: 'esm' })
const trend = await import(`data:text/javascript;base64,${Buffer.from(built.outputFiles[0].text).toString('base64')}`)
test('null quality remains N/A while real zero remains 0%', () => {
  assert.equal(trend.trendPercent(null), 'N/A')
  assert.equal(trend.trendPercent(undefined), 'N/A')
  assert.equal(trend.trendPercent(NaN), 'N/A')
  assert.equal(trend.trendPercent(0), '0.0%')
  assert.equal(trend.trendPercent(1 / 3), '33.3%')
})
test('trend uses exact source actual engine K and literal date strings without timezone conversion', async () => {
  mock.method(globalThis, 'fetch', async (url, options) => {
    const parsed = new URL(url, 'http://localhost')
    assert.equal(parsed.pathname, '/api/v1/runbooks/searches/trend')
    assert.equal(parsed.searchParams.get('from'), '2026-09-01')
    assert.equal(parsed.searchParams.get('to'), '2026-09-28')
    assert.equal(parsed.searchParams.get('source'), 'AGENT')
    assert.equal(parsed.searchParams.get('engine'), 'BM25_LOCAL_V1')
    assert.equal(parsed.searchParams.get('topK'), '3')
    assert.equal(options.method, undefined)
    return new Response(JSON.stringify({ success: true, data: { days: [] } }))
  })
  globalThis.localStorage = { getItem: () => null }
  try { assert.deepEqual(await trend.loadRetrievalTrend({ from: '2026-09-01', to: '2026-09-28', source: 'AGENT', engine: 'BM25_LOCAL_V1', topK: 3 }), { days: [] }) }
  finally { mock.restoreAll(); delete globalThis.localStorage }
})
test('default date window belongs to database and failed GET is never replaced or retried', async () => {
  let calls = 0
  mock.method(globalThis, 'fetch', async url => {
    calls++
    const parsed = new URL(url, 'http://localhost')
    assert.equal(parsed.searchParams.has('from'), false)
    assert.equal(parsed.searchParams.has('to'), false)
    return new Response(JSON.stringify({ success: false, error: { code: 'TEST_FAILURE', message: 'unavailable' } }), { status: 503 })
  })
  globalThis.localStorage = { getItem: () => null }
  try {
    await assert.rejects(trend.loadRetrievalTrend({ from: '', to: '', source: 'CONSOLE', engine: 'HYBRID_RRF_V1', topK: 5 }), /unavailable/)
    assert.equal(calls, 1)
  } finally { mock.restoreAll(); delete globalThis.localStorage }
})

import { beforeEach, test, mock } from 'node:test'
import assert from 'node:assert/strict'
import { build } from 'esbuild'
import { fileURLToPath } from 'node:url'
const built = await build({ entryPoints: [fileURLToPath(new URL('../src/services/onCallCoverage.ts', import.meta.url))], bundle: true, write: false, platform: 'node', format: 'esm' })
const coverage = await import(`data:text/javascript;base64,${Buffer.from(built.outputFiles[0].text).toString('base64')}`)
beforeEach(() => { mock.restoreAll(); globalThis.localStorage = { getItem: () => 'test-token' }; globalThis.window = new EventTarget() })
const segment = () => ({ shiftId: null, gapReason: 'NO_SHIFT', override: false, sameLayerOverlap: false, unavailableShiftIds: [], shadowedShiftIds: [] })
test('clock retains database local seconds without browser timezone conversion', () => assert.equal(coverage.coverageClock('2026-09-27T08:30:12.123'), '2026-09-27 08:30:12'))
test('state distinguishes gaps, inactive schedules, unavailable members and winning layers', () => {
  assert.equal(coverage.coverageState(segment()), '缺班 · 未排班')
  assert.equal(coverage.coverageState({ ...segment(), gapReason: 'MEMBER_UNAVAILABLE' }), '缺班 · 成员不可用')
  assert.equal(coverage.coverageState({ ...segment(), gapReason: 'SCHEDULE_INACTIVE' }), '缺班 · 计划停用')
  assert.equal(coverage.coverageState({ ...segment(), shiftId: 1 }), '普通班次生效')
  assert.equal(coverage.coverageState({ ...segment(), shiftId: 1, override: true }), '临时覆盖生效')
})
test('warnings do not count shadowed or ineligible shifts as additional coverage', () => {
  assert.deepEqual(coverage.coverageWarnings(segment()), [])
  const warnings = coverage.coverageWarnings({ ...segment(), sameLayerOverlap: true, unavailableShiftIds: [4, 5], shadowedShiftIds: [2] })
  assert.equal(warnings.length, 3)
  assert.match(warnings[0], /不重复计覆盖/)
  assert.match(warnings[1], /#4、#5/)
  assert.match(warnings[2], /#2/)
})
test('hours preserve fractional windows and zero gaps', () => { assert.equal(coverage.coverageHours(5400), '1.5'); assert.equal(coverage.coverageHours(0), '0') })
test('calendar splits midnight without double counting, retains clipped segments and leap days', () => {
  const base = { ...segment(), shiftId: 3, startsAt: '2028-02-28T23:00:00', endsAt: '2028-03-01T01:00:00' }
  const days = coverage.coverageDays({ from: base.startsAt, to: base.endsAt, segments: [base] })
  assert.deepEqual(days.map(d => d.date), ['2028-02-28','2028-02-29','2028-03-01'])
  assert.deepEqual(days.map(d => d.coveredSeconds), [3600,86400,3600])
  assert.equal(days[1].segments[0].startsAt, '2028-02-29T00:00:00')
  assert.equal(days[1].segments[0].endsAt, '2028-03-01T00:00:00')
  assert.equal(base.startsAt, '2028-02-28T23:00:00')
})
test('calendar uses zone-less arithmetic, end-midnight is excluded and gap totals remain explicit', () => {
  const gap = { ...segment(), startsAt: '2026-03-08T00:00:00', endsAt: '2026-03-09T00:00:00' }
  const days = coverage.coverageDays({ from: gap.startsAt, to: gap.endsAt, segments: [gap] })
  assert.equal(days.length, 1)
  assert.equal(days[0].gapSeconds, 86400) // Not an America/New_York DST instant calculation.
})
test('read-only query sends exact local timestamps and no mutation', async () => {
  mock.method(globalThis, 'fetch', async (url, init) => {
    const parsed = new URL(url, 'http://localhost')
    assert.equal(parsed.pathname, '/api/v1/on-call/coverage')
    assert.equal(parsed.searchParams.get('scheduleId'), '3')
    assert.equal(parsed.searchParams.get('from'), '2026-09-27T08:00:01')
    assert.equal(parsed.searchParams.get('to'), '2026-09-28T08:00:01')
    assert.equal(init.method, undefined)
    return Response.json({ success: true, data: { gapSeconds: 3600 } })
  })
  assert.equal((await coverage.readCoverage(3, '2026-09-27T08:00:01', '2026-09-28T08:00:01')).gapSeconds, 3600)
})
test('dense coverage error propagates without retry or a fabricated result', async () => {
  const fetcher = mock.method(globalThis, 'fetch', async () => Response.json({ success: false, error: { code: 'ONCALL_COVERAGE_TOO_DENSE', message: '请缩小范围' } }, { status: 409 }))
  await assert.rejects(coverage.readCoverage(3, '', ''), /缩小范围/)
  assert.equal(fetcher.mock.callCount(), 1)
})

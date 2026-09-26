import { beforeEach, test, mock } from 'node:test'
import assert from 'node:assert/strict'
import { build } from 'esbuild'
import { fileURLToPath } from 'node:url'

const built = await build({ entryPoints: [fileURLToPath(new URL('../src/services/onCallRotations.ts', import.meta.url))],
  bundle: true, write: false, platform: 'node', format: 'esm' })
const rotations = await import(`data:text/javascript;base64,${Buffer.from(built.outputFiles[0].text).toString('base64')}`)
const draft = () => ({ scheduleId: 1, name: '有序轮转', anchorAt: '2026-09-27T08:00', shiftMinutes: 480, members: [3, 2] })
const slot = () => ({ status: 'GENERATED', cancelledAt: null, memberAvailable: true })
beforeEach(() => {
  mock.restoreAll()
  globalThis.localStorage = { getItem: () => 'test-token' }
  globalThis.window = new EventTarget()
})
test('database local time is not converted across browser timezones', () => {
  assert.equal(rotations.rotationClock('2026-09-27T08:00:12.123'), '2026-09-27 08:00')
  assert.equal(rotations.rotationClock(null), '尚未扫描')
})
test('cancelled generated slot and current ineligible member are not mislabeled as coverage', () => {
  assert.equal(rotations.slotState(slot()), '已生成')
  assert.equal(rotations.slotState({ ...slot(), cancelledAt: '2026-09-27T08:01' }), '已取消 · 不再生成')
  assert.equal(rotations.slotState({ ...slot(), memberAvailable: false }), '已生成 · 成员不可用')
  assert.equal(rotations.slotState({ ...slot(), status: 'BLOCKED' }), '普通班次冲突')
  assert.equal(rotations.slotState({ ...slot(), status: 'MEMBER_UNAVAILABLE', memberAvailable: false }), '成员不可用 · 未生成')
})
test('warning labels preserve failure/inactive/blocked distinctions and never assert full coverage', () => {
  assert.match(rotations.rotationWarning('BLOCKED_SLOTS:12'), /受阻 12 个时段/)
  assert.match(rotations.rotationWarning('SCHEDULE_INACTIVE'), /计划已停用/)
  assert.match(rotations.rotationWarning('GENERATION_FAILED'), /失败并回滚/)
  assert.match(rotations.rotationWarning(null), /不代表整段覆盖/)
  assert.equal(rotations.rotationWarning('future-warning'), 'future-warning')
})
test('manager role gate excludes ON_CALL and AUDITOR', () => {
  for (const role of ['ADMIN', 'OPS_MANAGER']) assert.equal(rotations.canManageRotations(role), true)
  for (const role of ['ON_CALL', 'AUDITOR', undefined]) assert.equal(rotations.canManageRotations(role), false)
})
test('draft boundary checks keep ordered membership and whole-minute anchor', () => {
  assert.equal(rotations.draftError(draft()), '')
  for (const change of [{ name: ' ' }, { scheduleId: 0 }, { name: '长'.repeat(129) },
    { anchorAt: '2026-09-27T08:00:01' }, { shiftMinutes: 59 }, { shiftMinutes: 10081 },
    { shiftMinutes: 60.5 }, { members: [] }, { members: [2, 2] }, { members: [0] },
    { members: Array.from({ length: 21 }, (_, i) => i + 1) }]) assert.ok(rotations.draftError({ ...draft(), ...change }))
  assert.equal(rotations.draftError({ ...draft(), shiftMinutes: 60 }), '')
  assert.equal(rotations.draftError({ ...draft(), shiftMinutes: 10080, members: [3] }), '')
})
test('create request preserves member order and database local anchor, without mutating draft', async () => {
  const data = draft()
  data.name = '  有序轮转  '
  mock.method(globalThis, 'fetch', async (url, init) => {
    assert.equal(url, '/api/v1/on-call/rotations')
    assert.equal(init.method, 'POST')
    assert.equal(init.headers.get('Authorization'), 'Bearer test-token')
    assert.deepEqual(JSON.parse(init.body), { ...data, name: '有序轮转', members: [3, 2] })
    return Response.json({ success: true, data: { id: 7 } })
  })
  assert.deepEqual(await rotations.createRotation(data), { id: 7 })
  assert.equal(data.name, '  有序轮转  ')
  assert.deepEqual(data.members, [3, 2])
})
test('state command uses captured version and reason; conflicts propagate without retry', async () => {
  const fetcher = mock.method(globalThis, 'fetch', async (url, init) => {
    assert.equal(url, '/api/v1/on-call/rotations/7/state')
    assert.deepEqual(JSON.parse(init.body), { version: 4, active: false, reason: '协调换班' })
    return Response.json({ success: false, error: { code: 'ONCALL_ROTATION_VERSION_CONFLICT', message: '请刷新核对' } }, { status: 409 })
  })
  await assert.rejects(rotations.setRotationState({ id: 7, version: 4, active: true }, '  协调换班  '), { code: 'ONCALL_ROTATION_VERSION_CONFLICT', status: 409 })
  assert.equal(fetcher.mock.callCount(), 1)
})
test('list/slot reads preserve truncation and scan partial failures remain explicit', async () => {
  const calls = []
  mock.method(globalThis, 'fetch', async (url, init) => {
    calls.push({ url, method: init.method ?? 'GET' })
    return Response.json({ success: true, data: calls.length === 1 ? { rotations: [], truncated: true }
      : calls.length === 2 ? { slots: [], truncated: true }
        : { rotations: 3, createdShifts: 1, blockedSlots: 2, failedRotations: [9] } })
  })
  assert.equal((await rotations.listRotations('1')).truncated, true)
  assert.equal((await rotations.readSlots(7, '2026-09-27T08:00', '2026-09-28T08:00')).truncated, true)
  assert.deepEqual((await rotations.scanRotations()).failedRotations, [9])
  assert.deepEqual(calls, [
    { url: '/api/v1/on-call/rotations?scheduleId=1', method: 'GET' },
    { url: '/api/v1/on-call/rotations/7/slots?from=2026-09-27T08%3A00&to=2026-09-28T08%3A00', method: 'GET' },
    { url: '/api/v1/on-call/rotations/scan', method: 'POST' },
  ])
})

import { api } from './api'

export type HandoffStatus = 'PENDING' | 'ACCEPTED' | 'REJECTED' | 'WITHDRAWN'
export type HandoffDecision = Exclude<HandoffStatus, 'PENDING'>
export interface Handoff {
  id: number; scheduleId: number; sourceShiftId: number; sourceVersion: number
  requesterId: number; targetUserId: number; requestKey: string; startsAt: string; endsAt: string
  reason: string; status: HandoffStatus; version: number; createdAt: string
  decidedAt: string | null; decidedBy: number | null; decisionReason: string | null; replacementShiftId: number | null
}
export interface HandoffSource {
  id: number; scheduleId: number; scheduleName: string; userId: number; userName: string
  startsAt: string; endsAt: string; override: boolean; version: number; cancelledAt: string | null
}
export interface HandoffCommand {
  sourceShiftId: number; sourceVersion: number; targetUserId: number; requestKey: string
  startsAt: string; endsAt: string; reason: string
}
export interface HandoffList { databaseNow: string; requests: Handoff[]; truncated: boolean }
export interface SavedHandoffDraft { schema: 1; actorId: number; source: HandoffSource; command: HandoffCommand }
const roles = ['ADMIN', 'OPS_MANAGER', 'ON_CALL']
const storageKey = (actorId: number) => `opspilot_handoff_draft:${actorId}`
const wholeSecond = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}$/
const canonicalUuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
export const handoffClock = (value: string) => value.replace('T', ' ').slice(0, 19)
export const handoffTime = (value: string) => value.length === 16 ? `${value}:00` : value
export const canRequestHandoff = (source: HandoffSource, actorId: number | undefined, role: string | undefined, now: string) =>
  roles.includes(role ?? '') && source.userId === actorId && !source.override && !source.cancelledAt && Boolean(now) && source.endsAt > now
export function handoffActions(row: Handoff, actorId: number | undefined, role: string | undefined, now: string): HandoffDecision[] {
  if (!roles.includes(role ?? '') || row.status !== 'PENDING') return []
  if (row.requesterId === actorId) return ['WITHDRAWN']
  if (row.targetUserId !== actorId) return []
  return now && row.endsAt > now ? ['ACCEPTED', 'REJECTED'] : ['REJECTED']
}
export function handoffState(row: Handoff, now: string) {
  if (row.status === 'PENDING') return now && row.endsAt <= now ? '待处理 · 时段已结束' : '待处理'
  return { ACCEPTED: '已接受', REJECTED: '已拒绝', WITHDRAWN: '已撤回' }[row.status]
}
export function handoffDraftError(command: HandoffCommand, source: HandoffSource, actorId: number) {
  if (source.userId !== actorId || command.sourceShiftId !== source.id || command.sourceVersion !== source.version
    || source.override || source.cancelledAt) return '请重新核对本人普通班次及捕获的版本'
  if (!Number.isSafeInteger(command.targetUserId) || command.targetUserId < 1 || command.targetUserId === actorId) return '请选择其他接班人'
  if (!canonicalUuid.test(command.requestKey)) return '请求键须为规范 UUID'
  if (!wholeSecond.test(command.startsAt) || !wholeSecond.test(command.endsAt) || command.startsAt >= command.endsAt
    || command.startsAt < handoffTime(source.startsAt) || command.endsAt > handoffTime(source.endsAt)) return '接班须为原班次内的有效整秒时段'
  if (!command.reason.trim() || command.reason.length > 500) return '申请说明须为 1–500 字'
  return ''
}
export function saveHandoffDraft(storage: Pick<Storage, 'setItem'>, draft: SavedHandoffDraft) {
  storage.setItem(storageKey(draft.actorId), JSON.stringify(draft))
}
export function readHandoffDraft(storage: Pick<Storage, 'getItem'>, actorId: number): SavedHandoffDraft | null {
  const raw = storage.getItem(storageKey(actorId))
  if (!raw) return null
  const draft = JSON.parse(raw) as SavedHandoffDraft
  if (draft?.schema !== 1 || draft.actorId !== actorId || !draft.source || !draft.command
    || typeof draft.command.reason !== 'string' || typeof draft.command.requestKey !== 'string'
    || !Number.isSafeInteger(draft.source.version) || draft.source.version < 0
    || handoffDraftError(draft.command, draft.source, actorId)) throw new Error('接班重试草稿损坏，请先核对台账再明确放弃草稿')
  return draft
}
export const clearHandoffDraft = (storage: Pick<Storage, 'removeItem'>, actorId: number) => storage.removeItem(storageKey(actorId))
export const listHandoffs = (scheduleId: string, scope: 'ALL' | 'MINE', status: HandoffStatus | '') => {
  const query = new URLSearchParams({ scope })
  if (scheduleId) query.set('scheduleId', scheduleId)
  if (status) query.set('status', status)
  return api<HandoffList>(`/on-call/handoffs?${query}`)
}
// No implicit retries or key/version rotation; a caller retries the exact frozen command.
export const requestHandoff = (command: HandoffCommand) => api<Handoff>('/on-call/handoffs', { method: 'POST', body: JSON.stringify(command) })
export const decideHandoff = (id: number, command: { version: number; status: HandoffDecision; reason: string }) =>
  api<Handoff>(`/on-call/handoffs/${id}/decisions`, { method: 'POST', body: JSON.stringify(command) })

export interface HandoffCoverage {
  databaseNow: string; request: Handoff
  replacement: { id: number; userId: number; version: number; startsAt: string; endsAt: string; cancelledAt: string | null; cancellationReason: string | null } | null
  revocation: { handoffId: number; actorId: number; operationKey: string; handoffVersion: number; replacementVersion: number; reason: string; revokedAt: string } | null
}
export interface RevocationCommand { handoffVersion: number; replacementVersion: number; operationKey: string; reason: string }
export interface SavedRevocationDraft { schema: 1; actorId: number; handoffId: number; command: RevocationCommand; blocked: boolean }
const revocationStorageKey = (actorId: number) => `opspilot_handoff_revocation:v1:${actorId}`
export function coverageState(view: HandoffCoverage) {
  const shift = view.replacement
  if (!shift) return '未生成覆盖'
  if (shift.cancelledAt) return '覆盖已取消'
  if (view.databaseNow && shift.endsAt <= view.databaseNow) return '覆盖时段已结束'
  if (view.databaseNow && shift.startsAt > view.databaseNow) return '覆盖尚未开始'
  return '覆盖时段内 · 生效须核对日历'
}
export const canRevokeCoverage = (view: HandoffCoverage, role: string | undefined) =>
  ['ADMIN','OPS_MANAGER'].includes(role ?? '') && view.request.status === 'ACCEPTED' && Boolean(view.databaseNow)
    && Boolean(view.replacement && !view.replacement.cancelledAt && view.replacement.endsAt > view.databaseNow) && !view.revocation
export function revocationCommandError(command: RevocationCommand) {
  if (!Number.isSafeInteger(command.handoffVersion) || command.handoffVersion < 0
    || !Number.isSafeInteger(command.replacementVersion) || command.replacementVersion < 0) return '撤销须保留捕获的请求和覆盖版本'
  if (typeof command.operationKey !== 'string' || !canonicalUuid.test(command.operationKey)) return '撤销键须为规范 UUID'
  if (typeof command.reason !== 'string' || !command.reason.trim() || command.reason.length > 500) return '撤销说明须为 1–500 字'
  return ''
}
export const saveRevocationDraft = (storage: Pick<Storage,'setItem'>, draft: SavedRevocationDraft) =>
  storage.setItem(revocationStorageKey(draft.actorId), JSON.stringify(draft))
export function readRevocationDraft(storage: Pick<Storage,'getItem'>, actorId: number): SavedRevocationDraft | null {
  const raw = storage.getItem(revocationStorageKey(actorId))
  if (!raw) return null
  const draft = JSON.parse(raw) as SavedRevocationDraft
  if (draft?.schema !== 1 || draft.actorId !== actorId || !Number.isSafeInteger(draft.handoffId) || draft.handoffId < 1
    || typeof draft.blocked !== 'boolean' || !draft.command || revocationCommandError(draft.command)) {
    throw new Error('撤销草稿损坏，请先核对覆盖事实再明确放弃草稿')
  }
  return draft
}
export const clearRevocationDraft = (storage: Pick<Storage,'removeItem'>, actorId: number) => storage.removeItem(revocationStorageKey(actorId))
export const getHandoffCoverage = (id: number) => api<HandoffCoverage>(`/on-call/handoffs/${id}/coverage`)
// Only explicit manual retry: the persisted intent is never rebased by a new snapshot.
export const revokeHandoffCoverage = (id: number, command: RevocationCommand) =>
  api<HandoffCoverage>(`/on-call/handoffs/${id}/coverage/revoke`, { method:'POST', body:JSON.stringify(command) })

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

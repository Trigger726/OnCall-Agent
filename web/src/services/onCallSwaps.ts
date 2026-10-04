import { api } from './api'
import type { HandoffSource } from './onCallHandoffs'

export type SwapSource = HandoffSource
export type SwapStatus = 'PENDING' | 'ACCEPTED' | 'REJECTED' | 'WITHDRAWN'
export type SwapDecision = Exclude<SwapStatus, 'PENDING'>
export interface Swap {
  id: number; requesterId: number; targetUserId: number; requestKey: string
  firstScheduleId: number; firstShiftId: number; firstVersion: number; firstStartsAt: string; firstEndsAt: string
  secondScheduleId: number; secondShiftId: number; secondVersion: number; secondStartsAt: string; secondEndsAt: string
  reason: string; status: SwapStatus; version: number; createdAt: string; decidedAt: string | null
  decidedBy: number | null; decisionReason: string | null; firstReplacementShiftId: number | null; secondReplacementShiftId: number | null
}
export interface SwapCommand { firstShiftId: number; firstVersion: number; secondShiftId: number; secondVersion: number; requestKey: string; reason: string }
export interface DecisionCommand { version: number; status: SwapDecision; reason: string }
export interface SwapList { databaseNow: string; requests: Swap[]; truncated: boolean }
export interface SwapRoster { databaseNow: string; shifts: SwapSource[]; truncated: boolean; schedules: { id: number; name: string }[]; users: { id: number; displayName: string }[] }
export type SwapIntent = { schema: 1; actorId: number; blocked: boolean } & (
  { kind: 'REQUEST'; first: SwapSource; second: SwapSource; command: SwapCommand } |
  { kind: 'DECISION'; row: Swap; command: DecisionCommand })
export interface SwapCoverage { databaseNow: string; segments: { startsAt: string; endsAt: string; userId: number | null; userName: string | null; override: boolean; gapReason: string | null }[] }
const roles = ['ADMIN', 'OPS_MANAGER', 'ON_CALL']
const validId = (value: unknown): value is number => Number.isSafeInteger(value) && Number(value) > 0
const validVersion = (value: unknown): value is number => Number.isSafeInteger(value) && Number(value) >= 0
const time = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2}(?:\.\d{1,9})?)?$/
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const key = (actor: number) => `opspilot_swap_intent:v1:${actor}`
const rowValid = (row: Swap) => Boolean(row && [row.id,row.requesterId,row.targetUserId,row.firstScheduleId,row.secondScheduleId,row.firstShiftId,row.secondShiftId].every(validId)
  && [row.version,row.firstVersion,row.secondVersion].every(validVersion) && row.requesterId !== row.targetUserId && row.firstShiftId !== row.secondShiftId
  && [row.firstStartsAt,row.firstEndsAt,row.secondStartsAt,row.secondEndsAt].every(value => typeof value === 'string' && time.test(value))
  && row.firstStartsAt < row.firstEndsAt && row.secondStartsAt < row.secondEndsAt && row.status === 'PENDING')
export const swapClock = (value: string) => value.replace('T', ' ').slice(0, 19)
const sourceValid = (source: SwapSource) => Boolean(source && validId(source.id) && validId(source.scheduleId) && validId(source.userId)
  && validVersion(source.version) && source.override === false && source.cancelledAt === null
  && time.test(source.startsAt) && time.test(source.endsAt) && source.startsAt < source.endsAt)
export const canRequestSwap = (source: SwapSource, actor: number | undefined, role: string | undefined, now: string) =>
  roles.includes(role ?? '') && sourceValid(source) && source.userId === actor && Boolean(now) && source.startsAt > now
export const canChooseSwap = (source: SwapSource, first: SwapSource, actor: number, now: string) =>
  sourceValid(source) && source.id !== first.id && source.userId !== actor && Boolean(now) && source.startsAt > now
export function swapActions(row: Swap, actor: number | undefined, role: string | undefined, now: string): SwapDecision[] {
  if (!roles.includes(role ?? '') || row.status !== 'PENDING') return []
  if (row.requesterId === actor) return ['WITHDRAWN']
  if (row.targetUserId !== actor) return []
  return now && row.firstStartsAt > now && row.secondStartsAt > now ? ['ACCEPTED', 'REJECTED'] : ['REJECTED']
}
export function swapState(row: Swap, now: string) {
  if (row.status === 'PENDING') return now && (row.firstStartsAt <= now || row.secondStartsAt <= now) ? '待处理 · 源班次已开始' : '待处理'
  return { ACCEPTED: '已接受 · 历史决定', REJECTED: '已拒绝', WITHDRAWN: '已撤回' }[row.status]
}
export function swapRequestError(command: SwapCommand, first: SwapSource, second: SwapSource, actor: number) {
  if (!sourceValid(first) || !sourceValid(second) || first.userId !== actor || second.userId === actor || first.id === second.id
    || command.firstShiftId !== first.id || command.secondShiftId !== second.id || command.firstVersion !== first.version || command.secondVersion !== second.version) return '请核对本人和对方的两个不同普通班次及捕获版本'
  if (typeof command.requestKey !== 'string' || !uuid.test(command.requestKey)) return '请求键须为规范 UUID'
  if (typeof command.reason !== 'string' || !command.reason.trim() || command.reason.length > 500) return '说明须为 1–500 字'
  return ''
}
export function swapDecisionError(command: DecisionCommand) {
  if (!validVersion(command.version) || !['ACCEPTED','REJECTED','WITHDRAWN'].includes(command.status)) return '请保留有效决定与捕获版本'
  if (typeof command.reason !== 'string' || !command.reason.trim() || command.reason.length > 500) return '说明须为 1–500 字'
  return ''
}
export function saveSwapIntent(storage: Pick<Storage, 'setItem'>, intent: SwapIntent) { storage.setItem(key(intent.actorId), JSON.stringify(intent)) }
export function readSwapIntent(storage: Pick<Storage, 'getItem'>, actor: number): SwapIntent | null {
  const raw = storage.getItem(key(actor)); if (!raw) return null
  const intent = JSON.parse(raw) as SwapIntent
  let invalid = !intent || intent.schema !== 1 || intent.actorId !== actor || !validId(actor) || typeof intent.blocked !== 'boolean' || !intent.command
  if (!invalid) {
    if (intent.kind === 'REQUEST') invalid = Boolean(swapRequestError(intent.command, intent.first, intent.second, actor))
    else if (intent.kind === 'DECISION') invalid = !rowValid(intent.row)
      || intent.command.version !== intent.row.version || Boolean(swapDecisionError(intent.command))
      || actor !== (intent.command.status === 'WITHDRAWN' ? intent.row.requesterId : intent.row.targetUserId)
    else invalid = true
  }
  if (invalid) throw new Error('换班草稿损坏，未发送操作；请核对台账后明确放弃')
  return intent
}
export const clearSwapIntent = (storage: Pick<Storage, 'removeItem'>, actor: number) => storage.removeItem(key(actor))
export const listSwaps = (schedule: string, scope: 'ALL' | 'MINE', status: SwapStatus | '') => {
  const query = new URLSearchParams({ scope }); if (schedule) query.set('scheduleId', schedule); if (status) query.set('status', status)
  return api<SwapList>(`/on-call/swaps?${query}`)
}
export const requestSwap = (command: SwapCommand) => api<Swap>('/on-call/swaps', { method: 'POST', body: JSON.stringify(command) })
export const decideSwap = (id: number, command: DecisionCommand) => api<Swap>(`/on-call/swaps/${id}/decisions`, { method: 'POST', body: JSON.stringify(command) })
export const getSwap = (id: number) => api<Swap>(`/on-call/swaps/${id}`)
export function getSwapCoverage(schedule: number, from: string, to: string) {
  return api<SwapCoverage>(`/on-call/coverage?${new URLSearchParams({ scheduleId: String(schedule), from, to })}`)
}

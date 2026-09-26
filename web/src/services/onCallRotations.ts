import { api } from './api'

export interface Rotation {
  id: number; scheduleId: number; name: string; anchorAt: string; shiftMinutes: number
  members: number[]; active: boolean; version: number; stateReason: string
  lastScanAt: string | null; lastWarning: string | null
}
export interface RotationSlot {
  slot: number; userId: number; userName: string; startsAt: string; endsAt: string
  status: string; detail: string; shiftId: number | null; cancelledAt: string | null; memberAvailable: boolean
}
export interface SlotWindow { from: string; to: string; slots: RotationSlot[]; truncated: boolean }
export interface RotationDraft { scheduleId: number; name: string; anchorAt: string; shiftMinutes: number; members: number[] }
export interface RotationOptions {
  databaseNow: string; suggestedStart: string
  schedules: { id: number; name: string; resourceName: string }[]
  users: { id: number; displayName: string; roleCode: string }[]
}

// Keep database-session local time unchanged; never pass it through Date/UTC conversion.
export const rotationClock = (value: string | null) => value ? value.replace('T', ' ').slice(0, 16) : '尚未扫描'
export const canManageRotations = (role: string | undefined) => ['ADMIN', 'OPS_MANAGER'].includes(role ?? '')
export function rotationWarning(value: string | null): string {
  if (!value) return '上次扫描未报告阻塞；不代表整段覆盖已验证'
  if (value.startsWith('BLOCKED_SLOTS:')) return `上次扫描受阻 ${value.slice(14)} 个时段（冲突或成员资格），请查看台账`
  if (value === 'SCHEDULE_INACTIVE') return '计划已停用，未继续生成'
  if (value === 'GENERATION_FAILED') return '本轮生成失败并回滚，请检查审计/服务日志后重试'
  return value
}
export function slotState(slot: RotationSlot): string {
  if (slot.cancelledAt) return '已取消 · 不再生成'
  if (slot.status === 'GENERATED') return slot.memberAvailable ? '已生成' : '已生成 · 成员不可用'
  if (slot.status === 'BLOCKED') return '普通班次冲突'
  if (slot.status === 'MEMBER_UNAVAILABLE') return '成员不可用 · 未生成'
  return slot.status
}
export function draftError(draft: RotationDraft): string {
  if (!draft.scheduleId || !draft.name.trim() || draft.name.length > 128) return '请选择计划并填写 1–128 字名称'
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(draft.anchorAt)) return '锚点须按数据库时间输入整分钟'
  if (!Number.isInteger(draft.shiftMinutes) || draft.shiftMinutes < 60 || draft.shiftMinutes > 10080) return '单班须为 60–10080 整数分钟'
  if (!draft.members.length || draft.members.length > 20 || new Set(draft.members).size !== draft.members.length
    || draft.members.some(id => !Number.isSafeInteger(id) || id < 1)) return '请选择 1–20 名不重复成员，顺序决定轮次'
  return ''
}
export const listRotations = (scheduleId: string) => api<{ rotations: Rotation[]; truncated: boolean }>(
  `/on-call/rotations${scheduleId ? `?scheduleId=${encodeURIComponent(scheduleId)}` : ''}`)
export const readSlots = (id: number, from: string, to: string) => {
  const query = new URLSearchParams()
  if (from) query.set('from', from)
  if (to) query.set('to', to)
  return api<SlotWindow>(`/on-call/rotations/${id}/slots?${query}`)
}
export const createRotation = (draft: RotationDraft) => api<Rotation>('/on-call/rotations', {
  method: 'POST', body: JSON.stringify({ ...draft, name: draft.name.trim(), members: [...draft.members] }),
})
export const setRotationState = (rotation: Rotation, reason: string) => api<Rotation>(`/on-call/rotations/${rotation.id}/state`, {
  method: 'POST', body: JSON.stringify({ version: rotation.version, active: !rotation.active, reason: reason.trim() }),
})
export const scanRotations = () => api<{ rotations: number; createdShifts: number; blockedSlots: number; failedRotations: number[] }>(
  '/on-call/rotations/scan', { method: 'POST' })

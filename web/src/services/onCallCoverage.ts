import { api } from './api'

export interface CoverageSegment {
  startsAt: string; endsAt: string; shiftId: number | null; userId: number | null; userName: string | null
  override: boolean; gapReason: string | null; shadowedShiftIds: number[]; unavailableShiftIds: number[]; sameLayerOverlap: boolean
}
export interface Coverage {
  databaseNow: string; from: string; to: string
  schedule: { id: number; name: string; resourceName: string; active: boolean }
  sourceShifts: number; totalSeconds: number; coveredSeconds: number; gapSeconds: number; segments: CoverageSegment[]
}
export const coverageClock = (value: string) => value.replace('T', ' ').slice(0, 19)
export const coverageHours = (seconds: number) => (seconds / 3600).toLocaleString('zh-CN', { maximumFractionDigits: 2 })
// UTC is only a calendar-arithmetic carrier for a zone-less database clock, NOT an instant conversion.
// This deliberately has no browser-local getters and makes no DST/timezone support claim.
export function coverageDays(view: Coverage) {
  const days: { date: string; coveredSeconds: number; gapSeconds: number; segments: CoverageSegment[] }[] = []
  let start = view.from
  while (start < view.to) {
    const date = start.slice(0, 10)
    const nextDate = new Date(Date.parse(`${date}T00:00:00Z`) + 86400000).toISOString().slice(0, 10)
    const midnight = `${nextDate}T00:00:00`
    const end = midnight < view.to ? midnight : view.to
    const segments = view.segments.filter(s => s.startsAt < end && s.endsAt > start).map(s => ({ ...s,
      startsAt: s.startsAt < start ? start : s.startsAt, endsAt: s.endsAt > end ? end : s.endsAt }))
    let coveredSeconds = 0, gapSeconds = 0
    for (const segment of segments) {
      const seconds = (Date.parse(segment.endsAt + 'Z') - Date.parse(segment.startsAt + 'Z')) / 1000
      if (segment.shiftId === null) gapSeconds += seconds
      else coveredSeconds += seconds
    }
    days.push({ date, coveredSeconds, gapSeconds, segments })
    start = end
  }
  return days
}
export function coverageState(segment: CoverageSegment): string {
  if (segment.shiftId !== null) return segment.override ? '临时覆盖生效' : '普通班次生效'
  if (segment.gapReason === 'SCHEDULE_INACTIVE') return '缺班 · 计划停用'
  if (segment.gapReason === 'MEMBER_UNAVAILABLE') return '缺班 · 成员不可用'
  return '缺班 · 未排班'
}
export function coverageWarnings(segment: CoverageSegment): string[] {
  const warnings = []
  if (segment.sameLayerOverlap) warnings.push('同层重叠，请核对历史班次；按路由优先级选出一人，不重复计覆盖')
  if (segment.unavailableShiftIds.length) warnings.push(`无资格班次未计覆盖：#${segment.unavailableShiftIds.join('、#')}`)
  if (segment.shadowedShiftIds.length) warnings.push(`未胜出班次：#${segment.shadowedShiftIds.join('、#')}`)
  return warnings
}
export function readCoverage(scheduleId: number, from: string, to: string) {
  const query = new URLSearchParams({ scheduleId: String(scheduleId) })
  if (from) query.set('from', from)
  if (to) query.set('to', to)
  return api<Coverage>(`/on-call/coverage?${query}`)
}

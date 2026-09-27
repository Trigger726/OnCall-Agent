import { api, RequestError } from './api'

export type PublicationDecision = 'APPROVE' | 'REJECT' | 'WITHDRAW'
export interface PublicationIntent {
  actorId: number; id: number; expectedVersion: number; requestKey: string
  decision: PublicationDecision; reason: string; locked: boolean
}
type IntentStorage = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>
const decisions: string[] = ['APPROVE', 'REJECT', 'WITHDRAW']
const canonicalUuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
function valid(value: unknown): value is PublicationIntent {
  if (!value || typeof value !== 'object') return false
  const v = value as PublicationIntent
  return Number.isSafeInteger(v.actorId) && v.actorId > 0 && Number.isSafeInteger(v.id) && v.id > 0
    && Number.isSafeInteger(v.expectedVersion) && v.expectedVersion >= 0
    && typeof v.requestKey === 'string' && canonicalUuid.test(v.requestKey)
    && decisions.includes(v.decision) && typeof v.reason === 'string'
    && v.reason === v.reason.trim() && v.reason.length > 0 && v.reason.length <= 500 && typeof v.locked === 'boolean'
}
export function publicationStorageKey(actorId: number): string { return `opspilot_publication_intent_${actorId}` }
export function capturePublicationIntent(actorId: number, id: number, expectedVersion: number,
    decision: PublicationDecision, reason: string, requestKey = crypto.randomUUID()): PublicationIntent {
  const value = { actorId, id, expectedVersion, requestKey, decision, reason: reason.trim(), locked: false }
  if (!valid(value)) throw new Error('冻结意图字段无效，未提交')
  return value
}
export function readPublicationIntent(storage: IntentStorage, actorId: number): PublicationIntent | null {
  const raw = storage.getItem(publicationStorageKey(actorId))
  if (raw === null) return null
  let value: unknown
  try { value = JSON.parse(raw) } catch { throw new Error('冻结意图损坏，原始记录保留；核对后才能放弃') }
  if (!valid(value) || value.actorId !== actorId) throw new Error('冻结意图无效或账号不匹配，原始记录保留')
  return value
}
export function persistPublicationIntent(storage: IntentStorage, value: PublicationIntent): void {
  if (!valid(value)) throw new Error('冻结意图无效，未保存或提交')
  storage.setItem(publicationStorageKey(value.actorId), JSON.stringify(value))
}
export function discardPublicationIntent(storage: IntentStorage, actorId: number): void {
  storage.removeItem(publicationStorageKey(actorId))
}
export async function sendPublicationDecision<T>(intent: PublicationIntent, actorId: number): Promise<T> {
  if (!valid(intent) || intent.actorId !== actorId || intent.locked) throw new Error('冻结意图已锁定或账号变化，未提交')
  return api<T>(`/runbooks/publications/${intent.id}/decisions`, { method: 'POST', body: JSON.stringify({
    expectedVersion: intent.expectedVersion, requestKey: intent.requestKey, decision: intent.decision, reason: intent.reason,
  }) })
}
export function isPublicationIntentRejected(error: unknown): boolean {
  return error instanceof RequestError && [400,403,404,409].includes(error.status)
}

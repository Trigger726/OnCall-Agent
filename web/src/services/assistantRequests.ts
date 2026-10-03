import { RequestError } from './api'
import { captureStreamSession } from './streamSession'

export interface AssistantIntent {
  schema: 1; actorId: number; username: string; sessionId: number; requestKey: string; content: string
}
export type AssistantStatus = 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'CANCELLED' | 'TIMED_OUT' | 'REVOKED' | 'SUPERSEDED' | 'FAILED'
export interface AssistantRequestView {
  id: number; status: AssistantStatus; questionMessageId: number | null; answerMessageId: number | null; deadlineEpochMs: number
}
export interface AssistantStreamEvent {
  type: 'meta' | 'delta' | 'done' | 'error' | 'cancelled'; content: string; messageId: number | null; evidenceJson: string | null
}
const positive = (value: unknown): value is number => Number.isSafeInteger(value) && Number(value) > 0
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const statuses: AssistantStatus[] = ['QUEUED', 'RUNNING', 'COMPLETED', 'CANCELLED', 'TIMED_OUT', 'REVOKED', 'SUPERSEDED', 'FAILED']
function actor() {
  let user: { id?: unknown; username?: unknown }
  try { user = JSON.parse(localStorage.getItem('opspilot_user') ?? '{}') } catch { user = {} }
  if (!positive(user?.id) || typeof user.username !== 'string' || !user.username.trim()) {
    throw new RequestError('请重新登录后核对本人请求', 'ASSISTANT_ACCOUNT_INVALID', 0)
  }
  return { id: user.id, username: user.username }
}
function storageKey(sessionId: number) {
  if (!positive(sessionId)) throw new Error('会话编号无效')
  const user = actor()
  return `opspilot_assistant_request:v1:${user.id}:${encodeURIComponent(user.username)}:${sessionId}`
}
function checkOwner(intent: AssistantIntent) {
  const user = actor()
  if (intent.actorId !== user.id || intent.username !== user.username) throw new DOMException('本人账号已切换', 'AbortError')
}
export function readAssistantIntent(sessionId: number): Readonly<AssistantIntent> | null {
  const raw = sessionStorage.getItem(storageKey(sessionId))
  if (!raw) return null
  let value: AssistantIntent
  try { value = JSON.parse(raw) } catch { throw new Error('本地请求记录损坏，请先核对服务端会话') }
  const user = actor()
  if (value?.schema !== 1 || value.actorId !== user.id || value.username !== user.username || value.sessionId !== sessionId
    || typeof value.requestKey !== 'string' || !uuid.test(value.requestKey)
    || typeof value.content !== 'string' || !value.content.trim() || value.content.length > 10000
    || value.content !== value.content.trim()) throw new Error('本地请求记录损坏，请先核对服务端会话')
  return Object.freeze(value)
}
export function freezeAssistantIntent(sessionId: number, content: string): Readonly<AssistantIntent> {
  const value = content.trim()
  if (!value || value.length > 10000) throw new Error('问题须为1–10000字')
  if (readAssistantIntent(sessionId)) throw new Error('请先核对尚未确认的原请求，不会覆盖原键或问题')
  const user = actor()
  const intent: AssistantIntent = { schema: 1, actorId: user.id, username: user.username,
    sessionId, requestKey: crypto.randomUUID(), content: value }
  // Persist before the first POST. A storage failure must not send an untrackable command.
  sessionStorage.setItem(storageKey(sessionId), JSON.stringify(intent))
  return Object.freeze(intent)
}
export function clearAssistantIntent(intent: AssistantIntent) {
  checkOwner(intent)
  const saved = readAssistantIntent(intent.sessionId)
  if (saved && saved.requestKey !== intent.requestKey) throw new Error('本地原请求已变化，不能清除另一请求')
  sessionStorage.removeItem(storageKey(intent.sessionId))
}
export function removeUnreadableAssistantIntent(sessionId: number) { sessionStorage.removeItem(storageKey(sessionId)) }
export const assistantTerminal = (status: AssistantStatus) => status !== 'QUEUED' && status !== 'RUNNING'
export const assistantStatusLabel = (status: AssistantStatus) => ({ QUEUED: '正在排队', RUNNING: '正在回答', COMPLETED: '回答已完成',
  CANCELLED: '回答已取消', TIMED_OUT: '原请求已超时', REVOKED: '原登录会话已撤销', SUPERSEDED: '原问题已清空', FAILED: '原请求已停止' }[status])

function validateState(value: AssistantRequestView): AssistantRequestView {
  if (!value || !positive(value.id) || !statuses.includes(value.status) || !positive(value.deadlineEpochMs)
    || !(value.questionMessageId === null || positive(value.questionMessageId))
    || !(value.answerMessageId === null || positive(value.answerMessageId))
    || (value.status === 'COMPLETED' && (!positive(value.questionMessageId) || !positive(value.answerMessageId)))) {
    throw new RequestError('原请求状态无法确认，请再次查询', 'ASSISTANT_INVALID_STATE', 0)
  }
  return value
}
async function withSession<T>(intent: AssistantIntent, signal: AbortSignal | undefined,
  operation: (lease: ReturnType<typeof captureStreamSession>, check: () => void) => Promise<T>): Promise<T> {
  checkOwner(intent)
  const lease = captureStreamSession(signal)
  const check = () => { lease.check(); checkOwner(intent) }
  try { check(); return await operation(lease, check) } finally { lease.dispose() }
}
async function checkResponse(response: Response, lease: ReturnType<typeof captureStreamSession>, check: () => void) {
  try { check() } catch (caught) { await response.body?.cancel().catch(() => {}); throw caught }
  if (response.status === 401) { lease.expire(); throw new RequestError('登录状态已失效', 'AUTHENTICATION_REQUIRED', 401) }
  if (!response.ok) {
    const error = await response.json().catch(() => null); check()
    // The SSE endpoint may have no JSON body on an early capacity rejection. Keep HTTP truth, not a fabricated terminal.
    const fallback = response.status === 503 ? '服务暂时不可用或队列已满，请先查询原请求；不会自动重发'
      : response.status === 504 ? '服务端等待超时，请查询原请求状态' : '原请求连接失败，请先查询状态'
    throw new RequestError(error?.error?.message ?? fallback, error?.error?.code ?? 'ASSISTANT_REQUEST_FAILED', response.status)
  }
}
async function requestJson<T>(intent: AssistantIntent, suffix: string, method: 'GET' | 'POST', signal?: AbortSignal): Promise<T> {
  return withSession(intent, AbortSignal.any([AbortSignal.timeout(10000), ...(signal ? [signal] : [])]), async (lease, check) => {
    const response = await fetch(`/api/v1/assistant/sessions/${intent.sessionId}${suffix}`, { method, signal: lease.signal,
      headers: { 'Idempotency-Key': intent.requestKey, ...(lease.token ? { Authorization: `Bearer ${lease.token}` } : {}) } })
    await checkResponse(response, lease, check)
    const envelope = await response.json(); check()
    if (envelope?.success !== true) throw new RequestError('原请求结果无法确认，请再次查询', 'ASSISTANT_INVALID_RESPONSE', response.status)
    return envelope.data as T
  })
}
export const readAssistantRequest = async (intent: AssistantIntent, signal?: AbortSignal) =>
  validateState(await requestJson<AssistantRequestView>(intent, '/request', 'GET', signal))
export const cancelAssistantRequest = async (intent: AssistantIntent, signal?: AbortSignal) =>
  validateState(await requestJson<AssistantRequestView>(intent, '/request/cancel', 'POST', signal))
export const readAssistantSession = <T>(intent: AssistantIntent, signal?: AbortSignal) => requestJson<T>(intent, '', 'GET', signal)

export async function streamAssistantRequest(intent: AssistantIntent, onEvent: (event: AssistantStreamEvent) => void, signal?: AbortSignal) {
  return withSession(intent, AbortSignal.any([AbortSignal.timeout(75000), ...(signal ? [signal] : [])]), async (lease, check) => {
    const response = await fetch(`/api/v1/assistant/sessions/${intent.sessionId}/stream`, { method: 'POST', signal: lease.signal,
      headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream, application/json', 'Idempotency-Key': intent.requestKey,
        ...(lease.token ? { Authorization: `Bearer ${lease.token}` } : {}) }, body: JSON.stringify({ content: intent.content }) })
    await checkResponse(response, lease, check)
    if (!response.body || !response.headers.get('content-type')?.includes('text/event-stream')) {
      throw new RequestError('回答流协议无法确认，请查询原请求', 'ASSISTANT_INVALID_STREAM', response.status)
    }
    const reader = response.body.getReader(), decoder = new TextDecoder()
    let buffer = '', messageId: number | null = null
    try {
      while (true) {
        const { value, done } = await lease.read(reader); check()
        buffer += decoder.decode(value, { stream: !done })
        if (buffer.length > 1048576) throw new Error('回答流数据超出安全范围，请查询原请求')
        const frames = buffer.split(/\r?\n\r?\n/); buffer = frames.pop() ?? ''
        for (const frame of frames) {
          const data = frame.split(/\r?\n/).filter(line => line.startsWith('data:')).map(line => line.slice(5).trimStart()).join('\n')
          if (!data) continue
          const event = JSON.parse(data) as AssistantStreamEvent
          if (!['meta', 'delta', 'done', 'error', 'cancelled'].includes(event?.type) || typeof event.content !== 'string'
            || !(event.evidenceJson === null || typeof event.evidenceJson === 'string')
            || (['meta', 'delta', 'done'].includes(event.type) ? !positive(event.messageId) : event.messageId !== null)
            || (messageId !== null && event.messageId !== null && messageId !== event.messageId)) {
            throw new RequestError('回答流状态不一致，请查询原请求', 'ASSISTANT_INVALID_STREAM', 0)
          }
          messageId ??= event.messageId; check()
          if (event.type === 'error') throw new RequestError(event.content || '回答停止，请查询原请求', 'ASSISTANT_STREAM_TERMINAL_ERROR', 200)
          onEvent(event); check()
          if (event.type === 'done' || event.type === 'cancelled') return event.type
        }
        if (done) throw new RequestError('连接已结束但结果未确认，请查询原请求', 'ASSISTANT_STREAM_INCOMPLETE', 0)
      }
    } finally { await reader.cancel().catch(() => {}); reader.releaseLock() }
  })
}

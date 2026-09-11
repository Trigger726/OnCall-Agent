import { RequestError } from '@/services/api'
import type { AgentRunEvent } from '@/types/investigation'

interface StreamError {
  type: 'RUN_FAILED'
  message: string
}

const terminalEvents = new Set([
  'RUN_COMPLETED', 'RUN_FAILED', 'RUN_CANCELLED', 'RUN_TIMED_OUT', 'RUN_REJECTED',
])

function agentIdempotencyStorageKey(incidentId: number): string {
  return `opspilot_agent_idempotency_${incidentId}`
}

export function clearAgentInvestigationIdempotency(incidentId: number): void {
  sessionStorage.removeItem(agentIdempotencyStorageKey(incidentId))
}

function idempotencyKey(incidentId: number): { storageKey: string; value: string } {
  const storageKey = agentIdempotencyStorageKey(incidentId)
  const stored = sessionStorage.getItem(storageKey)
  if (stored) return { storageKey, value: stored }
  const value = crypto.randomUUID()
  sessionStorage.setItem(storageKey, value)
  return { storageKey, value }
}

function waitForRetry(delay: number, signal?: AbortSignal): Promise<void> {
  signal?.throwIfAborted()
  return new Promise((resolve, reject) => {
    const abort = () => {
      clearTimeout(timer)
      signal?.removeEventListener('abort', abort)
      reject(signal?.reason ?? new DOMException('Aborted', 'AbortError'))
    }
    const timer = setTimeout(() => {
      signal?.removeEventListener('abort', abort)
      resolve()
    }, delay)
    signal?.addEventListener('abort', abort, { once: true })
  })
}

export async function streamAgentInvestigation(
  incidentId: number,
  source: string,
  onEvent: (event: AgentRunEvent) => void,
  signal?: AbortSignal,
): Promise<void> {
  const requestKey = idempotencyKey(incidentId)
  let runId: number | null = null
  let cursor = 0
  let terminal = false
  let failures = 0

  while (!terminal) {
    signal?.throwIfAborted()
    const previousCursor = cursor
    try {
      const token = localStorage.getItem('opspilot_token')
      const response = await fetch(runId === null
        ? `/api/v1/incidents/${incidentId}/investigations/stream?${new URLSearchParams({ source })}`
        : `/api/v1/agent-runs/${runId}/events/stream?after=${cursor}`, {
        method: runId === null ? 'POST' : 'GET', signal,
        headers: {
          ...(runId === null ? { 'Idempotency-Key': requestKey.value } : {}),
          ...(token ? { Authorization: `Bearer ${token}` } : {}),
        },
      })
      if (!response.ok || !response.body) {
        if (response.status === 401) window.dispatchEvent(new Event('opspilot-auth-expired'))
        let message = 'Agent 事件流连接失败'
        let code = 'AGENT_STREAM_FAILED'
        try {
          const envelope = await response.json() as { error?: { code?: string; message?: string } }
          message = envelope.error?.message ?? message
          code = envelope.error?.code ?? code
        } catch { /* keep transport-level error */ }
        throw new RequestError(message, code, response.status)
      }

      const headerRunId = Number(response.headers.get('X-OpsPilot-Run-Id'))
      if (runId === null && Number.isSafeInteger(headerRunId) && headerRunId > 0) runId = headerRunId
      const reader = response.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''
      const consume = (block: string) => {
        const data = block.split(/\r?\n/).filter(line => line.startsWith('data:'))
          .map(line => line.slice(5).replace(/^ /, '')).join('\n')
        if (!data) return
        let payload: AgentRunEvent | StreamError
        try { payload = JSON.parse(data) as AgentRunEvent | StreamError } catch {
          throw new RequestError('Agent 事件格式异常', 'AGENT_EVENT_INVALID', 400)
        }
        if (!payload || typeof payload !== 'object') {
          throw new RequestError('Agent 事件格式异常', 'AGENT_EVENT_INVALID', 400)
        }
        if (!('eventType' in payload)) {
          // Legacy POST error frames may arrive after the durable RUN_FAILED event.
          throw new RequestError(payload.message || 'Agent 调查失败', 'AGENT_STREAM_INTERRUPTED', 502)
        }
        if (!Number.isSafeInteger(payload.id) || payload.id <= 0
          || !Number.isSafeInteger(payload.runId) || payload.runId <= 0
          || (runId !== null && payload.runId !== runId)) {
          throw new RequestError('Agent 事件归属或游标异常', 'AGENT_EVENT_INVALID', 400)
        }
        runId ??= payload.runId
        if (payload.id <= cursor) return
        try { onEvent(payload) } catch {
          throw new RequestError('Agent 事件处理失败', 'AGENT_EVENT_HANDLER_FAILED', 400)
        }
        cursor = payload.id // Advance only after the UI has accepted this event.
        if (terminalEvents.has(payload.eventType)) {
          terminal = true
          sessionStorage.removeItem(requestKey.storageKey)
        }
        if (payload.eventType === 'RUN_REJECTED') {
          throw new RequestError('Agent 执行队列已饱和，请稍后重试', 'AGENT_QUEUE_SATURATED', 503)
        }
      }

      try {
        while (!terminal) {
          signal?.throwIfAborted()
          const { value, done } = await reader.read()
          buffer += decoder.decode(value, { stream: !done })
          const blocks = buffer.split(/\r?\n\r?\n/)
          buffer = blocks.pop() ?? ''
          for (const block of blocks) {
            consume(block)
            if (terminal) break
          }
          if (done) break
        }
        // An incomplete final frame is not committed: replay it from the last accepted cursor.
      } finally {
        await reader.cancel().catch(() => {})
        reader.releaseLock()
      }
      if (terminal) return
    } catch (error) {
      signal?.throwIfAborted()
      if (terminal || (error instanceof RequestError && error.status < 500 && error.status !== 429)) throw error
    }
    failures = cursor > previousCursor ? 0 : failures + 1
    if (failures > 5) {
      throw new RequestError('Agent 订阅恢复失败，请稍后重试；后台调查未被取消',
        'AGENT_RECONNECT_EXHAUSTED', 503)
    }
    await waitForRetry(Math.min(500 * 2 ** failures, 8000), signal)
  }
}

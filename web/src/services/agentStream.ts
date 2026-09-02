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

export async function streamAgentInvestigation(
  incidentId: number,
  source: string,
  onEvent: (event: AgentRunEvent) => void,
  signal?: AbortSignal,
): Promise<void> {
  const token = localStorage.getItem('opspilot_token')
  const query = new URLSearchParams({ source })
  const requestKey = idempotencyKey(incidentId)
  const response = await fetch(`/api/v1/incidents/${incidentId}/investigations/stream?${query}`, {
    method: 'POST', signal,
    headers: {
      'Idempotency-Key': requestKey.value,
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

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  const consume = (block: string) => {
    const data = block.split(/\r?\n/).filter(line => line.startsWith('data:'))
      .map(line => line.slice(5).trim()).join('')
    if (!data) return
    const payload = JSON.parse(data) as AgentRunEvent | StreamError
    if ('eventType' in payload) {
      onEvent(payload)
      if (terminalEvents.has(payload.eventType)) sessionStorage.removeItem(requestKey.storageKey)
      if (payload.eventType === 'RUN_REJECTED') {
        throw new RequestError('Agent 执行队列已饱和，请稍后重试', 'AGENT_QUEUE_SATURATED', 503)
      }
    } else {
      throw new Error(payload.message || 'Agent 调查失败')
    }
  }

  while (true) {
    const { value, done } = await reader.read()
    buffer += decoder.decode(value, { stream: !done })
    const blocks = buffer.split(/\r?\n\r?\n/)
    buffer = blocks.pop() ?? ''
    blocks.forEach(consume)
    if (done) break
  }
  if (buffer.trim()) consume(buffer)
}

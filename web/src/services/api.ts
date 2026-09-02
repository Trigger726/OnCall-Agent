export interface ApiError {
  code: string
  message: string
}

interface ApiEnvelope<T> {
  success: boolean
  data: T
  error: ApiError | null
  timestamp: string
}

export interface PageResponse<T> {
  items: T[]
  total: number
  page: number
  size: number
}

export class RequestError extends Error {
  constructor(
    message: string,
    public readonly code: string,
    public readonly status: number,
  ) {
    super(message)
  }
}

export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const token = localStorage.getItem('opspilot_token')
  const headers = new Headers(init.headers)
  if (init.body && !(init.body instanceof FormData) && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }
  if (token) headers.set('Authorization', `Bearer ${token}`)

  const response = await fetch(`/api/v1${path}`, { ...init, headers })
  if (response.status === 401 && token) {
    window.dispatchEvent(new Event('opspilot-auth-expired'))
    throw new RequestError('登录已过期，请重新登录', 'AUTHENTICATION_REQUIRED', response.status)
  }
  let envelope: ApiEnvelope<T> | null = null
  try {
    envelope = (await response.json()) as ApiEnvelope<T>
  } catch {
    throw new RequestError('服务响应格式异常', 'INVALID_RESPONSE', response.status)
  }
  if (!response.ok || !envelope.success) {
    throw new RequestError(
      envelope.error?.message ?? '请求失败',
      envelope.error?.code ?? 'REQUEST_FAILED',
      response.status,
    )
  }
  return envelope.data
}

export function formatTime(value?: string | null, withDate = false): string {
  if (!value) return '-'
  const date = new Date(value)
  return new Intl.DateTimeFormat('zh-CN', {
    month: withDate ? '2-digit' : undefined,
    day: withDate ? '2-digit' : undefined,
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(date)
}

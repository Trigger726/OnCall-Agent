import { RequestError } from './api'
import { captureStreamSession } from './streamSession'

export interface RunbookCitation {
  citation: string
  stableKey: string
  versionNo: number
  status: 'PUBLISHED' | 'SUPERSEDED'
  title: string
  sourceType: string
  sourceName: string
  publishedAt: string | null
  chunkIndex: number
  heading: string
  content: string
}

export function parseRunbookCitation(citation: string) {
  const match = /^runbook:([a-z0-9][a-z0-9-]{2,79}):v([1-9]\d*)#chunk-(0|[1-9]\d*)$/.exec(citation)
  if (!match) return null
  const versionNo = Number(match[2]), chunkIndex = Number(match[3])
  if (!Number.isSafeInteger(versionNo) || versionNo > 2147483647 || !Number.isSafeInteger(chunkIndex) || chunkIndex > 2147483647) return null
  return { stableKey: match[1], versionNo, chunkIndex }
}

export async function readRunbookCitation(citation: string, signal?: AbortSignal): Promise<RunbookCitation> {
  const target = parseRunbookCitation(citation)
  if (!target) throw new RequestError('引用编号无法识别', 'RUNBOOK_CITATION_INVALID', 0)
  const lease = captureStreamSession(signal)
  try {
    lease.check()
    if (!lease.token) throw new RequestError('请先登录后查看原文', 'AUTHENTICATION_REQUIRED', 401)
    const response = await fetch(`/api/v1/runbooks/${target.stableKey}/versions/${target.versionNo}/chunks/${target.chunkIndex}`, {
      headers: { Authorization: `Bearer ${lease.token}` }, signal: lease.signal,
    })
    lease.check()
    if (response.status === 401) { lease.expire(); throw new RequestError('登录已过期，请重新登录', 'AUTHENTICATION_REQUIRED', 401) }
    const envelope = await response.json().catch(() => null)
    lease.check()
    if (!response.ok) throw new RequestError(response.status === 404 ? '引用原文不存在或当前无权查看'
      : '暂时无法读取原文，请重试', envelope?.error?.code ?? 'RUNBOOK_CITATION_FAILED', response.status)
    const data = envelope?.data as RunbookCitation | undefined
    if (envelope?.success !== true || !data || data.citation !== citation || data.stableKey !== target.stableKey
      || data.versionNo !== target.versionNo || data.chunkIndex !== target.chunkIndex
      || !['PUBLISHED', 'SUPERSEDED'].includes(data.status) || typeof data.content !== 'string'
      || typeof data.title !== 'string' || typeof data.heading !== 'string' || typeof data.sourceName !== 'string') {
      throw new RequestError('返回原文与引用不一致，请重新查看', 'RUNBOOK_CITATION_MISMATCH', response.status)
    }
    return data
  } finally { lease.dispose() }
}

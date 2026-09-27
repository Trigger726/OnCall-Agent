import { api } from './api'

export interface RetrievalCounts {
  queryCount: number; returnedQueries: number; emptyQueries: number; unknownResultQueries: number
  fullyReviewedQueries: number; partialReviewedQueries: number; unreviewedQueries: number
  qualityEligibleQueries: number; relevantQueries: number; purgedQueries: number
  returnRate: number | null; reviewCoverage: number | null; reviewedHitRateAtK: number | null
}
export interface RetrievalTrend {
  from: string; to: string; measuredAt: string; dateBasis: string; source: string; engine: string
  topK: number; relevantGradeThreshold: number; totals: RetrievalCounts
  days: { date: string; counts: RetrievalCounts }[]; note: string
}
export interface TrendFilter { from: string; to: string; source: string; engine: string; topK: number }

export function trendPercent(value: number | null | undefined): string {
  return value == null || !Number.isFinite(value) ? 'N/A' : `${(value * 100).toFixed(1)}%`
}
export function loadRetrievalTrend(filter: TrendFilter): Promise<RetrievalTrend> {
  const params = new URLSearchParams({ source: filter.source, engine: filter.engine, topK: String(filter.topK) })
  if (filter.from) params.set('from', filter.from)
  if (filter.to) params.set('to', filter.to)
  return api<RetrievalTrend>(`/runbooks/searches/trend?${params}`)
}

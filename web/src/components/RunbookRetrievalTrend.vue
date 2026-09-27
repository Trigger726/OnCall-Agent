<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, reactive, ref } from 'vue'
import { loadRetrievalTrend, trendPercent, type RetrievalTrend } from '@/services/runbookTrends'

const filter = reactive({ from: '', to: '', source: 'CONSOLE', engine: 'BM25_LOCAL_V1', topK: 5 })
const view = ref<RetrievalTrend | null>(null)
const loading = ref(false)
const error = ref('')
const dailyRows = computed(() => view.value ? [...view.value.days].reverse() : [])
let alive = true
onBeforeUnmount(() => { alive = false })
onMounted(refresh)
async function refresh() {
  if (loading.value) return
  loading.value = true
  error.value = ''
  view.value = null // A failed filter request must not present the previous cohort as current.
  try {
    const response = await loadRetrievalTrend({ ...filter })
    if (alive) {
      view.value = response
      filter.from = response.from
      filter.to = response.to
    }
  } catch (caught) {
    if (alive) error.value = caught instanceof Error ? caught.message : '趋势读取失败'
  } finally { if (alive) loading.value = false }
}
</script>

<template>
  <section class="content-panel retrieval-trend" aria-label="Runbook 真实检索趋势" :aria-busy="loading">
    <header class="panel-heading"><div><h2>真实检索 · 复核质量趋势</h2><span>成功持久化的实际检索，不含离线评测；返回结果不等于相关性命中</span></div></header>
    <form class="trend-filters" @submit.prevent="refresh">
      <fieldset :disabled="loading">
        <label>起始日<input v-model="filter.from" type="date" aria-label="趋势起始日" /></label>
        <label>结束日<input v-model="filter.to" type="date" aria-label="趋势结束日" /></label>
        <label>来源<select v-model="filter.source" aria-label="趋势来源"><option value="CONSOLE">控制台</option><option value="AGENT">Agent</option><option value="ALL">全部来源</option></select></label>
        <label>实际引擎<select v-model="filter.engine" aria-label="趋势实际引擎"><option value="BM25_LOCAL_V1">BM25</option><option value="HYBRID_RRF_V1">Hybrid RRF</option></select></label>
        <label>K<select v-model.number="filter.topK" aria-label="趋势K"><option v-for="k in 10" :key="k" :value="k">{{ k }}</option></select></label>
        <button class="secondary-button" type="submit">{{ loading ? '读取中…' : '查询检索趋势' }}</button>
      </fieldset>
    </form>
    <p v-if="error" role="alert" class="inline-error">{{ error }}</p>
    <div v-if="view" class="trend-result">
      <p class="trend-context">{{ view.from }} — {{ view.to }} · {{ view.source }} · {{ view.engine }} · K={{ view.topK }}<br />数据库本地日期；复核截至 {{ view.measuredAt.replace('T', ' ').split('.')[0] }}，不是历史当日评分快照</p>
      <div class="trend-cards">
        <article><span>持久化查询</span><strong data-testid="trend-queries">{{ view.totals.queryCount }}</strong><small>结果数未知 {{ view.totals.unknownResultQueries }} · 已清理 {{ view.totals.purgedQueries }}</small></article>
        <article><span>结果返回率（非质量）</span><strong>{{ trendPercent(view.totals.returnRate) }}</strong><small>{{ view.totals.returnedQueries }} / {{ view.totals.returnedQueries + view.totals.emptyQueries }} 个结果数已知查询</small></article>
        <article><span>非空查询全量复核覆盖</span><strong>{{ trendPercent(view.totals.reviewCoverage) }}</strong><small>{{ view.totals.fullyReviewedQueries }} / {{ view.totals.returnedQueries }} · 部分复核 {{ view.totals.partialReviewedQueries }}</small></article>
        <article><span>可计分子集 Hit@{{ view.topK }}（片段）</span><strong data-testid="trend-hit-rate">{{ trendPercent(view.totals.reviewedHitRateAtK) }}</strong><small>{{ view.totals.relevantQueries }} / {{ view.totals.qualityEligibleQueries }} · 复核等级≥{{ view.relevantGradeThreshold }}</small></article>
      </div>
      <p class="trend-caveat">{{ view.note }}</p>
      <div class="trend-table-scroll" tabindex="0" aria-label="逐日检索趋势，可横向滚动">
        <table><thead><tr><th>查询日</th><th>查询</th><th>非空 / 空</th><th>结果未知</th><th>全量 / 部分 / 未复核</th><th>质量分子 / 分母</th><th>子集 Hit@{{ view.topK }}</th></tr></thead>
          <tbody><tr v-for="day in dailyRows" :key="day.date"><td>{{ day.date }}</td><td>{{ day.counts.queryCount }}</td><td>{{ day.counts.returnedQueries }} / {{ day.counts.emptyQueries }}</td><td>{{ day.counts.unknownResultQueries }}</td><td>{{ day.counts.fullyReviewedQueries }} / {{ day.counts.partialReviewedQueries }} / {{ day.counts.unreviewedQueries }}</td><td>{{ day.counts.relevantQueries }} / {{ day.counts.qualityEligibleQueries }}</td><td>{{ trendPercent(day.counts.reviewedHitRateAtK) }}</td></tr></tbody>
        </table>
      </div>
      <p v-if="!view.totals.queryCount" class="trend-context">此口径没有持久化查询；N/A 不是0%，不会用演示评分补数。</p>
    </div>
  </section>
</template>

<style scoped>
.retrieval-trend { margin-bottom: 16px; padding: 20px; }
.trend-filters fieldset { border: 0; padding: 0; margin: 12px 0; display: flex; gap: 12px; flex-wrap: wrap; align-items: end; min-width: 0; }
.trend-filters label { display: grid; gap: 5px; font-size: 12px; color: var(--text-secondary, #64748b); }
.trend-filters input, .trend-filters select { min-width: 65px; min-height: 36px; padding: 6px 8px; border: 1px solid #dbe3ed; border-radius: 6px; background: #fff; color: #1e293b; max-width: 100%; }
.trend-context, .trend-caveat { font-size: 12px; line-height: 1.7; overflow-wrap: anywhere; color: #64748b; }
.trend-caveat { padding: 10px; background: #f1f5f9; border-radius: 6px; }
.trend-cards { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; margin: 12px 0; }
.trend-cards article { border: 1px solid #e2e8f0; border-radius: 8px; padding: 12px; display: grid; gap: 8px; }
.trend-cards span, .trend-cards small { color: #64748b; font-size: 12px; line-height: 1.5; }
.trend-cards strong { color: #1e293b; font-size: 26px; }
.trend-table-scroll { max-height: 280px; overflow: auto; }
table { width: 100%; border-collapse: collapse; min-width: 720px; font-size: 12px; }
th, td { text-align: left; white-space: nowrap; padding: 9px 12px; border-bottom: 1px solid #e2e8f0; }
th { position: sticky; top: 0; background: #f8fafc; color: #64748b; }
@media (max-width: 900px) { .trend-cards { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 500px) { .retrieval-trend { padding: 14px; } .trend-filters label { flex: 1 1 125px; min-width: 0; } .trend-filters input { width: 100%; box-sizing: border-box; } }
</style>

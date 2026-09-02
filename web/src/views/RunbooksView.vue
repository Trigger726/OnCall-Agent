<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import {
  BookOpenCheck, CheckCircle2, FileText, FlaskConical, GitBranch, RefreshCw, Search, ShieldCheck,
  ThumbsDown, ThumbsUp, Upload, X, XCircle,
} from 'lucide-vue-next'
import { api, formatTime, RequestError } from '@/services/api'
import { auth } from '@/stores/auth'

interface RunbookDocument {
  id: number
  stableKey: string
  versionNo: number
  status: string
  resourceType: string
  serviceCode: string | null
  title: string
  summary: string | null
  sourceType: string
  sourceName: string
  markdown: string
  allowedRoles: string[]
  chunkCount: number
  publishedAt: string
}

interface SearchResult {
  stableKey: string
  versionNo: number
  resourceType: string
  serviceCode: string | null
  title: string
  sourceType: string
  heading: string
  excerpt: string
  score: number
  lexicalRank: number | null
  semanticRank: number | null
  semanticScore: number | null
  citation: string
}

interface SearchResponse {
  searchId: number | null
  requestedMode: string
  engine: string
  candidateChunkCount: number
  semanticStatus: string
  semanticCoverage: number
  warnings: string[]
  results: SearchResult[]
}

interface Judgment {
  id: number
  searchId: number
  query: string
  sourceType: string
  actualEngine: string
  documentStableKey: string
  relevanceGrade: number
  comment: string | null
  reviewStatus: string
  versionNo: number
  judgedByName: string
  createdAt: string
  reviewerGrade: number | null
  promotedCaseKey: string | null
}

interface PendingJudgment {
  id: number
  searchId: number
  query: string
  sourceType: string
  actualEngine: string
  documentStableKey: string
  documentTitle: string
  documentExcerpt: string
  citation: string
  versionNo: number
  createdAt: string
}

interface Agreement {
  sampleCount: number
  exactAgreementRate: number
  withinOneAgreementRate: number
  linearWeightedKappa: number | null
  note: string
}

interface EngineMetric {
  engine: string
  available: boolean
  recallAt3: number | null
  mrr: number | null
  citationHitRate: number | null
  ndcgAt3: number | null
  failures: unknown[]
  note: string | null
}

interface SemanticIndex {
  enabled: boolean
  state: string
  provider: string
  model: string
  publishedChunkCount: number
  indexedChunkCount: number
  coverage: number
  latestRunId: number | null
  latestRunStatus: string | null
  note: string
}

interface Evaluation {
  id: number
  engine: string
  baselineEngine: string
  datasetVersion: string
  caseCount: number
  judgmentCount: number
  baselineRecallAt3: number
  baselineMrr: number
  baselineNdcgAt3: number | null
  recallAt3: number
  mrr: number
  ndcgAt3: number | null
  citationHitRate: number
  failuresJson: string
  metrics: EngineMetric[]
  semanticIndex: SemanticIndex
  evaluationNote: string
  createdAt: string
}

interface ImportResult { document: RunbookDocument; reused: boolean }

const documents = ref<RunbookDocument[]>([])
const query = ref('Redis 连接池 pending 慢命令')
const searchMode = ref<'AUTO' | 'BM25' | 'HYBRID'>('AUTO')
const searchResponse = ref<SearchResponse | null>(null)
const evaluation = ref<Evaluation | null>(null)
const semanticIndex = ref<SemanticIndex | null>(null)
const pendingJudgments = ref<PendingJudgment[]>([])
const agreement = ref<Agreement | null>(null)
const judgmentsByKey = ref<Record<string, Judgment>>({})
const feedbackLoadingKey = ref('')
const loading = ref(false)
const error = ref('')
const notice = ref('')
const showImport = ref(false)
const importMode = ref<'markdown' | 'file'>('markdown')
const selectedFile = ref<File | null>(null)
const form = reactive({
  stableKey: 'service-runbook',
  resourceType: 'APPLICATION',
  serviceCode: '',
  title: '',
  summary: '',
  sourceName: 'docs/runbooks/service-runbook.md',
  markdown: '# 适用场景\n\n描述告警症状和前置条件。\n\n# 诊断步骤\n\n1. 核对核心指标。\n2. 检查故障窗口内变更。\n\n# 恢复与验证\n\n执行可回滚动作并持续观察十五分钟。',
  allowedRoles: ['ADMIN', 'OPS_MANAGER', 'ON_CALL'] as string[],
})

const canManage = computed(() => ['ADMIN', 'OPS_MANAGER'].includes(auth.state.user?.roleCode ?? ''))
const failedCases = computed(() => {
  if (!evaluation.value) return 0
  try { return (JSON.parse(evaluation.value.failuresJson) as unknown[]).length } catch { return 0 }
})
const baselineMetric = computed(() => evaluation.value?.metrics.find((item) => item.engine === 'LEGACY_CONTAINS_V1'))
const bm25Metric = computed(() => evaluation.value?.metrics.find((item) => item.engine === 'BM25_LOCAL_V1'))
const hybridMetric = computed(() => evaluation.value?.metrics.find((item) => item.engine === 'HYBRID_RRF_V1'))

onMounted(async () => {
  await loadDocuments()
  await loadSemanticIndex()
  await loadLatestEvaluation()
  if (canManage.value) await Promise.all([loadPendingJudgments(), loadAgreement()])
  await search()
})

async function loadDocuments() {
  documents.value = await api<RunbookDocument[]>('/runbooks')
}

async function loadLatestEvaluation() {
  try {
    evaluation.value = await api<Evaluation>('/runbooks/evaluations/latest')
  } catch (caught) {
    if (!(caught instanceof RequestError) || caught.status !== 404) throw caught
  }
}

async function loadSemanticIndex() {
  semanticIndex.value = await api<SemanticIndex>('/runbooks/semantic-index')
}

async function loadPendingJudgments() {
  pendingJudgments.value = await api<PendingJudgment[]>('/runbooks/judgments/pending')
}

async function loadAgreement() {
  agreement.value = await api<Agreement>('/runbooks/judgments/agreement')
}

async function search() {
  if (!query.value.trim()) {
    searchResponse.value = null
    return
  }
  loading.value = true
  error.value = ''
  try {
    judgmentsByKey.value = {}
    searchResponse.value = await api<SearchResponse>(`/runbooks/search?q=${encodeURIComponent(query.value.trim())}&topK=5&mode=${searchMode.value}`)
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : '检索失败'
  } finally {
    loading.value = false
  }
}

async function submitJudgment(result: SearchResult, relevanceGrade: number) {
  const searchId = searchResponse.value?.searchId
  if (!searchId) {
    error.value = '本次检索快照未成功保存，不能提交相关性判断'
    return
  }
  feedbackLoadingKey.value = result.stableKey
  error.value = ''
  notice.value = ''
  try {
    const judgment = await api<Judgment>(`/runbooks/searches/${searchId}/judgments`, {
      method: 'POST',
      body: JSON.stringify({ documentStableKey: result.stableKey, relevanceGrade }),
    })
    judgmentsByKey.value = { ...judgmentsByKey.value, [result.stableKey]: judgment }
    notice.value = `已记录 ${result.title} 的相关性等级 ${relevanceGrade}，等待独立复核`
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : '相关性判断提交失败'
  } finally {
    feedbackLoadingKey.value = ''
  }
}

async function reviewJudgment(judgment: PendingJudgment, decision: 'APPROVE' | 'REJECT', reviewerGrade?: number) {
  feedbackLoadingKey.value = `review-${judgment.id}`
  error.value = ''
  notice.value = ''
  try {
    const reviewed = await api<Judgment>(`/runbooks/judgments/${judgment.id}/reviews`, {
      method: 'POST',
      body: JSON.stringify({ expectedVersion: judgment.versionNo, decision, reviewerGrade }),
    })
    notice.value = reviewed.promotedCaseKey
      ? `判断 #${reviewed.id} 已通过，并进入固定评测集 ${reviewed.promotedCaseKey}`
      : `判断 #${reviewed.id} 已${decision === 'APPROVE' ? '通过' : '拒绝'}，未新增正相关评测样本`
    await Promise.all([loadPendingJudgments(), loadAgreement()])
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : '相关性判断复核失败'
    await Promise.all([loadPendingJudgments(), loadAgreement()])
  } finally {
    feedbackLoadingKey.value = ''
  }
}

function setSearchMode(mode: string) {
  searchMode.value = mode as 'AUTO' | 'BM25' | 'HYBRID'
  void search()
}

async function rebuildSemanticIndex() {
  loading.value = true
  error.value = ''
  notice.value = ''
  try {
    const result = await api<{ reused: boolean; chunkCount: number; dimensions: number; status: SemanticIndex }>('/runbooks/semantic-index/rebuild', { method: 'POST' })
    semanticIndex.value = result.status
    notice.value = result.reused
      ? `向量索引内容未变化，复用运行 #${result.status.latestRunId}`
      : `向量索引已原子替换：${result.chunkCount} 个分块 / ${result.dimensions} 维`
    await search()
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : '向量索引重建失败'
    await loadSemanticIndex()
  } finally {
    loading.value = false
  }
}

async function runEvaluation() {
  loading.value = true
  error.value = ''
  notice.value = ''
  try {
    evaluation.value = await api<Evaluation>('/runbooks/evaluations', { method: 'POST' })
    notice.value = `评测 #${evaluation.value.id} 已保存，数据集版本 ${evaluation.value.datasetVersion}`
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : '评测失败'
  } finally {
    loading.value = false
  }
}

async function submitImport() {
  loading.value = true
  error.value = ''
  notice.value = ''
  try {
    let result: ImportResult
    if (importMode.value === 'markdown') {
      result = await api<ImportResult>('/runbooks/imports/markdown', {
        method: 'POST',
        body: JSON.stringify(form),
      })
    } else {
      if (!selectedFile.value) throw new Error('请选择 Markdown 或 PDF 文件')
      const body = new FormData()
      body.append('file', selectedFile.value)
      body.append('stableKey', form.stableKey)
      body.append('resourceType', form.resourceType)
      if (form.serviceCode) body.append('serviceCode', form.serviceCode)
      body.append('title', form.title)
      if (form.summary) body.append('summary', form.summary)
      form.allowedRoles.forEach((role) => body.append('allowedRoles', role))
      result = await api<ImportResult>('/runbooks/imports/file', { method: 'POST', body })
    }
    notice.value = result.reused
      ? `${result.document.title} 内容未变化，复用 v${result.document.versionNo}`
      : `${result.document.title} v${result.document.versionNo} 已发布并生成 ${result.document.chunkCount} 个分块`
    showImport.value = false
    await loadDocuments()
    query.value = result.document.title
    await search()
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : '导入失败'
  } finally {
    loading.value = false
  }
}

function chooseFile(event: Event) {
  selectedFile.value = (event.target as HTMLInputElement).files?.[0] ?? null
  if (selectedFile.value && !form.title) form.title = selectedFile.value.name.replace(/\.(md|markdown|pdf)$/i, '')
}

function previewLines(markdown: string) {
  return markdown.split(/\n|；|;/).map((line) => line.replace(/^#{1,6}\s+/, '').trim()).filter(Boolean).slice(0, 3)
}

function metric(value: number | null | undefined) {
  if (value == null) return 'N/A'
  return `${Math.round(Number(value) * 100)}%`
}

function kappa(value: number | null | undefined) {
  return value == null ? 'N/A' : Number(value).toFixed(2)
}
</script>

<template>
  <div class="page-content runbook-page">
    <div class="page-toolbar runbook-toolbar">
      <div><strong>版本化 Runbook 知识库</strong><span>{{ documents.length }} 份文档 · ACL 过滤 · BM25 / 向量 RRF · 分级 qrels 评测</span></div>
      <div class="toolbar-group">
        <button v-if="canManage && semanticIndex?.enabled" class="secondary-button" :disabled="loading" @click="rebuildSemanticIndex"><RefreshCw :size="15" />重建向量索引</button>
        <button v-if="canManage" class="secondary-button" :disabled="loading" @click="runEvaluation"><FlaskConical :size="15" />运行评测</button>
        <button v-if="canManage" class="primary-button" @click="showImport = true"><Upload :size="15" />导入 Runbook</button>
      </div>
    </div>

    <section class="runbook-comparison content-panel" aria-label="新旧 Runbook 能力对比">
      <article><span>原演示 · contains</span><strong>{{ baselineMetric ? `Recall@3 ${metric(baselineMetric.recallAt3)}` : '3 条内置文本' }}</strong><small>LEGACY_CONTAINS_V1 / 无分数、版本与引用</small></article>
      <article><span>词法基线 · BM25</span><strong>{{ bm25Metric ? `Recall@3 ${metric(bm25Metric.recallAt3)}` : '尚未评测' }}</strong><small>{{ bm25Metric ? `NDCG@3 ${metric(bm25Metric.ndcgAt3)} · MRR ${metric(bm25Metric.mrr)} · 引用 ${metric(bm25Metric.citationHitRate)}` : '13 条固定改写查询' }}</small></article>
      <article><span>混合检索 · RRF</span><strong>{{ hybridMetric?.available ? `Recall@3 ${metric(hybridMetric.recallAt3)}` : '本次不可用' }}</strong><small>{{ hybridMetric?.available ? `NDCG@3 ${metric(hybridMetric.ndcgAt3)} · MRR ${metric(hybridMetric.mrr)} · 引用 ${metric(hybridMetric.citationHitRate)}` : (hybridMetric?.note ?? '构建完整向量索引后计分') }}</small></article>
      <article><span>语义索引</span><strong>{{ semanticIndex ? `${semanticIndex.state} · ${metric(semanticIndex.coverage)}` : '读取中' }}</strong><small>{{ semanticIndex ? `${semanticIndex.indexedChunkCount}/${semanticIndex.publishedChunkCount} chunks · ${semanticIndex.provider}/${semanticIndex.model}` : '索引状态可观测' }}</small></article>
    </section>

    <p v-if="evaluation" class="evaluation-note">固定集 {{ evaluation.caseCount }} 个查询 · {{ evaluation.judgmentCount }} 个 qrels · 版本 {{ evaluation.datasetVersion }} · {{ evaluation.evaluationNote }} · 选择引擎 {{ evaluation.engine }} · NDCG@3 {{ metric(evaluation.ndcgAt3) }} · 失败 {{ failedCases }}</p>

    <div v-if="error" class="inline-error">{{ error }}</div>
    <div v-if="notice" class="success-banner">{{ notice }}</div>

    <form class="runbook-search content-panel" @submit.prevent="search">
      <Search :size="17" />
      <input v-model="query" aria-label="Runbook 检索语句" placeholder="输入故障症状、指标、组件或恢复动作" />
      <div class="segmented-control search-mode" aria-label="检索模式">
        <button v-for="mode in ['AUTO','BM25','HYBRID']" :key="mode" type="button" :class="{ active: searchMode === mode }" @click="setSearchMode(mode)">{{ mode }}</button>
      </div>
      <button class="primary-button" :disabled="loading">{{ loading ? '检索中…' : '检索' }}</button>
    </form>

    <section v-if="searchResponse" class="content-panel retrieval-panel">
      <header class="panel-heading"><div><h2>可引用检索结果</h2><span>请求 {{ searchResponse.requestedMode }} → {{ searchResponse.engine }} · 候选分块 {{ searchResponse.candidateChunkCount }} · 向量覆盖 {{ metric(searchResponse.semanticCoverage) }}</span></div></header>
      <div v-for="warning in searchResponse.warnings" :key="warning" class="retrieval-warning">{{ warning }}</div>
      <div v-if="searchResponse.results.length" class="retrieval-results">
        <article v-for="result in searchResponse.results" :key="result.citation">
          <div class="rank-score"><strong>{{ result.score.toFixed(4) }}</strong><span>{{ searchResponse.engine === 'HYBRID_RRF_V1' ? 'RRF' : 'BM25' }}</span><small v-if="result.lexicalRank || result.semanticRank">B{{ result.lexicalRank ?? '—' }} / V{{ result.semanticRank ?? '—' }}</small></div>
          <div class="retrieval-result-body">
            <header><strong>{{ result.title }}</strong><span>{{ result.resourceType }} · v{{ result.versionNo }} · {{ result.heading }}</span></header>
            <p>{{ result.excerpt }}</p><code>{{ result.citation }}</code>
            <div class="relevance-actions" aria-label="检索相关性反馈">
              <template v-if="judgmentsByKey[result.stableKey]">
                <CheckCircle2 :size="14" /><span>已提交等级 {{ judgmentsByKey[result.stableKey].relevanceGrade }} · 待独立复核</span>
              </template>
              <template v-else>
                <span>这条结果有帮助吗？</span>
                <button type="button" :disabled="feedbackLoadingKey === result.stableKey || !searchResponse.searchId" @click="submitJudgment(result, 3)"><ThumbsUp :size="13" />高度相关</button>
                <button type="button" :disabled="feedbackLoadingKey === result.stableKey || !searchResponse.searchId" @click="submitJudgment(result, 2)">部分相关</button>
                <button type="button" :disabled="feedbackLoadingKey === result.stableKey || !searchResponse.searchId" @click="submitJudgment(result, 0)"><ThumbsDown :size="13" />不相关</button>
              </template>
            </div>
          </div>
        </article>
      </div>
      <div v-else class="empty-state">当前角色可访问的 Runbook 中没有相关分块</div>
    </section>

    <section v-if="canManage" class="content-panel judgment-panel">
      <header class="panel-heading"><div><h2>待复核相关性判断</h2><span>原始等级对复核人隐藏；复核等级 ≥ 2 才进入固定评测集</span></div><div class="agreement-summary"><span class="keyword-tag">{{ pendingJudgments.length }} PENDING</span><span v-if="agreement" :title="agreement.note">κ {{ kappa(agreement.linearWeightedKappa) }} · 精确 {{ metric(agreement.exactAgreementRate) }} · {{ agreement.sampleCount }} 对</span></div></header>
      <div v-if="pendingJudgments.length" class="judgment-list">
        <article v-for="judgment in pendingJudgments" :key="judgment.id">
          <div><strong>{{ judgment.query }}</strong><span>{{ judgment.documentTitle }} · {{ judgment.actualEngine }} · {{ formatTime(judgment.createdAt, true) }}</span><p>{{ judgment.documentExcerpt }}</p><code>{{ judgment.citation }}</code></div>
          <div class="judgment-actions">
            <button v-for="grade in [3, 2, 1, 0]" :key="grade" type="button" :class="grade >= 2 ? 'primary-button' : 'secondary-button'" :disabled="feedbackLoadingKey === `review-${judgment.id}`" @click="reviewJudgment(judgment, 'APPROVE', grade)">{{ grade }} {{ ['不相关', '弱相关', '部分相关', '高度相关'][grade] }}</button>
            <button type="button" class="secondary-button" :disabled="feedbackLoadingKey === `review-${judgment.id}`" @click="reviewJudgment(judgment, 'REJECT')"><XCircle :size="14" />拒绝样本</button>
          </div>
        </article>
      </div>
      <div v-else class="empty-state">当前没有可由你独立复核的判断</div>
    </section>

    <section class="section-heading runbook-section-heading"><div><h2>已发布文档</h2><span>旧版本保留为 SUPERSEDED，不覆盖历史引用</span></div></section>
    <section class="runbook-grid">
      <article v-for="item in documents" :key="item.id" class="content-panel runbook-card knowledge-card">
        <header><span class="resource-icon"><BookOpenCheck :size="17" /></span><div><em>{{ item.resourceType }} · {{ item.serviceCode ?? '通用' }}</em><h2>{{ item.title }}</h2></div><span class="keyword-tag">v{{ item.versionNo }}</span></header>
        <p v-if="item.summary" class="runbook-summary">{{ item.summary }}</p>
        <ol><li v-for="line in previewLines(item.markdown)" :key="line">{{ line.replace(/^\d+\.\s*/, '') }}</li></ol>
        <div class="runbook-meta"><span><FileText :size="13" />{{ item.sourceType }}</span><span><GitBranch :size="13" />{{ item.chunkCount }} chunks</span><span><ShieldCheck :size="13" />{{ item.allowedRoles.join(' / ') }}</span></div>
        <footer>{{ item.sourceName }} · 发布于 {{ formatTime(item.publishedAt, true) }}</footer>
      </article>
    </section>

    <div v-if="showImport" class="dialog-backdrop" @click.self="showImport = false">
      <form class="dialog-panel runbook-import-dialog" @submit.prevent="submitImport">
        <header><div><h2>导入版本化 Runbook</h2><span>同 stableKey 内容变化会生成新版本；相同内容幂等复用</span></div><button type="button" class="icon-button" title="关闭" @click="showImport = false"><X :size="18" /></button></header>
        <div class="form-grid">
          <label><span>稳定键</span><input v-model="form.stableKey" pattern="[a-z0-9][a-z0-9-]{2,79}" required /></label>
          <label><span>资源类型</span><select v-model="form.resourceType"><option>APPLICATION</option><option>MIDDLEWARE</option><option>DATABASE</option><option>NETWORK</option></select></label>
          <label><span>服务编码</span><input v-model="form.serviceCode" placeholder="APP-SETTLEMENT" /></label>
          <label><span>标题</span><input v-model="form.title" required /></label>
          <label class="span-2"><span>摘要</span><input v-model="form.summary" /></label>
          <label class="span-2"><span>可访问角色</span><div class="role-options"><label v-for="role in ['ADMIN','OPS_MANAGER','ON_CALL']" :key="role"><input v-model="form.allowedRoles" type="checkbox" :value="role" />{{ role }}</label></div></label>
          <label class="span-2"><span>来源方式</span><div class="segmented-control"><button type="button" :class="{ active: importMode === 'markdown' }" @click="importMode = 'markdown'">粘贴 Markdown</button><button type="button" :class="{ active: importMode === 'file' }" @click="importMode = 'file'">上传 .md / .pdf</button></div></label>
          <template v-if="importMode === 'markdown'">
            <label class="span-2"><span>来源路径</span><input v-model="form.sourceName" required /></label>
            <label class="span-2"><span>Markdown 内容</span><textarea v-model="form.markdown" rows="12" required /></label>
          </template>
          <label v-else class="span-2"><span>文件（最大 5 MB，PDF 最多 200 页）</span><input type="file" accept=".md,.markdown,.pdf" required @change="chooseFile" /></label>
        </div>
        <footer><button type="button" class="secondary-button" @click="showImport = false">取消</button><button class="primary-button" :disabled="loading || !form.allowedRoles.length"><Upload :size="15" />{{ loading ? '处理中…' : '导入并发布' }}</button></footer>
      </form>
    </div>
  </div>
</template>

<style scoped>
.runbook-page { display: flex; flex-direction: column; gap: 16px; }
.runbook-toolbar { margin-bottom: 0; }
.runbook-comparison { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); overflow: hidden; }
.runbook-comparison article { min-height: 92px; padding: 17px 19px; display: flex; flex-direction: column; justify-content: center; gap: 5px; border-right: 1px solid var(--line); }
.runbook-comparison article:last-child { border-right: 0; }
.runbook-comparison span, .runbook-comparison small { color: var(--text-muted); font-size: 10px; }
.runbook-comparison strong { font-size: 16px; }
.evaluation-note { margin: -7px 2px 0; color: var(--text-muted); font-size: 9px; }
.runbook-search { min-height: 54px; padding: 8px 10px 8px 15px; display: flex; align-items: center; gap: 10px; }
.runbook-search > svg { color: var(--accent); }
.runbook-search input { min-width: 0; flex: 1; border: 0; outline: 0; background: transparent; font-size: 12px; }
.search-mode { flex: 0 0 auto; }
.search-mode button { min-height: 30px; padding: 0 9px; }
.retrieval-panel { overflow: hidden; }
.retrieval-warning { margin: 0 16px 6px; padding: 8px 10px; border: 1px solid #f1d7a8; border-radius: 6px; color: #805b16; background: #fff8e8; font-size: 9px; }
.retrieval-results { padding: 5px 16px 12px; }
.retrieval-results article { padding: 14px 0; display: grid; grid-template-columns: 62px minmax(0, 1fr); gap: 14px; border-bottom: 1px solid var(--line); }
.retrieval-results article:last-child { border-bottom: 0; }
.rank-score { display: flex; flex-direction: column; align-items: flex-start; gap: 3px; }
.rank-score strong { color: var(--accent); font-size: 15px; font-variant-numeric: tabular-nums; }
.rank-score span { color: var(--text-muted); font-size: 8px; }
.rank-score small { color: var(--text-muted); font-size: 8px; font-variant-numeric: tabular-nums; }
.retrieval-results article > div:last-child { min-width: 0; }
.retrieval-results header { display: flex; align-items: baseline; justify-content: space-between; gap: 10px; }
.retrieval-results header strong { font-size: 12px; }.retrieval-results header span { color: var(--text-muted); font-size: 9px; }
.retrieval-results p { margin: 7px 0; color: #55555a; font-size: 10px; line-height: 1.55; }
.retrieval-results code { display: inline-block; font-size: 9px; }
.relevance-actions { margin-top: 9px; display: flex; align-items: center; flex-wrap: wrap; gap: 6px; color: var(--text-muted); font-size: 9px; }
.relevance-actions button { min-height: 26px; padding: 0 8px; display: inline-flex; align-items: center; gap: 4px; border: 1px solid var(--line); border-radius: 5px; color: #55555a; background: #fff; font-size: 9px; cursor: pointer; }
.relevance-actions button:hover { border-color: var(--accent); color: var(--accent); }
.relevance-actions button:disabled { cursor: not-allowed; opacity: .5; }
.judgment-panel { overflow: hidden; }
.judgment-list { padding: 0 16px 10px; }
.judgment-list article { padding: 12px 0; display: flex; align-items: center; justify-content: space-between; gap: 16px; border-bottom: 1px solid var(--line); }
.judgment-list article:last-child { border-bottom: 0; }
.judgment-list article > div:first-child { min-width: 0; display: flex; flex-direction: column; gap: 4px; }
.judgment-list strong { font-size: 11px; }
.judgment-list code, .judgment-list span { color: var(--text-muted); font-size: 9px; }
.judgment-list p { max-width: 680px; margin: 2px 0; color: #55555a; font-size: 9px; line-height: 1.5; }
.agreement-summary { display: flex; align-items: center; gap: 8px; color: var(--text-muted); font-size: 9px; }
.judgment-actions { flex: 0 0 auto; display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 7px; }
.runbook-section-heading { padding: 2px 0 0; }
.runbook-summary { min-height: 34px; margin: 7px 0 0; color: var(--text-muted); font-size: 10px; line-height: 1.55; }
.runbook-meta { margin-top: 12px; display: flex; align-items: center; flex-wrap: wrap; gap: 6px; }
.runbook-meta span { padding: 4px 6px; display: inline-flex; align-items: center; gap: 4px; border-radius: 5px; color: #5b5b60; background: #f1f1f3; font-size: 8px; }
.knowledge-card footer { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.runbook-import-dialog { width: min(700px, 100%); }
.role-options { min-height: 38px; display: flex; align-items: center; gap: 16px; }
.role-options label { display: flex; flex-direction: row; align-items: center; gap: 6px; color: #55555a; font-size: 10px; }
.role-options input { width: 15px; min-height: 15px; }
@media (min-width: 1101px) { .runbook-comparison { grid-template-columns: repeat(4, minmax(0, 1fr)); } }
@media (max-width: 1100px) { .runbook-comparison { grid-template-columns: repeat(2, minmax(0, 1fr)); }.runbook-comparison article:nth-child(2) { border-right: 0; }.runbook-comparison article:nth-child(-n+2) { border-bottom: 1px solid var(--line); } }
@media (max-width: 640px) { .runbook-comparison { grid-template-columns: 1fr; }.runbook-comparison article { min-height: 72px; border-right: 0; border-bottom: 1px solid var(--line); }.runbook-comparison article:last-child { border-bottom: 0; }.runbook-toolbar { align-items: flex-start; flex-direction: column; }.runbook-toolbar .toolbar-group { width: 100%; flex-wrap: wrap; }.runbook-toolbar button { flex: 1; }.runbook-search { align-items: stretch; flex-wrap: wrap; }.runbook-search input { min-height: 34px; }.runbook-search > button { width: 100%; }.search-mode { width: 100%; }.search-mode button { flex: 1; }.retrieval-results article { grid-template-columns: 1fr; gap: 5px; }.retrieval-results header { align-items: flex-start; flex-direction: column; }.judgment-list article { align-items: flex-start; flex-direction: column; }.judgment-actions { width: 100%; }.judgment-actions button { flex: 1; }.form-grid { grid-template-columns: 1fr; }.span-2 { grid-column: 1; }.role-options { align-items: flex-start; flex-direction: column; gap: 8px; } }
</style>

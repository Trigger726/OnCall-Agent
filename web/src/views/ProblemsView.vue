<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import {
  AlertTriangle, BarChart3, CheckCircle2, ClipboardList, GitBranch,
  RefreshCw, RotateCw, Save, ShieldAlert, UsersRound,
} from 'lucide-vue-next'
import StatusBadge from '@/components/StatusBadge.vue'
import { api, formatTime, type PageResponse } from '@/services/api'
import { auth } from '@/stores/auth'

interface IncidentRef {
  id: number
  incidentCode: string
  title: string
  severity: string
  status: string
  createdAt: string
  resolvedAt: string | null
}

interface RecurrenceCandidate {
  recurrenceKey: string
  matchReason: string
  serviceId: number
  serviceName: string
  signalTitle: string
  incidentCount: number
  distinctDays: number
  firstIncidentAt: string
  latestIncidentAt: string
  totalAlertOccurrences: number
  activeIncidentCount: number
  highestSeverity: string
  problemId: number | null
  problemCode: string | null
  problemStatus: string | null
  unlinkedIncidentCount: number
  recurredAfterResolution: boolean
  incidents: IncidentRef[]
}

interface ProblemView {
  id: number
  problemCode: string
  recurrenceKey: string
  matchReason: string
  serviceId: number
  serviceName: string
  title: string
  status: string
  rootCause: string
  workaround: string
  resolutionSummary: string
  ownerId: number
  ownerName: string
  creatorId: number
  creatorName: string
  resolverId: number | null
  resolverName: string | null
  resolvedAt: string | null
  version: number
  createdAt: string
  updatedAt: string
  incidentCount: number
  firstIncidentAt: string | null
  latestIncidentAt: string | null
  activeIncidentCount: number
  recurredAfterResolution: boolean
  incidents: IncidentRef[]
}

interface ProblemCreateResult {
  created: boolean
  newlyLinkedIncidents: number
  problem: ProblemView
}

function localDate(date: Date): string {
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

const today = new Date()
const start = new Date(today)
start.setDate(start.getDate() - 89)

const from = ref(localDate(start))
const to = ref(localDate(today))
const problemStatus = ref('')
const candidates = ref<RecurrenceCandidate[]>([])
const problems = ref<ProblemView[]>([])
const candidateTotal = ref(0)
const problemTotal = ref(0)
const selectedId = ref<number | null>(null)
const loading = ref(false)
const promotingKey = ref('')
const saving = ref(false)
const error = ref('')
const notice = ref('')
const draft = reactive({
  title: '',
  status: 'OPEN',
  rootCause: '',
  workaround: '',
  resolutionSummary: '',
})

const canManage = computed(() => ['ADMIN', 'OPS_MANAGER'].includes(auth.state.user?.roleCode ?? ''))
const selectedProblem = computed(() => problems.value.find(item => item.id === selectedId.value) ?? null)
const recurringIncidentCount = computed(() => candidates.value.reduce((sum, item) => sum + item.incidentCount, 0))
const activeIncidentCount = computed(() => candidates.value.reduce((sum, item) => sum + item.activeIncidentCount, 0))
const postResolutionCount = computed(() => candidates.value.filter(item => item.recurredAfterResolution).length)
const saveDisabled = computed(() => {
  if (!selectedProblem.value || !draft.title.trim()) return true
  if (draft.status === 'KNOWN_ERROR' && (!draft.rootCause.trim() || !draft.workaround.trim())) return true
  return draft.status === 'RESOLVED' && !draft.resolutionSummary.trim()
})

watch(selectedProblem, (problem) => {
  if (!problem) return
  draft.title = problem.title
  draft.status = problem.status
  draft.rootCause = problem.rootCause
  draft.workaround = problem.workaround
  draft.resolutionSummary = problem.resolutionSummary
})

function query(path: string, params: Record<string, string>): string {
  const search = new URLSearchParams(params)
  return `${path}?${search.toString()}`
}

async function load(preserveMessage = false) {
  loading.value = true
  error.value = ''
  if (!preserveMessage) notice.value = ''
  try {
    const [candidatePage, problemPage] = await Promise.all([
      api<PageResponse<RecurrenceCandidate>>(query('/problems/recurrence-candidates', {
        from: from.value,
        to: to.value,
        page: '1',
        size: '50',
      })),
      api<PageResponse<ProblemView>>(query('/problems', {
        ...(problemStatus.value ? { status: problemStatus.value } : {}),
        page: '1',
        size: '50',
      })),
    ])
    candidates.value = candidatePage.items
    candidateTotal.value = candidatePage.total
    problems.value = problemPage.items
    problemTotal.value = problemPage.total
    if (selectedId.value == null || !problems.value.some(item => item.id === selectedId.value)) {
      selectedId.value = problems.value[0]?.id ?? null
    }
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : '问题治理数据加载失败'
  } finally {
    loading.value = false
  }
}

async function promote(candidate: RecurrenceCandidate) {
  promotingKey.value = candidate.recurrenceKey
  error.value = ''
  notice.value = ''
  try {
    const result = await api<ProblemCreateResult>('/problems', {
      method: 'POST',
      body: JSON.stringify({ recurrenceKey: candidate.recurrenceKey, from: from.value, to: to.value }),
    })
    selectedId.value = result.problem.id
    await load(true)
    selectedId.value = result.problem.id
    notice.value = result.created
      ? `已创建 ${result.problem.problemCode}，固化 ${result.newlyLinkedIncidents} 个 Incident 证据。`
      : `${result.problem.problemCode} 已存在，本次补充 ${result.newlyLinkedIncidents} 个关联。`
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : '提升为 Problem 失败'
  } finally {
    promotingKey.value = ''
  }
}

async function saveProblem() {
  const problem = selectedProblem.value
  if (!problem || saveDisabled.value) return
  saving.value = true
  error.value = ''
  notice.value = ''
  try {
    const updated = await api<ProblemView>(`/problems/${problem.id}`, {
      method: 'PATCH',
      body: JSON.stringify({
        expectedVersion: problem.version,
        title: draft.title.trim(),
        status: draft.status,
        rootCause: draft.rootCause.trim(),
        workaround: draft.workaround.trim(),
        resolutionSummary: draft.resolutionSummary.trim(),
      }),
    })
    await load(true)
    selectedId.value = updated.id
    notice.value = `${updated.problemCode} 已更新至版本 ${updated.version}。`
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : 'Problem 更新失败'
  } finally {
    saving.value = false
  }
}

function statusLabel(status: string | null): string {
  return ({ OPEN: '开放', KNOWN_ERROR: '已知错误', RESOLVED: '已解决' } as Record<string, string>)[status ?? ''] ?? '未登记'
}

function statusClass(status: string | null): string {
  if (status === 'RESOLVED') return 'status-success'
  if (status === 'KNOWN_ERROR') return 'status-warning'
  return status === 'OPEN' ? 'status-info' : 'status-neutral'
}

onMounted(load)
</script>

<template>
  <div class="page-content problem-page">
    <div class="page-toolbar problem-toolbar">
      <div><strong>重复事故与 Problem 治理</strong><span>从可解释的复发证据进入根因、规避方案和长期解决闭环</span></div>
      <div class="toolbar-group problem-filters">
        <label>开始<input v-model="from" type="date" :max="to" /></label>
        <label>结束<input v-model="to" type="date" :min="from" /></label>
        <button class="secondary-button" :disabled="loading" @click="load()">
          <RefreshCw :size="15" :class="{ spin: loading }" />查询
        </button>
      </div>
    </div>

    <div class="method-banner">
      <GitBranch :size="17" />
      <div><strong>高精度确定性基线</strong><span>同一归属服务 + 完全相同告警指纹，且跨至少 2 个不同 Incident；同一事故内重复告警只增加信号次数，不增加复发次数。</span></div>
      <em>EXACT_ALERT_FINGERPRINT</em>
    </div>
    <div v-if="error" class="inline-error"><AlertTriangle :size="16" />{{ error }}</div>
    <div v-if="notice" class="success-banner"><CheckCircle2 :size="16" />{{ notice }}</div>

    <section class="metric-strip problem-metrics" aria-label="问题治理指标">
      <article><span class="metric-icon danger"><RotateCw :size="18" /></span><div><small>复发候选</small><strong>{{ candidateTotal }}</strong><em>精确指纹簇</em></div></article>
      <article><span class="metric-icon warning"><BarChart3 :size="18" /></span><div><small>独立 Incident 证据</small><strong>{{ recurringIncidentCount }}</strong><em>当前候选合计</em></div></article>
      <article><span class="metric-icon info"><ClipboardList :size="18" /></span><div><small>已登记 Problem</small><strong>{{ problemTotal }}</strong><em>持久化治理记录</em></div></article>
      <article><span class="metric-icon neutral"><ShieldAlert :size="18" /></span><div><small>当前未关闭 / 解决后复发</small><strong>{{ activeIncidentCount }}<i>/ {{ postResolutionCount }}</i></strong><em>不静默重开状态</em></div></article>
    </section>

    <section class="content-panel candidate-panel">
      <div class="panel-heading">
        <div><h2>复发候选</h2><span>{{ from }} 至 {{ to }}，按 Incident 创建时间纳入</span></div>
        <strong>{{ candidateTotal }} 组</strong>
      </div>
      <div class="table-scroll">
        <table class="data-table candidate-table">
          <thead><tr><th>证据与影响服务</th><th>独立事故</th><th>告警信号</th><th>时间跨度</th><th>治理状态</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="candidate in candidates" :key="candidate.recurrenceKey" :class="{ 'recurred-row': candidate.recurredAfterResolution }">
              <td>
                <div class="candidate-identity"><StatusBadge :value="candidate.highestSeverity" /><div><strong>{{ candidate.signalTitle }}</strong><span>{{ candidate.serviceName }} · 精确指纹匹配</span></div></div>
                <div class="incident-links">
                  <RouterLink v-for="incident in candidate.incidents" :key="incident.id" :to="`/incidents?selected=${incident.id}`">{{ incident.incidentCode }}</RouterLink>
                  <em v-if="candidate.incidentCount > candidate.incidents.length">+{{ candidate.incidentCount - candidate.incidents.length }}</em>
                </div>
              </td>
              <td><div class="number-stack"><strong>{{ candidate.incidentCount }}</strong><span>{{ candidate.distinctDays }} 个不同日期</span><small v-if="candidate.activeIncidentCount">{{ candidate.activeIncidentCount }} 个未关闭</small></div></td>
              <td><div class="number-stack"><strong>{{ candidate.totalAlertOccurrences }}</strong><span>occurrence 总量</span><small>不作为事故分母</small></div></td>
              <td><div class="time-stack"><span>{{ formatTime(candidate.firstIncidentAt, true) }}</span><i /> <span>{{ formatTime(candidate.latestIncidentAt, true) }}</span></div></td>
              <td>
                <div class="governance-state">
                  <span class="status-badge" :class="statusClass(candidate.problemStatus)">{{ statusLabel(candidate.problemStatus) }}</span>
                  <button v-if="candidate.problemId" type="button" @click="selectedId = candidate.problemId">{{ candidate.problemCode }}</button>
                  <strong v-if="candidate.recurredAfterResolution"><AlertTriangle :size="12" />解决后复发</strong>
                  <small v-else-if="candidate.unlinkedIncidentCount">{{ candidate.unlinkedIncidentCount }} 个证据待关联</small>
                </div>
              </td>
              <td>
                <button v-if="canManage" class="table-action" :disabled="promotingKey === candidate.recurrenceKey" @click="promote(candidate)">
                  {{ promotingKey === candidate.recurrenceKey ? '处理中' : candidate.problemId ? '补充关联' : '登记 Problem' }}
                </button>
                <span v-else class="muted">只读</span>
              </td>
            </tr>
          </tbody>
        </table>
        <div v-if="!loading && !candidates.length" class="problem-empty"><CheckCircle2 :size="18" />当前窗口没有跨 Incident 的精确指纹复发候选。</div>
      </div>
    </section>

    <section class="problem-workspace">
      <aside class="content-panel problem-list">
        <div class="panel-heading problem-list-heading">
          <div><h2>Problem 台账</h2><span>负责人和状态持久化</span></div>
          <select v-model="problemStatus" aria-label="Problem 状态" @change="load()">
            <option value="">全部状态</option><option value="OPEN">开放</option><option value="KNOWN_ERROR">已知错误</option><option value="RESOLVED">已解决</option>
          </select>
        </div>
        <button v-for="problem in problems" :key="problem.id" type="button" :class="{ active: problem.id === selectedId }" @click="selectedId = problem.id">
          <span><b>{{ problem.problemCode }}</b><em class="status-badge" :class="statusClass(problem.status)">{{ statusLabel(problem.status) }}</em></span>
          <strong>{{ problem.title }}</strong>
          <small>{{ problem.serviceName }} · {{ problem.incidentCount }} 个 Incident</small>
          <i v-if="problem.recurredAfterResolution"><AlertTriangle :size="12" />解决后复发</i>
        </button>
        <div v-if="!loading && !problems.length" class="problem-empty compact">当前筛选下暂无 Problem。</div>
      </aside>

      <article class="content-panel problem-detail">
        <template v-if="selectedProblem">
          <header>
            <div><span>{{ selectedProblem.problemCode }} · v{{ selectedProblem.version }}</span><h2>{{ selectedProblem.title }}</h2><p>{{ selectedProblem.serviceName }} · {{ selectedProblem.matchReason }}</p></div>
            <span class="status-badge" :class="statusClass(selectedProblem.status)">{{ statusLabel(selectedProblem.status) }}</span>
          </header>

          <div class="problem-facts">
            <div><span>负责人</span><strong><UsersRound :size="13" />{{ selectedProblem.ownerName }}</strong></div>
            <div><span>关联 Incident</span><strong>{{ selectedProblem.incidentCount }} 个</strong></div>
            <div><span>未关闭 Incident</span><strong>{{ selectedProblem.activeIncidentCount }} 个</strong></div>
            <div><span>最近证据</span><strong>{{ formatTime(selectedProblem.latestIncidentAt, true) }}</strong></div>
          </div>

          <div v-if="selectedProblem.recurredAfterResolution" class="recurrence-alert"><AlertTriangle :size="15" /><span><strong>解决后再次复发</strong>Problem 保持“已解决”，由负责人判断是否重新打开，避免系统静默改写治理结论。</span></div>

          <form class="problem-form" @submit.prevent="saveProblem">
            <label class="full"><span>Problem 标题</span><input v-model="draft.title" :disabled="!canManage" maxlength="240" /></label>
            <label><span>治理状态</span><select v-model="draft.status" :disabled="!canManage"><option value="OPEN">开放</option><option value="KNOWN_ERROR">已知错误</option><option value="RESOLVED">已解决</option></select></label>
            <label><span>版本控制</span><input :value="`v${selectedProblem.version} · 乐观锁`" disabled /></label>
            <label class="full"><span>根因 <em v-if="draft.status === 'KNOWN_ERROR'">必填</em></span><textarea v-model="draft.rootCause" :disabled="!canManage" rows="3" maxlength="4000" placeholder="写明已经由证据确认的根因；未知时保持开放状态" /></label>
            <label class="full"><span>临时规避方案 <em v-if="draft.status === 'KNOWN_ERROR'">必填</em></span><textarea v-model="draft.workaround" :disabled="!canManage" rows="3" maxlength="4000" placeholder="值班人员可执行、可验证的临时恢复步骤" /></label>
            <label class="full"><span>长期解决说明 <em v-if="draft.status === 'RESOLVED'">必填</em></span><textarea v-model="draft.resolutionSummary" :disabled="!canManage" rows="3" maxlength="2000" placeholder="永久修复、验证结果与观测结论" /></label>
            <footer>
              <span v-if="draft.status === 'KNOWN_ERROR'"><ShieldAlert :size="13" />进入已知错误必须同时保存根因与规避方案。</span>
              <span v-else-if="draft.status === 'RESOLVED'"><CheckCircle2 :size="13" />解决状态必须保存长期解决说明。</span>
              <span v-else><GitBranch :size="13" />开放状态用于调查和归因，不要求伪造结论。</span>
              <button v-if="canManage" class="primary-button" :disabled="saving || saveDisabled"><Save :size="15" />{{ saving ? '保存中' : '保存治理记录' }}</button>
            </footer>
          </form>

          <div class="linked-evidence">
            <div><strong>已固化 Incident 证据</strong><span>新同指纹 Incident 会自动幂等关联</span></div>
            <RouterLink v-for="incident in selectedProblem.incidents" :key="incident.id" :to="`/incidents?selected=${incident.id}`">
              <StatusBadge :value="incident.severity" /><span><strong>{{ incident.incidentCode }}</strong><small>{{ incident.title }}</small></span><em>{{ formatTime(incident.createdAt, true) }}</em>
            </RouterLink>
          </div>
        </template>
        <div v-else class="problem-empty detail-empty"><ClipboardList :size="22" />选择或登记一个 Problem 后，在这里维护治理结论。</div>
      </article>
    </section>

    <footer class="problem-boundary"><ShieldAlert :size="14" />当前仅识别“相同服务 + 完全相同告警指纹”的历史复发，不声称已具备语义相似、跨服务因果或机器学习概率能力。</footer>
  </div>
</template>

<style scoped>
.problem-page { min-width: 0; display: flex; flex-direction: column; gap: 14px; overflow-x: clip; }
.problem-page .page-toolbar, .problem-page .metric-strip { margin-bottom: 0; }
.problem-toolbar { align-items: flex-end; }
.problem-filters { display: flex; align-items: flex-end; }
.problem-filters label { display: flex; flex-direction: column; gap: 4px; color: var(--text-muted); font-size: 8px; font-weight: 700; }
.problem-filters input { width: 126px; height: 32px; padding: 0 8px; border: 1px solid var(--line); border-radius: 4px; background: #fff; font-size: 10px; }
.method-banner { min-height: 58px; padding: 11px 14px; display: grid; grid-template-columns: auto minmax(0, 1fr) auto; align-items: center; gap: 11px; border: 1px solid #cfe0f2; border-radius: 8px; color: #2f6eaa; background: #f7fbff; }
.method-banner > div { min-width: 0; display: flex; flex-direction: column; gap: 3px; }
.method-banner strong { color: #2d5274; font-size: 11px; }
.method-banner span { color: #647687; font-size: 9px; line-height: 1.5; }
.method-banner em { padding: 4px 7px; border-radius: 5px; color: #326c9e; background: #e6f1fc; font-size: 8px; font-style: normal; font-weight: 750; }
.problem-metrics article > div { grid-template-columns: 1fr; }
.problem-metrics em { white-space: normal; }
.candidate-panel { overflow: hidden; }
.candidate-panel .panel-heading > strong { color: var(--text-muted); font-size: 10px; }
.candidate-table { min-width: 960px; }
.candidate-table td:first-child { min-width: 280px; }
.recurred-row { background: #fffafa; }
.candidate-identity { display: grid; grid-template-columns: auto minmax(0, 1fr); align-items: start; gap: 9px; }
.candidate-identity > div { min-width: 0; display: flex; flex-direction: column; gap: 4px; }
.candidate-identity strong { color: var(--text); font-size: 11px; line-height: 1.4; }
.candidate-identity span { color: var(--text-muted); font-size: 9px; }
.incident-links { margin-top: 8px; display: flex; flex-wrap: wrap; gap: 5px; }
.incident-links a, .incident-links em { padding: 3px 6px; border-radius: 4px; color: #236da9; background: #edf6ff; font-size: 8px; font-style: normal; font-weight: 650; }
.number-stack, .governance-state { display: flex; flex-direction: column; align-items: flex-start; gap: 3px; }
.number-stack strong { color: var(--text); font-size: 16px; font-variant-numeric: tabular-nums; }
.number-stack span, .number-stack small { color: var(--text-muted); font-size: 8px; }
.number-stack small { color: var(--danger); }
.time-stack { min-width: 90px; display: flex; flex-direction: column; gap: 5px; color: #56565b; font-size: 9px; }
.time-stack i { width: 1px; height: 9px; margin-left: 4px; background: #d6d6da; }
.governance-state button { padding: 0; border: 0; color: var(--accent); background: transparent; font-size: 8px; }
.governance-state strong { display: inline-flex; align-items: center; gap: 4px; color: var(--danger); font-size: 8px; }
.governance-state small { color: var(--warning); font-size: 8px; }
.problem-empty { min-height: 92px; padding: 25px 16px; display: flex; align-items: center; justify-content: center; gap: 7px; color: var(--text-muted); font-size: 10px; text-align: center; }
.problem-empty.compact { min-height: 72px; }
.problem-workspace { min-width: 0; display: grid; grid-template-columns: minmax(270px, .58fr) minmax(0, 1.42fr); gap: 14px; align-items: start; }
.problem-list, .problem-detail { overflow: hidden; }
.problem-list-heading select { height: 30px; padding: 0 27px 0 8px; border: 1px solid var(--line); border-radius: 5px; background: #fff; font-size: 9px; }
.problem-list > button { width: 100%; min-height: 94px; padding: 13px 14px; display: flex; flex-direction: column; align-items: stretch; gap: 6px; border: 0; border-bottom: 1px solid var(--line); background: #fff; text-align: left; }
.problem-list > button:hover { background: #fafafa; }
.problem-list > button.active { background: #f2f7fd; box-shadow: inset 3px 0 var(--accent); }
.problem-list > button > span { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.problem-list > button b { color: var(--accent); font-size: 9px; }
.problem-list > button > strong { color: var(--text); font-size: 11px; line-height: 1.4; }
.problem-list > button small { color: var(--text-muted); font-size: 8px; }
.problem-list > button > i { display: flex; align-items: center; gap: 4px; color: var(--danger); font-size: 8px; font-style: normal; font-weight: 700; }
.problem-detail > header { min-height: 92px; padding: 17px 19px; display: flex; align-items: flex-start; justify-content: space-between; gap: 14px; border-bottom: 1px solid var(--line); }
.problem-detail > header > div { min-width: 0; }
.problem-detail > header span { color: var(--text-muted); font-size: 9px; }
.problem-detail > header h2 { margin: 6px 0 0; color: var(--text); font-size: 18px; line-height: 1.4; }
.problem-detail > header p { margin: 5px 0 0; color: var(--text-muted); font-size: 9px; }
.problem-facts { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); border-bottom: 1px solid var(--line); background: #fafafa; }
.problem-facts > div { min-width: 0; min-height: 64px; padding: 12px 14px; display: flex; flex-direction: column; gap: 6px; border-right: 1px solid var(--line); }
.problem-facts > div:last-child { border-right: 0; }
.problem-facts span { color: var(--text-muted); font-size: 8px; }
.problem-facts strong { display: flex; align-items: center; gap: 5px; color: var(--text); font-size: 10px; }
.recurrence-alert { margin: 14px 16px 0; padding: 10px 11px; display: flex; align-items: flex-start; gap: 8px; border: 1px solid #efc8c5; border-radius: 6px; color: var(--danger); background: var(--danger-soft); }
.recurrence-alert span { display: flex; flex-direction: column; gap: 3px; color: #7d4a47; font-size: 8px; line-height: 1.5; }
.recurrence-alert strong { color: var(--danger); font-size: 10px; }
.problem-form { padding: 16px; display: grid; grid-template-columns: 1fr 1fr; gap: 12px; border-bottom: 1px solid var(--line); }
.problem-form label { min-width: 0; display: flex; flex-direction: column; gap: 5px; }
.problem-form label.full { grid-column: 1 / -1; }
.problem-form label > span { color: #5d5d62; font-size: 9px; font-weight: 650; }
.problem-form label > span em { margin-left: 4px; color: var(--danger); font-size: 8px; font-style: normal; }
.problem-form input, .problem-form select, .problem-form textarea { width: 100%; padding: 8px 10px; border: 1px solid var(--line); border-radius: 6px; color: var(--text); background: #fff; font-size: 10px; line-height: 1.5; resize: vertical; }
.problem-form input, .problem-form select { min-height: 34px; }
.problem-form input:disabled, .problem-form select:disabled, .problem-form textarea:disabled { color: #6f6f74; background: #f5f5f6; }
.problem-form footer { grid-column: 1 / -1; display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.problem-form footer > span { display: flex; align-items: center; gap: 5px; color: var(--text-muted); font-size: 8px; line-height: 1.45; }
.linked-evidence { padding: 15px 16px 17px; }
.linked-evidence > div { margin-bottom: 8px; display: flex; align-items: baseline; justify-content: space-between; gap: 10px; }
.linked-evidence > div strong { font-size: 11px; }
.linked-evidence > div span { color: var(--text-muted); font-size: 8px; }
.linked-evidence > a { min-height: 50px; padding: 8px 9px; display: grid; grid-template-columns: auto minmax(0, 1fr) auto; align-items: center; gap: 8px; border-bottom: 1px solid var(--line); }
.linked-evidence > a:hover { background: #fafafa; }
.linked-evidence > a > span { min-width: 0; display: flex; flex-direction: column; gap: 3px; }
.linked-evidence > a strong { color: var(--text); font-size: 9px; }
.linked-evidence > a small { overflow: hidden; color: var(--text-muted); font-size: 8px; text-overflow: ellipsis; white-space: nowrap; }
.linked-evidence > a em { color: var(--text-muted); font-size: 8px; font-style: normal; }
.detail-empty { min-height: 360px; flex-direction: column; }
.problem-boundary { min-height: 42px; padding: 10px 13px; display: flex; align-items: center; gap: 7px; border: 1px solid var(--line); border-radius: 7px; color: var(--text-muted); background: #fafafa; font-size: 9px; line-height: 1.5; }
.problem-boundary svg { flex: none; color: var(--warning); }

@media (max-width: 980px) {
  .problem-toolbar { align-items: flex-start; flex-direction: column; }
  .problem-filters { width: 100%; justify-content: flex-start; }
  .problem-workspace { grid-template-columns: 1fr; }
  .problem-list { max-height: 340px; overflow-y: auto; }
}

@media (max-width: 640px) {
  .problem-filters { flex-wrap: wrap; }
  .problem-filters label { flex: 1 1 42%; }
  .problem-filters input { width: 100%; }
  .problem-filters button { width: 100%; }
  .method-banner { grid-template-columns: auto minmax(0, 1fr); }
  .method-banner em { grid-column: 2; justify-self: start; }
  .problem-facts { grid-template-columns: 1fr 1fr; }
  .problem-facts > div:nth-child(2) { border-right: 0; }
  .problem-facts > div:nth-child(-n+2) { border-bottom: 1px solid var(--line); }
  .problem-form { grid-template-columns: 1fr; }
  .problem-form label, .problem-form label.full, .problem-form footer { grid-column: 1; }
  .problem-form footer { align-items: stretch; flex-direction: column; }
  .problem-form footer button { width: 100%; }
  .linked-evidence > div { align-items: flex-start; flex-direction: column; }
}
</style>

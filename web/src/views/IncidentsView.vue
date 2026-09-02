<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Bot, Check, CheckCircle2, ChevronDown, ChevronRight, CircleCheck, CircleStop, Clock3, Database, FileText, LoaderCircle, MessageSquarePlus, MessageSquareText, Radio, RefreshCw, Send, ShieldAlert, UserRound, Workflow, XCircle } from 'lucide-vue-next'
import StatusBadge from '@/components/StatusBadge.vue'
import { api, formatTime, type PageResponse } from '@/services/api'
import { clearAgentInvestigationIdempotency, streamAgentInvestigation } from '@/services/agentStream'
import { auth } from '@/stores/auth'
import type { AgentRun, AgentRunEvent, AgentStep, RemediationProposal } from '@/types/investigation'

interface IncidentSummary {
  id: number; incidentCode: string; title: string; severity: string; status: string; version: number
  resourceName: string; commander: string | null; assignee: string | null; alertCount: number
  createdAt: string; updatedAt: string
}

interface IncidentDetail {
  incident: IncidentSummary
  description: string
  alerts: { id: number; source: string; severity: string; status: string; title: string; occurrenceCount: number; lastOccurredAt: string }[]
  timeline: { id: number; eventType: string; fromStatus: string | null; toStatus: string | null; actor: string | null; content: string; evidenceRef: string | null; createdAt: string }[]
  investigations: { id: number; engine: string; status: string; summary: string; hypothesis: string; confidence: number; suggestions: string; evidenceJson: string; createdAt: string }[]
  agentRuns: AgentRun[]
  remediationProposals: RemediationProposal[]
}

const route = useRoute()
const router = useRouter()
const incidents = ref<IncidentSummary[]>([])
const selected = ref<IncidentDetail | null>(null)
const loading = ref(false)
const actionLoading = ref(false)
const statusFilter = ref('')
const severityFilter = ref('')
const note = ref('')
const toast = ref('')
const expandedRunId = ref<number | null>(null)
const agentStreaming = ref(false)
const activeAgentRunId = ref<number | null>(null)
const agentControlLoading = ref(false)
const liveAgentEvents = ref<AgentRunEvent[]>([])
const reviewComments = ref<Record<number, string>>({})
const reviewLoadingId = ref<number | null>(null)
let agentAbortController: AbortController | null = null

const transitionMap: Record<string, { value: string; label: string }[]> = {
  OPEN: [{ value: 'ACKNOWLEDGED', label: '确认接手' }],
  ACKNOWLEDGED: [{ value: 'INVESTIGATING', label: '开始调查' }],
  INVESTIGATING: [{ value: 'MITIGATED', label: '标记已缓解' }],
  MITIGATED: [{ value: 'INVESTIGATING', label: '退回调查' }, { value: 'RESOLVED', label: '确认恢复' }],
  RESOLVED: [{ value: 'INVESTIGATING', label: '重新打开' }, { value: 'CLOSED', label: '关闭 Incident' }],
}
const availableTransitions = computed(() => selected.value ? transitionMap[selected.value.incident.status] ?? [] : [])

async function loadList(preferredId?: number) {
  loading.value = true
  const query = new URLSearchParams({ size: '100' })
  if (statusFilter.value) query.set('status', statusFilter.value)
  if (severityFilter.value) query.set('severity', severityFilter.value)
  try {
    const page = await api<PageResponse<IncidentSummary>>(`/incidents?${query}`)
    incidents.value = page.items
    const routeId = Number(route.query.selected)
    const target = preferredId ?? (Number.isFinite(routeId) && routeId > 0 ? routeId : page.items[0]?.id)
    if (target) await selectIncident(target)
    else selected.value = null
  } finally {
    loading.value = false
  }
}

async function selectIncident(id: number) {
  const [detail, remediationProposals] = await Promise.all([
    api<Omit<IncidentDetail, 'remediationProposals'>>(`/incidents/${id}`),
    api<RemediationProposal[]>(`/incidents/${id}/remediation-proposals`),
  ])
  selected.value = { ...detail, remediationProposals }
  activeAgentRunId.value = detail.agentRuns.find(run => run.status === 'QUEUED' || run.status === 'RUNNING')?.id ?? null
  expandedRunId.value = detail.agentRuns[0]?.id ?? null
  if (Number(route.query.selected) !== id) void router.replace({ query: { ...route.query, selected: String(id) } })
}

async function transition(targetStatus: string, label: string) {
  if (!selected.value) return
  actionLoading.value = true
  try {
    await api(`/incidents/${selected.value.incident.id}/transitions`, {
      method: 'POST',
      body: JSON.stringify({ targetStatus, version: selected.value.incident.version, note: label }),
    })
    toast.value = `${label}成功`
    await loadList(selected.value.incident.id)
  } catch (caught) {
    toast.value = caught instanceof Error ? caught.message : '操作失败'
    await selectIncident(selected.value.incident.id)
  } finally {
    actionLoading.value = false
  }
}

async function investigate() {
  if (!selected.value || agentStreaming.value) return
  const incidentId = selected.value.incident.id
  let streamedRunId: number | null = null
  let streamError: string | null = null
  actionLoading.value = true
  agentStreaming.value = true
  liveAgentEvents.value = []
  agentAbortController = new AbortController()
  try {
    await streamAgentInvestigation(incidentId, 'INCIDENT_WORKSPACE', event => {
      liveAgentEvents.value.push(event)
      streamedRunId ??= event.runId
      if (event.eventType === 'RUN_QUEUED') {
        activeAgentRunId.value = event.runId
        toast.value = `Agent 调查 #${event.runId} 已进入队列`
      }
      if (event.eventType === 'RUN_STARTED') toast.value = `Agent 调查 #${event.runId} 已启动`
      if (event.eventType === 'RUN_COMPLETED') toast.value = `Agent 调查 #${event.runId} 已完成`
      if (event.eventType === 'RUN_CANCELLED') toast.value = `Agent 调查 #${event.runId} 已取消`
      if (event.eventType === 'RUN_TIMED_OUT') toast.value = `Agent 调查 #${event.runId} 已超时终止`
      if (event.eventType === 'RUN_REJECTED') toast.value = 'Agent 执行队列已饱和，请稍后重试'
      if (['RUN_COMPLETED', 'RUN_FAILED', 'RUN_CANCELLED', 'RUN_TIMED_OUT', 'RUN_REJECTED']
        .includes(event.eventType)) activeAgentRunId.value = null
    }, agentAbortController.signal)
  } catch (caught) {
    if (!(caught instanceof DOMException && caught.name === 'AbortError')) {
      streamError = caught instanceof Error ? caught.message : 'Agent 调查失败'
    }
  } finally {
    actionLoading.value = false
    agentStreaming.value = false
    agentAbortController = null
    if (selected.value?.incident.id === incidentId) {
      try {
        await selectIncident(incidentId)
        const persistedRun = streamedRunId
          ? selected.value?.agentRuns.find(run => run.id === streamedRunId)
          : null
        if (persistedRun && ['COMPLETED', 'PARTIAL', 'FAILED', 'CANCELLED', 'TIMED_OUT', 'QUEUE_REJECTED']
          .includes(persistedRun.status)) clearAgentInvestigationIdempotency(incidentId)
        if (persistedRun?.status === 'COMPLETED') toast.value = `Agent 调查 #${persistedRun.id} 已完成`
        else if (persistedRun?.status === 'PARTIAL') toast.value = `Agent 调查 #${persistedRun.id} 部分完成`
        else if (persistedRun?.status === 'FAILED') toast.value = `Agent 调查 #${persistedRun.id} 失败`
        else if (persistedRun?.status === 'CANCELLED') toast.value = `Agent 调查 #${persistedRun.id} 已取消`
        else if (persistedRun?.status === 'TIMED_OUT') toast.value = `Agent 调查 #${persistedRun.id} 已超时终止`
        else if (persistedRun?.status === 'QUEUE_REJECTED') toast.value = 'Agent 执行队列已饱和，请稍后重试'
        else if (streamError) toast.value = streamError
      } catch (caught) {
        if (streamError) toast.value = streamError
        else toast.value = caught instanceof Error ? caught.message : 'Agent 调查状态刷新失败'
      }
    } else if (streamError) {
      toast.value = streamError
    }
  }
}

async function cancelAgentInvestigation() {
  if (!activeAgentRunId.value || agentControlLoading.value) return
  const runId = activeAgentRunId.value
  agentControlLoading.value = true
  try {
    await api<AgentRun>(`/agent-runs/${runId}/cancel`, {
      method: 'POST', body: JSON.stringify({ reason: 'Incident 工作台显式取消' }),
    })
    toast.value = `已请求取消 Agent 调查 #${runId}`
  } catch (caught) {
    toast.value = caught instanceof Error ? caught.message : '取消调查失败'
  } finally { agentControlLoading.value = false }
}

function statusLabel(status: string) {
  return ({ QUEUED: '排队中', COMPLETED: '已完成', PARTIAL: '部分完成', RUNNING: '运行中',
    FAILED: '失败', CANCELLED: '已取消', TIMED_OUT: '已超时', QUEUE_REJECTED: '队列已满',
    SUCCEEDED: '成功', NO_DATA: '无数据' } as Record<string, string>)[status] ?? status
}

function evidenceCount(step: AgentStep) {
  try {
    const parsed = JSON.parse(step.evidenceJson)
    return Array.isArray(parsed) ? parsed.length : 0
  } catch { return 0 }
}

function eventSummary(event: AgentRunEvent) {
  try {
    const payload = JSON.parse(event.payloadJson) as Record<string, unknown>
    return String(payload.title ?? payload.summary ?? payload.error ?? event.eventType)
  } catch { return event.eventType }
}

function eventLabel(event: AgentRunEvent) {
  return ({
    RUN_QUEUED: '进入队列', RUN_STARTED: '开始运行', PLAN_COMPLETED: '计划完成', STEP_STARTED: '调用工具',
    STEP_COMPLETED: '步骤完成', EVIDENCE_COLLECTED: '取得证据', STEP_FAILED: '工具失败',
    STEP_CANCELLED: '步骤取消', STEP_TIMED_OUT: '步骤超时',
    REPLAN_COMPLETED: '重新规划', ACTION_PROPOSED: '生成提案',
    RUN_CANCEL_REQUESTED: '请求取消', RUN_TIMEOUT_REQUESTED: '触发超时',
    RUN_COMPLETED: '调查完成', RUN_FAILED: '调查失败', RUN_CANCELLED: '调查取消',
    RUN_TIMED_OUT: '调查超时', RUN_REJECTED: '队列拒绝',
  } as Record<string, string>)[event.eventType] ?? event.eventType
}

function canReview(proposal: RemediationProposal) {
  const role = auth.state.user?.roleCode
  return proposal.status === 'PENDING_APPROVAL'
    && (role === 'ADMIN' || role === 'OPS_MANAGER')
    && proposal.requestedById !== auth.state.user?.id
}

async function reviewProposal(proposal: RemediationProposal, decision: 'APPROVE' | 'REJECT') {
  const comment = (reviewComments.value[proposal.id] ?? '').trim()
  if (comment.length < 3) {
    toast.value = '请填写至少 3 个字的审批意见'
    return
  }
  reviewLoadingId.value = proposal.id
  try {
    await api(`/remediation-proposals/${proposal.id}/reviews`, {
      method: 'POST', body: JSON.stringify({ decision, version: proposal.version, comment }),
    })
    toast.value = decision === 'APPROVE' ? '处置提案已批准' : '处置提案已拒绝'
    await selectIncident(proposal.incidentId)
  } catch (caught) {
    toast.value = caught instanceof Error ? caught.message : '审批失败'
    await selectIncident(proposal.incidentId)
  } finally { reviewLoadingId.value = null }
}

async function addNote() {
  if (!selected.value || !note.value.trim()) return
  actionLoading.value = true
  try {
    await api(`/incidents/${selected.value.incident.id}/notes`, {
      method: 'POST', body: JSON.stringify({ content: note.value.trim() }),
    })
    note.value = ''
    await selectIncident(selected.value.incident.id)
  } finally { actionLoading.value = false }
}

watch([statusFilter, severityFilter], () => void loadList())
onMounted(() => loadList())
onBeforeUnmount(() => agentAbortController?.abort())
</script>

<template>
  <div class="page-content incident-workspace">
    <div class="page-toolbar">
      <div><strong>Incident 队列</strong><span>{{ incidents.length }} 条记录</span></div>
      <div class="toolbar-group">
        <select v-model="severityFilter" aria-label="严重等级"><option value="">全部等级</option><option v-for="level in ['P1','P2','P3','P4']" :key="level">{{ level }}</option></select>
        <select v-model="statusFilter" aria-label="Incident 状态"><option value="">全部状态</option><option v-for="item in ['OPEN','ACKNOWLEDGED','INVESTIGATING','MITIGATED','RESOLVED','CLOSED']" :key="item">{{ item }}</option></select>
        <button class="icon-button bordered" title="刷新" @click="loadList(selected?.incident.id)"><RefreshCw :size="16" :class="{ spin: loading }" /></button>
      </div>
    </div>

    <div class="incident-split">
      <section class="incident-queue">
        <button v-for="item in incidents" :key="item.id" :class="{ active: selected?.incident.id === item.id }" @click="selectIncident(item.id)">
          <StatusBadge :value="item.severity" />
          <div><span>{{ item.incidentCode }}</span><strong>{{ item.title }}</strong><small>{{ item.resourceName }} · {{ item.alertCount }} 条告警 · {{ formatTime(item.updatedAt) }}</small></div>
          <StatusBadge :value="item.status" /><ChevronRight :size="16" />
        </button>
        <div v-if="!incidents.length && !loading" class="empty-state"><Check :size="24" /><strong>当前筛选下没有 Incident</strong></div>
      </section>

      <section v-if="selected" class="incident-detail">
        <header class="incident-detail-head">
          <div><div class="eyebrow"><StatusBadge :value="selected.incident.severity" /><span>{{ selected.incident.incidentCode }}</span><StatusBadge :value="selected.incident.status" /></div><h2>{{ selected.incident.title }}</h2><p>{{ selected.description }}</p></div>
          <div class="incident-actions">
            <RouterLink :to="`/assistant?incident=${selected.incident.id}`" class="secondary-button"><MessageSquareText :size="16" />继续对话</RouterLink>
            <button class="secondary-button" :disabled="agentControlLoading || (actionLoading && !activeAgentRunId)" @click="activeAgentRunId ? cancelAgentInvestigation() : investigate()"><CircleStop v-if="activeAgentRunId" :size="16" /><Workflow v-else :size="16" />{{ activeAgentRunId ? '取消 Agent 调查' : (agentStreaming ? '调查连接中' : '运行 Agent 调查') }}</button>
            <button v-for="action in availableTransitions" :key="action.value" class="primary-button" :disabled="actionLoading" @click="transition(action.value, action.label)">{{ action.label }}</button>
          </div>
        </header>

        <div class="incident-facts">
          <div><span>影响服务</span><strong>{{ selected.incident.resourceName }}</strong></div>
          <div><span>处置人</span><strong><UserRound :size="15" />{{ selected.incident.assignee ?? '待分派' }}</strong></div>
          <div><span>指挥人</span><strong>{{ selected.incident.commander ?? '待指定' }}</strong></div>
          <div><span>创建时间</span><strong>{{ formatTime(selected.incident.createdAt, true) }}</strong></div>
        </div>

        <div class="incident-columns">
          <div class="incident-main-column">
            <div class="section-heading"><h3>关联告警</h3><span>{{ selected.alerts.length }}</span></div>
            <div class="alert-stack">
              <article v-for="alert in selected.alerts" :key="alert.id"><StatusBadge :value="alert.severity" /><div><strong>{{ alert.title }}</strong><span>{{ alert.source }} · 最近 {{ formatTime(alert.lastOccurredAt) }}</span></div><em>× {{ alert.occurrenceCount }}</em></article>
            </div>

            <div class="section-heading"><h3>Agent 调查轨迹</h3><span>{{ selected.agentRuns.length }}</span></div>
            <section v-if="liveAgentEvents.length" class="agent-live-panel" :class="{ complete: !activeAgentRunId }">
              <header><span><Radio :size="14" />LIVE RUN #{{ liveAgentEvents[0].runId }}</span><em>{{ activeAgentRunId ? (agentStreaming ? 'STREAMING' : 'RUNNING') : 'FINISHED' }}</em></header>
              <ol><li v-for="event in liveAgentEvents.slice(-8)" :key="event.id" :class="event.status?.toLowerCase()"><i /><span>{{ eventLabel(event) }}</span><strong>{{ eventSummary(event) }}</strong><small>#{{ event.sequence }}</small></li></ol>
            </section>
            <div class="agent-run-list">
              <article v-for="run in selected.agentRuns" :key="run.id" class="agent-run-card" :class="run.status.toLowerCase()">
                <button class="agent-run-head" @click="expandedRunId = expandedRunId === run.id ? null : run.id">
                  <span class="agent-run-icon"><Workflow :size="16" /></span>
                  <div><strong>调查运行 #{{ run.id }}</strong><small>{{ run.triggerSource }} · {{ run.createdBy ?? '系统' }} · {{ formatTime(run.startedAt, true) }}</small></div>
                  <em :class="run.status.toLowerCase()">{{ statusLabel(run.status) }}</em>
                  <ChevronDown :size="15" :class="{ rotated: expandedRunId === run.id }" />
                </button>
                <div v-if="expandedRunId === run.id" class="agent-run-body">
                  <p>{{ run.planSummary }}</p>
                  <ol class="agent-step-list">
                    <li v-for="step in run.steps" :key="step.id" :class="step.status.toLowerCase()">
                      <span class="agent-step-marker"><CircleCheck v-if="step.status === 'SUCCEEDED'" :size="14" /><Database v-else :size="14" /></span>
                      <div><header><strong>{{ step.phase }} · {{ step.title }}</strong><em>{{ statusLabel(step.status) }}</em></header><p>{{ step.outputSummary }}</p><footer><span v-if="step.toolName">{{ step.toolName }}</span><span><Database :size="12" />{{ evidenceCount(step) }} 条证据</span><span><Clock3 :size="12" />{{ step.durationMs }} ms</span></footer><small v-if="step.errorMessage">{{ step.errorMessage }}</small></div>
                    </li>
                  </ol>
                  <footer class="agent-run-result"><span>{{ run.steps.length }} 个步骤 · {{ run.durationMs ?? 0 }} ms</span><strong>{{ run.terminationReason ?? run.conclusion }}</strong></footer>
                </div>
              </article>
            </div>
            <button v-if="!selected.agentRuns.length" class="empty-action" @click="investigate"><Workflow :size="20" />运行第一次可追踪 Agent 调查</button>

            <div class="section-heading"><h3>调查报告</h3><span>{{ selected.investigations.length }}</span></div>
            <article v-for="report in selected.investigations" :key="report.id" class="investigation-report">
              <header><div><Bot :size="17" /><strong>{{ report.engine }}</strong></div><span>置信度 {{ Math.round(Number(report.confidence) * 100) }}%</span></header>
              <p>{{ report.summary }}</p><h4>推测根因</h4><p>{{ report.hypothesis }}</p><h4>建议动作</h4><p class="pre-line">{{ report.suggestions }}</p>
              <footer><FileText :size="14" />{{ formatTime(report.createdAt, true) }}</footer>
            </article>
            <button v-if="!selected.investigations.length && selected.agentRuns.length" class="empty-action" @click="investigate"><Bot :size="20" />重新生成调查报告</button>

            <div class="section-heading"><h3>受控处置提案</h3><span>{{ selected.remediationProposals.length }}</span></div>
            <div class="remediation-list">
              <article v-for="proposal in selected.remediationProposals" :key="proposal.id" class="remediation-card" :class="proposal.status.toLowerCase()">
                <header><span><ShieldAlert :size="15" />{{ proposal.riskLevel }} RISK</span><em>{{ proposal.status }}</em></header>
                <h4>{{ proposal.title }}</h4><p>{{ proposal.description }}</p>
                <dl><div><dt>目标资源</dt><dd>{{ proposal.resourceName }} · {{ proposal.resourceCode }}</dd></div><div><dt>发起人</dt><dd>{{ proposal.requestedByName }} · RUN #{{ proposal.runId }}</dd></div><div><dt>证据</dt><dd>{{ proposal.evidenceRef }}</dd></div></dl>
                <div v-if="canReview(proposal)" class="remediation-review"><input v-model="reviewComments[proposal.id]" maxlength="500" placeholder="填写审批依据" /><button class="approve" :disabled="reviewLoadingId === proposal.id" title="批准提案" @click="reviewProposal(proposal, 'APPROVE')"><CheckCircle2 :size="14" />批准</button><button class="reject" :disabled="reviewLoadingId === proposal.id" title="拒绝提案" @click="reviewProposal(proposal, 'REJECT')"><XCircle :size="14" />拒绝</button></div>
                <footer v-else-if="proposal.reviewedByName"><span>{{ proposal.reviewedByName }} · {{ formatTime(proposal.reviewedAt, true) }}</span><strong>{{ proposal.reviewComment }}</strong></footer>
                <footer v-else><span>{{ proposal.requestedById === auth.state.user?.id ? '等待其他审批人复核' : '等待管理员或运维经理审批' }}</span></footer>
              </article>
              <div v-if="!selected.remediationProposals.length" class="remediation-empty"><ShieldAlert :size="18" />当前没有待治理动作</div>
            </div>
          </div>

          <aside class="timeline-column">
            <div class="section-heading"><h3>处置时间线</h3></div>
            <form class="note-form" @submit.prevent="addNote"><MessageSquarePlus :size="16" /><input v-model="note" placeholder="记录处置进展" /><button class="icon-button" title="提交记录" :disabled="actionLoading || !note.trim()"><Send :size="15" /></button></form>
            <ol class="timeline-list">
              <li v-for="event in selected.timeline" :key="event.id"><i /><div><span>{{ event.actor ?? '系统' }} · {{ formatTime(event.createdAt, true) }}</span><strong>{{ event.content }}</strong><small v-if="event.evidenceRef">{{ event.evidenceRef }}</small></div></li>
            </ol>
          </aside>
        </div>
      </section>
      <div v-else class="workspace-loading"><LoaderCircle :size="24" class="spin" /></div>
    </div>
    <div v-if="toast" class="toast" @click="toast = ''">{{ toast }}</div>
  </div>
</template>

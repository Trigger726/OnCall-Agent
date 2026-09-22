<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  AlertTriangle, BarChart3, CheckCircle2, Clock3, RefreshCw,
  RotateCw, ShieldAlert, TimerReset, UsersRound, X,
} from 'lucide-vue-next'
import StatusBadge from '@/components/StatusBadge.vue'
import { api, formatTime, type PageResponse } from '@/services/api'
import { auth } from '@/stores/auth'

interface DurationMetric {
  sampleCount: number
  averageMinutes: number | null
  medianMinutes: number | null
}

interface AnalyticsOverview {
  window: { from: string; to: string; severity: string | null }
  incidentCount: number
  mtta: DurationMetric
  mttm: DurationMetric
  mttr: DurationMetric
  severityDistribution: { severity: string; count: number }[]
  slowestResolved: {
    id: number
    incidentCode: string
    title: string
    severity: string
    resourceName: string
    createdAt: string
    resolvedAt: string
    resolutionMinutes: number
  }[]
  services: {
    serviceResourceId: number
    serviceCode: string
    serviceName: string
    incidentCount: number
    openCount: number
    mtta: DurationMetric
    mttm: DurationMetric
    mttr: DurationMetric
  }[]
  followUps: {
    total: number
    open: number
    done: number
    overdue: number
    completionRatePercent: number
    asOf: string
  }
}

interface FollowUp {
  id: number
  incidentId: number
  incidentCode: string
  incidentTitle: string
  severity: string
  title: string
  description: string
  priority: string
  status: string
  ownerId: number
  ownerName: string
  dueDate: string
  overdue: boolean
  daysOverdue: number
  escalationStatus: string | null
  firstDetectedAt: string | null
  completedAt: string | null
  version: number
}

interface SloObjective {
  id: number
  serviceCode: string
  serviceName: string
  name: string
  targetPercent: number
  windowDays: number
  goodEventsQueryTemplate: string
  totalEventsQueryTemplate: string
  version: number
  measurement: {
    status: string
    goodEvents: number | null
    totalEvents: number | null
    badEvents: number | null
    sliPercent: number | null
    errorBudgetEvents: number | null
    remainingEvents: number | null
    consumedPercent: number | null
    evaluatedAt: string
    externalRef: string | null
    message: string | null
  }
  burnRate: {
    status: string
    severity: string
    message: string
    lanes: Array<{
      id: string
      severity: string
      longWindow: string
      shortWindow: string
      threshold: number
      budgetConsumedPercent: number
      status: string
      longBurnRate: number | null
      shortBurnRate: number | null
      longTotalEvents: number | null
      shortTotalEvents: number | null
      message: string | null
    }>
  }
}

interface SloOverview {
  evaluatedAt: string
  prometheusEnabled: boolean
  objectives: SloObjective[]
}

function localDate(date: Date): string {
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

const today = new Date()
const start = new Date(today)
start.setDate(start.getDate() - 29)

const from = ref(localDate(start))
const to = ref(localDate(today))
const severity = ref('')
const scope = ref('ALL')
const followUpStatus = ref('')
const overdueOnly = ref(false)
const overview = ref<AnalyticsOverview | null>(null)
const followUps = ref<FollowUp[]>([])
const sloOverview = ref<SloOverview | null>(null)
const followUpTotal = ref(0)
const loading = ref(false)
const scanning = ref(false)
const completingId = ref<number | null>(null)
const savingSloId = ref<number | null>(null)
const editingSlo = ref<SloObjective | null>(null)
const sloTargetInput = ref(0)
const sloWindowInput = ref(30)
const error = ref('')
const notice = ref('')

const canScan = computed(() => ['ADMIN', 'OPS_MANAGER'].includes(auth.state.user?.roleCode ?? ''))
const canManageSlo = computed(() => ['ADMIN', 'OPS_MANAGER'].includes(auth.state.user?.roleCode ?? ''))
const maxSeverityCount = computed(() => Math.max(1, ...(overview.value?.severityDistribution.map(item => item.count) ?? [1])))

function query(path: string, params: Record<string, string>): string {
  const search = new URLSearchParams(params)
  return `${path}?${search.toString()}`
}

async function load() {
  loading.value = true
  error.value = ''
  notice.value = ''
  try {
    const analyticsPath = query('/analytics/incidents', {
      from: from.value,
      to: to.value,
      ...(severity.value ? { severity: severity.value } : {}),
    })
    const followUpPath = query('/postmortem-follow-ups', {
      scope: scope.value,
      ...(followUpStatus.value ? { status: followUpStatus.value } : {}),
      overdue: String(overdueOnly.value),
      size: '50',
    })
    const [analytics, actionPage, slos] = await Promise.all([
      api<AnalyticsOverview>(analyticsPath),
      api<PageResponse<FollowUp>>(followUpPath),
      api<SloOverview>('/slo/objectives'),
    ])
    overview.value = analytics
    followUps.value = actionPage.items
    followUpTotal.value = actionPage.total
    sloOverview.value = slos
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : '运营数据加载失败'
  } finally {
    loading.value = false
  }
}

function openSloEdit(item: SloObjective) {
  editingSlo.value = item
  sloTargetInput.value = item.targetPercent
  sloWindowInput.value = item.windowDays
}

async function saveSlo() {
  const item = editingSlo.value
  if (!item) return
  const targetPercent = Number(sloTargetInput.value)
  const windowDays = Number(sloWindowInput.value)
  if (!Number.isFinite(targetPercent) || targetPercent <= 0 || targetPercent >= 100
      || !Number.isInteger(windowDays) || windowDays < 1 || windowDays > 90) {
    error.value = 'SLO 目标或滚动窗口格式不合法'
    return
  }
  savingSloId.value = item.id
  error.value = ''
  notice.value = ''
  try {
    await api(`/slo/objectives/${item.id}`, {
      method: 'PATCH',
      body: JSON.stringify({
        expectedVersion: item.version,
        name: item.name,
        targetPercent,
        windowDays,
        goodEventsQueryTemplate: item.goodEventsQueryTemplate,
        totalEventsQueryTemplate: item.totalEventsQueryTemplate,
      }),
    })
    await load()
    editingSlo.value = null
    notice.value = `${item.serviceName} 的 SLO 已更新，并使用新窗口重新评估。`
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : 'SLO 更新失败'
  } finally {
    savingSloId.value = null
  }
}

function sloStatus(value: string): string {
  return ({
    MET: '达标', BREACHED: '超预算', NO_DATA: '无数据', INVALID_DATA: '数据异常',
    PROVIDER_DISABLED: '未启用', PROVIDER_ERROR: '查询失败',
  } as Record<string, string>)[value] ?? value
}

function sloClass(value: string): string {
  return value === 'MET' ? 'status-success'
    : value === 'BREACHED' || value === 'INVALID_DATA' ? 'status-danger'
      : value === 'NO_DATA' || value === 'PROVIDER_DISABLED' ? 'status-neutral' : 'status-warning'
}

function burnStatus(value: string): string {
  return ({
    PAGE_FAST: '急速燃烧', PAGE_SLOW: '持续燃烧', TICKET: '工单关注', HEALTHY: '稳定',
    NO_DATA: '分母不足', INVALID_DATA: '数据异常', PROVIDER_DISABLED: '未启用',
    PROVIDER_ERROR: '查询失败',
  } as Record<string, string>)[value] ?? value
}

function burnClass(value: string): string {
  return value === 'HEALTHY' ? 'status-success'
    : value === 'PAGE_FAST' ? 'status-danger'
      : value === 'PAGE_SLOW' || value === 'TICKET' ? 'status-warning' : 'status-neutral'
}

function burnLane(value: string): string {
  return ({ FAST_PAGE: '急速 PAGE', SLOW_PAGE: '持续 PAGE', TICKET: '工单' } as Record<string, string>)[value] ?? value
}

function number(value: number | null, suffix = ''): string {
  return value == null ? '-' : `${value}${suffix}`
}

async function runEscalations() {
  scanning.value = true
  error.value = ''
  notice.value = ''
  try {
    const result = await api<{ createdEscalations: number; existingEscalations: number }>(
      '/postmortem-follow-ups/escalations/run', { method: 'POST' },
    )
    notice.value = `扫描完成：新增 ${result.createdEscalations} 条，已有 ${result.existingEscalations} 条逾期事实。`
    await load()
    notice.value = `扫描完成：新增 ${result.createdEscalations} 条，已有 ${result.existingEscalations} 条逾期事实。`
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : '逾期扫描失败'
  } finally {
    scanning.value = false
  }
}

function canComplete(item: FollowUp): boolean {
  const role = auth.state.user?.roleCode ?? ''
  return item.status === 'OPEN' && (item.ownerId === auth.state.user?.id || ['ADMIN', 'OPS_MANAGER'].includes(role))
}

async function complete(item: FollowUp) {
  if (!window.confirm(`确认将“${item.title}”标记为完成？`)) return
  completingId.value = item.id
  error.value = ''
  notice.value = ''
  try {
    await api(`/postmortem-follow-ups/${item.id}/complete`, {
      method: 'POST',
      body: JSON.stringify({ expectedVersion: item.version }),
    })
    await load()
    notice.value = '行动项已完成；如存在开放逾期事实，已同步关闭。'
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : '行动项完成失败'
  } finally {
    completingId.value = null
  }
}

function metric(value: number | null): string {
  return value == null ? '-' : String(value)
}

function priorityLabel(value: string): string {
  return ({ HIGH: '高', MEDIUM: '中', LOW: '低' } as Record<string, string>)[value] ?? value
}

function priorityClass(value: string): string {
  return value === 'HIGH' ? 'status-danger' : value === 'MEDIUM' ? 'status-warning' : 'status-neutral'
}

onMounted(load)
</script>

<template>
  <div class="page-content analytics-page">
    <div class="page-toolbar analytics-toolbar">
      <div><strong>事故运营分析</strong><span>明确分母的响应指标与防复发行动项闭环</span></div>
      <div class="toolbar-group analytics-filters">
        <label>开始<input v-model="from" type="date" :max="to" /></label>
        <label>结束<input v-model="to" type="date" :min="from" /></label>
        <select v-model="severity" aria-label="严重等级">
          <option value="">全部等级</option>
          <option v-for="item in ['P1', 'P2', 'P3', 'P4']" :key="item">{{ item }}</option>
        </select>
        <button class="secondary-button" :disabled="loading" @click="load">
          <RefreshCw :size="15" :class="{ spin: loading }" />查询
        </button>
      </div>
    </div>
    <div v-if="error" class="inline-error"><AlertTriangle :size="16" />{{ error }}</div>
    <div v-if="notice" class="success-banner"><CheckCircle2 :size="16" />{{ notice }}</div>

    <section class="metric-strip analytics-metric-strip" aria-label="事故响应指标">
      <article>
        <span class="metric-icon neutral"><BarChart3 :size="18" /></span>
        <div><small>窗口内 Incident</small><strong>{{ overview?.incidentCount ?? '-' }}</strong><em>按创建时间纳入</em></div>
      </article>
      <article>
        <span class="metric-icon info"><Clock3 :size="18" /></span>
        <div><small>MTTA 平均 / 中位</small><strong>{{ metric(overview?.mtta.averageMinutes ?? null) }}<i>min</i></strong><em>{{ metric(overview?.mtta.medianMinutes ?? null) }} min · n={{ overview?.mtta.sampleCount ?? 0 }}</em></div>
      </article>
      <article>
        <span class="metric-icon warning"><TimerReset :size="18" /></span>
        <div><small>MTTM 平均 / 中位</small><strong>{{ metric(overview?.mttm.averageMinutes ?? null) }}<i>min</i></strong><em>{{ metric(overview?.mttm.medianMinutes ?? null) }} min · n={{ overview?.mttm.sampleCount ?? 0 }}</em></div>
      </article>
      <article>
        <span class="metric-icon danger"><RotateCw :size="18" /></span>
        <div><small>MTTR 平均 / 中位</small><strong>{{ metric(overview?.mttr.averageMinutes ?? null) }}<i>min</i></strong><em>{{ metric(overview?.mttr.medianMinutes ?? null) }} min · n={{ overview?.mttr.sampleCount ?? 0 }}</em></div>
      </article>
    </section>

    <section class="analytics-grid">
      <div class="content-panel analytics-slowest-panel">
        <div class="panel-heading">
          <div><h2>恢复最慢的 Incident</h2><span>仅含具备有效恢复里程碑的样本</span></div>
        </div>
        <div class="table-scroll">
          <table class="data-table">
            <thead><tr><th>等级</th><th>编号 / 主题</th><th>影响服务</th><th>MTTR</th><th>恢复时间</th></tr></thead>
            <tbody>
              <tr v-for="item in overview?.slowestResolved" :key="item.id">
                <td><StatusBadge :value="item.severity" /></td>
                <td><RouterLink :to="`/incidents?selected=${item.id}`" class="primary-cell"><span>{{ item.incidentCode }}</span><strong>{{ item.title }}</strong></RouterLink></td>
                <td>{{ item.resourceName }}</td>
                <td><strong>{{ item.resolutionMinutes }} min</strong></td>
                <td>{{ formatTime(item.resolvedAt, true) }}</td>
              </tr>
            </tbody>
          </table>
          <div v-if="!overview?.slowestResolved.length" class="analytics-empty">当前窗口没有可计算 MTTR 的 Incident。</div>
        </div>
      </div>

      <aside class="content-panel analytics-severity-panel">
        <div class="panel-heading"><div><h2>严重等级分布</h2><span>窗口内 Incident 数量</span></div></div>
        <div class="analytics-severity-list">
          <div v-for="item in overview?.severityDistribution" :key="item.severity">
            <StatusBadge :value="item.severity" />
            <span><i :style="{ width: `${Math.max(8, item.count / maxSeverityCount * 100)}%` }" /></span>
            <strong>{{ item.count }}</strong>
          </div>
          <p v-if="!overview?.severityDistribution.length">当前窗口没有 Incident。</p>
        </div>
      </aside>
    </section>

    <section class="content-panel analytics-service-panel">
      <div class="panel-heading"><div><h2>按服务拆分</h2><span>与上方同一创建时间窗口及等级筛选；平均分钟数仅使用有效里程碑</span></div></div>
      <div class="table-scroll">
        <table class="data-table">
          <thead><tr><th>服务</th><th>Incident</th><th>未关闭</th><th>MTTA 平均 / 样本</th><th>MTTM 平均 / 样本</th><th>MTTR 平均 / 样本</th></tr></thead>
          <tbody>
            <tr v-for="item in overview?.services" :key="item.serviceResourceId">
              <td class="primary-cell"><strong>{{ item.serviceName }}</strong><span>{{ item.serviceCode }}</span></td>
              <td>{{ item.incidentCount }}</td>
              <td>{{ item.openCount }}</td>
              <td>{{ metric(item.mtta.averageMinutes) }} min · n={{ item.mtta.sampleCount }}</td>
              <td>{{ metric(item.mttm.averageMinutes) }} min · n={{ item.mttm.sampleCount }}</td>
              <td>{{ metric(item.mttr.averageMinutes) }} min · n={{ item.mttr.sampleCount }}</td>
            </tr>
          </tbody>
        </table>
        <div v-if="!overview?.services.length" class="analytics-empty">当前窗口没有可归属的 Incident。</div>
      </div>
      <footer class="follow-up-boundary">这是事故响应里程碑统计，不是服务可用性 SLO；没有完整请求或时间分母时不计算错误预算。</footer>
    </section>

    <section class="content-panel analytics-slo-panel">
      <div class="panel-heading">
        <div><h2>服务 SLO 与错误预算</h2><span>Prometheus 好事件 / 总事件 · 明确滚动窗口 · 不使用 Incident 指标代算</span></div>
        <span class="status-badge" :class="sloOverview?.prometheusEnabled ? 'status-success' : 'status-neutral'">
          Prometheus {{ sloOverview?.prometheusEnabled ? '已启用' : '未启用' }}
        </span>
      </div>
      <div class="table-scroll">
        <table class="data-table">
          <thead><tr><th>服务 / SLI</th><th>目标与窗口</th><th>当前 SLI</th><th>好事件 / 总事件</th><th>错误预算剩余</th><th>多窗口燃烧率</th><th>状态</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="item in sloOverview?.objectives" :key="item.id">
              <td class="primary-cell"><strong>{{ item.serviceName }}</strong><span>{{ item.serviceCode }} · {{ item.name }}</span></td>
              <td><strong>{{ item.targetPercent }}%</strong><br /><span class="muted">{{ item.windowDays }} 天滚动</span></td>
              <td><strong>{{ number(item.measurement.sliPercent, '%') }}</strong></td>
              <td>{{ number(item.measurement.goodEvents) }} / {{ number(item.measurement.totalEvents) }}</td>
              <td><strong>{{ number(item.measurement.remainingEvents) }}</strong><br /><span class="muted">已消耗 {{ number(item.measurement.consumedPercent, '%') }}</span></td>
              <td class="burn-rate-cell">
                <span class="status-badge" :class="burnClass(item.burnRate.status)">{{ burnStatus(item.burnRate.status) }}</span>
                <div v-if="item.burnRate.lanes.length" class="burn-rate-list">
                  <span v-for="lane in item.burnRate.lanes" :key="lane.id" :class="{ firing: lane.status === 'FIRING' }">
                    {{ burnLane(lane.id) }} {{ lane.longWindow }}/{{ lane.shortWindow }}：{{ number(lane.longBurnRate, 'x') }} / {{ number(lane.shortBurnRate, 'x') }}
                  </span>
                </div>
                <small v-else class="slo-message">{{ item.burnRate.message }}</small>
              </td>
              <td><span class="status-badge" :class="sloClass(item.measurement.status)">{{ sloStatus(item.measurement.status) }}</span><small v-if="item.measurement.message" class="slo-message">{{ item.measurement.message }}</small></td>
              <td><button v-if="canManageSlo" class="table-action" :disabled="savingSloId === item.id" @click="openSloEdit(item)">{{ savingSloId === item.id ? '保存中' : '调整目标' }}</button><span v-else class="muted">只读</span></td>
            </tr>
          </tbody>
        </table>
        <div v-if="!sloOverview?.objectives.length" class="analytics-empty">尚未配置服务 SLO。</div>
      </div>
      <footer class="follow-up-boundary">燃烧率按长/短窗口同时超阈值判定：1h/5m · 14.4x，6h/30m · 6x，3d/6h · 1x。低流量服务需另行制定样本政策，不在此自动压制告警。</footer>
    </section>

    <section class="content-panel follow-up-operations">
      <div class="panel-heading follow-up-heading">
        <div><h2>防复发行动项</h2><span>全局责任、期限与应用内逾期事实</span></div>
        <div class="follow-up-summary" aria-label="行动项摘要">
          <span>总数 <strong>{{ overview?.followUps.total ?? 0 }}</strong></span>
          <span>开放 <strong>{{ overview?.followUps.open ?? 0 }}</strong></span>
          <span class="danger">逾期 <strong>{{ overview?.followUps.overdue ?? 0 }}</strong></span>
          <span>完成率 <strong>{{ overview?.followUps.completionRatePercent ?? 0 }}%</strong></span>
        </div>
      </div>
      <div class="follow-up-controls">
        <div>
          <select v-model="scope" aria-label="行动项范围" @change="load"><option value="ALL">全部行动项</option><option value="MINE">只看我的</option></select>
          <select v-model="followUpStatus" aria-label="行动项状态" @change="load"><option value="">全部状态</option><option value="OPEN">开放</option><option value="DONE">已完成</option></select>
          <label><input v-model="overdueOnly" type="checkbox" @change="load" />只看逾期</label>
          <span>当前结果 {{ followUpTotal }} 项</span>
        </div>
        <button v-if="canScan" class="secondary-button" :disabled="scanning" @click="runEscalations">
          <ShieldAlert :size="15" />{{ scanning ? '扫描中' : '扫描逾期' }}
        </button>
      </div>
      <div class="table-scroll">
        <table class="data-table follow-up-table">
          <thead><tr><th>优先级</th><th>行动项 / Incident</th><th>负责人</th><th>截止日期</th><th>升级事实</th><th>状态</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="item in followUps" :key="item.id">
              <td><span class="status-badge" :class="priorityClass(item.priority)">{{ priorityLabel(item.priority) }}</span></td>
              <td><RouterLink :to="`/incidents?selected=${item.incidentId}`" class="primary-cell"><span>{{ item.incidentCode }} · {{ item.severity }}</span><strong>{{ item.title }}</strong></RouterLink></td>
              <td><span class="follow-up-owner"><UsersRound :size="14" />{{ item.ownerName }}</span></td>
              <td><div class="follow-up-due" :class="{ overdue: item.overdue }"><strong>{{ item.dueDate }}</strong><span v-if="item.overdue">逾期 {{ item.daysOverdue }} 天</span><span v-else>未逾期</span></div></td>
              <td><div class="follow-up-escalation"><span class="status-badge" :class="item.escalationStatus === 'OPEN' ? 'status-danger' : item.escalationStatus === 'RESOLVED' ? 'status-success' : 'status-neutral'">{{ item.escalationStatus === 'OPEN' ? '已升级' : item.escalationStatus === 'RESOLVED' ? '已关闭' : '未升级' }}</span><small v-if="item.firstDetectedAt">{{ formatTime(item.firstDetectedAt, true) }}</small></div></td>
              <td><span class="status-badge" :class="item.status === 'DONE' ? 'status-success' : 'status-info'">{{ item.status === 'DONE' ? '已完成' : '开放' }}</span></td>
              <td><button v-if="canComplete(item)" class="table-action" :disabled="completingId === item.id" @click="complete(item)">{{ completingId === item.id ? '提交中' : '完成' }}</button><span v-else class="muted">-</span></td>
            </tr>
          </tbody>
        </table>
        <div v-if="!followUps.length" class="analytics-empty">没有符合当前筛选条件的行动项。</div>
      </div>
      <footer class="follow-up-boundary"><ShieldAlert :size="14" />逾期扫描只形成 OpsPilot 内部升级事实与审计记录，不代表外部邮件或即时消息已经送达。</footer>
    </section>

    <div v-if="editingSlo" class="dialog-backdrop" @click.self="editingSlo = null">
      <form class="dialog-panel slo-edit-dialog" role="dialog" aria-modal="true" aria-labelledby="slo-edit-title" @submit.prevent="saveSlo">
        <header>
          <div><h2 id="slo-edit-title">调整服务 SLO</h2><span>{{ editingSlo.serviceName }} · {{ editingSlo.serviceCode }} · v{{ editingSlo.version }}</span></div>
          <button type="button" class="icon-button" title="关闭" @click="editingSlo = null"><X :size="18" /></button>
        </header>
        <div class="form-grid">
          <label><span>目标百分比</span><input v-model.number="sloTargetInput" type="number" min="0.001" max="99.999" step="0.001" required /></label>
          <label><span>滚动窗口（天）</span><input v-model.number="sloWindowInput" type="number" min="1" max="90" step="1" required /></label>
        </div>
        <p class="slo-edit-boundary">本操作只调整目标和窗口；好事件/总事件 PromQL 保持不变，并由服务端检查窗口占位符、角色权限和版本冲突。</p>
        <footer><button type="button" class="secondary-button" @click="editingSlo = null">取消</button><button class="primary-button" :disabled="savingSloId === editingSlo.id">{{ savingSloId === editingSlo.id ? '保存中' : '保存并重新评估' }}</button></footer>
      </form>
    </div>
  </div>
</template>

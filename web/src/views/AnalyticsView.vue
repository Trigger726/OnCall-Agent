<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  AlertTriangle, BarChart3, CheckCircle2, Clock3, RefreshCw,
  RotateCw, ShieldAlert, TimerReset, UsersRound,
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
const followUpTotal = ref(0)
const loading = ref(false)
const scanning = ref(false)
const completingId = ref<number | null>(null)
const error = ref('')
const notice = ref('')

const canScan = computed(() => ['ADMIN', 'OPS_MANAGER'].includes(auth.state.user?.roleCode ?? ''))
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
    const [analytics, actionPage] = await Promise.all([
      api<AnalyticsOverview>(analyticsPath),
      api<PageResponse<FollowUp>>(followUpPath),
    ])
    overview.value = analytics
    followUps.value = actionPage.items
    followUpTotal.value = actionPage.total
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : '运营数据加载失败'
  } finally {
    loading.value = false
  }
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
  </div>
</template>

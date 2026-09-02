<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { AlertTriangle, ArrowRight, BellRing, Boxes, Clock3, RadioTower, RefreshCw, Users } from 'lucide-vue-next'
import StatusBadge from '@/components/StatusBadge.vue'
import { api, formatTime, type PageResponse } from '@/services/api'

interface Dashboard {
  firingAlerts: number
  activeIncidents: number
  p1Incidents: number
  degradedResources: number
  alertCompressionPercent: number
  mttaMinutes: number
  severityDistribution: { severity: string; count: number }[]
  incidentTrend: { day: string; count: number }[]
  riskResources: { id: number; name: string; type: string; status: string; activeIncidents: number }[]
}

interface Incident {
  id: number
  incidentCode: string
  title: string
  severity: string
  status: string
  resourceName: string
  assignee: string | null
  alertCount: number
  updatedAt: string
}

interface OnCall {
  scheduleId: number
  scheduleName: string
  resourceName: string
  userName: string | null
  department: string | null
  endsAt: string | null
}

const dashboard = ref<Dashboard | null>(null)
const incidents = ref<Incident[]>([])
const onCall = ref<OnCall[]>([])
const loading = ref(true)
const error = ref('')

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [overview, incidentPage, shifts] = await Promise.all([
      api<Dashboard>('/dashboard'),
      api<PageResponse<Incident>>('/incidents?size=6'),
      api<OnCall[]>('/on-call/current'),
    ])
    dashboard.value = overview
    incidents.value = incidentPage.items
    onCall.value = shifts
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : '加载失败'
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page-content dashboard-page">
    <div class="page-toolbar">
      <div><strong>生产运行态势</strong><span>告警、事故与资源健康的统一视图</span></div>
      <button class="secondary-button" :disabled="loading" @click="load"><RefreshCw :size="15" :class="{ spin: loading }" />刷新</button>
    </div>
    <div v-if="error" class="inline-error"><AlertTriangle :size="16" />{{ error }}</div>

    <section class="metric-strip" aria-label="核心指标">
      <article><span class="metric-icon danger"><RadioTower :size="18" /></span><div><small>活跃 Incident</small><strong>{{ dashboard?.activeIncidents ?? '-' }}</strong><em>{{ dashboard?.p1Incidents ?? '-' }} 个 P1</em></div></article>
      <article><span class="metric-icon warning"><BellRing :size="18" /></span><div><small>触发中告警</small><strong>{{ dashboard?.firingAlerts ?? '-' }}</strong><em>压缩 {{ dashboard?.alertCompressionPercent ?? '-' }}%</em></div></article>
      <article><span class="metric-icon info"><Clock3 :size="18" /></span><div><small>平均确认时间</small><strong>{{ dashboard?.mttaMinutes ?? '-' }}<i>min</i></strong><em>目标 ≤ 5 min</em></div></article>
      <article><span class="metric-icon neutral"><Boxes :size="18" /></span><div><small>异常资源</small><strong>{{ dashboard?.degradedResources ?? '-' }}</strong><em>CMDB 实时关联</em></div></article>
    </section>

    <section class="dashboard-grid">
      <div class="content-panel incident-panel">
        <div class="panel-heading"><div><h2>当前 Incident</h2><span>按严重等级与更新时间排序</span></div><RouterLink to="/incidents">全部 <ArrowRight :size="14" /></RouterLink></div>
        <div class="table-scroll">
          <table class="data-table">
            <thead><tr><th>等级</th><th>编号 / 主题</th><th>影响服务</th><th>状态</th><th>处置人</th><th>最近更新</th></tr></thead>
            <tbody>
              <tr v-for="item in incidents" :key="item.id">
                <td><StatusBadge :value="item.severity" /></td>
                <td><RouterLink :to="`/incidents?selected=${item.id}`" class="primary-cell"><span>{{ item.incidentCode }}</span><strong>{{ item.title }}</strong></RouterLink></td>
                <td>{{ item.resourceName }}</td><td><StatusBadge :value="item.status" /></td>
                <td>{{ item.assignee ?? '待分派' }}</td><td>{{ formatTime(item.updatedAt) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <aside class="content-panel oncall-panel">
        <div class="panel-heading"><div><h2>当前值班</h2><span>生产域责任人</span></div><Users :size="17" /></div>
        <div class="oncall-list">
          <div v-for="shift in onCall" :key="shift.scheduleId" class="oncall-item">
            <span class="user-avatar compact">{{ shift.userName?.slice(0, 1) ?? '?' }}</span>
            <div><strong>{{ shift.userName ?? '暂无排班' }}</strong><span>{{ shift.scheduleName }}</span><small>{{ shift.resourceName }}</small></div>
            <time>{{ formatTime(shift.endsAt) }} 交班</time>
          </div>
        </div>
        <RouterLink to="/on-call" class="panel-link">查看升级策略 <ArrowRight :size="14" /></RouterLink>
      </aside>

      <div class="content-panel trend-panel">
        <div class="panel-heading"><div><h2>Incident 趋势</h2><span>近 7 个有记录日期</span></div></div>
        <div class="bar-chart" aria-label="Incident 趋势图">
          <div v-for="point in dashboard?.incidentTrend" :key="point.day" class="bar-column">
            <span class="bar-value">{{ point.count }}</span>
            <div class="bar-track"><i :style="{ height: `${Math.max(12, Math.min(100, point.count * 34))}%` }" /></div>
            <small>{{ point.day.slice(5) }}</small>
          </div>
        </div>
      </div>

      <div class="content-panel risk-panel">
        <div class="panel-heading"><div><h2>资源风险</h2><span>异常状态或存在未闭环 Incident</span></div></div>
        <div class="risk-list">
          <RouterLink v-for="resource in dashboard?.riskResources" :key="resource.id" :to="`/cmdb?selected=${resource.id}`">
            <span class="resource-type">{{ resource.type.slice(0, 3) }}</span>
            <div><strong>{{ resource.name }}</strong><small>{{ resource.type }}</small></div>
            <StatusBadge :value="resource.status" />
            <em>{{ resource.activeIncidents }} Incident</em>
          </RouterLink>
        </div>
      </div>
    </section>
  </div>
</template>

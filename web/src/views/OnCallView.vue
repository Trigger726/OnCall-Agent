<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { AlarmClock, ArrowDown, Clock3, PhoneForwarded, Users } from 'lucide-vue-next'
import { api, formatTime } from '@/services/api'
import { auth } from '@/stores/auth'
import OnCallRosterPanel from '@/components/OnCallRosterPanel.vue'

interface Shift { scheduleId: number; scheduleName: string; resourceName: string; userName: string | null; department: string | null; startsAt: string | null; endsAt: string | null; override: boolean }
interface Policy { policyId: number; policyName: string; severity: string | null; resourceName: string; step: number; delayMinutes: number; targetType: string; targetRef: string }
interface Escalation { id: number; incidentId: number; incidentCode: string; severity: string; policyName: string; step: number; targetType: string; targetRef: string; dueAt: string; status: string; recipient: string | null; detail: string; executedAt: string }
interface ScanResult { candidates: number; routedSteps: number; noTargetSteps: number }
const shifts = ref<Shift[]>([])
const policies = ref<Policy[]>([])
const escalations = ref<Escalation[]>([])
const scanning = ref(false)
const message = ref('')
const error = ref('')
const canScan = computed(() => ['ADMIN', 'OPS_MANAGER'].includes(auth.state.user?.roleCode ?? ''))

async function load() {
  try {
    [shifts.value, policies.value, escalations.value] = await Promise.all([
      api<Shift[]>('/on-call/current'), api<Policy[]>('/on-call/policies'),
      api<Escalation[]>('/on-call/escalations'),
    ])
    error.value = ''
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '值班数据加载失败'
  }
}

async function scan() {
  scanning.value = true
  message.value = ''
  try {
    const result = await api<ScanResult>('/on-call/escalations/scan', { method: 'POST' })
    message.value = `扫描 ${result.candidates} 个待处理 Incident，站内路由 ${result.routedSteps} 步，无目标 ${result.noTargetSteps} 步`
    await load()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '升级扫描失败'
  } finally {
    scanning.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page-content">
    <div class="page-toolbar"><div><strong>当前值班与升级链</strong><span>未确认 Incident 按策略记录站内路由；不等于外部渠道送达</span></div></div>
    <p v-if="error" role="alert">{{ error }}</p>
    <section class="oncall-grid">
      <article v-for="shift in shifts" :key="shift.scheduleId" class="content-panel shift-card">
        <header><span class="metric-icon info"><AlarmClock :size="18" /></span><div><span>{{ shift.scheduleName }}</span><h2>{{ shift.resourceName }}</h2></div><em :class="{ inactive: !shift.userName }">{{ shift.userName ? '当前班次' : '无生效班次' }}</em></header>
        <div class="shift-owner"><span class="user-avatar large">{{ shift.userName?.slice(0, 1) ?? '?' }}</span><div><strong>{{ shift.userName ?? '暂无排班' }}</strong><span>{{ shift.department ?? '-' }}</span></div><Users :size="18" /></div>
        <footer><span><Clock3 :size="15" />{{ formatTime(shift.startsAt, true) }}</span><ArrowDown :size="14" /><span>{{ formatTime(shift.endsAt, true) }}</span></footer>
      </article>
    </section>
    <OnCallRosterPanel @changed="load" />
    <section class="content-panel policy-panel">
      <div class="panel-heading"><div><h2>升级策略</h2><span>P1 Incident 未确认时自动逐级通知</span></div><PhoneForwarded :size="18" /></div>
      <div class="policy-flow" v-for="group in [...new Set(policies.map(item => item.policyId))]" :key="group">
        <div class="policy-name"><strong>{{ policies.find(item => item.policyId === group)?.policyName }}</strong><span>{{ policies.find(item => item.policyId === group)?.resourceName }} · {{ policies.find(item => item.policyId === group)?.severity ?? '全部级别' }}</span></div>
        <div class="policy-steps">
          <template v-for="(step, index) in policies.filter(item => item.policyId === group)" :key="step.step">
            <div class="policy-step"><em>STEP {{ step.step }}</em><strong>{{ step.delayMinutes === 0 ? '立即' : `${step.delayMinutes} 分钟` }}</strong><span>{{ step.targetType }} · {{ step.targetRef }}</span></div>
            <ArrowDown v-if="index < policies.filter(item => item.policyId === group).length - 1" :size="17" />
          </template>
        </div>
      </div>
    </section>
    <section class="content-panel oncall-escalation-panel">
      <div class="panel-heading">
        <div><h2>升级执行记录</h2><span>最近 50 步 · 仅 OPEN 事故到期后路由；确认后停止</span></div>
        <button v-if="canScan" class="secondary-button" :disabled="scanning" @click="scan">{{ scanning ? '扫描中' : '立即扫描' }}</button>
      </div>
      <p v-if="message" role="status">{{ message }}</p>
      <div v-if="escalations.length" class="table-scroll">
        <table class="data-table">
          <thead><tr><th>Incident</th><th>策略 / 步骤</th><th>到期</th><th>结果</th><th>记录时间</th></tr></thead>
          <tbody>
            <tr v-for="item in escalations" :key="item.id">
              <td><RouterLink :to="`/incidents?selected=${item.incidentId}`">{{ item.incidentCode }} · {{ item.severity }}</RouterLink></td>
              <td>{{ item.policyName }} · STEP {{ item.step }}<br><small>{{ item.targetType }} · {{ item.targetRef }}</small></td>
              <td>{{ formatTime(item.dueAt, true) }}</td>
              <td><span class="status-badge" :class="item.status === 'ROUTED' ? 'status-info' : 'status-warning'">{{ item.status === 'ROUTED' ? '已记站内路由' : '无可用目标' }}</span><br><small>{{ item.detail }}</small></td>
              <td>{{ formatTime(item.executedAt, true) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-if="escalations.length" class="oncall-escalation-cards">
        <article v-for="item in escalations" :key="item.id" class="oncall-escalation-card">
          <div><RouterLink :to="`/incidents?selected=${item.incidentId}`">{{ item.incidentCode }} · {{ item.severity }}</RouterLink><span class="status-badge" :class="item.status === 'ROUTED' ? 'status-info' : 'status-warning'">{{ item.status === 'ROUTED' ? '已记站内路由' : '无可用目标' }}</span></div>
          <strong>{{ item.policyName }} · STEP {{ item.step }}</strong>
          <small>{{ item.targetType }} · {{ item.targetRef }} · 到期 {{ formatTime(item.dueAt, true) }}</small>
          <p>{{ item.detail }}</p>
        </article>
      </div>
      <div v-else class="empty-state">尚无到期升级记录。历史策略展示仍保留；新告警形成未确认 Incident 后可运行扫描验证。</div>
    </section>
  </div>
</template>

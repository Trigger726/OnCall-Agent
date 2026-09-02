<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { AlarmClock, ArrowDown, Clock3, PhoneForwarded, Users } from 'lucide-vue-next'
import { api, formatTime } from '@/services/api'

interface Shift { scheduleId: number; scheduleName: string; resourceName: string; userName: string | null; department: string | null; startsAt: string | null; endsAt: string | null; override: boolean }
interface Policy { policyId: number; policyName: string; resourceName: string; step: number; delayMinutes: number; targetType: string; targetRef: string }
const shifts = ref<Shift[]>([])
const policies = ref<Policy[]>([])
onMounted(async () => {
  [shifts.value, policies.value] = await Promise.all([
    api<Shift[]>('/on-call/current'), api<Policy[]>('/on-call/policies'),
  ])
})
</script>

<template>
  <div class="page-content">
    <div class="page-toolbar"><div><strong>当前值班与升级链</strong><span>生产服务按排班和策略自动路由通知</span></div></div>
    <section class="oncall-grid">
      <article v-for="shift in shifts" :key="shift.scheduleId" class="content-panel shift-card">
        <header><span class="metric-icon info"><AlarmClock :size="18" /></span><div><span>{{ shift.scheduleName }}</span><h2>{{ shift.resourceName }}</h2></div><em>当前班次</em></header>
        <div class="shift-owner"><span class="user-avatar large">{{ shift.userName?.slice(0, 1) ?? '?' }}</span><div><strong>{{ shift.userName ?? '暂无排班' }}</strong><span>{{ shift.department ?? '-' }}</span></div><Users :size="18" /></div>
        <footer><span><Clock3 :size="15" />{{ formatTime(shift.startsAt, true) }}</span><ArrowDown :size="14" /><span>{{ formatTime(shift.endsAt, true) }}</span></footer>
      </article>
    </section>
    <section class="content-panel policy-panel">
      <div class="panel-heading"><div><h2>升级策略</h2><span>P1 Incident 未确认时自动逐级通知</span></div><PhoneForwarded :size="18" /></div>
      <div class="policy-flow" v-for="group in [...new Set(policies.map(item => item.policyId))]" :key="group">
        <div class="policy-name"><strong>{{ policies.find(item => item.policyId === group)?.policyName }}</strong><span>{{ policies.find(item => item.policyId === group)?.resourceName }}</span></div>
        <div class="policy-steps">
          <template v-for="(step, index) in policies.filter(item => item.policyId === group)" :key="step.step">
            <div class="policy-step"><em>STEP {{ step.step }}</em><strong>{{ step.delayMinutes === 0 ? '立即' : `${step.delayMinutes} 分钟` }}</strong><span>{{ step.targetType }} · {{ step.targetRef }}</span></div>
            <ArrowDown v-if="index < policies.filter(item => item.policyId === group).length - 1" :size="17" />
          </template>
        </div>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { api } from '@/services/api'
import { coverageClock, coverageDays, coverageHours, coverageState, coverageWarnings, readCoverage, type Coverage } from '@/services/onCallCoverage'

const options = ref<{ id: number; name: string; resourceName: string }[]>([])
const filter = reactive({ scheduleId: 0, from: '', to: '' })
const result = ref<Coverage | null>(null)
const busy = ref(false)
const error = ref('')
let pendingRefresh = false
const onlyGaps = ref(false)
const limit = ref(50)
const selectedDay = ref('')
const days = computed(() => result.value ? coverageDays(result.value) : [])
const shown = computed(() => (selectedDay.value ? days.value.find(d => d.date === selectedDay.value)?.segments : result.value?.segments)
  ?.filter(s => !onlyGaps.value || s.shiftId === null) ?? [])

async function refresh() {
  // A slow old request must not overwrite a newer selection; controls stay disabled while loading.
  if (busy.value) { pendingRefresh = true; return }
  busy.value = true
  result.value = null
  error.value = ''
  limit.value = 50
  selectedDay.value = ''
  try {
    const data = await api<{ schedules: typeof options.value }>('/on-call/roster')
    options.value = data.schedules
    if (!filter.scheduleId) filter.scheduleId = options.value[0]?.id ?? 0
    if (!filter.scheduleId) { error.value = '暂无可选择的活跃计划'; return }
    const view = await readCoverage(filter.scheduleId, filter.from, filter.to)
    result.value = view
    // Preserve the precise returned window; datetime-local supports seconds.
    filter.from = view.from
    filter.to = view.to
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '覆盖查询失败；不展示旧覆盖结论' }
  finally {
    busy.value = false
    if (pendingRefresh) { pendingRefresh = false; await refresh() }
  }
}
onMounted(refresh)
defineExpose({ refresh })
</script>

<template>
  <section class="content-panel coverage-panel" :aria-busy="busy">
    <div class="panel-heading"><div><h2>日历覆盖预览</h2><span>已落库班次的有效覆盖与缺班区间 · 最长 31 天</span></div></div>
    <div class="coverage-body">
      <p class="coverage-note">按数据库会话时钟，不按浏览器时区转换；临时覆盖优先，取消/无资格成员不计覆盖。未生成轮转不作预测，未来资格仍可能变化。</p>
      <form class="coverage-filter" @submit.prevent="refresh">
        <label>覆盖计划<select v-model.number="filter.scheduleId" aria-label="覆盖计划" :disabled="busy" required><option v-for="s in options" :key="s.id" :value="s.id">{{ s.resourceName }} · {{ s.name }}</option></select></label>
        <label>覆盖开始<input v-model="filter.from" aria-label="覆盖开始" type="datetime-local" step="any" :disabled="busy"></label>
        <label>覆盖结束<input v-model="filter.to" aria-label="覆盖结束" type="datetime-local" step="any" :disabled="busy"></label>
        <button class="secondary-button" :disabled="busy || !filter.scheduleId">{{ busy ? '计算中' : '查询覆盖' }}</button>
      </form>
      <p v-if="error" class="coverage-error" role="alert">{{ error }}</p>
      <template v-if="result">
        <p class="coverage-summary" role="status">{{ result.schedule.name }}：已覆盖 {{ coverageHours(result.coveredSeconds) }} 小时 · 缺班 {{ coverageHours(result.gapSeconds) }} 小时 · {{ result.sourceShifts }} 条未取消源班次</p>
        <p class="coverage-note">快照时间 {{ coverageClock(result.databaseNow) }}；半开区间 [开始, 结束)。不是事故响应率或可用性 SLO。</p>
        <div class="coverage-calendar" aria-label="每日覆盖日历">
          <button v-for="day in days" :key="day.date" type="button" class="coverage-day" :class="{ 'has-gap': day.gapSeconds > 0, selected: selectedDay === day.date }" :aria-pressed="selectedDay === day.date" @click="selectedDay = day.date; limit = 50">
            <strong>{{ day.date }}</strong><span>覆盖 {{ coverageHours(day.coveredSeconds) }}h</span><span>缺班 {{ coverageHours(day.gapSeconds) }}h</span>
          </button>
        </div>
        <button v-if="selectedDay" class="secondary-button" @click="selectedDay = ''; limit = 50">所有日期</button>
        <label class="coverage-gap-filter"><input v-model="onlyGaps" type="checkbox" @change="limit = 50">只看缺班</label>
        <div class="coverage-timeline" aria-label="有效覆盖时间线">
          <article v-for="s in shown.slice(0, limit)" :key="s.startsAt" class="coverage-segment" :class="{ gap: s.shiftId === null, override: s.override }">
            <div><strong>{{ s.userName ?? '无可用值班人' }}</strong><span class="status-badge" :class="s.shiftId === null ? 'status-warning' : 'status-info'">{{ coverageState(s) }}</span></div>
            <time>{{ coverageClock(s.startsAt) }} → {{ coverageClock(s.endsAt) }}</time>
            <small v-if="s.shiftId !== null">生效班次 #{{ s.shiftId }}</small>
            <p v-for="warning in coverageWarnings(s)" :key="warning" class="coverage-warning">{{ warning }}</p>
          </article>
        </div>
        <p v-if="!shown.length" class="empty-state">当前日期/筛选范围无缺班区间；仅代表已落库班次和当前成员资格。</p>
        <button v-if="shown.length > limit" class="secondary-button" @click="limit += 50">继续显示（已显示 {{ limit }}/{{ shown.length }} 段）</button>
      </template>
    </div>
  </section>
</template>

<style scoped>
.coverage-body { padding: 18px 20px; }
.coverage-note { color: var(--text-muted); font-size: 12px; line-height: 1.6; margin: 0 0 12px; }
.coverage-filter { display: grid; grid-template-columns: 1.3fr 1fr 1fr auto; gap: 12px; align-items: end; margin-bottom: 14px; }
.coverage-filter label { display: grid; gap: 6px; font-size: 12px; min-width: 0; }
.coverage-filter input, .coverage-filter select { width: 100%; min-width: 0; padding: 9px; border: 1px solid #e5e7eb; border-radius: 7px; background: white; color: inherit; }
.coverage-summary { font-size: 14px; line-height: 1.6; }
.coverage-gap-filter { display: flex; align-items: center; gap: 8px; margin: 12px 0; font-size: 13px; }
.coverage-calendar { display: grid; grid-template-columns: repeat(7, minmax(0,1fr)); gap: 8px; margin: 14px 0; }
.coverage-day { display: grid; gap: 6px; padding: 10px; border: 1px solid #dce4eb; border-radius: 7px; background: #f8fbff; color: inherit; text-align: left; cursor: pointer; min-width: 0; }
.coverage-day strong, .coverage-day span { font-size: 11px; overflow-wrap: anywhere; }
.coverage-day.has-gap { background: #fff9f0; border-color: #f0ce9d; }
.coverage-day.selected { outline: 2px solid #007aff; outline-offset: 1px; }
.coverage-segment { border-left: 3px solid #007aff; border-bottom: 1px solid #e5e7eb; padding: 14px 16px; overflow-wrap: anywhere; }
.coverage-segment.gap { border-left-color: #e59a21; background: #fff9f0; }
.coverage-segment.override { border-left-color: #7950b5; }
.coverage-segment > div { display: flex; justify-content: space-between; align-items: center; gap: 10px; margin-bottom: 6px; }
.coverage-segment time, .coverage-segment small { display: block; font-size: 12px; line-height: 1.7; }
.coverage-warning, .coverage-error { color: #b75c00; font-size: 12px; line-height: 1.6; }
@media (max-width: 820px) { .coverage-filter { grid-template-columns: 1fr 1fr; } .coverage-calendar { grid-template-columns: repeat(3,minmax(0,1fr)); } }
@media (max-width: 480px) { .coverage-body { padding: 14px; } .coverage-filter { grid-template-columns: 1fr; } .coverage-calendar { grid-template-columns: repeat(2,minmax(0,1fr)); } .coverage-segment { padding: 12px; } .coverage-segment > div { align-items: flex-start; flex-wrap: wrap; } }
</style>

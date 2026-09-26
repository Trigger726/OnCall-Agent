<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { api } from '@/services/api'
import { auth } from '@/stores/auth'

interface Shift { id: number; scheduleId: number; scheduleName: string; userId: number; userName: string; startsAt: string; endsAt: string; override: boolean; version: number; note: string; cancelledAt: string | null; cancellationReason: string | null }
interface Roster { databaseNow: string; from: string; to: string; suggestedStart: string; suggestedEnd: string; schedules: { id: number; name: string; resourceName: string }[]; users: { id: number; displayName: string; roleCode: string }[]; shifts: Shift[]; truncated: boolean }
const emit = defineEmits<{ changed: [] }>()
const canManage = computed(() => ['ADMIN', 'OPS_MANAGER'].includes(auth.state.user?.roleCode ?? ''))
const roster = ref<Roster | null>(null)
const busy = ref(false)
const showCreate = ref(false)
const cancelTarget = ref<Shift | null>(null)
const cancelReason = ref('')
const message = ref('')
const error = ref('')
const windowFilter = reactive({ scheduleId: '', from: '', to: '' })
const draft = reactive({ scheduleId: 0, userId: 0, startsAt: '', endsAt: '', override: false, note: '' })
const clockText = (value: string) => value.replace('T', ' ').slice(0, 16)
function shiftState(item: Shift) {
  if (item.cancelledAt) return '已取消'
  const now = roster.value?.databaseNow ?? ''
  if (item.endsAt <= now) return '已结束'
  return item.startsAt > now ? '待开始' : '时段内'
}

async function load() {
  const query = new URLSearchParams()
  if (windowFilter.scheduleId) query.set('scheduleId', windowFilter.scheduleId)
  if (windowFilter.from) query.set('from', windowFilter.from)
  if (windowFilter.to) query.set('to', windowFilter.to)
  const result = await api<Roster>(`/on-call/roster?${query}`)
  roster.value = result
  windowFilter.from = result.from.slice(0, 16)
  windowFilter.to = result.to.slice(0, 16)
  if (!draft.scheduleId) draft.scheduleId = result.schedules[0]?.id ?? 0
  if (!draft.userId) draft.userId = result.users[0]?.id ?? 0
  if (!draft.startsAt) draft.startsAt = result.suggestedStart.slice(0, 16)
  if (!draft.endsAt) draft.endsAt = result.suggestedEnd.slice(0, 16)
}

async function refresh() {
  busy.value = true
  error.value = ''
  try { await load() }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '班次加载失败' }
  finally { busy.value = false }
}

async function saveShift() {
  busy.value = true
  error.value = ''
  message.value = ''
  try {
    const created = await api<Shift>('/on-call/shifts', { method: 'POST', body: JSON.stringify(draft) })
    windowFilter.scheduleId = String(created.scheduleId)
    windowFilter.from = created.startsAt.slice(0, 16)
    windowFilter.to = created.endsAt.slice(0, 16)
    showCreate.value = false
    draft.note = ''
    message.value = '班次已创建；当前值班与后续路由已按新班次计算'
    await load()
    emit('changed')
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '创建失败' }
  finally { busy.value = false }
}

async function cancelShift() {
  if (!cancelTarget.value) return
  busy.value = true
  error.value = ''
  message.value = ''
  try {
    await api(`/on-call/shifts/${cancelTarget.value.id}/cancel`, {
      method: 'POST', body: JSON.stringify({ version: cancelTarget.value.version, reason: cancelReason.value }),
    })
    cancelTarget.value = null
    cancelReason.value = ''
    message.value = '班次已取消，历史与审计保留；不重写已有升级事件'
    await load()
    emit('changed')
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '取消失败，请刷新核对班次版本' }
  finally { busy.value = false }
}

onMounted(refresh)
</script>

<template>
  <section class="content-panel oncall-roster-panel">
    <div class="panel-heading">
      <div><h2>班次维护</h2><span>普通班次与临时覆盖分开记录；取消不删除历史</span></div>
      <button v-if="canManage" class="secondary-button" :disabled="busy || !roster" @click="showCreate = !showCreate">{{ showCreate ? '收起表单' : '新增班次' }}</button>
    </div>
    <div class="roster-body">
      <p v-if="roster" class="roster-clock">数据库会话时间：{{ clockText(roster.databaseNow) }}。输入按此时间口径，不按浏览器时区转换；“时段内”不等于胜出的值班人，以顶部当前值班为准。</p>
      <p v-if="error" class="roster-error" role="alert">{{ error }}</p>
      <p v-if="message" role="status">{{ message }}</p>
      <form v-if="roster" class="roster-filter" @submit.prevent="refresh">
        <label>查看计划<select v-model="windowFilter.scheduleId" aria-label="查看计划"><option value="">全部计划</option><option v-for="item in roster.schedules" :key="item.id" :value="String(item.id)">{{ item.name }}</option></select></label>
        <label>窗口开始<input v-model="windowFilter.from" type="datetime-local" required></label>
        <label>窗口结束<input v-model="windowFilter.to" type="datetime-local" required></label>
        <button class="secondary-button" :disabled="busy">查询班次</button>
      </form>
      <form v-if="showCreate && canManage && roster" class="roster-editor" @submit.prevent="saveShift">
        <h3>新增班次</h3>
        <div class="roster-fields">
          <label>服务计划<select v-model.number="draft.scheduleId" aria-label="服务计划" required><option v-for="item in roster.schedules" :key="item.id" :value="item.id">{{ item.resourceName }} · {{ item.name }}</option></select></label>
          <label>值班负责人<select v-model.number="draft.userId" aria-label="值班负责人" required><option v-for="item in roster.users" :key="item.id" :value="item.id">{{ item.displayName }} · {{ item.roleCode }}</option></select></label>
          <label>开始时间<input v-model="draft.startsAt" type="datetime-local" required></label>
          <label>结束时间<input v-model="draft.endsAt" type="datetime-local" required></label>
        </div>
        <label class="roster-checkbox"><input v-model="draft.override" type="checkbox">临时覆盖（优先于普通班次）</label>
        <label>排班说明<textarea v-model="draft.note" required maxlength="500" rows="2" placeholder="例如：已协调张伟接班至明早"></textarea></label>
        <p>同类班次不能重叠，可在结束边界交接；单班最长 31 天，结束不能早于当前数据库时间。</p>
        <button class="primary-button" :disabled="busy || !draft.scheduleId || !draft.userId">{{ busy ? '保存中' : '保存班次' }}</button>
      </form>
      <form v-if="cancelTarget && canManage" class="roster-editor" @submit.prevent="cancelShift">
        <h3>确认取消 {{ cancelTarget.userName }} 的{{ cancelTarget.override ? '临时覆盖' : '普通班次' }}</h3>
        <p>{{ clockText(cancelTarget.startsAt) }} → {{ clockText(cancelTarget.endsAt) }} · v{{ cancelTarget.version }}</p>
        <label>取消原因<textarea v-model="cancelReason" required maxlength="500" rows="2"></textarea></label>
        <div class="roster-actions"><button class="primary-button" :disabled="busy">确认取消班次</button><button type="button" class="secondary-button" :disabled="busy" @click="cancelTarget = null">保留班次</button></div>
      </form>
      <p v-if="roster?.truncated" role="status">仅显示前 200 条，请缩小查询窗口（最多 31 天）。</p>
      <div v-if="roster?.shifts.length" class="roster-list">
        <article v-for="item in roster.shifts" :key="item.id" class="roster-row" :class="{ cancelled: item.cancelledAt }">
          <div><strong>{{ item.userName }} · {{ item.override ? '临时覆盖' : '普通班次' }}</strong><span>{{ item.scheduleName }} · {{ shiftState(item) }} · v{{ item.version }}</span></div>
          <div><span>{{ clockText(item.startsAt) }} → {{ clockText(item.endsAt) }}</span><small>{{ item.cancelledAt ? `取消原因：${item.cancellationReason}` : item.note || '历史班次' }}</small></div>
          <button v-if="canManage && !item.cancelledAt && shiftState(item) !== '已结束'" class="secondary-button" :disabled="busy" @click="cancelTarget = item; cancelReason = ''">取消班次</button>
        </article>
      </div>
      <div v-else-if="roster" class="empty-state">窗口内没有班次。历史排班未删除；可调整窗口查看，管理角色可新增有效班次。</div>
    </div>
  </section>
</template>

<style scoped>
.oncall-roster-panel { margin-bottom: 18px; }
.roster-body { padding: 0 20px 20px; }
.roster-clock, .roster-editor p { color: var(--text-muted); font-size: 12px; line-height: 1.6; }
.roster-error { color: #b42318; }
.roster-filter, .roster-fields { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; margin: 16px 0; }
.roster-filter { grid-template-columns: 1.1fr 1fr 1fr auto; align-items: end; }
label { display: flex; flex-direction: column; gap: 6px; min-width: 0; font-size: 12px; }
input:not([type=checkbox]), select, textarea { width: 100%; min-width: 0; box-sizing: border-box; border: 1px solid var(--line); background: var(--surface); color: inherit; border-radius: 6px; padding: 9px; font: inherit; }
.roster-editor { padding: 16px; margin: 16px 0; border: 1px solid var(--line); border-radius: 8px; }
.roster-editor h3 { font-size: 14px; margin: 0 0 12px; }
.roster-checkbox { flex-direction: row; align-items: center; margin: 12px 0; }
.roster-actions { display: flex; gap: 8px; margin-top: 12px; }
.roster-row { display: grid; grid-template-columns: 1fr 1.4fr auto; gap: 12px; align-items: center; padding: 14px 0; border-top: 1px solid var(--line); }
.roster-row div { display: flex; flex-direction: column; gap: 6px; min-width: 0; }
.roster-row strong { font-size: 13px; }
.roster-row span, .roster-row small { font-size: 12px; overflow-wrap: anywhere; }
.roster-row.cancelled { color: var(--text-muted); }
@media (max-width: 900px) { .roster-filter { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 640px) { .roster-filter, .roster-fields, .roster-row { grid-template-columns: 1fr; } .roster-body { padding: 0 14px 14px; } .roster-editor { padding: 12px; } .roster-row button { justify-self: start; } }
</style>

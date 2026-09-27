<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { api, RequestError } from '@/services/api'
import { auth } from '@/stores/auth'
import { canRequestHandoff, clearHandoffDraft, decideHandoff, handoffActions, handoffClock, handoffDraftError,
  handoffState, handoffTime, listHandoffs, readHandoffDraft, requestHandoff, saveHandoffDraft,
  type Handoff, type HandoffDecision, type HandoffList, type HandoffSource, type SavedHandoffDraft } from '@/services/onCallHandoffs'

const emit = defineEmits<{ changed: [] }>()
const requestEditor = ref<HTMLElement | null>(null)
const decisionEditor = ref<HTMLElement | null>(null)
const busy = ref(false)
const list = ref<HandoffList | null>(null)
const options = ref<{ schedules: { id: number; name: string }[]; users: { id: number; displayName: string }[] }>({ schedules: [], users: [] })
const canAct = computed(() => ['ADMIN', 'OPS_MANAGER', 'ON_CALL'].includes(auth.state.user?.roleCode ?? ''))
const filter = reactive<{ scheduleId: string; scope: 'ALL' | 'MINE'; status: Handoff['status'] | '' }>({ scheduleId: '', scope: canAct.value ? 'MINE' : 'ALL', status: 'PENDING' })
const draft = ref<SavedHandoffDraft | null>(null)
const frozen = ref(false)
const draftUnreadable = ref(false)
const decision = ref<{ row: Handoff; status: HandoffDecision; reason: string; submitted: boolean; blocked: boolean } | null>(null)
const discardConfirm = ref(false)
const error = ref('')
const message = ref('')
let pendingRefresh = false
const actionName = (status: HandoffDecision) => ({ ACCEPTED: '接受接班', REJECTED: '拒绝接班', WITHDRAWN: '撤回申请' }[status])
const userName = (id: number) => options.value.users.find(u => u.id === id)?.displayName ?? `用户 #${id}`
const actions = (row: Handoff) => handoffActions(row, auth.state.user?.id, auth.state.user?.roleCode, list.value?.databaseNow ?? '')

async function refresh() {
  if (busy.value) { pendingRefresh = true; return }
  busy.value = true
  list.value = null // A failed/new query must not leave an old inbox actionable.
  error.value = ''
  const actorId = auth.state.user?.id
  try {
    const [roster, rows] = await Promise.all([api<typeof options.value>('/on-call/roster'), listHandoffs(filter.scheduleId, filter.scope, filter.status)])
    if (actorId !== auth.state.user?.id) throw new Error('登录身份已变化，请重新打开页面')
    options.value = roster
    list.value = rows
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '接班台账加载失败' }
  finally { busy.value = false; if (pendingRefresh) { pendingRefresh = false; await refresh() } }
}
async function open(source: HandoffSource) {
  if (busy.value || !canRequestHandoff(source, auth.state.user?.id, auth.state.user?.roleCode, list.value?.databaseNow ?? '')) return
  if (draft.value || draftUnreadable.value) { error.value = '请先完成或明确放弃当前申请，避免换键重复提交'; return }
  if (!options.value.schedules.some(s => s.id === source.scheduleId)) { error.value = '计划当前不可用，请刷新班次核对'; return }
  const actorId = auth.state.user!.id
  draft.value = { schema: 1, actorId, source: { ...source }, command: { sourceShiftId: source.id, sourceVersion: source.version,
    targetUserId: options.value.users.find(u => u.id !== actorId)?.id ?? 0, requestKey: crypto.randomUUID(),
    startsAt: handoffTime(source.startsAt), endsAt: handoffTime(source.endsAt), reason: '' } }
  frozen.value = false
  error.value = ''
  message.value = ''
  await nextTick()
  requestEditor.value?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}
async function submit() {
  const captured = draft.value
  if (!captured || busy.value) return
  error.value = ''; message.value = ''
  if (captured.actorId !== auth.state.user?.id || !canAct.value) { error.value = '申请身份已变化，请重新登录原申请人'; return }
  if (!frozen.value) {
    const command = { ...captured.command, startsAt: handoffTime(captured.command.startsAt), endsAt: handoffTime(captured.command.endsAt), reason: captured.command.reason.trim() }
    const invalid = handoffDraftError(command, captured.source, captured.actorId)
    if (invalid) { error.value = invalid; return }
    try {
      // Persist before POST; quota/storage errors must not send an unrecorded request.
      saveHandoffDraft(sessionStorage, { ...captured, command })
    } catch { error.value = '无法保存重试草稿，尚未发送申请；请检查浏览器存储权限'; return }
    captured.command = command
    frozen.value = true
  }
  busy.value = true
  try {
    const result = await requestHandoff(captured.command)
    clearHandoffDraft(sessionStorage, captured.actorId)
    draft.value = null
    frozen.value = false
    message.value = `接班请求 #${result.id} 已确认保存（${handoffState(result, list.value?.databaseNow ?? '')}）；责任变更以接班人的决定与覆盖日历为准`
    filter.scheduleId = String(result.scheduleId)
    filter.status = ''
    pendingRefresh = true
  } catch (cause) {
    error.value = `${cause instanceof TypeError ? '网络响应中断，服务端可能已保存申请' : cause instanceof Error ? cause.message : '无法确认申请结果'}。原请求键和内容已保留；请刷新台账核对，必要时重新提交原请求`
  } finally { busy.value = false; if (pendingRefresh) { pendingRefresh = false; await refresh() } }
}
async function chooseDecision(row: Handoff, status: HandoffDecision) {
  decision.value = { row: { ...row }, status, reason: '', submitted: false, blocked: false }
  error.value = ''; message.value = ''
  await nextTick()
  decisionEditor.value?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}
async function decide() {
  const captured = decision.value
  if (!captured || busy.value || !list.value || captured.blocked) return
  const participant = captured.status === 'WITHDRAWN' ? captured.row.requesterId : captured.row.targetUserId
  if (!canAct.value || participant !== auth.state.user?.id || (!captured.submitted && !actions(captured.row).includes(captured.status))) { error.value = '当前本人身份或时段不允许此决定，请刷新核对'; return }
  if (!captured.reason.trim() || captured.reason.length > 500) { error.value = '决定说明须为 1–500 字'; return }
  captured.reason = captured.reason.trim()
  captured.submitted = true
  busy.value = true
  error.value = ''; message.value = ''
  try {
    const result = await decideHandoff(captured.row.id, { version: captured.row.version, status: captured.status, reason: captured.reason })
    decision.value = null
    message.value = `请求 #${result.id} ${handoffState(result, list.value.databaseNow)}${result.replacementShiftId ? `；覆盖班次 #${result.replacementShiftId}，是否生效请核对日历` : ''}`
    filter.status = ''
    pendingRefresh = true
    if (result.status === 'ACCEPTED') emit('changed')
  } catch (cause) {
    if (cause instanceof RequestError && cause.status === 409) captured.blocked = true
    error.value = `${cause instanceof Error ? cause.message : '决定失败'}。${captured.blocked ? '旧版本已锁定，请刷新台账后重新选择请求；不会自动换版本提交' : '保留原版本与说明，可手动重试原决定'}`
  } finally { busy.value = false; if (pendingRefresh) { pendingRefresh = false; await refresh() } }
}
function discard() {
  const actorId = draft.value?.actorId ?? auth.state.user?.id
  try { if (actorId) clearHandoffDraft(sessionStorage, actorId) }
  catch { error.value = '无法清除浏览器草稿，请检查存储权限；尚未放弃'; return }
  draft.value = null; frozen.value = false; discardConfirm.value = false
  draftUnreadable.value = false
  message.value = '已明确放弃本浏览器草稿；不会撤回服务器上可能已保存的申请，请核对台账'
}
onMounted(async () => {
  try {
    if (auth.state.user) draft.value = readHandoffDraft(sessionStorage, auth.state.user.id)
    frozen.value = Boolean(draft.value)
    if (draft.value) message.value = '已恢复原请求键与不可变内容；先核对台账，再按需重试原请求'
  } catch (cause) { draftUnreadable.value = true; message.value = cause instanceof Error ? cause.message : '草稿读取失败' }
  await refresh()
})
defineExpose({ refresh, open })
</script>

<template>
  <section class="content-panel handoff-panel" :aria-busy="busy">
    <div class="panel-heading"><div><h2>接班请求</h2><span>本人申请、指定接班人同意后生成覆盖；原班次与旧路由保留</span></div><button class="secondary-button" :disabled="busy" @click="refresh">刷新接班台账</button></div>
    <div class="handoff-body">
      <p class="handoff-note">从“班次维护”中本人的有效普通班次发起。接受正在进行的申请只覆盖剩余时段；已接受不等于此刻生效，覆盖取消须由管理角色在班次维护操作。</p>
      <p v-if="error" class="handoff-error" role="alert">{{ error }}</p><p v-if="message" role="status">{{ message }}</p>
      <button v-if="draftUnreadable && canAct" class="secondary-button" :disabled="busy" @click="discardConfirm = true">核对台账后放弃损坏草稿</button>
      <form class="handoff-filter" @submit.prevent="refresh">
        <label>接班计划<select v-model="filter.scheduleId" aria-label="接班计划" :disabled="busy"><option value="">全部计划</option><option v-for="s in options.schedules" :key="s.id" :value="String(s.id)">{{ s.name }}</option></select></label>
        <label>接班范围<select v-model="filter.scope" aria-label="接班范围" :disabled="busy"><option value="MINE">与我相关</option><option value="ALL">全部请求</option></select></label>
        <label>接班状态<select v-model="filter.status" aria-label="接班状态" :disabled="busy"><option value="">全部状态</option><option value="PENDING">待处理</option><option value="ACCEPTED">已接受</option><option value="REJECTED">已拒绝</option><option value="WITHDRAWN">已撤回</option></select></label>
        <button class="secondary-button" :disabled="busy">查询接班</button>
      </form>
      <form v-if="draft && canAct" ref="requestEditor" class="handoff-editor handoff-request-editor" @submit.prevent="submit">
        <h3>申请定向接班</h3><p>{{ draft.source.scheduleName }} · 原班次 #{{ draft.source.id }} · v{{ draft.source.version }} · {{ draft.source.userName }}</p>
        <p class="handoff-note">{{ handoffClock(draft.source.startsAt) }} → {{ handoffClock(draft.source.endsAt) }}</p>
        <fieldset :disabled="busy || frozen"><div class="handoff-fields">
          <label>指定接班人<select v-model.number="draft.command.targetUserId" aria-label="指定接班人" required><option v-for="u in options.users.filter(u => u.id !== draft!.actorId)" :key="u.id" :value="u.id">{{ u.displayName }}</option></select></label>
          <label>接班开始<input v-model="draft.command.startsAt" aria-label="接班开始" type="datetime-local" step="1" required></label>
          <label>接班结束<input v-model="draft.command.endsAt" aria-label="接班结束" type="datetime-local" step="1" required></label>
        </div><label>申请说明<textarea v-model="draft.command.reason" aria-label="申请说明" required maxlength="500" rows="2"></textarea></label></fieldset>
        <p class="handoff-key">目标用户 #{{ draft.command.targetUserId }} · 原请求键：{{ draft.command.requestKey }}</p><p v-if="frozen" class="handoff-note">原内容已锁定并按账号保存在此标签页；刷新不会换键，重新提交使用同一键和内容。关闭标签页会丢失浏览器草稿，请先核对台账。</p>
        <div class="handoff-actions"><button class="primary-button" :disabled="busy">{{ frozen ? '重新提交原请求' : '提交接班申请' }}</button><button type="button" class="secondary-button" :disabled="busy" @click="discardConfirm = true">放弃草稿</button></div>
      </form>
      <div v-if="discardConfirm" class="handoff-editor" role="alert"><p>可能已在服务器保存。放弃草稿不撤回申请；请先刷新台账核对，创建新申请会换键。</p><div class="handoff-actions"><button class="secondary-button" :disabled="busy" @click="discard">确认放弃本地草稿</button><button class="secondary-button" :disabled="busy" @click="discardConfirm = false">保留草稿</button></div></div>
      <form v-if="decision && canAct" ref="decisionEditor" class="handoff-editor handoff-decision-editor" @submit.prevent="decide">
        <h3>{{ actionName(decision.status) }} #{{ decision.row.id }}</h3><p>{{ userName(decision.row.requesterId) }} → {{ userName(decision.row.targetUserId) }} · 捕获请求 v{{ decision.row.version }}</p><p>{{ handoffClock(decision.row.startsAt) }} → {{ handoffClock(decision.row.endsAt) }}</p>
        <label>决定说明<textarea v-model="decision.reason" aria-label="决定说明" required maxlength="500" rows="2" :disabled="busy || decision.submitted"></textarea></label>
        <p v-if="decision.blocked" role="alert">旧版本已锁定，请刷新并重新选择请求；不会自动替换捕获版本。</p>
        <div class="handoff-actions"><button class="primary-button" :disabled="busy || !list || decision.blocked">{{ decision.submitted ? '重试原决定' : '确认决定' }}</button><button type="button" class="secondary-button" :disabled="busy" @click="decision = null">关闭决定</button></div>
      </form>
      <p v-if="list" class="handoff-note">数据库快照 {{ handoffClock(list.databaseNow) }}；“时段已结束”仍是待处理事实，可拒绝或撤回，不伪造自动过期。</p>
      <p v-if="list?.truncated" role="status">筛选后仍超过200条，仅显示最新200条；请缩小计划/状态范围，不能当作完整台账。</p>
      <div v-if="list?.requests.length" class="handoff-list"><article v-for="row in list.requests" :key="row.id" class="handoff-row" :data-handoff-id="row.id">
        <div class="handoff-row-heading"><strong>#{{ row.id }} · {{ userName(row.requesterId) }} → {{ userName(row.targetUserId) }}</strong><span class="status-badge" :class="row.status === 'PENDING' ? 'status-warning' : 'status-info'">{{ handoffState(row, list.databaseNow) }}</span></div>
        <p>计划 #{{ row.scheduleId }} · 原班次 #{{ row.sourceShiftId }} v{{ row.sourceVersion }} · 请求 v{{ row.version }}</p><time>{{ handoffClock(row.startsAt) }} → {{ handoffClock(row.endsAt) }}</time><p>{{ row.reason }}</p>
        <p v-if="row.decisionReason">决定：{{ row.decisionReason }} · {{ row.decidedAt ? handoffClock(row.decidedAt) : '' }}</p><p v-if="row.replacementShiftId">覆盖班次 #{{ row.replacementShiftId }}，当前生效/取消情况请核对日历与班次维护。</p>
        <div v-if="actions(row).length" class="handoff-actions"><button v-for="action in actions(row)" :key="action" class="secondary-button" :disabled="busy" @click="chooseDecision(row, action)">{{ actionName(action) }}</button></div>
      </article></div>
      <div v-else-if="list" class="empty-state">当前筛选没有接班请求；与我相关包含本人申请与指向本人的请求。</div>
    </div>
  </section>
</template>

<style scoped>
.handoff-panel { margin-bottom: 18px; scroll-margin-top: 85px; }
.handoff-body { padding: 0 20px 20px; }
.handoff-note, .handoff-key { color: var(--text-muted); font-size: 12px; line-height: 1.6; overflow-wrap: anywhere; }
.handoff-error { color: #b42318; overflow-wrap: anywhere; }
.handoff-filter, .handoff-fields { display: grid; grid-template-columns: repeat(3,minmax(0,1fr)); gap: 12px; margin: 16px 0; }
.handoff-filter { grid-template-columns: 1.3fr 1fr 1fr auto; align-items: end; }
label { display: grid; gap: 6px; min-width: 0; font-size: 12px; }
input, select, textarea { width: 100%; min-width: 0; box-sizing: border-box; border: 1px solid var(--line); background: var(--surface); color: inherit; border-radius: 6px; padding: 9px; font: inherit; }
fieldset { border: 0; padding: 0; margin: 0; min-width: 0; }
.handoff-editor { margin: 16px 0; padding: 16px; border: 1px solid var(--line); border-radius: 8px; scroll-margin-top: 85px; }
.handoff-editor h3 { margin: 0 0 12px; font-size: 14px; }
.handoff-editor p, .handoff-row p, .handoff-row time { font-size: 12px; line-height: 1.6; overflow-wrap: anywhere; }
.handoff-actions { display: flex; gap: 8px; flex-wrap: wrap; margin-top: 12px; }
.handoff-row { border-top: 1px solid var(--line); padding: 14px 0; }
.handoff-row-heading { display: flex; justify-content: space-between; gap: 12px; align-items: start; flex-wrap: wrap; }
.handoff-row-heading strong { font-size: 13px; }
@media (max-width: 900px) { .handoff-filter, .handoff-fields { grid-template-columns: repeat(2,minmax(0,1fr)); } }
@media (max-width: 640px) { .handoff-filter, .handoff-fields { grid-template-columns: 1fr; } .handoff-body { padding: 0 14px 14px; } .handoff-editor { padding: 12px; } .handoff-panel .panel-heading { align-items: start; flex-wrap: wrap; gap: 10px; } }
</style>

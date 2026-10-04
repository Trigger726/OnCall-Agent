<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { api, RequestError } from '@/services/api'
import { auth } from '@/stores/auth'
import OnCallSwapNotificationPanel from '@/components/OnCallSwapNotificationPanel.vue'
import OnCallSwapRevocationPanel from '@/components/OnCallSwapRevocationPanel.vue'
import { canChooseSwap, canRequestSwap, clearSwapIntent, decideSwap, getSwap, getSwapCoverage, listSwaps,
  readSwapIntent, requestSwap, saveSwapIntent, swapActions, swapClock, swapDecisionError, swapRequestError, swapState,
  type Swap, type SwapCoverage, type SwapDecision, type SwapIntent, type SwapList, type SwapRoster, type SwapSource, type SwapStatus } from '@/services/onCallSwaps'

const emit = defineEmits<{ changed: [] }>()
const panel = ref<HTMLElement | null>(null)
const busy = ref(false), error = ref(''), message = ref(''), frozen = ref(false), unreadable = ref(false), discardConfirm = ref(false)
const list = ref<SwapList | null>(null), options = ref<SwapRoster | null>(null), candidates = ref<SwapRoster | null>(null)
const intent = ref<SwapIntent | null>(null)
const editor = ref<{ first: SwapSource; second: SwapSource | null; reason: string; key: string } | null>(null)
const detailId = ref<number | null>(null), detail = ref<{ row: Swap; first: SwapCoverage; second: SwapCoverage } | null>(null)
const canAct = computed(() => ['ADMIN','OPS_MANAGER','ON_CALL'].includes(auth.state.user?.roleCode ?? ''))
const filter = reactive<{ schedule: string; scope: 'ALL' | 'MINE'; status: SwapStatus | '' }>({ schedule: '', scope: canAct.value ? 'MINE' : 'ALL', status: 'PENDING' })
const windowFilter = reactive({ schedule: '', from: '', to: '', secondId: '' })
const choices = computed(() => editor.value ? candidates.value?.shifts.filter(s => canChooseSwap(s, editor.value!.first, auth.state.user?.id ?? 0, candidates.value!.databaseNow)) ?? [] : [])
const userName = (id: number) => options.value?.users.find(u => u.id === id)?.displayName ?? `用户 #${id}`
const planName = (id: number) => options.value?.schedules.find(s => s.id === id)?.name ?? `计划 #${id}`
const actions = (row: Swap) => swapActions(row, auth.state.user?.id, auth.state.user?.roleCode, list.value?.databaseNow ?? '')
const actionName = (status: SwapDecision) => ({ ACCEPTED: '接受双向换班', REJECTED: '拒绝双向换班', WITHDRAWN: '撤回换班申请' }[status])
let epoch = 0, pendingRefresh = false
const capture = () => ({ epoch, actor: auth.state.user?.id, token: localStorage.getItem('opspilot_token') })
const current = (value: ReturnType<typeof capture>) => value.epoch === epoch && value.actor === auth.state.user?.id && value.token === localStorage.getItem('opspilot_token')
async function finish(value: ReturnType<typeof capture>) {
  if (!current(value)) return
  busy.value = false
  if (pendingRefresh) { pendingRefresh = false; await refresh() }
}
async function loadDetail(id: number, identity: ReturnType<typeof capture>) {
  detail.value = null
  const row = await getSwap(id)
  const [first, second] = await Promise.all([getSwapCoverage(row.firstScheduleId, row.firstStartsAt, row.firstEndsAt), getSwapCoverage(row.secondScheduleId, row.secondStartsAt, row.secondEndsAt)])
  if (current(identity) && detailId.value === id) detail.value = { row, first, second }
}
async function refresh() {
  if (busy.value) { pendingRefresh = true; return }
  const identity = capture(); busy.value = true; list.value = null; detail.value = null; error.value = ''
  try {
    const [roster, rows] = await Promise.all([api<SwapRoster>('/on-call/roster'), listSwaps(filter.schedule, filter.scope, filter.status)])
    if (!current(identity)) return
    options.value = roster; list.value = rows
    if (detailId.value) await loadDetail(detailId.value, identity)
  } catch (cause) { if (current(identity)) error.value = cause instanceof Error ? cause.message : '换班台账加载失败；旧列表已清空' }
  finally { await finish(identity) }
}
async function chooseCandidates() {
  if (busy.value || frozen.value || !editor.value) return
  const identity = capture(); busy.value = true; candidates.value = null; editor.value.second = null; windowFilter.secondId = ''; error.value = ''
  try {
    const query = new URLSearchParams({ from: windowFilter.from, to: windowFilter.to }); if (windowFilter.schedule) query.set('scheduleId', windowFilter.schedule)
    const result = await api<SwapRoster>(`/on-call/roster?${query}`)
    if (current(identity)) candidates.value = result
  } catch (cause) { if (current(identity)) error.value = cause instanceof Error ? cause.message : '候选班次查询失败' }
  finally { await finish(identity) }
}
function chooseSecond() { if (editor.value && !frozen.value) editor.value.second = choices.value.find(s => String(s.id) === windowFilter.secondId) ?? null }
async function open(source: SwapSource) {
  if (busy.value || !canRequestSwap(source, auth.state.user?.id, auth.state.user?.roleCode, list.value?.databaseNow ?? '')) return
  if (editor.value || intent.value || unreadable.value) { error.value = '先完成或明确放弃原意图，不会换键创建第二个申请'; return }
  editor.value = { first: { ...source }, second: null, reason: '', key: crypto.randomUUID() }
  windowFilter.schedule = ''; windowFilter.from = source.startsAt.slice(0,16)
  windowFilter.to = new Date(Date.parse(source.startsAt + 'Z') + 7 * 86400000).toISOString().slice(0,16)
  candidates.value = null; frozen.value = false; message.value = ''; error.value = ''
  await nextTick(); panel.value?.scrollIntoView({ block: 'start', behavior: 'smooth' }); await chooseCandidates()
}
async function chooseDecision(row: Swap, status: SwapDecision) {
  if (busy.value || !actions(row).includes(status)) return
  if (editor.value || intent.value || unreadable.value) { error.value = '请先完成或明确放弃原意图'; return }
  intent.value = { schema: 1, actorId: auth.state.user!.id, blocked: false, kind: 'DECISION', row: { ...row }, command: { version: row.version, status, reason: '' } }
  frozen.value = false; error.value = ''; message.value = ''; await nextTick(); panel.value?.scrollIntoView({ block: 'start', behavior: 'smooth' })
}
async function submit() {
  if (busy.value || unreadable.value || intent.value?.blocked || !canAct.value || !auth.state.user) return
  error.value = ''; message.value = ''
  if (!frozen.value) {
    if (editor.value) {
      const edit = editor.value
      if (!edit.second) { error.value = '请选择对方的未来普通班次'; return }
      intent.value = { schema: 1, actorId: auth.state.user.id, blocked: false, kind: 'REQUEST', first: { ...edit.first }, second: { ...edit.second }, command: {
        firstShiftId: edit.first.id, firstVersion: edit.first.version, secondShiftId: edit.second.id, secondVersion: edit.second.version, requestKey: edit.key, reason: edit.reason.trim() } }
    }
    const captured = intent.value; if (!captured) return
    captured.command.reason = captured.command.reason.trim()
    const invalid = captured.kind === 'REQUEST' ? swapRequestError(captured.command, captured.first, captured.second, captured.actorId) : swapDecisionError(captured.command)
    if (invalid) { error.value = invalid; return }
    try { saveSwapIntent(sessionStorage, captured) }
    catch { error.value = '无法保存重试意图，尚未发送操作；请检查浏览器存储权限'; return }
    frozen.value = true // Persist both source versions/key or original decision before sending anything.
  }
  const captured = intent.value; if (!captured || captured.actorId !== auth.state.user.id) { error.value = '请原账号核对意图'; return }
  const identity = capture(); busy.value = true
  try {
    const result = captured.kind === 'REQUEST' ? await requestSwap(captured.command) : await decideSwap(captured.row.id, captured.command)
    if (!current(identity)) return // A late 200 cannot clear another account's intent or publish private details.
    message.value = `换班 #${result.id} 已确认：${swapState(result, list.value?.databaseNow ?? '')}；当前责任须核对两段覆盖事实`
    try { clearSwapIntent(sessionStorage, captured.actorId); intent.value = null; editor.value = null; frozen.value = false }
    catch { message.value += '；浏览器意图未清除，核对后可明确放弃，不会新建请求' }
    filter.status = ''; pendingRefresh = true
    if (result.status === 'ACCEPTED') emit('changed')
  } catch (cause) {
    if (!current(identity)) return
    if (cause instanceof RequestError && [403,409].includes(cause.status)) {
      captured.blocked = true
      try { saveSwapIntent(sessionStorage, captured) } catch { error.value = '锁定未能保存；刷新后仍须核对原意图。' }
    }
    error.value += `${cause instanceof TypeError ? '响应中断，服务器可能已提交' : cause instanceof Error ? cause.message : '结果未确认'}。${captured.blocked ? '原意图已锁定；不会自动换键/换版本，请核对后明确放弃' : '原键、双版本或决定说明已保留；刷新不自动提交，可手动重试原意图'}`
  } finally { await finish(identity) }
}
async function showDetail(id: number) {
  if (busy.value) return
  const identity = capture(); busy.value = true; detailId.value = id; detail.value = null; error.value = ''
  try { await loadDetail(id, identity) }
  catch (cause) { if (current(identity)) error.value = cause instanceof Error ? cause.message : '两段责任读取失败，旧详情已清空' }
  finally { await finish(identity) }
}
async function pairChanged() { emit('changed'); await refresh() }
async function discard() {
  try { if (auth.state.user) clearSwapIntent(sessionStorage, auth.state.user.id) }
  catch { error.value = '无法清除意图，尚未放弃'; return }
  intent.value = null; editor.value = null; candidates.value = null; frozen.value = false; unreadable.value = false; discardConfirm.value = false
  message.value = '仅放弃此账号本标签页草稿，不撤回服务器申请，也不恢复覆盖；请核对台账'; await refresh()
}
function resetAccount() {
  epoch++; pendingRefresh = false; busy.value = false; list.value = null; options.value = null; detail.value = null; detailId.value = null
  editor.value = null; intent.value = null; candidates.value = null; frozen.value = false; unreadable.value = false; discardConfirm.value = false; error.value = ''; message.value = ''
  filter.scope = canAct.value ? 'MINE' : 'ALL'; filter.status = 'PENDING'; filter.schedule = ''
  if (!auth.state.user) return
  try {
    intent.value = readSwapIntent(sessionStorage, auth.state.user.id); frozen.value = Boolean(intent.value)
    if (intent.value?.kind === 'REQUEST') editor.value = { first: intent.value.first, second: intent.value.second, reason: intent.value.command.reason, key: intent.value.command.requestKey }
    if (intent.value) message.value = '已恢复原意图；刷新只核对，不自动提交，也不更新原版本/键'
  } catch (cause) { unreadable.value = true; error.value = cause instanceof Error ? cause.message : '草稿读取失败' }
  void refresh()
}
onMounted(() => { window.addEventListener('opspilot-auth-session-changed', resetAccount); resetAccount() })
onBeforeUnmount(() => { epoch++; window.removeEventListener('opspilot-auth-session-changed', resetAccount) })
defineExpose({ refresh, open })
</script>

<template>
  <section ref="panel" class="content-panel swap-panel" :aria-busy="busy">
    <div class="panel-heading"><div><h2>双向换班</h2><span>两段未来普通班次 · 对方一次确认 · 原子生成两条覆盖</span></div><button class="secondary-button" :disabled="busy" @click="refresh">刷新换班台账</button></div>
    <div class="swap-body">
      <p class="swap-note">从“班次维护”中本人的未开始普通班次发起，可跨计划交换。原排班保留；管理员不能代替对方同意。输入采用数据库会话时间，不转换浏览器时区。</p>
      <p v-if="error" class="swap-error" role="alert">{{ error }}</p><p v-if="message" role="status">{{ message }}</p>
      <form class="swap-filter" @submit.prevent="refresh">
        <label>换班计划<select v-model="filter.schedule" aria-label="换班计划" :disabled="busy"><option value="">任一计划</option><option v-for="s in options?.schedules" :key="s.id" :value="String(s.id)">{{ s.name }}</option></select></label>
        <label>换班范围<select v-model="filter.scope" aria-label="换班范围" :disabled="busy"><option value="MINE">与我相关</option><option value="ALL">全部请求</option></select></label>
        <label>换班状态<select v-model="filter.status" aria-label="换班状态" :disabled="busy"><option value="">全部状态</option><option value="PENDING">待处理</option><option value="ACCEPTED">已接受</option><option value="REJECTED">已拒绝</option><option value="WITHDRAWN">已撤回</option></select></label>
        <button class="secondary-button" :disabled="busy">查询换班</button>
      </form>
      <form v-if="editor" class="swap-editor swap-request-editor" @submit.prevent="submit">
        <h3>确认交换两段责任</h3><div class="swap-pair"><article><h4>本人让出 → 对方接手</h4><p>{{ editor.first.scheduleName }} · #{{ editor.first.id }} · v{{ editor.first.version }} · {{ editor.first.userName }}</p><p>{{ swapClock(editor.first.startsAt) }} → {{ swapClock(editor.first.endsAt) }}</p></article><article><h4>对方让出 → 本人接手</h4><template v-if="editor.second"><p>{{ editor.second.scheduleName }} · #{{ editor.second.id }} · v{{ editor.second.version }} · {{ editor.second.userName }}</p><p>{{ swapClock(editor.second.startsAt) }} → {{ swapClock(editor.second.endsAt) }}</p></template><p v-else>先查询并选择对方班次</p></article></div>
        <fieldset v-if="!frozen" :disabled="busy"><legend>对方班次查询 · 最多31天</legend><div class="swap-fields"><label>候选计划<select v-model="windowFilter.schedule" aria-label="候选计划"><option value="">全部计划</option><option v-for="s in options?.schedules" :key="s.id" :value="String(s.id)">{{ s.name }}</option></select></label><label>候选开始<input v-model="windowFilter.from" type="datetime-local" required></label><label>候选结束<input v-model="windowFilter.to" type="datetime-local" required></label><button type="button" class="secondary-button" @click="chooseCandidates">查询可交换班次</button></div><label>对方未来班次<select v-model="windowFilter.secondId" aria-label="对方未来班次" @change="chooseSecond"><option value="">请选择不同负责人</option><option v-for="s in choices" :key="s.id" :value="String(s.id)">{{ s.userName }} · {{ s.scheduleName }} · #{{ s.id }} · {{ swapClock(s.startsAt) }}</option></select></label><p v-if="candidates?.truncated" role="status">候选结果截断，请缩小计划或窗口；不会假定其他班次不存在。</p><p v-if="candidates && !choices.length" class="swap-note">此窗口没有其他人的未来普通班次，请调整窗口。最新资格/重叠由提交事务再次检查。</p></fieldset>
        <label>换班申请说明<textarea v-model="editor.reason" required maxlength="500" rows="2" :disabled="busy || frozen || !canAct"></textarea></label><p class="swap-note">原请求键：{{ editor.key }}。首次发送前保存双版本及内容；刷新不自动发送。</p><p v-if="frozen" class="swap-note">原申请内容已冻结；仅手动重试相同键、双版本与说明。明确放弃不会撤回服务器申请。</p>
        <div class="swap-actions"><button class="primary-button" :disabled="busy || !canAct || intent?.blocked">{{ frozen ? '重试原换班申请' : '提交双向换班申请' }}</button><button type="button" class="secondary-button" :disabled="busy" @click="discardConfirm = true">放弃换班草稿</button></div>
      </form>
      <form v-if="intent?.kind === 'DECISION'" class="swap-editor swap-decision-editor" @submit.prevent="submit">
        <h3>{{ actionName(intent.command.status) }} #{{ intent.row.id }} · 捕获v{{ intent.command.version }}</h3><div class="swap-pair"><article><h4>{{ userName(intent.row.requesterId) }} → {{ userName(intent.row.targetUserId) }}</h4><p>{{ planName(intent.row.firstScheduleId) }} · #{{ intent.row.firstShiftId }}</p><p>{{ swapClock(intent.row.firstStartsAt) }} → {{ swapClock(intent.row.firstEndsAt) }}</p></article><article><h4>{{ userName(intent.row.targetUserId) }} → {{ userName(intent.row.requesterId) }}</h4><p>{{ planName(intent.row.secondScheduleId) }} · #{{ intent.row.secondShiftId }}</p><p>{{ swapClock(intent.row.secondStartsAt) }} → {{ swapClock(intent.row.secondEndsAt) }}</p></article></div>
        <p class="swap-note">接受将一起交换两段责任，不改原普通班次；任一源变化或审计失败则整笔回滚。决定说明与原版本发送后冻结，刷新不会换版本。</p><label>换班决定说明<textarea v-model="intent.command.reason" required maxlength="500" rows="2" :disabled="busy || frozen || !canAct"></textarea></label><div class="swap-actions"><button class="primary-button" :disabled="busy || !canAct || intent.blocked">{{ frozen ? '重试原换班决定' : '确认换班决定' }}</button><button type="button" class="secondary-button" :disabled="busy" @click="discardConfirm = true">放弃换班草稿</button></div>
      </form>
      <p v-if="intent?.blocked" class="swap-error" role="alert">原意图已锁定，不能重试；先刷新核对，再明确放弃后重新选择。</p>
      <button v-if="unreadable" class="secondary-button" :disabled="busy" @click="discardConfirm = true">核对后放弃损坏换班草稿</button>
      <div v-if="discardConfirm" class="swap-editor" role="group" aria-label="明确放弃换班草稿"><p>仅清除本标签页原意图，不撤回服务器请求、不恢复已取消覆盖。是否已提交请先核对台账。</p><div class="swap-actions"><button class="secondary-button" :disabled="busy" @click="discard">确认仅放弃本地草稿</button><button class="secondary-button" @click="discardConfirm = false">保留原意图</button></div></div>
      <p v-if="list?.truncated" role="status">已先按双方/任一计划/状态筛选，仅显示最近200条，请缩小筛选。</p>
      <div v-if="list?.requests.length" class="swap-list"><article v-for="row in list.requests" :key="row.id" class="swap-row" :data-swap-id="row.id"><header><strong>#{{ row.id }} · {{ userName(row.requesterId) }} ⇄ {{ userName(row.targetUserId) }}</strong><span class="status-badge" :class="row.status === 'PENDING' ? 'status-warning' : 'status-info'">{{ swapState(row,list.databaseNow) }}</span></header><div class="swap-pair"><p>{{ planName(row.firstScheduleId) }} · #{{ row.firstShiftId }} · {{ swapClock(row.firstStartsAt) }} → {{ swapClock(row.firstEndsAt) }}</p><p>{{ planName(row.secondScheduleId) }} · #{{ row.secondShiftId }} · {{ swapClock(row.secondStartsAt) }} → {{ swapClock(row.secondEndsAt) }}</p></div><p>{{ row.reason }}</p><p v-if="row.decisionReason" class="swap-note">原决定：{{ row.decisionReason }} · v{{ row.version }}</p><div class="swap-actions"><button v-for="action in actions(row)" :key="action" class="secondary-button" :disabled="busy" @click="chooseDecision(row,action)">{{ actionName(action) }}</button><button class="secondary-button" :disabled="busy" @click="showDetail(row.id)">核对两段责任</button></div></article></div>
      <p v-else-if="list" class="empty-state">此筛选下没有换班请求；可从下方本人未来普通班次发起。</p>
      <section v-if="detailId" class="swap-editor swap-detail"><div class="swap-actions"><h3>两段责任事实 #{{ detailId }}</h3><button class="secondary-button" :disabled="busy" @click="showDetail(detailId)">刷新两段事实</button><button class="secondary-button" :disabled="busy" @click="detailId = null; detail = null">关闭换班详情</button></div><template v-if="detail"><p>{{ swapState(detail.row,detail.first.databaseNow) }}；原决定保留，当前区间责任来自coverage查询。两次区间读取可能处于不同快照。</p><p class="swap-note">原覆盖ID {{ detail.row.firstReplacementShiftId ?? '未生成' }} / {{ detail.row.secondReplacementShiftId ?? '未生成' }}。任一覆盖可被班次维护独立取消；管理成对撤销请在下方独立面板核对；不保证原负责人仍能恢复。</p><div class="swap-pair"><article v-for="(view,index) in [detail.first,detail.second]" :key="index"><h4>{{ index ? '第二段' : '第一段' }} · 数据库快照 {{ swapClock(view.databaseNow) }}</h4><p v-for="(segment,i) in view.segments" :key="i">{{ segment.userName ?? '无可用负责人' }} · {{ segment.userId ? (segment.override ? '临时覆盖' : '普通班次') : segment.gapReason }}<br>{{ swapClock(segment.startsAt) }} → {{ swapClock(segment.endsAt) }}</p></article></div></template><p v-else class="swap-note">详情尚未取得或查询失败；不显示旧责任事实。</p></section>
      <p v-if="!canAct" class="swap-note">当前账号只读，不能申请或决定；后端仍会验证最新角色与实际参与者。</p>
      <OnCallSwapRevocationPanel v-if="detailId" :key="detailId" :swap-id="detailId" @changed="pairChanged" />
      <OnCallSwapNotificationPanel v-if="detail" :key="detail.row.id" :swap-id="detail.row.id" :requester="detail.row.requesterId" :target="detail.row.targetUserId" />
    </div>
  </section>
</template>

<style scoped>
.swap-panel { margin-bottom:18px; scroll-margin-top:84px; }
.swap-body { padding:0 20px 20px; }
.swap-note { color:var(--text-muted); font-size:12px; line-height:1.7; overflow-wrap:anywhere; }
.swap-error { color:#b42318; overflow-wrap:anywhere; }
.swap-filter,.swap-fields { display:grid; grid-template-columns:repeat(3,minmax(0,1fr)) auto; gap:12px; align-items:end; margin:16px 0; }
.swap-pair { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:12px; margin:12px 0; }
.swap-pair article { padding:12px; background:var(--surface); border:1px solid var(--line); border-radius:8px; }
.swap-pair p,.swap-row p { font-size:12px; line-height:1.7; overflow-wrap:anywhere; margin:6px 0; }
.swap-editor { padding:16px; border:1px solid var(--line); border-radius:8px; margin:16px 0; scroll-margin-top:84px; }
h3,h4 { font-size:14px; margin:0 0 10px; }
label { display:flex; flex-direction:column; gap:6px; font-size:12px; min-width:0; }
input,select,textarea { width:100%; min-width:0; box-sizing:border-box; padding:9px; border:1px solid var(--line); background:var(--surface); color:inherit; border-radius:6px; font:inherit; }
fieldset { border:1px solid var(--line); padding:12px; border-radius:6px; margin:12px 0; min-width:0; }
legend { font-size:12px; }
.swap-actions { display:flex; flex-wrap:wrap; gap:8px; align-items:center; margin-top:12px; }
.swap-row { padding:16px 0; border-top:1px solid var(--line); }
.swap-row header { display:flex; justify-content:space-between; gap:10px; flex-wrap:wrap; font-size:13px; }
@media(max-width:900px) { .swap-filter,.swap-fields { grid-template-columns:repeat(2,minmax(0,1fr)); } }
@media(max-width:640px) { .swap-filter,.swap-fields,.swap-pair { grid-template-columns:1fr; } .swap-body { padding:0 14px 14px; } .swap-editor { padding:12px; } }
</style>

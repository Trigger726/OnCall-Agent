<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { RequestError } from '@/services/api'
import { auth } from '@/stores/auth'
import { canRevokeCoverage, clearRevocationDraft, coverageState, getHandoffCoverage, handoffClock, handoffState,
  readRevocationDraft, revocationCommandError, revokeHandoffCoverage, saveRevocationDraft,
  type HandoffCoverage, type SavedRevocationDraft } from '@/services/onCallHandoffs'

const props = defineProps<{ users: { id: number; displayName: string }[] }>()
const emit = defineEmits<{ changed: [] }>()
const panel = ref<HTMLElement | null>(null)
const id = ref<number | null>(null)
const view = ref<HandoffCoverage | null>(null)
const busy = ref(false)
const draft = ref<SavedRevocationDraft | null>(null)
const frozen = ref(false)
const unreadable = ref(false)
const discardConfirm = ref(false)
const error = ref('')
const message = ref('')
const manager = computed(() => ['ADMIN','OPS_MANAGER'].includes(auth.state.user?.roleCode ?? ''))
const userName = (value: number) => props.users.find(u => u.id === value)?.displayName ?? `用户 #${value}`
let pendingRefresh = false

async function refresh() {
  if (!id.value) return
  if (busy.value) { pendingRefresh = true; return }
  const capturedId = id.value, actorId = auth.state.user?.id
  busy.value = true
  view.value = null // No actionable stale snapshot after a failed refresh.
  error.value = ''
  try {
    const result = await getHandoffCoverage(capturedId)
    if (actorId !== auth.state.user?.id || capturedId !== id.value) throw new Error('身份或详情已变化，请重新打开')
    view.value = result // Do not update a captured command's versions or key.
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '覆盖详情加载失败' }
  finally { busy.value = false; if (pendingRefresh) { pendingRefresh = false; await refresh() } }
}
async function open(handoffId: number) {
  if (busy.value) return
  if (draft.value || unreadable.value) { error.value = '请先完成或明确放弃原撤销草稿，不会切换请求或换键'; return }
  id.value = handoffId; message.value = ''; discardConfirm.value = false
  await refresh(); await nextTick()
  panel.value?.scrollIntoView({ behavior:'smooth', block:'start' })
}
function begin() {
  const current = view.value, actorId = auth.state.user?.id
  if (busy.value || draft.value || unreadable.value || !actorId || !current || !canRevokeCoverage(current, auth.state.user?.roleCode)) return
  draft.value = { schema:1, actorId, handoffId:current.request.id, blocked:false, command:{
    handoffVersion:current.request.version, replacementVersion:current.replacement!.version, operationKey:crypto.randomUUID(), reason:'' } }
  frozen.value = false; error.value = ''; message.value = ''
}
async function submit() {
  const captured = draft.value
  if (!captured || busy.value || captured.blocked) return
  error.value = ''; message.value = ''
  if (!manager.value || captured.actorId !== auth.state.user?.id) { error.value = '当前身份不是原管理操作人；须原账号具有管理资格'; return }
  if (!frozen.value) {
    if (!view.value || !canRevokeCoverage(view.value, auth.state.user?.roleCode)) { error.value = '请重新核对未结束的覆盖事实'; return }
    const command = { ...captured.command, reason:captured.command.reason.trim() }
    const invalid = revocationCommandError(command)
    if (invalid) { error.value = invalid; return }
    try { saveRevocationDraft(sessionStorage, { ...captured, command }) }
    catch { error.value = '无法保存撤销重试草稿，尚未发送操作；请检查浏览器存储权限'; return }
    captured.command = command; frozen.value = true
  }
  busy.value = true
  try {
    const result = await revokeHandoffCoverage(captured.handoffId, captured.command)
    if (captured.actorId !== auth.state.user?.id) throw new Error('身份已变化，请原账号核对覆盖事实')
    view.value = result
    message.value = `覆盖班次 #${result.replacement?.id} 撤销已确认；原接受事实保留。责任归属须核对覆盖日历，不保证原班次仍可接续`
    emit('changed')
    try { clearRevocationDraft(sessionStorage, captured.actorId); draft.value = null; frozen.value = false }
    catch { message.value += '；浏览器草稿未能清除，原意图保留，可核对后明确放弃' }
  } catch (cause) {
    if (cause instanceof RequestError && [409,403].includes(cause.status)) {
      captured.blocked = true
      try { saveRevocationDraft(sessionStorage,captured) }
      catch { error.value = '冲突锁定未能保存，刷新后仍须核对原意图；' }
    }
    error.value += `${cause instanceof TypeError ? '网络响应中断，服务器可能已完成撤销' : cause instanceof Error ? cause.message : '无法确认撤销结果'}。${captured.blocked ? '原意图已锁定；刷新仅核对事实，不会换版本/换键提交。核对后明确放弃再重新选择' : '原操作键、双版本和说明已保留；可刷新核对后手动重试原撤销'}`
  } finally { busy.value = false; if (pendingRefresh) { pendingRefresh = false; await refresh() } }
}
async function discard() {
  const actorId = draft.value?.actorId ?? auth.state.user?.id
  try { if (actorId) clearRevocationDraft(sessionStorage,actorId) }
  catch { error.value = '无法清除撤销草稿，尚未放弃'; return }
  draft.value = null; frozen.value = false; unreadable.value = false; discardConfirm.value = false
  message.value = '已明确放弃本地撤销草稿；不恢复服务器上可能已取消的覆盖。新操作须重新核对详情'
  await refresh()
}
onMounted(async () => {
  try {
    if (auth.state.user) draft.value = readRevocationDraft(sessionStorage,auth.state.user.id)
    if (draft.value) {
      id.value = draft.value.handoffId; frozen.value = true
      message.value = draft.value.blocked ? '已恢复锁定的原撤销意图；刷新仅核对事实，须明确放弃后重新选择，不会自动提交'
        : '已恢复原撤销键、双版本及说明；不会自动提交。先核对事实，再按需手动重试'
    }
  } catch { unreadable.value = true; error.value = '撤销草稿读取失败或损坏，未发送任何操作；请核对台账后明确放弃' }
  await refresh()
})
defineExpose({ open, refresh })
</script>

<template>
  <section v-if="id || unreadable" ref="panel" class="handoff-coverage" :aria-busy="busy">
    <div class="coverage-heading"><h3>接班覆盖详情 <span v-if="id">#{{ id }}</span></h3><div class="coverage-actions"><button class="secondary-button" :disabled="busy" @click="refresh">刷新覆盖事实</button><button v-if="!draft && !unreadable" class="secondary-button" :disabled="busy" @click="id = null; view = null">关闭覆盖详情</button></div></div>
    <p v-if="error" class="coverage-error" role="alert">{{ error }}</p><p v-if="message" role="status">{{ message }}</p>
    <template v-if="view">
      <p class="coverage-note">数据库快照 {{ handoffClock(view.databaseNow) }}；原接受决定与后续覆盖取消是独立事实，不重写过去责任。</p>
      <div class="coverage-facts">
        <article><h4>原接班决定 · {{ handoffState(view.request,view.databaseNow) }}</h4><p>{{ userName(view.request.requesterId) }} → {{ userName(view.request.targetUserId) }} · 请求 v{{ view.request.version }}</p><p>原班次 #{{ view.request.sourceShiftId }} · {{ view.request.reason }}</p><p v-if="view.request.decidedAt">{{ handoffClock(view.request.decidedAt) }} · {{ view.request.decisionReason }}</p></article>
        <article><h4>{{ coverageState(view) }}</h4><template v-if="view.replacement"><p>覆盖班次 #{{ view.replacement.id }} · {{ userName(view.replacement.userId) }} · v{{ view.replacement.version }}</p><p>{{ handoffClock(view.replacement.startsAt) }} → {{ handoffClock(view.replacement.endsAt) }}</p><p v-if="view.replacement.cancelledAt">取消时间 {{ handoffClock(view.replacement.cancelledAt) }} · {{ view.replacement.cancellationReason }}</p></template></article>
      </div>
      <article v-if="view.revocation" class="revocation-fact"><h4>独立管理撤销记录</h4><p>操作人 {{ userName(view.revocation.actorId) }} · {{ handoffClock(view.revocation.revokedAt) }}</p><p>{{ view.revocation.reason }}</p><p class="coverage-note">捕获请求 v{{ view.revocation.handoffVersion }} / 覆盖 v{{ view.revocation.replacementVersion }} · 原操作键 {{ view.revocation.operationKey }}</p></article>
      <p v-else-if="view.replacement?.cancelledAt" class="coverage-note">覆盖已由班次维护取消，无独立接班撤销记录；不会虚构撤销意图。</p>
      <p class="coverage-note">是否实际生效请核对下方覆盖日历。原班次或计划也可能已停用，撤销不承诺自动恢复原负责人。</p>
      <button v-if="!draft && !unreadable && canRevokeCoverage(view,auth.state.user?.roleCode)" class="secondary-button" :disabled="busy" @click="begin">撤销接班覆盖</button>
      <p v-if="!manager" class="coverage-note">当前账号只读；仅当前活跃管理员/运维经理可撤销已接受覆盖，接班人不能单方解除责任。</p>
    </template>
    <form v-if="draft" class="revocation-editor" @submit.prevent="submit">
      <h4>管理撤销确认 #{{ draft.handoffId }}</h4><p>管理人 #{{ draft.actorId }} · 捕获请求 v{{ draft.command.handoffVersion }} / 覆盖 v{{ draft.command.replacementVersion }}</p>
      <label>撤销说明<textarea v-model="draft.command.reason" aria-label="撤销说明" required maxlength="500" rows="2" :disabled="busy || frozen || !manager"></textarea></label>
      <p class="coverage-note">原撤销键：{{ draft.command.operationKey }}</p><p v-if="frozen" class="coverage-note">原意图已按账号保存在此标签页；刷新不会换键或双版本。关闭标签页会丢失浏览器草稿，先核对覆盖事实。</p>
      <p v-if="draft.blocked" role="alert">原撤销意图已锁定，不能重试；刷新仅核对，须明确放弃后重新选择。</p>
      <div class="coverage-actions"><button v-if="manager" class="primary-button" :disabled="busy || draft.blocked || (!frozen && !view)">{{ frozen ? '重试原撤销' : '确认撤销覆盖' }}</button><button type="button" class="secondary-button" :disabled="busy" @click="discardConfirm = true">放弃撤销草稿</button></div>
    </form>
    <button v-if="unreadable" class="secondary-button" :disabled="busy" @click="discardConfirm = true">核对事实后放弃损坏撤销草稿</button>
    <div v-if="discardConfirm" class="revocation-editor" role="alert"><p>操作可能已提交。放弃本地草稿不恢复覆盖；请先核对事实，新操作会使用新键。</p><div class="coverage-actions"><button class="secondary-button" :disabled="busy" @click="discard">确认放弃撤销草稿</button><button class="secondary-button" :disabled="busy" @click="discardConfirm = false">保留撤销草稿</button></div></div>
  </section>
</template>

<style scoped>
.handoff-coverage { margin:16px 0; padding:16px; border:1px solid var(--line); border-radius:8px; scroll-margin-top:85px; }
.coverage-heading { display:flex; align-items:start; justify-content:space-between; gap:12px; flex-wrap:wrap; }
h3 { margin:0; font-size:14px; } h4 { margin:0 0 8px; font-size:13px; }
p { font-size:12px; line-height:1.6; overflow-wrap:anywhere; }
.coverage-note { color:var(--text-muted); } .coverage-error { color:#b42318; }
.coverage-facts { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:12px; margin:16px 0; }
.coverage-facts article, .revocation-fact, .revocation-editor { padding:12px; border:1px solid var(--line); border-radius:6px; min-width:0; margin:12px 0; }
.coverage-facts article { margin:0; }
.coverage-actions { display:flex; flex-wrap:wrap; gap:8px; }
label { display:grid; gap:6px; font-size:12px; }
textarea { width:100%; min-width:0; box-sizing:border-box; border:1px solid var(--line); background:var(--surface); color:inherit; border-radius:6px; padding:9px; font:inherit; }
@media(max-width:640px) { .coverage-facts { grid-template-columns:1fr; } .handoff-coverage { padding:12px; } }
</style>

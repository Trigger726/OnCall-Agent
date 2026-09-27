<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { api } from '@/services/api'
import { capturePublicationIntent, readPublicationIntent, persistPublicationIntent, discardPublicationIntent,
  sendPublicationDecision, isPublicationIntentRejected, type PublicationIntent, type PublicationDecision } from '@/services/runbookPublications'
import { auth } from '@/stores/auth'

const props = defineProps<{ refreshToken: number }>()
const emit = defineEmits<{ changed: [] }>()
interface Item { id: number; stableKey: string; versionNo: number; title: string; status: string; createdBy: number | null }
interface Review { document: Item & { markdown: string; allowedRoles: string[] }; reviewVersion: number; basePublishedVersion: number; currentPublishedVersion: number; reviewNote: string | null }
const actor = auth.state.user!.id
const queue = ref<{ items: Item[]; total: number; truncated: boolean } | null>(null)
const detail = ref<Review | null>(null)
const intent = ref<PublicationIntent | null>(null)
const status = ref('PENDING_REVIEW'), decision = ref<PublicationDecision>('APPROVE'), reason = ref('')
const recoveryError = ref('')
const busy = ref(false), error = ref(''), notice = ref('')
const own = computed(() => detail.value?.document.createdBy === actor)
const canStart = computed(() => detail.value?.document.status === 'PENDING_REVIEW' && !intent.value && !recoveryError.value)

async function refresh() {
  busy.value = true; error.value = ''; queue.value = null
  try { queue.value = await api(`/runbooks/publications?status=${status.value}`) }
  catch (caught) { error.value = caught instanceof Error ? caught.message : '读取审核台账失败' }
  finally { busy.value = false }
}
async function select(id: number) {
  if (intent.value && id !== intent.value.id) { error.value = '请先处理或放弃当前冻结意图'; return }
  busy.value = true; error.value = ''; detail.value = null
  try {
    detail.value = await api(`/runbooks/publications/${id}`)
    if (!intent.value) { decision.value = own.value ? 'WITHDRAW' : 'APPROVE'; reason.value = '' }
  } catch (caught) { error.value = caught instanceof Error ? caught.message : '读取候选失败' }
  finally { busy.value = false }
}
function persist(value: PublicationIntent) {
  persistPublicationIntent(sessionStorage, value) // Persist BEFORE the first POST; fail closed if storage is unavailable.
  intent.value = value
}
async function submit() {
  if (!detail.value || intent.value?.locked || recoveryError.value) return
  error.value = ''; notice.value = ''
  try {
    if (!intent.value) persist(capturePublicationIntent(actor, detail.value.document.id,
      detail.value.reviewVersion, decision.value, reason.value))
  } catch { error.value = '无法保存冻结意图，本次未提交'; return }
  const frozen = intent.value!
  busy.value = true
  try {
    const confirmed = await sendPublicationDecision<Review>(frozen, auth.state.user?.id ?? 0)
    detail.value = confirmed
    discardPublicationIntent(sessionStorage, actor); intent.value = null
    notice.value = `已确认：${confirmed.document.status}；决定已进入审计`
    emit('changed')
    await refresh()
  } catch (caught) {
    if (isPublicationIntentRejected(caught)) {
      const locked = { ...frozen, locked: true }
      intent.value = locked
      try { persist(locked) } catch { recoveryError.value = '拒绝已锁定，但无法保存锁；请核对后放弃本地意图，不会自动重提' }
    }
    error.value = caught instanceof Error ? caught.message : '提交结果未知；请手动同键重试'
  } finally { busy.value = false }
}
function abandon() {
  if (!window.confirm('放弃本地冻结意图不会撤销服务端已发生的决定。请先核对候选状态，确认放弃？')) return
  try {
    discardPublicationIntent(sessionStorage, actor)
    intent.value = null; detail.value = null; error.value = ''; recoveryError.value = ''; reason.value = ''
  } catch { recoveryError.value = '无法删除本地冻结意图，仍禁止新提交' }
}
onMounted(async () => {
  try {
    intent.value = readPublicationIntent(sessionStorage, actor)
  } catch (caught) { recoveryError.value = caught instanceof Error ? caught.message : '本地冻结意图无法读取，禁止新提交' }
  await refresh()
  if (intent.value) await select(intent.value.id)
})
watch(() => props.refreshToken, refresh)
</script>

<template>
  <section class="panel publication-queue" aria-label="Runbook 独立复核发布" :aria-busy="busy">
    <h2>Runbook 独立复核发布</h2>
    <p>导入只产生待审版本。另一管理账号批准后才进入控制台与 Agent 检索；拒绝、撤回不影响旧发布版。</p>
    <div class="publication-controls"><label>候选状态 <select v-model="status" :disabled="busy"><option>PENDING_REVIEW</option><option>PUBLISHED</option><option>SUPERSEDED</option><option>REJECTED</option><option>WITHDRAWN</option></select></label><button class="secondary-button" :disabled="busy" @click="refresh">刷新审核台账</button></div>
    <p v-if="error" role="alert" class="publication-error">{{ error }}</p>
    <p v-if="recoveryError" role="alert" class="publication-error">{{ recoveryError }}</p>
    <p v-if="notice" role="status">{{ notice }}</p>
    <div v-if="intent || recoveryError" class="publication-recovery">
      <p v-if="intent">冻结：#{{ intent.id }} / 审核版本 {{ intent.expectedVersion }} / {{ intent.decision }} / {{ intent.reason }}<br />{{ intent.locked ? '冲突或权限拒绝已锁定，不会自动换版本重提。' : '结果未确认：刷新不会自动提交，重试保留原版本、请求键与说明。' }}</p>
      <div class="publication-controls"><button v-if="intent" class="secondary-button" :disabled="busy" @click="select(intent.id)">核对候选最新状态</button><button class="secondary-button" :disabled="busy" @click="abandon">核对后放弃本地意图</button></div>
    </div>
    <div v-if="queue" class="publication-items">
      <p>{{ queue.total }} 个候选<span v-if="queue.truncated">（仅展示最早 200 条，请按状态处理；不是完整台账）</span></p>
      <p v-if="!queue.items.length">此状态无候选</p>
      <button v-for="item in queue.items" :key="item.id" class="secondary-button publication-item" :disabled="busy" @click="select(item.id)">{{ item.stableKey }} · v{{ item.versionNo }} · {{ item.title }}</button>
    </div>
    <div v-if="detail" class="publication-detail">
      <h3>{{ detail.document.stableKey }} · v{{ detail.document.versionNo }}</h3>
      <p>状态 {{ detail.document.status }} · 提交人 #{{ detail.document.createdBy }} · 审核版本 {{ detail.reviewVersion }}</p>
      <p>提交基线 v{{ detail.basePublishedVersion }} / 当前发布 v{{ detail.currentPublishedVersion }} · 可见角色 {{ detail.document.allowedRoles.join(' / ') }}</p>
      <pre>{{ detail.document.markdown }}</pre>
      <p v-if="detail.reviewNote">复核说明：{{ detail.reviewNote }}</p>
      <form v-if="canStart || intent" @submit.prevent="submit">
        <fieldset :disabled="busy || !!intent">
          <label>发布决定 <select v-model="decision"><option v-if="!own" value="APPROVE">批准发布</option><option v-if="!own" value="REJECT">拒绝候选</option><option v-if="own" value="WITHDRAW">撤回候选</option></select></label>
          <label>复核说明 <textarea v-model="reason" required maxlength="500" placeholder="确认前置条件、权限、恢复及验证步骤；请勿填写凭证" /></label>
        </fieldset>
        <p v-if="own">本人不能审批自己的候选，只能撤回。</p>
        <div class="publication-controls"><button class="primary-button" :disabled="busy || intent?.locked || !!recoveryError || (!intent && !reason.trim())">{{ intent ? '同键重试原决定' : '确认提交决定' }}</button></div>
      </form>
    </div>
  </section>
</template>

<style scoped>
.publication-queue { padding: 20px; margin-bottom: 16px; }
h2 { font-size: 17px; margin: 0 0 12px; }
p { color: var(--text-muted); line-height: 1.6; overflow-wrap: anywhere; }
.publication-controls { display: flex; gap: 12px; align-items: center; flex-wrap: wrap; }
select, textarea { padding: 8px; color: var(--text); background: var(--surface); border: 1px solid var(--line); border-radius: 6px; max-width: 100%; }
.publication-items { display: grid; gap: 8px; max-height: 300px; overflow-y: auto; }
.publication-item { justify-content: flex-start; text-align: left; white-space: normal; overflow-wrap: anywhere; }
.publication-detail { margin-top: 16px; border-top: 1px solid var(--line); padding-top: 12px; }
pre { white-space: pre-wrap; overflow-wrap: anywhere; max-height: 300px; overflow-y: auto; padding: 12px; background: var(--surface-muted); }
fieldset { border: 0; padding: 0; display: grid; gap: 12px; }
fieldset label { display: grid; gap: 6px; }
textarea { min-height: 90px; width: 100%; box-sizing: border-box; }
.publication-error { color: #ef4444; }
@media (max-width: 600px) { .publication-queue { padding: 14px; } .publication-controls > * { width: 100%; } }
</style>

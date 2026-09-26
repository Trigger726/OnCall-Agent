<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { api, RequestError } from '@/services/api'
import { auth } from '@/stores/auth'
import { canManageRotations, createRotation, draftError, listRotations, readSlots, rotationClock, rotationWarning,
  scanRotations, setRotationState, slotState, type Rotation, type RotationOptions, type SlotWindow } from '@/services/onCallRotations'

const emit = defineEmits<{ changed: [] }>()
const canManage = computed(() => canManageRotations(auth.state.user?.roleCode))
const options = ref<RotationOptions | null>(null)
const rotations = ref<Rotation[]>([])
const listTruncated = ref(false)
const selectedId = ref(0)
const selected = computed(() => rotations.value.find(item => item.id === selectedId.value))
const slots = ref<SlotWindow | null>(null)
const busy = ref(false)
const error = ref('')
const message = ref('')
const showCreate = ref(false)
const stateTarget = ref<Rotation | null>(null)
const stateReason = ref('')
const stateStale = ref(false)
const filter = reactive({ scheduleId: '', from: '', to: '' })
const draft = reactive({ scheduleId: 0, name: '', anchorAt: '', shiftMinutes: 480, members: [] as number[] })
const memberToAdd = ref(0)
const availableMembers = computed(() => options.value?.users.filter(item => !draft.members.includes(item.id)) ?? [])
const memberName = (id: number) => options.value?.users.find(item => item.id === id)?.displayName ?? `用户 #${id}（当前不可选）`
const scheduleName = (id: number) => options.value?.schedules.find(item => item.id === id)?.name ?? `计划 #${id}（当前不可选）`

async function loadSlots(reset = false) {
  slots.value = null
  if (!selectedId.value) return
  const result = await readSlots(selectedId.value, reset ? '' : filter.from, reset ? '' : filter.to)
  slots.value = result
  filter.from = result.from.slice(0, 16)
  filter.to = result.to.slice(0, 16)
}
async function load(reset = false) {
  const [context, result] = await Promise.all([api<RotationOptions>('/on-call/roster'), listRotations(filter.scheduleId)])
  options.value = context
  rotations.value = result.rotations
  listTruncated.value = result.truncated
  if (!draft.scheduleId) draft.scheduleId = context.schedules[0]?.id ?? 0
  if (!draft.anchorAt) draft.anchorAt = context.suggestedStart.slice(0, 16)
  if (!result.rotations.some(item => item.id === selectedId.value)) {
    selectedId.value = result.rotations[0]?.id ?? 0
    reset = true
  }
  await loadSlots(reset)
}
async function run(action: () => Promise<void>) {
  busy.value = true
  error.value = ''
  try { await action() }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '轮转操作失败' }
  finally { busy.value = false }
}
async function refresh(reset = false) { await run(() => load(reset)) }
async function refreshAll() { await refresh(); emit('changed') }
function moveMember(index: number, direction: number) {
  const other = index + direction
  if (other < 0 || other >= draft.members.length) return
  const member = draft.members[index]!
  draft.members[index] = draft.members[other]!
  draft.members[other] = member
}
function addMember() {
  if (memberToAdd.value && !draft.members.includes(memberToAdd.value) && draft.members.length < 20) {
    draft.members.push(memberToAdd.value)
    memberToAdd.value = 0
  }
}
async function save() {
  const invalid = draftError(draft)
  if (invalid) { error.value = invalid; return }
  message.value = ''
  await run(async () => {
    const created = await createRotation(draft)
    selectedId.value = created.id
    filter.scheduleId = String(created.scheduleId)
    showCreate.value = false
    message.value = '轮转已创建；请核对生成/受阻台账，冲突不会覆盖手工班次'
    await load(true)
    emit('changed')
  })
}
function openState() {
  if (!selected.value) return
  showCreate.value = false
  stateTarget.value = { ...selected.value, members: [...selected.value.members] }
  stateReason.value = ''
  stateStale.value = false
  message.value = ''
}
async function changeState() {
  if (!stateTarget.value || stateStale.value) return
  message.value = ''
  await run(async () => {
    try { await setRotationState(stateTarget.value!, stateReason.value) }
    catch (cause) {
      if (cause instanceof RequestError && cause.code === 'ONCALL_ROTATION_VERSION_CONFLICT') stateStale.value = true
      throw cause
    }
    message.value = stateTarget.value!.active ? '已暂停续排；既有班次不变，可在班次维护单独取消' : '已恢复续排；既有取消时段不会自动重建'
    stateTarget.value = null
    await load()
    emit('changed')
  })
}
async function scan() {
  message.value = ''
  await run(async () => {
    const result = await scanRotations()
    message.value = `扫描 ${result.rotations} 条轮转，新生成 ${result.createdShifts} 班、受阻 ${result.blockedSlots} 时段`
      + (result.failedRotations.length ? `；失败规则 #${result.failedRotations.join('、#')}（已回滚，其他规则继续）` : '')
    await load()
    emit('changed')
  })
}
onMounted(() => refresh())
defineExpose({ refresh })
</script>

<template>
  <section class="content-panel rotation-panel" :aria-busy="busy">
    <div class="panel-heading">
      <div><h2>轮转与自动续排</h2><span>有序成员 · 默认分钟扫描 · 提前 14 天生成普通班次</span></div>
      <div class="rotation-actions">
        <button class="secondary-button" :disabled="busy" @click="refreshAll">刷新轮转</button>
        <button v-if="canManage" class="primary-button" :disabled="busy || Boolean(stateTarget) || !options?.schedules.length" @click="showCreate = !showCreate">{{ showCreate ? '收起轮转表单' : '新增轮转' }}</button>
      </div>
    </div>
    <div class="rotation-body">
      <p v-if="options" class="rotation-hint">数据库会话时间：{{ rotationClock(options.databaseNow) }}。锚点按此时间输入，不按浏览器时区换算；当前不支持跨时区/DST。</p>
      <p v-if="!canManage" class="rotation-hint">只读轮转台账：创建、暂停/恢复与立即续排限管理员或运维经理。</p>
      <p v-if="error" class="rotation-error" role="alert">{{ error }}</p>
      <p v-if="message" role="status">{{ message }}</p>
      <form v-if="showCreate && canManage && options" class="rotation-editor" @submit.prevent="save">
        <h3>创建轮转规则</h3>
        <div class="rotation-fields">
          <label>轮转计划<select v-model.number="draft.scheduleId" aria-label="轮转计划" required :disabled="busy"><option v-for="item in options.schedules" :key="item.id" :value="item.id">{{ item.resourceName }} · {{ item.name }}</option></select></label>
          <label>轮转名称<input v-model="draft.name" required maxlength="128" :disabled="busy" placeholder="例如：平台两人八小时轮值"></label>
          <label>轮转锚点<input v-model="draft.anchorAt" type="datetime-local" step="60" required :disabled="busy"></label>
          <label>每班分钟数<input v-model.number="draft.shiftMinutes" type="number" min="60" max="10080" step="1" required :disabled="busy"></label>
        </div>
        <div class="member-picker"><label>添加轮转成员<select v-model.number="memberToAdd" aria-label="添加轮转成员" :disabled="busy || draft.members.length >= 20"><option :value="0">选择活跃运维成员</option><option v-for="item in availableMembers" :key="item.id" :value="item.id">{{ item.displayName }} · {{ item.roleCode }}</option></select></label><button type="button" class="secondary-button" :disabled="busy || !memberToAdd || draft.members.length >= 20" @click="addMember">添加成员</button></div>
        <ol class="rotation-members" aria-label="成员轮次顺序">
          <li v-for="(id, index) in draft.members" :key="id"><span>第 {{ index + 1 }} 轮 · {{ memberName(id) }}</span><div class="rotation-actions"><button type="button" :aria-label="`${memberName(id)}上移`" class="secondary-button" :disabled="busy || index === 0" @click="moveMember(index, -1)">上移</button><button type="button" :aria-label="`${memberName(id)}下移`" class="secondary-button" :disabled="busy || index === draft.members.length - 1" @click="moveMember(index, 1)">下移</button><button type="button" :aria-label="`移除${memberName(id)}`" class="secondary-button" :disabled="busy" @click="draft.members.splice(index, 1)">移除</button></div></li>
        </ol>
        <p class="rotation-hint">锚点距现在不超过 31 天；单班 60–10080 分钟；1–20 名成员按顺序循环，停用成员不会被静默跳过。普通班冲突留台账，临时覆盖仍优先。规则保存后不可编辑，调整需暂停旧规则再新建，既有班次不会自动撤销。</p>
        <button class="primary-button" :disabled="busy || Boolean(draftError(draft))">保存轮转</button>
      </form>
      <div v-if="options" class="rotation-fields rotation-filters">
        <label>筛选轮转计划<select v-model="filter.scheduleId" aria-label="筛选轮转计划" :disabled="busy || Boolean(stateTarget)" @change="refresh(true)"><option value="">全部计划</option><option v-for="item in options.schedules" :key="item.id" :value="String(item.id)">{{ item.name }}</option></select></label>
        <label>查看轮转规则<select v-model.number="selectedId" aria-label="查看轮转规则" :disabled="busy || !rotations.length || Boolean(stateTarget)" @change="run(() => loadSlots(true))"><option v-if="!rotations.length" :value="0">暂无轮转</option><option v-for="item in rotations" :key="item.id" :value="item.id">#{{ item.id }} {{ item.name }} · {{ item.active ? '续排中' : '已暂停' }}</option></select></label>
      </div>
      <p v-if="listTruncated" role="status">规则列表仅前 100 条，请按计划缩小范围；不代表全部规则。</p>
      <article v-if="selected" class="rotation-summary">
        <div class="rotation-summary-title"><strong>{{ selected.name }} <small>#{{ selected.id }} · v{{ selected.version }}</small></strong><span class="status-badge" :class="selected.active ? 'status-info' : 'status-warning'">{{ selected.active ? '续排中' : '已暂停续排' }}</span></div>
        <p>{{ scheduleName(selected.scheduleId) }} · 每班 {{ selected.shiftMinutes }} 分钟 · 锚点 {{ rotationClock(selected.anchorAt) }}</p>
        <p>顺序：{{ selected.members.map(memberName).join(' → ') }} → 循环</p>
        <p class="rotation-warning" :class="{ blocked: selected.lastWarning }">{{ rotationWarning(selected.lastWarning) }}</p>
        <p class="rotation-hint">上次扫描：{{ rotationClock(selected.lastScanAt) }}<template v-if="selected.stateReason"> · 状态原因：{{ selected.stateReason }}</template></p>
        <div v-if="canManage" class="rotation-actions"><button class="secondary-button" :disabled="busy || Boolean(stateTarget)" @click="openState">{{ selected.active ? '暂停续排' : '恢复续排' }}</button><button class="secondary-button" :disabled="busy || Boolean(stateTarget)" @click="scan">立即续排（所有活跃规则）</button></div>
      </article>
      <form v-if="stateTarget && canManage" class="rotation-editor" @submit.prevent="changeState">
        <h3>{{ stateTarget.active ? '确认暂停续排' : '确认恢复续排' }} · {{ stateTarget.name }} · v{{ stateTarget.version }}</h3>
        <p class="rotation-hint">暂停不取消已生成班次；恢复不复活已取消时段。成员顺序与历史保持不变。</p>
        <label>续排状态变更原因<textarea v-model="stateReason" required maxlength="500" rows="2" :disabled="busy || stateStale"></textarea></label>
        <p v-if="stateStale" role="status">版本已变化，本次操作未提交。请关闭确认框并刷新轮转，核对最新状态后重新发起。</p>
        <div class="rotation-actions"><button class="primary-button" :disabled="busy || stateStale || !stateReason.trim()">{{ stateTarget.active ? '确认暂停' : '确认恢复' }}</button><button type="button" class="secondary-button" :disabled="busy" @click="stateTarget = null">关闭确认</button></div>
      </form>
      <form v-if="selected" class="rotation-fields rotation-window" @submit.prevent="run(() => loadSlots())">
        <label>台账窗口开始<input v-model="filter.from" type="datetime-local" required :disabled="busy"></label>
        <label>台账窗口结束<input v-model="filter.to" type="datetime-local" required :disabled="busy"></label>
        <button class="secondary-button" :disabled="busy">查询轮转台账</button>
      </form>
      <p v-if="slots?.truncated" role="status">台账仅显示前 200 条，请缩小窗口（最多 31 天）。</p>
      <div v-if="slots?.slots.length" class="rotation-slots">
        <article v-for="slot in slots.slots" :key="slot.slot" class="rotation-slot" :class="{ attention: slot.cancelledAt || !slot.memberAvailable || slot.status !== 'GENERATED' }">
          <div><strong>#{{ slot.slot }} · {{ slot.userName }}</strong><span>{{ rotationClock(slot.startsAt) }} → {{ rotationClock(slot.endsAt) }}</span></div>
          <div><span class="status-badge" :class="slot.cancelledAt || !slot.memberAvailable || slot.status !== 'GENERATED' ? 'status-warning' : 'status-info'">{{ slotState(slot) }}</span><small>{{ slot.detail }}</small><small v-if="!slot.memberAvailable">当前成员资格不可用，不作为新 ON_CALL 路由目标；历史生成事实不改写。</small></div>
          <small>{{ slot.shiftId ? `班次 #${slot.shiftId}` : '未生成班次' }}<template v-if="slot.cancelledAt"><br>取消于 {{ rotationClock(slot.cancelledAt) }}</template></small>
        </article>
      </div>
      <div v-else-if="slots" class="empty-state">窗口内没有物化台账。未来锚点、暂停或查询窗口均可能导致空白，不代表已有值班覆盖。</div>
      <div v-else-if="options && !selected" class="empty-state">暂无轮转规则，现有手工/历史排班保持不变。管理角色可创建有序规则，普通冲突不会被自动覆盖。</div>
      <p v-if="selected" class="rotation-hint">“已生成”不等于当前值班人或整段覆盖：临时覆盖优先，以顶部当前值班为准。取消生成班次请使用班次维护；取消时段永不自动重建。</p>
    </div>
  </section>
</template>

<style scoped>
.rotation-panel { margin-bottom: 18px; }
.rotation-body { padding: 0 20px 20px; }
.rotation-actions { display: flex; flex-wrap: wrap; gap: 8px; }
.panel-heading > .rotation-actions { flex-direction: row; gap: 8px; }
.rotation-hint { color: var(--text-muted); font-size: 12px; line-height: 1.65; }
.rotation-error { color: #b42318; }
.rotation-fields { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; margin: 16px 0; }
label { display: flex; flex-direction: column; gap: 6px; min-width: 0; font-size: 12px; }
input, select, textarea { width: 100%; min-width: 0; box-sizing: border-box; border: 1px solid var(--line); background: var(--surface); color: inherit; border-radius: 6px; padding: 9px; font: inherit; }
.rotation-editor, .rotation-summary { margin: 16px 0; padding: 16px; border: 1px solid var(--line); border-radius: 8px; }
.rotation-editor h3 { margin: 0 0 12px; font-size: 14px; }
.rotation-editor > .rotation-actions { margin-top: 12px; }
.member-picker { display: flex; gap: 8px; align-items: end; }
.member-picker label { flex: 1; }
.rotation-members { list-style: none; padding: 0; }
.rotation-members li { display: flex; flex-wrap: wrap; justify-content: space-between; align-items: center; gap: 8px; padding: 8px 0; font-size: 12px; }
.rotation-summary-title { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 8px; }
.rotation-summary p { font-size: 12px; line-height: 1.65; overflow-wrap: anywhere; }
.rotation-summary small { font-weight: normal; color: var(--text-muted); }
.rotation-warning { color: var(--text-muted); }
.rotation-warning.blocked { color: #a34c00; }
.rotation-window { grid-template-columns: 1fr 1fr auto; align-items: end; }
.rotation-slot { display: grid; grid-template-columns: 1fr 1.3fr auto; gap: 12px; align-items: start; padding: 14px 0; border-top: 1px solid var(--line); }
.rotation-slot > div { display: flex; flex-direction: column; gap: 6px; min-width: 0; }
.rotation-slot strong { font-size: 13px; }
.rotation-slot span, .rotation-slot small { font-size: 12px; overflow-wrap: anywhere; line-height: 1.5; }
.rotation-slot .status-badge { align-self: start; }
.rotation-slot.attention { border-left: 3px solid #d28a27; padding-left: 12px; }
@media (max-width: 640px) {
  .rotation-panel .panel-heading { flex-direction: column; align-items: stretch; gap: 12px; }
  .rotation-body { padding: 0 14px 14px; }
  .rotation-fields, .rotation-slot { grid-template-columns: 1fr; }
  .rotation-editor, .rotation-summary { padding: 12px; }
  .member-picker { flex-direction: column; align-items: stretch; }
}
</style>

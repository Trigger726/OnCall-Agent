<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref } from 'vue'
import { X, Save } from 'lucide-vue-next'
import { api, RequestError } from '@/services/api'
import { auth } from '@/stores/auth'
const props = defineProps<{ resource?: { id: number; resourceCode: string; name: string; resourceType: string; environment: string; status: string; ownerId: number | null; description: string; version: number } }>()
const emit = defineEmits<{ close: []; saved: [id: number]; reload: [] }>()
const dialog = ref<HTMLDialogElement | null>(null)
const draft = ref({ resourceCode: props.resource?.resourceCode ?? '', name: props.resource?.name ?? '', resourceType: props.resource?.resourceType ?? 'APPLICATION', environment: props.resource?.environment ?? 'PRODUCTION', status: props.resource?.status ?? 'RUNNING', ownerId: props.resource?.ownerId ?? '', description: props.resource?.description ?? '', version: props.resource?.version })
const owners = ref<{ id: number; displayName: string; roleCode: string }[]>([])
const busy = ref(false), ready = ref(false), error = ref(''), conflict = ref(false)
const session = { token: localStorage.getItem('opspilot_token'), actorId: auth.state.user?.id ?? 0 }
const abort = new AbortController()
onMounted(async () => {
  dialog.value?.showModal()
  try { owners.value = (await api<typeof owners.value>('/reference/users', { signal: abort.signal }, session)).filter(u => ['ADMIN','OPS_MANAGER','ON_CALL'].includes(u.roleCode)); ready.value = true }
  catch(e) { if(!abort.signal.aborted) error.value = e instanceof Error ? e.message : '无法读取负责人，请关闭后重试' }
})
onBeforeUnmount(() => { abort.abort(); dialog.value?.close() })
async function save() {
  if(busy.value || !ready.value) return
  busy.value = true; error.value = ''; conflict.value = false
  try {
    const response = await api<{ resource: { id: number } }>(props.resource ? `/cmdb/resources/${props.resource.id}` : '/cmdb/resources', { method: props.resource ? 'PUT' : 'POST', body: JSON.stringify({ ...draft.value, ownerId: Number(draft.value.ownerId) }) }, session)
    emit('saved', response.resource.id)
  } catch(e) { error.value = e instanceof Error ? e.message : '保存失败，请重试'; conflict.value = e instanceof RequestError && e.code === 'RESOURCE_VERSION_CONFLICT' }
  finally { busy.value = false }
}
</script>
<template>
  <dialog ref="dialog" class="resource-editor" aria-labelledby="resource-editor-title" @cancel="busy && $event.preventDefault()" @close="emit('close')">
    <form @submit.prevent="save">
      <header><div><h2 id="resource-editor-title">{{ resource ? '编辑资源' : '登记资源' }}</h2><p>准确的资源编码和负责人，是告警定位的起点。</p></div><button type="button" class="icon-button" aria-label="关闭资源编辑" :disabled="busy" @click="emit('close')"><X :size="20" /></button></header>
      <div v-if="error" role="alert" class="inline-error">{{ error }}<button v-if="conflict" type="button" class="secondary-button" @click="emit('reload')">关闭并重新加载</button></div>
      <fieldset :disabled="busy">
        <label>资源名称<input v-model="draft.name" required maxlength="128" autofocus placeholder="例如：订单服务" /></label>
        <label>资源编码<input v-model="draft.resourceCode" required maxlength="64" pattern="[A-Za-z0-9][A-Za-z0-9._\-]*" :readonly="Boolean(resource)" placeholder="例如：APP-ORDERS" /><small>与监控中的 resource_code 一致，登记后不可修改。</small></label>
        <div class="editor-pair"><label>类型<select v-model="draft.resourceType"><option value="APPLICATION">应用服务</option><option value="API">接口</option><option value="DATABASE">数据库</option><option value="MIDDLEWARE">中间件</option></select></label><label>环境<select v-model="draft.environment"><option value="PRODUCTION">生产</option><option value="STAGING">预发布</option><option value="TEST">测试</option><option value="DEVELOPMENT">开发</option></select></label></div>
        <div class="editor-pair"><label>负责人<select v-model="draft.ownerId" required :disabled="!ready"><option disabled value="">{{ ready ? '请选择负责人' : '加载负责人…' }}</option><option v-for="owner in owners" :key="owner.id" :value="owner.id">{{ owner.displayName }}</option></select></label><label>登记状态<select v-model="draft.status"><option value="RUNNING">正常运行</option><option value="DEGRADED">降级</option><option value="MAINTENANCE">维护中</option><option value="OFFLINE">已下线</option></select></label></div>
        <label>说明<textarea v-model="draft.description" maxlength="500" rows="3" placeholder="服务用途、维护范围或联系说明" /><small>登记状态由维护人员更新，不代表实时探测结果。</small></label>
      </fieldset>
      <footer><button type="button" class="secondary-button" :disabled="busy" @click="emit('close')">取消</button><button type="submit" class="primary-button" :disabled="busy || !ready || conflict"><Save :size="16" />{{ busy ? '保存中…' : '保存资源' }}</button></footer>
    </form>
  </dialog>
</template>
<style scoped>
.resource-editor{width:min(600px,calc(100vw - 32px));max-height:calc(100dvh - 32px);overflow:auto;border:1px solid var(--line-strong);border-radius:16px;background:var(--surface);color:var(--text);padding:26px;box-shadow:var(--shadow-float)}
.resource-editor::backdrop{background:#0008;backdrop-filter:blur(4px)}header{display:flex;align-items:flex-start;justify-content:space-between;gap:16px}h2{margin:0;font-size:22px}header p,small{font-size:12px;color:var(--text-muted);line-height:1.6}fieldset{border:0;padding:0;margin:22px 0;display:grid;gap:18px;min-width:0}label{display:grid;gap:7px;font-size:13px;min-width:0}input,select,textarea{width:100%;box-sizing:border-box;color:var(--text);background:var(--surface-muted);border:1px solid var(--line-strong);border-radius:8px;padding:10px 12px;font:inherit}input[readonly]{color:var(--text-muted)}.editor-pair{display:grid;grid-template-columns:1fr 1fr;gap:16px}footer{position:sticky;bottom:0;display:flex;justify-content:flex-end;gap:10px;padding:12px 0;background:var(--surface)}.inline-error{margin-top:16px;display:grid;gap:10px}button:focus-visible,input:focus-visible,select:focus-visible,textarea:focus-visible{outline:2px solid var(--blue);outline-offset:2px}@media(max-width:480px){.resource-editor{padding:18px}.editor-pair{grid-template-columns:1fr}}
</style>

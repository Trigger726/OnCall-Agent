<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { Database, GitFork, ServerCog, Plus, Pencil, RefreshCw } from 'lucide-vue-next'
import ResourceEditor from '@/components/ResourceEditor.vue'
import { auth } from '@/stores/auth'
import StatusBadge from '@/components/StatusBadge.vue'
import { api, formatTime, type PageResponse } from '@/services/api'

interface Resource {
  id: number; resourceCode: string; resourceType: string; name: string; environment: string; status: string
  description: string; ownerName: string; ownerId: number | null; version: number; activeIncidents: number; updatedAt: string
}
interface Detail { resource: Resource; attributesJson: string; relations: Relation[]; recentChanges: Change[] }
interface Relation { id: number; relationType: string; sourceId: number; sourceName: string; targetId: number; targetName: string }
interface Change { id: number; changeCode: string; changeType: string; summary: string; status: string; operator: string; startedAt: string }

const route = useRoute()
const resources = ref<Resource[]>([])
const detail = ref<Detail | null>(null)
const q = ref('')
const type = ref('')
const page = ref(1), total = ref(0), loading = ref(false), detailLoading = ref(false)
const error = ref(''), detailError = ref(''), savedMessage = ref('')
const editor = ref(false), editing = ref<Resource | undefined>()
const canManage = computed(() => ['ADMIN','OPS_MANAGER'].includes(auth.state.user?.roleCode ?? ''))
const pageCount = computed(() => Math.max(1, Math.ceil(total.value / 20)))
const token = localStorage.getItem('opspilot_token')
let listSeq = 0, detailSeq = 0, disposed = false
const current = () => !disposed && localStorage.getItem('opspilot_token') === token
const typeName = (value: string) => (({APPLICATION:'应用服务',API:'接口',DATABASE:'数据库',MIDDLEWARE:'中间件'} as Record<string,string>)[value] ?? value)
async function load(selectedId?: number) {
  const seq = ++listSeq; ++detailSeq
  loading.value = true; error.value = ''; detail.value = null; detailError.value = ''; detailLoading.value = false
  try {
    const query = new URLSearchParams({ q: q.value.trim(), type: type.value, page: String(page.value), size: '20' })
    const result = await api<PageResponse<Resource>>(`/cmdb/resources?${query}`)
    if(seq !== listSeq || !current()) return
    resources.value = result.items; total.value = result.total
    const id = selectedId ?? result.items[0]?.id
    if(id) await selectResource(id)
  } catch(e) { if(seq === listSeq && current()) { error.value = e instanceof Error ? e.message : '资源读取失败'; resources.value = [] } }
  finally { if(seq === listSeq && current()) loading.value = false }
}
async function selectResource(id: number) {
  const seq = ++detailSeq; detail.value = null; detailError.value = ''; detailLoading.value = true
  try { const result = await api<Detail>(`/cmdb/resources/${id}`); if(seq === detailSeq && current()) detail.value = result }
  catch(e) { if(seq === detailSeq && current()) detailError.value = e instanceof Error ? e.message : '资源详情读取失败' }
  finally { if(seq === detailSeq && current()) detailLoading.value = false }
}
function search() { page.value = 1; void load() }
function openEditor(resource?: Resource) { editing.value = resource; editor.value = true; savedMessage.value = '' }
async function saved(id: number) { editor.value = false; savedMessage.value = '资源已保存，告警可通过资源编码关联。'; await load(id) }
function reloadEdit() { const id = editing.value?.id; editor.value = false; void load(id) }
watch(() => route.query.selected, id => { if(Number(id)>0) void selectResource(Number(id)) })
onMounted(() => load(Number(route.query.selected) || undefined))
onBeforeUnmount(() => { disposed = true; ++listSeq; ++detailSeq })
</script>

<template>
  <div class="page-content">
    <div class="page-toolbar">
      <div><strong>资源台账</strong><span>找到服务、确认归属，维护准确的告警关联信息</span></div>
      <div class="toolbar-group"><button class="secondary-button" :disabled="loading" @click="load()"><RefreshCw :size="15" />刷新</button><button v-if="canManage" class="primary-button" @click="openEditor()"><Plus :size="16" />登记资源</button></div>
    </div>
    <p v-if="savedMessage" class="success-banner" role="status">{{ savedMessage }}</p>
    <div class="cmdb-layout">
      <section class="content-panel resource-list-panel">
        <form class="panel-filter" @submit.prevent="search"><input v-model="q" aria-label="搜索资源" maxlength="120" placeholder="名称或资源编码" /><select v-model="type" aria-label="资源类型" @change="search"><option value="">全部类型</option><option v-for="item in ['APPLICATION','API','DATABASE','MIDDLEWARE']" :key="item" :value="item">{{ typeName(item) }}</option></select><button class="secondary-button" type="submit">搜索</button></form>
        <div v-if="error" class="inline-error" role="alert">{{ error }}<button class="secondary-button" @click="load()">重试</button></div>
        <p v-else-if="loading" class="resource-state" role="status">正在加载资源…</p>
        <p v-else-if="!resources.length" class="resource-state">没有匹配的资源，请调整搜索条件或登记资源。</p>
        <button v-for="resource in loading ? [] : resources" :key="resource.id" class="resource-row" :aria-pressed="detail?.resource.id === resource.id" :class="{ active: detail?.resource.id === resource.id }" @click="selectResource(resource.id)">
          <span class="resource-icon"><Database v-if="resource.resourceType === 'DATABASE'" :size="17" /><ServerCog v-else :size="17" /></span>
          <div><span>{{ resource.resourceCode }}</span><strong>{{ resource.name }}</strong><small>{{ typeName(resource.resourceType) }} · {{ resource.ownerName || '未登记负责人' }}</small></div>
          <StatusBadge :value="resource.status" /><em>{{ resource.activeIncidents }} Incident</em>
        </button>
        <nav v-if="!error" class="resource-pagination" aria-label="资源分页"><span>共 {{ total }} 项 · {{ page }} / {{ pageCount }}</span><button class="secondary-button" :disabled="loading || page <= 1" @click="page--; load()">上一页</button><button class="secondary-button" :disabled="loading || page >= pageCount" @click="page++; load()">下一页</button></nav>
      </section>
      <section v-if="detail" class="content-panel resource-detail-panel">
        <header><div><span>{{ detail.resource.resourceCode }}</span><h2>{{ detail.resource.name }}</h2><p>{{ detail.resource.description }}</p></div><StatusBadge :value="detail.resource.status" /></header>
        <div class="resource-actions"><RouterLink :to="`/incidents?q=${encodeURIComponent(detail.resource.name)}`" class="secondary-button">搜索相关事件</RouterLink><button v-if="canManage" class="secondary-button" @click="openEditor(detail.resource)"><Pencil :size="15" />编辑资源</button></div>
        <div class="resource-facts"><div><span>类型</span><strong>{{ typeName(detail.resource.resourceType) }}</strong></div><div><span>环境</span><strong>{{ detail.resource.environment }}</strong></div><div><span>负责人</span><strong>{{ detail.resource.ownerName }}</strong></div><div><span>更新时间</span><strong>{{ formatTime(detail.resource.updatedAt, true) }}</strong></div></div>
        <div class="section-heading"><h3>依赖关系</h3><span>{{ detail.relations.length }}</span></div>
        <div class="relation-list"><div v-for="relation in detail.relations" :key="relation.id"><span>{{ relation.sourceName }}</span><i><GitFork :size="14" />{{ relation.relationType }}</i><span>{{ relation.targetName }}</span></div></div>
        <p v-if="!detail.relations.length" class="muted">尚未登记依赖关系。</p>
        <div class="section-heading"><h3>近期变更</h3><span>{{ detail.recentChanges.length }}</span></div>
        <div class="change-list"><article v-for="change in detail.recentChanges" :key="change.id"><span>{{ change.changeCode }} · {{ formatTime(change.startedAt, true) }}</span><strong>{{ change.summary }}</strong><small>{{ change.operator }} · {{ change.changeType }}</small></article><p v-if="!detail.recentChanges.length" class="muted">暂无近期变更</p></div>
      </section>
      <section v-else class="content-panel resource-state" :role="detailError ? 'alert' : 'status'">{{ detailError || (detailLoading ? '正在读取资源详情…' : '从左侧选择一个资源，查看负责人和维护记录。') }}</section>
    </div>

    <ResourceEditor v-if="editor && canManage" :resource="editing" @close="editor = false" @saved="saved" @reload="reloadEdit" />
  </div>
</template>
<style scoped>
.cmdb-layout{grid-template-columns:minmax(300px, .85fr) minmax(0, 1.4fr);align-items:start}.resource-list-panel{min-width:0;overflow:hidden}.panel-filter{grid-template-columns:minmax(110px,1fr) 110px auto;gap:8px}.panel-filter select{padding:0 22px 0 8px}.panel-filter input{flex:1;min-width:120px}.resource-row{width:100%;text-align:left;min-width:0}.resource-row div{min-width:0;overflow-wrap:anywhere}.resource-row em{white-space:nowrap}.resource-detail-panel{min-width:0}.resource-detail-panel header h2{font-size:26px;letter-spacing:-.5px;overflow-wrap:anywhere}.resource-detail-panel header p{max-width:65ch;line-height:1.7}.resource-actions{display:flex;flex-wrap:wrap;gap:10px;margin:20px 0;padding:0 22px}.resource-state{padding:32px 22px;color:var(--text-muted);line-height:1.7}.resource-pagination{display:flex;align-items:center;justify-content:flex-end;gap:8px;padding:16px;border-top:1px solid var(--line);flex-wrap:wrap}.resource-pagination span{margin-right:auto;font-size:12px;color:var(--text-muted)}.resource-row:focus-visible{outline:2px solid var(--blue);outline-offset:-3px}.resource-facts{grid-template-columns:repeat(2,minmax(0,1fr));gap:18px}.resource-facts strong{overflow-wrap:anywhere}@media(max-width:1000px){.cmdb-layout{grid-template-columns:1fr}.resource-list-panel{max-height:520px;overflow:auto}.resource-detail-panel header h2{font-size:23px}}
</style>

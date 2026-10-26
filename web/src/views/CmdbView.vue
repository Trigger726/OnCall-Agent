<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { Database, GitFork, ServerCog } from 'lucide-vue-next'
import StatusBadge from '@/components/StatusBadge.vue'
import { api, formatTime, type PageResponse } from '@/services/api'

interface Resource {
  id: number; resourceCode: string; resourceType: string; name: string; environment: string; status: string
  description: string; ownerName: string; activeIncidents: number; updatedAt: string
}
interface Detail { resource: Resource; attributesJson: string; relations: Relation[]; recentChanges: Change[] }
interface Relation { id: number; relationType: string; sourceId: number; sourceName: string; targetId: number; targetName: string }
interface Change { id: number; changeCode: string; changeType: string; summary: string; status: string; operator: string; startedAt: string }

const route = useRoute()
const resources = ref<Resource[]>([])
const detail = ref<Detail | null>(null)
const q = ref('')
const type = ref('')

const filteredResources = computed(() => resources.value.filter((item) => {
  const matchesQuery = !q.value || `${item.name}${item.resourceCode}`.toLowerCase().includes(q.value.toLowerCase())
  return matchesQuery && (!type.value || item.resourceType === type.value)
}))

async function load() {
  const page = await api<PageResponse<Resource>>('/cmdb/resources?size=100')
  resources.value = page.items
  const id = Number(route.query.selected) || page.items[0]?.id
  if (id) await selectResource(id)
}

async function selectResource(id: number) { detail.value = await api<Detail>(`/cmdb/resources/${id}`) }
onMounted(load)
</script>

<template>
  <div class="page-content">
    <div class="page-toolbar">
      <div><strong>资源台账</strong><span>查看资源负责人、已登记依赖与近期变更</span></div>
    </div>

    <div class="cmdb-layout">
      <section class="content-panel resource-list-panel">
        <div class="panel-filter"><input v-model="q" placeholder="搜索名称或资源编码" /><select v-model="type"><option value="">全部类型</option><option v-for="item in ['APPLICATION','API','DATABASE','MIDDLEWARE']" :key="item">{{ item }}</option></select></div>
        <button v-for="resource in filteredResources" :key="resource.id" class="resource-row" :class="{ active: detail?.resource.id === resource.id }" @click="selectResource(resource.id)">
          <span class="resource-icon"><Database v-if="resource.resourceType === 'DATABASE'" :size="17" /><ServerCog v-else :size="17" /></span>
          <div><span>{{ resource.resourceCode }}</span><strong>{{ resource.name }}</strong><small>{{ resource.resourceType }} · {{ resource.environment }}</small></div>
          <StatusBadge :value="resource.status" /><em>{{ resource.activeIncidents }} Incident</em>
        </button>
      </section>
      <section v-if="detail" class="content-panel resource-detail-panel">
        <header><div><span>{{ detail.resource.resourceCode }}</span><h2>{{ detail.resource.name }}</h2><p>{{ detail.resource.description }}</p></div><StatusBadge :value="detail.resource.status" /></header>
        <div class="resource-facts"><div><span>类型</span><strong>{{ detail.resource.resourceType }}</strong></div><div><span>环境</span><strong>{{ detail.resource.environment }}</strong></div><div><span>负责人</span><strong>{{ detail.resource.ownerName }}</strong></div><div><span>更新时间</span><strong>{{ formatTime(detail.resource.updatedAt, true) }}</strong></div></div>
        <div class="section-heading"><h3>依赖关系</h3><span>{{ detail.relations.length }}</span></div>
        <div class="relation-list"><div v-for="relation in detail.relations" :key="relation.id"><span>{{ relation.sourceName }}</span><i><GitFork :size="14" />{{ relation.relationType }}</i><span>{{ relation.targetName }}</span></div></div>
        <div class="section-heading"><h3>近期变更</h3><span>{{ detail.recentChanges.length }}</span></div>
        <div class="change-list"><article v-for="change in detail.recentChanges" :key="change.id"><span>{{ change.changeCode }} · {{ formatTime(change.startedAt, true) }}</span><strong>{{ change.summary }}</strong><small>{{ change.operator }} · {{ change.changeType }}</small></article><p v-if="!detail.recentChanges.length" class="muted">暂无近期变更</p></div>
      </section>
    </div>

  </div>
</template>

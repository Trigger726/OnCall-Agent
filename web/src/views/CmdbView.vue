<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { Boxes, Database, GitFork, Network, ServerCog } from 'lucide-vue-next'
import StatusBadge from '@/components/StatusBadge.vue'
import { api, formatTime, type PageResponse } from '@/services/api'

interface Resource {
  id: number; resourceCode: string; resourceType: string; name: string; environment: string; status: string
  description: string; ownerName: string; activeIncidents: number; updatedAt: string
}
interface Detail { resource: Resource; attributesJson: string; relations: Relation[]; recentChanges: Change[] }
interface Relation { id: number; relationType: string; sourceId: number; sourceName: string; targetId: number; targetName: string }
interface Change { id: number; changeCode: string; changeType: string; summary: string; status: string; operator: string; startedAt: string }
interface Topology { nodes: { id: number; name: string; type: string; status: string; environment: string }[]; edges: { id: number; source: number; target: number; type: string }[] }

const route = useRoute()
const tab = ref<'resources' | 'topology'>('resources')
const resources = ref<Resource[]>([])
const detail = ref<Detail | null>(null)
const topology = ref<Topology>({ nodes: [], edges: [] })
const q = ref('')
const type = ref('')

const positions: Record<number, { x: number; y: number }> = {
  2: { x: 90, y: 110 }, 1: { x: 300, y: 110 }, 3: { x: 520, y: 35 },
  4: { x: 520, y: 105 }, 5: { x: 520, y: 175 }, 6: { x: 735, y: 110 },
}
const filteredResources = computed(() => resources.value.filter((item) => {
  const matchesQuery = !q.value || `${item.name}${item.resourceCode}`.toLowerCase().includes(q.value.toLowerCase())
  return matchesQuery && (!type.value || item.resourceType === type.value)
}))

async function load() {
  const [page, graph] = await Promise.all([
    api<PageResponse<Resource>>('/cmdb/resources?size=100'), api<Topology>('/cmdb/topology'),
  ])
  resources.value = page.items
  topology.value = graph
  const id = Number(route.query.selected) || page.items[0]?.id
  if (id) await selectResource(id)
}

async function selectResource(id: number) { detail.value = await api<Detail>(`/cmdb/resources/${id}`) }
onMounted(load)
</script>

<template>
  <div class="page-content">
    <div class="page-toolbar">
      <div><strong>配置资源库</strong><span>应用、数据库、中间件与依赖关系</span></div>
      <div class="segmented-control"><button :class="{ active: tab === 'resources' }" @click="tab = 'resources'"><Boxes :size="15" />资源台账</button><button :class="{ active: tab === 'topology' }" @click="tab = 'topology'"><Network :size="15" />服务拓扑</button></div>
    </div>

    <div v-if="tab === 'resources'" class="cmdb-layout">
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

    <section v-else class="content-panel topology-panel">
      <div class="topology-legend"><span><i class="legend-node healthy" />正常</span><span><i class="legend-node degraded" />降级</span><span><i class="legend-line" />依赖 / 调用</span></div>
      <svg viewBox="0 0 840 230" role="img" aria-label="服务依赖拓扑">
        <g v-for="edge in topology.edges" :key="edge.id" class="topology-edge"><line :x1="(positions[edge.source]?.x ?? 0) + 68" :y1="positions[edge.source]?.y ?? 0" :x2="(positions[edge.target]?.x ?? 0) - 68" :y2="positions[edge.target]?.y ?? 0" /><text :x="((positions[edge.source]?.x ?? 0) + (positions[edge.target]?.x ?? 0)) / 2" :y="((positions[edge.source]?.y ?? 0) + (positions[edge.target]?.y ?? 0)) / 2 - 7">{{ edge.type }}</text></g>
        <g v-for="node in topology.nodes" :key="node.id" class="topology-node" :class="{ degraded: node.status !== 'RUNNING' }" :transform="`translate(${(positions[node.id]?.x ?? 0) - 68}, ${(positions[node.id]?.y ?? 0) - 26})`" @click="tab = 'resources'; selectResource(node.id)">
          <rect width="136" height="52" rx="5" /><circle cx="15" cy="16" r="4" /><text x="27" y="19" class="node-name">{{ node.name }}</text><text x="14" y="38" class="node-type">{{ node.type }}</text>
        </g>
      </svg>
      <div class="topology-summary"><Network :size="18" /><div><strong>{{ topology.nodes.length }} 项资源 · {{ topology.edges.length }} 条关系</strong><span>统一结算服务当前受 Redis 集群降级影响</span></div></div>
    </section>
  </div>
</template>

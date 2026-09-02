<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { RefreshCw, ShieldCheck } from 'lucide-vue-next'
import { api, formatTime } from '@/services/api'

interface Audit { id: number; actor: string | null; action: string; targetType: string; targetId: string; detail: string; ipAddress: string; createdAt: string }
const rows = ref<Audit[]>([])
const loading = ref(false)
async function load() {
  loading.value = true
  try { rows.value = await api<Audit[]>('/audit-logs?limit=200') }
  finally { loading.value = false }
}
onMounted(load)
</script>

<template>
  <div class="page-content">
    <div class="page-toolbar"><div><strong>操作审计</strong><span>关键状态变更、调查和责任人操作全量留痕</span></div><button class="secondary-button" @click="load"><RefreshCw :size="15" :class="{ spin: loading }" />刷新</button></div>
    <section class="content-panel full-table-panel">
      <div class="audit-notice"><ShieldCheck :size="17" /><span>当前日志为只读视图，按时间倒序展示。</span></div>
      <div class="table-scroll"><table class="data-table"><thead><tr><th>时间</th><th>操作者</th><th>动作</th><th>对象</th><th>详情</th><th>来源 IP</th></tr></thead><tbody><tr v-for="row in rows" :key="row.id"><td>{{ formatTime(row.createdAt, true) }}</td><td><strong>{{ row.actor ?? '系统' }}</strong></td><td><span class="action-code">{{ row.action }}</span></td><td>{{ row.targetType }} #{{ row.targetId }}</td><td>{{ row.detail }}</td><td><code>{{ row.ipAddress }}</code></td></tr></tbody></table></div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref, watch } from 'vue'
import { BellPlus, RefreshCw, Send, X } from 'lucide-vue-next'
import StatusBadge from '@/components/StatusBadge.vue'
import { api, formatTime, type PageResponse } from '@/services/api'

interface AlertItem {
  id: number; source: string; externalEventId: string | null; severity: string; status: string; title: string
  resourceName: string; occurrenceCount: number; firstOccurredAt: string; lastOccurredAt: string
  incidentId: number | null; incidentCode: string | null
}

const alerts = ref<AlertItem[]>([])
const statusFilter = ref('')
const severityFilter = ref('')
const showIntake = ref(false)
const loading = ref(false)
const resultMessage = ref('')
const form = reactive({
  source: 'prometheus', resourceCode: 'APP-PORTAL', severity: 'P3', status: 'FIRING',
  title: '客户门户接口错误率升高', description: '5xx 错误率连续 5 分钟超过阈值',
})

async function load() {
  loading.value = true
  const query = new URLSearchParams({ size: '100' })
  if (statusFilter.value) query.set('status', statusFilter.value)
  if (severityFilter.value) query.set('severity', severityFilter.value)
  try { alerts.value = (await api<PageResponse<AlertItem>>(`/alerts?${query}`)).items }
  finally { loading.value = false }
}

async function submitIntake() {
  loading.value = true
  try {
    const result = await api<{ action: string; alertId: number; incidentId: number | null; message: string }>('/alerts/intake', {
      method: 'POST', body: JSON.stringify({ ...form, labels: { cluster: 'prod-east', manual: 'true' } }),
    })
    resultMessage.value = `${result.message} · Alert #${result.alertId}`
    showIntake.value = false
    await load()
  } finally { loading.value = false }
}

watch([statusFilter, severityFilter], load)
onMounted(load)
</script>

<template>
  <div class="page-content">
    <div class="page-toolbar">
      <div><strong>告警事件</strong><span>原始事件经过去重和聚合后关联 Incident</span></div>
      <div class="toolbar-group">
        <select v-model="severityFilter"><option value="">全部等级</option><option v-for="level in ['P1','P2','P3','P4']" :key="level">{{ level }}</option></select>
        <select v-model="statusFilter"><option value="">全部状态</option><option value="FIRING">FIRING</option><option value="RESOLVED">RESOLVED</option></select>
        <button class="secondary-button" @click="load"><RefreshCw :size="15" :class="{ spin: loading }" />刷新</button>
        <button class="primary-button" @click="showIntake = true"><BellPlus :size="16" />接入告警</button>
      </div>
    </div>
    <div v-if="resultMessage" class="success-banner">{{ resultMessage }}</div>
    <section class="content-panel full-table-panel">
      <div class="table-scroll">
        <table class="data-table">
          <thead><tr><th>等级</th><th>告警主题</th><th>来源</th><th>资源</th><th>状态</th><th>压缩次数</th><th>关联 Incident</th><th>最近发生</th></tr></thead>
          <tbody>
            <tr v-for="alert in alerts" :key="alert.id">
              <td><StatusBadge :value="alert.severity" /></td>
              <td><div class="primary-cell"><span>#{{ alert.id }} · {{ alert.externalEventId ?? 'fingerprint' }}</span><strong>{{ alert.title }}</strong></div></td>
              <td><span class="source-tag">{{ alert.source }}</span></td><td>{{ alert.resourceName }}</td>
              <td><StatusBadge :value="alert.status" /></td><td><strong>× {{ alert.occurrenceCount }}</strong></td>
              <td><RouterLink v-if="alert.incidentId" :to="`/incidents?selected=${alert.incidentId}`">{{ alert.incidentCode }}</RouterLink><span v-else>-</span></td>
              <td>{{ formatTime(alert.lastOccurredAt, true) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <div v-if="showIntake" class="dialog-backdrop" @click.self="showIntake = false">
      <form class="dialog-panel" @submit.prevent="submitIntake">
        <header><div><h2>接入测试告警</h2><span>提交后立即执行指纹去重与 Incident 聚合</span></div><button type="button" class="icon-button" title="关闭" @click="showIntake = false"><X :size="18" /></button></header>
        <div class="form-grid">
          <label><span>告警来源</span><input v-model="form.source" required /></label>
          <label><span>资源编码</span><select v-model="form.resourceCode"><option value="APP-SETTLEMENT">APP-SETTLEMENT</option><option value="APP-PORTAL">APP-PORTAL</option><option value="APP-AUTH">APP-AUTH</option><option value="MID-REDIS-01">MID-REDIS-01</option></select></label>
          <label><span>严重等级</span><select v-model="form.severity"><option v-for="level in ['P1','P2','P3','P4']" :key="level">{{ level }}</option></select></label>
          <label><span>事件状态</span><select v-model="form.status"><option>FIRING</option><option>RESOLVED</option></select></label>
          <label class="span-2"><span>告警主题</span><input v-model="form.title" required /></label>
          <label class="span-2"><span>观测描述</span><textarea v-model="form.description" rows="4" /></label>
        </div>
        <footer><button type="button" class="secondary-button" @click="showIntake = false">取消</button><button class="primary-button" :disabled="loading"><Send :size="15" />提交事件</button></footer>
      </form>
    </div>
  </div>
</template>

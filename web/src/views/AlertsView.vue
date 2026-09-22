<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { BellPlus, RefreshCw, RotateCcw, Send, ShieldAlert, X } from 'lucide-vue-next'
import StatusBadge from '@/components/StatusBadge.vue'
import { api, formatTime, type PageResponse } from '@/services/api'
import { auth } from '@/stores/auth'

interface AlertItem {
  id: number; source: string; externalEventId: string | null; severity: string; status: string; title: string
  resourceName: string; occurrenceCount: number; firstOccurredAt: string; lastOccurredAt: string
  incidentId: number | null; incidentCode: string | null
}

interface RejectionItem {
  id: number; status: 'OPEN' | 'SUCCEEDED'; errorCode: string; errorMessage: string
  alertName: string | null; resourceCode: string | null; severity: string | null; alertStatus: string | null
  receiver: string | null; deliveryCount: number; replayCount: number; redactedFields: number
  replayLeaseUntil: string | null; lastReplayErrorCode: string | null; lastReplayErrorMessage: string | null
  resolvedAlertId: number | null; resolvedIncidentId: number | null
  firstReceivedAt: string; lastReceivedAt: string; lastReplayedAt: string | null; resolvedAt: string | null
}

const alerts = ref<AlertItem[]>([])
const rejections = ref<RejectionItem[]>([])
const statusFilter = ref('')
const severityFilter = ref('')
const rejectionStatus = ref('OPEN')
const showIntake = ref(false)
const loading = ref(false)
const rejectionLoading = ref(false)
const replayingId = ref<number | null>(null)
const resultMessage = ref('')
const canReplay = computed(() => ['ADMIN', 'OPS_MANAGER', 'ON_CALL'].includes(auth.state.user?.roleCode ?? ''))
const form = reactive({
  source: 'prometheus', resourceCode: 'APP-PORTAL', severity: 'P3', status: 'FIRING',
  title: '客户门户接口错误率升高', description: '5xx 错误率连续 5 分钟超过阈值',
})

async function loadAlerts() {
  loading.value = true
  const query = new URLSearchParams({ size: '100' })
  if (statusFilter.value) query.set('status', statusFilter.value)
  if (severityFilter.value) query.set('severity', severityFilter.value)
  try { alerts.value = (await api<PageResponse<AlertItem>>(`/alerts?${query}`)).items }
  finally { loading.value = false }
}

async function loadRejections() {
  rejectionLoading.value = true
  const query = new URLSearchParams({ size: '50' })
  if (rejectionStatus.value) query.set('status', rejectionStatus.value)
  try {
    rejections.value = (await api<PageResponse<RejectionItem>>(
      `/integrations/alertmanager/rejections?${query}`,
    )).items
  } finally { rejectionLoading.value = false }
}

async function load() {
  await Promise.all([loadAlerts(), loadRejections()])
}

async function submitIntake() {
  loading.value = true
  try {
    const result = await api<{ action: string; alertId: number; incidentId: number | null; message: string }>('/alerts/intake', {
      method: 'POST', body: JSON.stringify({ ...form, labels: { cluster: 'prod-east', manual: 'true' } }),
    })
    resultMessage.value = `${result.message} · Alert #${result.alertId}`
    showIntake.value = false
    await loadAlerts()
  } finally { loading.value = false }
}

async function replay(item: RejectionItem) {
  replayingId.value = item.id
  try {
    const result = await api<{ action: string; alertId: number | null; incidentId: number | null; message: string }>(
      `/integrations/alertmanager/rejections/${item.id}/replay`, { method: 'POST' },
    )
    resultMessage.value = `${result.message}${result.alertId ? ` · Alert #${result.alertId}` : ''}`
    await Promise.all([loadAlerts(), loadRejections()])
  } finally { replayingId.value = null }
}

watch([statusFilter, severityFilter], loadAlerts)
watch(rejectionStatus, loadRejections)
onMounted(load)
</script>

<template>
  <div class="page-content">
    <div class="page-toolbar">
      <div><strong>告警事件</strong><span>原始事件经过去重和聚合后关联 Incident</span></div>
      <div class="toolbar-group">
        <select v-model="severityFilter"><option value="">全部等级</option><option v-for="level in ['P1','P2','P3','P4']" :key="level">{{ level }}</option></select>
        <select v-model="statusFilter"><option value="">全部状态</option><option value="FIRING">FIRING</option><option value="RESOLVED">RESOLVED</option></select>
        <button class="secondary-button" @click="loadAlerts"><RefreshCw :size="15" :class="{ spin: loading }" />刷新</button>
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

    <div class="page-toolbar rejection-toolbar">
      <div><strong>Alertmanager 拒绝台账</strong><span>永久坏项脱敏留存，修复上游规则或 CMDB 后可受控重放</span></div>
      <div class="toolbar-group">
        <select v-model="rejectionStatus" aria-label="拒绝项状态">
          <option value="OPEN">待处理</option><option value="SUCCEEDED">已重放</option><option value="">全部状态</option>
        </select>
        <button class="secondary-button" @click="loadRejections"><RefreshCw :size="15" :class="{ spin: rejectionLoading }" />刷新</button>
      </div>
    </div>
    <section class="content-panel full-table-panel rejection-panel">
      <div v-if="!rejections.length && !rejectionLoading" class="empty-state compact-empty">
        <ShieldAlert :size="22" /><strong>当前没有匹配的拒绝项</strong>
      </div>
      <div v-else class="table-scroll">
        <table class="data-table">
          <thead><tr><th>状态</th><th>告警</th><th>拒绝原因</th><th>投递 / 重放</th><th>最近接收</th><th>结果</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="item in rejections" :key="item.id">
              <td><span class="status-badge" :class="item.status === 'SUCCEEDED' ? 'status-success' : 'status-danger'">{{ item.status === 'SUCCEEDED' ? '已重放' : '待处理' }}</span></td>
              <td><div class="primary-cell"><span>#{{ item.id }} · {{ item.resourceCode ?? '资源未识别' }}</span><strong>{{ item.alertName ?? '告警字段不完整' }}</strong><small>{{ item.severity ?? '-' }} · {{ item.alertStatus ?? '-' }} · {{ item.receiver ?? '-' }}</small></div></td>
              <td><div class="rejection-error"><code>{{ item.errorCode }}</code><span>{{ item.errorMessage }}</span><small v-if="item.redactedFields">已脱敏 {{ item.redactedFields }} 个字段</small><small v-if="item.lastReplayErrorCode">上次重放：{{ item.lastReplayErrorCode }}</small></div></td>
              <td><strong>{{ item.deliveryCount }}</strong> / {{ item.replayCount }}</td>
              <td>{{ formatTime(item.lastReceivedAt, true) }}</td>
              <td><RouterLink v-if="item.resolvedIncidentId" :to="`/incidents?selected=${item.resolvedIncidentId}`">Incident #{{ item.resolvedIncidentId }}</RouterLink><span v-else>-</span></td>
              <td><button v-if="item.status === 'OPEN' && canReplay" class="secondary-button compact-button" :disabled="replayingId === item.id" @click="replay(item)"><RotateCcw :size="14" :class="{ spin: replayingId === item.id }" />{{ replayingId === item.id ? '重放中' : '重放' }}</button><span v-else>-</span></td>
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

<style scoped>
.rejection-toolbar { margin-top: 18px; }
.rejection-panel { min-height: 132px; }
.compact-empty { min-height: 110px; }
.primary-cell small { color: var(--text-muted); font-size: 12px; }
.rejection-error { display: grid; gap: 4px; max-width: 360px; }
.rejection-error code { color: var(--danger); font-size: 12px; }
.rejection-error span { color: var(--text-secondary); line-height: 1.45; }
.rejection-error small { color: var(--text-muted); }
.compact-button { min-width: 74px; justify-content: center; }
@media (max-width: 720px) {
  .rejection-toolbar { align-items: flex-start; }
  .rejection-error { min-width: 240px; }
}
</style>

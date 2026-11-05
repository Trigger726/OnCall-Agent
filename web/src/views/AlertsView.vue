<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { RefreshCw, RotateCcw, ShieldAlert } from 'lucide-vue-next'
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
  payloadStatus: 'ACTIVE' | 'PURGED'; autoReplayCount: number; nextAutoReplayAt: string | null
  autoReplayExhaustedAt: string | null; purgedAt: string | null
  autoReplayEnabled: boolean
}

const alerts = ref<AlertItem[]>([])
const rejections = ref<RejectionItem[]>([])
const statusFilter = ref('')
const severityFilter = ref('')
const rejectionStatus = ref('OPEN')
const loading = ref(false)
const rejectionLoading = ref(false)
const replayingId = ref<number | null>(null)
const resultMessage = ref('')
const alertError = ref(''), rejectionError = ref(''), actionError = ref('')
const page = ref(1), total = ref(0)
let alertSeq = 0, rejectionSeq = 0, disposed = false
const token = localStorage.getItem('opspilot_token')
const current = () => !disposed && token === localStorage.getItem('opspilot_token')
onBeforeUnmount(() => { disposed = true; ++alertSeq; ++rejectionSeq })
const canReplay = computed(() => ['ADMIN', 'OPS_MANAGER', 'ON_CALL'].includes(auth.state.user?.roleCode ?? ''))

async function loadAlerts() {
  const seq = ++alertSeq; alertError.value = ''
  loading.value = true
  const query = new URLSearchParams({ size: '20', page: String(page.value) })
  if (statusFilter.value) query.set('status', statusFilter.value)
  if (severityFilter.value) query.set('severity', severityFilter.value)
  try { const result = await api<PageResponse<AlertItem>>(`/alerts?${query}`); if(seq === alertSeq && current()) { alerts.value = result.items; total.value = result.total } }
  catch(e) { if(seq === alertSeq && current()) { alerts.value = []; alertError.value = e instanceof Error ? e.message : '告警读取失败' } }
  finally { if(seq === alertSeq && current()) loading.value = false }
}

async function loadRejections() {
  const seq = ++rejectionSeq; rejectionError.value = ''
  rejectionLoading.value = true
  const query = new URLSearchParams({ size: '50' })
  if (rejectionStatus.value) query.set('status', rejectionStatus.value)
  try {
    const result = await api<PageResponse<RejectionItem>>(
      `/integrations/alertmanager/rejections?${query}`,
    )
    if(seq === rejectionSeq && current()) rejections.value = result.items
  } catch(e) { if(seq === rejectionSeq && current()) { rejections.value = []; rejectionError.value = e instanceof Error ? e.message : '拒绝台账读取失败' } }
  finally { if(seq === rejectionSeq && current()) rejectionLoading.value = false }
}

async function load() {
  await Promise.all([loadAlerts(), loadRejections()])
}

async function replay(item: RejectionItem) {
  if(replayingId.value !== null) return
  actionError.value = ''; resultMessage.value = ''
  replayingId.value = item.id
  try {
    const result = await api<{ action: string; alertId: number | null; incidentId: number | null; message: string }>(
      `/integrations/alertmanager/rejections/${item.id}/replay`, { method: 'POST' },
    )
    if(!current()) return
    resultMessage.value = `${result.message}${result.alertId ? ` · Alert #${result.alertId}` : ''}`
    await Promise.all([loadAlerts(), loadRejections()])
  } catch(e) { if(current()) actionError.value = e instanceof Error ? e.message : '重放失败，请重试' }
  finally { replayingId.value = null }
}

watch([statusFilter, severityFilter], () => { page.value = 1; void loadAlerts() })
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
      </div>
    </div>
    <div v-if="resultMessage" class="success-banner">{{ resultMessage }}</div>
    <div v-if="actionError" role="alert" class="inline-error">{{ actionError }}</div>
    <div v-if="alertError" role="alert" class="inline-error">{{ alertError }}<button class="secondary-button" @click="loadAlerts">重试读取告警</button></div>
    <p v-else-if="loading" role="status" class="muted">正在读取告警…</p>
    <p v-else-if="!alerts.length" class="muted">当前筛选下没有告警。</p>
    <section class="content-panel full-table-panel">
      <div class="table-scroll">
        <table class="data-table">
          <thead><tr><th>等级</th><th>告警主题</th><th>来源</th><th>资源</th><th>状态</th><th>压缩次数</th><th>关联 Incident</th><th>最近发生</th></tr></thead>
          <tbody>
            <tr v-for="alert in loading ? [] : alerts" :key="alert.id">
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

    <nav v-if="!alertError" class="page-toolbar" aria-label="告警分页"><span>共 {{ total }} 条 · 第 {{ page }} / {{ Math.max(1, Math.ceil(total / 20)) }} 页</span><div class="toolbar-group"><button class="secondary-button" :disabled="loading || page <= 1" @click="page--; loadAlerts()">上一页</button><button class="secondary-button" :disabled="loading || page * 20 >= total" @click="page++; loadAlerts()">下一页</button></div></nav>
    <div class="page-toolbar rejection-toolbar">
      <div><strong>Alertmanager 拒绝台账</strong><span>脱敏快照按保留期清理；补录 CMDB 后可自动或手动重放</span></div>
      <div class="toolbar-group">
        <select v-model="rejectionStatus" aria-label="拒绝项状态">
          <option value="OPEN">待处理</option><option value="SUCCEEDED">已重放</option><option value="">全部状态</option>
        </select>
        <button class="secondary-button" @click="loadRejections"><RefreshCw :size="15" :class="{ spin: rejectionLoading }" />刷新</button>
      </div>
    </div>
    <section class="content-panel full-table-panel rejection-panel">
      <div v-if="rejectionError" role="alert" class="inline-error">{{ rejectionError }}<button class="secondary-button" @click="loadRejections">重试读取台账</button></div>
      <div v-else-if="!rejections.length && !rejectionLoading" class="empty-state compact-empty">
        <ShieldAlert :size="22" /><strong>当前没有匹配的拒绝项</strong>
      </div>
      <div v-else class="table-scroll">
        <table class="data-table">
          <thead><tr><th>状态</th><th>告警</th><th>拒绝原因</th><th>投递 / 重放</th><th>最近接收</th><th>结果</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="item in rejections" :key="item.id">
              <td><span class="status-badge" :class="item.status === 'SUCCEEDED' ? 'status-success' : item.payloadStatus === 'PURGED' ? 'status-neutral' : 'status-danger'">{{ item.status === 'SUCCEEDED' ? '已重放' : item.payloadStatus === 'PURGED' ? '待重新投递' : '待处理' }}</span></td>
              <td><div class="primary-cell"><span>#{{ item.id }} · {{ item.resourceCode ?? (item.payloadStatus === 'PURGED' ? '载荷已清理' : '资源未识别') }}</span><strong>{{ item.alertName ?? (item.payloadStatus === 'PURGED' ? '原告警信息已清理' : '告警字段不完整') }}</strong><small v-if="item.payloadStatus === 'ACTIVE'">{{ item.severity ?? '-' }} · {{ item.alertStatus ?? '-' }} · {{ item.receiver ?? '-' }}</small></div></td>
              <td><div class="rejection-error"><code>{{ item.errorCode }}</code><span>{{ item.errorMessage }}</span><small v-if="item.payloadStatus === 'ACTIVE' && item.redactedFields">已脱敏 {{ item.redactedFields }} 个字段</small><small v-if="item.lastReplayErrorCode">上次重放：{{ item.lastReplayErrorCode }}</small><small v-if="item.autoReplayEnabled && item.nextAutoReplayAt">下次自动重放：{{ formatTime(item.nextAutoReplayAt, true) }}</small><small v-else-if="item.autoReplayExhaustedAt">自动重放已耗尽，可修复后手动重放</small><small v-else-if="!item.autoReplayEnabled && item.status === 'OPEN' && item.payloadStatus === 'ACTIVE' && item.errorCode === 'RESOURCE_NOT_FOUND'">自动重放未启用</small></div></td>
              <td><strong>{{ item.deliveryCount }}</strong> / {{ item.replayCount }}<small v-if="item.autoReplayCount" class="rejection-count-note">其中自动 {{ item.autoReplayCount }} 次</small></td>
              <td>{{ formatTime(item.lastReceivedAt, true) }}</td>
              <td><RouterLink v-if="item.resolvedIncidentId" :to="`/incidents?selected=${item.resolvedIncidentId}`">Incident #{{ item.resolvedIncidentId }}</RouterLink><span v-else>-</span></td>
              <td><button v-if="item.status === 'OPEN' && item.payloadStatus === 'ACTIVE' && canReplay" class="secondary-button compact-button" :disabled="replayingId === item.id" @click="replay(item)"><RotateCcw :size="14" :class="{ spin: replayingId === item.id }" />{{ replayingId === item.id ? '重放中' : '重放' }}</button><span v-else-if="item.status === 'OPEN' && item.payloadStatus === 'PURGED'">需重新投递</span><span v-else>-</span></td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

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
.rejection-count-note { display: block; margin-top: 4px; color: var(--text-muted); white-space: nowrap; }
.compact-button { min-width: 74px; justify-content: center; }
@media (max-width: 720px) {
  .rejection-toolbar { align-items: flex-start; }
  .rejection-error { min-width: 240px; }
}
</style>

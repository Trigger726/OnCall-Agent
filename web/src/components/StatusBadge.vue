<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{ value: string }>()

const labelMap: Record<string, string> = {
  OPEN: '待确认', ACKNOWLEDGED: '已确认', INVESTIGATING: '调查中', MITIGATED: '已缓解',
  RESOLVED: '已恢复', CLOSED: '已关闭', FIRING: '触发中', RUNNING: '正常', DEGRADED: '降级',
  P1: 'P1', P2: 'P2', P3: 'P3', P4: 'P4', ACTIVE: '启用', SENT: '已发送', COMPLETED: '已完成',
}

const tone = computed(() => {
  const value = props.value
  if (['P1', 'OPEN', 'FIRING'].includes(value)) return 'danger'
  if (['P2', 'ACKNOWLEDGED', 'INVESTIGATING', 'DEGRADED'].includes(value)) return 'warning'
  if (['P3', 'MITIGATED'].includes(value)) return 'info'
  if (['RUNNING', 'RESOLVED', 'COMPLETED', 'SENT'].includes(value)) return 'success'
  return 'neutral'
})
</script>

<template>
  <span class="status-badge" :class="`status-${tone}`">{{ labelMap[value] ?? value }}</span>
</template>

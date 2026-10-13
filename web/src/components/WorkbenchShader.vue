<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { CursorRipples, FlowingGradient, Shader } from 'shaders/vue'
import type { MouseMapConfig } from 'shaders/core'

const props = defineProps<{ palette: { colorA: string; colorB: string; colorC: string; colorD: string }; interactive: boolean }>()
const emit = defineEmits<{ ready: []; unavailable: [] }>()
const renderer = ref<InstanceType<typeof Shader> | null>(null)
// null = live renderer; a URL = its last frame; '' = theme-correct static fallback.
const pausedFrame = ref<string | null>(null)
let generation = 0
let pauseRequested = false
const distortion = computed<number | MouseMapConfig>(() => props.interactive
  ? { type: 'mouse', axis: 'x', outputMin: 0.28, outputMax: 0.62, smoothing: 0.16, momentum: 0.12 }
  : 0.42)
function ready() {
  renderer.value?.setFrameRateCap(30)
  renderer.value?.setResolutionScale(0.65)
  emit('ready')
}
function releaseFrame() { if (pausedFrame.value) URL.revokeObjectURL(pausedFrame.value) }
async function pause() {
  if (!renderer.value || pauseRequested) return
  pauseRequested = true
  const current = renderer.value, version = ++generation
  current.pause()
  try {
    const frame = await current.captureImage({ format: 'png' })
    if (version !== generation) return
    releaseFrame()
    pausedFrame.value = URL.createObjectURL(frame)
  } catch { if (version === generation) pausedFrame.value = '' }
  // Removing the renderer also removes native cursor listeners and releases GPU resources.
}
function resume() {
  const remount = pausedFrame.value !== null
  pauseRequested = false
  generation++
  releaseFrame()
  pausedFrame.value = null
  renderer.value?.resume()
  return !remount && Boolean(renderer.value)
}
watch(() => props.palette, () => {
  if (pauseRequested) { generation++; releaseFrame(); pausedFrame.value = '' }
})
onBeforeUnmount(() => { generation++; releaseFrame() })
defineExpose({ pause, resume })
</script>

<template>
  <div class="flow-layer">
    <Shader v-if="pausedFrame === null" ref="renderer" class="flow-renderer" :disable-telemetry="true" @ready="ready" @unavailable="emit('unavailable')">
      <FlowingGradient id="workbench-flow" v-bind="palette" color-space="linear" :speed="0.16" :distortion="distortion" :seed="7" />
      <CursorRipples v-if="interactive" :intensity="3.5" :decay="12" :radius="0.45" :chromatic-split="0" />
    </Shader>
    <img v-if="pausedFrame" :src="pausedFrame" alt="" class="flow-snapshot" />
  </div>
</template>

<style scoped>
.flow-layer, .flow-renderer, .flow-snapshot { width: 100%; height: 100%; }
.flow-layer { position: relative; }
.flow-snapshot { display: block; object-fit: fill; }
</style>

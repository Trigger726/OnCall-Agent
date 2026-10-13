<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, shallowRef, type Component, type ShallowRef } from 'vue'
import { theme } from '@/stores/theme'

const shaderComponent: ShallowRef<Component | null> = shallowRef(null)
const shader = ref<{ pause(): void; resume(): void } | null>(null)
const enabled = ref(true)
const reduced = ref(false)
const state = ref<'idle' | 'loading' | 'active' | 'paused' | 'static'>('idle')
let disposed = false
let media: MediaQueryList | null = null
const canAnimate = computed(() => enabled.value && !reduced.value)
const buttonLabel = computed(() => reduced.value ? '已遵循减少动态设置' : state.value === 'static' ? '当前设备使用静态背景' : enabled.value ? '暂停动态背景' : '开启动画背景')
const colors = computed(() => theme.value === 'light'
  ? { colorA: '#edf1e8', colorB: '#c1d3af', colorC: '#859d77', colorD: '#e3eadb' }
  : { colorA: '#101612', colorB: '#304d37', colorC: '#9ab785', colorD: '#182c21' })

function syncPlayback() {
  if (!shader.value || state.value === 'static') return
  if (canAnimate.value && !document.hidden) {
    shader.value.resume()
    state.value = 'active'
  } else {
    shader.value.pause()
    state.value = 'paused'
  }
}
function onMotionPreference() {
  reduced.value = media?.matches ?? false
  if (canAnimate.value && !shaderComponent.value) void initialize()
  syncPlayback()
}
async function initialize() {
  if (disposed || shaderComponent.value || state.value === 'loading' || state.value === 'static' || !canAnimate.value) return
  if (!(navigator as Navigator & { gpu?: unknown }).gpu) { state.value = 'static'; return }
  state.value = 'loading'
  try {
    // Load the GPU library only on this page and only when animation is allowed.
    const module = await import('./WorkbenchShader.vue')
    if (disposed || !canAnimate.value) { state.value = 'idle'; return }
    shaderComponent.value = module.default
  } catch { if (!disposed) state.value = 'static' }
}
function unavailable() { state.value = 'static'; shaderComponent.value = null }
function toggle() {
  enabled.value = !enabled.value
  try { localStorage.setItem('opspilot_workbench_motion', String(enabled.value)) } catch { /* In-page control still works. */ }
  if (canAnimate.value && !shaderComponent.value) void initialize()
  syncPlayback()
}
onMounted(() => {
  try { enabled.value = localStorage.getItem('opspilot_workbench_motion') !== 'false' } catch { /* Defaults remain usable. */ }
  media = matchMedia('(prefers-reduced-motion: reduce)')
  reduced.value = media.matches
  media.addEventListener('change', onMotionPreference)
  document.addEventListener('visibilitychange', syncPlayback)
  void initialize()
})
onBeforeUnmount(() => {
  disposed = true
  document.removeEventListener('visibilitychange', syncPlayback)
  media?.removeEventListener('change', onMotionPreference)
  // The native Shader component owns and releases its GPU, resize and intersection observers.
})
</script>

<template>
  <div class="workbench-backdrop" :data-effect-state="state">
    <div class="workbench-art" aria-hidden="true">
      <component v-if="shaderComponent" :is="shaderComponent" ref="shader" :palette="colors" class="workbench-canvas"
        :class="{ ready: state === 'active' || state === 'paused' }" @ready="syncPlayback" @unavailable="unavailable" />
    </div>
    <button class="workbench-motion" type="button" :aria-label="buttonLabel" :title="buttonLabel"
      :aria-pressed="enabled && !reduced && state !== 'static'" :disabled="reduced || state === 'static'" @click="toggle">
      <span class="motion-indicator" :class="{ playing: state === 'active' }" />
      {{ reduced || state === 'static' ? '静态背景' : enabled ? '动态背景' : '已暂停' }}
    </button>
  </div>
</template>

<style scoped>
.workbench-backdrop { position: absolute; inset: 0; pointer-events: none; }
.workbench-art { position: absolute; inset: 0 0 0 30%; overflow: hidden; background: radial-gradient(ellipse at 75% 90%, #4a684450, transparent 65%), radial-gradient(ellipse at 70% 5%, #8fa47a38, transparent 60%); mask-image: linear-gradient(to right, transparent, #000 45%); }
.workbench-canvas { display: block; width: 100%; height: 100%; opacity: 0; transition: opacity 400ms ease; }
.workbench-canvas.ready { opacity: .8; }
.workbench-motion { position: absolute; z-index: 2; right: 22px; bottom: 20px; pointer-events: auto; display: flex; align-items: center; gap: 7px; padding: 6px 9px; border: 1px solid var(--line); border-radius: 5px; color: var(--text-muted); background: var(--surface); font-size: 11px; }
.workbench-motion:disabled { opacity: .7; cursor: default; }
.motion-indicator { width: 7px; height: 7px; border-radius: 50%; background: var(--text-muted); }
.motion-indicator.playing { background: var(--highlight); }
@media (max-width: 640px) { .workbench-art { left: 5%; opacity: .65; } .workbench-motion { right: 16px; bottom: 14px; } }
@media (prefers-reduced-motion: reduce) { .workbench-canvas { transition: none; } }
</style>

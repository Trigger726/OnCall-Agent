<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, shallowRef, type Component, type ShallowRef } from 'vue'
import { theme } from '@/stores/theme'

withDefaults(defineProps<{ surface?: 'dashboard' | 'login' | 'conversation' }>(), { surface: 'dashboard' })
const shaderComponent: ShallowRef<Component | null> = shallowRef(null)
const shader = ref<{ pause(): void; resume(): boolean | void } | null>(null)
const enabled = ref(true)
const reduced = ref(false)
const state = ref<'idle' | 'loading' | 'active' | 'paused' | 'static'>('idle')
let disposed = false
let media: MediaQueryList | null = null
const canAnimate = computed(() => enabled.value && !reduced.value)
const buttonLabel = computed(() => reduced.value ? '已遵循减少动态设置' : state.value === 'static' ? '当前设备使用静态背景' : enabled.value ? '暂停动态背景' : '开启动画背景')
const colors = computed(() => theme.value === 'light'
  ? { colorA: '#f5f8f7', colorB: '#d5e5dc', colorC: '#a4c8c2', colorD: '#e8eedf' }
  : { colorA: '#0a0f13', colorB: '#173832', colorC: '#7a9c8b', colorD: '#35645d' })

function syncPlayback() {
  if (!shader.value || state.value === 'static') return
  if (canAnimate.value && !document.hidden) {
    state.value = shader.value.resume() === false ? 'loading' : 'active'
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
    // Each requested surface owns one lazy GPU renderer, only when animation is allowed.
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
  <div class="workbench-backdrop" :class="`workbench-backdrop--${surface}`" :data-effect-state="state"
    :style="{ '--flow-base': colors.colorA, '--flow-glow': colors.colorC }">
    <div class="workbench-art" aria-hidden="true">
      <component v-if="shaderComponent" :is="shaderComponent" ref="shader" :palette="colors" :interactive="state === 'active'" class="workbench-canvas"
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
.workbench-backdrop { position: absolute; inset: 0; pointer-events: none; overflow: hidden; }
.workbench-art { position: absolute; inset: 0; z-index: 0; overflow: hidden; background: radial-gradient(ellipse at 75% 70%, color-mix(in srgb, var(--flow-glow) 45%, transparent), transparent 68%), radial-gradient(ellipse at 15% 10%, color-mix(in srgb, var(--flow-glow) 28%, transparent), transparent 62%), var(--flow-base); }
.workbench-art::after { content: ''; position: absolute; inset: 0; background: linear-gradient(105deg, color-mix(in srgb, var(--canvas) 68%, transparent), transparent 80%); }
.workbench-canvas { display: block; width: 100%; height: 100%; opacity: 0; transition: opacity 400ms ease; }
.workbench-canvas.ready { opacity: .9; }
.workbench-backdrop--conversation .workbench-art { opacity: .6; }
.workbench-motion { position: absolute; z-index: 2; right: 28px; top: 28px; pointer-events: auto; display: flex; align-items: center; gap: 7px; padding: 7px 10px; border: 1px solid var(--line); border-radius: 5px; color: var(--text-muted); background: var(--surface); font-size: 11px; }
.workbench-backdrop--login .workbench-motion { top: 30px; right: 80px; }
.workbench-backdrop--conversation .workbench-motion { top: 15px; right: 148px; }
.workbench-motion:disabled { opacity: .7; cursor: default; }
.motion-indicator { width: 7px; height: 7px; flex-shrink: 0; border-radius: 50%; background: var(--text-muted); }
.motion-indicator.playing { background: var(--highlight); }
@media (max-width: 640px) {
  .workbench-motion { right: 16px; top: 20px; }
  .workbench-backdrop--conversation .workbench-motion { top: 8px; right: 142px; width: 44px; height: 44px; padding: 0; gap: 0; justify-content: center; font-size: 0; }
}
@media (prefers-reduced-motion: reduce) { .workbench-canvas { transition: none; } }
</style>

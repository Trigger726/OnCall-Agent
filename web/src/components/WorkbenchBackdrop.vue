<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, shallowRef, type Component, type ShallowRef } from 'vue'
import { theme } from '@/stores/theme'

withDefaults(defineProps<{ surface?: 'dashboard' | 'login' | 'conversation' }>(), { surface: 'dashboard' })
const shaderComponent: ShallowRef<Component | null> = shallowRef(null)
const shader = ref<{ pause(): void; resume(): boolean | void } | null>(null)
const reduced = ref(false)
const state = ref<'idle' | 'loading' | 'active' | 'paused' | 'static'>('idle')
let disposed = false
let media: MediaQueryList | null = null
const canAnimate = computed(() => !reduced.value)
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
onMounted(() => {
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
  </div>
</template>

<style scoped>
.workbench-backdrop { position: absolute; inset: 0; pointer-events: none; overflow: hidden; }
.workbench-art { position: absolute; inset: 0; z-index: 0; overflow: hidden; background: radial-gradient(ellipse at 75% 70%, color-mix(in srgb, var(--flow-glow) 45%, transparent), transparent 68%), radial-gradient(ellipse at 15% 10%, color-mix(in srgb, var(--flow-glow) 28%, transparent), transparent 62%), var(--flow-base); }
.workbench-art::after { content: ''; position: absolute; inset: 0; background: linear-gradient(105deg, color-mix(in srgb, var(--canvas) 68%, transparent), transparent 80%); }
.workbench-canvas { display: block; width: 100%; height: 100%; opacity: 0; transition: opacity 400ms ease; }
.workbench-canvas.ready { opacity: .9; }
.workbench-backdrop--conversation .workbench-art { opacity: .6; }
@media (prefers-reduced-motion: reduce) { .workbench-canvas { transition: none; } }
</style>

<script setup lang="ts">
import { ref } from 'vue'
import { FlowingGradient, Shader } from 'shaders/vue'

defineProps<{ palette: { colorA: string; colorB: string; colorC: string; colorD: string } }>()
const emit = defineEmits<{ ready: []; unavailable: [] }>()
const renderer = ref<InstanceType<typeof Shader> | null>(null)
function ready() {
  renderer.value?.setFrameRateCap(30)
  emit('ready')
}
defineExpose({ pause: () => renderer.value?.pause(), resume: () => renderer.value?.resume() })
</script>

<template>
  <Shader ref="renderer" :disable-telemetry="true" @ready="ready" @unavailable="emit('unavailable')">
    <FlowingGradient v-bind="palette" :speed="0.22" :distortion="0.35" :seed="7" />
  </Shader>
</template>

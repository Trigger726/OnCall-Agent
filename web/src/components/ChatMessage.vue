<script setup lang="ts">
import { computed, Fragment, h, onBeforeUnmount, ref, type VNodeChild } from 'vue'
import { Check, Copy } from 'lucide-vue-next'
import { chatBlocks, inlineParts, type Citation, type InlinePart } from '@/utils/chatFormatting'

const props = defineProps<{ content: string; citations?: readonly Citation[]; provisional?: boolean }>()
const emit = defineEmits<{ citation: [ref: string] }>()
const blocks = computed(() => chatBlocks(props.content))
const copied = ref<number | null>(null)
const copyError = ref<number | null>(null)
let timer: ReturnType<typeof setTimeout> | undefined, disposed = false

function renderParts(parts: InlinePart[]): VNodeChild[] {
  return parts.map(part => part.type === 'strong' ? h('strong', renderParts(part.children))
    : part.type === 'code' ? h('code', { class: 'chat-inline-code' }, part.text)
    : part.type === 'citation' ? h('button', { type: 'button', class: 'chat-citation',
      title: part.ref, 'aria-label': `查看原文 ${part.number}：${part.label}`,
      onClick: () => emit('citation', part.ref) }, `[${part.number}]`) : part.text)
}
const InlineText = (value: { text: string }) => h(Fragment, renderParts(inlineParts(value.text, props.provisional ? [] : props.citations)))

async function copyCode(index: number) {
  const block = blocks.value[index], snapshot = props.content
  if (!block || block.type !== 'code' || !block.closed || props.provisional) return
  copied.value = null; copyError.value = null
  try {
    await navigator.clipboard.writeText(block.content)
    if (!disposed && props.content === snapshot) copied.value = index
  } catch { if (!disposed && props.content === snapshot) copyError.value = index }
  if (!disposed) { clearTimeout(timer); timer = setTimeout(() => { copied.value = null; copyError.value = null }, 1600) }
}
onBeforeUnmount(() => { disposed = true; clearTimeout(timer) })
</script>

<template>
  <div class="chat-markdown">
    <template v-for="(block, index) in blocks" :key="index">
      <div v-if="block.type === 'code'" class="chat-code-block">
        <header><span>{{ block.language }}<small v-if="!block.closed"> · 未闭合片段</small></span><button v-if="block.closed && !provisional" type="button" :aria-label="copied === index ? '代码已复制' : '复制代码'" @click="copyCode(index)"><Check v-if="copied === index" :size="13" /><Copy v-else :size="13" />{{ copied === index ? '已复制' : '复制' }}</button></header>
        <pre><code>{{ block.content }}</code></pre>
        <small v-if="copyError === index" role="status">复制失败，请手动选中代码</small>
      </div>
      <h3 v-else-if="block.type === 'heading'" :class="`level-${block.level}`"><InlineText :text="block.content" /></h3>
      <p v-else-if="block.type === 'paragraph'"><InlineText :text="block.content" /></p>
      <div v-else-if="block.type === 'bullet'" class="chat-list-line"><i /><span><InlineText :text="block.content" /></span></div>
      <div v-else-if="block.type === 'ordered'" class="chat-list-line ordered"><b>{{ block.order }}</b><span><InlineText :text="block.content" /></span></div>
      <div v-else class="chat-space" />
    </template>
  </div>
</template>

<style scoped>
.chat-code-block { min-width: 0; margin: 14px 0; border: 1px solid var(--line); border-radius: 10px; overflow: hidden; background: var(--surface-muted); }
.chat-code-block header { display: flex; align-items: center; justify-content: space-between; gap: 12px; min-height: 36px; padding: 4px 12px; border-bottom: 1px solid var(--line); color: var(--text-muted); font-size: 11px; }
.chat-code-block header button { display: flex; align-items: center; gap: 5px; min-height: 28px; border: 0; border-radius: 4px; padding: 3px 6px; color: var(--text-muted); background: transparent; }
.chat-code-block header button:hover { color: var(--text); background: var(--surface); }
.chat-code-block pre { max-width: 100%; overflow-x: auto; margin: 0; padding: 14px; white-space: pre; line-height: 1.7; }
.chat-code-block pre code { padding: 0; border-radius: 0; color: var(--text); background: transparent; font-size: 12px; }
.chat-code-block > small { display: block; padding: 0 14px 10px; color: var(--danger); font-size: 12px; }
.chat-markdown :deep(.chat-inline-code) { padding: 2px 5px; border-radius: 4px; color: var(--text); background: var(--surface-muted); font-size: .9em; white-space: break-spaces; overflow-wrap: anywhere; }
.chat-markdown :deep(.chat-citation) { display: inline-flex; align-items: center; justify-content: center; min-width: 26px; min-height: 24px; margin: 0 2px; padding: 0 5px; border: 1px solid var(--line); border-radius: 5px; color: var(--text-muted); background: var(--surface-muted); font-size: 11px; vertical-align: baseline; }
.chat-markdown :deep(.chat-citation:hover) { color: var(--text); border-color: var(--line-strong); }
</style>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { BookOpen, X } from 'lucide-vue-next'
import { formatTime } from '@/services/api'
import { readRunbookCitation, type RunbookCitation } from '@/services/runbookCitations'

const props = defineProps<{ citation: string }>()
const emit = defineEmits<{ close: [] }>()
const dialog = ref<HTMLDialogElement | null>(null)
const source = ref<RunbookCitation | null>(null)
const loading = ref(false)
const error = ref('')
let controller: AbortController | null = null

function onKeydown(event: KeyboardEvent) {
  if (event.key !== 'Tab') return
  const buttons = dialog.value?.querySelectorAll<HTMLButtonElement>('button:not(:disabled)')
  if (!buttons?.length) return
  const first = buttons[0]!, last = buttons[buttons.length - 1]!
  if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus() }
  else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus() }
}

async function load() {
  controller?.abort()
  // Retrying removes the retry button; retain keyboard focus before replacing that content.
  dialog.value?.querySelector<HTMLButtonElement>('button[autofocus]')?.focus()
  const request = new AbortController()
  controller = request
  source.value = null; error.value = ''; loading.value = true
  try {
    const result = await readRunbookCitation(props.citation, request.signal)
    if (!request.signal.aborted) source.value = result
  } catch (caught) {
    if (!request.signal.aborted) error.value = caught instanceof Error ? caught.message : '暂时无法读取原文，请重试'
  } finally { if (controller === request) loading.value = false }
}
onMounted(() => { dialog.value?.showModal(); void load() })
onBeforeUnmount(() => { controller?.abort(); dialog.value?.close() })
</script>

<template>
  <dialog ref="dialog" class="runbook-source-dialog" aria-labelledby="runbook-source-title" @close="emit('close')" @keydown="onKeydown" @click="($event.target === dialog) && dialog?.close()">
    <section>
      <header><div><BookOpen :size="18" /><h2 id="runbook-source-title">引用原文</h2></div><button type="button" class="icon-button" aria-label="关闭引用原文" autofocus @click="dialog?.close()"><X :size="20" /></button></header>
      <p v-if="loading" role="status" class="source-state">正在读取被引用的版本…</p>
      <div v-else-if="error" class="source-state"><p role="alert">{{ error }}</p><button type="button" class="secondary-button" @click="load">重新读取</button></div>
      <article v-else-if="source">
        <h3>{{ source.title }}</h3>
        <p class="source-version">v{{ source.versionNo }} · {{ source.status === 'SUPERSEDED' ? '历史版本，非当前处置指引' : '已发布版本' }}</p>
        <p v-if="source.status === 'SUPERSEDED'" class="source-warning">这是回答当时引用的原文。执行处置前，请核对当前有效手册。</p>
        <dl><div><dt>来源文件</dt><dd>{{ source.sourceName }}</dd></div><div><dt>发布时间</dt><dd>{{ formatTime(source.publishedAt, true) }}</dd></div></dl>
        <h4>{{ source.heading }}</h4>
        <pre class="source-content">{{ source.content }}</pre>
        <footer><span>原始引用编号</span><code>{{ source.citation }}</code></footer>
      </article>
    </section>
  </dialog>
</template>

<style scoped>
.runbook-source-dialog { width: min(680px, calc(100vw - 32px)); max-height: calc(100dvh - 48px); margin: auto; padding: 0; border: 1px solid var(--line); border-radius: 18px; color: var(--text); background: var(--surface); box-shadow: 0 24px 80px #0003; }
.runbook-source-dialog::backdrop { background: #0006; }
.runbook-source-dialog > section { padding: 24px; }
header { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding-bottom: 20px; border-bottom: 1px solid var(--line); }
header > div { display: flex; align-items: center; gap: 10px; }
h2 { margin: 0; font-size: 16px; font-weight: 600; }
h3 { margin: 24px 0 8px; font-size: 20px; line-height: 1.5; overflow-wrap: anywhere; }
h4 { margin: 24px 0 12px; font-size: 14px; }
.source-version, dl, footer, .source-state { font-size: 12px; color: var(--text-muted); }
.source-warning { padding: 12px; border-left: 3px solid var(--warning); background: var(--surface-muted); font-size: 13px; line-height: 1.7; }
dl { display: grid; gap: 10px; margin: 20px 0; } dl > div { display: grid; grid-template-columns: 80px minmax(0, 1fr); gap: 12px; } dd { margin: 0; color: var(--text); overflow-wrap: anywhere; }
.source-content { white-space: pre-wrap; overflow-wrap: anywhere; font-family: inherit; font-size: 14px; line-height: 1.85; margin: 0; }
footer { display: grid; gap: 8px; margin-top: 28px; padding-top: 16px; border-top: 1px solid var(--line); } footer code { overflow-wrap: anywhere; font-size: 11px; }
.source-state { padding: 24px 0; line-height: 1.8; }
@media (max-width: 600px) { .runbook-source-dialog { width: calc(100vw - 20px); max-height: calc(100dvh - 24px); border-radius: 14px; }.runbook-source-dialog > section { padding: 18px; } }
</style>

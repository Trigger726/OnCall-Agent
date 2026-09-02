<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{ content: string }>()

interface InlinePart { text: string; bold: boolean }
interface Block { type: 'heading' | 'paragraph' | 'bullet' | 'ordered' | 'space'; content: string; level?: number }

const blocks = computed<Block[]>(() => props.content.split(/\r?\n/).map((line) => {
  if (!line.trim()) return { type: 'space', content: '' }
  const heading = line.match(/^(#{1,3})\s+(.+)$/)
  if (heading) return { type: 'heading', content: heading[2], level: heading[1].length }
  const bullet = line.match(/^[-*]\s+(.+)$/)
  if (bullet) return { type: 'bullet', content: bullet[1] }
  const ordered = line.match(/^\d+[.、]\s*(.+)$/)
  if (ordered) return { type: 'ordered', content: ordered[1] }
  return { type: 'paragraph', content: line }
}))

function inlineParts(text: string): InlinePart[] {
  const result: InlinePart[] = []
  const pattern = /\*\*(.+?)\*\*/g
  let cursor = 0
  let match: RegExpExecArray | null
  while ((match = pattern.exec(text)) !== null) {
    if (match.index > cursor) result.push({ text: text.slice(cursor, match.index), bold: false })
    result.push({ text: match[1], bold: true })
    cursor = match.index + match[0].length
  }
  if (cursor < text.length) result.push({ text: text.slice(cursor), bold: false })
  return result.length ? result : [{ text, bold: false }]
}
</script>

<template>
  <div class="chat-markdown">
    <template v-for="(block, index) in blocks" :key="index">
      <h3 v-if="block.type === 'heading'" :class="`level-${block.level}`">
        <template v-for="(part, partIndex) in inlineParts(block.content)" :key="partIndex"><strong v-if="part.bold">{{ part.text }}</strong><template v-else>{{ part.text }}</template></template>
      </h3>
      <p v-else-if="block.type === 'paragraph'">
        <template v-for="(part, partIndex) in inlineParts(block.content)" :key="partIndex"><strong v-if="part.bold">{{ part.text }}</strong><template v-else>{{ part.text }}</template></template>
      </p>
      <div v-else-if="block.type === 'bullet'" class="chat-list-line"><i />
        <span><template v-for="(part, partIndex) in inlineParts(block.content)" :key="partIndex"><strong v-if="part.bold">{{ part.text }}</strong><template v-else>{{ part.text }}</template></template></span>
      </div>
      <div v-else-if="block.type === 'ordered'" class="chat-list-line ordered"><b>{{ blocks.slice(0, index + 1).filter(item => item.type === 'ordered').length }}</b>
        <span><template v-for="(part, partIndex) in inlineParts(block.content)" :key="partIndex"><strong v-if="part.bold">{{ part.text }}</strong><template v-else>{{ part.text }}</template></template></span>
      </div>
      <div v-else class="chat-space" />
    </template>
  </div>
</template>

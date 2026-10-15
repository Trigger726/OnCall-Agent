import { parseRunbookCitation } from '../services/runbookCitations'

export interface Citation { ref: string; label: string }
export interface ChatBlock {
  type: 'heading' | 'paragraph' | 'bullet' | 'ordered' | 'space' | 'code'
  content: string
  level?: number
  order?: string
  language?: string
  closed?: boolean
}
export type InlinePart = { type: 'text' | 'code'; text: string }
  | { type: 'strong'; children: InlinePart[] }
  | { type: 'citation'; ref: string; label: string; number: number }

// Deliberately a text-only Markdown subset: never produces HTML or arbitrary URLs.
export function chatBlocks(content: string): ChatBlock[] {
  const blocks: ChatBlock[] = []
  const lines = content.match(/[^\r\n]*(?:\r\n|\r|\n|$)/g) ?? []
  let code: ChatBlock | null = null, marker = ''
  for (let index = 0; index < lines.length; index++) {
    const raw = lines[index]!
    if (!raw && index === lines.length - 1) continue
    const line = raw.replace(/(?:\r\n|\r|\n)$/, '')
    if (code) {
      const close = /^ {0,3}(`+|~+)[ \t]*$/.exec(line)
      if (close && close[1]![0] === marker[0] && close[1]!.length >= marker.length) {
        code.closed = true; code = null
      } else code.content += raw
      continue
    }
    const fence = /^ {0,3}(`{3,}|~{3,})(.*)$/.exec(line)
    if (fence && !(fence[1]![0] === '`' && fence[2]!.includes('`'))) {
      marker = fence[1]!
      code = { type: 'code', content: '', language: /^[a-zA-Z][\w+-]{0,31}(?=\s|$)/.exec(fence[2]!.trim())?.[0] ?? '代码', closed: false }
      blocks.push(code); continue
    }
    const heading = /^ {0,3}(#{1,6})[ \t]+(.+)$/.exec(line)
    const bullet = /^ {0,3}[-*+][ \t]+(.+)$/.exec(line)
    const ordered = /^ {0,3}(\d{1,9})[.)、][ \t]+(.+)$/.exec(line)
    if (!line.trim()) blocks.push({ type: 'space', content: '' })
    else if (heading) blocks.push({ type: 'heading', content: heading[2]!, level: heading[1]!.length })
    else if (bullet) blocks.push({ type: 'bullet', content: bullet[1]! })
    else if (ordered) blocks.push({ type: 'ordered', content: ordered[2]!, order: ordered[1]! })
    else blocks.push({ type: 'paragraph', content: line })
  }
  return blocks
}

export function inlineParts(text: string, citations: readonly Citation[] = [], allowStrong = true): InlinePart[] {
  const lookup = new Map<string, { label: string; number: number }>()
  if (Array.isArray(citations)) citations.forEach((item, index) => {
    if (item && typeof item.ref === 'string' && typeof item.label === 'string'
      && parseRunbookCitation(item.ref) && !lookup.has(item.ref)) lookup.set(item.ref, { label: item.label, number: index + 1 })
  })
  // Pair maximal backtick runs in a linear pass, including unmatched streaming prefixes.
  const ticks = [...text.matchAll(/`+/g)], nextTick = new Map<number, number>(), lastRun = new Map<number, number>()
  for (let index = ticks.length - 1; index >= 0; index--) {
    const tick = ticks[index]!, next = lastRun.get(tick[0].length)
    if (next !== undefined) nextTick.set(tick.index, next)
    lastRun.set(tick[0].length, tick.index)
  }
  const parts: InlinePart[] = [], pattern = /\\[\\`*\[\]]|`+|\*\*|\[runbook:/g
  const append = (value: string) => {
    const last = parts[parts.length - 1]
    if (last?.type === 'text') last.text += value
    else if (value) parts.push({ type: 'text', text: value })
  }
  let cursor = 0, match: RegExpExecArray | null
  while ((match = pattern.exec(text)) !== null) {
    append(text.slice(cursor, match.index))
    const token = match[0], start = match.index
    let end = start + token.length
    if (token.startsWith('\\')) append(token.slice(1))
    else if (token[0] === '`' && nextTick.has(start)) {
      const close = nextTick.get(start)!
      parts.push({ type: 'code', text: text.slice(end, close) }); end = close + token.length
    } else if (token === '**' && allowStrong && text.indexOf('**', end) > end) {
      const close = text.indexOf('**', end)
      parts.push({ type: 'strong', children: inlineParts(text.slice(end, close), citations, false) }); end = close + 2
    } else if (token === '[runbook:') {
      const close = text.indexOf(']', end), ref = close < 0 ? '' : text.slice(start + 1, close), item = lookup.get(ref)
      if (item) { parts.push({ type: 'citation', ref, ...item }); end = close + 1 }
      else append(token)
    } else append(token)
    cursor = end; pattern.lastIndex = end
  }
  append(text.slice(cursor))
  return parts
}

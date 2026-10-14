import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { runInNewContext } from 'node:vm'
import { transform } from 'esbuild'

const source = readFileSync(new URL('../src/views/AssistantView.vue', import.meta.url), 'utf8')
const handler = source.match(/function onComposerKeydown\(event: KeyboardEvent\) \{[\s\S]*?\n\}/)[0]
const compiled = (await transform(handler + '\nglobalThis.keydown = onComposerKeydown', { loader: 'ts' })).code
test('Enter sends once, but IME composition and modified Enter never submit', () => {
  let sends = 0, prevented = 0
  const fixture = { sendMessage: () => { sends++ } }
  runInNewContext(compiled, fixture)
  for (const flag of ['shiftKey', 'ctrlKey', 'altKey', 'metaKey', 'isComposing']) {
    fixture.keydown({ key: 'Enter', [flag]: true, preventDefault: () => { prevented++ } })
  }
  assert.equal(sends, 0); assert.equal(prevented, 0)
  fixture.keydown({ key: 'Enter', preventDefault: () => { prevented++ } })
  assert.equal(sends, 1); assert.equal(prevented, 1)
})
test('runtime label comes from the server, not a guessed brand or model', () => {
  assert.match(source, /api<AssistantRuntime>\('\/assistant\/runtime'\)/)
  assert.match(source, /运行模式待确认/)
  assert.match(source, /runtime\.value\?\.label/)
  assert.match(source, /aria-label="消息内容"/)
  assert.match(source, /rows="2" maxlength="10000"/)
})
test('existing themes and native motion stay intact, narrow screens prioritise conversation', () => {
  assert.match(source, /<WorkbenchBackdrop surface="conversation"/)
  assert.match(source, /@media \(max-width: 900px\)/)
  assert.match(source, /height: calc\(100dvh - 64px\)/)
  assert.doesNotMatch(source, /workbench-motion|font-family:.*serif/)
})

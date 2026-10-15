import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { runInNewContext } from 'node:vm'
import { fileURLToPath } from 'node:url'
import { build, transform } from 'esbuild'

const built = await build({ entryPoints: [fileURLToPath(new URL('../src/utils/chatFormatting.ts', import.meta.url))], bundle: true, write: false, platform: 'node', format: 'esm' })
const { chatBlocks, inlineParts } = await import(`data:text/javascript;base64,${Buffer.from(built.outputFiles[0].text).toString('base64')}`)
const ref = 'runbook:redis-pool:v2#chunk-0', citations = [{ ref: 'incident:1', label: '事件' }, { ref, label: '连接池手册' }]
const source = readFileSync(new URL('../src/components/ChatMessage.vue', import.meta.url), 'utf8')
const assistant = readFileSync(new URL('../src/views/AssistantView.vue', import.meta.url), 'utf8')
const plain = parts => parts.map(p => p.type === 'strong' ? plain(p.children) : p.type === 'citation' ? `[${p.number}]` : p.text).join('')

test('fenced code preserves exact CRLF, indentation, blank lines, HTML and final newline', () => {
  const body = '  redis-cli INFO stats\r\n\t# 中文🙂\r\n\r\n<img onerror="alert(1)">\r\n'
  const result = chatBlocks('说明\r\n```bash\r\n' + body + '```\r\n结束')
  assert.deepEqual(result[1], { type: 'code', content: body, language: 'bash', closed: true })
  assert.equal(result[2].content, '结束')
})
test('only a same-kind sufficiently long fence closes code; languages are inert bounded labels', () => {
  const blocks = chatBlocks('~~~~sql\nselect 1;\n```\n~~~\n~~~~~\n')
  assert.equal(blocks.length, 1);assert.equal(blocks[0].closed, true);assert.equal(blocks[0].content, 'select 1;\n```\n~~~\n')
  assert.equal(chatBlocks('```<img>\ntext\n```')[0].language, '代码')
  assert.equal(chatBlocks('```bad`info\ntext')[0].type, 'paragraph')
})
test('unclosed streamed fences remain literal code, without inventing a closing delimiter', () => {
  const result = chatBlocks('```sh\n  echo "hello"\n')
  assert.equal(result[0].closed, false);assert.equal(result[0].content, '  echo "hello"\n')
  for (let end = 0; end < 80; end++) assert.doesNotThrow(() => chatBlocks(('```sh\n' + '  echo x\n'.repeat(10) + '```').slice(0, end)))
})
test('keeps authored step numbers across sections, including zero and deliberate non-one starts', () => {
  const result = chatBlocks('## 诊断\n1. 查指标\n2. 查变更\n## 验证\n1. 查恢复\n7) 第七步\n0、 起点')
  assert.deepEqual(result.filter(b => b.type === 'ordered').map(b => b.order), ['1', '2', '1', '7', '0'])
})
test('IP addresses and numeric code tokens are not silently converted into numbered steps', () => {
  for (const line of ['127.0.0.1', '1.2.3', '1234567890. too long', '1.not a step']) assert.equal(chatBlocks(line)[0].type, 'paragraph')
})
test('existing headings, paragraphs, bullets, blank lines and bold remain supported', () => {
  assert.deepEqual(chatBlocks('# 标题\n正文\n- 项目\n\n###### 细节').map(b => b.type), ['heading', 'paragraph', 'bullet', 'space', 'heading'])
  assert.equal(inlineParts('先 **核对** 再恢复')[1].type, 'strong')
})
test('only a known canonical reference becomes a compact citation, using the evidence ordinal', () => {
  const parts = inlineParts(`参见 [${ref}]。`, citations)
  assert.deepEqual(parts[1], { type: 'citation', ref, label: '连接池手册', number: 2 });assert.equal(plain(parts), '参见 [2]。')
  for (const value of ['runbook:unknown-book:v1#chunk-0', 'runbook:../secret:v2#chunk-0', ref + '?next=evil']) assert.equal(inlineParts(`[${value}]`, citations).some(p => p.type === 'citation'), false)
  assert.equal(plain(inlineParts(`[${ref}]`)), `[${ref}]`)
})
test('duplicate evidence references retain the first stable ordinal and label', () => {
  const parts = inlineParts(`[${ref}]`, [...citations, { ref, label: '不要替换' }])
  assert.equal(parts[0].number, 2);assert.equal(parts[0].label, '连接池手册')
})
test('inline code is literal: no bold, citation or URL becomes executable markup', () => {
  const text = `[${ref}] **raw** <script>bad()</script>`
  assert.deepEqual(inlineParts('`' + text + '`', citations), [{ type: 'code', text }])
  assert.equal(plain(inlineParts('``a`b``')), 'a`b')
})
test('unmatched backticks and unknown HTML/links remain visible text', () => {
  const text = '`unfinished <img src=x onerror=evil()> [link](javascript:evil())'
  assert.equal(plain(inlineParts(text, citations)), text)
  assert.equal(inlineParts(text, citations).every(p => p.type === 'text'), true)
})
test('bold can contain safe inline code and a verified citation', () => {
  const parts = inlineParts('**核对 `pending`，见 [' + ref + ']**', citations)
  assert.equal(parts[0].type, 'strong');assert.deepEqual(parts[0].children.map(p => p.type), ['text', 'code', 'text', 'citation'])
})
test('escaped reference delimiters are not promoted to actions', () => {
  assert.equal(inlineParts('\\[' + ref + ']', citations).some(p => p.type === 'citation'), false)
  assert.equal(plain(inlineParts('\\*literal\\*')), '*literal*')
})
test('streaming prefixes never manufacture references or mutate source/evidence', () => {
  const text = `参见 [${ref}] 与 \`代码\``, original = JSON.stringify(citations)
  for (let end = 0; end <= text.length; end++) {
    const parts = inlineParts(text.slice(0, end), citations)
    for (const part of parts.filter(p => p.type === 'citation')) assert.equal(part.ref, ref)
  }
  assert.equal(JSON.stringify(citations), original)
})

const handler = source.match(/async function copyCode\(index: number\) \{[\s\S]*?\n\}/)[0]
const compiled = (await transform(handler + '\nglobalThis.copyCode = copyCode', { loader: 'ts' })).code
function fixture(write = async () => {}) {
  const ctx = { blocks: { value: [{ type: 'code', content: '  echo x\r\n', closed: true }] }, props: { content: 'original', provisional: false }, copied: { value: null }, copyError: { value: null }, disposed: false, timer: undefined, navigator: { clipboard: { writeText: write } }, clearTimeout: () => {}, setTimeout: () => 1 }
  runInNewContext(compiled, ctx);return ctx
}
test('code copy is explicit and copies exact raw code; clipboard denial is contained', async () => {
  const seen = [], f = fixture(async text => { seen.push(text) });await f.copyCode(0);assert.deepEqual(seen, ['  echo x\r\n']);assert.equal(f.copied.value, 0)
  const failed = fixture(async () => { throw Error('permission denied') });await failed.copyCode(0);assert.equal(failed.copyError.value, 0);assert.equal(failed.copied.value, null)
})
test('preview, incomplete and late-unmounted code cannot expose successful copy state', async () => {
  let calls = 0;const f = fixture(async () => { calls++ });f.props.provisional = true;await f.copyCode(0);f.props.provisional = false;f.blocks.value[0].closed = false;await f.copyCode(0);assert.equal(calls, 0)
  let release;const late = fixture(() => new Promise(resolve => { release = resolve })), pending = late.copyCode(0);late.disposed = true;release();await pending;assert.equal(late.copied.value, null)
})
test('Vue text nodes, committed assistant-only citations and existing raw copy/export are retained', () => {
  assert.doesNotMatch(source, /v-html|innerHTML|href=/);assert.match(source, /<pre><code>\{\{ block\.content \}\}<\/code><\/pre>/)
  assert.match(source, /props\.provisional \? \[\] : props\.citations/);assert.match(assistant, /message\.role === 'ASSISTANT' && !message\.provisional \? evidence\(message\) : \[\]/)
  assert.match(assistant, /navigator\.clipboard\.writeText\(message\.content\)/);assert.match(assistant, /@citation="selectedCitation = \$event"/)
})

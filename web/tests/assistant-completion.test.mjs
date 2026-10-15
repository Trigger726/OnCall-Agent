import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { runInNewContext } from 'node:vm'
import { transform } from 'esbuild'

const source = readFileSync(new URL('../src/views/AssistantView.vue', import.meta.url), 'utf8')
const functions = source.slice(source.indexOf('function completedAnswerMatches'), source.indexOf('\nasync function controlRequest'))
  + source.slice(source.indexOf('function acknowledgeRequest'), source.indexOf('\nfunction removeBrokenRecord'))
const compiled = (await transform(functions + '\nglobalThis.actions = { applyRequestState, acknowledgeRequest }', { loader: 'ts' })).code
const completed = { id: 9, status: 'COMPLETED', questionMessageId: 41, answerMessageId: 42, deadlineEpochMs: 2000000000000 }
function fixture(options = {}) {
  const intent = Object.freeze({ schema: 1, actorId: 2, username: 'zhangwei', sessionId: 7, requestKey: 'original-key', content: '你是谁' })
  const detail = { session: { id: 7 }, messages: [{ id: 41, role: 'USER', content: '你是谁' }, { id: 42, role: 'ASSISTANT', content: '真实已保存答案' }] }
  const events = [], ref = value => ({ value }), initial = { session: { id: 7 }, messages: [] }
  const f = { intent, detail, events, active: ref(initial), sessions: ref([{ id: 7 }]), pendingRequest: ref(intent), requestState: ref(null),
    requestNotice: ref('pending'), requestNotFound: ref(false), error: ref(''), sending: ref(false), requestControl: ref(false),
    assistantTerminal: status => !['QUEUED', 'RUNNING'].includes(status), readAssistantSession: async () => detail,
    clearAssistantIntent: () => { events.push('clear'); if (options.clearFailure) throw Error('storage blocked') },
    abortController: { abort: () => { assert.equal(f.pendingRequest.value, intent); events.push('abort') } },
    scrollToBottom: async () => { events.push('scroll') }, requestTerminal: { get value() { return Boolean(f.requestState.value && f.assistantTerminal(f.requestState.value.status)) } },
    lease: { signal: undefined, check: () => { if (options.revoked) throw Error('revoked') } },
  }
  runInNewContext(compiled, f)
  f.apply = state => f.actions.applyRequestState(intent, state, f.lease, () => f.pendingRequest.value === intent)
  return f
}
test('a matched committed answer restores input without a second confirmation', async () => {
  const f = fixture(); await f.apply(completed)
  assert.equal(f.active.value, f.detail); assert.equal(f.pendingRequest.value, null); assert.equal(f.requestState.value, null)
  assert.equal(f.requestNotice.value, ''); assert.deepEqual(f.events, ['clear', 'abort', 'scroll'])
})
test('completion needs exact session, original question ID/content and a non-provisional answer', async () => {
  const mutations = [f => { f.detail.session.id = 99 }, f => { f.detail.messages[0].id = 99 }, f => { f.detail.messages[0].content = '其他问题' },
    f => { f.detail.messages[1].id = 99 }, f => { f.detail.messages[1].role = 'USER' }, f => { f.detail.messages[1].content = '  ' }, f => { f.detail.messages[1].provisional = true }]
  for (const mutate of mutations) {
    const f = fixture(); mutate(f); await assert.rejects(f.apply(completed), /原答案未能回读/)
    assert.equal(f.pendingRequest.value, f.intent); assert.deepEqual(f.events, [])
  }
})
test('unknown and running states remain locked and never cancel or clear the original request', async () => {
  for (const status of ['QUEUED', 'RUNNING']) {
    const f = fixture(); await f.apply({ ...completed, status, answerMessageId: null })
    assert.equal(f.pendingRequest.value, f.intent); assert.equal(f.requestState.value.status, status); assert.deepEqual(f.events, ['scroll'])
  }
})
test('cancelled, timed-out and failed requests retain explicit acknowledgement', async () => {
  for (const status of ['CANCELLED', 'TIMED_OUT', 'FAILED', 'REVOKED', 'SUPERSEDED']) {
    const f = fixture(); await f.apply({ ...completed, status, answerMessageId: null })
    assert.equal(f.pendingRequest.value, f.intent); assert.equal(f.requestState.value.status, status)
    assert.deepEqual(f.events, ['clear', 'scroll']); f.actions.acknowledgeRequest(); assert.equal(f.pendingRequest.value, null)
  }
})
test('storage cleanup failure preserves the receipt, visible error and recovery action', async () => {
  const options = { clearFailure: true }, f = fixture(options); await f.apply(completed)
  assert.equal(f.pendingRequest.value, f.intent); assert.equal(f.requestState.value.status, 'COMPLETED')
  assert.match(f.error.value, /清除失败/); assert.ok(!f.events.includes('abort'))
  f.actions.acknowledgeRequest(); assert.equal(f.pendingRequest.value, f.intent)
  options.clearFailure = false; f.actions.acknowledgeRequest(); assert.equal(f.pendingRequest.value, null); assert.equal(f.error.value, '')
})
test('late readback cannot clear a newer request on the same conversation', async () => {
  const f = fixture(); let release
  f.readAssistantSession = () => new Promise(resolve => { release = resolve })
  const old = f.apply(completed), newer = { ...f.intent, requestKey: 'new-key' }; f.pendingRequest.value = newer; release(f.detail); await old
  assert.equal(f.pendingRequest.value, newer); assert.deepEqual(f.events, [])
})
test('an older running snapshot cannot regress an already confirmed terminal', async () => {
  const f = fixture(); f.requestState.value = completed; await f.apply({ ...completed, status: 'RUNNING', answerMessageId: null })
  assert.equal(f.requestState.value, completed); assert.equal(f.pendingRequest.value, f.intent); assert.deepEqual(f.events, [])
})
test('revoked lease cannot apply an answer or clear storage', async () => {
  const f = fixture({ revoked: true }); await assert.rejects(f.apply(completed), /revoked/)
  assert.equal(f.active.value.messages.length, 0); assert.equal(f.pendingRequest.value, f.intent); assert.deepEqual(f.events, [])
})
test('busy acknowledgement cannot unlock another in-flight operation', () => {
  const f = fixture(); f.requestState.value = completed; f.sending.value = true; f.actions.acknowledgeRequest()
  f.sending.value = false; f.requestControl.value = true; f.actions.acknowledgeRequest()
  assert.equal(f.pendingRequest.value, f.intent); assert.deepEqual(f.events, [])
})
test('sources are a native disclosure and duplicate sidebar mode has been removed', () => {
  assert.match(source, /<details v-if="evidence\(message\)\.length" class="assistant-evidence">/)
  assert.match(source, /<summary><FileText/); assert.match(source, /<code>\{\{ item\.ref \}\}<\/code>/)
  assert.doesNotMatch(source, /assistant-mode-dot/)
  const rail = source.slice(source.indexOf('<aside class="assistant-session-rail"'), source.indexOf('</aside>'))
  assert.doesNotMatch(rail, /<footer>/)
})

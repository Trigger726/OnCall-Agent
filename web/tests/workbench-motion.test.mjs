import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { runInNewContext } from 'node:vm'
import { transform } from 'esbuild'

const source = readFileSync(new URL('../src/components/WorkbenchBackdrop.vue', import.meta.url), 'utf8')
const script = source.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1]
const compiled = (await transform(script + '\nglobalThis.motionFixture={enabled,reduced,state,shader,shaderComponent,toggle,syncPlayback,unavailable}', { loader: 'ts', format: 'cjs' })).code
function fixture({ saved, reduce = false, blocked = false } = {}) {
  const values = new Map([['opspilot_workbench_motion', saved], ['opspilot_token', 'untouched']])
  const mounted = [], cleanup = [], events = new Map()
  const media = { matches: reduce, addEventListener: (_, fn) => events.set('motion', fn), removeEventListener: () => events.delete('motion') }
  const document = { hidden: false, addEventListener: (event, fn) => events.set(event, fn), removeEventListener: event => events.delete(event) }
  const vue = { ref: value => ({ value }), shallowRef: value => ({ value }), computed: fn => ({ get value() { return fn() } }), onMounted: fn => mounted.push(fn), onBeforeUnmount: fn => cleanup.push(fn) }
  const context = { withDefaults: value => value, defineProps: () => ({}), require: name => name === 'vue' ? vue : { theme: { value: 'dark' } }, navigator: {}, document, matchMedia: () => media,
    localStorage: { getItem: key => { if (blocked) throw Error('Blocked'); return values.get(key) }, setItem: (key, value) => { if (blocked) throw Error('Blocked'); values.set(key, value) } } }
  runInNewContext(compiled, context)
  return { ...context.motionFixture, values, media, document, events, mount: () => mounted.forEach(fn => fn()), dispose: () => cleanup.forEach(fn => fn()) }
}
test('reduced motion never mounts or loads a GPU effect', () => {
  const f = fixture({ reduce: true }); f.mount()
  assert.equal(f.reduced.value, true); assert.equal(f.shaderComponent.value, null); assert.equal(f.state.value, 'idle')
})
test('saved pause survives mounting and changes only its own preference', () => {
  const f = fixture({ saved: 'false' }); f.mount()
  assert.equal(f.enabled.value, false); assert.equal(f.shaderComponent.value, null)
  f.toggle(); assert.equal(f.values.get('opspilot_workbench_motion'), 'true'); assert.equal(f.values.get('opspilot_token'), 'untouched')
})
test('unsupported GPU becomes a static fallback without an exception', () => {
  const f = fixture(); assert.doesNotThrow(f.mount)
  assert.equal(f.state.value, 'static'); assert.equal(f.shaderComponent.value, null)
})
test('visibility and user pause control the native renderer', () => {
  const f = fixture({ saved: 'false' }); f.mount(); f.enabled.value = true
  let resumed = 0, paused = 0
  f.shader.value = { resume: () => resumed++, pause: () => paused++ }
  f.syncPlayback(); assert.equal(f.state.value, 'active')
  f.document.hidden = true; f.events.get('visibilitychange')(); assert.equal(f.state.value, 'paused')
  f.document.hidden = false; f.enabled.value = false; f.syncPlayback()
  assert.equal(resumed, 1); assert.equal(paused, 2)
})
test('blocked storage remains usable and route disposal removes listeners', () => {
  const f = fixture({ reduce: true, blocked: true }); assert.doesNotThrow(f.mount); assert.doesNotThrow(f.toggle)
  f.dispose(); assert.equal(f.events.size, 0)
})
test('resuming a frozen frame waits for the replacement renderer to be ready', () => {
  const f = fixture({ saved: 'false' }); f.mount(); f.enabled.value = true
  f.shader.value = { pause() {}, resume: () => false }; f.syncPlayback(); assert.equal(f.state.value, 'loading')
  f.shader.value.resume = () => true; f.syncPlayback(); assert.equal(f.state.value, 'active')
})
test('native Vue renderer is local, capped and telemetry-free', () => {
  const shader = readFileSync(new URL('../src/components/WorkbenchShader.vue', import.meta.url), 'utf8')
  assert.match(shader, /from 'shaders\/vue'/); assert.match(shader, /setFrameRateCap\(30\)/)
  assert.match(shader, /:disable-telemetry="true"/); assert.match(source, /import\('\.\/WorkbenchShader.vue'\)/)
})
test('native cursor motion warps the gradient and adds restrained fluid ripples', () => {
  const shader = readFileSync(new URL('../src/components/WorkbenchShader.vue', import.meta.url), 'utf8')
  assert.match(shader, /type: 'mouse', axis: 'x'/)
  assert.match(shader, /smoothing: 0\.16/)
  assert.match(shader, /CursorRipples v-if="interactive"/)
  assert.match(shader, /setResolutionScale\(0\.65\)/)
  assert.match(shader, /color-space="linear"/)
  assert.match(source, /:interactive="state === 'active'"/)
})
test('paused renderer releases cursor/GPU and stale captures cannot overwrite resumed state', async () => {
  const shader = readFileSync(new URL('../src/components/WorkbenchShader.vue', import.meta.url), 'utf8')
  const code = (await transform(shader.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1] + '\nglobalThis.f={renderer,pausedFrame,pause,resume}', { loader: 'ts', format: 'cjs' })).code
  let resolve, dispose, paletteChanged; const revoked = []
  const context = { require: name => name === 'vue' ? { ref: value => ({ value }), computed: fn => ({ get value() { return fn() } }), watch: (_, fn) => { paletteChanged = fn }, onBeforeUnmount: fn => { dispose = fn } } : {},
    defineProps: () => ({ interactive: true, palette: {} }), defineEmits: () => () => {}, defineExpose: () => {}, URL: { createObjectURL: () => 'blob:frame', revokeObjectURL: url => revoked.push(url) } }
  runInNewContext(code, context); const f = context.f
  f.renderer.value = { pause() {}, resume() {}, captureImage: () => new Promise(done => { resolve = done }) }
  const stale = f.pause(); f.resume(); resolve({}); await stale; assert.equal(f.pausedFrame.value, null)
  const paused = f.pause(); resolve({}); await paused; assert.equal(f.pausedFrame.value, 'blob:frame')
  paletteChanged(); assert.equal(f.pausedFrame.value, ''); assert.deepEqual(revoked, ['blob:frame'])
  f.resume(); const pending = f.pause(); resolve({}); await pending; dispose(); assert.ok(revoked.length >= 2)
  assert.match(shader, /Shader v-if="pausedFrame === null"/)
})
test('login welcome and assistant welcome/conversation share one full-surface backdrop each', () => {
  for (const view of ['LoginView', 'AssistantView', 'DashboardView']) {
    const text = readFileSync(new URL('../src/views/' + view + '.vue', import.meta.url), 'utf8')
    assert.equal((text.match(/<WorkbenchBackdrop\b/g) || []).length, 1)
  }
  assert.match(source, /\.workbench-art \{ position: absolute; inset: 0;/)
  assert.doesNotMatch(source, /mask-image:|inset: 0 0 0 30%/)
})

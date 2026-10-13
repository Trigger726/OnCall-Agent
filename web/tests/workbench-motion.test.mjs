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
  const context = { require: name => name === 'vue' ? vue : { theme: { value: 'dark' } }, navigator: {}, document, matchMedia: () => media,
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
test('native Vue renderer is local, capped and telemetry-free', () => {
  const shader = readFileSync(new URL('../src/components/WorkbenchShader.vue', import.meta.url), 'utf8')
  assert.match(shader, /from 'shaders\/vue'/); assert.match(shader, /setFrameRateCap\(30\)/)
  assert.match(shader, /:disable-telemetry="true"/); assert.match(source, /import\('\.\/WorkbenchShader.vue'\)/)
})

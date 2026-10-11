import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { runInNewContext } from 'node:vm'
import { transform } from 'esbuild'

const boot = readFileSync(new URL('../public/assets/theme.js', import.meta.url), 'utf8')
const store = (await transform(readFileSync(new URL('../src/stores/theme.ts', import.meta.url), 'utf8'), { loader: 'ts', format: 'cjs' })).code

function fixture(saved, blocked = false) {
  const values = new Map([['opspilot_theme', saved], ['opspilot_token', 'untouched']])
  const meta = { content: '', setAttribute: (_, value) => { meta.content = value } }
  const document = { documentElement: { dataset: {} }, querySelector: () => meta }
  const localStorage = {
    getItem: key => { if (blocked) throw Error('Storage disabled'); return values.get(key) ?? null },
    setItem: (key, value) => { if (blocked) throw Error('Storage disabled'); values.set(key, value) },
  }
  const module = { exports: {} }
  const context = { document, localStorage, module, require: () => ({ ref: value => ({ value }), readonly: value => value }) }
  runInNewContext(boot, context)
  runInNewContext(store, context)
  return { document, meta, values, ...module.exports }
}

for (const saved of [null, 'dark', 'invalid', '<script>']) {
  test(`prepaint uses safe dark default for ${saved}`, () => {
    const f = fixture(saved)
    assert.equal(f.document.documentElement.dataset.theme, 'dark')
    assert.equal(f.meta.content, '#090909')
    assert.equal(f.theme.value, 'dark')
  })
}
test('saved light is applied before the Vue store initializes', () => {
  const f = fixture('light')
  assert.equal(f.document.documentElement.dataset.theme, 'light')
  assert.equal(f.theme.value, 'light')
  assert.equal(f.meta.content, '#f7f7f7')
})
test('two toggles update theme, metadata and only the preference key', () => {
  const f = fixture('dark')
  for (const expected of ['light', 'dark']) {
    f.toggleTheme()
    assert.equal(f.theme.value, expected)
    assert.equal(f.document.documentElement.dataset.theme, expected)
    assert.equal(f.values.get('opspilot_theme'), expected)
    assert.equal(f.meta.content, expected === 'light' ? '#f7f7f7' : '#090909')
    assert.equal(f.values.get('opspilot_token'), 'untouched')
  }
})
test('disabled storage never prevents boot or an in-page toggle', () => {
  const f = fixture('light', true)
  assert.equal(f.theme.value, 'dark')
  assert.doesNotThrow(() => f.toggleTheme())
  assert.equal(f.theme.value, 'light')
  assert.equal(f.document.documentElement.dataset.theme, 'light')
})
test('missing browser theme metadata does not prevent switching', () => {
  const f = fixture('dark')
  f.document.querySelector = () => null
  assert.doesNotThrow(() => f.toggleTheme())
  assert.equal(f.theme.value, 'light')
})

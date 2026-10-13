import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { createHash } from 'node:crypto'
const read = file => readFileSync(new URL(file, import.meta.url), 'utf8')
test('UI uses locally bundled MiSans while code retains monospace', () => {
  const css = read('../src/terminal.css')
  assert.match(css, /@import url\('\/assets\/fonts\/misans\.css'\)/)
  assert.match(css, /--font-ui: "MiSans VF", "PingFang SC"/)
  assert.match(css, /--font-code: ui-monospace/)
  assert.match(css, /code, pre, kbd, samp \{ font-family: var\(--font-code\); \}/)
})
test('publisher variable weights, unicode ranges and swap are retained without remote requests', () => {
  const css = read('../public/assets/fonts/misans.css')
  assert.equal(/https?:\/\//.test(css), false)
  assert.match(css, /font-weight: 1 999/)
  assert.match(css, /font-display: swap/)
  assert.match(css, /unicode-range:/)
})
test('all official font bytes and bundled license match the source manifest', () => {
  const manifest = JSON.parse(read('../public/assets/fonts/source.json'))
  const hash = file => createHash('sha256').update(readFileSync(new URL('../public/assets/fonts/' + file, import.meta.url))).digest('hex')
  assert.equal(manifest.family, 'MiSans VF')
  assert.equal(manifest.files.length, 56)
  for (const font of manifest.files) assert.equal(hash(font.file), font.sha256, font.file)
  assert.equal(hash('MiSans-LICENSE.pdf'), manifest.licenseSha256)
  assert.equal(hash('misans.css'), manifest.localCssSha256)
  assert.match(read('../src/layouts/MainLayout.vue'), /本软件使用小米 MiSans 字体/)
})

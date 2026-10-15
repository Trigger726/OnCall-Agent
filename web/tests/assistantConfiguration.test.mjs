import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { compileScript, compileTemplate, parse } from '@vue/compiler-sfc'

const file = new URL('../src/components/AssistantConfigurationDialog.vue', import.meta.url)
const source = readFileSync(file, 'utf8'), assistant = readFileSync(new URL('../src/views/AssistantView.vue', import.meta.url), 'utf8')
test('configuration is a native labelled modal with deliberate focus and native close lifecycle', () => {
  assert.match(source, /<dialog[^>]*aria-labelledby="assistant-config-title"/)
  assert.match(source, /onMounted\(\(\) => dialog.value\?\.showModal\(\)\)/)
  assert.match(source, /onBeforeUnmount\(\(\) => dialog.value\?\.close\(\)\)/)
  assert.match(source, /@close="emit\('close'\)"/)
  assert.match(source, /aria-label="关闭模型与 API" autofocus/)
})
test('no secret input, browser persistence, network call or fake save in deployment guidance', () => {
  assert.doesNotMatch(source, /<input|<textarea|v-html|localStorage|sessionStorage|fetch\(|navigator.clipboard|api\</)
  assert.match(source, /不是在线密钥编辑器/)
  assert.match(source, /这里不会读取、显示或保存密钥/)
  assert.match(source, /DASHSCOPE_API_KEY/)
  assert.match(source, /AI_ENABLED=true/)
  assert.match(source, /src\/main\/resources\/application.yml/)
  assert.match(source, /\.env.example/)
  assert.match(source, /未配置 Spring Boot 自动加载/)
  assert.match(source, /重启使用这些变量的后端进程/)
  assert.match(source, /重新构建实际运行的 JAR/)
})
test('all real mode outcomes and unavailable metadata remain explicit', () => {
  assert.match(source, /runtime\?\.mode === 'MODEL'/)
  assert.match(source, /runtime\?\.mode === 'RULES'/)
  assert.match(source, /运行模式待确认/)
  assert.match(source, /不代表密钥有效、额度充足或服务此刻可用/)
  assert.match(source, /不会发送测试请求/)
})
test('assistant keeps visible entry during empty/loaded/error views and closes on account change', () => {
  assert.match(assistant, /aria-label="模型与 API 配置" aria-haspopup="dialog"/)
  assert.match(assistant, /function onSessionChange\(\) \{[\s\S]*?configurationOpen.value = false/)
  assert.match(assistant, /<AssistantConfigurationDialog v-if="configurationOpen" :runtime="runtime"/)
  assert.match(assistant, /assistant-config-entry \{ min-height: 44px/)
})
test('actual Vue script and template compile together without errors', () => {
  const { descriptor } = parse(source)
  const script = compileScript(descriptor, { id: 'config-test' })
  const result = compileTemplate({ source: descriptor.template.content, filename: file.pathname, id: 'config-test', compilerOptions: { bindingMetadata: script.bindings } })
  assert.deepEqual(result.errors, [])
})

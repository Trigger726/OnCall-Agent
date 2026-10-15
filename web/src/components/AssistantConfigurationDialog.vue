<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { X } from 'lucide-vue-next'

defineProps<{ runtime: { mode: 'RULES' | 'MODEL'; provider: string | null; model: string | null; label: string } | null }>()
const emit = defineEmits<{ close: [] }>()
const dialog = ref<HTMLDialogElement | null>(null)
onMounted(() => dialog.value?.showModal())
onBeforeUnmount(() => dialog.value?.close())
</script>

<template>
  <dialog ref="dialog" class="assistant-config-dialog" aria-labelledby="assistant-config-title" @close="emit('close')" @click="($event.target === dialog) && dialog?.close()">
    <section>
      <header><div><h2 id="assistant-config-title">模型与 API</h2><p>部署配置说明，不是在线密钥编辑器</p></div><button type="button" class="icon-button" aria-label="关闭模型与 API" autofocus @click="dialog?.close()"><X :size="20" /></button></header>
      <section aria-labelledby="assistant-config-mode">
        <h3 id="assistant-config-mode">当前模式</h3>
        <template v-if="runtime?.mode === 'MODEL'"><strong>{{ runtime.provider ?? '模型服务' }}<template v-if="runtime.model"> · {{ runtime.model }}</template></strong><p>页面加载时已配置模型。此状态不代表密钥有效、额度充足或服务此刻可用；这里不会发送测试请求。</p></template>
        <template v-else-if="runtime?.mode === 'RULES'"><strong>规则与证据模式</strong><p>未启用大语言模型。助手仍可检索已发布手册、查看事件证据；回答不是云模型生成。</p></template>
        <template v-else><strong>运行模式待确认</strong><p>未能读取部署模式，不能据此判断是否配置了密钥。请刷新页面，或联系部署管理员检查服务。</p></template>
      </section>
      <section aria-labelledby="assistant-config-key">
        <h3 id="assistant-config-key">在哪里配置密钥？</h3>
        <p>当前接入通义 DashScope。由部署管理员在<strong>启动后端的环境变量</strong>中设置：</p>
        <dl><div><dt><code>DASHSCOPE_API_KEY</code></dt><dd>阿里云百炼 / DashScope 的 API 密钥</dd></div><div><dt><code>AI_ENABLED=true</code></dt><dd>启用模型回答；只设置密钥不会打开模型</dd></div></dl>
        <p class="config-note">不要把密钥填到聊天里、前端代码或浏览器存储，也不要上传 Git。这里不会读取、显示或保存密钥。</p>
      </section>
      <section aria-labelledby="assistant-config-file">
        <h3 id="assistant-config-file">对应的代码文件</h3>
        <code class="config-path">src/main/resources/application.yml</code>
        <dl><div><dt><code>spring.ai.dashscope.api-key</code></dt><dd>读取 DASHSCOPE_API_KEY</dd></div><div><dt><code>opspilot.ai.enabled</code></dt><dd>读取 AI_ENABLED，默认 false</dd></div></dl>
        <p>项目根目录的 <code>.env.example</code> 是变量清单，不会自动生效。直接运行 JAR 时，本项目未配置 Spring Boot 自动加载 <code>.env</code>，需由启动终端或 IDE 注入环境变量。</p>
        <p>使用 Docker Compose 时，可在项目 <code>.env</code> 设置这两项供 Compose 插值，再重新创建应用容器；只刷新网页或重启旧容器不会更新它的环境变量。</p>
      </section>
      <section aria-labelledby="assistant-config-apply">
        <h3 id="assistant-config-apply">配置后如何生效？</h3>
        <p>重启使用这些变量的后端进程，再刷新助手页面确认模式。只改 YAML 源码时，还需要重新构建实际运行的 JAR；旧 JAR 不会随源码自动更新。</p>
        <p>密钥配置不等于服务可用。若仍显示规则模式，先核对启动配置是否覆盖了 <code>AI_ENABLED</code>；若模型请求失败，助手可能回退到规则结果。</p>
      </section>
    </section>
  </dialog>
</template>

<style scoped>
.assistant-config-dialog { width: min(620px, calc(100vw - 32px)); max-height: calc(100dvh - 48px); margin: auto; padding: 0; border: 1px solid var(--line); border-radius: 16px; color: var(--text); background: var(--surface); box-shadow: var(--shadow-float); }
.assistant-config-dialog::backdrop { background: #0006; }
.assistant-config-dialog > section { padding: 0 24px 24px; }
header { position: sticky; top: 0; z-index: 1; display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; padding: 24px 0 20px; background: var(--surface); }
h2 { margin: 0; font-size: 20px; line-height: 1.4; letter-spacing: -.02em; }
header p { margin: 6px 0 0; font-size: 12px; }
section > section { padding: 20px 0; border-top: 1px solid var(--line); }
section > section:last-child { padding-bottom: 0; }
h3 { margin: 0 0 12px; font-size: 14px; font-weight: 600; }
p, dd { color: var(--text-muted); font-size: 13px; line-height: 1.8; }
p { margin: 10px 0 0; }
strong { font-weight: 600; }
code { font-size: 12px; overflow-wrap: anywhere; }
.config-path { display: block; line-height: 1.8; }
dl { margin: 14px 0; }
dl > div { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 12px; padding: 8px 0; }
dt { min-width: 0; }
dd { margin: 0; }
.config-note { padding-left: 12px; border-left: 2px solid var(--line); }
button { flex-shrink: 0; min-width: 44px; min-height: 44px; }
button:focus-visible { outline: 2px solid var(--accent); outline-offset: 3px; }
@media (max-width: 600px) { .assistant-config-dialog { width: calc(100vw - 20px); max-height: calc(100dvh - 24px); }.assistant-config-dialog > section { padding: 0 18px 18px; }header { padding-top: 18px; }dl > div { grid-template-columns: minmax(0, 1fr); gap: 3px; } }
</style>

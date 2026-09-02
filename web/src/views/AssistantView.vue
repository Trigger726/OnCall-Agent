<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  Bot, Check, ChevronRight, CircleStop, Copy, Download, FileText, History,
  Menu, MessageSquarePlus, PanelLeft, Play, Plus, Send, Sparkles, Trash2, Workflow, X,
} from 'lucide-vue-next'
import ChatMessage from '@/components/ChatMessage.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import { api, formatTime, RequestError, type PageResponse } from '@/services/api'
import { clearAgentInvestigationIdempotency, streamAgentInvestigation } from '@/services/agentStream'
import type { AgentRun, AgentRunEvent } from '@/types/investigation'

interface SessionSummary {
  id: number; title: string; incidentId: number | null; incidentCode: string | null; incidentTitle: string | null
  incidentSeverity: string | null; incidentStatus: string | null; messageCount: number; lastMessage: string | null; updatedAt: string
}
interface Message { id: number; role: 'USER' | 'ASSISTANT'; content: string; evidenceJson: string | null; createdAt: string }
interface ContextItem { code: string; type: string; title: string; time: string }
interface IncidentContext {
  id: number; incidentCode: string; title: string; description: string; severity: string; status: string; resourceName: string
  alerts: ContextItem[]; changes: ContextItem[]; timeline: ContextItem[]
  latestInvestigationId: number | null; latestHypothesis: string | null; latestSuggestions: string | null
  latestAgentRun: AgentRun | null
}
interface SessionDetail { session: SessionSummary; messages: Message[]; context: IncidentContext | null }
interface IncidentSummary { id: number; incidentCode: string; title: string; severity: string; status: string; resourceName: string }
interface StreamEvent { type: 'meta' | 'delta' | 'done' | 'error'; content: string; messageId: number | null; evidenceJson: string | null }
interface EvidenceRef { ref: string; type: string; label: string }

const route = useRoute()
const router = useRouter()
const sessions = ref<SessionSummary[]>([])
const incidents = ref<IncidentSummary[]>([])
const active = ref<SessionDetail | null>(null)
const loading = ref(true)
const sending = ref(false)
const agentRunning = ref(false)
const draft = ref('')
const error = ref('')
const copiedId = ref<number | null>(null)
const agentEvents = ref<AgentRunEvent[]>([])
const streamedAgentRunId = ref<number | null>(null)
const mobileSessionsOpen = ref(false)
const messageViewport = ref<HTMLElement | null>(null)
let abortController: AbortController | null = null
let agentAbortController: AbortController | null = null

const suggestions = computed(() => active.value?.context
  ? ['总结当前证据', '最可能的根因是什么？', '展示 Agent 调查过程', '下一步应该怎么验证？']
  : ['当前有哪些活跃 Incident？', '告警聚合的处理流程是什么？', '如何使用 CMDB 辅助故障定位？'])
const activeAgentRunId = computed(() => {
  if (streamedAgentRunId.value) return streamedAgentRunId.value
  const run = active.value?.context?.latestAgentRun
  return run && (run.status === 'QUEUED' || run.status === 'RUNNING') ? run.id : null
})

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [sessionRows, incidentPage] = await Promise.all([
      api<SessionSummary[]>('/assistant/sessions'),
      api<PageResponse<IncidentSummary>>('/incidents?size=100'),
    ])
    sessions.value = sessionRows
    incidents.value = incidentPage.items
    const incidentId = Number(route.query.incident)
    if (Number.isFinite(incidentId) && incidentId > 0) {
      const existing = sessionRows.find(item => item.incidentId === incidentId)
      if (existing) await selectSession(existing.id)
      else await createSession(incidentId)
      await router.replace({ query: {} })
    } else if (sessionRows[0]) await selectSession(sessionRows[0].id)
    else await createSession()
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : '加载 OnCall 助手失败'
  } finally { loading.value = false }
}

async function refreshSessions() {
  sessions.value = await api<SessionSummary[]>('/assistant/sessions')
  if (active.value) {
    const summary = sessions.value.find(item => item.id === active.value?.session.id)
    if (summary) active.value = { ...active.value, session: summary }
  }
}

async function runAgentInvestigation() {
  if (!active.value?.context || agentRunning.value) return
  const incidentId = active.value.context.id
  const sessionId = active.value.session.id
  let runId: number | null = null
  let streamError: string | null = null
  agentRunning.value = true
  agentEvents.value = []
  agentAbortController = new AbortController()
  error.value = ''
  try {
    await streamAgentInvestigation(incidentId, 'ONCALL_ASSISTANT', event => {
      agentEvents.value.push(event)
      runId ??= event.runId
      if (event.eventType === 'RUN_QUEUED') streamedAgentRunId.value = event.runId
      if (['RUN_COMPLETED', 'RUN_FAILED', 'RUN_CANCELLED', 'RUN_TIMED_OUT', 'RUN_REJECTED']
        .includes(event.eventType)) streamedAgentRunId.value = null
    }, agentAbortController.signal)
  } catch (caught) {
    if (!(caught instanceof DOMException && caught.name === 'AbortError')) {
      streamError = caught instanceof Error ? caught.message : 'Agent 调查启动失败'
    }
  } finally {
    agentRunning.value = false
    agentAbortController = null
    if (active.value?.session.id === sessionId) {
      try {
        active.value = await api<SessionDetail>(`/assistant/sessions/${sessionId}`)
        const persistedRun = active.value.context?.latestAgentRun
        if (persistedRun && persistedRun.id === runId
          && ['COMPLETED', 'PARTIAL', 'FAILED', 'CANCELLED', 'TIMED_OUT', 'QUEUE_REJECTED'].includes(persistedRun.status)) {
          clearAgentInvestigationIdempotency(incidentId)
          streamedAgentRunId.value = null
          error.value = persistedRun.status === 'FAILED' ? (streamError ?? 'Agent 调查失败') : ''
        } else if (streamError) {
          error.value = streamError
        }
      } catch (caught) {
        error.value = streamError ?? (caught instanceof Error ? caught.message : 'Agent 调查状态刷新失败')
      }
    } else if (streamError) {
      error.value = streamError
    }
  }
}

async function cancelAgentInvestigation() {
  if (!activeAgentRunId.value) return
  const runId = activeAgentRunId.value
  try {
    await api<AgentRun>(`/agent-runs/${runId}/cancel`, {
      method: 'POST', body: JSON.stringify({ reason: 'OnCall 助手显式取消' }),
    })
    error.value = ''
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : '取消调查失败'
  }
}

function agentEventLabel(event: AgentRunEvent) {
  try {
    const payload = JSON.parse(event.payloadJson) as Record<string, unknown>
    return String(payload.title ?? payload.summary ?? event.eventType)
  } catch { return event.eventType }
}

async function createSession(incidentId?: number) {
  const detail = await api<SessionDetail>('/assistant/sessions', {
    method: 'POST', body: JSON.stringify({ incidentId: incidentId ?? null }),
  })
  active.value = detail
  await refreshSessions()
  mobileSessionsOpen.value = false
  await scrollToBottom()
}

async function selectSession(id: number) {
  if (sending.value) return
  active.value = await api<SessionDetail>(`/assistant/sessions/${id}`)
  mobileSessionsOpen.value = false
  await scrollToBottom()
}

async function sendMessage(content = draft.value) {
  const value = content.trim()
  if (!active.value || !value || sending.value) return
  sending.value = true
  error.value = ''
  draft.value = ''
  const sessionId = active.value.session.id
  const now = new Date().toISOString()
  const userMessage: Message = { id: -Date.now(), role: 'USER', content: value, evidenceJson: null, createdAt: now }
  const assistantMessage: Message = { id: userMessage.id - 1, role: 'ASSISTANT', content: '', evidenceJson: null, createdAt: now }
  active.value.messages.push(userMessage, assistantMessage)
  await scrollToBottom()

  abortController = new AbortController()
  try {
    const token = localStorage.getItem('opspilot_token')
    const response = await fetch(`/api/v1/assistant/sessions/${sessionId}/stream`, {
      method: 'POST', signal: abortController.signal,
      headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
      body: JSON.stringify({ content: value }),
    })
    if (!response.ok || !response.body) {
      if (response.status === 401) window.dispatchEvent(new Event('opspilot-auth-expired'))
      throw new RequestError('对话流连接失败', 'ASSISTANT_STREAM_FAILED', response.status)
    }
    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    while (true) {
      const { value: bytes, done } = await reader.read()
      buffer += decoder.decode(bytes, { stream: !done })
      const blocks = buffer.split(/\r?\n\r?\n/)
      buffer = blocks.pop() ?? ''
      for (const block of blocks) {
        const data = block.split(/\r?\n/).filter(line => line.startsWith('data:'))
          .map(line => line.slice(5).trim()).join('')
        if (!data) continue
        const event = JSON.parse(data) as StreamEvent
        if (event.type === 'delta') assistantMessage.content += event.content
        if (event.messageId) assistantMessage.id = event.messageId
        if (event.evidenceJson) assistantMessage.evidenceJson = event.evidenceJson
        if (event.type === 'error') throw new Error(event.content || '助手回答失败')
        await scrollToBottom()
      }
      if (done) break
    }
    await refreshSessions()
  } catch (caught) {
    if (caught instanceof DOMException && caught.name === 'AbortError') {
      if (!assistantMessage.content) assistantMessage.content = '回答已停止。'
    } else {
      assistantMessage.content = assistantMessage.content || '暂时无法完成回答，请稍后重试。'
      error.value = caught instanceof Error ? caught.message : '对话失败'
    }
  } finally {
    sending.value = false
    abortController = null
    await scrollToBottom()
  }
}

function stopStreaming() { abortController?.abort() }

async function clearConversation() {
  if (!active.value || !window.confirm('确定清空当前对话的全部消息吗？')) return
  await api(`/assistant/sessions/${active.value.session.id}/messages`, { method: 'DELETE' })
  active.value.messages = []
  await refreshSessions()
}

async function deleteConversation() {
  if (!active.value || !window.confirm('确定删除当前会话吗？')) return
  await api(`/assistant/sessions/${active.value.session.id}`, { method: 'DELETE' })
  active.value = null
  sessions.value = await api<SessionSummary[]>('/assistant/sessions')
  if (sessions.value[0]) await selectSession(sessions.value[0].id)
  else await createSession()
}

async function exportConversation() {
  if (!active.value) return
  const token = localStorage.getItem('opspilot_token')
  const response = await fetch(`/api/v1/assistant/sessions/${active.value.session.id}/export`, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  })
  if (!response.ok) throw new Error('导出失败')
  const url = URL.createObjectURL(await response.blob())
  const link = document.createElement('a')
  link.href = url
  link.download = `opspilot-${active.value.session.id}.md`
  link.click()
  URL.revokeObjectURL(url)
}

async function copyMessage(message: Message) {
  await navigator.clipboard.writeText(message.content)
  copiedId.value = message.id
  window.setTimeout(() => { copiedId.value = null }, 1200)
}

function evidence(message: Message): EvidenceRef[] {
  if (!message.evidenceJson) return []
  try { return JSON.parse(message.evidenceJson) as EvidenceRef[] }
  catch { return [] }
}

async function scrollToBottom() {
  await nextTick()
  messageViewport.value?.scrollTo({ top: messageViewport.value.scrollHeight, behavior: 'smooth' })
}

onMounted(load)
onBeforeUnmount(() => {
  abortController?.abort()
  agentAbortController?.abort()
})
</script>

<template>
  <div class="page-content assistant-page">
    <div class="assistant-shell">
      <aside class="assistant-session-rail" :class="{ 'mobile-open': mobileSessionsOpen }">
        <header><div><History :size="17" /><strong>会话</strong></div><button class="icon-button" title="关闭会话列表" @click="mobileSessionsOpen = false"><X :size="17" /></button></header>
        <button class="assistant-new-chat" @click="createSession()"><Plus :size="17" />新对话</button>
        <div class="assistant-session-list">
          <button v-for="session in sessions" :key="session.id" :class="{ active: active?.session.id === session.id }" @click="selectSession(session.id)">
            <span class="session-icon"><MessageSquarePlus :size="16" /></span>
            <div><strong>{{ session.title }}</strong><span>{{ session.lastMessage ?? (session.incidentCode ? session.incidentTitle : '开始新的协作对话') }}</span><small>{{ formatTime(session.updatedAt) }} · {{ session.messageCount }} 条消息</small></div>
            <ChevronRight :size="15" />
          </button>
        </div>
        <footer><span class="assistant-mode-dot" /><div><strong>{{ active?.context ? 'INCIDENT CONTEXT' : 'GENERAL CONTEXT' }}</strong><span>证据约束模式</span></div></footer>
      </aside>
      <div v-if="mobileSessionsOpen" class="assistant-mobile-scrim" @click="mobileSessionsOpen = false" />

      <section class="assistant-conversation">
        <header class="assistant-chat-head">
          <button class="icon-button assistant-mobile-sessions" title="打开会话列表" @click="mobileSessionsOpen = true"><PanelLeft :size="18" /></button>
          <div><strong>{{ active?.session.title ?? 'OnCall 助手' }}</strong><span v-if="active?.context">{{ active.context.incidentCode }} · {{ active.context.resourceName }}</span><span v-else>通用运维协作</span></div>
          <div class="assistant-chat-actions">
            <button class="icon-button" title="导出 Markdown" @click="exportConversation"><Download :size="17" /></button>
            <button class="icon-button" title="清空消息" @click="clearConversation"><Trash2 :size="17" /></button>
            <button class="icon-button" title="删除会话" @click="deleteConversation"><X :size="17" /></button>
          </div>
        </header>

        <div ref="messageViewport" class="assistant-message-viewport">
          <div v-if="loading" class="assistant-loading"><span class="assistant-thinking" /><span>正在载入会话</span></div>
          <div v-else-if="active && !active.messages.length" class="assistant-welcome">
            <span class="assistant-orb"><Bot :size="24" /></span>
            <h2>{{ active.context ? '从当前证据开始分析' : '今天需要处理什么？' }}</h2>
            <p v-if="active.context">已载入 {{ active.context.incidentCode }} 的告警、资源、变更与调查记录。</p>
            <p v-else>选择一个 Incident 建立上下文，或直接进行通用运维问答。</p>
            <div class="assistant-suggestions"><button v-for="item in suggestions" :key="item" @click="sendMessage(item)">{{ item }}<ChevronRight :size="15" /></button></div>
          </div>

          <div v-else class="assistant-message-list">
            <article v-for="message in active?.messages" :key="message.id" class="assistant-message" :class="message.role.toLowerCase()">
              <span v-if="message.role === 'ASSISTANT'" class="assistant-message-avatar"><Bot :size="17" /></span>
              <div class="assistant-message-body">
                <header><strong>{{ message.role === 'USER' ? '你' : 'OnCall 助手' }}</strong><time>{{ formatTime(message.createdAt, true) }}</time></header>
                <ChatMessage v-if="message.content" :content="message.content" />
                <div v-else class="assistant-generating"><i /><i /><i /></div>
                <div v-if="evidence(message).length" class="assistant-evidence">
                  <span><FileText :size="13" />证据引用</span>
                  <em v-for="item in evidence(message)" :key="item.ref">{{ item.ref }}</em>
                </div>
                <button v-if="message.content" class="assistant-copy" :title="copiedId === message.id ? '已复制' : '复制回答'" @click="copyMessage(message)"><Check v-if="copiedId === message.id" :size="14" /><Copy v-else :size="14" /></button>
              </div>
            </article>
          </div>
        </div>

        <div class="assistant-composer-wrap">
          <div v-if="error" class="assistant-inline-error">{{ error }}</div>
          <form class="assistant-composer" @submit.prevent="sendMessage()">
            <textarea v-model="draft" rows="1" :disabled="sending" placeholder="询问当前证据、根因假设或下一步动作" @keydown.enter.exact.prevent="sendMessage()" />
            <button v-if="sending" type="button" class="assistant-send" title="停止生成" @click="stopStreaming"><CircleStop :size="19" /></button>
            <button v-else class="assistant-send" title="发送消息" :disabled="!draft.trim()"><Send :size="18" /></button>
          </form>
          <div class="assistant-composer-meta"><span><Sparkles :size="13" />证据约束</span><span>回答不会自动执行生产操作</span></div>
        </div>
      </section>

      <aside class="assistant-context-rail">
        <template v-if="active?.context">
          <header><span>INCIDENT CONTEXT</span><StatusBadge :value="active.context.status" /></header>
          <div class="assistant-context-title"><StatusBadge :value="active.context.severity" /><strong>{{ active.context.title }}</strong><span>{{ active.context.incidentCode }}</span></div>
          <dl><div><dt>影响服务</dt><dd>{{ active.context.resourceName }}</dd></div><div><dt>关联告警</dt><dd>{{ active.context.alerts.length }} 条</dd></div><div><dt>近期变更</dt><dd>{{ active.context.changes.length }} 项</dd></div></dl>
          <section class="assistant-agent-section">
            <div class="assistant-section-head"><h3>Agent 调查</h3><button class="assistant-agent-run" :disabled="agentRunning && !activeAgentRunId" :title="activeAgentRunId ? '取消当前 Agent 调查' : '运行只读 Agent 调查'" @click="activeAgentRunId ? cancelAgentInvestigation() : runAgentInvestigation()"><CircleStop v-if="activeAgentRunId" :size="12" /><Play v-else :size="12" />{{ activeAgentRunId ? '取消' : (agentRunning ? '连接中' : '运行') }}</button></div>
            <div v-if="agentRunning && agentEvents.length" class="assistant-agent-trace live">
              <header><span><Workflow :size="13" />LIVE RUN #{{ agentEvents[0].runId }}</span><em class="running">STREAMING</em></header>
              <div v-for="event in agentEvents.slice(-6)" :key="event.id" :class="event.status?.toLowerCase()"><i /><span>{{ event.phase ?? 'RUN' }}</span><strong>{{ agentEventLabel(event) }}</strong><small>#{{ event.sequence }}</small></div>
              <footer>{{ agentEvents.length }} 个实时事件</footer>
            </div>
            <div v-if="active.context.latestAgentRun" class="assistant-agent-trace">
              <header><span><Workflow :size="13" />RUN #{{ active.context.latestAgentRun.id }}</span><em :class="active.context.latestAgentRun.status.toLowerCase()">{{ active.context.latestAgentRun.status }}</em></header>
              <div v-for="step in active.context.latestAgentRun.steps" :key="step.id" :class="step.status.toLowerCase()"><i /><span>{{ step.phase }}</span><strong>{{ step.title }}</strong><small>{{ step.durationMs }} ms</small></div>
              <footer>{{ active.context.latestAgentRun.steps.length }} 步 · {{ active.context.latestAgentRun.durationMs ?? 0 }} ms</footer>
            </div>
            <div v-else class="assistant-agent-empty">尚未运行调查工具链</div>
          </section>
          <section><h3>证据快照</h3><div class="assistant-context-items"><div v-for="item in [...active.context.alerts, ...active.context.changes].slice(0, 5)" :key="item.code"><span>{{ item.type }}</span><strong>{{ item.title }}</strong><small>{{ item.code }}</small></div></div></section>
          <section><h3>继续追问</h3><div class="assistant-context-prompts"><button v-for="item in suggestions" :key="item" @click="sendMessage(item)">{{ item }}</button></div></section>
          <RouterLink :to="`/incidents?selected=${active.context.id}`" class="assistant-incident-link">返回 Incident 工作台 <ChevronRight :size="15" /></RouterLink>
        </template>
        <template v-else>
          <header><span>SELECT CONTEXT</span></header>
          <div class="assistant-context-empty"><Menu :size="20" /><strong>绑定 Incident</strong><p>新建一个带完整故障上下文的协作会话。</p></div>
          <div class="assistant-incident-picker"><button v-for="incident in incidents.slice(0, 6)" :key="incident.id" @click="createSession(incident.id)"><StatusBadge :value="incident.severity" /><div><strong>{{ incident.title }}</strong><span>{{ incident.incidentCode }} · {{ incident.resourceName }}</span></div><ChevronRight :size="15" /></button></div>
        </template>
      </aside>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  Bot, Check, ChevronRight, CircleStop, Copy, Download, FileText, History,
  Menu, MessageSquarePlus, PanelLeft, Play, Plus, Send, Sparkles, Trash2, Workflow, X,
} from 'lucide-vue-next'
import ChatMessage from '@/components/ChatMessage.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import { api, formatTime, RequestError, type PageResponse } from '@/services/api'
import { clearAgentInvestigationIdempotency, streamAgentInvestigation, subscribeAgentInvestigation } from '@/services/agentStream'
import { captureStreamSession } from '@/services/streamSession'
import {
  assistantStatusLabel, assistantTerminal, cancelAssistantRequest, clearAssistantIntent, freezeAssistantIntent,
  readAssistantIntent, readAssistantRequest, readAssistantSession, removeUnreadableAssistantIntent, streamAssistantRequest,
  type AssistantIntent, type AssistantRequestView,
} from '@/services/assistantRequests'
import type { AgentRun, AgentRunEvent } from '@/types/investigation'

interface SessionSummary {
  id: number; title: string; incidentId: number | null; incidentCode: string | null; incidentTitle: string | null
  incidentSeverity: string | null; incidentStatus: string | null; messageCount: number; lastMessage: string | null; updatedAt: string
}
interface Message { id: number; role: 'USER' | 'ASSISTANT'; content: string; evidenceJson: string | null; createdAt: string; provisional?: boolean }
interface ContextItem { code: string; type: string; title: string; time: string }
interface IncidentContext {
  id: number; incidentCode: string; title: string; description: string; severity: string; status: string; resourceName: string
  alerts: ContextItem[]; changes: ContextItem[]; timeline: ContextItem[]
  latestInvestigationId: number | null; latestHypothesis: string | null; latestSuggestions: string | null
  latestAgentRun: AgentRun | null
}
interface SessionDetail { session: SessionSummary; messages: Message[]; context: IncidentContext | null }
interface IncidentSummary { id: number; incidentCode: string; title: string; severity: string; status: string; resourceName: string }
interface EvidenceRef { ref: string; type: string; label: string }

const route = useRoute()
const router = useRouter()
const sessions = ref<SessionSummary[]>([])
const incidents = ref<IncidentSummary[]>([])
const active = ref<SessionDetail | null>(null)
const loading = ref(true)
const sending = ref(false)
const pendingRequest = ref<Readonly<AssistantIntent> | null>(null)
const requestState = ref<AssistantRequestView | null>(null)
const requestControl = ref(false)
const requestNotFound = ref(false)
const requestRecordBroken = ref(false)
const requestNotice = ref('')
const sessionChanged = ref(false)
const pageToken = localStorage.getItem('opspilot_token')
const agentRunning = ref(false)
const agentControlLoading = ref(false)
const draft = ref('')
const error = ref('')
const copiedId = ref<number | null>(null)
const agentEvents = ref<AgentRunEvent[]>([])
const streamedAgentRunId = ref<number | null>(null)
const mobileSessionsOpen = ref(false)
const mobileContextOpen = ref(false)
const messageViewport = ref<HTMLElement | null>(null)
let abortController: AbortController | null = null
let requestController: AbortController | null = null
let agentAbortController: AbortController | null = null
let selectionVersion = 0
let disposed = false

const suggestions = computed(() => active.value?.context
  ? ['总结当前证据', '最可能的根因是什么？', '展示 Agent 调查过程', '下一步应该怎么验证？']
  : ['当前有哪些活跃 Incident？', '告警聚合的处理流程是什么？', '如何使用 CMDB 辅助故障定位？'])
const requestLocked = computed(() => loading.value || !active.value || sessionChanged.value || sending.value || requestControl.value || Boolean(pendingRequest.value) || requestRecordBroken.value)
const requestTerminal = computed(() => Boolean(requestState.value && assistantTerminal(requestState.value.status)))
const requestLabel = computed(() => requestState.value ? assistantStatusLabel(requestState.value.status)
  : requestNotFound.value ? '暂未查到原请求' : sending.value ? '等待回答' : '结果待确认')
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
    if (disposed || sessionChanged.value) return
    sessions.value = sessionRows
    incidents.value = incidentPage.items
    const incidentId = Number(route.query.incident)
    if (Number.isFinite(incidentId) && incidentId > 0) {
      const existing = sessionRows.find(item => item.incidentId === incidentId)
      if (existing) await selectSession(existing.id)
      else await createSession(incidentId)
    } else if (sessionRows.some(item => item.id === Number(route.query.session))) {
      await selectSession(Number(route.query.session))
    } else if (sessionRows[0]) await selectSession(sessionRows[0].id)
    else await createSession()
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : '加载 OnCall 助手失败'
  } finally { loading.value = false }
}

async function refreshSessions() {
  const version = selectionVersion
  const rows = await api<SessionSummary[]>('/assistant/sessions')
  if (disposed || sessionChanged.value || version !== selectionVersion) return
  sessions.value = rows
  if (active.value) {
    const summary = sessions.value.find(item => item.id === active.value?.session.id)
    if (summary) active.value = { ...active.value, session: summary }
  }
}

async function runAgentInvestigation() {
  await followAgentRun(activeAgentRunId.value ?? undefined)
}

async function followAgentRun(existingRunId?: number) {
  if (!active.value?.context || agentRunning.value) return
  const incidentId = active.value.context.id
  const sessionId = active.value.session.id
  let runId: number | null = existingRunId ?? null
  let streamError: string | null = null
  agentRunning.value = true
  agentEvents.value = []
  const controller = new AbortController()
  agentAbortController = controller
  error.value = ''
  try {
    const onEvent = (event: AgentRunEvent) => {
      if (controller.signal.aborted || agentAbortController !== controller) return
      agentEvents.value.push(event)
      runId ??= event.runId
      if (event.eventType === 'RUN_QUEUED') streamedAgentRunId.value = event.runId
      if (['RUN_COMPLETED', 'RUN_FAILED', 'RUN_CANCELLED', 'RUN_TIMED_OUT', 'RUN_REJECTED']
        .includes(event.eventType)) streamedAgentRunId.value = null
    }
    if (existingRunId) await subscribeAgentInvestigation(existingRunId, onEvent, controller.signal)
    else await streamAgentInvestigation(incidentId, 'ONCALL_ASSISTANT', onEvent, controller.signal)
  } catch (caught) {
    if (caught instanceof DOMException && caught.name === 'AbortError') controller.abort(caught)
    else {
      streamError = caught instanceof Error ? caught.message : 'Agent 调查启动失败'
    }
  } finally {
    if (agentAbortController !== controller) return
    try {
      if (!controller.signal.aborted && active.value?.session.id === sessionId) {
        try {
          const version = selectionVersion
          const detail = await api<SessionDetail>(`/assistant/sessions/${sessionId}`)
          if (version !== selectionVersion || active.value?.session.id !== sessionId) return
          active.value = detail
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
          if (agentAbortController !== controller) return
          error.value = streamError ?? (caught instanceof Error ? caught.message : 'Agent 调查状态刷新失败')
        }
      } else if (streamError) {
        error.value = streamError
      }
    } finally {
      if (agentAbortController === controller) {
        agentRunning.value = false
        agentAbortController = null
      }
    }
  }
}

async function cancelAgentInvestigation() {
  if (!activeAgentRunId.value || agentControlLoading.value) return
  const runId = activeAgentRunId.value
  agentControlLoading.value = true
  try {
    await api<AgentRun>(`/agent-runs/${runId}/cancel`, {
      method: 'POST', body: JSON.stringify({ reason: 'OnCall 助手显式取消' }),
    })
    if (disposed || sessionChanged.value) return
    // The original POST stream runs on the interrupted execution thread and may
    // close before its terminal frame is flushed. Reattach to durable events so
    // the UI always observes the persisted cancellation for this exact run.
    stopAgentSubscription()
    await followAgentRun(runId)
    error.value = ''
  } catch (caught) {
    if (disposed || sessionChanged.value) return
    error.value = caught instanceof Error ? caught.message : '取消调查失败'
  } finally { agentControlLoading.value = false }
}

function agentEventLabel(event: AgentRunEvent) {
  try {
    const payload = JSON.parse(event.payloadJson) as Record<string, unknown>
    return String(payload.title ?? payload.summary ?? event.eventType)
  } catch { return event.eventType }
}

async function createSession(incidentId?: number) {
  if (disposed || sessionChanged.value || sending.value || requestControl.value) return
  const version = ++selectionVersion
  stopAgentSubscription()
  const detail = await api<SessionDetail>('/assistant/sessions', {
    method: 'POST', body: JSON.stringify({ incidentId: incidentId ?? null }),
  })
  if (version !== selectionVersion || sessionChanged.value) return
  active.value = detail
  mobileContextOpen.value = false
  rememberSessionAndResume()
  await refreshSessions()
  mobileSessionsOpen.value = false
  await scrollToBottom()
}

async function selectSession(id: number) {
  if (disposed || sessionChanged.value) return
  if (sending.value || requestControl.value) return
  const version = ++selectionVersion
  if (active.value?.session.id !== id) {
    stopAgentSubscription()
  }
  const detail = await api<SessionDetail>(`/assistant/sessions/${id}`)
  if (version !== selectionVersion || sessionChanged.value) return
  active.value = detail
  mobileContextOpen.value = false
  rememberSessionAndResume()
  mobileSessionsOpen.value = false
  await scrollToBottom()
}

function stopAgentSubscription() {
  agentAbortController?.abort()
  agentAbortController = null
  agentRunning.value = false
  agentEvents.value = []
  streamedAgentRunId.value = null
}

function rememberSessionAndResume() {
  if (!active.value) return
  pendingRequest.value = null
  requestState.value = null
  requestNotFound.value = false
  requestRecordBroken.value = false
  requestNotice.value = ''
  try {
    pendingRequest.value = readAssistantIntent(active.value.session.id)
    if (pendingRequest.value) requestNotice.value = '保留了本标签页的原问题与请求键。请先查询，不会自动重发。'
  } catch (caught) {
    requestRecordBroken.value = true
    requestNotice.value = caught instanceof Error ? caught.message : '本地请求记录无法读取'
  }
  void router.replace({ query: { session: String(active.value.session.id) } })
  if (activeAgentRunId.value && !agentAbortController) void followAgentRun(activeAgentRunId.value)
}

async function sendMessage(content = draft.value) {
  const value = content.trim()
  if (!active.value || !value || requestLocked.value) return
  try { pendingRequest.value = freezeAssistantIntent(active.value.session.id, value) }
  catch (caught) { error.value = caught instanceof Error ? caught.message : '原请求无法保存，未发送'; return }
  draft.value = ''
  await sendFrozenRequest(pendingRequest.value)
}

async function sendFrozenRequest(intent: Readonly<AssistantIntent>) {
  if (disposed || sending.value || requestControl.value || pendingRequest.value !== intent || active.value?.session.id !== intent.sessionId) return
  sending.value = true
  error.value = ''
  requestState.value = null
  requestNotFound.value = false
  requestNotice.value = '已使用固定请求键提交；关闭页面只断开连接，不会取消服务端任务。'
  const version = selectionVersion
  const controller = new AbortController()
  abortController = controller
  const streamSession = captureStreamSession(controller.signal)
  const isCurrentView = () => !disposed && abortController === controller
    && selectionVersion === version && active.value?.session.id === intent.sessionId && pendingRequest.value === intent
  const now = new Date().toISOString()
  const userMessage: Message = { id: -Date.now(), role: 'USER', content: intent.content, evidenceJson: null, createdAt: now }
  const assistantMessage = reactive<Message>({ id: userMessage.id - 1, role: 'ASSISTANT', content: '', evidenceJson: null, createdAt: now })
  active.value.messages = active.value.messages.filter(item => item.id > 0)
  active.value.messages.push(userMessage, assistantMessage)
  try {
    await scrollToBottom()
    streamSession.check()
    await streamAssistantRequest(intent, event => {
      streamSession.check()
      if (!isCurrentView()) throw new DOMException('会话视图已切换', 'AbortError')
      if (event.type === 'generation') assistantMessage.provisional = true
      if (event.type === 'delta' || event.type === 'token') assistantMessage.content += event.content
      if (event.type === 'done') assistantMessage.provisional = false
      if (event.messageId) assistantMessage.id = event.messageId
      if (event.evidenceJson) assistantMessage.evidenceJson = event.evidenceJson
      void scrollToBottom()
    }, streamSession.signal)
    streamSession.check()
    if (!isCurrentView()) return
    const state = await readAssistantRequest(intent, streamSession.signal)
    streamSession.check()
    if (isCurrentView()) await applyRequestState(intent, state, streamSession, isCurrentView)
  } catch (caught) {
    if (!isCurrentView()) return
    if (localStorage.getItem('opspilot_token') !== streamSession.token) return
    if (!requestTerminal.value) {
      active.value!.messages = active.value!.messages.filter(item => item !== userMessage && item !== assistantMessage && item.id > 0)
      requestState.value = null
      requestNotice.value = '连接结束或响应丢失，结果待确认。原问题与请求键已保留，请先查询；不会自动重发。'
      error.value = caught instanceof Error ? caught.message : '原请求结果待确认'
    }
  } finally {
    streamSession.dispose()
    if (abortController === controller) {
      const currentView = isCurrentView()
      sending.value = false
      abortController = null
      if (currentView) await scrollToBottom()
    }
  }
}

async function applyRequestState(intent: AssistantIntent, state: AssistantRequestView,
  lease: ReturnType<typeof captureStreamSession>, current: () => boolean) {
  const detail = await readAssistantSession<SessionDetail>(intent, lease.signal)
  lease.check()
  if (!current()) return
  // An earlier GET must not regress a terminal observed by the concurrent original stream.
  if (requestState.value && assistantTerminal(requestState.value.status) && !assistantTerminal(state.status)) return
  if (state.status === 'COMPLETED' && !detail.messages.some(item => item.id === state.answerMessageId && item.role === 'ASSISTANT')) {
    throw new Error('原答案未能回读，请再次查询原请求')
  }
  active.value = detail
  const index = sessions.value.findIndex(item => item.id === intent.sessionId)
  if (index >= 0) sessions.value[index] = detail.session
  requestState.value = state
  requestNotFound.value = false
  requestNotice.value = state.status === 'COMPLETED' ? '已回读服务端原答案，没有新建问题或再次调用模型。'
    : state.status === 'CANCELLED' ? '服务端已确认取消；外部模型连接可能仍在结束，但不会再提交这次回答。'
    : assistantTerminal(state.status) ? '原请求已经结束，不会自动重跑。核对记录后可继续提问。'
    : '这是查询时的服务端状态，可再次查询或显式取消；不会自动重发。'
  error.value = ''
  if (assistantTerminal(state.status)) clearAssistantIntent(intent)
  await scrollToBottom()
}

async function controlRequest(cancel = false) {
  const intent = pendingRequest.value
  if (!intent || requestControl.value || requestTerminal.value) return
  const version = selectionVersion, controller = new AbortController()
  requestController = controller
  const lease = captureStreamSession(controller.signal)
  const current = () => !disposed && selectionVersion === version && active.value?.session.id === intent.sessionId
    && pendingRequest.value === intent && requestController === controller
  requestControl.value = true
  error.value = ''
  try {
    const state = await (cancel ? cancelAssistantRequest(intent, lease.signal) : readAssistantRequest(intent, lease.signal))
    lease.check()
    if (!current()) return
    await applyRequestState(intent, state, lease, current)
    if (current() && assistantTerminal(state.status)) abortController?.abort()
  } catch (caught) {
    if (!current() || localStorage.getItem('opspilot_token') !== lease.token || requestTerminal.value) return
    requestState.value = null
    requestNotFound.value = caught instanceof RequestError && caught.status === 404
    requestNotice.value = requestNotFound.value ? '404不能排除正在接纳的请求。只有你点击“继续原请求”时，才使用完全相同的键与问题提交。'
      : cancel ? '取消响应未确认，请查询原请求；没有把断线当成取消成功。' : '状态查询未确认，请再次查询；不会自动重发。'
    error.value = caught instanceof Error ? caught.message : '原请求结果待确认'
  } finally {
    lease.dispose()
    if (requestController === controller) { requestController = null; requestControl.value = false }
  }
}

async function retryOriginalRequest() {
  if (pendingRequest.value && requestNotFound.value) await sendFrozenRequest(pendingRequest.value)
}
function acknowledgeRequest() {
  if (!pendingRequest.value || !requestTerminal.value || sending.value || requestControl.value) return
  clearAssistantIntent(pendingRequest.value)
  pendingRequest.value = null; requestState.value = null; requestNotice.value = ''; error.value = ''
}
function removeBrokenRecord() {
  if (!active.value || !requestRecordBroken.value || !window.confirm('这只移除本标签页的损坏记录，不会取消服务端请求。请先核对会话；确定移除？')) return
  removeUnreadableAssistantIntent(active.value.session.id)
  requestRecordBroken.value = false; requestNotice.value = ''; error.value = ''
}

async function clearConversation() {
  if (!active.value || requestLocked.value || !window.confirm('确定清空当前对话的全部消息吗？')) return
  const id = active.value.session.id, version = selectionVersion
  await api(`/assistant/sessions/${id}/messages`, { method: 'DELETE' })
  if (disposed || sessionChanged.value || version !== selectionVersion || active.value?.session.id !== id) return
  active.value.messages = []
  await refreshSessions()
}

async function deleteConversation() {
  if (!active.value || requestLocked.value || !window.confirm('确定删除当前会话吗？')) return
  const id = active.value.session.id, version = selectionVersion
  await api(`/assistant/sessions/${id}`, { method: 'DELETE' })
  if (disposed || sessionChanged.value || version !== selectionVersion || active.value?.session.id !== id) return
  selectionVersion++
  stopAgentSubscription()
  active.value = null
  await refreshSessions()
  if (disposed || sessionChanged.value) return
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
  const blob = await response.blob()
  if (disposed || sessionChanged.value || localStorage.getItem('opspilot_token') !== token) return
  const url = URL.createObjectURL(blob)
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

function onSessionChange() {
  if (localStorage.getItem('opspilot_token') === pageToken) return
  sessionChanged.value = true
  selectionVersion++
  abortController?.abort()
  requestController?.abort()
  stopAgentSubscription()
  active.value = null; sessions.value = []; pendingRequest.value = null; requestState.value = null
  requestRecordBroken.value = false; requestNotice.value = ''; draft.value = ''
  error.value = '登录会话已切换，请刷新页面重新载入。原请求仍按本人账号隔离保留。'
}
onMounted(() => {
  window.addEventListener('storage', onSessionChange)
  window.addEventListener('opspilot-auth-session-changed', onSessionChange)
  void load()
})
onBeforeUnmount(() => {
  window.removeEventListener('storage', onSessionChange)
  window.removeEventListener('opspilot-auth-session-changed', onSessionChange)
  disposed = true
  selectionVersion++
  abortController?.abort()
  requestController?.abort()
  stopAgentSubscription()
})
</script>

<template>
  <div class="page-content assistant-page">
    <div class="assistant-shell">
      <aside class="assistant-session-rail" :class="{ 'mobile-open': mobileSessionsOpen }">
        <header><div><History :size="17" /><strong>会话</strong></div><button class="icon-button" title="关闭会话列表" @click="mobileSessionsOpen = false"><X :size="17" /></button></header>
        <button class="assistant-new-chat" :disabled="sessionChanged || sending || requestControl" @click="createSession()"><Plus :size="17" />新对话</button>
        <div class="assistant-session-list">
          <button v-for="session in sessions" :key="session.id" :disabled="sending || requestControl" :class="{ active: active?.session.id === session.id }" @click="selectSession(session.id)">
            <span class="session-icon"><MessageSquarePlus :size="16" /></span>
            <div><strong>{{ session.title }}</strong><span>{{ session.lastMessage ?? (session.incidentCode ? session.incidentTitle : '开始新的协作对话') }}</span><small>{{ formatTime(session.updatedAt) }} · {{ session.messageCount }} 条消息</small></div>
            <ChevronRight :size="15" />
          </button>
        </div>
        <footer><span class="assistant-mode-dot" /><div><strong>{{ active?.context ? 'INCIDENT CONTEXT' : 'GENERAL CONTEXT' }}</strong><span>证据约束模式</span></div></footer>
      </aside>
      <div v-if="mobileSessionsOpen" class="assistant-mobile-scrim" @click="mobileSessionsOpen = false" />
      <div v-if="mobileContextOpen" class="assistant-context-scrim" @click="mobileContextOpen = false" />

      <section class="assistant-conversation">
        <header class="assistant-chat-head">
          <button class="icon-button assistant-mobile-sessions" title="打开会话列表" aria-label="打开会话列表" :aria-expanded="mobileSessionsOpen" @click="mobileContextOpen = false; mobileSessionsOpen = true"><PanelLeft :size="18" /></button>
          <div><strong>{{ active?.session.title ?? 'OnCall 助手' }}</strong><span v-if="active?.context">{{ active.context.incidentCode }} · {{ active.context.resourceName }}</span><span v-else>通用运维协作</span></div>
          <div class="assistant-chat-actions">
            <button class="icon-button assistant-mobile-context" title="打开调查上下文" aria-label="打开调查上下文" :aria-expanded="mobileContextOpen" aria-controls="assistant-context-panel" @click="mobileSessionsOpen = false; mobileContextOpen = true"><Workflow :size="17" /></button>
            <button class="icon-button" title="导出 Markdown" @click="exportConversation"><Download :size="17" /></button>
            <button class="icon-button" title="清空消息" :disabled="requestLocked" @click="clearConversation"><Trash2 :size="17" /></button>
            <button class="icon-button" title="删除会话" :disabled="requestLocked" @click="deleteConversation"><X :size="17" /></button>
          </div>
        </header>

        <div ref="messageViewport" class="assistant-message-viewport">
          <div v-if="loading" class="assistant-loading"><span class="assistant-thinking" /><span>正在载入会话</span></div>
          <div v-else-if="active && !active.messages.length" class="assistant-welcome">
            <span class="assistant-orb"><Bot :size="24" /></span>
            <h2>{{ active.context ? '从当前证据开始分析' : '今天需要处理什么？' }}</h2>
            <p v-if="active.context">已载入 {{ active.context.incidentCode }} 的告警、资源、变更与调查记录。</p>
            <p v-else>选择一个 Incident 建立上下文，或直接进行通用运维问答。</p>
            <div class="assistant-suggestions"><button v-for="item in suggestions" :key="item" :disabled="requestLocked" @click="sendMessage(item)">{{ item }}<ChevronRight :size="15" /></button></div>
          </div>

          <div v-else class="assistant-message-list">
            <article v-for="message in active?.messages" :key="message.id" class="assistant-message" :class="message.role.toLowerCase()">
              <span v-if="message.role === 'ASSISTANT'" class="assistant-message-avatar"><Bot :size="17" /></span>
              <div class="assistant-message-body">
                <header><strong>{{ message.role === 'USER' ? '你' : 'OnCall 助手' }}</strong><time>{{ formatTime(message.createdAt, true) }}</time></header>
                <small v-if="message.provisional" class="assistant-preview-label" role="status">模型实时生成 · 片段尚未保存</small>
                <ChatMessage v-if="message.content" :content="message.content" />
                <div v-else class="assistant-generating"><i /><i /><i /></div>
                <div v-if="evidence(message).length" class="assistant-evidence">
                  <span><FileText :size="13" />证据引用</span>
                  <em v-for="item in evidence(message)" :key="item.ref">{{ item.ref }}</em>
                </div>
                <button v-if="message.content && !message.provisional" class="assistant-copy" :title="copiedId === message.id ? '已复制' : '复制回答'" @click="copyMessage(message)"><Check v-if="copiedId === message.id" :size="14" /><Copy v-else :size="14" /></button>
              </div>
            </article>
          </div>
        </div>

        <div class="assistant-composer-wrap">
          <section v-if="pendingRequest || requestRecordBroken" class="assistant-request-status" aria-live="polite">
            <div><strong>{{ requestRecordBroken ? '本地请求记录无法读取' : requestLabel }}</strong><span>{{ requestNotice }}</span>
              <small v-if="pendingRequest">原问题：{{ pendingRequest.content }}</small>
              <small v-if="requestState && !requestTerminal">服务端截止时间：{{ formatTime(new Date(requestState.deadlineEpochMs).toISOString(), true) }}（排队计入预算）</small>
            </div>
            <div class="assistant-request-actions">
              <button v-if="requestRecordBroken" type="button" @click="removeBrokenRecord">仅清除本地记录</button>
              <template v-else-if="!requestTerminal">
                <button type="button" :disabled="requestControl" @click="controlRequest()">{{ requestControl ? '核对中…' : '查询原请求' }}</button>
                <button v-if="requestNotFound" type="button" :disabled="sending || requestControl" @click="retryOriginalRequest">继续原请求</button>
                <button type="button" title="取消原请求" :disabled="requestControl" @click="controlRequest(true)"><CircleStop :size="13" />取消原请求</button>
              </template>
              <button v-else type="button" :disabled="sending || requestControl" @click="acknowledgeRequest">继续提问</button>
            </div>
          </section>
          <div v-if="error" class="assistant-inline-error">{{ error }}</div>
          <form class="assistant-composer" @submit.prevent="sendMessage()">
            <textarea v-model="draft" rows="1" :disabled="requestLocked" placeholder="询问当前证据、根因假设或下一步动作" @keydown.enter.exact.prevent="sendMessage()" />
            <button class="assistant-send" title="发送消息" :disabled="requestLocked || !draft.trim()"><Send :size="18" /></button>
          </form>
          <div class="assistant-composer-meta"><span><Sparkles :size="13" />证据约束</span><span>回答不会自动执行生产操作</span></div>
        </div>
      </section>

      <aside id="assistant-context-panel" class="assistant-context-rail" :class="{ 'mobile-open': mobileContextOpen }">
        <template v-if="active?.context">
          <header><span>INCIDENT CONTEXT</span><div class="assistant-context-actions"><StatusBadge :value="active.context.status" /><button class="icon-button assistant-context-close" title="关闭调查上下文" aria-label="关闭调查上下文" @click="mobileContextOpen = false"><X :size="17" /></button></div></header>
          <div class="assistant-context-title"><StatusBadge :value="active.context.severity" /><strong>{{ active.context.title }}</strong><span>{{ active.context.incidentCode }}</span></div>
          <dl><div><dt>影响服务</dt><dd>{{ active.context.resourceName }}</dd></div><div><dt>关联告警</dt><dd>{{ active.context.alerts.length }} 条</dd></div><div><dt>近期变更</dt><dd>{{ active.context.changes.length }} 项</dd></div></dl>
          <section class="assistant-agent-section">
            <div class="assistant-section-head"><h3>Agent 调查</h3><button class="assistant-agent-run" :disabled="agentControlLoading || (agentRunning && !activeAgentRunId)" :title="activeAgentRunId ? '取消当前 Agent 调查' : '运行只读 Agent 调查'" @click="activeAgentRunId ? cancelAgentInvestigation() : runAgentInvestigation()"><CircleStop v-if="activeAgentRunId" :size="12" /><Play v-else :size="12" />{{ agentControlLoading ? '取消中' : (activeAgentRunId ? '取消' : (agentRunning ? '连接中' : '运行')) }}</button></div>
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
          <header><span>SELECT CONTEXT</span><button class="icon-button assistant-context-close" title="关闭调查上下文" aria-label="关闭调查上下文" @click="mobileContextOpen = false"><X :size="17" /></button></header>
          <div class="assistant-context-empty"><Menu :size="20" /><strong>绑定 Incident</strong><p>新建一个带完整故障上下文的协作会话。</p></div>
          <div class="assistant-incident-picker"><button v-for="incident in incidents.slice(0, 6)" :key="incident.id" @click="createSession(incident.id)"><StatusBadge :value="incident.severity" /><div><strong>{{ incident.title }}</strong><span>{{ incident.incidentCode }} · {{ incident.resourceName }}</span></div><ChevronRight :size="15" /></button></div>
        </template>
      </aside>
    </div>
  </div>
</template>

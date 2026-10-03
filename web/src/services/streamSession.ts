/** A client-side lifetime fence, not a substitute for server-side authorization. */
export function captureStreamSession(callerSignal?: AbortSignal) {
  callerSignal?.throwIfAborted()
  const token = localStorage.getItem('opspilot_token')
  const controller = new AbortController()
  const abortFromCaller = () => controller.abort(callerSignal?.reason)
  const check = () => {
    if (localStorage.getItem('opspilot_token') !== token) {
      controller.abort(new DOMException('登录会话已切换', 'AbortError'))
    }
    controller.signal.throwIfAborted()
  }
  const onSessionChange = () => { try { check() } catch { /* already aborted */ } }
  callerSignal?.addEventListener('abort', abortFromCaller, { once: true })
  window.addEventListener('storage', onSessionChange)
  window.addEventListener('opspilot-auth-session-changed', onSessionChange)

  return {
    token,
    signal: controller.signal,
    check,
    expire() {
      check()
      if (token) window.dispatchEvent(new Event('opspilot-auth-expired'))
    },
    async read(reader: ReadableStreamDefaultReader<Uint8Array>) {
      check()
      const cancel = () => { void reader.cancel().catch(() => {}) }
      controller.signal.addEventListener('abort', cancel, { once: true })
      try {
        const chunk = await reader.read()
        check()
        return chunk
      } finally {
        controller.signal.removeEventListener('abort', cancel)
      }
    },
    dispose() {
      callerSignal?.removeEventListener('abort', abortFromCaller)
      window.removeEventListener('storage', onSessionChange)
      window.removeEventListener('opspilot-auth-session-changed', onSessionChange)
    },
  }
}

import { api } from './api'

export function passwordChangeError(current: string, next: string, confirmation: string): string {
  const validInput = (value: string) => {
    if (!value.trim() || value.includes('\0') || new TextEncoder().encode(value).length > 72) return false
    for (const point of value) {
      const code = point.codePointAt(0)!
      if (code >= 0xD800 && code <= 0xDFFF) return false
    }
    return true
  }
  if (!validInput(current)) return '请填写有效当前密码（UTF-8最多72字节）'
  if (!validInput(next) || [...next].length < 15) return '新密码至少15个字符，UTF-8最多72字节；不可包含空字符或无效Unicode'
  if (current === next) return '新密码不能与当前密码相同'
  if (next !== confirmation) return '两次输入的新密码不一致'
  return ''
}

export async function changeOwnSessions(command: 'password' | 'logout-all', body?: {currentPassword: string; newPassword: string}) {
  const result = await api<{ reauthenticationRequired: boolean; scope: string }>(`/auth/${command}`, {
    method: 'POST', signal: AbortSignal.timeout(10000), ...(command === 'password' ? {body:JSON.stringify(body)} : {}),
  })
  if (result?.reauthenticationRequired !== true || result.scope !== 'ALL_ISSUED_SESSIONS') {
    throw new Error('无法确认会话撤销结果，请重新登录核对；不会自动重试')
  }
}

<script setup lang="ts">
import { onBeforeUnmount, ref } from 'vue'
import { useRouter } from 'vue-router'
import { KeyRound, ShieldCheck } from 'lucide-vue-next'
import { auth } from '@/stores/auth'
import { RequestError } from '@/services/api'
import { changeOwnSessions, passwordChangeError } from '@/services/authSessions'

const router = useRouter()
const current = ref(''), next = ref(''), confirmation = ref('')
const confirmed = ref(false), busy = ref(false), error = ref('')
const clearPasswords = () => { current.value = ''; next.value = ''; confirmation.value = '' }
onBeforeUnmount(clearPasswords)

async function submit(command: 'password' | 'logout-all') {
  if (busy.value) return
  error.value = command === 'password' ? passwordChangeError(current.value, next.value, confirmation.value)
    : confirmed.value ? '' : '请先确认将撤销本人全部已签发会话'
  if (error.value) return
  const token = localStorage.getItem('opspilot_token')
  const username = auth.state.user?.username ?? ''
  busy.value = true
  const reauthenticate = async (reason: string) => {
    if (localStorage.getItem('opspilot_token') !== token) return // Never sign out a newer identity.
    auth.logout()
    await router.replace({path:'/login',query:{reason,username}})
  }
  try {
    await changeOwnSessions(command, command === 'password' ? {currentPassword:current.value,newPassword:next.value} : undefined)
    await reauthenticate(command === 'password' ? 'password-changed' : 'sessions-revoked')
  } catch (cause) {
    if (!(cause instanceof RequestError) || cause.status >= 500) {
      // Response loss may follow a committed mutation. No automatic retry or stored password draft.
      await reauthenticate('session-result-unknown')
    } else {
      error.value = cause.code === 'AUTHENTICATION_FAILED' ? '当前密码不正确，请重新输入；登录仍有效。'
        : cause.message
    }
  } finally { clearPasswords(); busy.value = false }
}
</script>

<template>
  <div class="page-content account-security" :aria-busy="busy">
    <div class="page-toolbar"><div><strong>账号安全</strong><span>仅操作本人账号 · 服务端撤销全部已签发会话</span></div></div>
    <p class="security-identity"><ShieldCheck :size="17" />{{ auth.state.user?.displayName }} · {{ auth.state.user?.username }} · {{ auth.state.user?.roleCode }}</p>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    <div class="security-grid">
      <section class="content-panel security-card" aria-labelledby="password-title">
        <h2 id="password-title"><KeyRound :size="18" />修改本人密码</h2>
        <p>新密码至少15个字符、UTF-8最多72字节。成功后所有已签发会话失效，包含本次登录；需要使用新密码重新登录。</p>
        <form @submit.prevent="submit('password')">
          <label>当前密码<input v-model="current" type="password" autocomplete="current-password" :disabled="busy" required /></label>
          <label>新密码<input v-model="next" type="password" autocomplete="new-password" :disabled="busy" required /></label>
          <label>确认新密码<input v-model="confirmation" type="password" autocomplete="new-password" :disabled="busy" required /></label>
          <button class="primary-button" type="submit" :disabled="busy">{{ busy ? '正在确认' : '修改密码并重新登录' }}</button>
        </form>
      </section>
      <section class="content-panel security-card" aria-labelledby="sessions-title">
        <h2 id="sessions-title"><ShieldCheck :size="18" />退出本人全部会话</h2>
        <p>撤销本账号在所有设备和浏览器上已签发的Token，不影响其他账号。密码不变，需要重新登录。</p>
        <label class="security-confirm"><input v-model="confirmed" type="checkbox" :disabled="busy" />我确认退出本人全部会话（包含当前登录）</label>
        <button class="secondary-button" type="button" :disabled="busy || !confirmed" @click="submit('logout-all')">退出全部会话并重新登录</button>
        <div class="security-boundary"><strong>当前边界</strong><p>普通“退出登录”仅清理本浏览器。撤销不是取消调查：已进入执行的任务、已建立的长连接和在途请求仍需后续再授权，不承诺立即中断。</p><p>网络响应丢失时不重试密码命令，会清理本地凭证并提示重新登录核对结果。密码不会写入本地存储或草稿。</p></div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.security-identity { display:flex; align-items:center; gap:8px; margin-bottom:20px; color:var(--text-muted); font-size:13px; overflow-wrap:anywhere; }
.security-grid { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:20px; max-width:1120px; }
.security-card { padding:24px; min-width:0; }
.security-card h2 { display:flex; align-items:center; gap:8px; font-size:16px; margin-bottom:14px; }
.security-card p { color:var(--text-muted); font-size:13px; line-height:1.8; }
.security-card form { display:grid; gap:16px; margin-top:20px; }
.security-card label:not(.security-confirm) { display:grid; gap:7px; font-size:13px; }
.security-card input[type=password] { width:100%; min-width:0; padding:10px 12px; border:1px solid var(--line); border-radius:7px; background:var(--surface); }
.security-card input:focus-visible { outline:2px solid var(--accent); outline-offset:2px; }
.security-confirm { display:flex; align-items:flex-start; gap:8px; margin:24px 0; font-size:13px; line-height:1.6; }
.security-confirm input { margin-top:4px; flex-shrink:0; }
.security-card button { min-height:42px; height:auto; white-space:normal; }
.security-boundary { border-top:1px solid var(--line); margin-top:24px; padding-top:20px; }
.security-boundary strong { font-size:13px; }
.security-boundary p { margin-top:10px; }
.account-security > .form-error { margin:0 0 16px; }
@media (max-width:800px) { .security-grid { grid-template-columns:1fr; } .security-card { padding:20px; } }
</style>

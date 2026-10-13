<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Activity, ArrowRight, KeyRound, UserRound } from 'lucide-vue-next'
import { auth } from '@/stores/auth'
import ThemeToggle from '@/components/ThemeToggle.vue'
import WorkbenchBackdrop from '@/components/WorkbenchBackdrop.vue'

const router = useRouter()
const route = useRoute()
const username = ref(typeof route.query.username === 'string' && route.query.username.length <= 64 ? route.query.username : 'admin')
const password = ref(route.query.reason ? '' : 'OpsPilot@2026')
const loading = ref(false)
const error = ref('')
const notice = computed(() => ({
  expired: '登录已过期，请重新登录后继续操作。',
  'password-changed': '密码已修改，全部已签发会话已撤销。请使用新密码重新登录。',
  'sessions-revoked': '本人全部已签发会话已撤销，请重新登录。',
  'session-result-unknown': '无法确认操作结果，已清理本地凭证且不会自动重试。请重新登录核对；改密请先尝试新密码。',
}[String(route.query.reason)] ?? ''))

async function submit() {
  loading.value = true
  error.value = ''
  try {
    await auth.login(username.value, password.value)
    await router.push('/')
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : '登录失败'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <main class="login-page">
    <WorkbenchBackdrop surface="login" />
    <ThemeToggle class="login-theme-toggle" />
    <section class="login-brand-panel">
      <div class="login-brand"><Activity :size="22" /><strong>OpsPilot</strong></div>
      <div class="login-caption">
        <h1>让每一次故障处置<br />都有证据、有责任人、有闭环。</h1>
        <p>告警关联、值班协作与处置审计</p>
      </div>
    </section>
    <section class="login-form-panel">
      <form class="login-form" @submit.prevent="submit">
        <div class="login-form-title"><h2>登录运维控制台</h2></div>
        <label><span>账号</span><div class="input-with-icon"><UserRound :size="17" /><input v-model="username" autocomplete="username" /></div></label>
        <label><span>密码</span><div class="input-with-icon"><KeyRound :size="17" /><input v-model="password" type="password" autocomplete="current-password" /></div></label>
        <p v-if="notice" class="form-notice">{{ notice }}</p>
        <p v-if="error" class="form-error">{{ error }}</p>
        <button class="primary-button login-submit" type="submit" :disabled="loading">
          <span>{{ loading ? '正在验证' : '进入控制台' }}</span><ArrowRight :size="17" />
        </button>
        <div class="login-meta">演示账号仅用于本地试用</div>
      </form>
    </section>
  </main>
</template>

<style scoped>
.login-page { isolation: isolate; }
.login-brand-panel, .login-form-panel { position: relative; z-index: 1; background: transparent; }
.login-form { background: color-mix(in srgb, var(--surface) 94%, transparent); border: 1px solid var(--line); border-radius: 12px; padding: 32px; }
.login-theme-toggle { z-index: 3; }
@media (max-width: 640px) { .login-form { padding: 24px 20px; } }
</style>

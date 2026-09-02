<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Activity, ArrowRight, KeyRound, UserRound } from 'lucide-vue-next'
import { auth } from '@/stores/auth'

const router = useRouter()
const route = useRoute()
const username = ref('admin')
const password = ref('OpsPilot@2026')
const loading = ref(false)
const error = ref('')
const notice = computed(() => route.query.reason === 'expired' ? '登录已过期，请重新登录后继续操作。' : '')

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
    <section class="login-brand-panel">
      <div class="login-brand"><Activity :size="22" /><strong>OpsPilot</strong></div>
      <div class="signal-visual" aria-hidden="true">
        <span v-for="height in [24, 42, 31, 58, 38, 70, 47, 64, 35, 51]" :key="height" :style="{ height: `${height}%` }" />
      </div>
      <div class="login-caption">
        <span>INCIDENT COMMAND</span>
        <h1>让每一次故障处置<br />都有证据、有责任人、有闭环。</h1>
        <p>华东生产域 · 6 项核心资源在线</p>
      </div>
    </section>
    <section class="login-form-panel">
      <form class="login-form" @submit.prevent="submit">
        <div class="login-form-title"><span>OPERATION CONSOLE</span><h2>登录运维控制台</h2></div>
        <label><span>账号</span><div class="input-with-icon"><UserRound :size="17" /><input v-model="username" autocomplete="username" /></div></label>
        <label><span>密码</span><div class="input-with-icon"><KeyRound :size="17" /><input v-model="password" type="password" autocomplete="current-password" /></div></label>
        <p v-if="notice" class="form-notice">{{ notice }}</p>
        <p v-if="error" class="form-error">{{ error }}</p>
        <button class="primary-button login-submit" type="submit" :disabled="loading">
          <span>{{ loading ? '正在验证' : '进入控制台' }}</span><ArrowRight :size="17" />
        </button>
        <div class="login-meta"><span class="health-dot" />统一身份认证运行正常</div>
      </form>
    </section>
  </main>
</template>

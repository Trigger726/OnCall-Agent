<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  Activity, AlarmClock, BellRing, BookOpenCheck, Boxes, ChevronLeft, ChevronRight,
  BarChart3, ClipboardList, GitBranch, LayoutDashboard, LogOut, Menu, MessageSquareText, RadioTower, ShieldCheck, X,
} from 'lucide-vue-next'
import { auth } from '@/stores/auth'
import ThemeToggle from '@/components/ThemeToggle.vue'

const route = useRoute()
const router = useRouter()
const collapsed = ref(false)
const mobileOpen = ref(false)
const now = ref(new Date())
let timer = 0

const nav = [
  { to: '/', label: '运行总览', icon: LayoutDashboard },
  { to: '/incidents', label: 'Incident', icon: RadioTower },
  { to: '/assistant', label: 'OnCall 助手', icon: MessageSquareText },
  { to: '/alerts', label: '告警中心', icon: BellRing },
  { to: '/cmdb', label: '资源台账', icon: Boxes },
  { to: '/on-call', label: '值班与升级', icon: AlarmClock },
  { to: '/runbooks', label: '处置手册', icon: BookOpenCheck },
  { to: '/analytics', label: '运营分析', icon: BarChart3 },
  { to: '/problems', label: '问题治理', icon: GitBranch },
  { to: '/audit', label: '审计日志', icon: ClipboardList },
  { to: '/account/security', label: '账号安全', icon: ShieldCheck },
]

const pageTitle = computed(() => String(route.meta.title ?? 'OpsPilot'))
const visibleNav = computed(() => nav.filter(item => item.to !== '/audit' || ['ADMIN','OPS_MANAGER','AUDITOR'].includes(auth.state.user?.roleCode ?? '')))
const menuButton = ref<HTMLButtonElement | null>(null)
const mobileClose = ref<HTMLButtonElement | null>(null)
watch(mobileOpen, async open => { if(open) { await nextTick(); mobileClose.value?.focus() } else menuButton.value?.focus() })
function escapeNav(event: KeyboardEvent) { if(event.key === 'Escape' && mobileOpen.value) mobileOpen.value = false }
const clock = computed(() => new Intl.DateTimeFormat('zh-CN', {
  month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false,
}).format(now.value))

onMounted(() => { timer = window.setInterval(() => { now.value = new Date() }, 1000); window.addEventListener('keydown', escapeNav) })
onBeforeUnmount(() => { window.clearInterval(timer); window.removeEventListener('keydown', escapeNav) })

function signOut() {
  auth.logout()
  void router.push('/login')
}
</script>

<template>
  <div class="app-shell" :class="{ 'sidebar-collapsed': collapsed }">
    <aside id="primary-sidebar" class="sidebar" :class="{ 'mobile-open': mobileOpen }">
      <div class="brand-row">
        <span class="brand-mark"><Activity :size="18" /></span>
        <div class="brand-copy">
          <strong>OpsPilot</strong>
          <span>智能运维平台</span>
        </div>
        <button ref="mobileClose" class="icon-button mobile-close" title="关闭导航" @click="mobileOpen = false"><X :size="18" /></button>
      </div>
      <nav class="primary-nav" aria-label="主导航">
        <RouterLink v-for="item in visibleNav" :key="item.to" :to="item.to" :title="collapsed ? item.label : undefined" @click="mobileOpen = false">
          <component :is="item.icon" :size="18" />
          <span>{{ item.label }}</span>
        </RouterLink>
      </nav>
      <div class="sidebar-foot">
        <button class="collapse-button" :title="collapsed ? '展开侧栏' : '收起侧栏'" @click="collapsed = !collapsed">
          <ChevronRight v-if="collapsed" :size="17" />
          <ChevronLeft v-else :size="17" />
          <span>收起侧栏</span>
        </button>
        <a class="font-credit" href="/assets/fonts/MiSans-LICENSE.pdf" target="_blank" rel="noopener" title="本软件使用小米 MiSans 字体，查看授权协议">MiSans 字体 · 小米</a>
      </div>
    </aside>

    <div v-if="mobileOpen" class="mobile-scrim" @click="mobileOpen = false" />

    <main class="main-frame">
      <header class="topbar">
        <button ref="menuButton" class="icon-button mobile-menu" title="打开导航" :aria-expanded="mobileOpen" aria-controls="primary-sidebar" @click="mobileOpen = true"><Menu :size="20" /></button>
        <div class="page-heading">
          <h1>{{ pageTitle }}</h1>
        </div>
        <div class="topbar-actions">
          <time>{{ clock }}</time>
          <ThemeToggle />
          <div class="user-block">
            <span class="user-avatar">{{ auth.state.user?.displayName.slice(0, 1) }}</span>
            <div><strong>{{ auth.state.user?.displayName }}</strong><span>{{ auth.state.user?.roleCode }}</span></div>
          </div>
          <button class="icon-button" title="退出登录" @click="signOut"><LogOut :size="17" /></button>
        </div>
      </header>
      <RouterView />
    </main>
  </div>
</template>

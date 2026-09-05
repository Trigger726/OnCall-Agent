<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  Activity, AlarmClock, BellRing, BookOpenCheck, Boxes, ChevronLeft, ChevronRight,
  BarChart3, ClipboardList, GitBranch, LayoutDashboard, LogOut, Menu, MessageSquareText, RadioTower, Search, ShieldCheck, X,
} from 'lucide-vue-next'
import { auth } from '@/stores/auth'

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
  { to: '/cmdb', label: '资源与拓扑', icon: Boxes },
  { to: '/on-call', label: '值班与升级', icon: AlarmClock },
  { to: '/runbooks', label: '处置手册', icon: BookOpenCheck },
  { to: '/analytics', label: '运营分析', icon: BarChart3 },
  { to: '/problems', label: '问题治理', icon: GitBranch },
  { to: '/audit', label: '审计日志', icon: ClipboardList },
]

const pageTitle = computed(() => String(route.meta.title ?? 'OpsPilot'))
const clock = computed(() => new Intl.DateTimeFormat('zh-CN', {
  month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false,
}).format(now.value))

onMounted(() => { timer = window.setInterval(() => { now.value = new Date() }, 1000) })
onBeforeUnmount(() => window.clearInterval(timer))

function signOut() {
  auth.logout()
  void router.push('/login')
}
</script>

<template>
  <div class="app-shell" :class="{ 'sidebar-collapsed': collapsed }">
    <aside class="sidebar" :class="{ 'mobile-open': mobileOpen }">
      <div class="brand-row">
        <span class="brand-mark"><Activity :size="18" /></span>
        <div class="brand-copy">
          <strong>OpsPilot</strong>
          <span>智能运维平台</span>
        </div>
        <button class="icon-button mobile-close" title="关闭导航" @click="mobileOpen = false"><X :size="18" /></button>
      </div>
      <nav class="primary-nav" aria-label="主导航">
        <RouterLink v-for="item in nav" :key="item.to" :to="item.to" :title="collapsed ? item.label : undefined" @click="mobileOpen = false">
          <component :is="item.icon" :size="18" />
          <span>{{ item.label }}</span>
        </RouterLink>
      </nav>
      <div class="sidebar-foot">
        <div class="secure-state"><ShieldCheck :size="16" /><span>生产域 · 受控</span></div>
        <button class="collapse-button" :title="collapsed ? '展开侧栏' : '收起侧栏'" @click="collapsed = !collapsed">
          <ChevronRight v-if="collapsed" :size="17" />
          <ChevronLeft v-else :size="17" />
          <span>收起侧栏</span>
        </button>
      </div>
    </aside>

    <div v-if="mobileOpen" class="mobile-scrim" @click="mobileOpen = false" />

    <main class="main-frame">
      <header class="topbar">
        <button class="icon-button mobile-menu" title="打开导航" @click="mobileOpen = true"><Menu :size="20" /></button>
        <div class="page-heading">
          <h1>{{ pageTitle }}</h1>
          <span class="environment-label">PRODUCTION</span>
        </div>
        <div class="topbar-actions">
          <button class="search-trigger" title="全局搜索"><Search :size="16" /><span>搜索资源、告警或编号</span><kbd>Ctrl K</kbd></button>
          <div class="platform-health"><span class="health-dot" />服务正常</div>
          <time>{{ clock }}</time>
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

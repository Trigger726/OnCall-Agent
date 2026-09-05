import { createRouter, createWebHistory } from 'vue-router'
import { auth } from '@/stores/auth'
import MainLayout from '@/layouts/MainLayout.vue'
import LoginView from '@/views/LoginView.vue'
import DashboardView from '@/views/DashboardView.vue'
import IncidentsView from '@/views/IncidentsView.vue'
import AlertsView from '@/views/AlertsView.vue'
import CmdbView from '@/views/CmdbView.vue'
import OnCallView from '@/views/OnCallView.vue'
import RunbooksView from '@/views/RunbooksView.vue'
import AuditView from '@/views/AuditView.vue'
import AssistantView from '@/views/AssistantView.vue'
import AnalyticsView from '@/views/AnalyticsView.vue'
import ProblemsView from '@/views/ProblemsView.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: LoginView, meta: { public: true, title: '登录' } },
    {
      path: '/',
      component: MainLayout,
      children: [
        { path: '', name: 'dashboard', component: DashboardView, meta: { title: '运行总览' } },
        { path: 'incidents', name: 'incidents', component: IncidentsView, meta: { title: 'Incident 工作台' } },
        { path: 'assistant', name: 'assistant', component: AssistantView, meta: { title: 'OnCall 助手' } },
        { path: 'alerts', name: 'alerts', component: AlertsView, meta: { title: '告警中心' } },
        { path: 'cmdb', name: 'cmdb', component: CmdbView, meta: { title: '资源与拓扑' } },
        { path: 'on-call', name: 'on-call', component: OnCallView, meta: { title: '值班与升级' } },
        { path: 'runbooks', name: 'runbooks', component: RunbooksView, meta: { title: '处置手册' } },
        { path: 'audit', name: 'audit', component: AuditView, meta: { title: '审计日志' } },
        { path: 'analytics', name: 'analytics', component: AnalyticsView, meta: { title: '运营分析' } },
        { path: 'problems', name: 'problems', component: ProblemsView, meta: { title: '问题治理' } },
      ],
    },
  ],
})

router.beforeEach(async (to) => {
  if (!auth.state.initialized) await auth.restore()
  if (!to.meta.public && !auth.isAuthenticated.value) return '/login'
  if (to.path === '/login' && auth.isAuthenticated.value) return '/'
  return true
})

window.addEventListener('opspilot-auth-expired', () => {
  if (!auth.isAuthenticated.value) return
  auth.logout()
  void router.push({ path: '/login', query: { reason: 'expired' } })
})

export default router

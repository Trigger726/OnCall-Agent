import { computed, reactive } from 'vue'
import { api } from '@/services/api'

export interface UserView {
  id: number
  username: string
  displayName: string
  roleCode: string
}

interface LoginResponse {
  accessToken: string
  tokenType: string
  expiresIn: number
  user: UserView
}

const state = reactive<{ user: UserView | null; initialized: boolean }>({
  user: JSON.parse(localStorage.getItem('opspilot_user') ?? 'null') as UserView | null,
  initialized: false,
})

async function login(username: string, password: string) {
  const response = await api<LoginResponse>('/auth/login', {
    method: 'POST',
    body: JSON.stringify({ username, password }),
  })
  localStorage.setItem('opspilot_token', response.accessToken)
  localStorage.setItem('opspilot_user', JSON.stringify(response.user))
  state.user = response.user
}

async function restore() {
  if (!localStorage.getItem('opspilot_token')) {
    state.initialized = true
    return
  }
  try {
    state.user = await api<UserView>('/auth/me')
    localStorage.setItem('opspilot_user', JSON.stringify(state.user))
  } catch {
    logout()
  } finally {
    state.initialized = true
  }
}

function logout() {
  localStorage.removeItem('opspilot_token')
  localStorage.removeItem('opspilot_user')
  state.user = null
}

export const auth = {
  state,
  isAuthenticated: computed(() => Boolean(state.user && localStorage.getItem('opspilot_token'))),
  login,
  restore,
  logout,
}

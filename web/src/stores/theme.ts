import { readonly, ref } from 'vue'

const current = ref(document.documentElement.dataset.theme === 'light' ? 'light' : 'dark')

export const theme = readonly(current)

export function toggleTheme() {
  current.value = current.value === 'dark' ? 'light' : 'dark'
  document.documentElement.dataset.theme = current.value
  document.querySelector('meta[name="theme-color"]')?.setAttribute('content', current.value === 'light' ? '#f7f7f7' : '#090909')
  try {
    localStorage.setItem('opspilot_theme', current.value)
  } catch { /* Switching still works for this page when persistence is unavailable. */ }
}

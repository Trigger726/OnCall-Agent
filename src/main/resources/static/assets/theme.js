// Run before styles load so a saved light theme never paints as dark first.
(() => {
  let theme = 'dark'
  try {
    if (localStorage.getItem('opspilot_theme') === 'light') theme = 'light'
  } catch { /* Storage can be disabled; the default theme remains usable. */ }
  document.documentElement.dataset.theme = theme
  document.querySelector('meta[name="theme-color"]')?.setAttribute('content', theme === 'light' ? '#f7f7f7' : '#090909')
})()

const THEME_KEY = 'pulse_theme'
const CHAT_REFRESH_KEY = 'pulse_chat_auto_refresh'

export function getTheme() { return uni.getStorageSync(THEME_KEY) === 'glass' ? 'glass' : 'classic' }
export function setTheme(value) { uni.setStorageSync(THEME_KEY, value === 'glass' ? 'glass' : 'classic') }
export function getChatAutoRefresh() { return uni.getStorageSync(CHAT_REFRESH_KEY) !== 'off' }
export function setChatAutoRefresh(enabled) { uni.setStorageSync(CHAT_REFRESH_KEY, enabled ? 'on' : 'off') }

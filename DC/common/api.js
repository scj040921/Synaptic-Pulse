const TOKEN_KEY = 'pulse_token'
const SERVER_KEY = 'pulse_server'

export function serverUrl() {
  const saved = uni.getStorageSync(SERVER_KEY)
  if (saved) return saved
  const platform = uni.getSystemInfoSync().platform
  return platform === 'android' ? 'http://10.0.2.2:8088' : 'http://localhost:8088'
}

export function setServerUrl(value) {
  const url = value.trim().replace(/\/+$/, '')
  if (!/^https?:\/\/[\w.:-]+$/.test(url)) throw new Error('请输入完整服务地址，例如 http://192.168.1.8:8088')
  uni.setStorageSync(SERVER_KEY, url)
}

export function token() { return uni.getStorageSync(TOKEN_KEY) }
export function setToken(value) { uni.setStorageSync(TOKEN_KEY, value) }
export function clearToken() { uni.removeStorageSync(TOKEN_KEY) }

export function ensureLogin() {
  if (token()) return true
  uni.reLaunch({ url: '/pages/auth/auth' })
  return false
}

export function api(path, method = 'GET', data = undefined) {
  return new Promise((resolve, reject) => {
    uni.request({
      url: serverUrl() + '/api' + path,
      method,
      data,
      header: token() ? { Authorization: 'Bearer ' + token() } : {},
      success: (response) => {
        if (response.statusCode >= 200 && response.statusCode < 300) {
          resolve(response.data)
          return
        }
        if (response.statusCode === 401 && path !== '/auth/login') clearToken()
        reject(new Error(response.data?.error || '请求失败（' + response.statusCode + '）'))
      },
      fail: () => reject(new Error('无法连接服务，请检查服务地址和网络'))
    })
  })
}

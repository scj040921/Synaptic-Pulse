const TOKEN_KEY = 'pulse_token'
const SERVER_KEY = 'pulse_server'
const ACCOUNT_KEY = 'pulse_account'
let redirecting = false

export function serverUrl() {
  const saved = uni.getStorageSync(SERVER_KEY)
  if (saved) return saved
  const platform = uni.getSystemInfoSync().platform
  return platform === 'android' ? 'http://10.0.2.2:8088' : 'http://localhost:8088'
}

export function setServerUrl(value) {
  const url = value.trim().replace(/\/+$/, '')
  if (!/^https?:\/\/[\w.:-]+$/.test(url)) throw new Error('请输入完整服务地址，例如 http://192.168.1.8:8088')
  const changed = url !== serverUrl()
  if (changed) clearToken()
  uni.setStorageSync(SERVER_KEY, url)
  return changed
}

export function token() { return uni.getStorageSync(TOKEN_KEY) }
export function setToken(value, userId) { uni.setStorageSync(TOKEN_KEY, value); uni.setStorageSync(ACCOUNT_KEY, String(userId || '')) }
export function clearToken() { uni.removeStorageSync(TOKEN_KEY); uni.removeStorageSync(ACCOUNT_KEY) }
export function accountKey() { return serverUrl() + ':' + (uni.getStorageSync(ACCOUNT_KEY) || 'legacy') }
export function errorText(error) { return error instanceof Error ? error.message : '操作失败，请稍后重试' }
export function confirmAction(title, content) {
  return new Promise((resolve) => { uni.showModal({ title, content, success: (result) => resolve(result.confirm), fail: () => resolve(false) }) })
}
export function expireSession() {
  clearToken()
  if (redirecting) return
  redirecting = true
  uni.reLaunch({ url: '/pages/auth/auth' })
  setTimeout(() => { redirecting = false }, 1500)
}

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
      // Some successful endpoints have no response body (read, block, logout).
      // Decode JSON here so the runtime does not try to parse an empty string.
      dataType: 'text',
      timeout: 35000,
      header: token() ? { Authorization: 'Bearer ' + token() } : {},
      success: (response) => {
        let body = response.data
        if (typeof body === 'string') {
          if (!body.trim()) body = undefined
          else {
            try { body = JSON.parse(body) }
            catch (_) { reject(new Error('服务返回了无法识别的内容，请稍后重试')); return }
          }
        }
        if (response.statusCode >= 200 && response.statusCode < 300) {
          resolve(body)
          return
        }
        if (response.statusCode === 401 && !path.startsWith('/auth/')) expireSession()
        reject(new Error(body?.error || '请求失败（' + response.statusCode + '）'))
      },
      fail: () => reject(new Error('无法连接服务，请检查服务地址和网络'))
    })
  })
}

export function uploadImage(filePath) {
  return new Promise((resolve, reject) => {
    uni.uploadFile({
      url: serverUrl() + '/api/uploads/images',
      filePath,
      name: 'file',
      timeout: 35000,
      header: token() ? { Authorization: 'Bearer ' + token() } : {},
      success: (response) => {
        let body = response.data
        if (typeof body === 'string') {
          try { body = JSON.parse(body) } catch (_) { body = {} }
        }
        if (response.statusCode >= 200 && response.statusCode < 300) {
          resolve(body)
          return
        }
        if (response.statusCode === 401) expireSession()
        reject(new Error(body?.error || '图片上传失败（' + response.statusCode + '）'))
      },
      fail: () => reject(new Error('图片上传失败，请检查服务地址和网络'))
    })
  })
}

// The H5 drag-and-drop path receives a browser File rather than a uni temp path.
export async function uploadWebFile(file) {
  // #ifdef H5
  const body = new FormData()
  body.append('file', file, file.name)
  const controller = new AbortController()
  const timeout = setTimeout(() => controller.abort(), 35000)
  try {
  const response = await fetch(serverUrl() + '/api/uploads/images', {
    method: 'POST',
    headers: token() ? { Authorization: 'Bearer ' + token() } : {},
    signal: controller.signal,
    body
  })
  const result = await response.json()
  if (response.status === 401) expireSession()
  if (!response.ok) throw new Error(result.error || '图片上传失败（' + response.status + '）')
  return result
  } catch (error) {
    if (controller.signal.aborted) throw new Error('图片上传超时，请检查网络后重试')
    throw error
  } finally { clearTimeout(timeout) }
  // #endif
}

export function mediaUrl(path) {
  return path ? (path.startsWith('/media/') ? serverUrl() + path : path) : ''
}

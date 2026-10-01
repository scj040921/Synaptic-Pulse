import { serverUrl, token } from './api.js'
import { getTheme } from './settings.js'

// Fragment data stays out of HTTP request URLs and access logs.
export function campusMapUrl(placeId = '') {
  const params = ['token=' + encodeURIComponent(token()), 'theme=' + getTheme(), 'place=' + encodeURIComponent(placeId)]
  // #ifdef H5
  params.push('parent=' + encodeURIComponent(window.location.origin), 'platform=web')
  // #endif
  // #ifndef H5
  params.push('platform=app')
  // #endif
  return serverUrl() + '/campus/index.html#' + params.join('&')
}

export function chooseCampusPlace() {
  return openSelection('/pages/map/map?select=1')
}

export function chooseMapPosition(position = null) {
  const point = position ? '&lat=' + position.latitude + '&lng=' + position.longitude : ''
  return openSelection('/pages/map/map?point=1' + point)
}

export function chooseActivityPosition(position = null) {
  const point = position ? '&lat=' + position.latitude + '&lng=' + position.longitude + '&name=' + encodeURIComponent(position.locationName || '') : ''
  return openSelection('/pages/map/map?point=1&purpose=event' + point)
}

// Each navigation owns one callback. A cancelled picker resolves once without
// relying on platform-specific page EventChannel implementations.
let sequence = 0
const selections = {}
function openSelection(url) {
  const requestId = Date.now() + '-' + (++sequence)
  return new Promise((resolve, reject) => {
    selections[requestId] = resolve
    uni.navigateTo({ url: url + '&request=' + requestId, fail: () => {
      delete selections[requestId]; reject(new Error('无法打开地图选点'))
    } })
  })
}
export function finishMapSelection(requestId, value) {
  const resolve = selections[requestId]
  if (!resolve) return
  delete selections[requestId]
  resolve(value)
}

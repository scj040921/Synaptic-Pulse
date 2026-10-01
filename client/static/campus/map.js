/* Shared desktop / Android map. User text is inserted with textContent, never HTML. */
(() => {
  'use strict';
  const boot = new URLSearchParams(location.hash.slice(1));
  const accessToken = boot.get('token') || '';
  const native = boot.get('platform') === 'app';
  const parentOrigin = boot.get('parent');
  const selecting = boot.get('select') === '1';
  const initialPlace = boot.get('place');
  const pointMode = boot.get('point') === '1';
  const initialPost = Number(boot.get('post'));
  const eventMode = boot.get('purpose') === 'event';
  const initialEvent = Number(boot.get('event'));
  const bridge = boot.get('bridge');
  let pinMarker = null;
  // Remove credentials before the page exposes any outbound links.
  history.replaceState(null, '', location.pathname);
  if (boot.get('theme') === 'glass') document.body.classList.add('glass');
  const $ = (id) => document.getElementById(id);
  const symbols = { study: '▤', food: '◉', sport: '◇', culture: '✧', leisure: '♧', service: '⌖' };
  const categories = { '': '全部', study: '学习', sport: '运动', food: '餐饮', culture: '文化', leisure: '休闲', service: '服务' };
  const campus = [23.0421, 113.3679];
  const campusBounds = [[23.0394, 113.3626], [23.0441, 113.3735]];
  const state = { places: [], events: [], posts: [], point: null, selectedPost: null, selectedEvent: null, pinning: pointMode, tab: 'places', category: '', query: '', nearby: false, radius: 250, center: campus, centerLabel: '校园参考中心 · 非实时定位', selected: null, loading: false, sequence: 0, online: false, locating: false };
  const map = L.map('map', { zoomControl: true, attributionControl: true, minZoom: 3, maxZoom: 20 }).fitBounds(campusBounds, { padding: [24, 24], maxZoom: 17 });
  map.attributionControl.addAttribution('<a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener noreferrer">© OpenStreetMap contributors</a>');
  const markers = L.layerGroup().addTo(map);
  const circle = L.circle(campus, { radius: 250, color: '#51998b', weight: 1.5, dashArray: '5 5', fillColor: '#86c7b7', fillOpacity: .10, interactive: false }).addTo(map);
  let yourMarker = null, accuracyCircle = null, tiles = null, tileFailed = false;
  const resize = new ResizeObserver(() => map.invalidateSize()); resize.observe($('map'));
  function el(tag, className, text) {
    const node = document.createElement(tag); if (className) node.className = className; if (text != null) node.textContent = text; return node;
  }
  function button(text, action, className = '') { const node = el('button', className, text); node.type = 'button'; node.addEventListener('click', action); return node; }
  function status(text = '', error = false) { $('status').textContent = text; $('status').classList.toggle('error', error); }
  function send(action, extra = {}) {
    const data = { channel: 'pulse-campus', bridge, action, ...extra };
    if (!native && window.parent !== window && parentOrigin) window.parent.postMessage(data, parentOrigin);
    else if (native && window.uni?.webView) window.uni.webView.postMessage({ data });
    else status('请从应用首页打开地图。', true);
  }
  async function request(path, method = 'GET') {
    const controller = new AbortController(); const timeout = setTimeout(() => controller.abort(), 15000);
    try {
      const response = await fetch('/api' + path, { method, signal: controller.signal, headers: { Authorization: 'Bearer ' + accessToken } });
      if (response.status === 401) { send('expired'); throw new Error('登录已失效，请返回重新登录。'); }
      const text = await response.text(); let data = null;
      if (text) { try { data = JSON.parse(text); } catch (_) { throw new Error('地图服务返回了无法识别的内容，请刷新重试。'); } }
      if (!response.ok) throw new Error(data?.error || '地图请求失败，请重试。');
      return data;
    } catch (error) {
      if (error.name === 'AbortError' || error instanceof TypeError) throw new Error('网络连接失败，请检查服务后刷新。');
      throw error;
    } finally { clearTimeout(timeout); }
  }
  async function load() {
    const sequence = ++state.sequence; state.loading = true; $('refresh').disabled = true; status('正在加载校园地点与活动…');
    const params = new URLSearchParams({ lat: state.center[0], lng: state.center[1] });
    if (state.nearby) params.set('radius', state.radius);
    try {
      const data = await request('/map/overview?' + params);
      if (sequence !== state.sequence) return;
      state.places = data.places; state.events = data.events; state.posts = data.posts || [];
      if (state.selectedEvent) state.selectedEvent = state.events.find(e => e.id === state.selectedEvent.id) || null;
      if (state.selectedPost) state.selectedPost = state.posts.find(p => p.id === state.selectedPost.id) || null;
      if (state.selected) state.selected = state.places.find(p => p.id === state.selected.id) || null;
      status(); render();
    } catch (error) { if (sequence === state.sequence) status(error.message, true); }
    finally { if (sequence === state.sequence) { state.loading = false; $('refresh').disabled = false; } }
  }
  function visiblePlaces() {
    return state.places.filter(p => (!state.category || p.category === state.category) && (!state.query || p.name.includes(state.query)) && (state.tab !== 'favorites' || p.favorite));
  }
  function associatedEvents(placeId) { return state.events.filter(e => e.placeId === placeId); }
  function distanceLabel(place) { return place.distanceMeters < 1000 ? place.distanceMeters + ' m' : (place.distanceMeters / 1000).toFixed(1) + ' km'; }
  function renderMarkers() {
    markers.clearLayers();
    const items = state.tab === 'posts' && !selecting && !pointMode ? [] : state.tab === 'events' ? state.places.filter(p => associatedEvents(p.id).some(e => !state.query || e.title.includes(state.query) || e.content.includes(state.query) || p.name.includes(state.query))) : visiblePlaces();
    items.forEach(p => {
      const signal = associatedEvents(p.id).length > 0;
      const selected = state.selected?.id === p.id;
      const icon = L.divIcon({ className: 'map-marker', html: '<span class="marker-dot ' + p.category + (signal ? ' pulse' : '') + (selected ? ' selected' : '') + '">' + (symbols[p.category] || '⌖') + '</span>', iconSize: [31, 31], iconAnchor: [15, 15] });
      const marker = L.marker([p.latitude, p.longitude], { icon, title: p.name, alt: p.name }).addTo(markers);
      marker.bindTooltip(el('span', '', p.name), { direction: 'top', offset: [0, -12] });
      marker.on('click', () => selectPlace(p));
    });
    if (!selecting && !pointMode) visiblePosts().forEach(p => {
      const icon = L.divIcon({ className: 'map-marker', html: '<span class="marker-dot post-dot">✎</span>', iconSize: [31,31], iconAnchor: [15,15] });
      const marker = L.marker([p.latitude, p.longitude], { icon, title: p.authorName + '的动态' }).addTo(markers);
      marker.bindTooltip(el('span', '', p.locationName || p.content.slice(0,30) || '图片动态'));
      marker.on('click', () => selectPost(p));
    });
    if (!selecting && !pointMode) state.events.filter(e => !e.placeId || !state.places.some(p => p.id === e.placeId)).filter(e => !state.query || e.title.includes(state.query) || e.locationName.includes(state.query)).forEach(e => {
      const icon = L.divIcon({ className: 'map-marker', html: '<span class="marker-dot pulse">✧</span>', iconSize:[31,31], iconAnchor:[15,15] });
      const marker = L.marker([e.latitude,e.longitude], { icon, title:e.title }).addTo(markers);
      marker.bindTooltip(el('span','',e.title)); marker.on('click', () => selectEvent(e));
    });
    circle.setLatLng(state.center).setRadius(state.radius);
    $('center-label').textContent = state.centerLabel; $('radius-label').textContent = state.radius + ' 米';
  }
  function placeCard(place) {
    const card = button('', () => selectPlace(place), 'place-card' + (state.selected?.id === place.id ? ' selected' : ''));
    card.append(el('span', 'place-symbol', symbols[place.category] || '⌖'));
    const copy = el('span', 'place-copy'); copy.append(el('span', 'place-name', place.name));
    const signals = associatedEvents(place.id).length;
    copy.append(el('span', 'place-meta', categories[place.category] + (place.favorite ? ' · 已收藏' : '') + (signals ? ' · ' + signals + ' 场活动' : '')));
    card.append(copy, el('span', 'place-distance', distanceLabel(place))); return card;
  }
  function eventCard(event) {
    const card = el('article', 'event-card'); card.append(el('h4', 'event-title', event.title));
    card.append(el('p', 'event-meta', event.locationName + ' · ' + event.startsAt.replace('T', ' ').slice(0, 16)));
    card.append(el('p', 'event-meta', event.hostName + ' 发起 · ' + event.participants + '/' + event.capacity + ' 人'));
    const actions = el('div', 'event-actions');
    const place = state.places.find(p => p.id === event.placeId);
    if (place) actions.append(button('查看地点', () => selectPlace(place)));
    else if (event.latitude != null) actions.append(button('查看标记地点', () => selectEvent(event)));
    const full = event.participants >= event.capacity;
    const join = button(event.joined ? '取消报名' : full ? '名额已满' : '报名参与', async () => {
      join.disabled = true;
      try { await request('/events/' + event.id + '/signup', event.joined ? 'DELETE' : 'PUT'); await load(); }
      catch (error) { status(error.message, true); join.disabled = false; }
    }, 'primary');
    join.disabled = full && !event.joined; actions.append(join); card.append(actions); return card;
  }
  function render() {
    $('panel-title').textContent = pointMode ? (eventMode ? '标记活动地点' : '标记帖子位置') : selecting ? '选择活动地点' : '探索校园';
    $('pin').hidden = selecting; $('pin').textContent = pointMode ? '点击地图选点' : state.pinning ? '取消标记' : '＋ 标记发帖';
    $('pin').setAttribute('aria-pressed', String(state.pinning)); $('pin').disabled = pointMode;
    document.querySelector('.search').hidden = !!state.point;
    document.querySelector('.tabs').hidden = !!state.point || pointMode;
    document.querySelector('.range-row').hidden = !!state.point;
    $('place-count').textContent = state.tab === 'posts' ? state.posts.length + ' 条动态' : state.places.length + ' 个地点';
    $('nearby').textContent = state.nearby ? '附近范围 ✓' : '全部校园'; $('nearby').setAttribute('aria-pressed', String(state.nearby));
    $('categories').hidden = state.tab === 'events' || state.tab === 'posts' || !!state.point || pointMode;
    renderMarkers();
    if (state.point) { renderPoint(); return; }
    if (state.selectedEvent) { renderEventDetail(); return; }
    if (state.selectedPost) { renderPostDetail(); return; }
    if (state.selected) { renderDetail(); return; }
    $('detail').hidden = true; $('list').hidden = false; $('list').replaceChildren();
    if (pointMode) { $('list').append(el('div', 'empty', '点击地图任意位置放置标记，可拖动标记微调位置。'));
    } else if (state.tab === 'posts') {
      const items = visiblePosts();
      if (!items.length) $('list').append(el('div', 'empty', '这里还没有位置动态。点击「标记发帖」，把你的发现留在地图上。'));
      items.forEach(p => $('list').append(postCard(p)));
      $('list').append(el('p', 'event-meta', '最多展示最近 100 条同校位置动态，可用附近范围缩小查询。'));
    } else if (state.tab === 'events') {
      const items = state.events.filter(e => !state.query || e.title.includes(state.query) || e.content.includes(state.query) || e.locationName.includes(state.query));
      if (!items.length) $('list').append(el('div', 'empty', '这里还没有符合条件的活动波纹。发起活动时关联地图地点，就能让同学发现它。'), button('去校园活动', () => send('events'), 'soft-button'));
      else items.forEach(e => $('list').append(eventCard(e)));
    } else {
      const items = visiblePlaces();
      if (!items.length) $('list').append(el('div', 'empty', state.tab === 'favorites' ? '还没有符合条件的收藏。点击地点后可以收藏；也可以切回全部校园。' : '没有找到符合条件的地点。试试其他关键词，或关闭附近范围。'));
      else items.forEach(p => $('list').append(placeCard(p)));
    }
  }
  function selectPlace(place) { state.selectedEvent = null; if (pointMode) { choosePoint(place.latitude, place.longitude, place.name, place.id); map.flyTo([place.latitude, place.longitude], 17); return; } clearPoint(); state.selectedPost = null; state.selected = place; if (selecting) send('selection', { position: { latitude: place.latitude, longitude: place.longitude, locationName: place.name, placeId: place.id } }); map.flyTo([place.latitude, place.longitude], Math.max(map.getZoom(), 17), { duration: .5 }); render(); }
  function renderDetail() {
    const p = state.selected; $('list').hidden = true; $('detail').hidden = false; const detail = $('detail'); detail.replaceChildren();
    detail.append(button('← 返回列表', () => { state.selected = null; render(); }, 'detail-close'), el('h3', '', p.name), el('p', '', categories[p.category] + ' · 距参考中心 ' + distanceLabel(p)), el('p', '', p.description));
    const actions = el('div', 'detail-actions');
    const favorite = button(p.favorite ? '★ 已收藏 · 取消' : '☆ 收藏地点', async () => {
      favorite.disabled = true;
      try { await request('/map/places/' + encodeURIComponent(p.id) + '/favorite', p.favorite ? 'DELETE' : 'PUT'); p.favorite = !p.favorite; render(); }
      catch (error) { status(error.message, true); favorite.disabled = false; }
    });
    actions.append(favorite, button('以此查找附近', () => { state.center = [p.latitude, p.longitude]; state.centerLabel = p.name + ' · 手动参考点'; state.nearby = true; state.selected = null; load(); }));
    if (!selecting) actions.append(button(pointMode ? '标记在此' : '在此发帖', () => choosePoint(p.latitude, p.longitude, p.name), 'primary'));
    if (selecting) actions.append(button('选择这个地点', () => send('select', { place: { id: p.id, name: p.name } }), 'primary'));
    detail.append(actions, el('h4', 'event-title', '这里的活动波纹'));
    const signals = associatedEvents(p.id);
    if (signals.length) signals.forEach(e => detail.append(eventCard(e)));
    else detail.append(el('p', '', '暂无关联的未开始活动。'));
  }
  function selectEvent(event) { clearPoint(); state.selected = null; state.selectedPost = null; state.selectedEvent = event; map.flyTo([event.latitude,event.longitude],17); render(); }
  function renderEventDetail() {
    $('list').hidden = true; const detail = $('detail'); detail.hidden = false; detail.replaceChildren();
    detail.append(button('← 返回列表', () => { state.selectedEvent = null; render(); }, 'detail-close'), el('h3','',state.selectedEvent.locationName), eventCard(state.selectedEvent));
  }
  function visiblePosts() {
    return state.posts.filter(p => !state.query || [p.content, p.locationName, p.authorName, p.topics].some(t => (t || '').includes(state.query)));
  }
  function clearPoint() { if (selecting || pointMode) send('selection', { position:null }); state.point = null; if (pinMarker) { pinMarker.remove(); pinMarker = null; } }
  function choosePoint(latitude, longitude, locationName = '地图标记', placeId = null) {
    if (selecting) return;
    clearPoint(); state.selected = null; state.selectedPost = null;
    state.point = { latitude, longitude, locationName, placeId }; state.selectedEvent = null;
    pinMarker = L.marker([latitude, longitude], { draggable: true, icon: L.divIcon({ className: 'map-marker', html: '<span class="marker-dot chosen-dot">⌖</span>', iconSize: [35,35], iconAnchor: [17,17] }) }).addTo(map);
    pinMarker.on('dragend', () => { const p = pinMarker.getLatLng().wrap(); state.point.latitude = p.lat; state.point.longitude = p.lng; state.point.placeId = null; if (pointMode) send('selection', { position:state.point }); render(); });
    render(); if (pointMode) send('selection', { position:state.point });
  }
  function renderPoint() {
    $('list').hidden = true; const detail = $('detail'); detail.hidden = false; detail.replaceChildren();
    detail.append(button('← 取消选点', () => { clearPoint(); state.pinning = pointMode; render(); }, 'detail-close'), el('h3', '', pointMode ? '选择这个位置' : '在这里分享发现'));
    detail.append(el('p', '', '点击地图重新选点，或拖动标记微调。'));
    const name = el('input', 'point-name'); name.maxLength = 80; name.value = state.point.locationName; name.placeholder = '给这个位置起个名字（选填）'; name.setAttribute('aria-label', '标记地点名称');
    name.addEventListener('input', () => { state.point.locationName = name.value; state.point.placeId = null; if (pointMode) send('selection', { position:state.point }); }); detail.append(name);
    detail.append(el('p', '', state.point.latitude.toFixed(5) + ', ' + state.point.longitude.toFixed(5) + ' · WGS84'), el('p', '', eventMode ? '此标记是活动集合地点，发布后可供参与者查看。' : '标记的位置将随帖子公开展示，不代表实时定位。'));
    const actions = el('div', 'detail-actions'); actions.append(button(pointMode ? '使用此位置' : '在此发布图文', () => send(pointMode ? 'point' : 'publish', { position: { ...state.point, locationName: state.point.locationName.trim() || '地图标记' } }), 'primary')); detail.append(actions);
  }
  function postCard(post) {
    const card = el('article', 'event-card');
    card.append(button(post.authorName + ' · ' + (post.locationName || '地图标记'), () => selectPost(post), 'post-title'), el('p', 'post-copy', post.content || '图片动态'));
    if (post.imageUrls?.length) {
      const grid = el('div', 'post-grid');
      post.imageUrls.slice(0,9).forEach(url => {
        // Only server-owned media paths can load in the embedded map.
        if (!/^\/media\/[a-zA-Z0-9._/-]+$/.test(url)) return;
        const img = el('img', ''); img.src = url; img.alt = '动态图片'; img.loading = 'lazy';
        const control = button('', () => send('post', { id: post.id }), 'post-photo'); control.append(img); grid.append(control);
      }); card.append(grid);
    }
    card.append(el('p', 'event-meta', post.createdAt.replace('T',' ').slice(0,16) + ' · ♥ ' + post.likeCount + ' · 评论 ' + post.commentCount), button('查看全文与评论 →', () => send('post', { id: post.id }), 'detail-close'));
    return card;
  }
  function selectPost(post) { state.selectedEvent = null; clearPoint(); state.selected = null; state.selectedPost = post; map.flyTo([post.latitude,post.longitude], 17, { duration: .5 }); render(); }
  function renderPostDetail() {
    $('list').hidden = true; const detail = $('detail'); detail.hidden = false; detail.replaceChildren();
    detail.append(button('← 返回列表', () => { state.selectedPost = null; render(); }, 'detail-close'), postCard(state.selectedPost));
    const actions = el('div', 'detail-actions'); const p = state.selectedPost;
    actions.append(button('也在这里发帖', () => choosePoint(p.latitude, p.longitude, p.locationName || '地图标记'), 'primary')); detail.append(actions);
    state.posts.filter(other => other.id !== p.id && Math.abs(other.latitude - p.latitude) < 0.00001 && Math.abs(other.longitude - p.longitude) < 0.00001).forEach(other => detail.append(postCard(other)));
  }
  map.on('click', event => { if (selecting || (!state.pinning && !pointMode)) return; const p = event.latlng.wrap(); choosePoint(p.lat, p.lng); });
  $('pin').addEventListener('click', () => { state.pinning = !state.pinning; state.selectedEvent = null; clearPoint(); state.selected = null; state.selectedPost = null; status(state.pinning ? '点击地图任意位置放置标记，可拖动微调。' : ''); render(); });
  Object.entries(categories).forEach(([key, name]) => {
    const control = button(name, () => { state.category = key; state.selected = null; $('categories').querySelectorAll('button').forEach(b => b.classList.toggle('active', b === control)); render(); }, key === '' ? 'active' : ''); $('categories').append(control);
  });
  document.querySelectorAll('[data-tab]').forEach(control => control.addEventListener('click', () => { state.tab = control.dataset.tab; state.selectedEvent = null; state.selected = null; state.selectedPost = null; clearPoint(); document.querySelectorAll('[data-tab]').forEach(b => b.classList.toggle('active', b === control)); render(); }));
  $('search').addEventListener('input', () => { state.query = $('search').value.trim(); state.selected = null; state.selectedPost = null; render(); });
  $('back').addEventListener('click', () => send('back'));
  $('refresh').addEventListener('click', load);
  $('nearby').addEventListener('click', () => { state.nearby = !state.nearby; state.selected = null; load(); });
  $('radius').addEventListener('change', () => { state.radius = Number($('radius').value); if (state.nearby) load(); else renderMarkers(); });
  $('search-center').addEventListener('click', () => { const center = map.getCenter(); state.center = [center.lat, center.lng]; state.centerLabel = '地图中心 · 手动参考点'; state.nearby = true; state.selected = null; load(); });
  $('reset').addEventListener('click', () => { state.selectedEvent = null; clearPoint(); state.selectedPost = null; state.center = campus; state.centerLabel = '校园参考中心 · 非实时定位'; state.nearby = false; state.selected = null; state.query = ''; $('search').value = ''; map.fitBounds(campusBounds, { padding: [24, 24], maxZoom: 17 }); load(); });
  function locationFailed() { state.locating = false; $('locate').disabled = false; status('无法获取定位。请检查系统位置权限；仍可用地图中心查找附近。', true); }
  function setLocation(position) {
    const lat = Number(position.latitude), lng = Number(position.longitude);
    if (!Number.isFinite(lat) || !Number.isFinite(lng) || Math.abs(lat) > 90 || Math.abs(lng) > 180) { locationFailed(); return; }
    state.locating = false; $('locate').disabled = false;
    state.center = [lat, lng]; state.centerLabel = '我的定位' + (position.accuracy > 250 ? ' · 精度较低' : ''); state.nearby = true; state.selected = null;
    if (yourMarker) yourMarker.remove(); if (accuracyCircle) accuracyCircle.remove();
    yourMarker = L.marker(state.center, { icon: L.divIcon({ className: '', html: '<span class="you-marker"></span>', iconSize: [15, 15], iconAnchor: [7, 7] }) }).addTo(map);
    if (Number.isFinite(position.accuracy) && position.accuracy > 0) accuracyCircle = L.circle(state.center, { radius: Math.min(position.accuracy, 5000), color: '#378cca', weight: 1, fillOpacity: .04, interactive: false }).addTo(map);
    map.setView(state.center, 17); load();
  }
  function pickCenter() { if (!pointMode) return; const p = map.getCenter().wrap(); choosePoint(p.lat, p.lng); }
  window.PulseCampus = { setLocation, locationFailed, pickCenter };
  window.addEventListener('message', event => {
    if (native || event.source !== window.parent || event.origin !== parentOrigin) return;
    const data = event.data;
    if (data?.channel === 'pulse-campus-command' && data.bridge === bridge && data.action === 'pick-center') pickCenter();
  });
  $('locate').addEventListener('click', () => {
    if (state.locating) return;
    state.locating = true; $('locate').disabled = true; status('正在获取位置，仅用于当前地图查询…');
    if (native) { send('locate'); return; }
    if (!navigator.geolocation || !window.isSecureContext) { locationFailed(); return; }
    navigator.geolocation.getCurrentPosition(p => setLocation(p.coords), locationFailed, { enableHighAccuracy: true, timeout: 12000, maximumAge: 30000 });
  });
  $('basemap').addEventListener('click', () => {
    state.online = !state.online;
    if (state.online) {
      tileFailed = false;
      tiles = L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', { maxZoom: 19, detectRetina: false, attribution: '', crossOrigin: false });
      tiles.on('tileerror', () => { if (!tileFailed) { tileFailed = true; status('街道底图暂时无法加载；可切回本地校园地图。', true); } });
      tiles.addTo(map); tiles.bringToBack(); $('basemap').textContent = '切回校园底图';
    } else { if (tiles) { tiles.remove(); tiles = null; } $('basemap').textContent = '切换街道底图'; status(); }
  });
  fetch('campus.geojson').then(r => { if (!r.ok) throw new Error(); return r.json(); }).then(data => {
    L.geoJSON(data, { style: feature => ({ color: ({ building: '#c0cfcc', road: '#ced9d5', water: '#9bcfd6', green: '#adcdb5' })[feature.properties.kind], fillColor: ({ building: '#dce7e2', water: '#c2e3e5', green: '#d4e7d6' })[feature.properties.kind], weight: feature.properties.kind === 'road' ? 3 : 1, fillOpacity: .75, interactive: false }) }).addTo(map).bringToBack();
  }).catch(() => status('校园底图未能加载，可尝试切换街道底图。', true));
  if (!accessToken) { status('请从登录后的应用首页打开地图。', true); $('list').append(el('div', 'empty', '尚未登录')); }
  else load().then(async () => {
    if (pointMode && boot.get('lat') && boot.get('lng')) { const lat=Number(boot.get('lat')), lng=Number(boot.get('lng')); if (Number.isFinite(lat) && Number.isFinite(lng) && Math.abs(lat)<=90 && Math.abs(lng)<=180) { map.setView([lat,lng],17); choosePoint(lat,lng,boot.get('name') || '地图标记'); } }
    if (initialEvent > 0) { try { const e = await request('/events/' + initialEvent); if (e.latitude != null && e.longitude != null) { if (!state.events.some(item => item.id === e.id)) state.events.push(e); selectEvent(e); } } catch(error) { status(error.message,true); } }
    if (initialPost > 0) { try { const p = await request('/posts/' + initialPost); if (p.latitude != null && p.longitude != null) { if (!state.posts.some(item => item.id === p.id)) state.posts.push(p); selectPost(p); } } catch (error) { status(error.message, true); } }
    if (!initialPlace) return;
    const place = state.places.find(p => p.id === initialPlace); if (place) selectPlace(place);
  });
})();

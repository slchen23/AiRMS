// AiRMS 控制頁 airms.html（取代舊系統的同名頁面）：路網圖同即時監視頁；下方讀取與設定 AiRMS 設備的敏感性、開／關。
// 開頁時讀一次設備狀態；按「開始監控」後，路網依「間隔更新時間」、設備依「參數輪詢間隔時間」定時讀取（同舊系統）。
// 送出前跳出確認視窗（同舊系統的「確定送出?」），每次送出都由伺服器記錄。
import { api, ApiError, confirmDialog, fmtTime, h, handleAuthError, loadMe, toast, withBusy } from './common.js';
import { createLiveMap } from './live-map.js';

const MAP_MIN = 10;
const MAP_MAX = 600;
const POLL_MIN = 30;
const POLL_MAX = 3600;
const SENS_MIN = 40;
const SENS_MAX = 80;
const SENS_STEP = 5;
const SUMMARY_LINES = 5;

const $id = (id) => document.getElementById(id);
const watchBtn = $id('watch-btn');
const mapInput = $id('watch-interval');
const pollInput = $id('poll-interval');
const statusEl = $id('view-status');
const sensInput = $id('param-sensitivity');
const sensValue = $id('sens-value');
const onInput = $id('param-on');
const onLabel = $id('param-on-label');
const submitBtn = $id('submit-btn');
const readBtn = $id('read-btn');
const revertBtn = $id('revert-btn');

let map = null;
let mapTimer = null;
let pollTimer = null;
let mapLoading = false;
let reading = false;
let sending = false;
/** 最後一次讀到（或設定後設備確認）的狀態：{sensitivity, on, atMs}；還沒讀到為 null。 */
let device = null;
/** 使用者改過設定、還沒送出：輪詢時不覆蓋畫面上的設定值。 */
let dirty = false;
const summary = [];

// ---------------------------------------------------------------- 共用

function clamp(n, min, max, def) {
  if (!Number.isFinite(n)) return def;
  return Math.min(max, Math.max(min, n));
}

const onOff = (on) => (on ? 'ON' : 'OFF');
const hms = (ms) => fmtTime(ms).slice(11);

function setStatus(text, isError = false) {
  statusEl.textContent = text;
  statusEl.title = text;
  statusEl.classList.toggle('is-error', isError);
}

function addSummary(text) {
  summary.unshift(text);
  summary.length = Math.min(summary.length, SUMMARY_LINES);
  $id('summary').replaceChildren(...summary.map((t) => h('li', { title: t }, t)));
}

/** 401／必須改密碼時轉頁；回傳 true 表示已處理。 */
function authRedirect(e) {
  return handleAuthError(e);
}

// ---------------------------------------------------------------- 設定值（參數一、參數二）

function sensitivity() {
  return clamp(Number.parseInt(sensInput.value, 10), SENS_MIN, SENS_MAX, 60);
}

function showInputs() {
  sensValue.textContent = String(sensitivity());
  onLabel.textContent = onOff(onInput.checked);
  $id('sens-minus').disabled = sensitivity() <= SENS_MIN;
  $id('sens-plus').disabled = sensitivity() >= SENS_MAX;
  const differs = device && (device.sensitivity !== sensitivity() || device.on !== onInput.checked);
  $id('dirty-badge').classList.toggle('d-none', !dirty);
  revertBtn.classList.toggle('d-none', !(dirty && device && differs));
}

function setInputs(s, on) {
  sensInput.value = String(s);
  onInput.checked = on;
  dirty = false;
  showInputs();
}

function touched() {
  dirty = true;
  showInputs();
}

sensInput.addEventListener('input', touched);
onInput.addEventListener('change', touched);
$id('sens-minus').addEventListener('click', () => {
  sensInput.value = String(Math.max(SENS_MIN, sensitivity() - SENS_STEP));
  touched();
});
$id('sens-plus').addEventListener('click', () => {
  sensInput.value = String(Math.min(SENS_MAX, sensitivity() + SENS_STEP));
  touched();
});
revertBtn.addEventListener('click', () => {
  if (device) setInputs(device.sensitivity, device.on);
});

// ---------------------------------------------------------------- 設備狀態

function showDevice(addr, error) {
  const sens = $id('dev-sensitivity');
  const on = $id('dev-on');
  if (device) {
    sens.textContent = String(device.sensitivity);
    on.textContent = onOff(device.on);
    on.classList.toggle('is-on', device.on);
    on.classList.toggle('is-off', !device.on);
    $id('dev-time').textContent = `讀取時間 ${fmtTime(device.atMs)}`;
  }
  sens.classList.toggle('is-stale', Boolean(error));
  on.classList.toggle('is-stale', Boolean(error));
  if (addr !== undefined) $id('dev-addr').textContent = addr ? `設備 ${addr}` : '設備位址未設定（系統設定 → AiRMS 設備）';
  $id('dev-error').textContent = error || '';
}

/** 讀設備狀態（輪詢與「立即讀取」）。 */
async function readDevice() {
  if (reading) return;
  reading = true;
  readBtn.disabled = true;
  try {
    const r = await api('GET', '/api/airms/status');
    if (r.ok) {
      device = { sensitivity: r.sensitivity, on: r.on, atMs: r.readAtMs };
      showDevice(r.device, null);
      addSummary(`${hms(r.readAtMs)} 輪詢。敏感性：${r.sensitivity}，AiRMS：${onOff(r.on)}。`);
      if (!dirty) setInputs(r.sensitivity, r.on);
      else showInputs();
    } else {
      showDevice(r.device, `讀取失敗：${r.message}`);
      addSummary(`${hms(r.readAtMs)} 輪詢。${r.message}。`);
    }
  } catch (e) {
    if (authRedirect(e)) return;
    showDevice(undefined, `讀取失敗：${e.message}`);
    addSummary(`${hms(Date.now())} 輪詢。${e.message}。`);
  } finally {
    reading = false;
    readBtn.disabled = sending;
  }
}

readBtn.addEventListener('click', readDevice);

// ---------------------------------------------------------------- 送出

function confirmBody(s, on) {
  const now = device ? `（最後讀到 ${device.sensitivity}／${onOff(device.on)}，${fmtTime(device.atMs)}）`
    : '（尚未讀到設備狀態）';
  return h('div', null,
    h('p', { class: 'mb-2 fw-bold' }, '確定送出？'),
    h('ul', { class: 'mb-2' },
      h('li', null, `儀控敏感性：${s}`),
      h('li', null, `AiRMS 開/關：${onOff(on)}`)),
    h('p', { class: 'mb-0 small text-secondary' }, now));
}

submitBtn.addEventListener('click', async () => {
  const s = sensitivity();
  const on = onInput.checked;
  if (!await confirmDialog('送出 AiRMS 設定', confirmBody(s, on), '送出', 'warning')) return;
  sending = true;
  readBtn.disabled = true;
  try {
    await withBusy(submitBtn, async () => {
      const r = await api('POST', '/api/airms/settings', { sensitivity: s, on });
      if (r.after) device = { sensitivity: r.after.sensitivity, on: r.after.on, atMs: r.atMs };
      if (r.ok) {
        showDevice(r.device, null);
        setInputs(r.after.sensitivity, r.after.on);
        addSummary(`${hms(r.atMs)} 設定。敏感性：${r.after.sensitivity}，AiRMS：${onOff(r.after.on)}。`);
        toast(`已送出：敏感性 ${r.after.sensitivity}，AiRMS ${onOff(r.after.on)}`);
      } else {
        showDevice(r.device, `設定失敗：${r.message}`);
        showInputs();
        addSummary(`${hms(r.atMs)} 設定。${r.message}。`);
        toast(`設定失敗：${r.message}`, 'danger');
      }
    });
  } catch (e) {
    if (authRedirect(e)) return;
    const msg = e instanceof ApiError ? e.message : '系統錯誤';
    addSummary(`${hms(Date.now())} 設定。${msg}。`);
    toast(`設定失敗：${msg}`, 'danger');
  } finally {
    sending = false;
    readBtn.disabled = reading;
  }
});

// ---------------------------------------------------------------- 路網圖與監控

async function refreshMap() {
  if (mapLoading || !map) return;
  mapLoading = true;
  try {
    await map.refresh();
    setStatus(mapTimer ? `監控中：路網每 ${mapInterval()} 秒、參數每 ${pollInterval()} 秒更新`
      : '已載入最新資料（未啟動定時更新）');
  } catch (e) {
    if (authRedirect(e)) return;
    setStatus(`路網更新失敗：${e.message}`, true);
  } finally {
    mapLoading = false;
  }
}

const mapInterval = () => clamp(Number.parseInt(mapInput.value, 10), MAP_MIN, MAP_MAX, 25);
const pollInterval = () => clamp(Number.parseInt(pollInput.value, 10), POLL_MIN, POLL_MAX, 180);

function start() {
  mapInput.value = String(mapInterval());
  pollInput.value = String(pollInterval());
  mapInput.disabled = true;
  pollInput.disabled = true;
  watchBtn.classList.replace('btn-danger', 'btn-success');
  watchBtn.textContent = '停止監控';
  mapTimer = window.setInterval(refreshMap, mapInterval() * 1000);
  pollTimer = window.setInterval(readDevice, pollInterval() * 1000);
  refreshMap();
  readDevice();
}

function stop() {
  window.clearInterval(mapTimer);
  window.clearInterval(pollTimer);
  mapTimer = null;
  pollTimer = null;
  mapInput.disabled = false;
  pollInput.disabled = false;
  watchBtn.classList.replace('btn-success', 'btn-danger');
  watchBtn.textContent = '開始監控';
  setStatus('已停止定時更新');
}

watchBtn.addEventListener('click', () => (mapTimer ? stop() : start()));

$id('fullscreen-btn').addEventListener('click', () => {
  if (document.fullscreenElement) document.exitFullscreen();
  else document.documentElement.requestFullscreen().catch(() => {});
});

$id('logout-btn').addEventListener('click', async () => {
  if (!await confirmDialog('登出', '確定登出？', '登出', 'secondary')) return;
  try {
    await api('POST', '/api/auth/logout');
  } finally {
    location.href = '/login.html';
  }
});

// ---------------------------------------------------------------- 設定紀錄

const pair = (p) => (p ? `${p.sensitivity}／${onOff(p.on)}` : '—');

async function loadLog() {
  const tbody = $id('log-rows');
  const alertBox = $id('log-alert');
  alertBox.replaceChildren();
  tbody.replaceChildren(h('tr', null, h('td', { colspan: 8, class: 'text-secondary' }, '讀取中…')));
  try {
    const j = await api('GET', '/api/airms/log?limit=500');
    if (!j.rows.length) {
      tbody.replaceChildren(h('tr', null, h('td', { colspan: 8, class: 'text-secondary' }, '最近 30 天沒有設定紀錄')));
      return;
    }
    tbody.replaceChildren(...j.rows.map((r) => h('tr', null,
      h('td', { class: 'text-nowrap' }, fmtTime(r.atMs)),
      h('td', null, r.username),
      h('td', { class: 'text-nowrap' }, pair(r.request)),
      h('td', { class: 'text-nowrap' }, r.before ? pair(r.before) : '未知',
        r.before && r.before.atMs ? h('div', { class: 'small text-secondary' }, `${fmtTime(r.before.atMs)} 讀到`) : null),
      h('td', { class: 'text-nowrap' }, pair(r.after)),
      h('td', null, h('span', { class: `badge text-bg-${r.ok ? 'success' : 'danger'}`, title: r.message || '' },
        r.outcomeLabel)),
      h('td', { class: 'font-monospace' }, r.response || '—'),
      h('td', { class: 'text-nowrap' }, r.ip || ''))));
  } catch (e) {
    if (authRedirect(e)) return;
    tbody.replaceChildren();
    alertBox.append(h('div', { class: 'alert alert-danger py-2' }, e.message));
  }
}

$id('log-btn').addEventListener('click', () => {
  window.bootstrap.Modal.getOrCreateInstance($id('log-modal')).show();
  loadLog();
});

// ---------------------------------------------------------------- 開頁

(async () => {
  showInputs();
  let user;
  try {
    user = await loadMe();      // 同時取得送出設定需要的 CSRF token
  } catch (e) {
    user = null;
  }
  if (!user) {
    location.href = `/login.html?next=${encodeURIComponent(location.pathname)}`;
    return;
  }
  $id('view-user').textContent = user.displayName;
  readDevice();
  try {
    map = await createLiveMap($id('network'));
    await refreshMap();
  } catch (e) {
    if (authRedirect(e)) return;
    setStatus(`無法載入路網設定：${e.message}`, true);
  }
})();

// 即時監視頁 view2：開頁時畫路網並載入一次資料；按「開始監控」後依間隔定時更新（同舊 view.html）。
import { api, fmtTime, handleAuthError } from './common.js';
import { renderNetwork } from './network-svg.js';

const MIN_INTERVAL = 10;
const MAX_INTERVAL = 600;

const btn = document.getElementById('watch-btn');
const intervalInput = document.getElementById('watch-interval');
const statusEl = document.getElementById('view-status');
const svg = document.getElementById('network');

let view = null;
let timer = null;
let loading = false;

function setStatus(text, isError = false) {
  statusEl.textContent = text;
  statusEl.classList.toggle('is-error', isError);
}

/** 來源檔頭時間「2026-11-03 08:55:31」→「2026/11/03 08:55:31」。 */
function feedTimeText(block) {
  if (!block || !block.feedTime) return null;
  return [block.feedTime.replaceAll('-', '/'), Boolean(block.stale)];
}

async function refresh() {
  if (loading || !view) return;
  loading = true;
  try {
    const live = await api('GET', '/api/view/live');
    view.updateLegend(live.speedClasses);
    view.updateSections(live.sections.items);
    view.updateRms(live.rms.items);
    view.updateScreenlines(live.screenlines.items);
    view.updateTimes({
      sections: feedTimeText(live.sections),
      rms: feedTimeText(live.rms),
      screenlines: feedTimeText(live.screenlines),
      refreshed: [fmtTime(Date.now()), false],
    });
    setStatus(timer ? `監控中，每 ${currentInterval()} 秒更新` : '已載入最新資料（未啟動定時更新）');
  } catch (e) {
    // 需要登入時（例如閒置過久或程式重新啟動後登入失效）轉到登入頁，登入後回到這裡
    if (handleAuthError(e)) return;
    setStatus(`更新失敗：${e.message}`, true);
  } finally {
    loading = false;
  }
}

function currentInterval() {
  const n = Number.parseInt(intervalInput.value, 10);
  if (!Number.isFinite(n)) return 25;
  return Math.min(MAX_INTERVAL, Math.max(MIN_INTERVAL, n));
}

function start() {
  const sec = currentInterval();
  intervalInput.value = String(sec);
  intervalInput.disabled = true;
  btn.classList.replace('btn-danger', 'btn-success');
  btn.textContent = '停止監控';
  timer = window.setInterval(refresh, sec * 1000);
  refresh();
}

function stop() {
  window.clearInterval(timer);
  timer = null;
  intervalInput.disabled = false;
  btn.classList.replace('btn-success', 'btn-danger');
  btn.textContent = '開始監控';
  setStatus('已停止定時更新');
}

btn.addEventListener('click', () => (timer ? stop() : start()));

document.getElementById('fullscreen-btn').addEventListener('click', () => {
  if (document.fullscreenElement) document.exitFullscreen();
  else document.documentElement.requestFullscreen().catch(() => {});
});

(async () => {
  try {
    const net = await api('GET', '/api/view/network');
    view = renderNetwork(svg, net);
    await refresh();
  } catch (e) {
    if (handleAuthError(e)) return;
    setStatus(`無法載入路網設定：${e.message}`, true);
  }
})();

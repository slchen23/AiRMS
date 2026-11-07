import { api, fmtTime, h } from './common.js';
import { initUser, renderShell } from './layout.js';

const shell = renderShell('home');
initUser(shell);

const STATE = {
  OK: ['success', '正常'],
  DEGRADED: ['danger', '異常'],
  WARMING_UP: ['secondary', '啟動中'],
  DISABLED: ['light', '已停用'],
};

async function loadHealth() {
  const body = document.getElementById('feed-rows');
  try {
    const j = await api('GET', '/api/health');
    const rows = Object.values(j.feeds).map((f) => {
      const [kind, label] = STATE[f.state] || ['secondary', f.state];
      const ms = (iso) => (iso ? fmtTime(Date.parse(iso)) : '');
      return h('tr', null,
        h('td', null, f.label),
        h('td', null, h('span', { class: `badge text-bg-${kind}` }, label)),
        h('td', null, ms(f.lastAttemptAt)),
        h('td', null, ms(f.lastSuccessAt)),
        h('td', null, f.lastFeedTime || ''),
        h('td', { class: 'text-end' }, f.consecutiveFailures ?? ''));
    });
    body.replaceChildren(...rows);
    showCert(j.tls);
    showStorage(j.storage, j.backup);
  } catch (e) {
    body.replaceChildren(h('tr', null, h('td', { colspan: '6', class: 'text-danger' }, e.message)));
  }
}

/** HTTPS 憑證到期日；快到期（系統設定的提醒天數內）或已過期時在上方顯示提醒。 */
function showCert(tls) {
  const info = document.getElementById('cert-info');
  const alert = document.getElementById('cert-alert');
  if (!tls) {
    info.replaceChildren();
    alert.replaceChildren();
    return;
  }
  info.textContent = tls.state === 'EXPIRED'
    ? `HTTPS 憑證已於 ${tls.notAfter} 過期`
    : `HTTPS 憑證有效到 ${tls.notAfter}（還有 ${tls.daysLeft} 天）`;
  if (tls.state === 'OK') {
    alert.replaceChildren();
    return;
  }
  const expired = tls.state === 'EXPIRED';
  alert.replaceChildren(h('div', { class: `alert alert-${expired ? 'danger' : 'warning'} d-flex align-items-center gap-2`, role: 'alert' },
    h('i', { class: 'bi bi-shield-exclamation fs-5', 'aria-hidden': 'true' }),
    h('div', null,
      expired ? `HTTPS 憑證已於 ${tls.notAfter} 過期，瀏覽器會顯示警告。`
        : `HTTPS 憑證將於 ${tls.notAfter} 到期（還有 ${tls.daysLeft} 天）。`,
      '請管理者用 tools/tls/new-cert 重新產生憑證後重新啟動。')));
}

const BACKUP = {
  DISABLED: ['light', '未啟用'],
  NEVER: ['secondary', '尚未執行'],
  RUNNING: ['info', '備份中'],
  OK: ['success', '正常'],
  FAILED: ['danger', '失敗'],
};

function gb(bytes) {
  return `${(bytes / 1024 ** 3).toFixed(1)} GB`;
}

/** 資料目錄剩餘空間與備份狀態；空間不足、備份失敗或逾期時在上方顯示提醒。 */
function showStorage(st, bk) {
  const body = document.getElementById('storage-rows');
  const alert = document.getElementById('storage-alert');
  if (!st || !bk) {
    body.replaceChildren();
    alert.replaceChildren();
    return;
  }
  const ms = (iso) => (iso ? fmtTime(Date.parse(iso)) : '—');
  const space = st.error ? st.error
    : `剩餘 ${gb(st.freeBytes)}／共 ${gb(st.totalBytes)}（${st.freePercent}%）`;
  const [kind, label] = BACKUP[bk.state] || ['secondary', bk.state];
  const rows = [
    h('tr', null, h('th', { class: 'w-25' }, '資料目錄'), h('td', null, st.dataDir)),
    h('tr', null, h('th', null, '磁碟空間'), h('td', { class: st.low ? 'text-danger fw-bold' : '' }, space)),
    h('tr', null, h('th', null, '備份'), h('td', null, h('span', { class: `badge text-bg-${kind}` }, label),
      bk.enabled ? ` 每天 ${bk.dailyAt} 備份到 ${bk.targetDir}` : '（config.json 的 backup.enabled）')),
  ];
  if (bk.enabled) {
    rows.push(h('tr', null, h('th', null, '最後成功'), h('td', null, ms(bk.lastSuccessAt))));
    if (bk.lastError) rows.push(h('tr', null, h('th', null, '最近錯誤'), h('td', { class: 'text-danger' }, bk.lastError)));
  }
  body.replaceChildren(...rows);

  const warnings = [];
  if (st.low) warnings.push(`資料目錄剩餘空間只有 ${st.freePercent}%（低於 ${st.minFreePercent}%），請檢查保留期限或清理空間。`);
  if (bk.enabled && bk.overdue) {
    warnings.push(bk.lastError ? `備份未完成：${bk.lastError}` : '今天的備份尚未完成，請確認外接硬碟已接上。');
  }
  alert.replaceChildren(...warnings.map((w) => h('div', { class: 'alert alert-warning d-flex align-items-center gap-2', role: 'alert' },
    h('i', { class: 'bi bi-hdd fs-5', 'aria-hidden': 'true' }), h('div', null, w))));
}

loadHealth();
setInterval(loadHealth, 30000);

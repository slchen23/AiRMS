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

loadHealth();
setInterval(loadHealth, 30000);

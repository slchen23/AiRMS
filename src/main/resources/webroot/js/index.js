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
  } catch (e) {
    body.replaceChildren(h('tr', null, h('td', { colspan: '6', class: 'text-danger' }, e.message)));
  }
}

loadHealth();
setInterval(loadHealth, 30000);

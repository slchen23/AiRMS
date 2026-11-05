import {
  api, confirmDialog, dateInputToMs, DT_LANGUAGE, fmtDate, fmtTime, h, handleAuthError, isoDate, ROLE_LABEL,
  showAlert, toast, withBusy, $,
} from './common.js';
import { initUser, renderShell } from './layout.js';

const { DataTable } = window;
const DAY_MS = 24 * 60 * 60 * 1000;
const shell = renderShell('admin-sessions');
const pageAlert = $('#sessions-alert');

const OUTCOME = {
  SUCCESS: ['success', '成功'],
  BAD_PASSWORD: ['warning', '密碼錯誤'],
  BAD_PASSWORD_LOCKED: ['danger', '密碼錯誤，帳號已鎖定'],
  UNKNOWN_USER: ['warning', '帳號不存在'],
  LOCKED: ['danger', '帳號鎖定中'],
  DISABLED: ['secondary', '帳號已停用'],
  EXPIRED: ['secondary', '帳號已過期'],
  IP_BLOCKED: ['danger', '來源暫停（嘗試過多）'],
};

const ACTION = {
  SETUP_ADMIN: '建立第一個管理者',
  USER_CREATE: '新增帳號',
  USER_UPDATE: '修改帳號',
  PASSWORD_RESET: '重設密碼',
  PASSWORD_CHANGE: '變更自己的密碼',
  USER_UNLOCK: '解除鎖定',
  SESSION_REVOKE: '強制登出',
  SETTING_UPDATE: '修改系統設定',
  SETTING_RESET: '設定還原預設',
  RAMP_UPDATE: '修改路網匝道',
  RAMP_RESET: '路網匝道還原預設',
};

const PERM_LABEL = { AIRMS_CONTROL: 'AiRMS 控制', DATA_DOWNLOAD: '資料下載', VIEW: '監視' };
const permText = (list) => (list || []).map((p) => PERM_LABEL[p] || p).join('、') || '（無）';

const FIELD = {
  displayName: '顯示名稱', role: '角色', enabled: '啟用', expiresAt: '到期', permissions: '權限',
};

function fail(e) {
  if (!handleAuthError(e)) showAlert(pageAlert, 'danger', e.message);
}

const timeColumn = (data) => ({
  data, className: 'text-nowrap text-start', render: (d, type) => (type === 'sort' || type === 'type' ? d : fmtTime(d)),
});
const text = (data, extra = {}) => ({ data, render: DataTable.render.text(), defaultContent: '', ...extra });

// ---------------------------------------------------------------- 線上使用者

const online = new DataTable('#online', {
  data: [],
  language: DT_LANGUAGE,
  order: [[3, 'desc']],
  scrollX: true,
  columns: [
    text('username'),
    text('displayName'),
    timeColumn('createdAt'),
    timeColumn('lastSeenAt'),
    text('ip'),
    text('userAgent', { className: 'ua-cell' }),
    {
      data: null,
      orderable: false,
      searchable: false,
      render: () => '',
      createdCell: (td, d, row) => {
        if (row.current) {
          td.replaceChildren(h('span', { class: 'badge text-bg-info' }, '目前這個登入'));
          return;
        }
        td.replaceChildren(h('button', { type: 'button', class: 'btn btn-outline-danger btn-sm', dataset: { sid: row.id } },
          h('i', { class: 'bi bi-box-arrow-right', 'aria-hidden': 'true' }), ' 強制登出'));
      },
    },
  ],
  createdRow: (tr, row) => {
    tr.cells[5].title = row.userAgent || '';
  },
});

let sessions = [];

async function loadOnline() {
  sessions = (await api('GET', '/api/admin/sessions')).sessions;
  online.clear().rows.add(sessions).draw();
}

$('#reload-online').addEventListener('click', () => loadOnline().catch(fail));

$('#online').addEventListener('click', async (ev) => {
  const b = ev.target.closest('button[data-sid]');
  if (!b) return;
  const s = sessions.find((x) => x.id === b.dataset.sid);
  if (!s) return;
  if (!await confirmDialog('強制登出', `確定要讓 ${s.username}（${s.ip || ''}）這個登入立即登出？`, '強制登出')) return;
  try {
    await api('DELETE', `/api/admin/sessions/${encodeURIComponent(s.id)}`);
    toast(`已讓 ${s.username} 登出`);
  } catch (e) {
    fail(e);
  }
  loadOnline().catch(fail);
});

// ---------------------------------------------------------------- 登入紀錄

const logins = new DataTable('#logins', {
  data: [],
  language: DT_LANGUAGE,
  order: [[0, 'desc']],
  pageLength: 25,
  scrollX: true,
  columns: [
    timeColumn('at'),
    text('username'),
    {
      data: 'outcome',
      render: (d) => (OUTCOME[d] ? OUTCOME[d][1] : String(d).replace(/[<>&"]/g, '')),
      createdCell: (td, d) => {
        const [kind, label] = OUTCOME[d] || ['secondary', d];
        td.replaceChildren(h('span', { class: `badge text-bg-${kind}` }, label));
      },
    },
    text('ip'),
    text('userAgent', { className: 'ua-cell' }),
  ],
  createdRow: (tr, row) => {
    tr.cells[4].title = row.userAgent || '';
  },
});

const outcomeSelect = $('#l-outcome');
for (const [key, [, label]] of Object.entries(OUTCOME)) outcomeSelect.append(h('option', { value: key }, label));

function rangeQuery(fromInput, toInput) {
  const from = dateInputToMs(fromInput.value);
  const to = dateInputToMs(toInput.value);
  if (from === null || to === null) throw new Error('請選擇起訖日期');
  if (to < from) throw new Error('迄日不可早於起日');
  return `from=${from}&to=${to + DAY_MS}`;
}

async function loadLogins() {
  let q = rangeQuery($('#l-from'), $('#l-to'));
  const user = $('#l-user').value.trim();
  if (user) q += `&username=${encodeURIComponent(user)}`;
  if (outcomeSelect.value) q += `&outcome=${encodeURIComponent(outcomeSelect.value)}`;
  const rows = (await api('GET', `/api/admin/login-history?${q}`)).rows;
  logins.clear().rows.add(rows).draw();
}

$('#logins-filter').addEventListener('submit', (ev) => {
  ev.preventDefault();
  showAlert(pageAlert, null, null);
  withBusy($('#logins-submit'), () => loadLogins().catch(fail));
});

// ---------------------------------------------------------------- 操作紀錄

function describe(row) {
  const d = row.detail;
  if (!d) return '';
  if (row.action === 'USER_UPDATE' && d.before && d.after) {
    const changes = [];
    for (const [key, label] of Object.entries(FIELD)) {
      const a = JSON.stringify(d.before[key]);
      const b = JSON.stringify(d.after[key]);
      if (a !== b) changes.push(`${label}：${fmtValue(key, d.before[key])} → ${fmtValue(key, d.after[key])}`);
    }
    return changes.length ? changes.join('；') : '（沒有變更）';
  }
  if ('from' in d || 'to' in d) return `${JSON.stringify(d.from)} → ${JSON.stringify(d.to)}`;
  if ((row.action === 'RAMP_UPDATE' || row.action === 'RAMP_RESET') && d.before && d.after) {
    const show = (v) => (v ? '顯示' : '隱藏');
    const codes = (list) => (list && list.length ? list.join('、') : '無');
    const changes = [];
    if (d.before.visible !== d.after.visible) changes.push(`${show(d.before.visible)} → ${show(d.after.visible)}`);
    if (JSON.stringify(d.before.rms) !== JSON.stringify(d.after.rms)) {
      changes.push(`RMS：${codes(d.before.rms)} → ${codes(d.after.rms)}`);
    }
    return changes.length ? changes.join('；') : '（沒有變更）';
  }
  if (row.action === 'USER_CREATE') {
    return `角色：${ROLE_LABEL[d.role] || d.role}；權限：${permText(d.permissions)}；啟用：${d.enabled ? '是' : '否'}`
      + (d.expiresAt ? `；到期：${fmtDate(d.expiresAt - 1)}` : '');
  }
  return JSON.stringify(d);
}

function fmtValue(key, v) {
  if (v === null || v === undefined) return '（無）';
  if (key === 'expiresAt') return fmtDate(v - 1);
  if (key === 'enabled') return v ? '是' : '否';
  if (key === 'role') return ROLE_LABEL[v] || String(v);
  if (key === 'permissions') return permText(v);
  return String(v);
}

const audit = new DataTable('#audit', {
  data: [],
  language: DT_LANGUAGE,
  order: [[0, 'desc']],
  pageLength: 25,
  scrollX: true,
  columns: [
    timeColumn('at'),
    text('actor'),
    { data: 'action', className: 'text-nowrap', render: (d) => ACTION[d] || String(d).replace(/[<>&"]/g, '') },
    text('target'),
    { data: null, orderable: false, width: '35%', className: 'detail-cell', render: (d, type, row) => DataTable.render.text().display(describe(row)) },
    text('ip'),
  ],
});

async function loadAudit() {
  const rows = (await api('GET', `/api/admin/audit?${rangeQuery($('#a-from'), $('#a-to'))}`)).rows;
  audit.clear().rows.add(rows).draw();
}

$('#audit-filter').addEventListener('submit', (ev) => {
  ev.preventDefault();
  showAlert(pageAlert, null, null);
  withBusy($('#audit-submit'), () => loadAudit().catch(fail));
});

// 隱藏分頁裡的表格切換顯示時重新計算欄寬。
document.querySelectorAll('button[data-bs-toggle="tab"]').forEach((tab) => {
  tab.addEventListener('shown.bs.tab', () => DataTable.tables({ visible: true, api: true }).columns.adjust());
});

// ---------------------------------------------------------------- 啟動

(async () => {
  const me = await initUser(shell);
  if (!me) {
    location.href = '/login.html?next=/admin-sessions.html';
    return;
  }
  const today = isoDate(Date.now());
  const weekAgo = isoDate(Date.now() - 6 * DAY_MS);
  for (const id of ['#l-from', '#a-from']) $(id).value = weekAgo;
  for (const id of ['#l-to', '#a-to']) $(id).value = today;
  try {
    await Promise.all([loadOnline(), loadLogins(), loadAudit()]);
  } catch (e) {
    fail(e);
  }
})();

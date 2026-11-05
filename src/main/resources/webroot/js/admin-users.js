import {
  api, confirmDialog, dateInputToMs, DT_LANGUAGE, fmtDate, fmtTime, h, handleAuthError, isoDate, PASSWORD_HINT,
  ROLE_LABEL, showAlert, toast, withBusy, $,
} from './common.js';
import { initUser, renderShell } from './layout.js';

const { DataTable, bootstrap } = window;
const DAY_MS = 24 * 60 * 60 * 1000;
const shell = renderShell('admin-users');
const pageAlert = $('#users-alert');
const userModal = new bootstrap.Modal($('#user-modal'));
const resetModal = new bootstrap.Modal($('#reset-modal'));
$('#u-password-hint').textContent = PASSWORD_HINT;
$('#r-password-hint').textContent = PASSWORD_HINT;

let me = null;
let permissions = [];
let users = [];
let editing = null; // 編輯中的帳號；null 表示新增
let resetting = null;

/** 到期日存的是「該日的隔天 00:00」，畫面顯示選定的那一天。 */
const expiryLabel = (ms) => (ms ? fmtDate(ms - 1) : '');

function statusList(u) {
  const now = Date.now();
  const s = [];
  if (!u.enabled) s.push(['secondary', '停用']);
  if (u.expiresAt && u.expiresAt <= now) s.push(['secondary', '已過期']);
  if (u.lockedUntil && u.lockedUntil > now) s.push(['danger', `鎖定至 ${fmtTime(u.lockedUntil)}`]);
  if (u.mustChangePassword) s.push(['warning', '需改密碼']);
  if (!s.length) s.push(['success', '正常']);
  return s;
}

function permLabel(key) {
  const p = permissions.find((x) => x.key === key);
  return p ? p.label : key;
}

const badges = (list) => list.map(([kind, text]) => h('span', { class: `badge text-bg-${kind} me-1` }, text));

const table = new DataTable('#users', {
  data: [],
  language: DT_LANGUAGE,
  order: [[0, 'asc']],
  pageLength: 25,
  scrollX: true,
  columns: [
    { data: 'username', render: DataTable.render.text() },
    { data: 'displayName', render: DataTable.render.text() },
    { data: 'role', className: 'text-nowrap', render: (d) => ROLE_LABEL[d] || '' },
    {
      data: 'permissions',
      orderable: false,
      render: (d) => d.map(permLabel).join('、').replace(/[<>&"]/g, ''),
      createdCell: (td, d) => td.replaceChildren(...badges(d.map((k) => ['info', permLabel(k)]))),
    },
    {
      data: null,
      orderable: false,
      className: 'text-nowrap',
      render: (d, type, row) => statusList(row).map((x) => x[1]).join(' '),
      createdCell: (td, d, row) => td.replaceChildren(...badges(statusList(row))),
    },
    { data: 'expiresAt', className: 'text-nowrap text-start', render: (d, type) => (type === 'sort' || type === 'type' ? (d || 0) : expiryLabel(d)) },
    { data: 'lastLoginAt', className: 'text-nowrap text-start', render: (d, type) => (type === 'sort' || type === 'type' ? (d || 0) : fmtTime(d)) },
    {
      data: null,
      orderable: false,
      searchable: false,
      className: 'text-nowrap',
      render: () => '',
      createdCell: (td, d, row) => {
        const btn = (action, kind, ic, label) => h('button', {
          type: 'button', class: `btn btn-${kind} btn-sm me-1`, dataset: { action, id: String(row.id) }, title: label,
        }, h('i', { class: `bi bi-${ic}`, 'aria-hidden': 'true' }), ` ${label}`);
        const items = [btn('edit', 'outline-primary', 'pencil', '編輯')];
        if (row.id !== me.id) items.push(btn('reset', 'outline-warning', 'key', '重設密碼'));
        if (row.lockedUntil && row.lockedUntil > Date.now()) items.push(btn('unlock', 'outline-danger', 'unlock', '解鎖'));
        td.replaceChildren(...items);
      },
    },
  ],
});

async function reload() {
  const j = await api('GET', '/api/admin/users');
  users = j.users;
  table.clear().rows.add(users).draw();
}

function fail(e, box = pageAlert) {
  if (!handleAuthError(e)) showAlert(box, 'danger', e.message);
}

// ---------------------------------------------------------------- 新增 / 編輯

function renderPermissionBoxes(selected, disabled) {
  const fs = $('#u-perms');
  fs.querySelectorAll('.form-check').forEach((el) => el.remove());
  for (const p of permissions) {
    const id = `perm-${p.key}`;
    const input = h('input', { class: 'form-check-input', type: 'checkbox', id, value: p.key });
    input.checked = disabled || selected.includes(p.key);
    input.disabled = disabled;
    fs.append(h('div', { class: 'form-check' }, input,
      h('label', { class: 'form-check-label', for: id }, p.label, ' ',
        h('span', { class: 'text-secondary small' }, `（${p.description}）`))));
  }
}

$('#u-role').addEventListener('change', () => {
  const admin = $('#u-role').value === 'ADMIN';
  const selected = [...$('#u-perms').querySelectorAll('input:checked')].map((i) => i.value);
  renderPermissionBoxes(admin ? [] : selected, admin);
});

function openEditor(user) {
  editing = user;
  showAlert($('#user-form-alert'), null, null);
  $('#user-form').reset();
  $('#user-modal-title').textContent = user ? `編輯帳號：${user.username}` : '新增帳號';
  $('#u-username').value = user ? user.username : '';
  $('#u-username').disabled = Boolean(user);
  $('#u-display').value = user ? user.displayName : '';
  $('#u-password-group').classList.toggle('d-none', Boolean(user));
  $('#u-role').value = user ? user.role : 'USER';
  $('#u-enabled').checked = user ? user.enabled : true;
  $('#u-expires').value = user && user.expiresAt ? isoDate(user.expiresAt - 1) : '';
  renderPermissionBoxes(user ? user.permissions : [], $('#u-role').value === 'ADMIN');
  userModal.show();
}

$('#add-user').addEventListener('click', () => openEditor(null));

$('#user-form').addEventListener('submit', async (ev) => {
  ev.preventDefault();
  const alertBox = $('#user-form-alert');
  const role = $('#u-role').value;
  const expiresDay = dateInputToMs($('#u-expires').value);
  const body = {
    displayName: $('#u-display').value.trim(),
    role,
    enabled: $('#u-enabled').checked,
    expiresAt: expiresDay === null ? null : expiresDay + DAY_MS,
    permissions: role === 'ADMIN' ? [] : [...$('#u-perms').querySelectorAll('input:checked')].map((i) => i.value),
  };
  if (!editing) {
    body.username = $('#u-username').value.trim();
    body.password = $('#u-password').value;
  }
  await withBusy($('#user-submit'), async () => {
    try {
      if (editing) {
        await api('PUT', `/api/admin/users/${editing.id}`, body);
        toast(`已更新 ${editing.username}`);
      } else {
        await api('POST', '/api/admin/users', body);
        toast(`已新增 ${body.username}，請把臨時密碼交給對方，首次登入時必須變更`);
      }
      userModal.hide();
      await reload();
    } catch (e) {
      fail(e, alertBox);
    }
  });
});

// ---------------------------------------------------------------- 重設密碼、解鎖

$('#reset-form').addEventListener('submit', async (ev) => {
  ev.preventDefault();
  const password = $('#r-password').value;
  await withBusy($('#reset-submit'), async () => {
    try {
      await api('POST', `/api/admin/users/${resetting.id}/password`, { password });
      resetModal.hide();
      toast(`已重設 ${resetting.username} 的密碼`);
      await reload();
    } catch (e) {
      fail(e, $('#reset-form-alert'));
    }
  });
});

$('#users').addEventListener('click', async (ev) => {
  const b = ev.target.closest('button[data-action]');
  if (!b) return;
  const user = users.find((u) => String(u.id) === b.dataset.id);
  if (!user) return;
  showAlert(pageAlert, null, null);
  if (b.dataset.action === 'edit') {
    openEditor(user);
  } else if (b.dataset.action === 'reset') {
    resetting = user;
    $('#reset-form').reset();
    showAlert($('#reset-form-alert'), null, null);
    $('#reset-target').textContent = `帳號：${user.username}（${user.displayName}）`;
    resetModal.show();
  } else if (b.dataset.action === 'unlock') {
    if (!await confirmDialog('解除鎖定', `確定要解除 ${user.username} 的鎖定？`, '解除鎖定', 'primary')) return;
    try {
      await api('POST', `/api/admin/users/${user.id}/unlock`);
      toast(`已解除 ${user.username} 的鎖定`);
      await reload();
    } catch (e) {
      fail(e);
    }
  }
});

// ---------------------------------------------------------------- 啟動

(async () => {
  me = await initUser(shell);
  if (!me) {
    location.href = '/login.html?next=/admin-users.html';
    return;
  }
  try {
    permissions = (await api('GET', '/api/admin/meta')).permissions;
    await reload();
  } catch (e) {
    fail(e);
  }
})();

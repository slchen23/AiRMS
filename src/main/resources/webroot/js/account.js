import { api, fmtTime, h, PASSWORD_HINT, ROLE_LABEL, showAlert, withBusy, $ } from './common.js';
import { initUser, renderShell } from './layout.js';

const shell = renderShell('account');
const params = new URLSearchParams(location.search);
const alertBox = $('#password-alert');
$('#password-hint').textContent = PASSWORD_HINT;

const PERM_LABEL = { AIRMS_CONTROL: 'AiRMS 控制', DATA_DOWNLOAD: '資料下載', VIEW: '監視' };

function renderProfile(user) {
  const row = (label, value) => [h('dt', { class: 'col-sm-4' }, label), h('dd', { class: 'col-sm-8' }, value)];
  const perms = user.permissions.length ? user.permissions.map((p) => PERM_LABEL[p] || p).join('、') : '（無）';
  $('#profile').replaceChildren(
    ...row('帳號', user.username),
    ...row('顯示名稱', user.displayName),
    ...row('角色', ROLE_LABEL[user.role] || user.role),
    ...row('功能權限', perms),
    ...row('到期日', user.expiresAt ? fmtTime(user.expiresAt) : '不限'),
    ...row('上次登入', user.lastLoginAt ? fmtTime(user.lastLoginAt) : ''));
  if (user.mustChangePassword) {
    showAlert($('#must-change'), 'warning', '這是管理者設定的臨時密碼，請先變更成只有你知道的密碼才能使用其他功能。');
  } else {
    showAlert($('#must-change'), null, null);
  }
}

initUser(shell).then((user) => {
  if (!user) {
    location.href = '/login.html?next=/account.html';
    return;
  }
  renderProfile(user);
});

$('#password-form').addEventListener('submit', async (ev) => {
  ev.preventDefault();
  const currentPassword = $('#current').value;
  const newPassword = $('#next').value;
  if (!currentPassword || !newPassword) {
    showAlert(alertBox, 'warning', '請輸入目前密碼與新密碼');
    return;
  }
  if (newPassword !== $('#confirm').value) {
    showAlert(alertBox, 'warning', '兩次輸入的新密碼不同');
    return;
  }
  await withBusy($('#password-submit'), async () => {
    try {
      await api('POST', '/api/auth/password', { currentPassword, newPassword });
      ev.target.reset();
      const me = await api('GET', '/api/auth/me');
      if (!me.user) {
        location.href = '/login.html?next=/account.html';
        return;
      }
      renderProfile(me.user);
      showAlert(alertBox, 'success', '密碼已變更。');
      if (params.get('mustChange') === '1') setTimeout(() => { location.href = '/index.html'; }, 1200);
    } catch (e) {
      if (e.status === 401) {
        location.href = '/login.html?next=/account.html';
        return;
      }
      showAlert(alertBox, 'danger', e.message);
    }
  });
});

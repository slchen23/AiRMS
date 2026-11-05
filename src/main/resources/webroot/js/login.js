import { api, safeNext, setCsrfToken, showAlert, withBusy, $ } from './common.js';

const params = new URLSearchParams(location.search);
const alertBox = $('#login-alert');
if (params.get('setup') === '1') showAlert(alertBox, 'success', '管理者帳號已建立，請登入。');

api('GET', '/api/setup/status').then((j) => {
  if (j.needed) $('#setup-link').classList.remove('d-none');
}).catch(() => {});

$('#login-form').addEventListener('submit', async (ev) => {
  ev.preventDefault();
  const username = $('#username').value.trim();
  const password = $('#password').value;
  if (!username || !password) {
    showAlert(alertBox, 'warning', '請輸入帳號與密碼');
    return;
  }
  await withBusy($('#login-submit'), async () => {
    try {
      const j = await api('POST', '/api/auth/login', { username, password });
      setCsrfToken(j.csrfToken);
      if (j.user.mustChangePassword) {
        location.href = '/account.html?mustChange=1';
      } else {
        location.href = safeNext(params.get('next'));
      }
    } catch (e) {
      $('#password').value = '';
      showAlert(alertBox, 'danger', e.message);
    }
  });
});

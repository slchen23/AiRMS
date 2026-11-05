import { api, PASSWORD_HINT, showAlert, withBusy, $ } from './common.js';

const alertBox = $('#setup-alert');
$('#password-hint').textContent = PASSWORD_HINT;

$('#setup-form').addEventListener('submit', async (ev) => {
  ev.preventDefault();
  const body = {
    code: $('#code').value.trim(),
    username: $('#username').value.trim(),
    displayName: $('#display').value.trim(),
    password: $('#password').value,
  };
  if (!body.code || !body.username || !body.displayName || !body.password) {
    showAlert(alertBox, 'warning', '每一欄都必須填寫');
    return;
  }
  if (body.password !== $('#confirm').value) {
    showAlert(alertBox, 'warning', '兩次輸入的密碼不同');
    return;
  }
  await withBusy($('#setup-submit'), async () => {
    try {
      await api('POST', '/api/setup', body);
      location.href = '/login.html?setup=1';
    } catch (e) {
      showAlert(alertBox, 'danger', e.message);
    }
  });
});

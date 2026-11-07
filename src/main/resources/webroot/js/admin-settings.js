import { api, confirmDialog, h, handleAuthError, showAlert, toast, withBusy, $ } from './common.js';
import { initUser, renderShell } from './layout.js';

const shell = renderShell('admin-settings');
const pageAlert = $('#settings-alert');

function fail(e) {
  if (!handleAuthError(e)) showAlert(pageAlert, 'danger', e.message);
}

function displayValue(def, v) {
  if (def.type === 'BOOL') return v ? '開啟' : '關閉';
  if (def.type === 'URL' || def.type === 'HOST') return v || '（未設定）';
  return String(v);
}

function inputFor(def) {
  const id = `s-${def.key.replace(/[^A-Za-z0-9_-]/g, '-')}`;
  if (def.type === 'BOOL') {
    const input = h('input', { class: 'form-check-input', type: 'checkbox', role: 'switch', id });
    input.checked = Boolean(def.value);
    const label = h('label', { class: 'form-check-label', for: id }, input.checked ? '開啟' : '關閉');
    input.addEventListener('change', () => { label.textContent = input.checked ? '開啟' : '關閉'; });
    const wrap = h('div', { class: 'form-check form-switch mb-0' }, input, label);
    return { id, wrap, read: () => input.checked, input };
  }
  if (def.type === 'INT') {
    const input = h('input', { class: 'form-control form-control-sm', type: 'number', id, min: def.min, max: def.max,
      step: 1, required: true });
    input.value = def.value;
    return {
      id,
      wrap: input,
      input,
      read: () => {
        const n = Number(input.value);
        if (input.value.trim() === '' || !Number.isInteger(n)) throw new Error(`${def.label} 必須是整數`);
        return n;
      },
    };
  }
  const input = h('input', { class: 'form-control form-control-sm setting-input-url', type: def.type === 'URL' ? 'url' : 'text',
    id, maxlength: def.type === 'HOST' ? 253 : 500,
    spellcheck: 'false', placeholder: def.type === 'HOST' ? '例如 192.168.1.10（空白＝未設定）' : null });
  input.value = def.value || '';
  return { id, wrap: input, input, read: () => input.value.trim() };
}

function settingRow(def) {
  const field = inputFor(def);
  const save = h('button', { type: 'button', class: 'btn btn-primary btn-sm' }, '儲存');
  const reset = h('button', { type: 'button', class: 'btn btn-outline-secondary btn-sm ms-1', title: '還原成預設值' },
    '還原預設');
  reset.disabled = !def.overridden;

  save.addEventListener('click', async () => {
    showAlert(pageAlert, null, null);
    let value;
    try {
      value = field.read();
    } catch (e) {
      showAlert(pageAlert, 'warning', e.message);
      return;
    }
    if (def.key.endsWith('.enabled') && value === false
        && !await confirmDialog('停用資料來源', `確定要停止抓取「${def.group.replace('資料來源：', '')}」？停用期間不會有這個來源的資料。`, '停用')) {
      return;
    }
    await withBusy(save, async () => {
      try {
        const j = await api('PUT', `/api/admin/settings/${encodeURIComponent(def.key)}`, { value });
        render(j.settings);
        toast(`已儲存：${def.label}`);
      } catch (e) {
        fail(e);
      }
    });
  });

  reset.addEventListener('click', async () => {
    if (!await confirmDialog('還原預設', `「${def.label}」要還原成 ${displayValue(def, def.defaultValue)}？`, '還原', 'primary')) {
      return;
    }
    showAlert(pageAlert, null, null);
    try {
      const j = await api('DELETE', `/api/admin/settings/${encodeURIComponent(def.key)}`);
      render(j.settings);
      toast(`已還原：${def.label}`);
    } catch (e) {
      fail(e);
    }
  });

  return h('div', { class: 'setting-row row g-2 py-3 align-items-center' },
    h('div', { class: 'col-lg-4' },
      h('label', { class: 'form-label fw-semibold mb-1', for: field.id }, def.label,
        def.overridden ? h('span', { class: 'badge text-bg-warning ms-2' }, '已修改') : null),
      h('div', { class: 'form-text mt-0' }, def.description)),
    h('div', { class: 'col-lg-5 min-w-0' }, field.wrap,
      h('div', { class: 'setting-default text-secondary mt-1' }, `預設值：${displayValue(def, def.defaultValue)}`)),
    h('div', { class: 'col-lg-3 text-lg-end' }, save, reset));
}

function render(settings) {
  const groups = new Map();
  for (const s of settings) {
    if (!groups.has(s.group)) groups.set(s.group, []);
    groups.get(s.group).push(s);
  }
  const cards = [...groups].map(([group, defs]) => h('div', { class: 'card mb-3' },
    h('div', { class: 'card-header' }, h('h3', { class: 'card-title' }, group)),
    h('div', { class: 'card-body py-0' }, defs.map(settingRow))));
  $('#settings-groups').replaceChildren(...cards);
}

(async () => {
  const me = await initUser(shell);
  if (!me) {
    location.href = '/login.html?next=/admin-settings.html';
    return;
  }
  try {
    render((await api('GET', '/api/admin/settings')).settings);
  } catch (e) {
    fail(e);
  }
})();

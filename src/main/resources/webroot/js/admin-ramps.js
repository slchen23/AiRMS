// 路網匝道管理：每個匝道圈的顯示／隱藏與 RMS 設備代碼。
import { api, confirmDialog, DT_LANGUAGE, fmtTime, h, handleAuthError, showAlert, toast, withBusy, $ } from './common.js';
import { initUser, renderShell } from './layout.js';

const { DataTable, bootstrap } = window;
const shell = renderShell('admin-ramps');
const pageAlert = $('#ramps-alert');
const modal = new bootstrap.Modal($('#ramp-modal'));

let rows = [];
let unassigned = [];
let editing = null;

const DIR = { S: '南下', N: '北上' };

function fail(e, box = pageAlert) {
  if (!handleAuthError(e)) showAlert(box, 'danger', e.message);
}

function statusBadges(r) {
  const out = [];
  if (r.overridden) out.push(['warning', '已修改']);
  if (!r.defaultVisible) out.push(['secondary', '預設隱藏']);
  if (r.rms.length > 1) out.push(['info', `切 ${r.rms.length} 格`]);
  return out.map(([kind, text]) => h('span', { class: `badge text-bg-${kind} me-1 mb-1` }, text));
}

function rmsCell(r) {
  if (!r.rms.length) return [h('span', { class: 'text-secondary' }, r.kind === 'outside' ? '（範圍外）' : '（無）')];
  return r.live.map((x) => h('div', { class: 'rms-line' },
    h('span', { class: `badge ${x.present ? 'text-bg-success' : 'text-bg-secondary'} me-1` }, x.present ? '有資料' : '無資料'),
    h('span', { class: 'font-monospace small text-break' }, x.id)));
}

const table = new DataTable('#ramps', {
  data: [],
  language: DT_LANGUAGE,
  order: [],
  pageLength: 50,
  scrollX: true,
  autoWidth: false, // 初始化時沒有資料，自動欄寬會依標題算錯，交給瀏覽器排版
  columns: [
    {
      data: 'freewayId',
      className: 'text-nowrap',
      render: (d, type, row) => (type === 'sort' || type === 'type' ? `${d}-${row.direction}` : `國${d} ${DIR[row.direction] || ''}`),
    },
    { data: 'name', className: 'text-nowrap', render: DataTable.render.text() },
    { data: 'km', className: 'text-nowrap text-start', render: (d, type, row) => (type === 'sort' || type === 'type' ? row.milepost : DataTable.render.text().display(d)) },
    {
      data: 'visible',
      className: 'text-nowrap',
      render: (d, type) => (type === 'display' ? '' : (d ? '顯示' : '隱藏')),
      createdCell: (td, d, row) => {
        const input = h('input', { class: 'form-check-input', type: 'checkbox', role: 'switch', id: `v-${row.key}`,
          dataset: { key: row.key }, 'aria-label': `${row.label} 顯示` });
        input.checked = Boolean(d);
        td.replaceChildren(h('div', { class: 'form-check form-switch mb-0' }, input,
          h('label', { class: 'form-check-label small', for: `v-${row.key}` }, d ? '顯示' : '隱藏')));
      },
    },
    {
      data: 'rms',
      orderable: false,
      render: (d) => d.join(' ').replace(/[<>&"]/g, ''),
      createdCell: (td, d, row) => td.replaceChildren(...rmsCell(row)),
    },
    {
      data: null,
      orderable: false,
      width: '6rem',
      render: (d, type, row) => (type === 'display' ? '' : statusBadges(row).map((x) => x.textContent).join(' ')),
      createdCell: (td, d, row) => td.replaceChildren(...statusBadges(row)),
    },
    {
      data: 'updatedAt',
      className: 'text-nowrap small text-start',
      render: (d, type, row) => {
        if (type === 'sort' || type === 'type') return d || 0;
        return type === 'display' ? '' : (d ? `${fmtTime(d)} ${row.updatedBy || ''}` : '');
      },
      createdCell: (td, d, row) => td.replaceChildren(...(d
        ? [h('div', null, fmtTime(d)), h('div', { class: 'text-secondary' }, row.updatedBy || '')] : [])),
    },
    {
      data: null,
      orderable: false,
      searchable: false,
      className: 'text-nowrap',
      render: () => '',
      createdCell: (td, d, row) => {
        const edit = h('button', { type: 'button', class: 'btn btn-outline-primary btn-sm me-1', dataset: { action: 'edit', key: row.key } },
          h('i', { class: 'bi bi-pencil', 'aria-hidden': 'true' }), ' 修改');
        const reset = h('button', { type: 'button', class: 'btn btn-outline-secondary btn-sm', dataset: { action: 'reset', key: row.key },
          title: '還原成路網設定檔的內容' }, h('i', { class: 'bi bi-arrow-counterclockwise', 'aria-hidden': 'true' }), ' 還原');
        reset.disabled = !row.overridden;
        td.replaceChildren(edit, reset);
      },
    },
  ],
});

function filtered() {
  const fw = $('#f-freeway').value;
  const dir = $('#f-dir').value;
  const changed = $('#f-changed').checked;
  return rows.filter((r) => (!fw || r.freewayId === fw) && (!dir || r.direction === dir)
    && (!changed || r.overridden || !r.visible));
}

function draw() {
  table.clear().rows.add(filtered()).draw(false);
}

function summary(liveCount) {
  const hidden = rows.filter((r) => !r.visible).length;
  const changed = rows.filter((r) => r.overridden).length;
  const card = (label, value, kind, note) => h('div', { class: 'col-6 col-lg-3' },
    h('div', { class: `small-box text-bg-${kind} mb-0` },
      h('div', { class: 'inner' }, h('h3', null, String(value)), h('p', { class: 'mb-0' }, label),
        note ? h('p', { class: 'small mb-0' }, note) : null)));
  $('#summary').replaceChildren(
    card('匝道圈', rows.length, 'primary'),
    card('隱藏中', hidden, hidden ? 'secondary' : 'light'),
    card('已修改', changed, changed ? 'warning' : 'light'),
    card('資料中未對應的 RMS', unassigned.length, unassigned.length ? 'danger' : 'success',
      `目前資料共 ${liveCount} 台 RMS`));
}

async function reload() {
  const j = await api('GET', '/api/admin/ramps');
  rows = j.ramps;
  unassigned = j.unassignedRms;
  summary(j.liveRmsCount);
  draw();
}

for (const id of ['#f-freeway', '#f-dir', '#f-changed']) $(id).addEventListener('change', draw);

// ---------------------------------------------------------------- 顯示開關

$('#ramps').addEventListener('change', async (ev) => {
  const input = ev.target.closest('input[data-key]');
  if (!input) return;
  const row = rows.find((r) => r.key === input.dataset.key);
  if (!row) return;
  showAlert(pageAlert, null, null);
  input.disabled = true;
  try {
    await api('PUT', `/api/admin/ramps/${encodeURIComponent(row.key)}`, { visible: input.checked });
    toast(`${row.label}：${input.checked ? '顯示' : '隱藏'}`);
    await reload();
  } catch (e) {
    input.checked = !input.checked;
    input.disabled = false;
    fail(e);
  }
});

// ---------------------------------------------------------------- 修改、還原

function setMode(mode) {
  $(`#r-mode-${mode}`).checked = true;
  const custom = mode === 'custom';
  $('#r-rms').disabled = !custom;
  $('#r-pick').disabled = !custom || !unassigned.length;
  $('#r-pick-add').disabled = !custom || !unassigned.length;
  if (!custom) $('#r-rms').value = editing.defaultRms.join('\n');
}

function openEditor(row) {
  editing = row;
  showAlert($('#ramp-form-alert'), null, null);
  $('#ramp-modal-title').textContent = `修改匝道圈：${row.label}`;
  $('#r-visible').checked = row.visible;
  $('#r-visible-default').textContent = `路網設定檔預設：${row.defaultVisible ? '顯示' : '隱藏'}`;
  $('#r-default-rms').textContent = row.defaultRms.length ? row.defaultRms.join('、')
    : (row.defaultKind === 'outside' ? '範圍外，沒有 RMS' : '沒有 RMS');
  $('#r-pick').replaceChildren(...(unassigned.length
    ? unassigned.map((id) => h('option', { value: id }, id))
    : [h('option', { value: '' }, '（目前沒有未對應的 RMS）')]));
  if (row.overrideRms === null) {
    setMode('default');
  } else {
    $('#r-rms').value = row.overrideRms.join('\n');
    setMode('custom');
  }
  modal.show();
}

$('#r-mode-default').addEventListener('change', () => setMode('default'));
$('#r-mode-custom').addEventListener('change', () => {
  const keep = $('#r-rms').value;
  setMode('custom');
  $('#r-rms').value = keep;
});
$('#r-pick-add').addEventListener('click', () => {
  const id = $('#r-pick').value;
  if (!id) return;
  const lines = $('#r-rms').value.split('\n').map((s) => s.trim()).filter(Boolean);
  if (!lines.includes(id)) lines.push(id);
  $('#r-rms').value = lines.join('\n');
});

$('#ramp-form').addEventListener('submit', async (ev) => {
  ev.preventDefault();
  showAlert($('#ramp-form-alert'), null, null);
  const custom = $('#r-mode-custom').checked;
  const body = {
    visible: $('#r-visible').checked,
    rms: custom ? $('#r-rms').value.split('\n').map((s) => s.trim()).filter(Boolean) : null,
  };
  await withBusy($('#ramp-submit'), async () => {
    try {
      await api('PUT', `/api/admin/ramps/${encodeURIComponent(editing.key)}`, body);
      modal.hide();
      toast(`已儲存：${editing.label}`);
      await reload();
    } catch (e) {
      fail(e, $('#ramp-form-alert'));
    }
  });
});

$('#ramps').addEventListener('click', async (ev) => {
  const b = ev.target.closest('button[data-action]');
  if (!b) return;
  const row = rows.find((r) => r.key === b.dataset.key);
  if (!row) return;
  showAlert(pageAlert, null, null);
  if (b.dataset.action === 'edit') {
    openEditor(row);
    return;
  }
  if (!await confirmDialog('還原預設', `「${row.label}」要還原成路網設定檔的內容？`, '還原', 'primary')) return;
  try {
    await api('DELETE', `/api/admin/ramps/${encodeURIComponent(row.key)}`);
    toast(`已還原：${row.label}`);
    await reload();
  } catch (e) {
    fail(e);
  }
});

(async () => {
  const me = await initUser(shell);
  if (!me) {
    location.href = '/login.html?next=/admin-ramps.html';
    return;
  }
  try {
    await reload();
  } catch (e) {
    fail(e);
  }
})();

// 路網路段（查看用）：來源 XML 的每一筆路段畫在圖上哪裡、目前速度分級。
import { api, DT_LANGUAGE, fmtTime, h, handleAuthError, showAlert, toast, withBusy, $ } from './common.js';
import { initUser, renderShell } from './layout.js';
import { kmText, renderNetwork, sectionKey } from './network-svg.js';

const { DataTable } = window;
const shell = renderShell('admin-sections');
const pageAlert = $('#sections-alert');
const SVG_NS = 'http://www.w3.org/2000/svg';

const MAP_STATUS = {
  FULL: ['success', '完整'],
  PARTIAL: ['warning', '部分在圖外'],
  OUTSIDE: ['secondary', '不在圖上'],
  NO_ROUTE: ['danger', '圖上沒有這條路線'],
};

let view = null;
let colors = new Map();
let labels = new Map();
let rows = [];
let selectedKey = null;

function fail(e) {
  if (!handleAuthError(e)) showAlert(pageAlert, 'danger', e.message);
}

function needsAttention(r) {
  return r.mapStatus !== 'FULL' || r.stale || r.speed === null || r.speed === undefined;
}

function swatch(cls) {
  const svg = document.createElementNS(SVG_NS, 'svg');
  svg.setAttribute('width', '16');
  svg.setAttribute('height', '16');
  svg.setAttribute('class', 'speed-swatch me-1');
  svg.setAttribute('aria-hidden', 'true');
  const rect = document.createElementNS(SVG_NS, 'rect');
  for (const [k, v] of Object.entries({ x: 0.5, y: 0.5, width: 15, height: 15, rx: 2, fill: colors.get(cls) || '#777' })) {
    rect.setAttribute(k, String(v));
  }
  svg.append(rect);
  return svg;
}

const num = (d, type, suffix) => {
  if (type === 'sort' || type === 'type') return d ?? -1;
  return d === null || d === undefined ? '—' : `${d}${suffix}`;
};

const table = new DataTable('#sections', {
  data: [],
  language: DT_LANGUAGE,
  order: [],
  paging: false,
  scrollX: true,
  autoWidth: false,
  columns: [
    { data: 'routeLabel', className: 'text-nowrap', render: DataTable.render.text() },
    {
      data: 'fromName',
      className: 'text-nowrap',
      render: (d, type, row) => DataTable.render.text().display(`${d || ''} → ${row.toName || ''}`),
    },
    {
      data: 'lo',
      className: 'text-nowrap text-start',
      render: (d, type, row) => (type === 'sort' || type === 'type' ? d : `${row.fromKm}～${row.toKm}`),
      createdCell: (td, d, row) => { td.title = `長度 ${(row.length / 1000).toFixed(1)} 公里`; },
    },
    { data: 'speed', className: 'text-nowrap text-start', render: (d, type) => num(d, type, ' km/h'), defaultContent: '—' },
    { data: 'volume', className: 'text-nowrap text-start', render: (d, type) => num(d, type, ''), defaultContent: '—' },
    {
      data: 'cls',
      className: 'text-nowrap',
      render: (d, type) => (type === 'display' ? '' : (labels.get(d) || d)),
      createdCell: (td, d) => td.replaceChildren(swatch(d), labels.get(d) || d),
    },
    {
      data: 'mapStatus',
      className: 'text-nowrap',
      render: (d, type) => (type === 'display' ? '' : (MAP_STATUS[d] || [null, d])[1]),
      createdCell: (td, d, row) => {
        const [kind, text] = MAP_STATUS[d] || ['secondary', d];
        const badge = h('span', { class: `badge text-bg-${kind}` }, text);
        if (d === 'PARTIAL') badge.title = `圖上只畫 ${kmText(row.drawnFrom)}～${kmText(row.drawnTo)}`;
        td.replaceChildren(badge);
      },
    },
    {
      data: 'feedTime',
      type: 'string',
      className: 'text-nowrap small text-start',
      render: (d, type, row) => (type === 'display' ? '' : `${d || ''}${row.stale ? ' 最新資料沒有' : ''}`),
      createdCell: (td, d, row) => {
        // 「2026-11-03 23:35:28」→「11/03 23:35」，完整時間放在提示
        const short = d && d.length >= 16 ? `${d.slice(5, 10).replace('-', '/')} ${d.slice(11, 16)}` : (d || '');
        td.title = d ? d.replaceAll('-', '/') : '';
        td.replaceChildren(row.stale ? h('span', { class: 'badge text-bg-danger me-1' }, '最新資料沒有') : '', short);
      },
    },
  ],
  createdRow: (tr, row) => {
    tr.dataset.key = row.key;
    tr.classList.add('section-row');
    if (row.key === selectedKey) tr.classList.add('is-selected');
  },
});

function filtered() {
  const fw = $('#f-freeway').value;
  const dir = $('#f-dir').value;
  const attention = $('#f-attention').checked;
  return rows.filter((r) => (!fw || r.freewayId === fw) && (!dir || r.dir === dir) && (!attention || needsAttention(r)));
}

function draw() {
  table.clear().rows.add(filtered()).draw(false);
}

for (const id of ['#f-freeway', '#f-dir', '#f-attention']) $(id).addEventListener('change', draw);

function summary(c) {
  const card = (label, value, kind, note) => h('div', { class: 'col-6 col-lg' },
    h('div', { class: `small-box text-bg-${kind} mb-0` },
      h('div', { class: 'inner' }, h('h3', null, String(value)), h('p', { class: 'mb-0' }, label),
        note ? h('p', { class: 'small mb-0' }, note) : null)));
  const off = c.partial + c.outside + c.noRoute;
  $('#summary').replaceChildren(
    card('最新資料的路段', c.current, 'primary', c.total > c.current ? `另有 ${c.total - c.current} 筆舊路段` : null),
    card('完整畫在圖上', c.full, 'success'),
    card('部分在圖外／不在圖上', off, off ? 'warning' : 'light'),
    card('最新資料中沒有', c.notInLatest, c.notInLatest ? 'danger' : 'light', c.notInLatest ? '可能已重新切段' : null),
    card('相鄰路段空隙／重疊', c.issues, c.issues ? 'danger' : 'light'),
  );
}

function issuesBox(issues) {
  if (!issues.length) {
    $('#issues').replaceChildren();
    return;
  }
  const items = issues.map((x) => h('li', null,
    `${x.routeLabel} ${x.fromKm}～${x.toKm}：${x.kind === 'GAP' ? '沒有路段資料（空隙）' : '兩段重疊'}`,
    h('span', { class: 'text-secondary small' }, `（${x.aText} 與 ${x.bText}）`)));
  $('#issues').replaceChildren(h('div', { class: 'alert alert-warning' },
    h('div', { class: 'fw-semibold mb-1' }, '最新資料中，同一路線相鄰路段的接縫有問題：'),
    h('ul', { class: 'mb-0' }, items)));
}

// ---------------------------------------------------------------- 選取

function markRow(key) {
  document.querySelectorAll('#sections tr.is-selected').forEach((tr) => tr.classList.remove('is-selected'));
  const tr = key ? document.querySelector(`#sections tr[data-key="${CSS.escape(key)}"]`) : null;
  if (tr) tr.classList.add('is-selected');
  return tr;
}

function select(row, { scroll = false } = {}) {
  selectedKey = row ? row.key : null;
  const drawn = view ? view.highlightSection(row) : false;
  const tr = markRow(selectedKey);
  if (row) {
    $('#map-hint').textContent = drawn
      ? `${row.routeLabel} ${row.fromName || ''}→${row.toName || ''}（${row.fromKm}～${row.toKm}）`
        + `${row.mapStatus === 'PARTIAL' ? `，圖上只畫 ${kmText(row.drawnFrom)}～${kmText(row.drawnTo)}` : ''}`
      : `${row.routeLabel} ${row.fromName || ''}→${row.toName || ''}（${row.fromKm}～${row.toKm}）不在圖上`;
  }
  if (row && !drawn) toast('這一段不在圖上', 'warning');
  if (scroll && tr) tr.scrollIntoView({ block: 'end', behavior: 'smooth' });
}

$('#sections').addEventListener('click', (ev) => {
  const tr = ev.target.closest('tr[data-key]');
  if (!tr) return;
  const row = rows.find((r) => r.key === tr.dataset.key);
  if (row) select(row);
});

function onMapClick(item) {
  const key = sectionKey(item);
  const row = rows.find((r) => r.key === key);
  if (!row) return;
  if (!filtered().includes(row)) {
    $('#f-freeway').value = '';
    $('#f-dir').value = '';
    $('#f-attention').checked = false;
    draw();
  }
  if (table.search()) table.search('').draw(false);
  select(row, { scroll: true });
}

// ---------------------------------------------------------------- 載入

async function load() {
  const [net, live, sec] = await Promise.all([
    view ? null : api('GET', '/api/view/network'),
    api('GET', '/api/view/live'),
    api('GET', '/api/admin/sections'),
  ]);
  if (net) {
    view = renderNetwork($('#network'), net, { onSectionClick: onMapClick });
    colors = new Map(net.speedClasses.map((c) => [c.key, c.color]));
  }
  labels = new Map(sec.speedClasses.map((c) => [c.key, c.label]));
  view.updateLegend(sec.speedClasses);
  view.updateSections(live.sections.items);
  view.updateRms(live.rms.items);
  view.updateScreenlines(live.screenlines.items);
  const t = (b) => (b && b.feedTime ? [b.feedTime.replaceAll('-', '/'), Boolean(b.stale)] : null);
  view.updateTimes({ sections: t(live.sections), rms: t(live.rms), screenlines: t(live.screenlines),
    refreshed: [fmtTime(Date.now()), false] });

  rows = sec.sections;
  const c = sec.speedClasses;
  $('#steps-text').textContent = c.slice(1).map((x) => x.label).join('、') + ' 公里/小時'
    + `；超過 ${sec.staleMinutes} 分鐘沒更新視為過時`;
  $('#feed-time').textContent = sec.feedTime ? sec.feedTime.replaceAll('-', '/') + (sec.stale ? '（已過時）' : '') : '尚無資料';
  summary(sec.counts);
  issuesBox(sec.issues);
  draw();
  const again = rows.find((r) => r.key === selectedKey);
  select(again || null);
  if (!again) $('#map-hint').textContent = '點表格的一列，圖上會閃爍標出那一段；點圖上的路段，表格會跳到那一列。';
}

$('#reload').addEventListener('click', () => withBusy($('#reload'), () => load().catch(fail)));

(async () => {
  const me = await initUser(shell);
  if (!me) {
    location.href = '/login.html?next=/admin-sections.html';
    return;
  }
  try {
    await load();
  } catch (e) {
    fail(e);
  }
})();

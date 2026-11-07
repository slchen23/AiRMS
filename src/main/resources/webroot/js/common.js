// AiRMS 前端共用工具。所有畫面文字都以 textContent 放入，不用 innerHTML，避免資料被當成 HTML 執行。

export const TIME_ZONE = 'Asia/Taipei';

const dtFormat = new Intl.DateTimeFormat('zh-TW', {
  timeZone: TIME_ZONE, year: 'numeric', month: '2-digit', day: '2-digit',
  hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23',
});
const dateFormat = new Intl.DateTimeFormat('zh-TW', {
  timeZone: TIME_ZONE, year: 'numeric', month: '2-digit', day: '2-digit',
});

function parts(fmt, ms) {
  const o = {};
  for (const p of fmt.formatToParts(new Date(ms))) o[p.type] = p.value;
  return o;
}

/** 毫秒 → 「2026/11/03 08:15:02」（台北時間）。 */
export function fmtTime(ms) {
  if (ms === null || ms === undefined) return '';
  const p = parts(dtFormat, ms);
  return `${p.year}/${p.month}/${p.day} ${p.hour}:${p.minute}:${p.second}`;
}

/** 毫秒 → 「2026/11/03」（台北時間）。 */
export function fmtDate(ms) {
  if (ms === null || ms === undefined) return '';
  const p = parts(dateFormat, ms);
  return `${p.year}/${p.month}/${p.day}`;
}

/** 台北時間的「YYYY-MM-DD」（給 input[type=date]）。 */
export function isoDate(ms) {
  const p = parts(dateFormat, ms);
  return `${p.year}-${p.month}-${p.day}`;
}

/** input[type=date] 的值（台北時間當天 00:00）→ 毫秒。 */
export function dateInputToMs(value) {
  if (!value) return null;
  const ms = Date.parse(`${value}T00:00:00+08:00`);
  return Number.isNaN(ms) ? null : ms;
}

// ---------------------------------------------------------------- API

let csrfToken = null;

export class ApiError extends Error {
  constructor(status, message, body) {
    super(message);
    this.status = status;
    this.body = body;
  }
}

/** 目前登入者；未登入回傳 null。同時取得 CSRF token。 */
export async function loadMe() {
  const r = await fetch('/api/auth/me', { headers: { Accept: 'application/json' }, cache: 'no-store' });
  if (!r.ok) throw new ApiError(r.status, '無法取得登入狀態');
  const j = await r.json();
  csrfToken = j.user ? j.csrfToken : null;
  return j.user || null;
}

export function setCsrfToken(token) {
  csrfToken = token;
}

/**
 * 呼叫後端 API。寫入請求自動帶 CSRF token。回應 204 時回傳 null；錯誤時丟 ApiError（訊息可直接顯示）。
 */
export async function api(method, url, body) {
  const headers = { Accept: 'application/json' };
  const init = { method, headers, cache: 'no-store' };
  if (body !== undefined) {
    headers['Content-Type'] = 'application/json';
    init.body = JSON.stringify(body);
  }
  if (method !== 'GET' && csrfToken) headers['X-CSRF-Token'] = csrfToken;
  let r;
  try {
    r = await fetch(url, init);
  } catch (e) {
    throw new ApiError(0, '無法連線到伺服器');
  }
  if (r.status === 204) return null;
  let j = null;
  try {
    j = await r.json();
  } catch (e) {
    j = null;
  }
  if (!r.ok) throw new ApiError(r.status, (j && j.error) || `系統錯誤（${r.status}）`, j);
  return j;
}

/**
 * 下載檔案（POST JSON，回傳 { blob, headers }）。錯誤時同 api() 丟 ApiError（伺服器回 JSON 的 error 訊息）。
 */
export async function apiDownload(url, body) {
  const headers = { Accept: 'text/csv, application/json', 'Content-Type': 'application/json' };
  if (csrfToken) headers['X-CSRF-Token'] = csrfToken;
  let r;
  try {
    r = await fetch(url, { method: 'POST', headers, cache: 'no-store', body: JSON.stringify(body) });
  } catch (e) {
    throw new ApiError(0, '無法連線到伺服器');
  }
  if (!r.ok) {
    let j = null;
    try {
      j = await r.json();
    } catch (e) {
      j = null;
    }
    throw new ApiError(r.status, (j && j.error) || `系統錯誤（${r.status}）`, j);
  }
  return { blob: await r.blob(), headers: r.headers };
}

/** 管理頁共用：登入逾時、需要先改密碼時自動轉頁；回傳 true 表示已處理（呼叫端不必再顯示錯誤）。 */
export function handleAuthError(err) {
  if (!(err instanceof ApiError)) return false;
  if (err.status === 401) {
    location.href = `/login.html?next=${encodeURIComponent(location.pathname)}`;
    return true;
  }
  if (err.status === 403 && err.body && err.body.mustChangePassword) {
    location.href = '/account.html?mustChange=1';
    return true;
  }
  return false;
}

/** 只接受本站頁面（/xxx.html），防止登入後被導到外部網站。 */
export function safeNext(value) {
  if (typeof value === 'string' && /^\/[A-Za-z0-9_-]+\.html$/.test(value) && value !== '/login.html'
      && value !== '/setup.html') {
    return value;
  }
  return '/index.html';
}

// ---------------------------------------------------------------- DOM

/**
 * 建立元素：h('a', {class: 'btn', href: '/x'}, '文字', childEl)。
 * 屬性以 setAttribute 設定（不支援 on* 事件屬性，事件請用 addEventListener）；子節點字串一律當純文字。
 */
export function h(tag, attrs, ...children) {
  const el = document.createElement(tag);
  if (attrs) {
    for (const [k, v] of Object.entries(attrs)) {
      if (v === null || v === undefined || v === false) continue;
      if (k.startsWith('on')) throw new Error('請改用 addEventListener');
      if (k === 'dataset') Object.assign(el.dataset, v);
      else el.setAttribute(k, v === true ? '' : String(v));
    }
  }
  append(el, children);
  return el;
}

function append(el, children) {
  for (const c of children) {
    if (c === null || c === undefined || c === false) continue;
    if (Array.isArray(c)) append(el, c);
    else el.append(c instanceof Node ? c : document.createTextNode(String(c)));
  }
}

export function icon(name) {
  return h('i', { class: `bi bi-${name}`, 'aria-hidden': 'true' });
}

export function $(selector, root = document) {
  return root.querySelector(selector);
}

/** 在容器中顯示提示（success/danger/warning/info）；傳 null 訊息即清除。 */
export function showAlert(container, kind, message) {
  container.replaceChildren();
  if (!message) return;
  container.append(h('div', { class: `alert alert-${kind} py-2 mb-3`, role: 'alert' }, message));
}

/** 右上角短暫提示。 */
export function toast(message, kind = 'success') {
  let area = document.getElementById('toast-area');
  if (!area) {
    area = h('div', { id: 'toast-area', class: 'toast-container position-fixed top-0 end-0 p-3 toast-area' });
    document.body.append(area);
  }
  const t = h('div', { class: `toast align-items-center text-bg-${kind} border-0`, role: 'status', 'aria-live': 'polite' },
    h('div', { class: 'd-flex' },
      h('div', { class: 'toast-body' }, message),
      h('button', { type: 'button', class: 'btn-close btn-close-white me-2 m-auto', 'data-bs-dismiss': 'toast',
        'aria-label': '關閉' })));
  area.append(t);
  t.addEventListener('hidden.bs.toast', () => t.remove());
  new window.bootstrap.Toast(t, { delay: 3500 }).show();
}

/** 按鈕送出期間停用並顯示轉圈。 */
export async function withBusy(button, fn) {
  const original = [...button.childNodes];
  button.disabled = true;
  button.replaceChildren(h('span', { class: 'spinner-border spinner-border-sm me-1', 'aria-hidden': 'true' }),
    '處理中…');
  try {
    return await fn();
  } finally {
    button.disabled = false;
    button.replaceChildren(...original);
  }
}

/** 確認對話框（Bootstrap modal），回傳 Promise<boolean>。 */
export function confirmDialog(title, message, okText = '確定', okKind = 'danger') {
  return new Promise((resolve) => {
    const ok = h('button', { type: 'button', class: `btn btn-${okKind}` }, okText);
    const modal = h('div', { class: 'modal fade', tabindex: '-1', 'aria-hidden': 'true' },
      h('div', { class: 'modal-dialog modal-dialog-centered' },
        h('div', { class: 'modal-content' },
          h('div', { class: 'modal-header' }, h('h5', { class: 'modal-title' }, title),
            h('button', { type: 'button', class: 'btn-close', 'data-bs-dismiss': 'modal', 'aria-label': '關閉' })),
          h('div', { class: 'modal-body' }, message),
          h('div', { class: 'modal-footer' },
            h('button', { type: 'button', class: 'btn btn-secondary', 'data-bs-dismiss': 'modal' }, '取消'), ok))));
    document.body.append(modal);
    const m = new window.bootstrap.Modal(modal);
    let result = false;
    ok.addEventListener('click', () => {
      result = true;
      m.hide();
    });
    modal.addEventListener('hidden.bs.modal', () => {
      m.dispose();
      modal.remove();
      resolve(result);
    });
    m.show();
  });
}

export const PASSWORD_HINT = '至少 10 個字元，需同時包含字母與數字，且不可包含帳號名稱。';

export const ROLE_LABEL = { ADMIN: '管理者', USER: '一般使用者' };

/** DataTables 共用中文介面文字。 */
export const DT_LANGUAGE = {
  emptyTable: '沒有資料',
  info: '第 _START_～_END_ 筆，共 _TOTAL_ 筆',
  infoEmpty: '共 0 筆',
  infoFiltered: '（從 _MAX_ 筆中篩選）',
  lengthMenu: '每頁 _MENU_ 筆',
  loadingRecords: '載入中…',
  processing: '處理中…',
  search: '搜尋：',
  zeroRecords: '沒有符合的資料',
  paginate: { first: '第一頁', last: '最後一頁', next: '下一頁', previous: '上一頁' },
  aria: { orderable: '點選排序', orderableReverse: '點選反向排序' },
};

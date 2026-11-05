// 共用版面（AdminLTE 4）：上方列、左側選單、頁尾。各頁在 <div class="app-wrapper"> 裡只放 <main>，
// 載入時同步插入版面（在 AdminLTE 初始化之前），登入狀態取得後再補上使用者選單與管理選單。
import { api, h, icon, loadMe, ROLE_LABEL } from './common.js';

const MENU = [
  { key: 'home', href: '/index.html', icon: 'house', label: '首頁' },
  { key: 'view', href: '/view2.html', icon: 'display', label: '即時監視' },
  { key: 'control', icon: 'sliders', label: 'AiRMS 控制', pending: true },
  { key: 'query', icon: 'download', label: '資料下載', pending: true },
];

const ADMIN_MENU = [
  { key: 'admin-users', href: '/admin-users.html', icon: 'people', label: '使用者管理' },
  { key: 'admin-sessions', href: '/admin-sessions.html', icon: 'shield-lock', label: '登入管理' },
  { key: 'admin-ramps', href: '/admin-ramps.html', icon: 'signpost-split', label: '路網匝道' },
  { key: 'admin-sections', href: '/admin-sections.html', icon: 'bezier2', label: '路網路段' },
  { key: 'admin-settings', href: '/admin-settings.html', icon: 'gear', label: '系統設定' },
];

function menuItem(item, active) {
  const p = h('p', null, item.label);
  if (item.pending) p.append(' ', h('span', { class: 'nav-badge badge text-bg-secondary' }, '建置中'));
  const link = item.pending
    ? h('span', { class: 'nav-link is-pending', 'aria-disabled': 'true' }, h('i', { class: `nav-icon bi bi-${item.icon}` }), p)
    : h('a', { href: item.href, class: `nav-link${item.key === active ? ' active' : ''}`,
      'aria-current': item.key === active ? 'page' : null }, h('i', { class: `nav-icon bi bi-${item.icon}` }), p);
  return h('li', { class: 'nav-item' }, link);
}

/** 插入版面。active 為選單代碼。回傳 {userArea, adminItems}。 */
export function renderShell(active) {
  const wrapper = document.querySelector('.app-wrapper');
  const userArea = h('ul', { class: 'navbar-nav ms-auto' });
  const header = h('nav', { class: 'app-header navbar navbar-expand bg-body' },
    h('div', { class: 'container-fluid' },
      h('ul', { class: 'navbar-nav' },
        h('li', { class: 'nav-item' },
          h('a', { class: 'nav-link', 'data-lte-toggle': 'sidebar', href: '#', role: 'button', 'aria-label': '切換選單' },
            icon('list')))),
      userArea));

  const adminItems = [h('li', { class: 'nav-header d-none' }, '管理'),
    ...ADMIN_MENU.map((m) => {
      const li = menuItem(m, active);
      li.classList.add('d-none');
      return li;
    })];
  const sidebar = h('aside', { class: 'app-sidebar bg-body-secondary shadow', 'data-bs-theme': 'dark' },
    h('div', { class: 'sidebar-brand' },
      h('a', { href: '/index.html', class: 'brand-link' }, h('span', { class: 'brand-text' }, 'AiRMS'))),
    h('div', { class: 'sidebar-wrapper' },
      h('nav', { class: 'mt-2' },
        h('ul', { class: 'nav sidebar-menu flex-column', 'data-lte-toggle': 'treeview', role: 'navigation',
          'aria-label': '主選單', 'data-accordion': 'false' },
        MENU.map((m) => menuItem(m, active)), adminItems))));

  const footer = h('footer', { class: 'app-footer' },
    h('span', null, '匝道儀控監控系統 AiRMS'),
    h('span', { class: 'float-end d-none d-sm-inline text-secondary' }, '時間皆為台灣時間'));

  wrapper.prepend(header, sidebar);
  wrapper.append(footer);
  return { userArea, adminItems };
}

/** 依登入狀態補上右上角選單；管理者顯示管理選單。回傳登入者或 null。 */
export async function initUser(shell) {
  let user = null;
  try {
    user = await loadMe();
  } catch (e) {
    user = null;
  }
  const area = shell.userArea;
  area.replaceChildren();
  if (!user) {
    const next = encodeURIComponent(location.pathname);
    area.append(h('li', { class: 'nav-item' },
      h('a', { class: 'nav-link', href: `/login.html?next=${next}` }, icon('box-arrow-in-right'), ' 登入')));
    return null;
  }
  if (user.role === 'ADMIN') shell.adminItems.forEach((el) => el.classList.remove('d-none'));

  const logout = h('button', { type: 'button', class: 'dropdown-item' }, icon('box-arrow-right'), ' 登出');
  logout.addEventListener('click', async () => {
    try {
      await api('POST', '/api/auth/logout');
    } finally {
      location.href = '/index.html';
    }
  });
  area.append(h('li', { class: 'nav-item dropdown' },
    h('a', { class: 'nav-link dropdown-toggle', href: '#', role: 'button', 'data-bs-toggle': 'dropdown',
      'aria-expanded': 'false' }, icon('person-circle'), ' ', user.displayName),
    h('ul', { class: 'dropdown-menu dropdown-menu-end' },
      h('li', null, h('span', { class: 'dropdown-item-text small text-secondary' },
        `${user.username}（${ROLE_LABEL[user.role] || user.role}）`)),
      h('li', null, h('hr', { class: 'dropdown-divider' })),
      h('li', null, h('a', { class: 'dropdown-item', href: '/account.html' }, icon('key'), ' 帳號與密碼')),
      h('li', null, logout))));
  return user;
}

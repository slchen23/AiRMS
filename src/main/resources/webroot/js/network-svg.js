// 路網圖 SVG 共用元件（view2、airms2、query 共用）。
// 依 /api/view/network 的設定檔畫出路線、匝道圓圈、標籤、方向與屏柵線鈴，再由 update* 函式套上即時資料。
// 只用 SVG 屬性與 class（CSP 不允許 inline style），文字一律 textContent。

const NS = 'http://www.w3.org/2000/svg';
const FONT = 'Microsoft JhengHei, Noto Sans TC, PingFang TC, sans-serif';
const CHAR_H = 26;
const RADIUS = 23;

/** 設備代碼統一用 NFKC 比對：來源資料有時用相容字（例如「北」U+F963）。 */
export function normId(id) {
  return typeof id === 'string' ? id.normalize('NFKC').trim() : '';
}

function el(tag, attrs, ...children) {
  const e = document.createElementNS(NS, tag);
  if (attrs) {
    for (const [k, v] of Object.entries(attrs)) {
      if (v === null || v === undefined || v === false) continue;
      e.setAttribute(k, String(v));
    }
  }
  for (const c of children) {
    if (c === null || c === undefined) continue;
    e.append(c instanceof Node ? c : document.createTextNode(String(c)));
  }
  return e;
}

function title(text) {
  return el('title', null, text);
}

function pointsAttr(pts) {
  return pts.map((p) => `${p[0].toFixed(1)},${p[1].toFixed(1)}`).join(' ');
}

/** 取路線上 [lo, hi] 里程之間的折線（點已依里程遞增）。 */
export function subPolyline(points, lo, hi) {
  const out = [];
  for (let i = 0; i < points.length - 1; i++) {
    const [x1, y1, m1] = points[i];
    const [x2, y2, m2] = points[i + 1];
    if (m2 <= m1) continue;
    const a = Math.max(lo, m1);
    const b = Math.min(hi, m2);
    if (a >= b) continue;
    const t1 = (a - m1) / (m2 - m1);
    const t2 = (b - m1) / (m2 - m1);
    if (!out.length) out.push([x1 + (x2 - x1) * t1, y1 + (y2 - y1) * t1]);
    out.push([x1 + (x2 - x1) * t2, y1 + (y2 - y1) * t2]);
  }
  return out;
}

/** 直書名稱：中文一字一行，括號內容（例如「(北)」）合成一行。 */
function verticalTokens(name) {
  const tokens = [];
  let i = 0;
  while (i < name.length) {
    if (name[i] === '(' || name[i] === '（') {
      const close = name.indexOf(name[i] === '(' ? ')' : '）', i);
      const end = close < 0 ? name.length : close + 1;
      tokens.push(name.slice(i, end));
      i = end;
    } else {
      tokens.push(name[i]);
      i += 1;
    }
  }
  return tokens;
}

function labelGroup(node) {
  const lab = node.label;
  const g = el('g', { class: `net-label${node.service ? ' is-service' : ''}` });
  if (lab.layout === 'v') {
    const tokens = verticalTokens(node.name);
    const small = node.service || tokens.length > 4;
    const size = small ? 18 : 24;
    const step = small ? 19 : CHAR_H;
    const name = el('text', { x: lab.x, y: lab.y, 'text-anchor': 'middle', 'font-size': size, 'font-family': FONT,
      class: 'net-name', 'dominant-baseline': 'hanging' });
    tokens.forEach((t, i) => {
      name.append(el('tspan', { x: lab.x, dy: i === 0 ? 0 : step, 'font-size': t.length > 1 ? size * 0.7 : null }, t));
    });
    g.append(name);
    g.append(el('text', { x: lab.x, y: lab.y + 4 * CHAR_H + 20, 'text-anchor': 'middle', 'font-size': 22,
      'font-family': FONT, class: 'net-km', 'dominant-baseline': 'hanging' }, node.km));
  } else {
    const t = el('text', { x: lab.x, y: lab.y, 'text-anchor': lab.anchor || 'start', 'font-size': 24,
      'font-family': FONT, 'dominant-baseline': 'central' });
    t.append(el('tspan', { class: 'net-name' }, node.name), el('tspan', { class: 'net-km', dx: 10 }, node.km));
    g.append(t);
  }
  return g;
}

/** 梅花形國道標誌。 */
function shield(freeway, x, y, size) {
  const g = el('g', { class: 'net-shield', transform: `translate(${x + size / 2},${y + size / 2})` });
  const r = size * 0.27;
  const d = size * 0.24;
  for (let k = 0; k < 5; k++) {
    const a = (-90 + k * 72) * Math.PI / 180;
    g.append(el('circle', { cx: (d * Math.cos(a)).toFixed(2), cy: (d * Math.sin(a)).toFixed(2), r: r.toFixed(2),
      class: 'shield-petal' }));
  }
  g.append(el('circle', { cx: 0, cy: 0, r: (size * 0.3).toFixed(2), class: 'shield-petal' }));
  g.append(el('text', { x: 0, y: 1, 'text-anchor': 'middle', 'dominant-baseline': 'central', 'font-size': size * 0.42,
    'font-weight': 'bold', 'font-family': FONT, class: 'shield-number' }, freeway));
  return g;
}

/** 鈴（屏柵線告警）圖示，原點在鈴的中心。 */
function bellIcon() {
  const g = el('g', { class: 'bell-icon' });
  g.append(el('path', { class: 'bell-waves', d: 'M-22,-10 Q-27,0 -22,10 M22,-10 Q27,0 22,10 M-27,-14 Q-34,0 -27,14 M27,-14 Q34,0 27,14' }));
  g.append(el('circle', { class: 'bell-body', cx: 0, cy: -17, r: 3 }));
  g.append(el('path', { class: 'bell-body', d: 'M-15,10 L15,10 L11,3 L11,-6 A11,11 0 0 0 -11,-6 L-11,3 Z' }));
  g.append(el('circle', { class: 'bell-body', cx: 0, cy: 14, r: 4 }));
  g.append(el('path', { class: 'bell-mark', d: 'M0,-9 L0,0 M0,4 L0,5' }));
  g.append(el('path', { class: 'bell-cross', d: 'M-20,-20 L20,20 M20,-20 L-20,20' }));
  return g;
}

/** RMS 圓圈：單一、切 2 格（左右）、切 3 格（上、左下、右下）。 */
function rampCircle(node, dir, ramp, rmsCells) {
  const label = `${node.name}（${dir === 'S' ? '南下' : '北上'}）`;
  const g = el('g', { class: `ramp ramp-${ramp.kind}`, transform: `translate(${ramp.x},${ramp.y})` });
  g.append(el('circle', { class: 'ramp-circle', cx: 0, cy: 0, r: RADIUS }));
  if (ramp.kind === 'outside') {
    g.append(el('text', { class: 'ramp-outside', x: 0, y: 1, 'text-anchor': 'middle', 'dominant-baseline': 'central',
      'font-size': 24, 'font-family': FONT }, '外'));
    g.append(title(`${label}：範圍外，無儀控`));
    return g;
  }
  if (ramp.kind !== 'rms') {
    g.append(title(`${label}：目前沒有 RMS 資料`));
    return g;
  }
  const ids = ramp.rms;
  let cells;
  if (ids.length === 1) {
    cells = [{ x: 0, y: 0, size: 21 }];
  } else if (ids.length === 2) {
    g.append(el('line', { class: 'ramp-split', x1: 0, y1: -RADIUS, x2: 0, y2: RADIUS }));
    cells = [{ x: -11, y: 0, size: 15 }, { x: 11, y: 0, size: 15 }];
  } else {
    g.append(el('line', { class: 'ramp-split', x1: -RADIUS, y1: 0, x2: RADIUS, y2: 0 }));
    g.append(el('line', { class: 'ramp-split', x1: 0, y1: 0, x2: 0, y2: RADIUS }));
    cells = [{ x: 0, y: -11, size: 14 }, { x: -10, y: 10, size: 13 }, { x: 10, y: 10, size: 13 }];
  }
  ids.forEach((id, i) => {
    const c = cells[Math.min(i, cells.length - 1)];
    const text = el('text', { class: 'ramp-rate', x: c.x, y: c.y + 1, 'text-anchor': 'middle',
      'dominant-baseline': 'central', 'font-size': c.size, 'font-weight': 'bold', 'font-family': FONT }, '');
    const t = title(`${label}\n${id}\n尚未取得資料`);
    // 每格一個透明熱區，滑鼠移上去看該台 RMS 的詳細狀態
    const hit = el('rect', { class: 'ramp-hit', x: c.x - (ids.length === 1 ? RADIUS : 11),
      y: c.y - (ids.length === 3 && i === 0 ? 11 : ids.length === 3 ? 10 : RADIUS),
      width: ids.length === 1 ? RADIUS * 2 : 22, height: ids.length === 3 ? (i === 0 ? 22 : 20) : RADIUS * 2 }, t);
    g.append(text, hit);
    rmsCells.set(normId(id), { text, title: t, label, id });
  });
  return g;
}

function endMarks(end) {
  const g = el('g', { class: 'net-ends' });
  g.append(shield(end.freeway, end.shield.x, end.shield.y, end.shield.size));
  for (const lab of end.labels) {
    const [x1, x2] = lab.arrow;
    g.append(el('line', { class: 'end-arrow', x1, y1: lab.y, x2, y2: lab.y, 'marker-end': 'url(#net-arrow)' }));
    const textX = end.side === 'left' ? Math.min(x1, x2) - 8 : Math.max(x1, x2) + 8;
    g.append(el('text', { class: 'end-label', x: textX, y: lab.y, 'text-anchor': end.side === 'left' ? 'end' : 'start',
      'dominant-baseline': 'central', 'font-size': 26, 'font-weight': 'bold', 'font-family': FONT }, lab.text));
  }
  return g;
}

function legend(net, panel, labelRefs) {
  const g = el('g', { class: 'net-legend', transform: `translate(${panel.x},${panel.y})` });
  g.append(el('rect', { class: 'panel-bg', x: 0, y: 0, width: 470, height: 128, rx: 8 }));
  g.append(el('text', { class: 'panel-title', x: 235, y: 26, 'text-anchor': 'middle', 'font-size': 22,
    'font-family': FONT }, '路段顏色速度對照圖例（公里/小時）'));
  // 色帶：最右邊（速度最快）一格畫成箭頭，同舊圖例；箭頭頭部比色帶上下各高 10。
  const w = 66;
  const top = 44;
  const h = 30;
  const head = 36;
  const last = net.speedClasses.length - 1;
  net.speedClasses.forEach((c, i) => {
    const x = 20 + i * w;
    if (i === last) {
      const pts = [[x, top], [x + w, top], [x + w, top - 10], [x + w + head, top + h / 2], [x + w, top + h + 10],
        [x + w, top + h], [x, top + h]].map((p) => p.join(',')).join(' ');
      g.append(el('polygon', { points: pts, fill: c.color, class: 'legend-swatch' }));
    } else {
      g.append(el('rect', { x, y: top, width: w, height: h, fill: c.color, class: 'legend-swatch' }));
    }
    const label = el('text', { class: 'panel-text', x: x + w / 2, y: 92, 'text-anchor': 'middle', 'font-size': 17,
      'font-family': FONT }, c.label);
    labelRefs.set(c.key, label);
    g.append(label);
  });
  const bells = [['bell-volume', '流量超過門檻'], ['bell-continuous', '連續兩期偏高'], ['bell-error', '資料異常']];
  bells.forEach(([cls, text], i) => {
    const bx = 34 + i * 150;
    const b = el('g', { class: `net-bell ${cls}`, transform: `translate(${bx},114) scale(0.45)` }, bellIcon());
    g.append(b, el('text', { class: 'panel-text', x: bx + 18, y: 115, 'dominant-baseline': 'central', 'font-size': 16,
      'font-family': FONT }, text));
  });
  return g;
}

function timesPanel(panel, refs) {
  const g = el('g', { class: 'net-times', transform: `translate(${panel.x},${panel.y})` });
  g.append(el('rect', { class: 'panel-bg', x: 0, y: 0, width: 470, height: 168, rx: 8 }));
  g.append(el('text', { class: 'panel-title', x: 20, y: 28, 'font-size': 22, 'font-family': FONT }, '雲端提供資料時間'));
  const rows = [['sections', '速度'], ['rms', '時制'], ['screenlines', '屏柵線'], ['refreshed', '畫面更新']];
  rows.forEach(([key, label], i) => {
    const y = 64 + i * 32;
    g.append(el('text', { class: 'panel-text', x: 128, y, 'text-anchor': 'end', 'font-size': 20, 'font-family': FONT },
      `${label}：`));
    const v = el('text', { class: 'panel-value', x: 136, y, 'font-size': 20, 'font-family': FONT }, '—');
    g.append(v);
    refs[key] = v;
  });
  return g;
}

/** 里程 99400 →「99.4K」。 */
export function kmText(m) {
  return `${(m / 1000).toFixed(1)}K`;
}

/** 路段代碼：國道|方向代碼|起點里程|終點里程（與後端 SectionCoverage 相同）。 */
export function sectionKey(s) {
  return `${s.freewayId}|${s.directionId}|${s.from}|${s.to}`;
}

/**
 * 畫出路網圖並回傳更新用的介面。
 * @param {SVGSVGElement} svg 目標 svg 元素（內容會被清空）
 * @param {object} net 路網設定檔
 * @param {object} [opts] onSectionClick(item)：點路段時呼叫（路網路段管理頁用）
 */
export function renderNetwork(svg, net, opts = {}) {
  const [vx, vy, vw, vh] = net.viewBox;
  svg.replaceChildren();
  svg.setAttribute('viewBox', `${vx} ${vy} ${vw} ${vh}`);
  svg.setAttribute('preserveAspectRatio', 'xMidYMin meet');

  const defs = el('defs');
  const marker = el('marker', { id: 'net-arrow', viewBox: '0 0 10 10', refX: 8, refY: 5, markerWidth: 5, markerHeight: 5,
    orient: 'auto-start-reverse' });
  marker.append(el('path', { d: 'M0,0 L10,5 L0,10 Z', class: 'end-arrow-head' }));
  defs.append(marker);
  svg.append(defs, el('rect', { class: 'net-bg', x: vx, y: vy, width: vw, height: vh }));

  // 國3 先畫、國1 後畫：交叉處國1在上，黑色外框做出立體交叉的效果。
  const order = [...net.freeways].sort((a, b) => (a.id === '3' ? -1 : 0) - (b.id === '3' ? -1 : 0));
  const sectionLayers = new Map();
  for (const fw of order) {
    const g = el('g', { class: `freeway freeway-${fw.id}` });
    for (const [dir, route] of Object.entries(fw.routes)) {
      const pts = pointsAttr(route.points);
      g.append(el('polyline', { class: 'road-casing', points: pts }));
      g.append(el('polyline', { class: 'road-base', points: pts }));
    }
    for (const [dir, route] of Object.entries(fw.routes)) {
      const layer = el('g', { class: 'road-sections' });
      g.append(layer);
      sectionLayers.set(`${fw.id}|${route.directionId}`, { layer, route, dir });
    }
    svg.append(g);
  }

  const labels = el('g', { class: 'net-labels' });
  const ramps = el('g', { class: 'net-ramps' });
  const rmsCells = new Map();
  for (const node of net.nodes) {
    labels.append(labelGroup(node));
    for (const [dir, ramp] of Object.entries(node.ramps || {})) {
      ramps.append(rampCircle(node, dir, ramp, rmsCells));
    }
  }
  svg.append(labels, ramps);

  const deco = el('g', { class: 'net-decorations' });
  for (const end of net.decorations.ends) deco.append(endMarks(end));
  for (const s of net.decorations.shields) deco.append(shield(s.freeway, s.x, s.y, s.size));
  svg.append(deco);

  const bells = new Map();
  const bellLayer = el('g', { class: 'net-bells' });
  for (const sl of net.screenlines) {
    const t = title(`${sl.label}屏柵線\n尚未取得資料`);
    const g = el('g', { class: 'net-bell is-hidden', transform: `translate(${sl.bell.x},${sl.bell.y})` }, bellIcon(), t);
    bellLayer.append(g);
    bells.set(sl.id, { g, title: t, def: sl });
  }
  svg.append(bellLayer);

  const timeRefs = {};
  const legendRefs = new Map();
  if (net.panels) {
    svg.append(legend(net, net.panels.legend, legendRefs), timesPanel(net.panels.times, timeRefs));
  }
  const highlightLayer = el('g', { class: 'net-highlight' });
  svg.append(highlightLayer);

  const colors = new Map(net.speedClasses.map((c) => [c.key, c.color]));

  return {
    /** sections: [{freewayId, directionId, from, to, cls, speed, volume}] */
    updateSections(items) {
      for (const { layer } of sectionLayers.values()) layer.replaceChildren();
      for (const s of items) {
        const target = sectionLayers.get(`${s.freewayId}|${s.directionId}`);
        if (!target) continue;
        const lo = Math.min(s.from, s.to);
        const hi = Math.max(s.from, s.to);
        const pts = subPolyline(target.route.points, lo, hi);
        if (pts.length < 2) continue;
        const line = el('polyline', { class: `road-section speed-${s.cls}${opts.onSectionClick ? ' is-clickable' : ''}`,
          points: pointsAttr(pts), stroke: colors.get(s.cls) || '#777' });
        const spd = s.speed === null || s.speed === undefined ? '無資料' : `${s.speed} km/h`;
        line.append(title(`國${s.freewayId} ${target.dir === 'S' ? '南下' : '北上'} ${s.fromName || ''}→${s.toName || ''}`
          + `（${kmText(s.from)}～${kmText(s.to)}）\n速度 ${spd}，流量 ${s.volume ?? '無資料'}`
          + `${s.stale ? '\n最新資料中沒有這段' : ''}`));
        if (opts.onSectionClick) line.addEventListener('click', () => opts.onSectionClick(s));
        target.layer.append(line);
      }
    },
    /** rms: [{id, display, rate, status, mode}] */
    updateRms(items) {
      const seen = new Set();
      for (const r of items) {
        const key = normId(r.id);
        const cell = rmsCells.get(key);
        if (!cell) continue;
        seen.add(key);
        cell.text.textContent = r.display;
        cell.text.classList.toggle('is-down', r.status !== '0');
        cell.title.textContent = `${cell.label}\n${cell.id}\n顯示：${r.display || '（空白）'}　原始：${r.rate ?? '無'}\n`
          + `通訊：${r.status === '0' ? '正常' : `異常（${r.status ?? '無'}）`}　模式：${r.mode ?? '無'}`;
      }
      for (const [key, cell] of rmsCells) {
        if (seen.has(key)) continue;
        cell.text.textContent = '';
        cell.title.textContent = `${cell.label}\n${cell.id}\n資料中沒有這台 RMS`;
      }
    },
    /** screenlines: [{id, state, judge, reason, vdId, volLimit, volContinuous, latest}] */
    updateScreenlines(items) {
      const byId = new Map(items.map((s) => [s.id, s]));
      for (const [id, bell] of bells) {
        const s = byId.get(id);
        const state = s ? s.state : 'ERROR';
        bell.g.setAttribute('class', `net-bell bell-${state.toLowerCase()}${state === 'OK' ? ' is-hidden' : ''}`);
        const desc = { OK: '正常', VOLUME: '12 期平均換算小時流量超過門檻', CONTINUOUS: '連續兩期流量超過門檻', ERROR: '資料異常' }[state];
        bell.title.textContent = s
          ? `${bell.def.label}屏柵線：${desc}\n${s.vdId}\n換算小時流量 ${s.judge ?? '—'}（門檻 ${s.volLimit}）`
            + `，單期門檻 ${s.volContinuous}${s.reason ? `\n${s.reason}` : ''}`
          : `${bell.def.label}屏柵線：沒有資料`;
      }
    },
    /** speedClasses: [{key, label}]：系統設定改了速度門檻時，圖例文字跟著改（顏色固定）。 */
    updateLegend(speedClasses) {
      for (const c of speedClasses || []) {
        const ref = legendRefs.get(c.key);
        if (ref && ref.textContent !== c.label) ref.textContent = c.label;
      }
    },
    /** 在圖上標出一個路段（null 清除）；回傳是否有畫出來（不在圖上回傳 false）。 */
    highlightSection(s) {
      highlightLayer.replaceChildren();
      if (!s) return false;
      const target = sectionLayers.get(`${s.freewayId}|${s.directionId}`);
      if (!target) return false;
      const pts = subPolyline(target.route.points, Math.min(s.from, s.to), Math.max(s.from, s.to));
      if (pts.length < 2) return false;
      const attr = pointsAttr(pts);
      highlightLayer.append(el('polyline', { class: 'section-highlight-halo', points: attr }),
        el('polyline', { class: 'section-highlight', points: attr }));
      for (const p of [pts[0], pts[pts.length - 1]]) {
        highlightLayer.append(el('circle', { class: 'section-highlight-end', cx: p[0].toFixed(1), cy: p[1].toFixed(1), r: 9 }));
      }
      return true;
    },
    /** times: {sections, rms, screenlines, refreshed} → [文字, 是否逾時] */
    updateTimes(times) {
      for (const [key, ref] of Object.entries(timeRefs)) {
        const v = times[key];
        ref.textContent = v ? v[0] : '—';
        ref.classList.toggle('is-stale', Boolean(v && v[1]));
      }
    },
  };
}

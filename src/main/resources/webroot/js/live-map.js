// 即時監視路網圖（即時監視與 AiRMS 控制頁共用）：載入路網、依 /api/view/live 更新路段顏色、匝道與屏柵線。
import { api, fmtTime } from './common.js';
import { renderNetwork } from './network-svg.js';

/** 來源檔頭時間「2026-11-03 08:55:31」→「2026/11/03 08:55:31」。 */
function feedTimeText(block) {
  if (!block || !block.feedTime) return null;
  return [block.feedTime.replaceAll('-', '/'), Boolean(block.stale)];
}

/** 畫出路網；回傳 { refresh() }，refresh 失敗時丟出 ApiError 由呼叫端處理。 */
export async function createLiveMap(svg) {
  const net = await api('GET', '/api/view/network');
  const view = renderNetwork(svg, net);
  return {
    async refresh() {
      const live = await api('GET', '/api/view/live');
      view.updateLegend(live.speedClasses);
      view.updateSections(live.sections.items);
      view.updateRms(live.rms.items);
      view.updateScreenlines(live.screenlines.items);
      view.updateTimes({
        sections: feedTimeText(live.sections),
        rms: feedTimeText(live.rms),
        screenlines: feedTimeText(live.screenlines),
        refreshed: [fmtTime(Date.now()), false],
      });
    },
  };
}

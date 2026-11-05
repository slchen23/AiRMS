package tw.org.ncree.teles.airms.view;

import java.util.ArrayList;
import java.util.List;

/**
 * 屏柵線告警判斷（沿用舊系統 PiUtil.getBell3/getBell4，加上無效車道規則）。
 *
 * <ul>
 *   <li>取最近 80 分鐘內、最新的 12 期（5 分鐘一期）VD 資料，每期流量 = 各車道 lane_total_car_volume 加總。</li>
 *   <li>資料異常：80 分鐘內沒有資料、最新一期超過 17 分鐘、最新一期有無效車道（-2 或缺值）、
 *       最新三期都沒有有效流量（≤0 或無效）。</li>
 *   <li>無效車道規則（新）：該期有任何無效車道就整期不計入（不把 -2 加進總量），也不能當作「連續」的一期。</li>
 *   <li>流量超過門檻：有效各期平均 × 12（換算每小時）&gt; volLimit。</li>
 *   <li>連續兩期偏高：相鄰兩期都有效且都 &gt; volContinuous。</li>
 * </ul>
 */
public final class ScreenlineEvaluator {
  public static final long WINDOW_MS = 80 * 60_000L;
  public static final long STALE_MS = 17 * 60_000L;
  public static final int MAX_PERIODS = 12;

  public enum State { OK, VOLUME, CONTINUOUS, ERROR }

  /** 一期資料。lanes = 車道數，badLanes = 無效車道數，total = 有效車道流量加總。 */
  public record Sample(long feedTimeMs, String feedTimeRaw, int lanes, int badLanes, int total) {
    public boolean valid() {
      return lanes > 0 && badLanes == 0;
    }
  }

  public record Result(State state, boolean volume, boolean continuous, boolean error, Integer judge, String reason,
                       int periods, int invalidPeriods, Long latestFeedTimeMs, String latestFeedTimeRaw) { }

  private ScreenlineEvaluator() { }

  /**
   * @param newestFirst 依資料時間由新到舊；超過 80 分鐘或超過 12 期的會被忽略
   * @param deviceKnown VD 資料中是否有這支 VD（沒有時理由寫清楚）
   */
  public static Result evaluate(List<Sample> newestFirst, long nowMs, int volLimit, int volContinuous,
                                boolean deviceKnown) {
    List<Sample> s = new ArrayList<>();
    for (Sample x : newestFirst) {
      if (x.feedTimeMs() < nowMs - WINDOW_MS) continue;
      if (s.size() >= MAX_PERIODS) break;
      s.add(x);
    }
    if (s.isEmpty()) {
      String reason = deviceKnown ? "80 分鐘內沒有這支 VD 的資料" : "VD 資料中沒有這支 VD（請確認代碼）";
      return new Result(State.ERROR, false, false, true, null, reason, 0, 0, null, null);
    }
    Sample latest = s.get(0);
    String reason = null;
    if (latest.feedTimeMs() < nowMs - STALE_MS) {
      reason = "最新資料超過 17 分鐘沒有更新";
    } else if (!latest.valid()) {
      reason = latest.lanes() == 0 ? "最新一期沒有車道資料" : "最新一期有無效車道（-2 或缺值）";
    } else if (s.size() >= 3 && s.subList(0, 3).stream().noneMatch(x -> x.valid() && x.total() > 0)) {
      reason = "最新三期都沒有有效流量";
    }

    List<Sample> valid = s.stream().filter(Sample::valid).toList();
    Integer judge = null;
    boolean volume = false;
    if (!valid.isEmpty()) {
      long sum = valid.stream().mapToLong(Sample::total).sum();
      judge = (int) (sum * 12 / valid.size());
      volume = sum * 12.0 / valid.size() > volLimit;
    }
    boolean continuous = false;
    for (int i = 0; i + 1 < s.size(); i++) {
      Sample a = s.get(i);
      Sample b = s.get(i + 1);
      if (a.valid() && b.valid() && a.total() > volContinuous && b.total() > volContinuous) {
        continuous = true;
        break;
      }
    }
    int invalid = s.size() - valid.size();
    boolean error = reason != null;
    State state = error ? State.ERROR : volume ? State.VOLUME : continuous ? State.CONTINUOUS : State.OK;
    if (!error && invalid > 0) reason = "近 " + s.size() + " 期中有 " + invalid + " 期有無效車道，已排除不計";
    return new Result(state, volume, continuous, error, judge, reason, s.size(), invalid, latest.feedTimeMs(),
        latest.feedTimeRaw());
  }
}

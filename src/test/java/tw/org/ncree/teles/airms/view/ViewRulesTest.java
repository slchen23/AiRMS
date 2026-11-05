package tw.org.ncree.teles.airms.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tw.org.ncree.teles.airms.view.ScreenlineEvaluator.Result;
import tw.org.ncree.teles.airms.view.ScreenlineEvaluator.Sample;
import tw.org.ncree.teles.airms.view.ScreenlineEvaluator.State;
import tw.org.ncree.teles.airms.view.TrafficRules.SpeedClass;

/** 監視畫面規則：速度分級、RMS 文字、屏柵線告警、路網設定檔。 */
class ViewRulesTest {
  private static final long NOW = 1_793_670_000_000L;
  private static final long MIN = 60_000L;

  @Test
  void speedClassesFollowOldThresholds() {
    assertEquals(SpeedClass.NO_DATA, TrafficRules.classify(0, 0));     // ≤20 且流量 0
    assertEquals(SpeedClass.NO_DATA, TrafficRules.classify(15, 0));
    assertEquals(SpeedClass.C20, TrafficRules.classify(20, 5));
    assertEquals(SpeedClass.C20, TrafficRules.classify(0, 3));
    assertEquals(SpeedClass.C40, TrafficRules.classify(21, 0));
    assertEquals(SpeedClass.C40, TrafficRules.classify(40, 100));
    assertEquals(SpeedClass.C60, TrafficRules.classify(60, 100));
    assertEquals(SpeedClass.C80, TrafficRules.classify(80, 100));
    assertEquals(SpeedClass.FREE, TrafficRules.classify(81, 100));
    assertEquals(SpeedClass.NO_DATA, TrafficRules.classify(null, 100)); // 新：缺值
    assertEquals(SpeedClass.NO_DATA, TrafficRules.classify(-99, 100));  // 新：負數代碼
    assertEquals(List.of("資料不足", "0～20", "20～40", "40～60", "60～80", "80 以上"),
        TrafficRules.SpeedSteps.DEFAULT.labels());
  }

  @Test
  void speedClassesFollowConfiguredThresholds() {
    TrafficRules.SpeedSteps s = new TrafficRules.SpeedSteps(30, 50, 70, 90, false);
    assertEquals(SpeedClass.C20, TrafficRules.classify(0, 0, s));       // 低速規則關閉
    assertEquals(SpeedClass.C20, TrafficRules.classify(30, 5, s));
    assertEquals(SpeedClass.C40, TrafficRules.classify(31, 5, s));
    assertEquals(SpeedClass.C60, TrafficRules.classify(70, 5, s));
    assertEquals(SpeedClass.C80, TrafficRules.classify(90, 5, s));
    assertEquals(SpeedClass.FREE, TrafficRules.classify(91, 5, s));
    assertEquals(SpeedClass.NO_DATA, TrafficRules.classify(null, 5, s));
    assertEquals("30～50", s.legendJson().getJsonObject(2).getString("label"));
    assertEquals("90 以上", s.legendJson().getJsonObject(5).getString("label"));
    assertThrows(IllegalArgumentException.class, () -> new TrafficRules.SpeedSteps(30, 30, 70, 90, true));
  }

  @Test
  void rmsDisplayFollowsOldRule() {
    assertEquals("30", TrafficRules.rmsDisplay("閃黃", "0"));
    assertEquals("紅", TrafficRules.rmsDisplay("紅燈", "0"));
    assertEquals("綠", TrafficRules.rmsDisplay("綠", "0"));
    assertEquals("12", TrafficRules.rmsDisplay("12", "0"));
    assertEquals("X", TrafficRules.rmsDisplay("閃黃", "1"));
    assertEquals("X", TrafficRules.rmsDisplay("12", null));
    assertEquals("", TrafficRules.rmsDisplay(null, "0"));
  }

  /** 由新到舊、每 5 分鐘一期；totals 為 null 表示該期有無效車道。 */
  private static List<Sample> periods(long newestMs, Integer... totals) {
    List<Sample> out = new ArrayList<>();
    for (int i = 0; i < totals.length; i++) {
      long t = newestMs - i * 5 * MIN;
      Integer v = totals[i];
      out.add(v == null ? new Sample(t, "t" + i, 3, 1, 100) : new Sample(t, "t" + i, 3, 0, v));
    }
    return out;
  }

  @Test
  void screenlineVolumeAndContinuous() {
    // 平均 400 × 12 = 4800 > 4200 → 紅鈴
    Result r = ScreenlineEvaluator.evaluate(periods(NOW - 2 * MIN, 400, 400, 400, 400), NOW, 4200, 450, true);
    assertEquals(State.VOLUME, r.state());
    assertEquals(Integer.valueOf(4800), r.judge());
    assertFalse(r.continuous());
    // 平均沒超過，但相鄰兩期都 > 350 → 橘鈴
    r = ScreenlineEvaluator.evaluate(periods(NOW - 2 * MIN, 360, 351, 100, 100, 100, 100), NOW, 4200, 350, true);
    assertEquals(State.CONTINUOUS, r.state());
    assertTrue(r.continuous());
    // 正常
    r = ScreenlineEvaluator.evaluate(periods(NOW - 2 * MIN, 100, 200, 100), NOW, 4200, 350, true);
    assertEquals(State.OK, r.state());
    assertNull(r.reason());
    // 只取最新 12 期
    Integer[] many = new Integer[16];
    java.util.Arrays.fill(many, 100);
    many[13] = 9999;
    many[14] = 9999;
    r = ScreenlineEvaluator.evaluate(periods(NOW - MIN, many), NOW, 4200, 350, true);
    assertEquals(State.OK, r.state());
  }

  @Test
  void invalidLanePeriodsAreExcludedNotSummed() {
    // 第 2 期有無效車道：不加進總量（平均只算有效期），也不能和前後構成「連續兩期」
    Result r = ScreenlineEvaluator.evaluate(periods(NOW - MIN, 360, null, 360, 100), NOW, 4200, 350, true);
    assertEquals(State.OK, r.state());
    assertEquals(1, r.invalidPeriods());
    assertEquals(Integer.valueOf((360 + 360 + 100) * 12 / 3), r.judge());
    assertTrue(r.reason().contains("無效車道"));
    // 最新一期就無效 → 資料異常
    r = ScreenlineEvaluator.evaluate(periods(NOW - MIN, null, 100, 100), NOW, 4200, 350, true);
    assertEquals(State.ERROR, r.state());
    assertTrue(r.reason().contains("無效車道"));
  }

  @Test
  void screenlineErrors() {
    Result r = ScreenlineEvaluator.evaluate(List.of(), NOW, 4200, 350, false);
    assertEquals(State.ERROR, r.state());
    assertTrue(r.reason().contains("沒有這支 VD"));
    r = ScreenlineEvaluator.evaluate(List.of(), NOW, 4200, 350, true);
    assertTrue(r.reason().contains("80 分鐘"));
    // 最新一期超過 17 分鐘
    r = ScreenlineEvaluator.evaluate(periods(NOW - 18 * MIN, 100, 100), NOW, 4200, 350, true);
    assertEquals(State.ERROR, r.state());
    assertTrue(r.reason().contains("17 分鐘"));
    // 超過 80 分鐘的資料不算
    r = ScreenlineEvaluator.evaluate(periods(NOW - 81 * MIN, 100), NOW, 4200, 350, true);
    assertEquals(0, r.periods());
    // 最新三期都是 0
    r = ScreenlineEvaluator.evaluate(periods(NOW - MIN, 0, 0, 0, 500), NOW, 4200, 350, true);
    assertEquals(State.ERROR, r.state());
    // 異常時仍保留計算結果（紅鈴條件成立也顯示異常）
    r = ScreenlineEvaluator.evaluate(periods(NOW - 20 * MIN, 900, 900), NOW, 4200, 350, true);
    assertEquals(State.ERROR, r.state());
    assertTrue(r.volume());
  }

  @Test
  void builtInNetworkConfigIsValid() throws Exception {
    NetworkConfig n = NetworkConfig.load(null);
    assertEquals(100, n.rmsIds().size());
    assertEquals(8, n.screenlines().size());
    assertEquals(58, n.nodeCount());
    assertTrue(n.rmsIds().contains(NetworkConfig.normId("RMS-N3-S-212-I-NS-22-霧峰-環道-國6專用道")));
    // 來源用相容字「北」(U+F963) 也對得上
    assertTrue(n.rmsIds().contains(NetworkConfig.normId("RMS-N1-N-110-I-SN-1-北上入口-頭份")));
    NetworkConfig.ScreenlineDef sl = n.screenlines().get(0);
    assertEquals("n1-99-s", sl.id());
    assertEquals(5400, sl.volLimit());
  }

  @Test
  void everyRampHasKeyAndDefaults() throws Exception {
    NetworkConfig n = NetworkConfig.load(null);
    assertEquals(104, n.ramps().size());
    NetworkConfig.RampDef daya = n.ramps().stream().filter(r -> r.key().equals("n1-174200-n")).findFirst().orElseThrow();
    assertFalse(daya.visible());                       // 大雅北上預設隱藏（同舊 view.html）
    assertEquals("國1北上 大雅 174K", daya.label());
    NetworkConfig.RampDef wufeng = n.ramps().stream().filter(r -> r.key().equals("n3-211300-s")).findFirst().orElseThrow();
    assertEquals(3, wufeng.rms().size());
    assertEquals(1, n.ramps().stream().filter(r -> !r.visible()).count());
  }

  @Test
  void invalidNetworkConfigListsErrors() throws Exception {
    String good = new String(NetworkConfig.class.getClassLoader().getResourceAsStream(NetworkConfig.BUILT_IN)
        .readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    io.vertx.core.json.JsonObject j = new io.vertx.core.json.JsonObject(good);
    // RMS 重複、屏柵線門檻錯、路線里程倒退
    j.getJsonArray("nodes").getJsonObject(2).getJsonObject("ramps").getJsonObject("N").put("kind", "rms")
        .put("rms", new io.vertx.core.json.JsonArray().add("RMS-N1-S-110-I-NS-1-南下入口-頭份"));
    j.getJsonArray("screenlines").getJsonObject(0).put("volLimit", 0);
    j.getJsonArray("freeways").getJsonObject(0).getJsonObject("routes").getJsonObject("S").getJsonArray("points")
        .getJsonArray(3).set(2, 1);
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> NetworkConfig.parse(j.encode(), "test"));
    assertTrue(e.getMessage().contains("RMS 代碼重複"));
    assertTrue(e.getMessage().contains("門檻"));
    assertTrue(e.getMessage().contains("遞增"));
    assertThrows(IllegalArgumentException.class, () -> NetworkConfig.parse("{oops", "test"));
  }
}

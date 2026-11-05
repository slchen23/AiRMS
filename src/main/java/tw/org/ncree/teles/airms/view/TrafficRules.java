package tw.org.ncree.teles.airms.view;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.List;

/** 監視畫面的判斷規則（沿用舊系統，集中在這裡方便對照與測試）。 */
public final class TrafficRules {
  private TrafficRules() { }

  /**
   * 路段速度分級（全線統一）。代碼沿用預設門檻的名稱：C20 為第 1 級（最慢）…FREE 為最快；
   * 門檻可在系統設定「路段速度顏色」調整，顏色固定（路網設定檔 speedClasses）。
   */
  public enum SpeedClass { NO_DATA, C20, C40, C60, C80, FREE }

  /**
   * 分級門檻（公里/小時，由小到大）與「低速且流量 0 視為資料不足」。
   * ≤t1 第 1 級、≤t2 第 2 級、≤t3 第 3 級、≤t4 第 4 級，其餘最快一級。
   */
  public record SpeedSteps(int t1, int t2, int t3, int t4, boolean lowSpeedNoVolumeAsNoData) {
    public static final SpeedSteps DEFAULT = new SpeedSteps(20, 40, 60, 80, true);

    public SpeedSteps {
      if (!(0 < t1 && t1 < t2 && t2 < t3 && t3 < t4)) throw new IllegalArgumentException("門檻必須由小到大且大於 0");
    }

    public static SpeedSteps of(List<Integer> t, boolean lowSpeedNoVolumeAsNoData) {
      if (t.size() != 4) throw new IllegalArgumentException("門檻必須是 4 個數字");
      return new SpeedSteps(t.get(0), t.get(1), t.get(2), t.get(3), lowSpeedNoVolumeAsNoData);
    }

    /** 圖例文字，依 SpeedClass 順序。 */
    public List<String> labels() {
      return List.of("資料不足", "0～" + t1, t1 + "～" + t2, t2 + "～" + t3, t3 + "～" + t4, t4 + " 以上");
    }

    /** 給畫面的圖例：[{key, label}]（顏色在路網設定檔）。 */
    public JsonArray legendJson() {
      JsonArray out = new JsonArray();
      List<String> labels = labels();
      SpeedClass[] all = SpeedClass.values();
      for (int i = 0; i < all.length; i++) out.add(new JsonObject().put("key", all[i].name()).put("label", labels.get(i)));
      return out;
    }
  }

  /** 用預設門檻（同舊系統）。 */
  public static SpeedClass classify(Integer speed, Integer volume) {
    return classify(speed, volume, SpeedSteps.DEFAULT);
  }

  /**
   * 舊系統：速度 ≤20 且總流量為 0 視為資料不足（白）；≤20 紫、≤40 紅、≤60 橘、≤80 黃、其餘綠。
   * 新增：沒有速度或速度為負數（來源的缺值代碼）也視為資料不足，不再畫成紫色；門檻與低速規則可調整。
   */
  public static SpeedClass classify(Integer speed, Integer volume, SpeedSteps s) {
    if (speed == null || speed < 0) return SpeedClass.NO_DATA;
    if (speed <= s.t1()) {
      return s.lowSpeedNoVolumeAsNoData() && volume != null && volume == 0 ? SpeedClass.NO_DATA : SpeedClass.C20;
    }
    if (speed <= s.t2()) return SpeedClass.C40;
    if (speed <= s.t3()) return SpeedClass.C60;
    if (speed <= s.t4()) return SpeedClass.C80;
    return SpeedClass.FREE;
  }

  /**
   * RMS 圓圈上的文字（同舊系統）：通訊狀態不是 0 顯示 X；
   * 儀控率含「黃」顯示 30、含「紅」顯示紅、含「綠」顯示綠，其餘照原值。
   */
  public static String rmsDisplay(String rate, String commStatus) {
    if (!"0".equals(commStatus)) return "X";
    if (rate == null) return "";
    String r = rate.trim();
    if (r.contains("黃")) return "30";
    if (r.contains("紅")) return "紅";
    if (r.contains("綠")) return "綠";
    return r;
  }
}

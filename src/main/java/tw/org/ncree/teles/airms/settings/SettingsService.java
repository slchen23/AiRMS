package tw.org.ncree.teles.airms.settings;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import tw.org.ncree.teles.airms.config.AppConfig;
import tw.org.ncree.teles.airms.db.Db;
import tw.org.ncree.teles.airms.db.Series;
import tw.org.ncree.teles.airms.feed.FeedKind;
import tw.org.ncree.teles.airms.view.NetworkConfig;
import tw.org.ncree.teles.airms.view.TrafficRules;

/**
 * 系統設定頁可以調整的項目。預設值來自 config.json；在網頁上改過的值存在 core.db 的 app_setting，
 * 刪掉即還原預設。所有項目都「下一次使用時生效」，不需重啟（抓取在下一輪、清除在下一次執行、登入規則立即）。
 * port、憑證、資料目錄、備份開關與備份位置等部署設定不在這裡，只能改 config.json。
 */
public final class SettingsService {

  /** SPEED_STEPS：路段速度分級的 4 個門檻，存成「20, 40, 60, 80」這樣的文字。 */
  public enum Type { BOOL, INT, URL, VD_ID, SPEED_STEPS, HOST }

  public static final String TLS_EXPIRY_WARNING_DAYS = "tls.expiryWarningDays";
  public static final String RAW_XML_DAYS = "retention.rawXmlDays";
  public static final String LOG_DAYS = "retention.logDays";
  public static final String SPEED_THRESHOLDS = "speed.thresholds";
  public static final String SPEED_LOW_NO_VOLUME = "speed.lowSpeedNoVolumeAsNoData";
  public static final String SPEED_STALE_MINUTES = "speed.staleMinutes";
  public static final String AIRMS_HOST = "airms.host";
  public static final String AIRMS_PORT = "airms.port";
  private static final int SPEED_MAX = 200;

  public record Def(String key, String group, String label, Type type, int min, int max, Object defaultValue,
                    String description) { }

  public static final class InvalidSettingException extends Exception {
    private static final long serialVersionUID = 1L;

    public InvalidSettingException(String message) {
      super(message);
    }
  }

  private final Db db;
  private final Map<String, Def> defs = new LinkedHashMap<>();
  private final Map<String, Object> overrides = new ConcurrentHashMap<>();

  public SettingsService(Db coreDb, AppConfig config) {
    this(coreDb, config, null);
  }

  /**
   * network：路網設定檔。屏柵線的 VD 與門檻以設定檔為預設值，在系統設定頁可個別調整；null 表示沒有（測試用）。
   * 匝道圈的顯示與 RMS 代碼不在這裡，在「路網匝道」管理頁（RampService）。
   */
  public SettingsService(Db coreDb, AppConfig config, NetworkConfig network) {
    this.db = coreDb;
    List<NetworkConfig.ScreenlineDef> screenlines = network == null ? List.of() : network.screenlines();
    for (FeedKind k : FeedKind.values()) {
      AppConfig.Feed f = config.feed(k);
      String g = "資料來源：" + k.label();
      add(new Def(feedKey(k, "enabled"), g, "啟用抓取", Type.BOOL, 0, 1, f.enabled(), "關閉後停止抓取這個來源"));
      add(new Def(feedKey(k, "url"), g, "XML 網址", Type.URL, 0, 0, f.url() == null ? "" : f.url().toString(),
          "正式機改成內網來源網址；下一輪抓取生效"));
      add(new Def(feedKey(k, "intervalSeconds"), g, "抓取間隔（秒）", Type.INT, 10, 3600, f.intervalSeconds(),
          "每一輪完成後才排下一輪"));
    }
    AppConfig.Retention r = config.retention();
    String rg = "資料保留";
    String backupNote = config.backup().enabled()
        ? "已啟用備份：尚未備份到外接硬碟的資料不會刪除。"
        : "未啟用備份（config.json 的 backup.enabled）：到期就直接刪除，測試機若放入舊資料請設為 0。";
    add(new Def(RAW_XML_DAYS, rg, "原始 XML 保留天數", Type.INT, 0, 36500, r.rawXmlDays(),
        "原始 XML 在資料目錄 cache/xml_raw 保留最近幾天（含今天），超過的日期資料夾會刪除；0 表示不刪除。" + backupNote));
    add(new Def(Series.VD.retentionSetting(), rg, "VD 保留月數", Type.INT, 0, 1200, r.vdMonths(),
        "VD 月檔在本月以外再保留幾個月；例如 12 表示 11 月時保留去年 11 月到本月；0 表示不刪除。" + backupNote));
    add(new Def(Series.SECTION.retentionSetting(), rg, "路段保留月數", Type.INT, 0, 1200, r.sectionMonths(),
        "路段歷史月檔在本月以外再保留幾個月（25 個月約 760 天）；0 表示不刪除"));
    add(new Def(Series.RMS.retentionSetting(), rg, "RMS 保留月數", Type.INT, 0, 1200, r.rmsMonths(),
        "RMS 歷史月檔在本月以外再保留幾個月；0 表示不刪除"));
    add(new Def(Series.FEEDLOG.retentionSetting(), rg, "抓取紀錄保留月數", Type.INT, 0, 1200, r.feedLogMonths(),
        "每次抓取的結果與原始檔索引，在本月以外再保留幾個月；0 表示不刪除"));
    add(new Def(LOG_DAYS, rg, "程式 log 保留天數", Type.INT, 0, 36500, r.logDays(),
        "程式工作目錄 logs 資料夾的每日 log 檔（airms-日期.log）保留最近幾天（含今天），超過的刪除；0 表示不刪除。"
            + "log 檔不備份到外接硬碟，到期就刪除"));
    add(new Def("auth.sessionIdleMinutes", "登入安全", "閒置自動登出（分鐘）", Type.INT, 5, 720, 30,
        "超過這段時間沒有操作就需要重新登入"));
    add(new Def("auth.maxFailedLogins", "登入安全", "密碼錯誤幾次後冷卻", Type.INT, 3, 20, 5,
        "同一帳號在「錯誤次數計算期間」內密碼錯誤達到這個次數，就暫停該帳號登入一段時間（冷卻）；不依來源 IP 鎖定"));
    add(new Def("auth.failureWindowSeconds", "登入安全", "錯誤次數計算期間（秒）", Type.INT, 30, 3600, 300,
        "只計算最近這段時間內的密碼錯誤"));
    add(new Def("auth.cooldownSeconds", "登入安全", "冷卻時間（秒）", Type.INT, 10, 86400, 60,
        "冷卻期間再嘗試登入不會延長冷卻；管理者也可以在使用者管理頁手動解除"));
    add(new Def("view.requireLogin", "即時監視", "首頁與即時監視需要登入", Type.BOOL, 0, 1, true,
        "開啟（預設）時，首頁需要登入，即時監視需要登入且有「監視」或「AiRMS 控制」權限；"
            + "關閉則兩者都不需登入，只適合放在內網、沒有對外開放時"));
    add(new Def(TLS_EXPIRY_WARNING_DAYS, "HTTPS 憑證", "到期前幾天開始提醒", Type.INT, 7, 3650, 90,
        "憑證剩下的天數少於這個值時，首頁顯示提醒、log 每天記一次警告。憑證本身的有效期限在產生時決定"
            + "（tools/tls/new-cert 預設 10 年）"));
    String sg = "路段速度顏色";
    add(new Def(SPEED_THRESHOLDS, sg, "速度分級門檻（公里/小時）", Type.SPEED_STEPS, 1, SPEED_MAX, "20, 40, 60, 80",
        "4 個數字由小到大、以逗號分隔：≤第 1 個紫、≤第 2 個紅、≤第 3 個橘、≤第 4 個黃，其餘綠。全線統一，顏色固定，圖例自動跟著改"));
    add(new Def(SPEED_LOW_NO_VOLUME, sg, "低速且流量 0 視為資料不足", Type.BOOL, 0, 1, true,
        "速度 ≤ 第 1 個門檻且流量為 0 時畫白色（同舊系統）；關閉則畫紫色"));
    add(new Def(SPEED_STALE_MINUTES, sg, "路段資料過時（分鐘）", Type.INT, 1, 120, 10,
        "某路段比最新資料舊超過這個時間就畫白色；整個路段來源超過這個時間沒更新，畫面上的資料時間顯示紅字"));
    AppConfig.AirmsDevice dev = config.airmsDevice();
    add(new Def(AIRMS_HOST, "AiRMS 設備", "設備 IP 或主機名稱", Type.HOST, 0, 0, dev.host(),
        "「AiRMS 控制」頁讀取與設定敏感性、開關時連線的 AiRMS 設備；空白表示尚未設定。下一次讀取或送出生效"));
    add(new Def(AIRMS_PORT, "AiRMS 設備", "設備 TCP port", Type.INT, 1, 65535, dev.port(),
        "AiRMS 設備接收指令的 TCP port（舊系統為 9876）"));
    for (NetworkConfig.ScreenlineDef sl : screenlines) {
      String g = "屏柵線：" + sl.label();
      add(new Def(screenlineKey(sl.id(), "vdId"), g, "VD 代碼", Type.VD_ID, 0, 0, sl.vdId(),
          "計算流量用的主線 VD；VD 資料中找不到時鈴會顯示資料異常"));
      add(new Def(screenlineKey(sl.id(), "volLimit"), g, "小時流量門檻（輛）", Type.INT, 1, 100000, sl.volLimit(),
          "最近 12 期平均 × 12 超過就響紅鈴"));
      add(new Def(screenlineKey(sl.id(), "volContinuous"), g, "單期流量門檻（輛／5 分鐘）", Type.INT, 1, 20000,
          sl.volContinuous(), "連續兩期都超過就響橘鈴"));
    }
  }

  public static String screenlineKey(String id, String field) {
    return "screenlines." + id + "." + field;
  }

  private void add(Def d) {
    defs.put(d.key(), d);
  }

  public static String feedKey(FeedKind k, String field) {
    return "feeds." + k.key() + "." + field;
  }

  /** 啟動時載入已儲存的值；不認得或已不合法的值略過（沿用預設），不讓啟動失敗。 */
  public Future<Void> load() {
    return db.run(c -> {
      try (Statement st = c.createStatement();
           ResultSet rs = st.executeQuery("SELECT setting_key, value_json FROM app_setting")) {
        while (rs.next()) {
          Def d = defs.get(rs.getString(1));
          if (d == null) continue;
          try {
            Object v = new JsonObject(rs.getString(2)).getValue("v");
            overrides.put(d.key(), validate(d, v));
          } catch (InvalidSettingException | RuntimeException ignored) {
            // 保留預設值
          }
        }
      }
      return null;
    });
  }

  public boolean feedEnabled(FeedKind k) {
    return (Boolean) get(feedKey(k, "enabled"));
  }

  public URI feedUrl(FeedKind k) {
    String s = (String) get(feedKey(k, "url"));
    return s.isEmpty() ? null : URI.create(s);
  }

  public int feedIntervalSeconds(FeedKind k) {
    return (Integer) get(feedKey(k, "intervalSeconds"));
  }

  public int intValue(String key) {
    return (Integer) get(key);
  }

  public boolean boolValue(String key) {
    return (Boolean) get(key);
  }

  public String stringValue(String key) {
    return (String) get(key);
  }

  /** 路段速度分級（門檻＋低速規則）。 */
  public TrafficRules.SpeedSteps speedSteps() {
    try {
      return TrafficRules.SpeedSteps.of(parseSteps(stringValue(SPEED_THRESHOLDS)), boolValue(SPEED_LOW_NO_VOLUME));
    } catch (InvalidSettingException | IllegalArgumentException e) {
      return TrafficRules.SpeedSteps.DEFAULT;   // 存進來時已檢查過，不會發生
    }
  }

  public long sectionStaleMs() {
    return intValue(SPEED_STALE_MINUTES) * 60_000L;
  }

  public Object get(String key) {
    Def d = defs.get(key);
    if (d == null) throw new IllegalArgumentException("未定義的設定：" + key);
    return overrides.getOrDefault(key, d.defaultValue());
  }

  /** 修改；回傳舊值。值與預設相同時直接刪除覆寫。 */
  public Future<Object> update(String key, Object rawValue, String actor, long nowMs) {
    Def d = defs.get(key);
    if (d == null) return Future.failedFuture(new InvalidSettingException("未定義的設定：" + key));
    Object value;
    try {
      value = validate(d, rawValue);
      if (d.type() == Type.URL && "".equals(value) && feedEnabledFor(key)) {
        throw new InvalidSettingException("啟用中的來源必須有網址");
      }
      if (d.type() == Type.BOOL && Boolean.TRUE.equals(value) && key.endsWith(".enabled")
          && ((String) get(key.replace(".enabled", ".url"))).isEmpty()) {
        throw new InvalidSettingException("請先設定網址再啟用");
      }
    } catch (InvalidSettingException e) {
      return Future.failedFuture(e);
    }
    Object old = get(key);
    boolean isDefault = value.equals(d.defaultValue());
    return db.run(c -> {
      if (isDefault) delete(c, key);
      else upsert(c, key, value, actor, nowMs);
      return null;
    }).map(v -> {
      if (isDefault) overrides.remove(key);
      else overrides.put(key, value);
      return old;
    });
  }

  private boolean feedEnabledFor(String urlKey) {
    return urlKey.endsWith(".url") && Boolean.TRUE.equals(get(urlKey.replace(".url", ".enabled")));
  }

  public Future<Object> reset(String key, String actor, long nowMs) {
    Def d = defs.get(key);
    if (d == null) return Future.failedFuture(new InvalidSettingException("未定義的設定：" + key));
    return update(key, d.defaultValue(), actor, nowMs);
  }

  public JsonArray toJson() {
    JsonArray out = new JsonArray();
    for (Def d : defs.values()) {
      out.add(new JsonObject().put("key", d.key()).put("group", d.group()).put("label", d.label())
          .put("type", d.type().name()).put("min", d.min()).put("max", d.max())
          .put("value", get(d.key())).put("defaultValue", d.defaultValue())
          .put("overridden", overrides.containsKey(d.key())).put("description", d.description()));
    }
    return out;
  }

  public List<String> keys() {
    return new ArrayList<>(defs.keySet());
  }

  static Object validate(Def d, Object v) throws InvalidSettingException {
    switch (d.type()) {
      case BOOL -> {
        if (v instanceof Boolean b) return b;
        throw new InvalidSettingException(d.label() + " 必須是是/否");
      }
      case INT -> {
        if (!(v instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue())) {
          throw new InvalidSettingException(d.label() + " 必須是整數");
        }
        long x = n.longValue();
        if (x < d.min() || x > d.max()) {
          throw new InvalidSettingException(d.label() + " 必須介於 " + d.min() + "～" + d.max());
        }
        return (int) x;
      }
      case URL -> {
        if (!(v instanceof String s)) throw new InvalidSettingException(d.label() + " 必須是文字");
        String t = s.trim();
        if (t.isEmpty()) return "";
        if (t.length() > 500) throw new InvalidSettingException(d.label() + " 太長");
        try {
          URI u = URI.create(t);
          String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
          if (!(scheme.equals("http") || scheme.equals("https")) || u.getHost() == null || u.getRawUserInfo() != null) {
            throw new InvalidSettingException(d.label() + " 必須是 http(s) 網址，且不可含帳密");
          }
        } catch (IllegalArgumentException e) {
          throw new InvalidSettingException(d.label() + " 格式錯誤");
        }
        return t;
      }
      case HOST -> {
        if (!(v instanceof String s)) throw new InvalidSettingException(d.label() + " 必須是文字");
        String t = s.trim();
        if (t.isEmpty()) return "";
        if (!tw.org.ncree.teles.airms.control.AirmsProtocol.validHost(t)) {
          throw new InvalidSettingException(d.label() + " 必須是 IP（例如 192.168.1.10）或主機名稱，不含 port 與 http://");
        }
        return t;
      }
      case VD_ID -> {
        if (!(v instanceof String s)) throw new InvalidSettingException(d.label() + " 必須是文字");
        String t = s.trim();
        if (!NetworkConfig.validVdId(t)) {
          throw new InvalidSettingException(d.label() + " 必須是 VD- 開頭的設備代碼，不可含空白");
        }
        return t;
      }
      case SPEED_STEPS -> {
        List<Integer> t;
        if (v instanceof String s) {
          t = parseSteps(s);
        } else if (v instanceof JsonArray arr) {
          t = new ArrayList<>();
          for (Object o : arr) {
            if (!(o instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue())) {
              throw new InvalidSettingException(d.label() + " 必須是整數");
            }
            t.add(n.intValue());
          }
        } else {
          throw new InvalidSettingException(d.label() + " 必須是 4 個以逗號分隔的數字");
        }
        if (t.size() != 4) throw new InvalidSettingException(d.label() + " 必須剛好 4 個數字，例如 20, 40, 60, 80");
        for (int i = 0; i < 4; i++) {
          if (t.get(i) < d.min() || t.get(i) > d.max()) {
            throw new InvalidSettingException(d.label() + " 每個數字必須介於 " + d.min() + "～" + d.max());
          }
          if (i > 0 && t.get(i) <= t.get(i - 1)) throw new InvalidSettingException(d.label() + " 必須由小到大");
        }
        return t.get(0) + ", " + t.get(1) + ", " + t.get(2) + ", " + t.get(3);
      }
      default -> throw new InvalidSettingException("未知的設定型別");
    }
  }

  /** 「20, 40、60 80」→ [20, 40, 60, 80]；逗號、頓號、空白都可分隔。 */
  static List<Integer> parseSteps(String s) throws InvalidSettingException {
    List<Integer> out = new ArrayList<>();
    for (String part : s.trim().split("[,，、\\s]+")) {
      if (part.isEmpty()) continue;
      if (!part.matches("\\d{1,4}")) throw new InvalidSettingException("速度分級門檻 必須是整數：" + part);
      out.add(Integer.parseInt(part));
    }
    return out;
  }

  private static void upsert(Connection c, String key, Object value, String actor, long nowMs) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement("""
        INSERT INTO app_setting(setting_key, value_json, updated_at_ms, updated_by) VALUES (?,?,?,?)
        ON CONFLICT(setting_key) DO UPDATE SET value_json=excluded.value_json,
          updated_at_ms=excluded.updated_at_ms, updated_by=excluded.updated_by""")) {
      ps.setString(1, key);
      ps.setString(2, new JsonObject().put("v", value).encode());
      ps.setLong(3, nowMs);
      ps.setString(4, actor);
      ps.executeUpdate();
    }
  }

  private static void delete(Connection c, String key) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement("DELETE FROM app_setting WHERE setting_key=?")) {
      ps.setString(1, key);
      ps.executeUpdate();
    }
  }
}

package tw.org.ncree.teles.airms.view;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import tw.org.ncree.teles.airms.db.Db;
import tw.org.ncree.teles.airms.db.MonthlyStore;
import tw.org.ncree.teles.airms.settings.SettingsService;

/**
 * 即時監視資料：路段速度分級、RMS 顯示文字、屏柵線告警。定時（LiveViewVerticle）整理一次放在記憶體，
 * 網頁請求直接拿這份，不論幾個畫面同時開都不會增加資料庫負擔。
 * 路段與 RMS 讀記憶體資料庫的最新一筆；屏柵線讀 VD 月檔近 80 分鐘（跨月時同時讀上個月的檔）。
 */
public final class LiveViewService {
  private static final Logger log = LogManager.getLogger(LiveViewService.class);

  /**
   * 路段過時的預設：個別路段比最新一筆舊這麼久就畫資料不足（該路段已不在來源中）；整個來源這麼久沒更新就標紅。
   * 實際值在系統設定「路段速度顏色 → 路段資料過時」。
   */
  static final long DEFAULT_SECTION_STALE_MS = 10 * 60_000L;
  /** RMS 1 分鐘一筆，比最新一筆舊 5 分鐘以上就不採用；整個來源 5 分鐘沒更新就標紅。 */
  static final long RMS_ITEM_STALE_MS = 5 * 60_000L;
  static final long RMS_STALE_MS = 5 * 60_000L;

  private final Db liveDb;
  private final MonthlyStore vd;
  private final SettingsService settings;
  private final NetworkConfig network;
  private final LongSupplier clock;
  private volatile JsonObject current;
  private Future<JsonObject> inFlight;
  private long lastFailureLogMs;

  public LiveViewService(Db liveDb, MonthlyStore vd, SettingsService settings, NetworkConfig network,
                         LongSupplier clock) {
    this.liveDb = liveDb;
    this.vd = vd;
    this.settings = settings;
    this.network = network;
    this.clock = clock;
  }

  /** 最近一次整理好的資料；還沒有時立即整理一次。 */
  public Future<JsonObject> current() {
    JsonObject c = current;
    return c != null ? Future.succeededFuture(c) : refresh();
  }

  /** 重新整理（同時間只會有一次在跑）。失敗時保留上一份資料。 */
  public synchronized Future<JsonObject> refresh() {
    if (inFlight != null) return inFlight;
    long now = clock.getAsLong();
    List<ScreenlineQuery> queries = new ArrayList<>();
    for (NetworkConfig.ScreenlineDef d : network.screenlines()) {
      queries.add(new ScreenlineQuery(d,
          settings.stringValue(SettingsService.screenlineKey(d.id(), "vdId")),
          settings.intValue(SettingsService.screenlineKey(d.id(), "volLimit")),
          settings.intValue(SettingsService.screenlineKey(d.id(), "volContinuous"))));
    }
    TrafficRules.SpeedSteps steps = settings.speedSteps();
    long sectionStaleMs = settings.sectionStaleMs();
    Future<JsonObject> airms = liveDb.run(c -> new JsonObject()
        .put("sections", sections(c, now, steps, sectionStaleMs))
        .put("speedClasses", steps.legendJson())
        .put("rms", rms(c, now)));
    Future<JsonObject> lines = screenlines(vd, queries, now);
    Future<JsonObject> f = Future.all(airms, lines).map(cf -> {
      JsonObject out = airms.result().put("screenlines", lines.result()).put("generatedAtMs", now);
      current = out;
      return out;
    }).onFailure(e -> {
      if (now - lastFailureLogMs > 10 * 60_000L) {
        lastFailureLogMs = now;
        log.warn("整理即時監視資料失敗：{}", e.getMessage());
      }
    });
    inFlight = f;
    f.onComplete(ar -> {
      synchronized (this) {
        inFlight = null;
      }
    });
    return f;
  }

  // ---------------------------------------------------------------- 路段

  static JsonObject sections(Connection c, long now) throws SQLException {
    return sections(c, now, TrafficRules.SpeedSteps.DEFAULT, DEFAULT_SECTION_STALE_MS);
  }

  /**
   * 路段速度分級。舊的在前、新的在後：高公局重新切段時，已不在來源中的舊路段（畫資料不足）會被新路段蓋過。
   */
  static JsonObject sections(Connection c, long now, TrafficRules.SpeedSteps steps, long staleMs) throws SQLException {
    record Row(String fw, String dir, String fromName, int from, String toName, int to, Integer speed, Integer volume,
               String feedRaw, long feedMs) { }
    List<Row> rows = new ArrayList<>();
    try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("""
        SELECT freeway_id, direction_id, from_location, from_milepost, end_location, end_milepost,
               section_average_speed, section_total_car_volume, feed_time_raw, feed_time_ms
        FROM section_latest""")) {
      while (rs.next()) {
        Integer from = parseInt(rs.getString(4));
        Integer to = parseInt(rs.getString(6));
        if (from == null || to == null) continue;
        rows.add(new Row(rs.getString(1), rs.getString(2), rs.getString(3), from, rs.getString(5), to,
            intOrNull(rs, 7), intOrNull(rs, 8), rs.getString(9), rs.getLong(10)));
      }
    }
    rows.sort((a, b) -> Long.compare(a.feedMs(), b.feedMs()));
    Row newest = rows.isEmpty() ? null : rows.get(rows.size() - 1);
    JsonArray items = new JsonArray();
    for (Row r : rows) {
      boolean stale = r.feedMs() < newest.feedMs() - staleMs;
      TrafficRules.SpeedClass cls = stale ? TrafficRules.SpeedClass.NO_DATA
          : TrafficRules.classify(r.speed(), r.volume(), steps);
      items.add(new JsonObject().put("freewayId", r.fw()).put("directionId", r.dir())
          .put("from", r.from()).put("to", r.to()).put("fromName", r.fromName()).put("toName", r.toName())
          .put("speed", r.speed()).put("volume", r.volume()).put("cls", cls.name()).put("stale", stale)
          .put("feedTime", r.feedRaw()));
    }
    return block(newest == null ? null : newest.feedRaw(), newest == null ? null : newest.feedMs(), now,
        staleMs, items);
  }

  // ---------------------------------------------------------------- RMS

  static JsonObject rms(Connection c, long now) throws SQLException {
    record Row(String id, String status, String rate, String mode, String feedRaw, long feedMs) { }
    List<Row> rows = new ArrayList<>();
    try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("""
        SELECT eq_id, eq_comm_status, rms_rate, rms_control_mode, feed_time_raw, feed_time_ms FROM rms_latest""")) {
      while (rs.next()) {
        rows.add(new Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
            rs.getLong(6)));
      }
    }
    Row newest = rows.stream().max((a, b) -> Long.compare(a.feedMs(), b.feedMs())).orElse(null);
    JsonArray items = new JsonArray();
    for (Row r : rows) {
      boolean stale = r.feedMs() < newest.feedMs() - RMS_ITEM_STALE_MS;
      items.add(new JsonObject().put("id", NetworkConfig.normId(r.id())).put("status", r.status())
          .put("rate", r.rate()).put("mode", r.mode())
          .put("display", stale ? "" : TrafficRules.rmsDisplay(r.rate(), r.status())).put("stale", stale));
    }
    return block(newest == null ? null : newest.feedRaw(), newest == null ? null : newest.feedMs(), now,
        RMS_STALE_MS, items);
  }

  // ---------------------------------------------------------------- 屏柵線

  record ScreenlineQuery(NetworkConfig.ScreenlineDef def, String vdId, int volLimit, int volContinuous) { }

  /** 一個月檔裡某支 VD 的資料：是否有這支 VD、近 80 分鐘各期（新的在前）。 */
  record Found(boolean known, List<ScreenlineEvaluator.Sample> samples) { }

  /**
   * 讀 VD 月檔：近 80 分鐘可能跨到上個月，兩個月的檔都讀，再合併。
   * 月檔不存在（剛換月、或資料已依保留期限刪除）就當作沒有資料。
   */
  static Future<JsonObject> screenlines(MonthlyStore vd, List<ScreenlineQuery> queries, long now) {
    YearMonth to = MonthlyStore.monthOf(now);
    YearMonth from = MonthlyStore.monthOf(now - ScreenlineEvaluator.WINDOW_MS);
    List<Future<List<Found>>> parts = new ArrayList<>();
    for (YearMonth m = from; !m.isAfter(to); m = m.plusMonths(1)) {
      parts.add(vd.read(m, c -> collect(c, queries, now)));
    }
    return Future.all(parts).map(cf -> {
      List<List<Found>> months = new ArrayList<>();
      for (Future<List<Found>> p : parts) if (p.result() != null) months.add(p.result());
      return evaluate(queries, months, now);
    });
  }

  /** 單一月檔版本（測試與單月查詢用）。 */
  static JsonObject screenlines(Connection c, List<ScreenlineQuery> queries, long now) throws SQLException {
    return evaluate(queries, List.of(collect(c, queries, now)), now);
  }

  static List<Found> collect(Connection c, List<ScreenlineQuery> queries, long now) throws SQLException {
    List<Found> out = new ArrayList<>();
    for (ScreenlineQuery q : queries) {
      Long deviceId = deviceId(c, q.vdId());
      out.add(new Found(deviceId != null, deviceId == null ? List.of() : samples(c, deviceId, now)));
    }
    return out;
  }

  /** months：每個月檔一份 collect 的結果（與 queries 同順序）。 */
  static JsonObject evaluate(List<ScreenlineQuery> queries, List<List<Found>> months, long now) {
    JsonArray items = new JsonArray();
    Long newest = null;
    String newestRaw = null;
    for (int i = 0; i < queries.size(); i++) {
      ScreenlineQuery q = queries.get(i);
      boolean known = false;
      List<ScreenlineEvaluator.Sample> samples = new ArrayList<>();
      for (List<Found> month : months) {
        Found f = month.get(i);
        known |= f.known();
        samples.addAll(f.samples());
      }
      samples.sort((a, b) -> Long.compare(b.feedTimeMs(), a.feedTimeMs()));
      ScreenlineEvaluator.Result r = ScreenlineEvaluator.evaluate(samples, now, q.volLimit(), q.volContinuous(), known);
      if (r.latestFeedTimeMs() != null && (newest == null || r.latestFeedTimeMs() > newest)) {
        newest = r.latestFeedTimeMs();
        newestRaw = r.latestFeedTimeRaw();
      }
      items.add(new JsonObject().put("id", q.def().id()).put("label", q.def().label()).put("vdId", q.vdId())
          .put("volLimit", q.volLimit()).put("volContinuous", q.volContinuous())
          .put("state", r.state().name()).put("volume", r.volume()).put("continuous", r.continuous())
          .put("error", r.error()).put("judge", r.judge()).put("reason", r.reason())
          .put("periods", r.periods()).put("invalidPeriods", r.invalidPeriods())
          .put("latestFeedTime", r.latestFeedTimeRaw()));
    }
    return block(newestRaw, newest, now, ScreenlineEvaluator.STALE_MS, items);
  }

  static Long deviceId(Connection c, String vdId) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement("SELECT device_id FROM vd_device WHERE eq_id = ?")) {
      ps.setString(1, vdId);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) return rs.getLong(1);
      }
    }
    // 來源代碼可能用相容字，退而用正規化比對
    String want = NetworkConfig.normId(vdId);
    try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT device_id, eq_id FROM vd_device")) {
      while (rs.next()) if (want.equals(NetworkConfig.normId(rs.getString(2)))) return rs.getLong(1);
    }
    return null;
  }

  /** 最近 80 分鐘內每一期的車道統計，新的在前；同一資料時間有多份（來源更正）只取最後收到的。 */
  static List<ScreenlineEvaluator.Sample> samples(Connection c, long deviceId, long now) throws SQLException {
    List<ScreenlineEvaluator.Sample> out = new ArrayList<>();
    try (PreparedStatement ps = c.prepareStatement("""
        SELECT s.feed_time_ms, s.feed_time_raw,
               COUNT(ls.lane_key),
               SUM(CASE WHEN ls.lane_key IS NOT NULL
                         AND (ls.lane_total_car_volume IS NULL OR ls.lane_total_car_volume < 0) THEN 1 ELSE 0 END),
               COALESCE(SUM(CASE WHEN ls.lane_total_car_volume >= 0 THEN ls.lane_total_car_volume END), 0)
        FROM vd_device_sample ds
        JOIN vd_snapshot s ON s.snapshot_id = ds.snapshot_id
        LEFT JOIN vd_lane l ON l.device_id = ds.device_id
        LEFT JOIN vd_lane_sample ls ON ls.snapshot_id = ds.snapshot_id AND ls.lane_key = l.lane_key
        WHERE ds.device_id = ? AND s.feed_time_ms >= ?
        GROUP BY s.snapshot_id
        ORDER BY s.feed_time_ms DESC, s.snapshot_id DESC""")) {
      ps.setLong(1, deviceId);
      ps.setLong(2, now - ScreenlineEvaluator.WINDOW_MS);
      try (ResultSet rs = ps.executeQuery()) {
        long lastTime = Long.MIN_VALUE;
        while (rs.next()) {
          long t = rs.getLong(1);
          if (t == lastTime) continue;
          lastTime = t;
          out.add(new ScreenlineEvaluator.Sample(t, rs.getString(2), rs.getInt(3), rs.getInt(4), rs.getInt(5)));
        }
      }
    }
    return out;
  }

  // ---------------------------------------------------------------- 工具

  private static JsonObject block(String feedRaw, Long feedMs, long now, long staleMs, JsonArray items) {
    return new JsonObject().put("feedTime", feedRaw).put("feedTimeMs", feedMs)
        .put("stale", feedMs == null || now - feedMs > staleMs).put("items", items);
  }

  private static Integer intOrNull(ResultSet rs, int col) throws SQLException {
    int v = rs.getInt(col);
    return rs.wasNull() ? null : v;
  }

  private static Integer parseInt(String s) {
    if (s == null) return null;
    try {
      return Integer.parseInt(s.trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }
}

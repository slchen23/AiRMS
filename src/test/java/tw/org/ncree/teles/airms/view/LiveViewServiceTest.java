package tw.org.ncree.teles.airms.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Test;
import tw.org.ncree.teles.airms.db.Db;
import tw.org.ncree.teles.airms.db.MonthlyStore;
import tw.org.ncree.teles.airms.db.Schema;
import tw.org.ncree.teles.airms.db.Series;

/** 從資料庫整理即時監視資料：路段、RMS、屏柵線（含同時間更正版與無效車道）。 */
class LiveViewServiceTest {
  private static final long NOW = 1_793_670_000_000L;
  private static final long MIN = 60_000L;

  private static Connection open(String name) throws Exception {
    return Db.open(Files.createTempDirectory("airms-view").resolve(name), Db.Durability.NORMAL);
  }

  private static JsonObject find(JsonArray items, String key, Object value) {
    for (Object o : items) if (value.equals(((JsonObject) o).getValue(key))) return (JsonObject) o;
    throw new AssertionError("找不到 " + key + "=" + value);
  }

  @Test
  void sectionsAndRms() throws Exception {
    try (Connection c = Db.openMemory()) {
      Schema.migrateLive(c);
      try (PreparedStatement ps = c.prepareStatement("""
          INSERT INTO section_latest(freeway_id, direction_id, from_location, from_milepost, end_location, end_milepost,
            section_average_speed, section_total_car_volume, feed_time_raw, feed_time_ms, updated_at_ms)
          VALUES (?,?,?,?,?,?,?,?,?,?,?)""")) {
        Object[][] rows = {
            {"1", "3", "新竹系統", "99400", "頭份", "110400", 99, 228, "2026-11-03 08:55:31", NOW - 2 * MIN},
            {"1", "4", "頭份", "110400", "新竹系統", "99400", 15, 0, "2026-11-03 08:55:31", NOW - 2 * MIN},
            {"3", "3", "古坑(北)", "268232", "古坑系統", "269200", 35, 10, "2026-11-03 08:30:00", NOW - 30 * MIN},
        };
        for (Object[] r : rows) {
          for (int i = 0; i < 6; i++) ps.setString(i + 1, (String) r[i]);
          ps.setInt(7, (Integer) r[6]);
          ps.setInt(8, (Integer) r[7]);
          ps.setString(9, (String) r[8]);
          ps.setLong(10, (Long) r[9]);
          ps.setLong(11, NOW);
          ps.executeUpdate();
        }
      }
      JsonObject s = LiveViewService.sections(c, NOW);
      assertEquals("2026-11-03 08:55:31", s.getString("feedTime"));
      assertFalse(s.getBoolean("stale"));
      JsonArray items = s.getJsonArray("items");
      assertEquals("FREE", find(items, "fromName", "新竹系統").getString("cls"));
      assertEquals(Integer.valueOf(99400), find(items, "freewayId", "1").getInteger("from"));
      assertEquals("NO_DATA", find(items, "fromName", "頭份").getString("cls"));             // ≤20 且流量 0
      JsonObject old = find(items, "freewayId", "3");
      assertTrue(old.getBoolean("stale"));                                                  // 比最新舊 25 分鐘
      assertEquals("NO_DATA", old.getString("cls"));
      assertEquals(old, items.getJsonObject(0));             // 舊路段排最前面，畫圖時會被新路段蓋過
      assertEquals("2026-11-03 08:30:00", old.getString("feedTime"));

      // 系統設定：門檻改成 30/50/100/110、關閉低速規則、過時改 40 分鐘
      JsonObject t = LiveViewService.sections(c, NOW, new TrafficRules.SpeedSteps(30, 50, 100, 110, false), 40 * MIN);
      JsonArray ti = t.getJsonArray("items");
      assertEquals("C60", find(ti, "fromName", "新竹系統").getString("cls"));      // 99 ≤ 100
      assertEquals("C20", find(ti, "fromName", "頭份").getString("cls"));         // 低速流量 0 改畫紫
      JsonObject notOld = find(ti, "freewayId", "3");
      assertFalse(notOld.getBoolean("stale"));
      assertEquals("C40", notOld.getString("cls"));                            // 35 ≤ 50

      try (PreparedStatement ps = c.prepareStatement("""
          INSERT INTO rms_latest(eq_id, eq_comm_status, rms_rate, rms_control_mode, feed_time_raw, feed_time_ms, updated_at_ms)
          VALUES (?,?,?,?,?,?,?)""")) {
        Object[][] rows = {
            {"RMS-N1-N-110-I-SN-1-北上入口-頭份", "0", "閃黃", "固定時制模式", NOW - MIN},
            {"RMS-N1-S-110-I-NS-1-南下入口-頭份", "1", "12", "固定時制模式", NOW - MIN},
            {"RMS-N1-S-125.940-ES-L-南下入口-頭屋", "0", "18", "固定時制模式", NOW - 20 * MIN},
        };
        for (Object[] r : rows) {
          ps.setString(1, (String) r[0]);
          ps.setString(2, (String) r[1]);
          ps.setString(3, (String) r[2]);
          ps.setString(4, (String) r[3]);
          ps.setString(5, "t");
          ps.setLong(6, (Long) r[4]);
          ps.setLong(7, NOW);
          ps.executeUpdate();
        }
      }
      JsonArray rms = LiveViewService.rms(c, NOW).getJsonArray("items");
      assertEquals("30", find(rms, "id", "RMS-N1-N-110-I-SN-1-北上入口-頭份").getString("display")); // 已正規化
      assertEquals("X", find(rms, "id", "RMS-N1-S-110-I-NS-1-南下入口-頭份").getString("display"));
      assertEquals("", find(rms, "id", "RMS-N1-S-125.940-ES-L-南下入口-頭屋").getString("display")); // 過舊
    }
  }

  @Test
  void screenlineFromVdTables() throws Exception {
    try (Connection c = open("vd_202611.db")) {
      Schema.migrateVd(c);
      try (var st = c.createStatement()) {
        st.execute("INSERT INTO vd_device(device_id, eq_id, first_seen_ms) VALUES (1, 'VD-N1-S-109.250-M-LOOP', 0)");
        st.execute("INSERT INTO vd_lane(lane_key, device_id, lane_ref, first_seen_ms) VALUES (11,1,'1',0),(12,1,'2',0),(13,1,'3',0)");
      }
      // 6 期：每期 3 車道各 150 → 450；第 3 期有一個車道 -2；最新一時間有一份更正版（後收到者為準）
      int snap = 0;
      for (int i = 5; i >= 0; i--) {
        long t = NOW - MIN - i * 5 * MIN;
        int[] lanes = i == 2 ? new int[] {150, -2, 150} : new int[] {150, 150, 150};
        snap = insertSnapshot(c, snap + 1, t, lanes);
      }
      snap = insertSnapshot(c, snap + 1, NOW - MIN, new int[] {10, 10, 10}); // 更正版
      List<LiveViewService.ScreenlineQuery> q = List.of(
          new LiveViewService.ScreenlineQuery(
              new NetworkConfig.ScreenlineDef("n1-99-s", "國1北往中", "1", "S", "VD-N1-S-109.250-M-LOOP", 5400, 450),
              "VD-N1-S-109.250-M-LOOP", 5400, 400),
          new LiveViewService.ScreenlineQuery(
              new NetworkConfig.ScreenlineDef("n1-250-s", "國1中往南", "1", "S", "VD-N1-S-249.200-M-LOOP", 4200, 350),
              "VD-N1-S-249.200-M-LOOP", 4200, 350));
      JsonObject out = LiveViewService.screenlines(c, q, NOW);
      JsonObject a = find(out.getJsonArray("items"), "id", "n1-99-s");
      assertEquals(6, a.getInteger("periods"));
      assertEquals(1, a.getInteger("invalidPeriods"));
      // 有效 5 期：30 + 450×4 = 1830 → ×12/5 = 4392
      assertEquals(Integer.valueOf(4392), a.getInteger("judge"));
      assertEquals("CONTINUOUS", a.getString("state"));  // 第 2、4 期… 相鄰兩期都 450 > 400
      JsonObject b = find(out.getJsonArray("items"), "id", "n1-250-s");
      assertEquals("ERROR", b.getString("state"));
      assertTrue(b.getString("reason").contains("沒有這支 VD"));
      assertFalse(out.getBoolean("stale"));
    }
  }

  @Test
  void screenlineSpansTwoMonthFiles() throws Exception {
    io.vertx.core.Vertx vertx = io.vertx.core.Vertx.vertx();
    MonthlyStore vd = new MonthlyStore(vertx, Series.VD, Files.createTempDirectory("airms-vdm"));
    try {
      // 11/1 00:20（台北）查詢：近 80 分鐘有 10/31 23:20～23:55 的 8 期（10 月檔）與 11/1 00:00～00:15 的 4 期（11 月檔）
      long now = java.time.ZonedDateTime.of(2026, 11, 1, 0, 20, 0, 0, MonthlyStore.TAIPEI).toInstant().toEpochMilli();
      long first = now - 60 * MIN;
      for (int i = 0; i < 12; i++) {
        long t = first + i * 5 * MIN;
        int id = i + 1;
        await(vd.forWrite(MonthlyStore.monthOf(t)).compose(db -> db.run(c -> {
          try (var st = c.createStatement()) {
            st.execute("INSERT OR IGNORE INTO vd_device(device_id, eq_id, first_seen_ms) VALUES (1, 'VD-X', 0)");
            st.execute("INSERT OR IGNORE INTO vd_lane(lane_key, device_id, lane_ref, first_seen_ms) "
                + "VALUES (11,1,'1',0),(12,1,'2',0),(13,1,'3',0)");
          }
          return insertSnapshot(c, id, t, new int[] {160, 160, 160});
        })));
      }
      assertEquals(List.of(YearMonth.of(2026, 10), YearMonth.of(2026, 11)), vd.monthsOnDisk());
      List<LiveViewService.ScreenlineQuery> q = List.of(new LiveViewService.ScreenlineQuery(
          new NetworkConfig.ScreenlineDef("x", "測試", "1", "S", "VD-X", 5400, 450), "VD-X", 5400, 450));
      JsonObject out = await(LiveViewService.screenlines(vd, q, now));
      JsonObject a = out.getJsonArray("items").getJsonObject(0);
      assertEquals(12, a.getInteger("periods"));            // 兩個月檔合併
      assertEquals(Integer.valueOf(5760), a.getInteger("judge"));
      assertEquals("VOLUME", a.getString("state"));
      assertEquals("t12", a.getString("latestFeedTime"));   // 最新一期在 11 月檔

      // 12 月初、11 月檔還在但 12 月檔還沒有：不建立空檔，只讀既有的
      long dec = java.time.ZonedDateTime.of(2026, 12, 1, 0, 5, 0, 0, MonthlyStore.TAIPEI).toInstant().toEpochMilli();
      JsonObject later = await(LiveViewService.screenlines(vd, q, dec));
      assertEquals("ERROR", later.getJsonArray("items").getJsonObject(0).getString("state"));
      assertFalse(vd.exists(YearMonth.of(2026, 12)));
    } finally {
      await(vd.closeAll());
      await(vertx.close());
    }
  }

  static <T> T await(io.vertx.core.Future<T> f) throws Exception {
    return f.toCompletionStage().toCompletableFuture().get(30, java.util.concurrent.TimeUnit.SECONDS);
  }

  private static int insertSnapshot(Connection c, int id, long t, int[] lanes) throws Exception {
    try (var st = c.createStatement()) {
      st.execute("INSERT INTO vd_snapshot(snapshot_id, feed_time_raw, feed_time_ms, received_at_ms, raw_sha256, "
          + "device_count, lane_count, rows_bad) VALUES (" + id + ", 't" + id + "', " + t + ", " + t + ", 'sha" + id
          + "', 1, 3, 0)");
      st.execute("INSERT INTO vd_device_sample(snapshot_id, device_id, eq_comm_status) VALUES (" + id + ", 1, '0')");
      for (int k = 0; k < lanes.length; k++) {
        st.execute("INSERT INTO vd_lane_sample(snapshot_id, lane_key, lane_total_car_volume) VALUES (" + id + ", "
            + (11 + k) + ", " + lanes[k] + ")");
      }
    }
    return id;
  }
}

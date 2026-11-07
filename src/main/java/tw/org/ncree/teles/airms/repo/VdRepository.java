package tw.org.ncree.teles.airms.repo;

import io.vertx.core.json.JsonObject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import tw.org.ncree.teles.airms.db.Schema;
import tw.org.ncree.teles.airms.parse.VdParser;

/**
 * VD 全量資料寫入該月的 vd_yyyyMM.db。一份快照在同一個交易內寫完：快照、設備、車道、樣本。
 * 每個月檔有自己的設備與車道編號，編號快取在記憶體、依月份分開；
 * write 只能在 VD 月檔的 Db 執行緒呼叫（所有 VD 月檔共用同一條執行緒）。
 */
public final class VdRepository {
  private static final String LANE_COLS = String.join(", ", Schema.VD_LANE_FIELDS);
  private static final String LANE_MARKS = Schema.VD_LANE_FIELDS.stream().map(f -> "?").collect(Collectors.joining(","));

  /** 一個月檔的編號快取。 */
  private static final class Keys {
    final Map<String, Long> deviceIds = new HashMap<>();
    final Map<String, Long> laneKeys = new HashMap<>();
  }

  private final Map<YearMonth, Keys> caches = new ConcurrentHashMap<>();

  public record Result(boolean inserted, long snapshotId, int devices, int lanes) { }

  /** 同一份內容（雜湊相同）已存過就不再寫，回傳 inserted=false。month 是這個連線所屬的月檔。 */
  public Result write(Connection c, YearMonth month, VdParser.Snapshot s, String sha256, long receivedAtMs)
      throws SQLException {
    Keys cache = loadCache(c, month);
    long snapshotId;
    try (PreparedStatement ps = c.prepareStatement("""
        INSERT OR IGNORE INTO vd_snapshot(feed_time_raw, feed_time_ms, control_center_id, file_name,
                                          received_at_ms, raw_sha256, device_count, lane_count, rows_bad)
        VALUES (?,?,?,?,?,?,?,?,?)""", Statement.RETURN_GENERATED_KEYS)) {
      ps.setString(1, s.feedTimeRaw());
      ps.setLong(2, s.feedTimeMs());
      ps.setString(3, s.controlCenterId());
      ps.setString(4, s.fileName());
      ps.setLong(5, receivedAtMs);
      ps.setString(6, sha256);
      ps.setInt(7, s.devices().size());
      ps.setInt(8, s.laneCount());
      ps.setInt(9, s.stats().bad());
      if (ps.executeUpdate() == 0) return new Result(false, -1, 0, 0);
      try (ResultSet keys = ps.getGeneratedKeys()) {
        if (!keys.next()) throw new SQLException("vd_snapshot 沒有回傳新編號");
        snapshotId = keys.getLong(1);
      }
    }

    try (PreparedStatement newDevice = c.prepareStatement(
             "INSERT INTO vd_device(eq_id, first_seen_ms) VALUES (?,?)", Statement.RETURN_GENERATED_KEYS);
         PreparedStatement newLane = c.prepareStatement(
             "INSERT INTO vd_lane(device_id, lane_ref, first_seen_ms) VALUES (?,?,?)", Statement.RETURN_GENERATED_KEYS);
         PreparedStatement deviceSample = c.prepareStatement("""
             INSERT INTO vd_device_sample(snapshot_id, device_id, eq_comm_status, lanes, congestion_level, extra_json)
             VALUES (?,?,?,?,?,?)""");
         PreparedStatement laneSample = c.prepareStatement("INSERT INTO vd_lane_sample(snapshot_id, lane_key, "
             + "is_data_valid, " + LANE_COLS + ", extra_json) VALUES (?,?,?," + LANE_MARKS + ",?)")) {
      int lanes = 0;
      for (VdParser.Device d : s.devices()) {
        long deviceId = deviceId(cache, newDevice, d.eqId(), s.feedTimeMs());
        deviceSample.setLong(1, snapshotId);
        deviceSample.setLong(2, deviceId);
        deviceSample.setString(3, d.commStatus());
        deviceSample.setString(4, d.lanes());
        deviceSample.setString(5, d.congestionLevel());
        deviceSample.setString(6, json(d.extra()));
        deviceSample.addBatch();

        for (VdParser.Lane l : d.laneList()) {
          long laneKey = laneKey(cache, newLane, deviceId, d.eqId(), l.laneRef(), s.feedTimeMs());
          Map<String, String> extra = new LinkedHashMap<>(l.extra());
          int i = 1;
          laneSample.setLong(i++, snapshotId);
          laneSample.setLong(i++, laneKey);
          laneSample.setString(i++, l.isDataValid());
          for (String f : Schema.VD_LANE_FIELDS) {
            String raw = l.values().get(f);
            Integer v = parseInt(raw);
            if (v == null) {
              laneSample.setNull(i++, Types.INTEGER);
              if (raw != null) extra.put(f, raw); // 非整數的原字串保留在 extra_json
            } else {
              laneSample.setInt(i++, v);
            }
          }
          laneSample.setString(i, json(extra));
          laneSample.addBatch();
          lanes++;
        }
      }
      deviceSample.executeBatch();
      laneSample.executeBatch();
      return new Result(true, snapshotId, s.devices().size(), lanes);
    }
  }

  /** 交易失敗回滾後（或月檔被刪除後），快取可能含有不存在的鍵，必須清掉重載。 */
  public void invalidateCache(YearMonth month) {
    caches.remove(month);
  }

  private Keys loadCache(Connection c, YearMonth month) throws SQLException {
    Keys k = caches.get(month);
    if (k != null) return k;
    k = new Keys();
    try (Statement st = c.createStatement()) {
      try (ResultSet rs = st.executeQuery("SELECT device_id, eq_id FROM vd_device")) {
        while (rs.next()) k.deviceIds.put(rs.getString(2), rs.getLong(1));
      }
      try (ResultSet rs = st.executeQuery(
          "SELECT l.lane_key, d.eq_id, l.lane_ref FROM vd_lane l JOIN vd_device d ON d.device_id = l.device_id")) {
        while (rs.next()) k.laneKeys.put(laneCacheKey(rs.getString(2), rs.getString(3)), rs.getLong(1));
      }
    }
    caches.put(month, k);
    return k;
  }

  private static long deviceId(Keys k, PreparedStatement insert, String eqId, long seenMs) throws SQLException {
    Long id = k.deviceIds.get(eqId);
    if (id != null) return id;
    insert.setString(1, eqId);
    insert.setLong(2, seenMs);
    insert.executeUpdate();
    try (ResultSet keys = insert.getGeneratedKeys()) {
      if (!keys.next()) throw new SQLException("vd_device 沒有回傳新編號");
      long newId = keys.getLong(1);
      k.deviceIds.put(eqId, newId);
      return newId;
    }
  }

  private static long laneKey(Keys k, PreparedStatement insert, long deviceId, String eqId, String laneRef,
                              long seenMs) throws SQLException {
    String cacheKey = laneCacheKey(eqId, laneRef);
    Long key = k.laneKeys.get(cacheKey);
    if (key != null) return key;
    insert.setLong(1, deviceId);
    insert.setString(2, laneRef);
    insert.setLong(3, seenMs);
    insert.executeUpdate();
    try (ResultSet keys = insert.getGeneratedKeys()) {
      if (!keys.next()) throw new SQLException("vd_lane 沒有回傳新編號");
      long newKey = keys.getLong(1);
      k.laneKeys.put(cacheKey, newKey);
      return newKey;
    }
  }

  private static String laneCacheKey(String eqId, String laneRef) {
    return eqId + "\u0000" + laneRef;
  }

  private static String json(Map<String, String> m) {
    if (m == null || m.isEmpty()) return null;
    return new JsonObject(new LinkedHashMap<>(m)).encode();
  }

  private static Integer parseInt(String s) {
    if (s == null) return null;
    try {
      return Integer.valueOf(s.trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }
}

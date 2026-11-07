package tw.org.ncree.teles.airms.repo;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.stream.Collectors;
import tw.org.ncree.teles.airms.db.Schema;
import tw.org.ncree.teles.airms.parse.RmsParser;

/**
 * RMS 資料：最新一筆寫入記憶體資料庫（rms_latest，只會往新時間更新），
 * 歷史寫入該月的 rms_yyyyMM.db（同設備同時間只存一筆）。只在各自 Db 的執行緒使用。
 */
public final class RmsRepository {
  private RmsRepository() { }

  private static final String COLS = String.join(", ", Schema.RMS_FIELDS);
  private static final String MARKS = Schema.RMS_FIELDS.stream().map(f -> "?").collect(Collectors.joining(","));
  private static final String UPDATES = Schema.RMS_FIELDS.stream()
      .map(f -> f + "=excluded." + f).collect(Collectors.joining(", "));

  private static final String LATEST = "INSERT INTO rms_latest(eq_id, " + COLS
      + ", feed_time_raw, feed_time_ms, updated_at_ms) VALUES (?," + MARKS + ",?,?,?) "
      + "ON CONFLICT(eq_id) DO UPDATE SET " + UPDATES
      + ", feed_time_raw=excluded.feed_time_raw, feed_time_ms=excluded.feed_time_ms, "
      + "updated_at_ms=excluded.updated_at_ms WHERE excluded.feed_time_ms >= rms_latest.feed_time_ms";

  private static final String HISTORY = "INSERT OR IGNORE INTO rms_history(eq_id, feed_time_ms, " + COLS
      + ", received_at_ms) VALUES (?,?," + MARKS + ",?)";

  /** 更新記憶體中的最新一筆（舊時間的資料不會蓋掉新的）。 */
  public static void writeLatest(Connection c, RmsParser.Snapshot s, long receivedAtMs) throws SQLException {
    try (PreparedStatement latest = c.prepareStatement(LATEST)) {
      for (RmsParser.Row r : s.rows()) {
        int i = 1;
        latest.setString(i++, r.eqId());
        for (String f : Schema.RMS_FIELDS) latest.setString(i++, r.values().get(f));
        latest.setString(i++, s.feedTimeRaw());
        latest.setLong(i++, s.feedTimeMs());
        latest.setLong(i, receivedAtMs);
        latest.addBatch();
      }
      latest.executeBatch();
    }
  }

  /** 寫入歷史；回傳新增的筆數（同時間重抓為 0）。 */
  public static int writeHistory(Connection c, RmsParser.Snapshot s, long receivedAtMs) throws SQLException {
    try (PreparedStatement history = c.prepareStatement(HISTORY)) {
      for (RmsParser.Row r : s.rows()) {
        int i = 1;
        history.setString(i++, r.eqId());
        history.setLong(i++, s.feedTimeMs());
        for (String f : Schema.RMS_FIELDS) history.setString(i++, r.values().get(f));
        history.setLong(i, receivedAtMs);
        history.addBatch();
      }
      return SectionRepository.sum(history.executeBatch());
    }
  }
}

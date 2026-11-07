package tw.org.ncree.teles.airms.repo;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.stream.Collectors;
import tw.org.ncree.teles.airms.db.Schema;
import tw.org.ncree.teles.airms.parse.SectionParser;

/**
 * 路段資料：最新一筆寫入記憶體資料庫（section_latest，只會往新時間更新），
 * 歷史寫入該月的 section_yyyyMM.db（同路段同時間只存一筆）。只在各自 Db 的執行緒使用。
 */
public final class SectionRepository {
  private SectionRepository() { }

  private static final String INTS = String.join(", ", Schema.SECTION_INT_FIELDS);
  private static final String INT_MARKS = Schema.SECTION_INT_FIELDS.stream().map(f -> "?").collect(Collectors.joining(","));
  private static final String INT_UPDATES = Schema.SECTION_INT_FIELDS.stream()
      .map(f -> f + "=excluded." + f).collect(Collectors.joining(", "));

  private static final String LATEST = "INSERT INTO section_latest(freeway_id, expressway_id, direction_id, "
      + "from_location, from_milepost, end_location, end_milepost, " + INTS
      + ", feed_time_raw, feed_time_ms, updated_at_ms) VALUES (?,?,?,?,?,?,?," + INT_MARKS + ",?,?,?) "
      + "ON CONFLICT(freeway_id, direction_id, from_milepost, end_milepost) DO UPDATE SET "
      + "expressway_id=excluded.expressway_id, from_location=excluded.from_location, "
      + "end_location=excluded.end_location, " + INT_UPDATES
      + ", feed_time_raw=excluded.feed_time_raw, feed_time_ms=excluded.feed_time_ms, "
      + "updated_at_ms=excluded.updated_at_ms WHERE excluded.feed_time_ms >= section_latest.feed_time_ms";

  private static final String HISTORY = "INSERT OR IGNORE INTO section_history(freeway_id, expressway_id, "
      + "direction_id, from_milepost, end_milepost, feed_time_ms, " + INTS + ", received_at_ms) "
      + "VALUES (?,?,?,?,?,?," + INT_MARKS + ",?)";

  /** 更新記憶體中的最新一筆（舊時間的資料不會蓋掉新的）。 */
  public static void writeLatest(Connection c, SectionParser.Snapshot s, long receivedAtMs) throws SQLException {
    try (PreparedStatement latest = c.prepareStatement(LATEST)) {
      for (SectionParser.Row r : s.rows()) {
        int i = 1;
        latest.setString(i++, r.freewayId());
        latest.setString(i++, r.expresswayId());
        latest.setString(i++, r.directionId());
        latest.setString(i++, r.fromLocation());
        latest.setString(i++, r.fromMilepost());
        latest.setString(i++, r.endLocation());
        latest.setString(i++, r.endMilepost());
        for (String f : Schema.SECTION_INT_FIELDS) FeedLogRepository.setInt(latest, i++, r.values().get(f));
        latest.setString(i++, s.feedTimeRaw());
        latest.setLong(i++, s.feedTimeMs());
        latest.setLong(i, receivedAtMs);
        latest.addBatch();
      }
      latest.executeBatch();
    }
  }

  /** 寫入歷史；回傳新增的筆數（同時間重抓為 0）。 */
  public static int writeHistory(Connection c, SectionParser.Snapshot s, long receivedAtMs) throws SQLException {
    try (PreparedStatement history = c.prepareStatement(HISTORY)) {
      for (SectionParser.Row r : s.rows()) {
        int i = 1;
        history.setString(i++, r.freewayId());
        history.setString(i++, r.expresswayId());
        history.setString(i++, r.directionId());
        history.setString(i++, r.fromMilepost());
        history.setString(i++, r.endMilepost());
        history.setLong(i++, s.feedTimeMs());
        for (String f : Schema.SECTION_INT_FIELDS) FeedLogRepository.setInt(history, i++, r.values().get(f));
        history.setLong(i, receivedAtMs);
        history.addBatch();
      }
      return sum(history.executeBatch());
    }
  }

  static int sum(int[] counts) {
    int n = 0;
    for (int v : counts) if (v > 0) n += v;
    return n;
  }
}

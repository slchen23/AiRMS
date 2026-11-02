package tw.org.ncree.teles.airms.repo;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import tw.org.ncree.teles.airms.feed.FeedKind;
import tw.org.ncree.teles.airms.feed.RawXmlArchive;

/** airms.db 的 feed_attempt（每次抓取）與 raw_xml_file（每份不同內容的原始檔）。只在 Db 執行緒使用。 */
public final class FeedLogRepository {
  private FeedLogRepository() { }

  public record Attempt(FeedKind kind, long startedAtMs, long finishedAtMs, Integer httpStatus, Integer bytes,
                        String sha256, String outcome, String error, String feedTimeRaw, Long feedTimeMs) { }

  public record RawFile(FeedKind kind, String sha256, String relativePath, long bytes, long storedBytes,
                        long receivedAtMs, String feedTimeRaw, Long feedTimeMs, String parseOutcome,
                        Integer rowsTotal, Integer rowsBad, String note) { }

  public static void insertAttempt(Connection c, Attempt a) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement("""
        INSERT INTO feed_attempt(source, started_at_ms, finished_at_ms, http_status, bytes, sha256, outcome,
                                 error, feed_time_raw, feed_time_ms)
        VALUES (?,?,?,?,?,?,?,?,?,?)""")) {
      ps.setString(1, a.kind().key());
      ps.setLong(2, a.startedAtMs());
      ps.setLong(3, a.finishedAtMs());
      setInt(ps, 4, a.httpStatus());
      setInt(ps, 5, a.bytes());
      ps.setString(6, a.sha256());
      ps.setString(7, a.outcome());
      ps.setString(8, a.error());
      ps.setString(9, a.feedTimeRaw());
      setLong(ps, 10, a.feedTimeMs());
      ps.executeUpdate();
    }
  }

  /** 同一來源同一雜湊只留第一筆（之後重抓到同內容不重複記錄）。 */
  public static void insertRawFile(Connection c, RawFile r) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement("""
        INSERT OR IGNORE INTO raw_xml_file(source, sha256, rel_path, bytes, stored_bytes, received_at_ms,
                                           feed_time_raw, feed_time_ms, parse_outcome, rows_total, rows_bad, note)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,?)""")) {
      ps.setString(1, r.kind().key());
      ps.setString(2, r.sha256());
      ps.setString(3, r.relativePath());
      ps.setLong(4, r.bytes());
      ps.setLong(5, r.storedBytes());
      ps.setLong(6, r.receivedAtMs());
      ps.setString(7, r.feedTimeRaw());
      setLong(ps, 8, r.feedTimeMs());
      ps.setString(9, r.parseOutcome());
      setInt(ps, 10, r.rowsTotal());
      setInt(ps, 11, r.rowsBad());
      ps.setString(12, r.note());
      ps.executeUpdate();
    }
  }

  /** 這份內容是否已歸檔過；有就回傳原本的檔案位置。 */
  public static RawXmlArchive.Stored archivedFile(Connection c, FeedKind kind, String sha) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(
        "SELECT rel_path, stored_bytes FROM raw_xml_file WHERE source=? AND sha256=?")) {
      ps.setString(1, kind.key());
      ps.setString(2, sha);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? new RawXmlArchive.Stored(rs.getString(1), rs.getLong(2)) : null;
      }
    }
  }

  /** 重啟後用來判斷「內容沒變」，避免重複歸檔。 */
  public static String lastSha(Connection c, FeedKind kind) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(
        "SELECT sha256 FROM raw_xml_file WHERE source=? ORDER BY received_at_ms DESC, raw_id DESC LIMIT 1")) {
      ps.setString(1, kind.key());
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getString(1) : null;
      }
    }
  }

  static void setInt(PreparedStatement ps, int i, Integer v) throws SQLException {
    if (v == null) ps.setNull(i, Types.INTEGER);
    else ps.setInt(i, v);
  }

  static void setLong(PreparedStatement ps, int i, Long v) throws SQLException {
    if (v == null) ps.setNull(i, Types.INTEGER);
    else ps.setLong(i, v);
  }
}

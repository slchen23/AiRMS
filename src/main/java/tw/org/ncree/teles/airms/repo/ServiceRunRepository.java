package tw.org.ncree.teles.airms.repo;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * service_run：程式每次啟動一列、每分鐘更新心跳、正常停止時寫入停止時間。
 * 日後分析資料缺口時，用它區分「本系統沒在跑」與「來源沒有資料」。只在 core.db 的 Db 執行緒使用。
 */
public final class ServiceRunRepository {
  private ServiceRunRepository() { }

  /** 上一次執行的摘要；stoppedAtMs 為 null 表示不是正常停止（強制結束、當機、斷電）。 */
  public record PreviousRun(long runId, long startedAtMs, long lastHeartbeatMs, Long stoppedAtMs) { }

  public static PreviousRun previous(Connection c) throws SQLException {
    try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(
        "SELECT run_id, started_at_ms, last_heartbeat_ms, stopped_at_ms FROM service_run ORDER BY run_id DESC LIMIT 1")) {
      if (!rs.next()) return null;
      long stopped = rs.getLong(4);
      Long stoppedAt = rs.wasNull() ? null : stopped;
      return new PreviousRun(rs.getLong(1), rs.getLong(2), rs.getLong(3), stoppedAt);
    }
  }

  public static long start(Connection c, long nowMs, String host, String version) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(
        "INSERT INTO service_run(started_at_ms, last_heartbeat_ms, host, app_version) VALUES (?,?,?,?)",
        Statement.RETURN_GENERATED_KEYS)) {
      ps.setLong(1, nowMs);
      ps.setLong(2, nowMs);
      ps.setString(3, host);
      ps.setString(4, version);
      ps.executeUpdate();
      try (ResultSet keys = ps.getGeneratedKeys()) {
        if (!keys.next()) throw new SQLException("service_run 沒有回傳新編號");
        return keys.getLong(1);
      }
    }
  }

  public static void heartbeat(Connection c, long runId, long nowMs) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement("UPDATE service_run SET last_heartbeat_ms=? WHERE run_id=?")) {
      ps.setLong(1, nowMs);
      ps.setLong(2, runId);
      ps.executeUpdate();
    }
  }

  public static void stop(Connection c, long runId, long nowMs) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(
        "UPDATE service_run SET last_heartbeat_ms=?, stopped_at_ms=? WHERE run_id=?")) {
      ps.setLong(1, nowMs);
      ps.setLong(2, nowMs);
      ps.setLong(3, runId);
      ps.executeUpdate();
    }
  }
}

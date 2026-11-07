package tw.org.ncree.teles.airms.auth;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/** core.db 的登入紀錄與管理操作紀錄。只記嘗試的帳號名稱、結果與連線資訊，不記密碼。 */
public final class AuthLogRepository {
  private AuthLogRepository() { }

  public static void login(Connection c, long atMs, String username, Long accountId, String outcome, String ip,
                           String userAgent) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(
        "INSERT INTO login_history(at_ms, username, account_id, outcome, ip, user_agent) VALUES (?,?,?,?,?,?)")) {
      ps.setLong(1, atMs);
      ps.setString(2, clip(username, 64));
      if (accountId == null) ps.setNull(3, Types.INTEGER);
      else ps.setLong(3, accountId);
      ps.setString(4, outcome);
      ps.setString(5, clip(ip, 64));
      ps.setString(6, clip(userAgent, 300));
      ps.executeUpdate();
    }
  }

  public static void audit(Connection c, long atMs, String actor, String action, String target, JsonObject detail,
                           String ip) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(
        "INSERT INTO audit_log(at_ms, actor, action, target, detail_json, ip) VALUES (?,?,?,?,?,?)")) {
      ps.setLong(1, atMs);
      ps.setString(2, clip(actor, 64));
      ps.setString(3, action);
      ps.setString(4, clip(target, 200));
      ps.setString(5, detail == null ? null : clip(detail.encode(), 4000));
      ps.setString(6, clip(ip, 64));
      ps.executeUpdate();
    }
  }

  /** 依時間區間（含 from、不含 to）查詢，新的在前，最多 limit 筆。 */
  public static JsonArray logins(Connection c, long fromMs, long toMs, String username, String outcome, int limit)
      throws SQLException {
    StringBuilder sql = new StringBuilder(
        "SELECT at_ms, username, outcome, ip, user_agent FROM login_history WHERE at_ms >= ? AND at_ms < ?");
    List<Object> args = new ArrayList<>(List.of(fromMs, toMs));
    if (username != null && !username.isBlank()) {
      sql.append(" AND username = ? COLLATE NOCASE");
      args.add(username.trim());
    }
    if (outcome != null && !outcome.isBlank()) {
      sql.append(" AND outcome = ?");
      args.add(outcome.trim());
    }
    sql.append(" ORDER BY at_ms DESC, login_id DESC LIMIT ?");
    args.add(limit);
    JsonArray out = new JsonArray();
    try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
      bind(ps, args);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          out.add(new JsonObject().put("at", rs.getLong(1)).put("username", rs.getString(2))
              .put("outcome", rs.getString(3)).put("ip", rs.getString(4)).put("userAgent", rs.getString(5)));
        }
      }
    }
    return out;
  }

  public static JsonArray audits(Connection c, long fromMs, long toMs, int limit) throws SQLException {
    JsonArray out = new JsonArray();
    try (PreparedStatement ps = c.prepareStatement("""
        SELECT at_ms, actor, action, target, detail_json, ip FROM audit_log
        WHERE at_ms >= ? AND at_ms < ? ORDER BY at_ms DESC, audit_id DESC LIMIT ?""")) {
      bind(ps, List.of(fromMs, toMs, limit));
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          String detail = rs.getString(5);
          out.add(new JsonObject().put("at", rs.getLong(1)).put("actor", rs.getString(2))
              .put("action", rs.getString(3)).put("target", rs.getString(4))
              .put("detail", detail == null ? null : new JsonObject(detail)).put("ip", rs.getString(6)));
        }
      }
    }
    return out;
  }

  private static void bind(PreparedStatement ps, List<Object> args) throws SQLException {
    for (int i = 0; i < args.size(); i++) {
      Object a = args.get(i);
      if (a instanceof Long l) ps.setLong(i + 1, l);
      else if (a instanceof Integer n) ps.setInt(i + 1, n);
      else ps.setString(i + 1, (String) a);
    }
  }

  static String clip(String s, int max) {
    if (s == null) return null;
    String t = s.replace('\r', ' ').replace('\n', ' ');
    return t.length() > max ? t.substring(0, max) : t;
  }
}

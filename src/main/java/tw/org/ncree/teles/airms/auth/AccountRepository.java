package tw.org.ncree.teles.airms.auth;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** core.db 的帳號與權限。只在 core.db 的 Db 執行緒使用。 */
public final class AccountRepository {
  private AccountRepository() { }

  /** 登入時需要的帳號與密碼雜湊。 */
  public record Credentials(Account account, String passwordHash) { }

  private static final String COLS = "account_id, username, display_name, role, enabled, expires_at_ms, "
      + "must_change_password, failed_count, locked_until_ms, last_login_at_ms";

  public static long count(Connection c) throws SQLException {
    try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM account")) {
      rs.next();
      return rs.getLong(1);
    }
  }

  public static List<Account> loadAll(Connection c) throws SQLException {
    Map<Long, Set<Permission>> perms = permissions(c, null);
    List<Account> out = new ArrayList<>();
    try (Statement st = c.createStatement();
         ResultSet rs = st.executeQuery("SELECT " + COLS + " FROM account ORDER BY username COLLATE NOCASE")) {
      while (rs.next()) out.add(read(rs, perms));
    }
    return out;
  }

  public static Account load(Connection c, long id) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement("SELECT " + COLS + " FROM account WHERE account_id=?")) {
      ps.setLong(1, id);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? read(rs, permissions(c, id)) : null;
      }
    }
  }

  public static Credentials findForLogin(Connection c, String username) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(
        "SELECT " + COLS + ", password_hash FROM account WHERE username=? COLLATE NOCASE")) {
      ps.setString(1, username);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) return null;
        long id = rs.getLong(1);
        return new Credentials(read(rs, permissions(c, id)), rs.getString(11));
      }
    }
  }

  public static boolean usernameTaken(Connection c, String username) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM account WHERE username=? COLLATE NOCASE")) {
      ps.setString(1, username);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next();
      }
    }
  }

  public static long insert(Connection c, String username, String displayName, String passwordHash,
                            Account.Role role, boolean enabled, Long expiresAtMs, boolean mustChange,
                            Set<Permission> permissions, long nowMs) throws SQLException {
    long id;
    try (PreparedStatement ps = c.prepareStatement("""
        INSERT INTO account(username, display_name, password_hash, role, enabled, expires_at_ms,
                            must_change_password, password_changed_at_ms, created_at_ms, updated_at_ms)
        VALUES (?,?,?,?,?,?,?,?,?,?)""", Statement.RETURN_GENERATED_KEYS)) {
      ps.setString(1, username);
      ps.setString(2, displayName);
      ps.setString(3, passwordHash);
      ps.setString(4, role.name());
      ps.setInt(5, enabled ? 1 : 0);
      setLong(ps, 6, expiresAtMs);
      ps.setInt(7, mustChange ? 1 : 0);
      ps.setLong(8, nowMs);
      ps.setLong(9, nowMs);
      ps.setLong(10, nowMs);
      ps.executeUpdate();
      try (ResultSet keys = ps.getGeneratedKeys()) {
        keys.next();
        id = keys.getLong(1);
      }
    }
    replacePermissions(c, id, permissions);
    return id;
  }

  public static void update(Connection c, long id, String displayName, Account.Role role, boolean enabled,
                            Long expiresAtMs, Set<Permission> permissions, long nowMs) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement("""
        UPDATE account SET display_name=?, role=?, enabled=?, expires_at_ms=?, updated_at_ms=?
        WHERE account_id=?""")) {
      ps.setString(1, displayName);
      ps.setString(2, role.name());
      ps.setInt(3, enabled ? 1 : 0);
      setLong(ps, 4, expiresAtMs);
      ps.setLong(5, nowMs);
      ps.setLong(6, id);
      ps.executeUpdate();
    }
    replacePermissions(c, id, permissions);
  }

  public static void setPassword(Connection c, long id, String hash, boolean mustChange, long nowMs)
      throws SQLException {
    try (PreparedStatement ps = c.prepareStatement("""
        UPDATE account SET password_hash=?, must_change_password=?, password_changed_at_ms=?, updated_at_ms=?,
                           failed_count=0, locked_until_ms=NULL
        WHERE account_id=?""")) {
      ps.setString(1, hash);
      ps.setInt(2, mustChange ? 1 : 0);
      ps.setLong(3, nowMs);
      ps.setLong(4, nowMs);
      ps.setLong(5, id);
      ps.executeUpdate();
    }
  }

  /**
   * 密碼錯誤：累加連續失敗次數（給管理頁參考）；coolUntilMs 不為 null 時開始冷卻到該時間。
   * 是否冷卻由 LoginProtection 依「計算期間內的錯誤次數」決定。回傳更新後的帳號。
   */
  public static Account recordFailure(Connection c, long id, Long coolUntilMs) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement("""
        UPDATE account SET failed_count = failed_count + 1,
          locked_until_ms = COALESCE(?, locked_until_ms)
        WHERE account_id=?""")) {
      if (coolUntilMs == null) ps.setNull(1, java.sql.Types.INTEGER);
      else ps.setLong(1, coolUntilMs);
      ps.setLong(2, id);
      ps.executeUpdate();
    }
    return load(c, id);
  }

  public static void recordSuccess(Connection c, long id, long nowMs) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(
        "UPDATE account SET failed_count=0, locked_until_ms=NULL, last_login_at_ms=? WHERE account_id=?")) {
      ps.setLong(1, nowMs);
      ps.setLong(2, id);
      ps.executeUpdate();
    }
  }

  public static void unlock(Connection c, long id, long nowMs) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(
        "UPDATE account SET failed_count=0, locked_until_ms=NULL, updated_at_ms=? WHERE account_id=?")) {
      ps.setLong(1, nowMs);
      ps.setLong(2, id);
      ps.executeUpdate();
    }
  }

  private static void replacePermissions(Connection c, long id, Set<Permission> permissions) throws SQLException {
    try (PreparedStatement del = c.prepareStatement("DELETE FROM account_permission WHERE account_id=?")) {
      del.setLong(1, id);
      del.executeUpdate();
    }
    try (PreparedStatement ins = c.prepareStatement(
        "INSERT INTO account_permission(account_id, permission) VALUES (?,?)")) {
      for (Permission p : permissions) {
        ins.setLong(1, id);
        ins.setString(2, p.name());
        ins.addBatch();
      }
      ins.executeBatch();
    }
  }

  private static Map<Long, Set<Permission>> permissions(Connection c, Long onlyId) throws SQLException {
    Map<Long, Set<Permission>> out = new HashMap<>();
    String sql = "SELECT account_id, permission FROM account_permission" + (onlyId == null ? "" : " WHERE account_id=?");
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      if (onlyId != null) ps.setLong(1, onlyId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          Permission.parse(rs.getString(2)).ifPresent(p ->
              out.computeIfAbsent(idOf(rs), k -> EnumSet.noneOf(Permission.class)).add(p));
        }
      }
    }
    return out;
  }

  private static long idOf(ResultSet rs) {
    try {
      return rs.getLong(1);
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private static Account read(ResultSet rs, Map<Long, Set<Permission>> perms) throws SQLException {
    long id = rs.getLong(1);
    return new Account(id, rs.getString(2), rs.getString(3), Account.Role.valueOf(rs.getString(4)),
        rs.getInt(5) == 1, longOrNull(rs, 6), rs.getInt(7) == 1, rs.getInt(8), longOrNull(rs, 9),
        longOrNull(rs, 10), perms.getOrDefault(id, Set.of()));
  }

  private static Long longOrNull(ResultSet rs, int col) throws SQLException {
    long v = rs.getLong(col);
    return rs.wasNull() ? null : v;
  }

  private static void setLong(PreparedStatement ps, int i, Long v) throws SQLException {
    if (v == null) ps.setNull(i, Types.INTEGER);
    else ps.setLong(i, v);
  }
}

package tw.org.ncree.teles.airms.auth;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.EnumSet;
import java.util.Set;

/** 帳號快照（不含密碼雜湊）。不可變；資料有變動時整個換掉。 */
public record Account(
    long id,
    String username,
    String displayName,
    Role role,
    boolean enabled,
    Long expiresAtMs,
    boolean mustChangePassword,
    int failedCount,
    Long lockedUntilMs,
    Long lastLoginAtMs,
    Set<Permission> permissions) {

  public enum Role { ADMIN, USER }

  public Account {
    permissions = permissions.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(permissions));
  }

  public boolean isAdmin() {
    return role == Role.ADMIN;
  }

  /** 管理者擁有全部功能權限。 */
  public boolean can(Permission p) {
    return isAdmin() || permissions.contains(p);
  }

  public boolean expired(long nowMs) {
    return expiresAtMs != null && nowMs >= expiresAtMs;
  }

  public boolean locked(long nowMs) {
    return lockedUntilMs != null && nowMs < lockedUntilMs;
  }

  /** 可以使用系統：啟用、未到期。（鎖定只影響登入，不影響已登入的 Session。） */
  public boolean active(long nowMs) {
    return enabled && !expired(nowMs);
  }

  public JsonObject toJson() {
    JsonArray perms = new JsonArray();
    for (Permission p : Permission.values()) if (can(p)) perms.add(p.name());
    return new JsonObject()
        .put("id", id)
        .put("username", username)
        .put("displayName", displayName)
        .put("role", role.name())
        .put("enabled", enabled)
        .put("expiresAt", expiresAtMs)
        .put("mustChangePassword", mustChangePassword)
        .put("failedCount", failedCount)
        .put("lockedUntil", lockedUntilMs)
        .put("lastLoginAt", lastLoginAtMs)
        .put("permissions", perms);
  }
}

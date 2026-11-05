package tw.org.ncree.teles.airms.auth;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.WorkerExecutor;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import tw.org.ncree.teles.airms.db.Db;
import tw.org.ncree.teles.airms.settings.SettingsService;

/**
 * 帳號、登入、Session 與管理操作。帳號資料快取在記憶體，每次請求驗證只讀記憶體；
 * 任何異動先寫入 auth.db、成功後才更新快取。Argon2 雜湊在專用 worker（最多 2 個同時）計算，不占 event loop。
 */
public final class AuthService {
  private static final Logger log = LogManager.getLogger(AuthService.class);
  private static final long SETUP_TTL_MS = 30 * 60 * 1000L;

  /** 一次請求的登入身分。 */
  public record Principal(SessionRegistry.Session session, Account account) { }

  public record LoginOk(String token, Principal principal) { }

  private final Db db;
  private final SettingsService settings;
  private final LongSupplier clock;
  private final WorkerExecutor hashWorker;
  private final Map<Long, Account> accounts = new ConcurrentHashMap<>();
  private final SessionRegistry sessions;
  private final LoginThrottle throttle;
  private volatile String dummyHash;
  private volatile String setupCode;
  private volatile long setupExpiresMs;

  public AuthService(Vertx vertx, Db authDb, SettingsService settings, LongSupplier clock) {
    this.db = authDb;
    this.settings = settings;
    this.clock = clock;
    this.hashWorker = vertx.createSharedWorkerExecutor("airms-auth-hash", 2, 30, TimeUnit.SECONDS);
    this.sessions = new SessionRegistry(clock);
    this.throttle = new LoginThrottle(clock);
  }

  /** 載入帳號；還沒有任何帳號時產生一次性的設定碼（30 分鐘有效），回傳設定碼或 null。 */
  public Future<String> init() {
    return hashWorker.executeBlocking(() -> PasswordHasher.hash(SessionRegistry.random(24).toCharArray()), false)
        .compose(h -> {
          dummyHash = h;
          return db.run(AccountRepository::loadAll);
        })
        .map(list -> {
          list.forEach(a -> accounts.put(a.id(), a));
          if (list.isEmpty()) {
            setupCode = SessionRegistry.random(9);
            setupExpiresMs = clock.getAsLong() + SETUP_TTL_MS;
            return setupCode;
          }
          return null;
        });
  }

  public Future<Void> close() {
    return hashWorker.close();
  }

  private long idleMs() {
    return settings.intValue("auth.sessionIdleMinutes") * 60_000L;
  }

  /** 定期維護：清掉逾時 Session 與過期的 IP 計數。 */
  public void housekeeping() {
    sessions.purgeExpired(idleMs());
    throttle.purge();
  }

  // ---------------------------------------------------------------- 初次設定

  public boolean setupNeeded() {
    return accounts.isEmpty();
  }

  public Future<Account> setup(String code, String username, String displayName, String password, String ip) {
    long now = clock.getAsLong();
    if (!accounts.isEmpty()) return fail(409, "已經有管理者帳號，請直接登入");
    if (setupCode == null || now > setupExpiresMs) return fail(410, "設定碼已過期，請重新啟動程式取得新的設定碼");
    if (code == null || !constantEquals(code.trim(), setupCode)) {
      throttle.fail(ip);
      return fail(403, "設定碼錯誤");
    }
    String err = firstError(AccountRules.checkUsername(username), AccountRules.checkDisplayName(displayName),
        AccountRules.checkPassword(password, username));
    if (err != null) return fail(400, err);
    return hash(password).compose(h -> db.tx(c -> {
      if (AccountRepository.count(c) > 0) throw new AuthException(409, "已經有管理者帳號，請直接登入");
      long id = AccountRepository.insert(c, username, displayName.trim(), h, Account.Role.ADMIN, true, null, false,
          EnumSet.allOf(Permission.class), now);
      AuthLogRepository.audit(c, now, username, "SETUP_ADMIN", username, null, ip);
      return AccountRepository.load(c, id);
    })).map(a -> {
      accounts.put(a.id(), a);
      setupCode = null;
      log.info("已建立第一個管理者帳號：{}", a.username());
      return a;
    });
  }

  // ---------------------------------------------------------------- 登入、登出、驗證

  public Future<LoginOk> login(String username, String password, String ip, String userAgent) {
    long now = clock.getAsLong();
    String name = username == null ? "" : username.trim();
    if (throttle.blocked(ip)) {
      return logLogin(name, null, "IP_BLOCKED", ip, userAgent)
          .compose(v -> fail(429, "嘗試次數過多，請 10 分鐘後再試"));
    }
    if (name.isEmpty() || password == null || password.isEmpty() || password.length() > AccountRules.PASSWORD_MAX) {
      return fail(400, "請輸入帳號與密碼");
    }
    return db.run(c -> AccountRepository.findForLogin(c, name)).compose(cred -> {
      Account a = cred == null ? null : cred.account();
      boolean locked = a != null && a.locked(now);
      // 帳號不存在或已鎖定時，仍以假雜湊驗證一次，讓回應時間一致，避免從時間差猜出帳號是否存在。
      String hashToCheck = (a == null || locked) ? dummyHash : cred.passwordHash();
      // 紀錄用帳號的正式名稱（大小寫一致），帳號不存在時記下輸入的名稱。
      String logName = a == null ? name : a.username();
      return verify(hashToCheck, password).compose(ok -> {
        if (a == null) return rejected(name, null, "UNKNOWN_USER", ip, userAgent, "帳號或密碼錯誤");
        if (locked) return rejected(logName, a.id(), "LOCKED", ip, userAgent, "帳號或密碼錯誤，或帳號暫時鎖定");
        if (!ok) {
          int max = settings.intValue("auth.maxFailedLogins");
          long lockMs = settings.intValue("auth.lockMinutes") * 60_000L;
          return db.tx(c -> AccountRepository.recordFailure(c, a.id(), now, max, lockMs)).compose(updated -> {
            accounts.put(updated.id(), updated);
            String outcome = updated.locked(now) ? "BAD_PASSWORD_LOCKED" : "BAD_PASSWORD";
            return rejected(logName, a.id(), outcome, ip, userAgent, "帳號或密碼錯誤");
          });
        }
        // 密碼正確才告知帳號狀態，不會洩漏給不知道密碼的人。
        if (!a.enabled()) return rejected(logName, a.id(), "DISABLED", ip, userAgent, "此帳號已停用，請聯絡管理者");
        if (a.expired(now)) return rejected(logName, a.id(), "EXPIRED", ip, userAgent, "此帳號已過期，請聯絡管理者");
        return db.tx(c -> {
          AccountRepository.recordSuccess(c, a.id(), now);
          AuthLogRepository.login(c, now, logName, a.id(), "SUCCESS", ip, userAgent);
          return AccountRepository.load(c, a.id());
        }).map(fresh -> {
          accounts.put(fresh.id(), fresh);
          SessionRegistry.Created created = sessions.create(fresh.id(), ip, userAgent);
          return new LoginOk(created.token(), new Principal(created.session(), fresh));
        });
      });
    });
  }

  private <T> Future<T> rejected(String name, Long accountId, String outcome, String ip, String ua, String message) {
    throttle.fail(ip);
    return logLogin(name, accountId, outcome, ip, ua).compose(v -> fail(401, message));
  }

  private Future<Void> logLogin(String name, Long accountId, String outcome, String ip, String ua) {
    long now = clock.getAsLong();
    return db.run(c -> {
      AuthLogRepository.login(c, now, name, accountId, outcome, ip, ua);
      return null;
    });
  }

  /** 由 Cookie token 找出登入身分；帳號已停用或過期時一併登出。 */
  public Principal authenticate(String token) {
    SessionRegistry.Session s = sessions.touch(token, idleMs());
    if (s == null) return null;
    Account a = accounts.get(s.accountId());
    if (a == null || !a.active(clock.getAsLong())) {
      sessions.removeByToken(token);
      return null;
    }
    return new Principal(s, a);
  }

  public void logout(String token) {
    sessions.removeByToken(token);
  }

  public Future<Void> changeOwnPassword(Principal p, String current, String next, String ip) {
    String err = AccountRules.checkPassword(next, p.account().username());
    if (err != null) return fail(400, err);
    if (Objects.equals(current, next)) return fail(400, "新密碼不可與目前密碼相同");
    long id = p.account().id();
    return db.run(c -> AccountRepository.findForLogin(c, p.account().username()))
        .compose(cred -> verify(cred.passwordHash(), current == null ? "" : current))
        .compose(ok -> ok ? hash(next) : fail(400, "目前密碼不正確"))
        .compose(h -> db.tx(c -> {
          long now = clock.getAsLong();
          AccountRepository.setPassword(c, id, h, false, now);
          AuthLogRepository.audit(c, now, p.account().username(), "PASSWORD_CHANGE", p.account().username(), null, ip);
          return AccountRepository.load(c, id);
        }))
        .map(a -> {
          accounts.put(a.id(), a);
          sessions.removeAccount(a.id(), p.session().publicId()); // 其他裝置上的登入一併登出
          return null;
        });
  }

  // ---------------------------------------------------------------- 管理者操作

  public List<Account> listAccounts() {
    List<Account> list = new ArrayList<>(accounts.values());
    list.sort(Comparator.comparing(a -> a.username().toLowerCase()));
    return list;
  }

  public Future<Account> createAccount(Principal actor, String username, String displayName, Account.Role role,
                                       boolean enabled, Long expiresAtMs, Set<Permission> perms, String tempPassword,
                                       String ip) {
    String err = firstError(AccountRules.checkUsername(username), AccountRules.checkDisplayName(displayName),
        AccountRules.checkPassword(tempPassword, username));
    if (err != null) return fail(400, err);
    long now = clock.getAsLong();
    return hash(tempPassword).compose(h -> db.tx(c -> {
      if (AccountRepository.usernameTaken(c, username)) throw new AuthException(409, "帳號名稱已被使用");
      long id = AccountRepository.insert(c, username, displayName.trim(), h, role, enabled, expiresAtMs, true, perms, now);
      Account a = AccountRepository.load(c, id);
      AuthLogRepository.audit(c, now, actor.account().username(), "USER_CREATE", username, a.toJson(), ip);
      return a;
    })).map(a -> {
      accounts.put(a.id(), a);
      return a;
    });
  }

  public Future<Account> updateAccount(Principal actor, long id, String displayName, Account.Role role,
                                       boolean enabled, Long expiresAtMs, Set<Permission> perms, String ip) {
    Account before = accounts.get(id);
    if (before == null) return fail(404, "找不到這個帳號");
    String err = AccountRules.checkDisplayName(displayName);
    if (err != null) return fail(400, err);
    long now = clock.getAsLong();
    boolean stillActiveAdmin = role == Account.Role.ADMIN && enabled && (expiresAtMs == null || expiresAtMs > now);
    if (id == actor.account().id() && !stillActiveAdmin) {
      return fail(400, "不能停用自己、取消自己的管理者身分或設定自己已過期");
    }
    if (before.isAdmin() && before.active(now) && !stillActiveAdmin && otherActiveAdmins(id, now) == 0) {
      return fail(400, "至少要保留一個可用的管理者帳號");
    }
    return db.tx(c -> {
      AccountRepository.update(c, id, displayName.trim(), role, enabled, expiresAtMs, perms, now);
      Account after = AccountRepository.load(c, id);
      AuthLogRepository.audit(c, now, actor.account().username(), "USER_UPDATE", after.username(),
          new JsonObject().put("before", before.toJson()).put("after", after.toJson()), ip);
      return after;
    }).map(after -> {
      accounts.put(after.id(), after);
      if (!after.active(now)) sessions.removeAccount(after.id(), null);
      return after;
    });
  }

  public Future<Void> resetPassword(Principal actor, long id, String tempPassword, String ip) {
    Account target = accounts.get(id);
    if (target == null) return fail(404, "找不到這個帳號");
    if (id == actor.account().id()) return fail(400, "請到「變更密碼」修改自己的密碼");
    String err = AccountRules.checkPassword(tempPassword, target.username());
    if (err != null) return fail(400, err);
    return hash(tempPassword).compose(h -> db.tx(c -> {
      long now = clock.getAsLong();
      AccountRepository.setPassword(c, id, h, true, now);
      AuthLogRepository.audit(c, now, actor.account().username(), "PASSWORD_RESET", target.username(), null, ip);
      return AccountRepository.load(c, id);
    })).map(a -> {
      accounts.put(a.id(), a);
      sessions.removeAccount(a.id(), null);
      return null;
    });
  }

  public Future<Void> unlock(Principal actor, long id, String ip) {
    Account target = accounts.get(id);
    if (target == null) return fail(404, "找不到這個帳號");
    return db.tx(c -> {
      long now = clock.getAsLong();
      AccountRepository.unlock(c, id, now);
      AuthLogRepository.audit(c, now, actor.account().username(), "USER_UNLOCK", target.username(), null, ip);
      return AccountRepository.load(c, id);
    }).map(a -> {
      accounts.put(a.id(), a);
      return null;
    });
  }

  public JsonArray activeSessions() {
    sessions.purgeExpired(idleMs());
    return sessions.toJson(accounts);
  }

  public Future<Void> revokeSession(Principal actor, String publicId, String ip) {
    Long accountId = sessions.removeByPublicId(publicId);
    if (accountId == null) return fail(404, "這個登入已經結束");
    Account target = accounts.get(accountId);
    return audit(actor, "SESSION_REVOKE", target == null ? String.valueOf(accountId) : target.username(), null, ip);
  }

  public Future<Void> audit(Principal actor, String action, String target, JsonObject detail, String ip) {
    long now = clock.getAsLong();
    return db.run(c -> {
      AuthLogRepository.audit(c, now, actor.account().username(), action, target, detail, ip);
      return null;
    });
  }

  public Future<JsonArray> loginHistory(long fromMs, long toMs, String username, String outcome) {
    return db.run(c -> AuthLogRepository.logins(c, fromMs, toMs, username, outcome, 5000));
  }

  public Future<JsonArray> auditLog(long fromMs, long toMs) {
    return db.run(c -> AuthLogRepository.audits(c, fromMs, toMs, 5000));
  }

  // ---------------------------------------------------------------- 工具

  private long otherActiveAdmins(long exceptId, long now) {
    return accounts.values().stream().filter(a -> a.id() != exceptId && a.isAdmin() && a.active(now)).count();
  }

  private Future<String> hash(String password) {
    return hashWorker.executeBlocking(() -> PasswordHasher.hash(password.toCharArray()), false);
  }

  private Future<Boolean> verify(String hash, String password) {
    return hashWorker.executeBlocking(() -> PasswordHasher.verify(hash, password.toCharArray()), false);
  }

  private static <T> Future<T> fail(int status, String message) {
    return Future.failedFuture(new AuthException(status, message));
  }

  private static String firstError(String... errors) {
    for (String e : errors) if (e != null) return e;
    return null;
  }

  static boolean constantEquals(String a, String b) {
    return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
  }
}

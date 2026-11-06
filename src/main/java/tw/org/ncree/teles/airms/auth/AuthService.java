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
  /** 設定碼輸錯這麼多次就作廢（不依 IP 計算），要重新啟動程式取得新的設定碼。 */
  static final int SETUP_MAX_FAILURES = 10;
  private static final java.time.format.DateTimeFormatter TIME =
      java.time.format.DateTimeFormatter.ofPattern("MM/dd HH:mm").withZone(java.time.ZoneId.of("Asia/Taipei"));

  /** 一次請求的登入身分。 */
  public record Principal(SessionRegistry.Session session, Account account) { }

  public record LoginOk(String token, Principal principal) { }

  private final Db db;
  private final SettingsService settings;
  private final LongSupplier clock;
  private final WorkerExecutor hashWorker;
  private final Map<Long, Account> accounts = new ConcurrentHashMap<>();
  private final SessionRegistry sessions;
  private final LoginProtection protection = new LoginProtection();
  private int setupFailures;
  private volatile String dummyHash;
  private volatile String setupCode;
  private volatile long setupExpiresMs;

  public AuthService(Vertx vertx, Db authDb, SettingsService settings, LongSupplier clock) {
    this.db = authDb;
    this.settings = settings;
    this.clock = clock;
    this.hashWorker = vertx.createSharedWorkerExecutor("airms-auth-hash", 2, 30, TimeUnit.SECONDS);
    this.sessions = new SessionRegistry(clock);
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

  private int maxFailures() {
    return settings.intValue("auth.maxFailedLogins");
  }

  private long failureWindowMs() {
    return settings.intValue("auth.failureWindowSeconds") * 1000L;
  }

  private long cooldownMs() {
    return settings.intValue("auth.cooldownSeconds") * 1000L;
  }

  /** 定期維護：清掉逾時 Session 與已過計算期間的密碼錯誤紀錄。 */
  public void housekeeping() {
    sessions.purgeExpired(idleMs());
    protection.purge(clock.getAsLong(), failureWindowMs());
  }

  // ---------------------------------------------------------------- 初次設定

  public boolean setupNeeded() {
    return accounts.isEmpty();
  }

  public Future<Account> setup(String code, String username, String displayName, String password, String ip) {
    long now = clock.getAsLong();
    if (!accounts.isEmpty()) return fail(409, "已經有管理者帳號，請直接登入");
    if (setupCode == null || now > setupExpiresMs) return fail(410, "設定碼已失效，請重新啟動程式取得新的設定碼");
    if (code == null || !constantEquals(code.trim(), setupCode)) {
      synchronized (this) {
        if (++setupFailures >= SETUP_MAX_FAILURES) {
          setupCode = null;
          log.warn("設定碼輸錯 {} 次，已作廢；請重新啟動程式取得新的設定碼", SETUP_MAX_FAILURES);
          return fail(410, "設定碼錯誤次數過多已作廢，請重新啟動程式取得新的設定碼");
        }
      }
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
    return login(username, password, ip, userAgent, null);
  }

  /**
   * 登入。currentToken 是這個瀏覽器原本的登入 Cookie（可為 null）：重新登入時不算「已在別處登入」，成功後舊的那個會登出。
   * 規則（比照 EMS）：帳號名稱先檢查格式；同一帳號在計算期間內密碼錯誤達次數就冷卻，冷卻期間的嘗試不計次、不延長；
   * 不依來源 IP 鎖定。一般帳號同一時間只能登入一處，已在別處登入時拒絕新的登入；管理者不限。
   */
  public Future<LoginOk> login(String username, String password, String ip, String userAgent, String currentToken) {
    long now = clock.getAsLong();
    String name = LoginInput.normalize(username);
    if (name.isEmpty() || password == null || password.isEmpty()) return fail(400, "請輸入帳號與密碼");
    String inputError = LoginInput.check(name);
    if (inputError != null) {
      return logLogin(LoginInput.forLog(name), null, "INVALID_INPUT", ip, userAgent).compose(v -> fail(400, inputError));
    }
    if (password.length() > AccountRules.PASSWORD_MAX) return rejected(name, null, "BAD_PASSWORD", ip, userAgent);
    return db.run(c -> AccountRepository.findForLogin(c, name)).compose(cred -> {
      Account a = cred == null ? null : cred.account();
      boolean cooling = a != null && a.locked(now);
      // 帳號不存在或冷卻中時，仍以假雜湊驗證一次，讓回應時間一致，避免從時間差猜出帳號是否存在。
      String hashToCheck = (a == null || cooling) ? dummyHash : cred.passwordHash();
      // 紀錄用帳號的正式名稱（大小寫一致），帳號不存在時記下輸入的名稱。
      String logName = a == null ? name : a.username();
      return verify(hashToCheck, password).compose(ok -> {
        if (a == null) return rejected(name, null, "UNKNOWN_USER", ip, userAgent);
        // 冷卻中：不看密碼對錯、不計次、不延長
        if (cooling) return rejected(logName, a.id(), "LOCKED", ip, userAgent);
        if (!ok) {
          boolean startCooling = protection.recordFailure(a.id(), now, maxFailures(), failureWindowMs());
          Long coolUntil = startCooling ? now + cooldownMs() : null;
          return db.tx(c -> AccountRepository.recordFailure(c, a.id(), coolUntil)).compose(updated -> {
            accounts.put(updated.id(), updated);
            return rejected(logName, a.id(), startCooling ? "BAD_PASSWORD_LOCKED" : "BAD_PASSWORD", ip, userAgent);
          });
        }
        protection.clear(a.id());
        // 密碼正確才告知帳號狀態，不會洩漏給不知道密碼的人。
        if (!a.enabled()) return rejectWith(logName, a.id(), "DISABLED", ip, userAgent, 401, "此帳號已停用，請聯絡管理者");
        if (a.expired(now)) return rejectWith(logName, a.id(), "EXPIRED", ip, userAgent, 401, "此帳號已過期，請聯絡管理者");
        if (!a.isAdmin()) {
          SessionRegistry.Session other = sessions.otherActive(a.id(), currentToken, idleMs());
          if (other != null) return rejectWith(logName, a.id(), "ALREADY_LOGGED_IN", ip, userAgent, 409, alreadyText(other));
        }
        return db.tx(c -> {
          AccountRepository.recordSuccess(c, a.id(), now);
          AuthLogRepository.login(c, now, logName, a.id(), "SUCCESS", ip, userAgent);
          return AccountRepository.load(c, a.id());
        }).compose(fresh -> {
          accounts.put(fresh.id(), fresh);
          // 這個瀏覽器原本的登入（不論哪個帳號）換成新的，避免舊的留著佔住一般帳號
          if (currentToken != null) sessions.removeByToken(currentToken);
          SessionRegistry.Created created;
          if (fresh.isAdmin()) {
            created = sessions.create(fresh.id(), ip, userAgent);
          } else {
            SessionRegistry.Exclusive x = sessions.createExclusive(fresh.id(), ip, userAgent, idleMs(), currentToken);
            if (x.conflict() != null) {   // 兩個地方幾乎同時登入：後到的拒絕
              return rejectWith(logName, fresh.id(), "ALREADY_LOGGED_IN", ip, userAgent, 409, alreadyText(x.conflict()));
            }
            created = x.created();
          }
          return Future.succeededFuture(new LoginOk(created.token(), new Principal(created.session(), fresh)));
        });
      });
    });
  }

  /** 帳號或密碼錯誤（含帳號不存在、冷卻中）一律同一句話，不讓人從訊息猜出帳號是否存在或是否冷卻中。 */
  private String badCredentialText() {
    return "帳號或密碼錯誤。同一帳號 " + duration(failureWindowMs()) + "內錯誤 " + maxFailures() + " 次，需等待 "
        + duration(cooldownMs()) + "後再試。";
  }

  private String alreadyText(SessionRegistry.Session other) {
    return "此帳號已在其他地方登入（" + TIME.format(java.time.Instant.ofEpochMilli(other.createdAtMs())) + " 登入，來源 "
        + (other.ip() == null ? "不明" : other.ip()) + "），一般帳號同一時間只能登入一處。請先在那裡登出；若那邊已關閉，閒置 "
        + settings.intValue("auth.sessionIdleMinutes") + " 分鐘後會自動登出，或請管理者在「登入管理」強制登出。";
  }

  /** 300000 →「5 分鐘」、60000 →「60 秒」。 */
  static String duration(long ms) {
    long s = ms / 1000;
    return s >= 120 && s % 60 == 0 ? (s / 60) + " 分鐘" : s + " 秒";
  }

  private <T> Future<T> rejected(String name, Long accountId, String outcome, String ip, String ua) {
    return rejectWith(name, accountId, outcome, ip, ua, 401, badCredentialText());
  }

  private <T> Future<T> rejectWith(String name, Long accountId, String outcome, String ip, String ua, int status,
                                   String message) {
    return logLogin(name, accountId, outcome, ip, ua).compose(v -> fail(status, message));
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
      protection.clear(a.id());
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

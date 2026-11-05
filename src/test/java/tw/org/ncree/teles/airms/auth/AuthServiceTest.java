package tw.org.ncree.teles.airms.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import tw.org.ncree.teles.airms.config.AppConfig;
import tw.org.ncree.teles.airms.db.Db;
import tw.org.ncree.teles.airms.db.Schema;
import tw.org.ncree.teles.airms.settings.SettingsService;

/** 帳號服務的完整流程：初次設定、登入、鎖定、管理操作與 Session 撤銷。 */
class AuthServiceTest {
  private static final String IP = "10.1.1.1";
  private static final String ADMIN_PW = "Airms-Root-2026x";

  /** 一組測試環境；用完呼叫 close()。 */
  private static final class Env implements AutoCloseable {
    final Vertx vertx = Vertx.vertx();
    final AtomicLong now = new AtomicLong(1_790_000_000_000L);
    final Db airms;
    final Db authDb;
    final SettingsService settings;
    final AuthService auth;

    Env() throws Exception {
      Path dir = Files.createTempDirectory("airms-auth");
      airms = new Db(vertx, dir.resolve("airms.db"), "t-airms", Db.Durability.NORMAL);
      authDb = new Db(vertx, dir.resolve("auth.db"), "t-auth", Db.Durability.FULL);
      await(airms.run(c -> {
        Schema.migrateAirms(c);
        return null;
      }));
      await(authDb.run(c -> {
        Schema.migrateAuth(c);
        return null;
      }));
      AppConfig config = AppConfig.parse(new JsonObject(
          Files.readString(Path.of("config.example.json"), StandardCharsets.UTF_8)));
      settings = new SettingsService(airms, config);
      await(settings.load());
      auth = new AuthService(vertx, authDb, settings, now::get);
    }

    /** 建立第一個管理者並登入。 */
    AuthService.Principal admin() throws Exception {
      String code = await(auth.init());
      await(auth.setup(code, "admin", "系統管理者", ADMIN_PW, IP));
      return await(auth.login("admin", ADMIN_PW, IP, "test")).principal();
    }

    @Override
    public void close() throws Exception {
      await(auth.close());
      await(airms.close());
      await(authDb.close());
      await(vertx.close());
    }
  }

  static <T> T await(Future<T> f) throws Exception {
    try {
      return f.toCompletionStage().toCompletableFuture().get(60, TimeUnit.SECONDS);
    } catch (ExecutionException e) {
      if (e.getCause() instanceof Exception ex) throw ex;
      throw e;
    }
  }

  /** 預期失敗並回傳 HTTP 狀態碼。 */
  static int status(Future<?> f) throws Exception {
    try {
      await(f);
    } catch (AuthException e) {
      return e.status();
    }
    throw new AssertionError("預期失敗但成功了");
  }

  @Test
  void firstAdminViaSetupCodeThenLogin() throws Exception {
    try (Env env = new Env()) {
      String code = await(env.auth.init());
      assertNotNull(code);
      assertTrue(env.auth.setupNeeded());
      assertEquals(403, status(env.auth.setup("wrong", "admin", "系統管理者", ADMIN_PW, IP)));
      assertEquals(400, status(env.auth.setup(code, "admin", "系統管理者", "weak", IP)));
      Account a = await(env.auth.setup(code, "admin", "系統管理者", ADMIN_PW, IP));
      assertEquals(Account.Role.ADMIN, a.role());
      assertEquals(EnumSet.allOf(Permission.class), a.permissions());
      assertFalse(env.auth.setupNeeded());
      assertEquals(409, status(env.auth.setup(code, "admin2", "x", ADMIN_PW, IP)));

      assertEquals(401, status(env.auth.login("admin", "Wrong-pass-123", IP, "t")));
      assertEquals(401, status(env.auth.login("nobody", "Wrong-pass-123", IP, "t")));
      AuthService.LoginOk ok = await(env.auth.login("ADMIN", ADMIN_PW, IP, "t")); // 帳號不分大小寫
      AuthService.Principal p = env.auth.authenticate(ok.token());
      assertNotNull(p);
      assertEquals("admin", p.account().username());
      assertEquals(0, p.account().failedCount());
      assertNull(env.auth.authenticate("forged"));

      // 閒置超過設定時間（預設 30 分鐘）後失效
      env.now.addAndGet(31 * 60_000L);
      assertNull(env.auth.authenticate(ok.token()));

      // 重新啟動（新的 AuthService）：已有帳號就不再產生設定碼
      AuthService again = new AuthService(env.vertx, env.authDb, env.settings, env.now::get);
      assertNull(await(again.init()));
      await(again.close());

      JsonArray logins = await(env.auth.loginHistory(0, Long.MAX_VALUE, null, null));
      assertEquals(3, logins.size());
      assertEquals("SUCCESS", logins.getJsonObject(0).getString("outcome"));
      assertEquals("admin", logins.getJsonObject(0).getString("username")); // 記錄正式名稱
      assertEquals("UNKNOWN_USER", logins.getJsonObject(1).getString("outcome"));
    }
  }

  @Test
  void lockoutAfterRepeatedFailuresAndAdminUnlock() throws Exception {
    try (Env env = new Env()) {
      AuthService.Principal admin = env.admin();
      Account op = await(env.auth.createAccount(admin, "op1", "操作員", Account.Role.USER, true, null,
          Set.of(Permission.DATA_DOWNLOAD), "Temp-pass-001", IP));
      assertTrue(op.mustChangePassword());
      assertTrue(op.can(Permission.DATA_DOWNLOAD));
      assertFalse(op.can(Permission.AIRMS_CONTROL));
      assertEquals(409, status(env.auth.createAccount(admin, "OP1", "x", Account.Role.USER, true, null,
          Set.of(), "Temp-pass-001", IP)));

      for (int i = 0; i < 5; i++) assertEquals(401, status(env.auth.login("op1", "Wrong-pass-123", IP, "t")));
      // 已鎖定：正確密碼也不能登入
      assertEquals(401, status(env.auth.login("op1", "Temp-pass-001", IP, "t")));
      // 鎖定時間（預設 15 分鐘）過後自動解除
      env.now.addAndGet(16 * 60_000L);
      assertNotNull(await(env.auth.login("op1", "Temp-pass-001", IP, "t")).token());

      for (int i = 0; i < 5; i++) status(env.auth.login("op1", "Wrong-pass-123", IP, "t"));
      assertEquals(401, status(env.auth.login("op1", "Temp-pass-001", IP, "t")));
      await(env.auth.unlock(admin, op.id(), IP));
      assertNotNull(await(env.auth.login("op1", "Temp-pass-001", IP, "t")).token());
    }
  }

  @Test
  void ipIsThrottledAfterManyFailures() throws Exception {
    try (Env env = new Env()) {
      env.admin();
      for (int i = 0; i < LoginThrottle.MAX_FAILURES; i++) {
        status(env.auth.login("user" + i, "Wrong-pass-123", "10.9.9.9", "t"));
      }
      assertEquals(429, status(env.auth.login("admin", ADMIN_PW, "10.9.9.9", "t")));
      // 其他來源不受影響
      assertNotNull(await(env.auth.login("admin", ADMIN_PW, "10.9.9.8", "t")).token());
    }
  }

  @Test
  void adminCannotLockThemselvesOut() throws Exception {
    try (Env env = new Env()) {
      AuthService.Principal admin = env.admin();
      long id = admin.account().id();
      assertEquals(400, status(env.auth.updateAccount(admin, id, "系統管理者", Account.Role.USER, true, null,
          Set.of(), IP)));
      assertEquals(400, status(env.auth.updateAccount(admin, id, "系統管理者", Account.Role.ADMIN, false, null,
          Set.of(), IP)));
      assertEquals(400, status(env.auth.updateAccount(admin, id, "系統管理者", Account.Role.ADMIN, true,
          env.now.get() - 1, Set.of(), IP)));
      assertEquals(400, status(env.auth.resetPassword(admin, id, "Another-pass-01", IP)));
      assertEquals(404, status(env.auth.updateAccount(admin, 999, "x", Account.Role.USER, true, null, Set.of(), IP)));
      // 改顯示名稱可以
      assertEquals("管理者", await(env.auth.updateAccount(admin, id, "管理者", Account.Role.ADMIN, true, null,
          Set.of(), IP)).displayName());
    }
  }

  @Test
  void disablingOrResettingRevokesSessions() throws Exception {
    try (Env env = new Env()) {
      AuthService.Principal admin = env.admin();
      Account op = await(env.auth.createAccount(admin, "op1", "操作員", Account.Role.USER, true, null, Set.of(),
          "Temp-pass-001", IP));
      String token = await(env.auth.login("op1", "Temp-pass-001", IP, "t")).token();
      assertNotNull(env.auth.authenticate(token));

      await(env.auth.updateAccount(admin, op.id(), "操作員", Account.Role.USER, false, null, Set.of(), IP));
      assertNull(env.auth.authenticate(token));
      assertEquals(401, status(env.auth.login("op1", "Temp-pass-001", IP, "t"))); // 停用

      await(env.auth.updateAccount(admin, op.id(), "操作員", Account.Role.USER, true, null, Set.of(), IP));
      token = await(env.auth.login("op1", "Temp-pass-001", IP, "t")).token();
      await(env.auth.resetPassword(admin, op.id(), "Reset-pass-002", IP));
      assertNull(env.auth.authenticate(token));
      AuthService.LoginOk ok = await(env.auth.login("op1", "Reset-pass-002", IP, "t"));
      assertTrue(ok.principal().account().mustChangePassword());

      // 到期：到期後已登入的也失效
      await(env.auth.updateAccount(admin, op.id(), "操作員", Account.Role.USER, true, env.now.get() + 60_000, Set.of(),
          IP));
      assertNotNull(env.auth.authenticate(ok.token()));
      env.now.addAndGet(61_000);
      assertNull(env.auth.authenticate(ok.token()));

      JsonArray audit = await(env.auth.auditLog(0, Long.MAX_VALUE));
      assertEquals("USER_UPDATE", audit.getJsonObject(0).getString("action"));
      assertTrue(audit.getJsonObject(0).getJsonObject("detail").containsKey("before"));
    }
  }

  @Test
  void changeOwnPasswordKeepsCurrentSessionOnly() throws Exception {
    try (Env env = new Env()) {
      AuthService.Principal admin = env.admin();
      AuthService.LoginOk other = await(env.auth.login("admin", ADMIN_PW, "10.2.2.2", "other"));
      assertEquals(400, status(env.auth.changeOwnPassword(admin, "Wrong-pass-123", "Brand-new-pw-1", IP)));
      assertEquals(400, status(env.auth.changeOwnPassword(admin, ADMIN_PW, ADMIN_PW, IP)));
      assertEquals(400, status(env.auth.changeOwnPassword(admin, ADMIN_PW, "admin-in-pw-12", IP)));
      await(env.auth.changeOwnPassword(admin, ADMIN_PW, "Brand-new-pw-1", IP));
      assertNull(env.auth.authenticate(other.token()));
      assertEquals(1, env.auth.activeSessions().size());
      assertEquals(401, status(env.auth.login("admin", ADMIN_PW, IP, "t")));
      assertNotNull(await(env.auth.login("admin", "Brand-new-pw-1", IP, "t")).token());
    }
  }
}

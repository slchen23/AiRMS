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

  /** 預期失敗並回傳錯誤訊息。 */
  static String message(Future<?> f) throws Exception {
    try {
      await(f);
    } catch (AuthException e) {
      return e.getMessage();
    }
    throw new AssertionError("預期失敗但成功了");
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

      // 300 秒內錯 5 次 → 冷卻 60 秒（預設）
      for (int i = 0; i < 5; i++) {
        assertEquals(401, status(env.auth.login("op1", "Wrong-pass-123", IP, "t")));
        env.now.addAndGet(10_000);
      }
      // 冷卻中：正確密碼也不能登入，而且一直重試不會延長冷卻
      for (int i = 0; i < 4; i++) {
        assertEquals(401, status(env.auth.login("op1", "Temp-pass-001", IP, "t")));
        env.now.addAndGet(5_000);
      }
      env.now.addAndGet(29_000);                                   // 第 5 次錯誤後 59 秒：還在冷卻
      assertEquals(401, status(env.auth.login("op1", "Temp-pass-001", IP, "t")));
      env.now.addAndGet(1_000);                                    // 滿 60 秒：解除（中間的重試沒有延長）
      String token = await(env.auth.login("op1", "Temp-pass-001", IP, "t")).token();
      assertNotNull(token);
      env.auth.logout(token);

      // 錯誤分散在 300 秒以外不會冷卻
      for (int i = 0; i < 6; i++) {
        assertEquals(401, status(env.auth.login("op1", "Wrong-pass-123", IP, "t")));
        env.now.addAndGet(80_000);
      }
      token = await(env.auth.login("op1", "Temp-pass-001", IP, "t")).token();
      env.auth.logout(token);

      // 管理者可手動解除冷卻
      for (int i = 0; i < 5; i++) status(env.auth.login("op1", "Wrong-pass-123", IP, "t"));
      assertEquals(401, status(env.auth.login("op1", "Temp-pass-001", IP, "t")));
      await(env.auth.unlock(admin, op.id(), IP));
      assertNotNull(await(env.auth.login("op1", "Temp-pass-001", IP, "t")).token());

      JsonArray logins = await(env.auth.loginHistory(0, Long.MAX_VALUE, "op1", "BAD_PASSWORD_LOCKED"));
      assertEquals(2, logins.size());
    }
  }

  @Test
  void noIpLockAndLoginNameIsChecked() throws Exception {
    try (Env env = new Env()) {
      env.admin();
      // 同一來源亂試很多不同帳號，不會擋住同一來源的其他人（不依 IP 鎖定）
      for (int i = 0; i < 30; i++) status(env.auth.login("user" + i, "Wrong-pass-123", "127.0.0.1", "t"));
      assertNotNull(await(env.auth.login("admin", ADMIN_PW, "127.0.0.1", "t")).token());
      // 帳號名稱：超過 50 字或含控制字元直接拒絕，紀錄裡已清理
      assertEquals(400, status(env.auth.login("a".repeat(51), "x", IP, "t")));
      assertEquals(400, status(env.auth.login("adm\u0000in", "x", IP, "t")));
      assertEquals(400, status(env.auth.login("  ", "x", IP, "t")));
      JsonArray bad = await(env.auth.loginHistory(0, Long.MAX_VALUE, null, "INVALID_INPUT"));
      assertEquals(2, bad.size());
      assertTrue(bad.stream().map(o -> ((JsonObject) o).getString("username")).anyMatch("adm?in"::equals));
      // 帳號不存在、密碼錯、冷卻中都是同一句話
      String unknown = message(env.auth.login("nobody", "Wrong-pass-123", IP, "t"));
      String wrong = message(env.auth.login("admin", "Wrong-pass-123", IP, "t"));
      assertEquals(unknown, wrong);
      assertTrue(wrong.contains("5 分鐘內錯誤 5 次") && wrong.contains("60 秒"), wrong);
    }
  }

  @Test
  void normalAccountCanOnlyBeLoggedInOnceAdminUnlimited() throws Exception {
    try (Env env = new Env()) {
      AuthService.Principal admin = env.admin();
      await(env.auth.createAccount(admin, "op1", "操作員", Account.Role.USER, true, null, Set.of(), "Temp-pass-001", IP));
      AuthService.LoginOk first = await(env.auth.login("op1", "Temp-pass-001", "10.0.0.1", "pc1"));
      // 第二台：拒絕，訊息說明在哪裡登入
      String msg = message(env.auth.login("op1", "Temp-pass-001", "10.0.0.2", "pc2"));
      assertTrue(msg.contains("已在其他地方登入") && msg.contains("10.0.0.1"), msg);
      assertEquals(409, status(env.auth.login("op1", "Temp-pass-001", "10.0.0.2", "pc2")));
      assertNotNull(env.auth.authenticate(first.token()));                // 原本的不受影響
      // 密碼錯時不透露是否已登入
      assertEquals(401, status(env.auth.login("op1", "Wrong-pass-123", "10.0.0.2", "pc2")));
      // 同一個瀏覽器重新登入可以，舊的換成新的
      AuthService.LoginOk again = await(env.auth.login("op1", "Temp-pass-001", "10.0.0.1", "pc1", first.token()));
      assertNull(env.auth.authenticate(first.token()));
      // 登出後，別處就能登入
      env.auth.logout(again.token());
      AuthService.LoginOk pc2 = await(env.auth.login("op1", "Temp-pass-001", "10.0.0.2", "pc2"));
      // 管理者強制登出後也可以
      String pid = pc2.principal().session().publicId();
      await(env.auth.revokeSession(admin, pid, IP));
      assertNotNull(await(env.auth.login("op1", "Temp-pass-001", "10.0.0.3", "pc3")).token());
      // 閒置逾時（預設 30 分鐘）後也可以
      env.now.addAndGet(31 * 60_000L);
      assertNotNull(await(env.auth.login("op1", "Temp-pass-001", "10.0.0.4", "pc4")).token());
      // 管理者不限
      assertNotNull(await(env.auth.login("admin", ADMIN_PW, "10.0.0.5", "x")).token());
      assertNotNull(await(env.auth.login("admin", ADMIN_PW, "10.0.0.6", "y")).token());
      assertEquals(2, await(env.auth.loginHistory(0, Long.MAX_VALUE, "op1", "ALREADY_LOGGED_IN")).size());
    }
  }

  @Test
  void setupCodeIsVoidedAfterTooManyWrongTries() throws Exception {
    try (Env env = new Env()) {
      String code = await(env.auth.init());
      for (int i = 0; i < AuthService.SETUP_MAX_FAILURES - 1; i++) {
        assertEquals(403, status(env.auth.setup("wrong" + i, "admin", "系統管理者", ADMIN_PW, IP)));
      }
      assertEquals(410, status(env.auth.setup("wrong-last", "admin", "系統管理者", ADMIN_PW, IP)));
      assertEquals(410, status(env.auth.setup(code, "admin", "系統管理者", ADMIN_PW, IP)));  // 正確的也不行了
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

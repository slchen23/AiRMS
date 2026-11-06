package tw.org.ncree.teles.airms.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** 帳號規則、密碼雜湊、Session 期限與來源 IP 限制。 */
class AuthRulesTest {

  @Test
  void usernameAndDisplayNameRules() {
    assertNull(AccountRules.checkUsername("admin"));
    assertNull(AccountRules.checkUsername("op.user_1-a"));
    assertNotNull(AccountRules.checkUsername("ab"));
    assertNotNull(AccountRules.checkUsername("has space"));
    assertNotNull(AccountRules.checkUsername("中文帳號"));
    assertNotNull(AccountRules.checkUsername(null));
    assertNull(AccountRules.checkDisplayName("值班人員"));
    assertNotNull(AccountRules.checkDisplayName("  "));
    assertNotNull(AccountRules.checkDisplayName("a\nb"));
    assertNotNull(AccountRules.checkDisplayName("x".repeat(41)));
  }

  @Test
  void passwordRules() {
    assertNull(AccountRules.checkPassword("Airms-2026x", "admin"));
    assertNotNull(AccountRules.checkPassword("short1a", "admin"));            // 太短
    assertNotNull(AccountRules.checkPassword("onlyletterspass", "admin"));    // 沒有數字
    assertNotNull(AccountRules.checkPassword("1234567890", "admin"));         // 沒有字母
    assertNotNull(AccountRules.checkPassword("my-Admin-2026", "admin"));      // 含帳號（不分大小寫）
    assertNotNull(AccountRules.checkPassword("a1".repeat(65), "admin"));      // 太長
    assertNotNull(AccountRules.checkPassword(null, "admin"));
  }

  @Test
  void passwordHashRoundTrip() {
    String h = PasswordHasher.hash("Airms-2026x".toCharArray());
    assertTrue(h.startsWith("$argon2id$"));
    assertTrue(PasswordHasher.validHash(h));
    assertTrue(PasswordHasher.verify(h, "Airms-2026x".toCharArray()));
    assertFalse(PasswordHasher.verify(h, "Airms-2026y".toCharArray()));
    assertFalse(PasswordHasher.validHash("plain-text"));
    // 同一密碼每次雜湊結果不同（隨機 salt）
    assertFalse(h.equals(PasswordHasher.hash("Airms-2026x".toCharArray())));
  }

  @Test
  void sessionIdleAndAbsoluteTimeout() {
    AtomicLong now = new AtomicLong(1_000_000);
    SessionRegistry r = new SessionRegistry(now::get);
    long idle = 30 * 60_000L;
    SessionRegistry.Created a = r.create(1, "10.0.0.1", "ua");
    assertNotNull(r.touch(a.token(), idle));
    assertNull(r.touch("not-a-token", idle));
    assertNull(r.touch(null, idle));
    // 持續操作（每 20 分鐘一次）可一直延長，但登入滿 12 小時就失效
    long step = 20 * 60_000L;
    while (now.get() + step - 1_000_000 <= SessionRegistry.ABSOLUTE_MAX_MS) {
      now.addAndGet(step);
      assertNotNull(r.touch(a.token(), idle));
    }
    now.addAndGet(step);
    assertNull(r.touch(a.token(), idle));
    // 閒置超過就失效
    SessionRegistry.Created b = r.create(1, "10.0.0.1", "ua");
    now.addAndGet(idle + 1);
    assertNull(r.touch(b.token(), idle));
  }

  @Test
  void sessionRevocation() {
    AtomicLong now = new AtomicLong(0);
    SessionRegistry r = new SessionRegistry(now::get);
    long idle = 60_000L;
    SessionRegistry.Created a1 = r.create(1, null, null);
    SessionRegistry.Created a2 = r.create(1, null, null);
    SessionRegistry.Created b = r.create(2, null, null);
    // token 與公開編號、CSRF token 都不同
    assertFalse(a1.token().equals(a1.session().publicId()));
    assertFalse(a1.token().equals(a1.session().csrfToken()));
    assertEquals(1, r.removeAccount(1, a1.session().publicId()));
    assertNotNull(r.touch(a1.token(), idle));
    assertNull(r.touch(a2.token(), idle));
    assertEquals(Long.valueOf(2), r.removeByPublicId(b.session().publicId()));
    assertNull(r.removeByPublicId(b.session().publicId()));
    assertNull(r.touch(b.token(), idle));
    assertEquals(1, r.toJson(java.util.Map.of()).size());
  }

  @Test
  void loginNameIsLimitedAndCleaned() {
    assertEquals("admin", LoginInput.normalize("  admin \u3000"));
    assertEquals("", LoginInput.normalize(null));
    assertNull(LoginInput.check("admin"));
    assertNull(LoginInput.check("a".repeat(50)));                       // 剛好 50 個字
    assertNotNull(LoginInput.check("a".repeat(51)));
    assertNotNull(LoginInput.check("ad\u0000min"));                    // 控制字元
    assertNotNull(LoginInput.check("ad\u001bmin"));                    // ESC
    assertNotNull(LoginInput.check("ad\u200Bmin"));                    // 零寬空白（格式控制）
    assertNotNull(LoginInput.check("ad\u202Emin"));                    // 方向覆寫（格式控制）
    assertNotNull(LoginInput.check("ad\u2028min"));                    // 行分隔
    assertNotNull(LoginInput.check("ad\uD800min"));                    // 落單代理字元
    assertNotNull(LoginInput.check("ad\uE000min"));                    // 私用區
    assertNull(LoginInput.check("管理者"));                              // 一般文字可輸入（只是不會有這種帳號）
    // 紀錄用：不允許的字元換成 ?，過長截斷
    assertEquals("ad?min", LoginInput.forLog("ad\u0000min"));
    assertEquals("a".repeat(50) + "…", LoginInput.forLog("a".repeat(80)));
    assertEquals("x".repeat(49) + "…", LoginInput.forLog("x".repeat(49) + "\uD83D\uDE00"));  // 不切斷表情符號
  }

  @Test
  void accountCooldownCountsFailuresInSlidingWindow() {
    LoginProtection p = new LoginProtection();
    long w = 300_000;
    // 300 秒內第 5 次才冷卻
    for (int i = 0; i < 4; i++) assertFalse(p.recordFailure(1, i * 10_000L, 5, w));
    assertTrue(p.recordFailure(1, 40_000, 5, w));
    assertEquals(0, p.count(1, 40_000, w));                             // 開始冷卻後重新計算
    // 分散在 300 秒以外的不算：0、100、200、300 秒各一次，第 350 秒時視窗內只有 4 次
    for (long t : new long[] {1_000_000, 1_100_000, 1_200_000, 1_300_000}) assertFalse(p.recordFailure(2, t, 5, w));
    assertFalse(p.recordFailure(2, 1_350_000, 5, w));
    assertTrue(p.recordFailure(2, 1_360_000, 5, w));                    // 100～360 秒共 5 次
    // 帳號互不影響、成功或解除時清除
    assertFalse(p.recordFailure(3, 0, 5, w));
    assertEquals(1, p.count(3, 0, w));
    p.clear(3);
    assertEquals(0, p.count(3, 0, w));
    p.recordFailure(4, 0, 5, w);
    p.purge(w, w);
    assertEquals(0, p.count(4, w, w));
  }

  @Test
  void normalAccountGetsOnlyOneSession() {
    AtomicLong now = new AtomicLong(1_000_000);
    SessionRegistry r = new SessionRegistry(now::get);
    long idle = 30 * 60_000L;
    SessionRegistry.Exclusive first = r.createExclusive(7, "10.0.0.1", "a", idle, null);
    assertNotNull(first.created());
    SessionRegistry.Exclusive second = r.createExclusive(7, "10.0.0.2", "b", idle, null);
    assertNull(second.created());
    assertEquals("10.0.0.1", second.conflict().ip());
    // 同一個瀏覽器（帶著原本的登入）重新登入不算別處
    assertNotNull(r.createExclusive(7, "10.0.0.1", "a", idle, first.created().token()).created());
    // 原本的登入閒置逾時後，別處可以登入
    SessionRegistry r2 = new SessionRegistry(now::get);
    r2.createExclusive(8, "10.0.0.1", "a", idle, null);
    now.addAndGet(idle + 1);
    assertNotNull(r2.createExclusive(8, "10.0.0.2", "b", idle, null).created());
  }
}

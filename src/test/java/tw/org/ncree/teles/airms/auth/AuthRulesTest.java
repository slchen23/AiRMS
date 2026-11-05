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
  void ipThrottleBlocksAfterTwentyFailuresInTenMinutes() {
    AtomicLong now = new AtomicLong(0);
    LoginThrottle t = new LoginThrottle(now::get);
    for (int i = 0; i < LoginThrottle.MAX_FAILURES - 1; i++) t.fail("10.0.0.9");
    assertFalse(t.blocked("10.0.0.9"));
    t.fail("10.0.0.9");
    assertTrue(t.blocked("10.0.0.9"));
    assertFalse(t.blocked("10.0.0.10"));
    now.addAndGet(LoginThrottle.WINDOW_MS + 1);
    assertFalse(t.blocked("10.0.0.9"));
    t.purge();
  }
}

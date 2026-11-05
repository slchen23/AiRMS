package tw.org.ncree.teles.airms.auth;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 登入中的 Session（只在記憶體，重啟後需重新登入）。
 * Cookie 只放隨機 token；記憶體裡用 token 的 SHA-256 當鍵，另給每個 Session 一個公開編號供管理頁強制登出，
 * 公開編號不能拿來登入。閒置逾時與最長 12 小時兩種期限。
 */
public final class SessionRegistry {
  public static final long ABSOLUTE_MAX_MS = 12L * 60 * 60 * 1000;
  private static final SecureRandom RANDOM = new SecureRandom();

  public static final class Session {
    final String publicId;
    final long accountId;
    final String csrfToken;
    final long createdAtMs;
    volatile long lastSeenMs;
    final String ip;
    final String userAgent;

    Session(String publicId, long accountId, String csrfToken, long nowMs, String ip, String userAgent) {
      this.publicId = publicId;
      this.accountId = accountId;
      this.csrfToken = csrfToken;
      this.createdAtMs = nowMs;
      this.lastSeenMs = nowMs;
      this.ip = ip;
      this.userAgent = userAgent;
    }

    public String publicId() {
      return publicId;
    }

    public long accountId() {
      return accountId;
    }

    public String csrfToken() {
      return csrfToken;
    }
  }

  /** 新建立的 Session 與要放進 Cookie 的 token（token 只在這裡出現一次）。 */
  public record Created(String token, Session session) { }

  private final Map<String, Session> byTokenHash = new ConcurrentHashMap<>();
  private final LongSupplier clock;

  public SessionRegistry(LongSupplier clock) {
    this.clock = clock;
  }

  public Created create(long accountId, String ip, String userAgent) {
    String token = random(32);
    Session s = new Session(random(12), accountId, random(24), clock.getAsLong(), ip, userAgent);
    byTokenHash.put(hash(token), s);
    return new Created(token, s);
  }

  /** 找到且未逾時就更新最後活動時間並回傳；逾時則移除。 */
  public Session touch(String token, long idleTimeoutMs) {
    if (token == null || token.isEmpty() || token.length() > 100) return null;
    String key = hash(token);
    Session s = byTokenHash.get(key);
    if (s == null) return null;
    long now = clock.getAsLong();
    if (now - s.lastSeenMs > idleTimeoutMs || now - s.createdAtMs > ABSOLUTE_MAX_MS) {
      byTokenHash.remove(key);
      return null;
    }
    s.lastSeenMs = now;
    return s;
  }

  public void removeByToken(String token) {
    if (token != null) byTokenHash.remove(hash(token));
  }

  /** 管理頁強制登出；回傳被登出的帳號編號，找不到回 null。 */
  public Long removeByPublicId(String publicId) {
    for (var e : byTokenHash.entrySet()) {
      if (e.getValue().publicId.equals(publicId)) {
        byTokenHash.remove(e.getKey());
        return e.getValue().accountId;
      }
    }
    return null;
  }

  /** 帳號被停用、改密碼等情況：登出該帳號的所有 Session（可保留一個，例如自己改密碼時的目前 Session）。 */
  public int removeAccount(long accountId, String keepPublicId) {
    int n = 0;
    for (var e : byTokenHash.entrySet()) {
      Session s = e.getValue();
      if (s.accountId == accountId && !s.publicId.equals(keepPublicId)) {
        byTokenHash.remove(e.getKey());
        n++;
      }
    }
    return n;
  }

  public void purgeExpired(long idleTimeoutMs) {
    long now = clock.getAsLong();
    byTokenHash.entrySet().removeIf(e -> now - e.getValue().lastSeenMs > idleTimeoutMs
        || now - e.getValue().createdAtMs > ABSOLUTE_MAX_MS);
  }

  public JsonArray toJson(Map<Long, Account> accounts) {
    List<Session> list = new ArrayList<>(byTokenHash.values());
    list.sort(Comparator.comparingLong((Session s) -> s.lastSeenMs).reversed());
    JsonArray out = new JsonArray();
    for (Session s : list) {
      Account a = accounts.get(s.accountId);
      out.add(new JsonObject().put("id", s.publicId).put("accountId", s.accountId)
          .put("username", a == null ? "?" : a.username()).put("displayName", a == null ? "" : a.displayName())
          .put("createdAt", s.createdAtMs).put("lastSeenAt", s.lastSeenMs).put("ip", s.ip).put("userAgent", s.userAgent));
    }
    return out;
  }

  static String random(int bytes) {
    byte[] b = new byte[bytes];
    RANDOM.nextBytes(b);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
  }

  static String hash(String token) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}

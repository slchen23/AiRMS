package tw.org.ncree.teles.airms.auth;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 依來源 IP 限制登入嘗試：10 分鐘內失敗 20 次就暫停該 IP 10 分鐘。
 * 與「帳號鎖定」互補：帳號鎖定擋針對單一帳號猜密碼，這裡擋同一來源大量試不同帳號。
 */
public final class LoginThrottle {
  static final long WINDOW_MS = 10 * 60 * 1000L;
  static final int MAX_FAILURES = 20;

  private final Map<String, Deque<Long>> failures = new ConcurrentHashMap<>();
  private final LongSupplier clock;

  public LoginThrottle(LongSupplier clock) {
    this.clock = clock;
  }

  public boolean blocked(String ip) {
    Deque<Long> d = failures.get(key(ip));
    if (d == null) return false;
    synchronized (d) {
      prune(d);
      return d.size() >= MAX_FAILURES;
    }
  }

  public void fail(String ip) {
    Deque<Long> d = failures.computeIfAbsent(key(ip), k -> new ArrayDeque<>());
    synchronized (d) {
      prune(d);
      d.addLast(clock.getAsLong());
    }
  }

  /** 定期清掉已過期的紀錄，避免記憶體累積。 */
  public void purge() {
    failures.entrySet().removeIf(e -> {
      synchronized (e.getValue()) {
        prune(e.getValue());
        return e.getValue().isEmpty();
      }
    });
  }

  private void prune(Deque<Long> d) {
    long cutoff = clock.getAsLong() - WINDOW_MS;
    while (!d.isEmpty() && d.peekFirst() < cutoff) d.removeFirst();
  }

  private static String key(String ip) {
    return ip == null ? "?" : ip;
  }
}

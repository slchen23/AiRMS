package tw.org.ncree.teles.airms.auth;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * 同一帳號的密碼錯誤計數（比照 EMS）：在「計算期間」（預設 300 秒）內錯誤達到次數（預設 5 次）就開始冷卻
 * （預設 60 秒）。冷卻期間的嘗試不檢查密碼、不計次、不延長冷卻。不依來源 IP 鎖定。
 * 這裡只記最近的錯誤時間（記憶體，重啟後歸零）；冷卻到期時間存在 auth.db（locked_until_ms），管理頁看得到、可手動解除。
 */
public final class LoginProtection {
  private final Map<Long, Deque<Long>> failures = new HashMap<>();

  /** 記一次密碼錯誤；回傳 true 表示達到次數，應從現在開始冷卻（同時清掉計數，冷卻結束後重新計算）。 */
  public synchronized boolean recordFailure(long accountId, long nowMs, int maxFailures, long windowMs) {
    Deque<Long> q = failures.computeIfAbsent(accountId, k -> new ArrayDeque<>());
    while (!q.isEmpty() && nowMs - q.peekFirst() >= windowMs) q.pollFirst();
    q.addLast(nowMs);
    if (q.size() >= maxFailures) {
      failures.remove(accountId);
      return true;
    }
    return false;
  }

  /** 計算期間內目前累計的錯誤次數（測試與顯示用）。 */
  public synchronized int count(long accountId, long nowMs, long windowMs) {
    Deque<Long> q = failures.get(accountId);
    if (q == null) return 0;
    return (int) q.stream().filter(t -> nowMs - t < windowMs).count();
  }

  /** 登入成功或管理者解除時清除。 */
  public synchronized void clear(long accountId) {
    failures.remove(accountId);
  }

  /** 定期清掉已超過計算期間的紀錄。 */
  public synchronized void purge(long nowMs, long windowMs) {
    failures.values().removeIf(q -> {
      while (!q.isEmpty() && nowMs - q.peekFirst() >= windowMs) q.pollFirst();
      return q.isEmpty();
    });
  }
}

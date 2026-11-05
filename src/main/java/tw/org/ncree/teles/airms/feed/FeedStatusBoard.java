package tw.org.ncree.teles.airms.feed;

import io.vertx.core.json.JsonObject;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * 各來源最近一次抓取的狀態（記憶體內，重啟後重新累積）。
 * 對外只提供摘要：不含錯誤細節、網址或檔案路徑。
 */
public final class FeedStatusBoard {

  public enum Outcome { OK, PARTIAL, UNCHANGED, FETCH_FAILED, REJECTED, ARCHIVE_FAILED, DB_FAILED }

  private static final class Entry {
    Instant lastAttemptAt;
    Instant lastSuccessAt;
    String lastFeedTime;
    Outcome lastOutcome;
    long consecutiveFailures;
  }

  private final Map<FeedKind, Entry> entries = new EnumMap<>(FeedKind.class);

  public synchronized void record(FeedKind kind, Instant at, Outcome outcome, String feedTimeRaw) {
    Entry e = entries.computeIfAbsent(kind, k -> new Entry());
    e.lastAttemptAt = at;
    e.lastOutcome = outcome;
    boolean success = outcome == Outcome.OK || outcome == Outcome.PARTIAL || outcome == Outcome.UNCHANGED;
    if (success) {
      e.lastSuccessAt = at;
      e.consecutiveFailures = 0;
      if (feedTimeRaw != null) e.lastFeedTime = feedTimeRaw;
    } else {
      e.consecutiveFailures++;
    }
  }

  /** 三個來源的摘要；停用的來源標示 DISABLED，不影響整體狀態。 */
  public synchronized JsonObject summary(Set<FeedKind> enabled) {
    JsonObject out = new JsonObject();
    for (FeedKind k : FeedKind.values()) {
      Entry e = entries.get(k);
      JsonObject o = new JsonObject().put("label", k.label());
      if (!enabled.contains(k)) {
        o.put("state", "DISABLED");
      } else if (e == null) {
        o.put("state", "WARMING_UP");
      } else {
        o.put("state", e.consecutiveFailures == 0 ? "OK" : "DEGRADED")
            .put("lastOutcome", e.lastOutcome.name())
            .put("lastAttemptAt", e.lastAttemptAt.toString())
            .put("lastSuccessAt", e.lastSuccessAt == null ? null : e.lastSuccessAt.toString())
            .put("lastFeedTime", e.lastFeedTime)
            .put("consecutiveFailures", e.consecutiveFailures);
      }
      out.put(k.key(), o);
    }
    return out;
  }
}

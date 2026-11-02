package tw.org.ncree.teles.airms.retention;

import io.vertx.core.Future;
import io.vertx.core.VerticleBase;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import tw.org.ncree.teles.airms.AppContext;
import tw.org.ncree.teles.airms.config.AppConfig;

/**
 * 依設定天數清除 airms.db 的路段/RMS 歷史與抓取紀錄。VD（vd_health.db）與原始 XML 永久保存，這裡不碰。
 * 一次只刪一天份的資料、各自一個短交易，不會長時間卡住抓取寫入。
 */
public final class RetentionVerticle extends VerticleBase {
  private static final Logger log = LogManager.getLogger(RetentionVerticle.class);
  private static final long FIRST_RUN_DELAY_MS = Duration.ofMinutes(10).toMillis();
  private static final long DAY_MS = Duration.ofDays(1).toMillis();

  private final AppContext app;
  private long timerId = -1;
  private boolean stopping;
  private Future<Void> inFlight = Future.succeededFuture();

  public RetentionVerticle(AppContext app) {
    this.app = app;
  }

  @Override
  public Future<?> start() {
    schedule(FIRST_RUN_DELAY_MS);
    return Future.succeededFuture();
  }

  @Override
  public Future<?> stop() {
    stopping = true;
    vertx.cancelTimer(timerId);
    return inFlight.recover(e -> Future.succeededFuture());
  }

  private void schedule(long delay) {
    if (stopping) return;
    timerId = vertx.setTimer(delay, id -> {
      inFlight = runOnce().onFailure(e -> log.error("資料保留清除失敗", e))
          .recover(e -> Future.succeededFuture())
          .onComplete(v -> schedule(Duration.ofHours(app.config().retention().runEveryHours()).toMillis()));
    });
  }

  public Future<Void> runOnce() {
    AppConfig.Retention r = app.config().retention();
    long now = app.clock().millis();
    return purgeByDay("section_history", "feed_time_ms", now - r.sectionHistoryDays() * DAY_MS, 0)
        .compose(n -> logDeleted("路段歷史", n))
        .compose(v -> purgeByDay("rms_history", "feed_time_ms", now - r.rmsHistoryDays() * DAY_MS, 0))
        .compose(n -> logDeleted("RMS 歷史", n))
        .compose(v -> purgeByDay("feed_attempt", "started_at_ms", now - r.feedAttemptDays() * DAY_MS, 0))
        .compose(n -> logDeleted("抓取紀錄", n));
  }

  private Future<Void> logDeleted(String what, long n) {
    if (n > 0) log.info("資料保留：刪除{} {} 筆", what, n);
    return Future.succeededFuture();
  }

  /** 從最舊的一天開始，一天一個交易刪到 cutoff 為止。表名與欄名都是程式內固定字串。 */
  private Future<Long> purgeByDay(String table, String timeColumn, long cutoffMs, long deletedSoFar) {
    if (stopping) return Future.succeededFuture(deletedSoFar);
    return app.airmsDb().tx(c -> {
      long oldest;
      try (PreparedStatement ps = c.prepareStatement("SELECT MIN(" + timeColumn + ") FROM " + table);
           ResultSet rs = ps.executeQuery()) {
        rs.next();
        oldest = rs.getLong(1);
        if (rs.wasNull() || oldest >= cutoffMs) return -1L;
      }
      long sliceEnd = Math.min(cutoffMs, oldest + DAY_MS);
      try (PreparedStatement del = c.prepareStatement("DELETE FROM " + table + " WHERE " + timeColumn + " < ?")) {
        del.setLong(1, sliceEnd);
        return (long) del.executeUpdate();
      }
    }).compose(n -> n < 0 ? Future.succeededFuture(deletedSoFar)
        : purgeByDay(table, timeColumn, cutoffMs, deletedSoFar + n));
  }
}

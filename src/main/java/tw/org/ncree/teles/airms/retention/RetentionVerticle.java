package tw.org.ncree.teles.airms.retention;

import io.vertx.core.Future;
import io.vertx.core.VerticleBase;
import java.time.Duration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** 定時執行資料保留：啟動後 10 分鐘第一次，之後每 runEveryHours 小時（config.json）。 */
public final class RetentionVerticle extends VerticleBase {
  private static final Logger log = LogManager.getLogger(RetentionVerticle.class);
  private static final long FIRST_RUN_DELAY_MS = Duration.ofMinutes(10).toMillis();

  private final RetentionService service;
  private final long intervalMs;
  private long timerId = -1;
  private boolean stopping;
  private Future<?> inFlight = Future.succeededFuture();

  public RetentionVerticle(RetentionService service, int runEveryHours) {
    this.service = service;
    this.intervalMs = Duration.ofHours(runEveryHours).toMillis();
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
    return inFlight.recover(e -> Future.succeededFuture()).eventually(service::close);
  }

  private void schedule(long delay) {
    if (stopping) return;
    timerId = vertx.setTimer(delay, id -> {
      inFlight = service.runOnce().onFailure(e -> log.error("資料保留清除失敗", e))
          .recover(e -> Future.succeededFuture())
          .onComplete(v -> schedule(intervalMs));
    });
  }
}

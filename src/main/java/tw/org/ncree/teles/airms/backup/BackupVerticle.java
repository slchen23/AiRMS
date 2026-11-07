package tw.org.ncree.teles.airms.backup;

import io.vertx.core.Future;
import io.vertx.core.VerticleBase;
import java.time.Clock;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** 每 10 分鐘檢查一次是否該備份（每日時間已過、今天尚未成功）；備份未啟用時什麼都不做。 */
public final class BackupVerticle extends VerticleBase {
  private static final Logger log = LogManager.getLogger(BackupVerticle.class);
  static final long CHECK_MS = 10 * 60_000L;
  static final long FIRST_CHECK_MS = 2 * 60_000L;

  private final BackupService service;
  private final Clock clock;
  private long timerId = -1;
  private boolean stopping;
  private Future<?> inFlight = Future.succeededFuture();

  public BackupVerticle(BackupService service, Clock clock) {
    this.service = service;
    this.clock = clock;
  }

  @Override
  public Future<?> start() {
    if (!service.enabled()) {
      log.info("備份未啟用（config.json 的 backup.enabled）");
      return Future.succeededFuture();
    }
    schedule(FIRST_CHECK_MS);
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
      if (service.due(clock.millis())) {
        inFlight = service.runOnce().recover(e -> Future.succeededFuture()).onComplete(v -> schedule(CHECK_MS));
      } else {
        schedule(CHECK_MS);
      }
    });
  }
}

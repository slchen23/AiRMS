package tw.org.ncree.teles.airms.view;

import io.vertx.core.Future;
import io.vertx.core.VerticleBase;

/** 每 15 秒整理一次即時監視資料（來源每 1～5 分鐘更新，15 秒足以讓畫面跟上）。 */
public final class LiveViewVerticle extends VerticleBase {
  static final long INTERVAL_MS = 15_000;

  private final LiveViewService service;
  private long timerId = -1;
  private boolean stopping;
  private Future<?> inFlight = Future.succeededFuture();

  public LiveViewVerticle(LiveViewService service) {
    this.service = service;
  }

  @Override
  public Future<?> start() {
    tick();
    return Future.succeededFuture();
  }

  private void tick() {
    if (stopping) return;
    inFlight = service.refresh().onComplete(ar -> {
      if (!stopping) timerId = vertx.setTimer(INTERVAL_MS, id -> tick());
    });
  }

  @Override
  public Future<?> stop() {
    stopping = true;
    vertx.cancelTimer(timerId);
    return inFlight.recover(e -> Future.succeededFuture());
  }
}

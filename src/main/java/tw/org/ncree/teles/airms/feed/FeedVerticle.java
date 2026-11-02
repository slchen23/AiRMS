package tw.org.ncree.teles.airms.feed;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.VerticleBase;
import io.vertx.core.WorkerExecutor;
import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import tw.org.ncree.teles.airms.AppContext;
import tw.org.ncree.teles.airms.config.AppConfig;
import tw.org.ncree.teles.airms.feed.FeedStatusBoard.Outcome;
import tw.org.ncree.teles.airms.parse.FeedRejectedException;
import tw.org.ncree.teles.airms.parse.ParseStats;
import tw.org.ncree.teles.airms.parse.RmsParser;
import tw.org.ncree.teles.airms.parse.SectionParser;
import tw.org.ncree.teles.airms.parse.VdParser;
import tw.org.ncree.teles.airms.repo.FeedLogRepository;
import tw.org.ncree.teles.airms.repo.RmsRepository;
import tw.org.ncree.teles.airms.repo.SectionRepository;

/**
 * 一個來源一個 verticle。每一輪：下載 → 算雜湊（內容沒變就結束）→ 解析 → 原始檔歸檔 → 寫資料庫 → 記錄。
 * 同一來源同時只有一輪在跑；這一輪完全結束後才排下一輪，不會堆積。
 * 解析失敗的檔也會歸檔，當作來源 XML 結構有問題的證據。
 */
public final class FeedVerticle extends VerticleBase {
  private static final Logger log = LogManager.getLogger(FeedVerticle.class);
  private static final long FIRST_RUN_DELAY_MS = 2000;

  private final AppContext app;
  private final AppConfig.Feed feed;
  private final FeedKind kind;
  private final SectionParser.Filter sectionFilter;
  private final RmsParser.Filter rmsFilter;

  private WorkerExecutor worker;
  private long timerId = -1;
  private boolean stopping;
  private Future<Void> inFlight = Future.succeededFuture();
  private String lastSha;

  /** 解析結果：snapshot 與 rejectReason 二選一。 */
  private record Prepared(String sha, Object snapshot, ParseStats stats, String feedTimeRaw,
                          Long feedTimeMs, String rejectReason, RawXmlArchive.Stored stored, String archiveError) { }

  public FeedVerticle(AppContext app, AppConfig.Feed feed) {
    this.app = app;
    this.feed = feed;
    this.kind = feed.kind();
    this.sectionFilter = kind == FeedKind.SECTION ? SectionParser.Filter.from(feed.filter()) : null;
    this.rmsFilter = kind == FeedKind.RMS ? RmsParser.Filter.from(feed.filter()) : null;
  }

  @Override
  public Future<?> start() {
    worker = vertx.createSharedWorkerExecutor("airms-feed-" + kind.key(), 1, 2, TimeUnit.MINUTES);
    return app.airmsDb().run(c -> FeedLogRepository.lastSha(c, kind)).map(sha -> {
      lastSha = sha;
      schedule(FIRST_RUN_DELAY_MS + kind.ordinal() * 1500L);
      log.info("{} 開始抓取，每 {} 秒一次", kind.label(), feed.intervalSeconds());
      return null;
    });
  }

  @Override
  public Future<?> stop() {
    stopping = true;
    vertx.cancelTimer(timerId);
    // 等這一輪真正結束（含資料庫寫入）才關 worker，不假裝已取消。
    return inFlight.recover(e -> Future.succeededFuture()).eventually(() -> worker.close());
  }

  private void schedule(long delayMs) {
    if (stopping) return;
    timerId = vertx.setTimer(Math.max(1000, delayMs), id -> runOnce());
  }

  private void runOnce() {
    if (stopping) return;
    Instant started = app.clock().instant();
    Promise<Void> done = Promise.promise();
    inFlight = done.future();
    app.feedHttp().get(feed.url(), AppContext.USER_AGENT)
        .transform(ar -> ar.failed() ? onFetchFailure(ar.cause(), started) : process(ar.result(), started))
        .onComplete(ar -> {
          if (ar.failed()) log.error("{} 這一輪處理失敗：{}", kind.label(), clean(ar.cause()));
          done.complete();
          long elapsed = app.clock().millis() - started.toEpochMilli();
          schedule(feed.intervalSeconds() * 1000L - elapsed);
        });
  }

  private Future<Void> process(FeedHttpClient.Response resp, Instant started) {
    byte[] body = resp.body();
    return worker.executeBlocking(() -> RawXmlArchive.sha256(body), true).compose(sha -> {
      if (sha.equals(lastSha)) {
        return recordAttempt(started, resp.status(), body.length, sha, Outcome.UNCHANGED, null, null, null)
            .onSuccess(v -> app.feedStatus().record(kind, started, Outcome.UNCHANGED, null));
      }
      // 同一份內容以前歸檔過（例如內容在兩個版本間來回）就不再另存一個檔。
      return app.airmsDb().run(c -> FeedLogRepository.archivedFile(c, kind, sha))
          .compose(existing -> worker.executeBlocking(() -> prepare(body, sha, existing), true))
          .compose(p -> persist(p, started, resp.status(), body.length));
    });
  }

  /** worker 執行緒：解析、歸檔（阻塞 IO 都在這裡）。 */
  private Prepared prepare(byte[] body, String sha, RawXmlArchive.Stored alreadyStored) {
    Object snapshot = null;
    ParseStats stats = null;
    String feedTimeRaw = null;
    Long feedTimeMs = null;
    String reject = null;
    try {
      XmlNode root = SafeXml.parse(body, app.config().xmlLimits());
      feedTimeRaw = root.attr("time");
      switch (kind) {
        case VD -> {
          VdParser.Snapshot s = VdParser.parse(root);
          snapshot = s;
          stats = s.stats();
          feedTimeMs = s.feedTimeMs();
        }
        case SECTION -> {
          SectionParser.Snapshot s = SectionParser.parse(root, sectionFilter);
          snapshot = s;
          stats = s.stats();
          feedTimeMs = s.feedTimeMs();
        }
        case RMS -> {
          RmsParser.Snapshot s = RmsParser.parse(root, rmsFilter);
          snapshot = s;
          stats = s.stats();
          feedTimeMs = s.feedTimeMs();
        }
      }
    } catch (SafeXml.XmlRejectedException | FeedRejectedException e) {
      reject = clean(e);
    }
    RawXmlArchive.Stored stored = alreadyStored;
    String archiveError = null;
    if (stored == null) {
      try {
        stored = app.archive().store(kind, body, sha, app.clock().instant());
      } catch (IOException | RuntimeException e) {
        archiveError = clean(e);
      }
    }
    return new Prepared(sha, snapshot, stats, feedTimeRaw, feedTimeMs, reject, stored, archiveError);
  }

  private Future<Void> persist(Prepared p, Instant started, int httpStatus, int bytes) {
    long receivedAt = app.clock().millis();

    Future<Integer> dataWrite;
    if (p.snapshot() == null) {
      dataWrite = Future.succeededFuture(0);
    } else if (p.snapshot() instanceof VdParser.Snapshot s) {
      dataWrite = app.vdDb().tx(c -> app.vdRepository().write(c, s, p.sha(), receivedAt).lanes())
          .onFailure(e -> app.vdDb().run(c -> {
            app.vdRepository().invalidateCache();
            return null;
          }));
    } else if (p.snapshot() instanceof SectionParser.Snapshot s) {
      dataWrite = app.airmsDb().tx(c -> SectionRepository.write(c, s, receivedAt));
    } else {
      RmsParser.Snapshot s = (RmsParser.Snapshot) p.snapshot();
      dataWrite = app.airmsDb().tx(c -> RmsRepository.write(c, s, receivedAt));
    }

    return dataWrite.transform(ar -> {
      Outcome outcome;
      String error = null;
      if (ar.failed()) {
        outcome = Outcome.DB_FAILED;
        error = clean(ar.cause());
      } else if (p.rejectReason() != null) {
        outcome = Outcome.REJECTED;
        error = p.rejectReason();
      } else if (p.archiveError() != null) {
        outcome = Outcome.ARCHIVE_FAILED;
        error = "歸檔失敗：" + p.archiveError();
      } else {
        outcome = p.stats().bad() > 0 ? Outcome.PARTIAL : Outcome.OK;
      }
      String parseOutcome = p.rejectReason() != null ? "REJECTED" : (p.stats().bad() > 0 ? "PARTIAL" : "OK");
      String note = p.rejectReason() != null ? p.rejectReason()
          : (p.stats().warnings().isEmpty() ? null : String.join("；", p.stats().warnings()));
      String errorFinal = error;
      Future<Void> logged = app.airmsDb().tx(c -> {
        if (p.stored() != null) {
          FeedLogRepository.insertRawFile(c, new FeedLogRepository.RawFile(kind, p.sha(), p.stored().relativePath(),
              bytes, p.stored().storedBytes(), receivedAt, p.feedTimeRaw(), p.feedTimeMs(), parseOutcome,
              p.stats() == null ? null : p.stats().total(), p.stats() == null ? null : p.stats().bad(), note));
        }
        FeedLogRepository.insertAttempt(c, new FeedLogRepository.Attempt(kind, started.toEpochMilli(),
            app.clock().millis(), httpStatus, bytes, p.sha(), outcome.name(), errorFinal, p.feedTimeRaw(),
            p.feedTimeMs()));
        return null;
      });
      return logged.map(v -> {
        app.feedStatus().record(kind, started, outcome, p.feedTimeRaw());
        logResult(outcome, p, errorFinal, ar.succeeded() ? ar.result() : 0);
        // 全部成功（或確定整份不能用且已歸檔）才記住雜湊；否則下一輪重做，寫入本身可重複執行。
        if (outcome == Outcome.OK || outcome == Outcome.PARTIAL || outcome == Outcome.REJECTED) lastSha = p.sha();
        return (Void) null;
      });
    });
  }

  private Future<Void> onFetchFailure(Throwable error, Instant started) {
    Integer status = error instanceof FeedHttpClient.FetchException fe ? fe.status() : null;
    String message = clean(error);
    log.warn("{} 下載失敗：{}", kind.label(), message);
    app.feedStatus().record(kind, started, Outcome.FETCH_FAILED, null);
    return recordAttempt(started, status, null, null, Outcome.FETCH_FAILED, message, null, null);
  }

  private Future<Void> recordAttempt(Instant started, Integer status, Integer bytes, String sha, Outcome outcome,
                                     String error, String feedTimeRaw, Long feedTimeMs) {
    return app.airmsDb().run(c -> {
      FeedLogRepository.insertAttempt(c, new FeedLogRepository.Attempt(kind, started.toEpochMilli(),
          app.clock().millis(), status, bytes, sha, outcome.name(), error, feedTimeRaw, feedTimeMs));
      return (Void) null;
    });
  }

  private void logResult(Outcome outcome, Prepared p, String error, int written) {
    switch (outcome) {
      case OK -> log.info("{} 完成：資料時間 {}，{} 筆，新增 {}", kind.label(), p.feedTimeRaw(), p.stats().total(), written);
      case PARTIAL -> log.warn("{} 完成但有 {} 個問題：資料時間 {}，例如：{}", kind.label(), p.stats().bad(),
          p.feedTimeRaw(), p.stats().warnings().isEmpty() ? "" : p.stats().warnings().get(0));
      default -> log.error("{} {}：{}", kind.label(), outcome, error);
    }
  }

  static String clean(Throwable e) {
    String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    return ParseStats.clean(m);
  }
}

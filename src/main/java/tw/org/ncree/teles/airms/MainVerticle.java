package tw.org.ncree.teles.airms;

import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.ThreadingModel;
import io.vertx.core.VerticleBase;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.PfxOptions;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import tw.org.ncree.teles.airms.config.AppConfig;
import tw.org.ncree.teles.airms.config.Secrets;
import tw.org.ncree.teles.airms.db.Db;
import tw.org.ncree.teles.airms.db.Schema;
import tw.org.ncree.teles.airms.feed.FeedHttpClient;
import tw.org.ncree.teles.airms.feed.FeedStatusBoard;
import tw.org.ncree.teles.airms.feed.FeedVerticle;
import tw.org.ncree.teles.airms.feed.RawXmlArchive;
import tw.org.ncree.teles.airms.http.WebServer;
import tw.org.ncree.teles.airms.parse.FeedTime;
import tw.org.ncree.teles.airms.repo.ServiceRunRepository;
import tw.org.ncree.teles.airms.repo.VdRepository;
import tw.org.ncree.teles.airms.retention.RetentionVerticle;
import tw.org.ncree.teles.airms.storage.DataDirLock;
import tw.org.ncree.teles.airms.storage.DataVolume;

/**
 * 啟動順序：讀設定 → 檢查外接硬碟標記檔 → 開兩個資料庫並確認結構 → 啟動三個來源抓取與資料保留 → 開 HTTPS。
 * 任何一步失敗就停止啟動並釋放已開啟的資源。設定檔路徑可用 -Dairms.config 指定，預設工作目錄的 config.json。
 */
public final class MainVerticle extends VerticleBase {
  private static final Logger log = LogManager.getLogger(MainVerticle.class);

  private static final long HEARTBEAT_MS = 60_000;
  private static final String APP_VERSION = "0.1.0";

  private AppContext app;
  private HttpServer server;
  private volatile DataDirLock lock;
  private long runId = -1;
  private long heartbeatTimer = -1;

  @Override
  public Future<?> start() {
    Path configPath = Path.of(System.getProperty("airms.config", "config.json")).toAbsolutePath();
    return vertx.fileSystem().readFile(configPath.toString())
        .recover(e -> Future.failedFuture("找不到或無法讀取設定檔 " + configPath))
        .compose(buf -> vertx.executeBlocking(() -> build(new JsonObject(buf))))
        .compose(ctx -> {
          app = ctx;
          return app.airmsDb().run(c -> {
            Schema.migrateAirms(c);
            return null;
          }).compose(v -> app.vdDb().run(c -> {
            Schema.migrateVd(c);
            return null;
          }));
        })
        .compose(v -> recordRunStart())
        .compose(v -> deployWorkers())
        .compose(v -> startHttp())
        .onSuccess(s -> log.info("AiRMS 已啟動：{}://{}:{}/", app.config().http().tlsEnabled() ? "https" : "http",
            app.config().http().host(), s.actualPort()))
        .onFailure(e -> log.error("AiRMS 啟動失敗：{}", e.getMessage()))
        .recover(error -> closeResources().transform(ignored -> Future.failedFuture(error)));
  }

  /** worker 執行緒：讀設定、機密，驗證資料目錄，建立元件（不連網、不寫資料）。 */
  private AppContext build(JsonObject json) {
    AppConfig config = AppConfig.parse(json);
    DataVolume volume = DataVolume.verify(config.storage().dataDir(), config.storage().volumeMarker());
    lock = DataDirLock.acquire(volume);
    log.info("資料目錄：{}", volume.root());
    RawXmlArchive archive = new RawXmlArchive(volume.rawXmlRoot());
    try {
      int cleaned = archive.cleanStaleTemp(Clock.systemUTC().instant());
      if (cleaned > 0) log.warn("清除上次中斷留下的暫存檔 {} 個", cleaned);
    } catch (java.io.IOException e) {
      log.warn("清除暫存檔失敗：{}", e.getMessage());
    }
    Db airms = new Db(vertx, volume.airmsDb(), "airms-db", Db.Durability.NORMAL);
    Db vd = new Db(vertx, volume.vdHealthDb(), "airms-vd-db", Db.Durability.FULL);
    return new AppContext(config, Clock.systemUTC(), volume, airms, vd, new VdRepository(),
        archive, new FeedHttpClient(vertx, config.feedHttp()), new FeedStatusBoard());
  }

  /** 記錄這次啟動；上次若不是正常停止，把停機區間寫進 log，方便對照資料缺口。 */
  private Future<Void> recordRunStart() {
    long now = app.clock().millis();
    return app.airmsDb().tx(c -> {
      ServiceRunRepository.PreviousRun prev = ServiceRunRepository.previous(c);
      if (prev != null && prev.stoppedAtMs() == null) {
        log.warn("上次執行（#{}）沒有正常停止；最後心跳 {}，停機約 {} 分鐘，這段期間沒有抓取資料",
            prev.runId(), Instant.ofEpochMilli(prev.lastHeartbeatMs()).atZone(FeedTime.TAIPEI).toLocalDateTime(),
            (now - prev.lastHeartbeatMs()) / 60000);
      }
      runId = ServiceRunRepository.start(c, now, hostName(), APP_VERSION);
      return (Void) null;
    }).onSuccess(v -> heartbeatTimer = vertx.setPeriodic(HEARTBEAT_MS, id -> app.airmsDb()
        .run(c -> {
          ServiceRunRepository.heartbeat(c, runId, app.clock().millis());
          return null;
        }).onFailure(e -> log.warn("心跳記錄失敗：{}", e.getMessage()))));
  }

  private Future<Void> deployWorkers() {
    DeploymentOptions opts = new DeploymentOptions().setThreadingModel(ThreadingModel.EVENT_LOOP);
    List<Future<String>> deployments = new ArrayList<>();
    for (AppConfig.Feed feed : app.config().feeds()) {
      if (feed.enabled()) deployments.add(vertx.deployVerticle(new FeedVerticle(app, feed), opts));
      else log.warn("{} 已在設定中停用", feed.kind().label());
    }
    deployments.add(vertx.deployVerticle(new RetentionVerticle(app), opts));
    return Future.all(deployments).mapEmpty();
  }

  private Future<HttpServer> startHttp() {
    AppConfig.Http h = app.config().http();
    HttpServerOptions options = new HttpServerOptions().setHost(h.host()).setPort(h.port());
    if (h.tlsEnabled()) {
      String password = Secrets.load(app.config().secretsFile()).tlsKeystorePassword();
      options.setSsl(true)
          .setUseAlpn(true)
          .setEnabledSecureTransportProtocols(Set.of("TLSv1.3"))
          .setKeyCertOptions(new PfxOptions().setPath(h.keystorePath().toString()).setPassword(password));
    } else {
      log.warn("HTTPS 已關閉（http.tls.enabled=false），只適合本機開發");
    }
    return vertx.createHttpServer(options)
        .requestHandler(WebServer.router(vertx, app))
        .listen()
        .onSuccess(s -> server = s);
  }

  private static String hostName() {
    try {
      return java.net.InetAddress.getLocalHost().getHostName();
    } catch (java.io.IOException e) {
      return "unknown";
    }
  }

  @Override
  public Future<?> stop() {
    log.info("AiRMS 停止中");
    return closeResources();
  }

  /** 子 verticle（抓取、保留）由 Vert.x 先行停止並等在途工作結束；這裡關 HTTP、下載用戶端與資料庫。 */
  private Future<Void> closeResources() {
    vertx.cancelTimer(heartbeatTimer);
    List<Future<?>> closing = new ArrayList<>();
    if (server != null) closing.add(server.close());
    Future<Void> marked = Future.succeededFuture();
    if (app != null) {
      closing.add(app.feedHttp().close());
      if (runId > 0) {
        marked = app.airmsDb().run(c -> {
          ServiceRunRepository.stop(c, runId, app.clock().millis());
          return (Void) null;
        }).recover(e -> {
          log.warn("記錄停止時間失敗：{}", e.getMessage());
          return Future.succeededFuture();
        });
      }
    }
    return marked.compose(v -> {
      if (app != null) {
        closing.add(app.airmsDb().close());
        closing.add(app.vdDb().close());
      }
      return Future.join(closing);
    }).eventually(() -> {
      DataDirLock l = lock;
      lock = null;
      if (l != null) l.close();
      return Future.succeededFuture();
    }).mapEmpty();
  }
}

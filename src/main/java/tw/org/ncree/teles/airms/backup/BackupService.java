package tw.org.ncree.teles.airms.backup;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.WorkerExecutor;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import tw.org.ncree.teles.airms.config.AppConfig;
import tw.org.ncree.teles.airms.db.Db;
import tw.org.ncree.teles.airms.db.MonthlyStore;
import tw.org.ncree.teles.airms.db.Series;
import tw.org.ncree.teles.airms.storage.DayDirs;

/**
 * 每天把資料複製到外接硬碟（config.json 的 backup.targetDir；測試機 backup.enabled=false 就完全不做）。
 *
 * <ol>
 *   <li>原始 XML：只複製還沒有的檔案；已過完的日期全部複製且大小相符後，記為「已備份」。</li>
 *   <li>core.db：整份備份（VACUUM INTO），保留最近 coreKeep 份。</li>
 *   <li>月檔：已結束的月份複製一次並檢查（quick_check）後記為已備份，之後不再複製。
 *       本月的檔不另外備份：SD 卡壞掉時可由外接硬碟上的原始 XML 重新解析補回。</li>
 * </ol>
 *
 * 備份目的地結構與資料目錄相同：cache/xml_raw/…、db/vd/vd_yyyyMM.db…；core.db 放在 db/core/core_yyyyMMdd_HHmmss.db。
 * 資料保留（RetentionService）在啟用備份時，只刪除這裡記錄為已備份的日期與月份。
 */
public final class BackupService {
  private static final Logger log = LogManager.getLogger(BackupService.class);

  public enum State { DISABLED, NEVER, RUNNING, OK, FAILED }

  /** 失敗後多久再試（例如外接硬碟沒接上）。 */
  static final long RETRY_MS = 60 * 60_000L;
  /** 月底最後一期資料可能在午夜後才抓到，上個月結束後等這麼久才備份上個月的月檔。 */
  static final long MONTH_GRACE_MS = 60 * 60_000L;

  private static final Pattern SOURCE = Pattern.compile("[a-z0-9_-]+");
  private static final Pattern CORE_FILE = Pattern.compile("core_\\d{8}_\\d{6}\\.db");
  private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

  private final AppConfig.Backup cfg;
  private final Path rawXmlRoot;
  private final Db core;
  private final Map<Series, MonthlyStore> stores;
  private final Clock clock;
  private final WorkerExecutor worker;

  private volatile State state;
  private volatile long lastAttemptMs;
  private volatile long lastSuccessMs;
  private volatile String lastError;
  private volatile JsonObject lastDetail;
  private Future<JsonObject> running;

  /** 一個「已備份」的單位：kind = xml:&lt;來源&gt; 或 db:&lt;系列&gt;；unit = yyyy-MM-dd 或 yyyyMM。 */
  public record DoneUnit(String kind, String unit, int files, long bytes) { }

  public BackupService(Vertx vertx, AppConfig.Backup cfg, Path rawXmlRoot, Db core, Map<Series, MonthlyStore> stores,
                       Clock clock) {
    this.cfg = cfg;
    this.rawXmlRoot = rawXmlRoot.toAbsolutePath().normalize();
    this.core = core;
    this.stores = stores;
    this.clock = clock;
    this.worker = vertx.createSharedWorkerExecutor("airms-backup", 1, 2, TimeUnit.HOURS);
    this.state = cfg.enabled() ? State.NEVER : State.DISABLED;
  }

  public boolean enabled() {
    return cfg.enabled();
  }

  public static String xmlKind(String source) {
    return "xml:" + source;
  }

  public static String dbKind(Series s) {
    return "db:" + s.key();
  }

  static String key(String kind, String unit) {
    return kind + "|" + unit;
  }

  /** 啟動時讀回最後一次的結果，首頁才看得到上次備份時間。 */
  public Future<Void> load() {
    if (!cfg.enabled()) return Future.succeededFuture();
    return core.run(c -> {
      try (Statement st = c.createStatement()) {
        try (ResultSet rs = st.executeQuery(
            "SELECT MAX(finished_at_ms) FROM backup_log WHERE outcome='OK'")) {
          if (rs.next()) lastSuccessMs = rs.getLong(1);
        }
        try (ResultSet rs = st.executeQuery(
            "SELECT started_at_ms, outcome, error, detail_json FROM backup_log ORDER BY backup_id DESC LIMIT 1")) {
          if (rs.next()) {
            lastAttemptMs = rs.getLong(1);
            state = "OK".equals(rs.getString(2)) ? State.OK : State.FAILED;
            lastError = rs.getString(3);
            String d = rs.getString(4);
            lastDetail = d == null ? null : new JsonObject(d);
          }
        }
      }
      return (Void) null;
    });
  }

  /** 是否該執行：啟用中、今天還沒成功過、已過每日時間，失敗後至少隔一小時再試。 */
  public boolean due(long nowMs) {
    if (!cfg.enabled() || state == State.RUNNING) return false;
    LocalDateTime now = Instant.ofEpochMilli(nowMs).atZone(MonthlyStore.TAIPEI).toLocalDateTime();
    if (now.toLocalTime().isBefore(cfg.dailyAt())) return false;
    if (lastSuccessMs > 0 && day(lastSuccessMs).equals(now.toLocalDate())) return false;
    return lastAttemptMs <= 0 || nowMs - lastAttemptMs >= RETRY_MS;
  }

  private static LocalDate day(long ms) {
    return Instant.ofEpochMilli(ms).atZone(MonthlyStore.TAIPEI).toLocalDate();
  }

  /** 執行一次完整備份（同時間只會有一次）。回傳這次的摘要。 */
  public synchronized Future<JsonObject> runOnce() {
    if (!cfg.enabled()) return Future.failedFuture(new IllegalStateException("備份未啟用"));
    if (running != null) return running;
    long started = clock.millis();
    state = State.RUNNING;
    lastAttemptMs = started;
    JsonObject detail = new JsonObject();
    Future<JsonObject> f = worker.executeBlocking(this::checkTarget, true)
        .compose(v -> loadDone())
        .compose(done -> backupRawXml(done, started, detail)
            .compose(v -> backupCore(started, detail))
            .compose(v -> backupMonths(done, started, detail)))
        .transform(ar -> {
          long finished = clock.millis();
          String error = ar.failed() ? clean(ar.cause()) : null;
          return core.run(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO backup_log(started_at_ms, finished_at_ms, outcome, detail_json, error)
                VALUES (?,?,?,?,?)""")) {
              ps.setLong(1, started);
              ps.setLong(2, finished);
              ps.setString(3, error == null ? "OK" : "FAILED");
              ps.setString(4, detail.encode());
              ps.setString(5, error);
              ps.executeUpdate();
            }
            return (Void) null;
          }).recover(e -> {
            log.warn("備份紀錄寫入失敗：{}", clean(e));
            return Future.succeededFuture();
          }).compose(v -> {
            lastDetail = detail;
            if (error == null) {
              state = State.OK;
              lastSuccessMs = finished;
              lastError = null;
              log.info("備份完成：{}", detail.encode());
              return Future.succeededFuture(detail);
            }
            state = State.FAILED;
            lastError = error;
            log.warn("備份失敗：{}", error);
            return Future.failedFuture(ar.cause());
          });
        });
    running = f;
    f.onComplete(ar -> {
      synchronized (this) {
        running = null;
      }
    });
    return f;
  }

  // ---------------------------------------------------------------- 目的地

  private Path target() {
    return cfg.targetDir().toAbsolutePath().normalize();
  }

  private Void checkTarget() {
    Path t = target();
    if (!Files.isDirectory(t) || !Files.isRegularFile(t.resolve(cfg.volumeMarker()))) {
      throw new IllegalStateException("備份目的地 " + t + " 不存在或缺少標記檔 " + cfg.volumeMarker()
          + "（外接硬碟是否已接上？）");
    }
    if (!Files.isWritable(t)) throw new IllegalStateException("備份目的地不可寫入：" + t);
    return null;
  }

  /** 已備份的單位（kind|unit）。 */
  public Future<Set<String>> loadDone() {
    return core.run(c -> {
      Set<String> out = new HashSet<>();
      try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT kind, unit FROM backup_done")) {
        while (rs.next()) out.add(key(rs.getString(1), rs.getString(2)));
      }
      return out;
    });
  }

  private Future<Void> markDone(List<DoneUnit> units) {
    if (units.isEmpty()) return Future.succeededFuture();
    long now = clock.millis();
    return core.tx(c -> {
      try (PreparedStatement ps = c.prepareStatement("""
          INSERT INTO backup_done(kind, unit, done_at_ms, files, bytes) VALUES (?,?,?,?,?)
          ON CONFLICT(kind, unit) DO UPDATE SET done_at_ms=excluded.done_at_ms, files=excluded.files,
            bytes=excluded.bytes""")) {
        for (DoneUnit u : units) {
          ps.setString(1, u.kind());
          ps.setString(2, u.unit());
          ps.setLong(3, now);
          ps.setInt(4, u.files());
          ps.setLong(5, u.bytes());
          ps.addBatch();
        }
        ps.executeBatch();
      }
      return (Void) null;
    });
  }

  // ---------------------------------------------------------------- 原始 XML

  private Future<Void> backupRawXml(Set<String> done, long nowMs, JsonObject detail) {
    LocalDate today = day(nowMs);
    return worker.executeBlocking(() -> copyRawXml(rawXmlRoot, target().resolve("cache").resolve("xml_raw"), today,
            done, detail), true)
        .compose(this::markDone);
  }

  /**
   * 複製 cache/xml_raw 內還沒備份的日期資料夾；過完的日期（今天以前）全部檔案都在目的地且大小相符，才記為已備份。
   * 今天的資料夾只複製目前已有的檔案，明天再補齊。
   */
  static List<DoneUnit> copyRawXml(Path src, Path dst, LocalDate today, Set<String> done, JsonObject detail)
      throws IOException {
    List<DoneUnit> completed = new ArrayList<>();
    int copied = 0;
    long copiedBytes = 0;
    if (Files.isDirectory(src)) {
      for (Path sourceDir : list(src)) {
        String source = sourceDir.getFileName().toString();
        if (!Files.isDirectory(sourceDir) || !SOURCE.matcher(source).matches()) continue;
        for (DayDirs.Day d : DayDirs.of(sourceDir)) {
          String kind = xmlKind(source);
          String unit = d.date().toString();
          if (done.contains(key(kind, unit))) continue;
          Path targetDir = dst.resolve(src.relativize(d.dir()).toString());
          int files = 0;
          long bytes = 0;
          boolean complete = true;
          for (Path f : list(d.dir())) {
            String name = f.getFileName().toString();
            if (!Files.isRegularFile(f) || name.startsWith(".")) continue;
            long size = Files.size(f);
            Path t = targetDir.resolve(name);
            if (!Files.isRegularFile(t) || Files.size(t) != size) {
              copyFile(f, t);
              copied++;
              copiedBytes += size;
            }
            if (!Files.isRegularFile(t) || Files.size(t) != size) complete = false;
            files++;
            bytes += size;
          }
          if (complete && d.date().isBefore(today)) completed.add(new DoneUnit(kind, unit, files, bytes));
        }
      }
    }
    detail.put("xmlFilesCopied", copied).put("xmlBytesCopied", copiedBytes).put("xmlDaysCompleted", completed.size());
    return completed;
  }

  static List<Path> list(Path dir) throws IOException {
    try (Stream<Path> s = Files.list(dir)) {
      return s.sorted().toList();
    }
  }

  /** 先寫同目錄暫存檔再改名，目的地不會出現寫一半的檔案。 */
  static void copyFile(Path from, Path to) throws IOException {
    Files.createDirectories(to.getParent());
    Path tmp = to.resolveSibling(".tmp-" + to.getFileName());
    try {
      Files.copy(from, tmp, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
      moveReplace(tmp, to);
    } finally {
      Files.deleteIfExists(tmp);
    }
  }

  static void moveReplace(Path from, Path to) throws IOException {
    try {
      Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  // ---------------------------------------------------------------- core.db

  private Future<Void> backupCore(long nowMs, JsonObject detail) {
    Path dir = target().resolve("db").resolve("core");
    String name = "core_" + STAMP.format(Instant.ofEpochMilli(nowMs).atZone(MonthlyStore.TAIPEI)) + ".db";
    Path finalFile = dir.resolve(name);
    Path tmp = dir.resolve(name + ".tmp");
    return worker.executeBlocking(() -> {
      Files.createDirectories(dir);
      Files.deleteIfExists(tmp);
      return null;
    }, true).compose(v -> core.run(c -> {
      vacuumInto(c, tmp);
      return null;
    })).compose(v -> worker.executeBlocking(() -> {
      verifyCopy(tmp, tw.org.ncree.teles.airms.db.Schema.CORE_APP_ID);
      moveReplace(tmp, finalFile);
      int pruned = pruneCore(dir, cfg.coreKeep());
      detail.put("coreFile", name).put("coreBytes", Files.size(finalFile)).put("corePruned", pruned);
      return (Void) null;
    }, true)).recover(e -> worker.executeBlocking(() -> {
      Files.deleteIfExists(tmp);
      return (Void) null;
    }, true).transform(x -> Future.failedFuture(e)));
  }

  /** 保留最新 keep 份 core_*.db，刪除更舊的；回傳刪除數。 */
  static int pruneCore(Path dir, int keep) throws IOException {
    List<Path> files = new ArrayList<>();
    for (Path p : list(dir)) if (CORE_FILE.matcher(p.getFileName().toString()).matches()) files.add(p);
    files.sort((a, b) -> b.getFileName().toString().compareTo(a.getFileName().toString()));
    int n = 0;
    for (int i = keep; i < files.size(); i++) {
      if (Files.deleteIfExists(files.get(i))) n++;
    }
    return n;
  }

  /** 由資料庫自己寫出一份一致的副本（執行中也安全，含 WAL 內尚未寫回的資料）。 */
  static void vacuumInto(Connection c, Path file) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement("VACUUM INTO ?")) {
      ps.setString(1, file.toAbsolutePath().toString());
      ps.execute();
    }
  }

  /** 以唯讀方式開啟副本：quick_check 必須是 ok，application_id 必須相符。 */
  static void verifyCopy(Path file, int appId) throws SQLException {
    Properties p = new Properties();
    p.setProperty("open_mode", "1");   // SQLITE_OPEN_READONLY
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath(), p);
         Statement st = c.createStatement()) {
      try (ResultSet rs = st.executeQuery("PRAGMA quick_check")) {
        String r = rs.next() ? rs.getString(1) : null;
        if (!"ok".equals(r)) throw new SQLException("備份檔檢查失敗（quick_check：" + r + "）：" + file.getFileName());
      }
      try (ResultSet rs = st.executeQuery("PRAGMA application_id")) {
        if (!rs.next() || rs.getInt(1) != appId) {
          throw new SQLException("備份檔種類不符（application_id）：" + file.getFileName());
        }
      }
    }
  }

  // ---------------------------------------------------------------- 月檔

  private Future<Void> backupMonths(Set<String> done, long nowMs, JsonObject detail) {
    YearMonth current = MonthlyStore.monthOf(nowMs);
    boolean previousReady = nowMs >= MonthlyStore.startOf(current) + MONTH_GRACE_MS;
    JsonArray backedUp = new JsonArray();
    detail.put("months", backedUp);
    Future<Void> chain = Future.succeededFuture();
    for (Series s : Series.values()) {
      MonthlyStore store = stores.get(s);
      if (store == null) continue;
      chain = chain.compose(v -> worker.executeBlocking(store::monthsOnDisk, true)).compose(months -> {
        Future<Void> c = Future.succeededFuture();
        for (YearMonth m : months) {
          if (!m.isBefore(current)) continue;
          if (m.equals(current.minusMonths(1)) && !previousReady) continue;
          if (done.contains(key(dbKind(s), Series.unit(m)))) continue;
          c = c.compose(v -> backupMonth(store, m, current).map(bytes -> {
            backedUp.add(s.fileName(m));
            return (Void) null;
          }));
        }
        return c;
      });
    }
    return chain;
  }

  /** 複製一個已結束的月檔；舊月份（上上個月以前）用完就關閉連線。 */
  private Future<Long> backupMonth(MonthlyStore store, YearMonth m, YearMonth current) {
    Series s = store.series();
    Path dir = target().resolve("db").resolve(s.key());
    Path finalFile = dir.resolve(s.fileName(m));
    Path tmp = dir.resolve(s.fileName(m) + ".tmp");
    Future<Long> f = worker.executeBlocking(() -> {
      Files.createDirectories(dir);
      Files.deleteIfExists(tmp);
      return null;
    }, true).compose(v -> store.read(m, c -> {
      vacuumInto(c, tmp);
      return Boolean.TRUE;
    })).compose(ok -> {
      if (ok == null) return Future.succeededFuture(-1L);   // 檔案已不存在（剛被刪除）
      return worker.executeBlocking(() -> {
        verifyCopy(tmp, s.appId());
        moveReplace(tmp, finalFile);
        return Files.size(finalFile);
      }, true).compose(bytes -> markDone(List.of(new DoneUnit(dbKind(s), Series.unit(m), 1, bytes))).map(bytes));
    }).recover(e -> worker.executeBlocking(() -> {
      Files.deleteIfExists(tmp);
      return (Long) null;
    }, true).transform(x -> Future.failedFuture(e)));
    return f.eventually(() -> m.isBefore(current.minusMonths(1)) ? store.close(m) : Future.succeededFuture());
  }

  // ---------------------------------------------------------------- 狀態

  /** 首頁顯示用。 */
  public JsonObject status() {
    JsonObject o = new JsonObject().put("enabled", cfg.enabled()).put("state", state.name());
    if (!cfg.enabled()) return o;
    o.put("targetDir", target().toString())
        .put("dailyAt", cfg.dailyAt().toString())
        .put("lastAttemptAt", lastAttemptMs > 0 ? Instant.ofEpochMilli(lastAttemptMs).toString() : null)
        .put("lastSuccessAt", lastSuccessMs > 0 ? Instant.ofEpochMilli(lastSuccessMs).toString() : null)
        .put("lastError", lastError);
    // 今天（已過每日時間）或昨天以前都沒成功過，視為逾期
    long now = clock.millis();
    LocalDate today = day(now);
    LocalDate expected = Instant.ofEpochMilli(now).atZone(MonthlyStore.TAIPEI).toLocalTime().isBefore(cfg.dailyAt())
        ? today.minusDays(1) : today;
    boolean overdue = lastSuccessMs <= 0 || day(lastSuccessMs).isBefore(expected);
    o.put("overdue", overdue && state != State.RUNNING);
    return o;
  }

  public Future<Void> close() {
    Future<?> r;
    synchronized (this) {
      r = running == null ? Future.succeededFuture() : running;
    }
    return r.transform(ar -> worker.close());
  }

  static String clean(Throwable e) {
    String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    return m.replaceAll("[\\r\\n]+", " ");
  }
}

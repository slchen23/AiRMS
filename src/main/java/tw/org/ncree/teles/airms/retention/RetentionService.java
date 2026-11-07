package tw.org.ncree.teles.airms.retention;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.WorkerExecutor;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import tw.org.ncree.teles.airms.backup.BackupService;
import tw.org.ncree.teles.airms.db.MonthlyStore;
import tw.org.ncree.teles.airms.db.Series;
import tw.org.ncree.teles.airms.repo.VdRepository;
import tw.org.ncree.teles.airms.settings.SettingsService;
import tw.org.ncree.teles.airms.storage.DayDirs;

/**
 * 依系統設定刪除過期資料：
 * <ul>
 *   <li>原始 XML：cache/xml_raw/&lt;來源&gt;/yyyy/MM/dd 資料夾，保留最近 N 天（含今天）。</li>
 *   <li>月檔：本月以外再保留 N 個月，更舊的整個檔刪除。</li>
 * </ul>
 * 設為 0 表示不刪除。啟用備份時，只刪除已確認備份到外接硬碟的日期與月份；
 * 未啟用備份（測試機）時，到期就刪除。
 */
public final class RetentionService {
  private static final Logger log = LogManager.getLogger(RetentionService.class);

  private final SettingsService settings;
  private final Path rawXmlRoot;
  private final Map<Series, MonthlyStore> stores;
  private final BackupService backup;
  private final VdRepository vdRepository;
  private final Clock clock;
  private final WorkerExecutor worker;

  public RetentionService(Vertx vertx, SettingsService settings, Path rawXmlRoot, Map<Series, MonthlyStore> stores,
                          BackupService backup, VdRepository vdRepository, Clock clock) {
    this.settings = settings;
    this.rawXmlRoot = rawXmlRoot.toAbsolutePath().normalize();
    this.stores = stores;
    this.backup = backup;
    this.vdRepository = vdRepository;
    this.clock = clock;
    this.worker = vertx.createSharedWorkerExecutor("airms-retention", 1, 1, TimeUnit.HOURS);
  }

  /** 執行一次；回傳摘要（刪了什麼、有幾個因為還沒備份而保留）。 */
  public Future<JsonObject> runOnce() {
    long now = clock.millis();
    boolean gated = backup != null && backup.enabled();
    Future<Set<String>> done = gated ? backup.loadDone() : Future.succeededFuture(Set.of());
    JsonObject summary = new JsonObject();
    return done.compose(d -> worker.executeBlocking(() -> {
      purgeRawXml(rawXmlRoot, today(now), settings.intValue(SettingsService.RAW_XML_DAYS), gated, d, summary);
      return null;
    }, true).compose(v -> purgeMonths(now, gated, d, summary)))
        .compose(v -> closeOldConnections(now))
        .map(v -> {
          int pending = summary.getInteger("rawDaysPendingBackup", 0) + summary.getInteger("monthsPendingBackup", 0);
          if (summary.getInteger("rawDaysDeleted", 0) > 0 || !summary.getJsonArray("monthsDeleted").isEmpty()) {
            log.info("資料保留：{}", summary.encode());
          }
          if (pending > 0) log.warn("資料保留：{} 個日期或月份已到期但尚未備份到外接硬碟，暫不刪除", pending);
          return summary;
        });
  }

  private static LocalDate today(long nowMs) {
    return Instant.ofEpochMilli(nowMs).atZone(MonthlyStore.TAIPEI).toLocalDate();
  }

  /**
   * 刪除超過天數的日期資料夾（保留今天與前 days-1 天）。gated 時只刪 done 內有記錄（已備份）的日期。
   */
  static void purgeRawXml(Path root, LocalDate today, int days, boolean gated, Set<String> done, JsonObject summary)
      throws IOException {
    int deletedDays = 0;
    int deletedFiles = 0;
    int pending = 0;
    if (days > 0 && Files.isDirectory(root)) {
      LocalDate keepFrom = today.minusDays(days - 1L);
      for (Path sourceDir : list(root)) {
        if (!Files.isDirectory(sourceDir)) continue;
        String source = sourceDir.getFileName().toString();
        for (DayDirs.Day d : DayDirs.of(sourceDir)) {
          if (!d.date().isBefore(keepFrom)) continue;
          if (gated && !done.contains(BackupService.xmlKind(source) + "|" + d.date())) {
            pending++;
            continue;
          }
          deletedFiles += deleteDir(d.dir());
          deletedDays++;
          deleteIfEmpty(d.dir().getParent());              // 月
          deleteIfEmpty(d.dir().getParent().getParent());  // 年
        }
      }
    }
    summary.put("rawDaysDeleted", deletedDays).put("rawFilesDeleted", deletedFiles).put("rawDaysPendingBackup", pending);
  }

  private Future<Void> purgeMonths(long now, boolean gated, Set<String> done, JsonObject summary) {
    YearMonth current = MonthlyStore.monthOf(now);
    JsonArray deleted = new JsonArray();
    summary.put("monthsDeleted", deleted).put("monthsPendingBackup", 0);
    Future<Void> chain = Future.succeededFuture();
    for (Series s : Series.values()) {
      MonthlyStore store = stores.get(s);
      if (store == null) continue;
      int months = settings.intValue(s.retentionSetting());
      if (months <= 0) continue;
      YearMonth keepFrom = current.minusMonths(months);
      chain = chain.compose(v -> worker.executeBlocking(store::monthsOnDisk, true)).compose(list -> {
        Future<Void> c = Future.succeededFuture();
        for (YearMonth m : list) {
          if (!m.isBefore(keepFrom)) continue;
          if (gated && !done.contains(BackupService.dbKind(s) + "|" + Series.unit(m))) {
            summary.put("monthsPendingBackup", summary.getInteger("monthsPendingBackup") + 1);
            continue;
          }
          c = c.compose(v -> store.close(m)).compose(v -> worker.executeBlocking(() -> {
            deleteDbFile(store.file(m));
            return null;
          }, true)).map(v -> {
            if (s == Series.VD && vdRepository != null) vdRepository.invalidateCache(m);
            deleted.add(s.fileName(m));
            return (Void) null;
          });
        }
        return c;
      });
    }
    return chain;
  }

  /** 上個月以前的月檔若因查詢或備份而開著，關掉釋放資源。 */
  private Future<Void> closeOldConnections(long now) {
    YearMonth keepFrom = MonthlyStore.monthOf(now).minusMonths(1);
    Future<Void> chain = Future.succeededFuture();
    for (MonthlyStore store : stores.values()) chain = chain.compose(v -> store.closeBefore(keepFrom));
    return chain;
  }

  /** 刪除 SQLite 檔與附屬的 -wal、-shm、-journal。 */
  static void deleteDbFile(Path file) throws IOException {
    for (String suffix : List.of("-wal", "-shm", "-journal")) {
      Files.deleteIfExists(file.resolveSibling(file.getFileName() + suffix));
    }
    Files.deleteIfExists(file);
  }

  private static int deleteDir(Path dir) throws IOException {
    int n = 0;
    for (Path f : list(dir)) {
      if (Files.isDirectory(f)) {
        n += deleteDir(f);
      } else if (Files.deleteIfExists(f)) {
        n++;
      }
    }
    Files.deleteIfExists(dir);
    return n;
  }

  private static void deleteIfEmpty(Path dir) throws IOException {
    if (dir == null || !Files.isDirectory(dir)) return;
    try (var s = Files.list(dir)) {
      if (s.findAny().isPresent()) return;
    }
    Files.deleteIfExists(dir);
  }

  private static List<Path> list(Path dir) throws IOException {
    try (var s = Files.list(dir)) {
      return s.sorted().toList();
    }
  }

  public Future<Void> close() {
    return worker.close();
  }
}

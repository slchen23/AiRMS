package tw.org.ncree.teles.airms.db;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * 一個資料系列的所有月檔（db/&lt;系列&gt;/&lt;系列&gt;_yyyyMM.db）。
 * 寫入時依資料時間找到該月的檔（沒有就建立）；所有月份共用同一條執行緒，仍然只有一個寫入者。
 * 讀取舊月份時檔案不存在就回傳 null，不會建立空檔。
 * 月檔整份就是保存、備份、刪除的單位：舊月份不再變動，只需複製一次；刪除時直接刪掉整個檔。
 */
public final class MonthlyStore {
  public static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

  private final Vertx vertx;
  private final Series series;
  private final Path dir;
  private final String poolName;
  private final Map<YearMonth, Future<Db>> open = new ConcurrentHashMap<>();

  public MonthlyStore(Vertx vertx, Series series, Path dir) {
    this.vertx = vertx;
    this.series = series;
    this.dir = dir.toAbsolutePath().normalize();
    this.poolName = "airms-db-" + series.key();
  }

  public Series series() {
    return series;
  }

  public Path dir() {
    return dir;
  }

  public static YearMonth monthOf(long epochMs) {
    return YearMonth.from(Instant.ofEpochMilli(epochMs).atZone(TAIPEI));
  }

  /** 該月第一天 00:00（台北）的 epoch 毫秒。 */
  public static long startOf(YearMonth month) {
    return month.atDay(1).atStartOfDay(TAIPEI).toInstant().toEpochMilli();
  }

  public Path file(YearMonth month) {
    return dir.resolve(series.fileName(month));
  }

  public boolean exists(YearMonth month) {
    return Files.isRegularFile(file(month));
  }

  /** 資料夾內現有的月檔，由舊到新。 */
  public List<YearMonth> monthsOnDisk() throws IOException {
    List<YearMonth> out = new ArrayList<>();
    if (!Files.isDirectory(dir)) return out;
    try (Stream<Path> files = Files.list(dir)) {
      files.forEach(p -> {
        YearMonth m = series.monthOf(p.getFileName().toString());
        if (m != null && Files.isRegularFile(p)) out.add(m);
      });
    }
    out.sort(null);
    return out;
  }

  /** 寫入用：該月的檔不存在就建立並建好資料表。 */
  public synchronized Future<Db> forWrite(YearMonth month) {
    Future<Db> f = open.get(month);
    if (f != null) return f;
    Db db = new Db(vertx, file(month), poolName, series.durability());
    Future<Db> opened = db.run(c -> {
      series.migrate(c);
      return db;
    });
    open.put(month, opened);
    opened.onFailure(e -> {
      synchronized (this) {
        open.remove(month, opened);
      }
      db.close();
    });
    return opened;
  }

  /** 讀取用：檔案不存在時回傳 null（不建立空檔）。 */
  public Future<Db> forRead(YearMonth month) {
    Future<Db> f = open.get(month);
    if (f != null) return f;
    if (!exists(month)) return Future.succeededFuture(null);
    return forWrite(month);
  }

  /** 在資料時間所屬的月檔執行一個交易。 */
  public <T> Future<T> tx(long timeMs, Db.Work<T> work) {
    return forWrite(monthOf(timeMs)).compose(db -> db.tx(work));
  }

  /** 在資料時間所屬的月檔執行（不包交易）。 */
  public <T> Future<T> run(long timeMs, Db.Work<T> work) {
    return forWrite(monthOf(timeMs)).compose(db -> db.run(work));
  }

  /** 讀取某個月；檔案不存在時 work 不執行、回傳 null。 */
  public <T> Future<T> read(YearMonth month, Db.Work<T> work) {
    return forRead(month).compose(db -> db == null ? Future.succeededFuture(null) : db.run(work));
  }

  /** 關閉某個月的連線（刪除、移出前必須先關）；沒開啟時直接成功。 */
  public Future<Void> close(YearMonth month) {
    Future<Db> f;
    synchronized (this) {
      f = open.remove(month);
    }
    if (f == null) return Future.succeededFuture();
    return f.compose(Db::close).recover(e -> Future.succeededFuture());
  }

  /** 關閉 keepFrom 之前月份的連線（備份、查詢舊月份之後釋放資源）。 */
  public Future<Void> closeBefore(YearMonth keepFrom) {
    List<Future<Void>> closing = new ArrayList<>();
    for (YearMonth m : List.copyOf(open.keySet())) {
      if (m.isBefore(keepFrom)) closing.add(close(m));
    }
    return Future.join(closing).mapEmpty();
  }

  public Future<Void> closeAll() {
    List<Future<Void>> closing = new ArrayList<>();
    for (YearMonth m : List.copyOf(open.keySet())) closing.add(close(m));
    return Future.join(closing).mapEmpty();
  }

  /** 目前開著的月份（測試與狀態顯示用）。 */
  public List<YearMonth> openMonths() {
    List<YearMonth> out = new ArrayList<>(open.keySet());
    out.sort(null);
    return out;
  }
}

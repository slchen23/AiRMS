package tw.org.ncree.teles.airms.retention;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import tw.org.ncree.teles.airms.backup.BackupService;
import tw.org.ncree.teles.airms.config.AppConfig;
import tw.org.ncree.teles.airms.db.Db;
import tw.org.ncree.teles.airms.db.MonthlyStore;
import tw.org.ncree.teles.airms.db.Schema;
import tw.org.ncree.teles.airms.db.Series;
import tw.org.ncree.teles.airms.settings.SettingsService;

class RetentionServiceTest {

  static <T> T await(Future<T> f) throws Exception {
    return f.toCompletionStage().toCompletableFuture().get(60, TimeUnit.SECONDS);
  }

  static long taipei(int y, int mo, int d, int h, int mi) {
    return ZonedDateTime.of(y, mo, d, h, mi, 0, 0, MonthlyStore.TAIPEI).toInstant().toEpochMilli();
  }

  static final class Env implements AutoCloseable {
    final Vertx vertx = Vertx.vertx();
    final Path data = Files.createTempDirectory("airms-ret");
    final Path raw = data.resolve("cache").resolve("xml_raw");
    final Db core = new Db(vertx, data.resolve("core.db"), "t-core-ret", Db.Durability.NORMAL);
    final Map<Series, MonthlyStore> stores = new EnumMap<>(Series.class);
    final SettingsService settings;

    Env() throws Exception {
      await(core.run(c -> {
        Schema.migrateCore(c);
        return null;
      }));
      for (Series s : Series.values()) {
        Files.createDirectories(data.resolve("db").resolve(s.key()));
        stores.put(s, new MonthlyStore(vertx, s, data.resolve("db").resolve(s.key())));
      }
      AppConfig config = AppConfig.parse(new JsonObject(
          Files.readString(Path.of("config.example.json"), StandardCharsets.UTF_8)));
      settings = new SettingsService(core, config);
      await(settings.load());
    }

    RetentionService service(boolean backupEnabled, long nowMs) throws Exception {
      Path target = Files.createTempDirectory("airms-ret-target");
      BackupService backup = new BackupService(vertx,
          new AppConfig.Backup(backupEnabled, target, ".airms-backup", LocalTime.of(2, 0), 30), raw, core, stores,
          Clock.fixed(Instant.ofEpochMilli(nowMs), ZoneOffset.UTC));
      return new RetentionService(vertx, settings, raw, data.resolve("logs"), stores, backup, null,
          Clock.fixed(Instant.ofEpochMilli(nowMs), ZoneOffset.UTC));
    }

    void day(String source, String date) throws Exception {
      Path dir = raw.resolve(source).resolve(date.replace('-', '/'));
      Files.createDirectories(dir);
      Files.writeString(dir.resolve("x.xml.gz"), "x");
    }

    boolean hasDay(String source, String date) {
      return Files.isDirectory(raw.resolve(source).resolve(date.replace('-', '/')));
    }

    void month(Series s, YearMonth m) throws Exception {
      await(stores.get(s).forWrite(m));
    }

    void done(String kind, String unit) throws Exception {
      await(core.run(c -> {
        try (var ps = c.prepareStatement("INSERT INTO backup_done(kind, unit, done_at_ms, files, bytes) VALUES (?,?,0,1,1)")) {
          ps.setString(1, kind);
          ps.setString(2, unit);
          ps.executeUpdate();
        }
        return null;
      }));
    }

    @Override
    public void close() throws Exception {
      for (MonthlyStore s : stores.values()) await(s.closeAll());
      await(core.close());
      await(vertx.close());
    }
  }

  @Test
  void withoutBackupExpiredDataIsDeleted() throws Exception {
    try (Env env = new Env()) {
      long now = taipei(2026, 11, 5, 10, 0);
      // 原始 XML 保留 30 天（含今天）：10/07 起保留，10/06 以前刪除
      for (String d : List.of("2026-09-30", "2026-10-06", "2026-10-07", "2026-11-05")) env.day("vd", d);
      env.day("rms", "2026-10-01");
      // VD 保留 12 個月：2025-11 起保留；路段 25 個月：2024-10 起保留
      env.month(Series.VD, YearMonth.of(2025, 10));
      env.month(Series.VD, YearMonth.of(2025, 11));
      env.month(Series.VD, YearMonth.of(2026, 11));
      env.month(Series.SECTION, YearMonth.of(2024, 9));
      env.month(Series.SECTION, YearMonth.of(2024, 10));
      JsonObject r = await(env.service(false, now).runOnce());

      assertFalse(env.hasDay("vd", "2026-09-30"));
      assertFalse(env.hasDay("vd", "2026-10-06"));
      assertTrue(env.hasDay("vd", "2026-10-07"));
      assertTrue(env.hasDay("vd", "2026-11-05"));
      assertFalse(env.hasDay("rms", "2026-10-01"));
      assertFalse(Files.exists(env.raw.resolve("vd/2026/09")));          // 空的月份資料夾一併刪除
      assertEquals(3, r.getInteger("rawDaysDeleted"));   // vd 09/30、10/06 與 rms 10/01
      assertEquals(List.of(YearMonth.of(2025, 11), YearMonth.of(2026, 11)), env.stores.get(Series.VD).monthsOnDisk());
      assertEquals(List.of(YearMonth.of(2024, 10)), env.stores.get(Series.SECTION).monthsOnDisk());
      assertEquals(2, r.getJsonArray("monthsDeleted").size());
      assertFalse(Files.exists(env.data.resolve("db/vd/vd_202510.db-wal")));
    }
  }

  @Test
  void withBackupOnlyBackedUpDataIsDeleted() throws Exception {
    try (Env env = new Env()) {
      long now = taipei(2026, 11, 5, 10, 0);
      env.day("vd", "2026-10-01");
      env.day("vd", "2026-10-02");
      env.done("xml:vd", "2026-10-01");
      env.month(Series.VD, YearMonth.of(2025, 9));
      env.month(Series.VD, YearMonth.of(2025, 10));
      env.done("db:vd", "202509");
      JsonObject r = await(env.service(true, now).runOnce());
      assertFalse(env.hasDay("vd", "2026-10-01"));
      assertTrue(env.hasDay("vd", "2026-10-02"));                  // 還沒備份：保留
      assertEquals(List.of(YearMonth.of(2025, 10)), env.stores.get(Series.VD).monthsOnDisk());
      assertEquals(1, r.getInteger("rawDaysPendingBackup"));
      assertEquals(1, r.getInteger("monthsPendingBackup"));
    }
  }

  @Test
  void zeroMeansKeepForever() throws Exception {
    try (Env env = new Env()) {
      long now = taipei(2026, 11, 5, 10, 0);
      await(env.settings.update(SettingsService.RAW_XML_DAYS, 0, "admin", now));
      await(env.settings.update(Series.VD.retentionSetting(), 0, "admin", now));
      env.day("vd", "2020-01-01");
      env.month(Series.VD, YearMonth.of(2020, 1));
      await(env.service(false, now).runOnce());
      assertTrue(env.hasDay("vd", "2020-01-01"));
      assertEquals(List.of(YearMonth.of(2020, 1)), env.stores.get(Series.VD).monthsOnDisk());
    }
  }

  @Test
  void oldLogFilesAreDeletedByFileDate() throws Exception {
    try (Env env = new Env()) {
      long now = taipei(2026, 11, 5, 10, 0);
      Path logs = Files.createDirectories(env.data.resolve("logs"));
      // 預設保留 90 天（含今天）：2026-08-08 起保留
      List<String> names = List.of("airms.log", "airms-2026-08-07.log", "airms-2026-08-08.log", "airms-2026-11-04.log",
          "airms-2025-01-01.log.gz", "other-2020-01-01.log", "airms-2020-13-01.log", "notes.txt");
      for (String n : names) Files.writeString(logs.resolve(n), "x");
      Files.createDirectories(logs.resolve("airms-2020-01-02.log"));        // 同名資料夾不動
      JsonObject r = await(env.service(true, now).runOnce());                // 啟用備份也照天數刪（log 不備份）
      assertEquals(2, r.getInteger("logFilesDeleted"));
      assertFalse(Files.exists(logs.resolve("airms-2026-08-07.log")));
      assertFalse(Files.exists(logs.resolve("airms-2025-01-01.log.gz")));
      for (String keep : List.of("airms.log", "airms-2026-08-08.log", "airms-2026-11-04.log", "other-2020-01-01.log",
          "airms-2020-13-01.log", "notes.txt", "airms-2020-01-02.log")) {
        assertTrue(Files.exists(logs.resolve(keep)), keep);
      }
      await(env.settings.update(SettingsService.LOG_DAYS, 0, "admin", now));  // 0：不刪除
      Files.writeString(logs.resolve("airms-2019-01-01.log"), "x");
      assertEquals(0, await(env.service(false, now).runOnce()).getInteger("logFilesDeleted"));
      assertTrue(Files.exists(logs.resolve("airms-2019-01-01.log")));
      await(env.settings.update(SettingsService.LOG_DAYS, 2, "admin", now));  // 2 天：只留 11/04、11/05
      assertEquals(2, await(env.service(false, now).runOnce()).getInteger("logFilesDeleted"));
      assertTrue(Files.exists(logs.resolve("airms-2026-11-04.log")));
      assertFalse(Files.exists(logs.resolve("airms-2026-08-08.log")));
    }
  }
}

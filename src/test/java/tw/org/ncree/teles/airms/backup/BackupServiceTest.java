package tw.org.ncree.teles.airms.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import java.time.ZonedDateTime;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import tw.org.ncree.teles.airms.config.AppConfig;
import tw.org.ncree.teles.airms.db.Db;
import tw.org.ncree.teles.airms.db.MonthlyStore;
import tw.org.ncree.teles.airms.db.Schema;
import tw.org.ncree.teles.airms.db.Series;

class BackupServiceTest {

  static <T> T await(Future<T> f) throws Exception {
    return f.toCompletionStage().toCompletableFuture().get(60, TimeUnit.SECONDS);
  }

  static long taipei(int y, int mo, int d, int h, int mi) {
    return ZonedDateTime.of(y, mo, d, h, mi, 0, 0, MonthlyStore.TAIPEI).toInstant().toEpochMilli();
  }

  /** 可調整時間的時鐘。 */
  static final class TestClock extends Clock {
    final AtomicLong ms;

    TestClock(long ms) {
      this.ms = new AtomicLong(ms);
    }

    @Override
    public java.time.ZoneId getZone() {
      return java.time.ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return Instant.ofEpochMilli(ms.get());
    }
  }

  /** 資料目錄、備份目的地、core.db 與各系列月檔。 */
  static final class Env implements AutoCloseable {
    final Vertx vertx = Vertx.vertx();
    final Path data;
    final Path raw;
    final Path target;
    final Db core;
    final Map<Series, MonthlyStore> stores = new EnumMap<>(Series.class);

    Env() throws Exception {
      data = Files.createTempDirectory("airms-data");
      raw = data.resolve("cache").resolve("xml_raw");
      target = Files.createTempDirectory("airms-backup");
      Files.createFile(target.resolve(".airms-backup"));
      core = new Db(vertx, data.resolve("core.db"), "t-core", Db.Durability.NORMAL);
      await(core.run(c -> {
        Schema.migrateCore(c);
        return null;
      }));
      for (Series s : Series.values()) stores.put(s, new MonthlyStore(vertx, s, data.resolve("db").resolve(s.key())));
      Files.createDirectories(data.resolve("db").resolve("vd"));
    }

    BackupService service(boolean enabled, int keep, Clock clock) {
      return new BackupService(vertx, new AppConfig.Backup(enabled, target, ".airms-backup", LocalTime.of(2, 0), keep),
          raw, core, stores, clock);
    }

    void rawFile(String rel, String content) throws Exception {
      Path p = raw.resolve(rel);
      Files.createDirectories(p.getParent());
      Files.writeString(p, content, StandardCharsets.UTF_8);
    }

    void vdMonth(long timeMs) throws Exception {
      await(stores.get(Series.VD).tx(timeMs, c -> {
        try (var st = c.createStatement()) {
          st.execute("INSERT INTO vd_snapshot(feed_time_raw, feed_time_ms, received_at_ms, raw_sha256, device_count, "
              + "lane_count, rows_bad) VALUES ('t', " + timeMs + ", " + timeMs + ", 'sha" + timeMs + "', 0, 0, 0)");
        }
        return null;
      }));
    }

    long count(String sql) throws Exception {
      return await(core.run(c -> {
        try (var st = c.createStatement(); var rs = st.executeQuery(sql)) {
          rs.next();
          return rs.getLong(1);
        }
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
  void copiesRawXmlCoreAndFinishedMonths() throws Exception {
    try (Env env = new Env()) {
      env.rawFile("vd/2026/11/04/a.xml.gz", "a");
      env.rawFile("vd/2026/11/05/b.xml.gz", "b");
      env.rawFile("rms/2026/11/04/c.xml.gz", "c");
      env.vdMonth(taipei(2026, 9, 15, 12, 0));
      env.vdMonth(taipei(2026, 10, 31, 23, 55));
      env.vdMonth(taipei(2026, 11, 5, 1, 0));
      TestClock clock = new TestClock(taipei(2026, 11, 5, 2, 30));
      BackupService b = env.service(true, 2, clock);
      assertTrue(b.due(clock.millis()));
      JsonObject d = await(b.runOnce());

      // 原始 XML：全部複製；過完的日期記為已備份，今天（11/05）還沒
      assertTrue(Files.isRegularFile(env.target.resolve("cache/xml_raw/vd/2026/11/04/a.xml.gz")));
      assertTrue(Files.isRegularFile(env.target.resolve("cache/xml_raw/vd/2026/11/05/b.xml.gz")));
      assertTrue(Files.isRegularFile(env.target.resolve("cache/xml_raw/rms/2026/11/04/c.xml.gz")));
      assertEquals(3, d.getInteger("xmlFilesCopied"));
      assertEquals(1, env.count("SELECT COUNT(*) FROM backup_done WHERE kind='xml:vd' AND unit='2026-11-04'"));
      assertEquals(0, env.count("SELECT COUNT(*) FROM backup_done WHERE kind='xml:vd' AND unit='2026-11-05'"));
      assertEquals(1, env.count("SELECT COUNT(*) FROM backup_done WHERE kind='xml:rms'"));

      // core.db 整份
      try (var files = Files.list(env.target.resolve("db/core"))) {
        assertEquals(1, files.filter(p -> p.getFileName().toString().matches("core_\\d{8}_\\d{6}\\.db")).count());
      }
      // 月檔：已結束的 9 月、10 月複製；本月（11 月）不複製
      assertTrue(Files.isRegularFile(env.target.resolve("db/vd/vd_202609.db")));
      assertTrue(Files.isRegularFile(env.target.resolve("db/vd/vd_202610.db")));
      assertFalse(Files.exists(env.target.resolve("db/vd/vd_202611.db")));
      assertEquals(2, env.count("SELECT COUNT(*) FROM backup_done WHERE kind='db:vd'"));
      assertEquals("OK", b.status().getString("state"));
      assertFalse(b.status().getBoolean("overdue"));
      assertFalse(b.due(clock.millis()));                        // 今天已成功

      // 隔天：今天以前的 11/05 補齊並記為已備份；已備份的不重複複製；core 只留 2 份
      env.rawFile("vd/2026/11/05/b2.xml.gz", "b2");
      clock.ms.set(taipei(2026, 11, 6, 2, 10));
      await(b.runOnce());
      assertTrue(Files.isRegularFile(env.target.resolve("cache/xml_raw/vd/2026/11/05/b2.xml.gz")));
      assertEquals(1, env.count("SELECT COUNT(*) FROM backup_done WHERE kind='xml:vd' AND unit='2026-11-05' AND files=2"));
      clock.ms.set(taipei(2026, 11, 7, 2, 10));
      JsonObject third = await(b.runOnce());
      assertEquals(0, third.getInteger("xmlFilesCopied"));
      assertEquals(0, third.getJsonArray("months").size());
      try (var files = Files.list(env.target.resolve("db/core"))) {
        assertEquals(2, files.filter(p -> p.getFileName().toString().endsWith(".db")).count());
      }
      assertEquals(3, env.count("SELECT COUNT(*) FROM backup_log WHERE outcome='OK'"));
    }
  }

  @Test
  void previousMonthWaitsForLateDataAndMissingDriveFails() throws Exception {
    try (Env env = new Env()) {
      env.vdMonth(taipei(2026, 10, 31, 23, 55));
      TestClock clock = new TestClock(taipei(2026, 11, 1, 0, 30));   // 月初 30 分鐘內：上個月還不備份
      BackupService b = new BackupService(env.vertx,
          new AppConfig.Backup(true, env.target, ".airms-backup", LocalTime.of(0, 0), 30), env.raw, env.core,
          env.stores, clock);
      await(b.runOnce());
      assertFalse(Files.exists(env.target.resolve("db/vd/vd_202610.db")));
      clock.ms.set(taipei(2026, 11, 1, 1, 30));
      await(b.runOnce());
      assertTrue(Files.isRegularFile(env.target.resolve("db/vd/vd_202610.db")));

      // 外接硬碟沒接上（沒有標記檔）：失敗、記錄、狀態顯示逾期
      Files.delete(env.target.resolve(".airms-backup"));
      clock.ms.set(taipei(2026, 11, 2, 3, 0));
      assertThrows(Exception.class, () -> await(b.runOnce()));
      assertEquals("FAILED", b.status().getString("state"));
      assertTrue(b.status().getString("lastError").contains("標記檔"));
      assertTrue(b.status().getBoolean("overdue"));
      assertEquals(1, env.count("SELECT COUNT(*) FROM backup_log WHERE outcome='FAILED'"));
      assertFalse(b.due(clock.millis() + 10 * 60_000L));      // 失敗後一小時內不重試
      assertTrue(b.due(clock.millis() + 61 * 60_000L));

      // 重新啟動後讀回上次狀態
      BackupService reloaded = new BackupService(env.vertx,
          new AppConfig.Backup(true, env.target, ".airms-backup", LocalTime.of(0, 0), 30), env.raw, env.core,
          env.stores, clock);
      await(reloaded.load());
      assertEquals("FAILED", reloaded.status().getString("state"));
      assertTrue(reloaded.status().getString("lastSuccessAt") != null);
    }
  }

  @Test
  void disabledDoesNothing() throws Exception {
    try (Env env = new Env()) {
      BackupService b = env.service(false, 30, new TestClock(taipei(2026, 11, 5, 3, 0)));
      assertFalse(b.due(taipei(2026, 11, 5, 3, 0)));
      assertEquals("DISABLED", b.status().getString("state"));
      assertThrows(Exception.class, () -> await(b.runOnce()));
      assertEquals(0, env.count("SELECT COUNT(*) FROM backup_log"));
    }
  }
}

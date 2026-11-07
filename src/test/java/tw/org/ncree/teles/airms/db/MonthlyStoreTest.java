package tw.org.ncree.teles.airms.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class MonthlyStoreTest {

  static <T> T await(Future<T> f) throws Exception {
    return f.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
  }

  static long taipei(int y, int mo, int d, int h, int mi) {
    return ZonedDateTime.of(y, mo, d, h, mi, 0, 0, MonthlyStore.TAIPEI).toInstant().toEpochMilli();
  }

  @Test
  void monthFollowsTaipeiTime() {
    // UTC 10/31 16:30 = 台北 11/1 00:30
    assertEquals(YearMonth.of(2026, 11), MonthlyStore.monthOf(taipei(2026, 11, 1, 0, 30)));
    assertEquals(YearMonth.of(2026, 10), MonthlyStore.monthOf(taipei(2026, 10, 31, 23, 59)));
    assertEquals(taipei(2026, 11, 1, 0, 0), MonthlyStore.startOf(YearMonth.of(2026, 11)));
    assertEquals("vd_202611.db", Series.VD.fileName(YearMonth.of(2026, 11)));
    assertEquals(YearMonth.of(2026, 1), Series.RMS.monthOf("rms_202601.db"));
    assertNull(Series.RMS.monthOf("rms_202601.db-wal"));
    assertNull(Series.RMS.monthOf("vd_202601.db"));
  }

  @Test
  void writesGoToTheMonthOfTheDataAndReadsNeverCreateFiles() throws Exception {
    Vertx vertx = Vertx.vertx();
    Path dir = Files.createTempDirectory("airms-month");
    MonthlyStore store = new MonthlyStore(vertx, Series.SECTION, dir);
    try {
      long oct = taipei(2026, 10, 31, 23, 55);
      long nov = taipei(2026, 11, 1, 0, 5);
      for (long t : List.of(oct, nov, nov + 60_000)) {
        await(store.tx(t, c -> {
          try (var st = c.createStatement()) {
            st.execute("INSERT INTO section_history(freeway_id, direction_id, from_milepost, end_milepost, "
                + "feed_time_ms, received_at_ms) VALUES ('1','S','1','2'," + t + "," + t + ")");
          }
          return null;
        }));
      }
      assertEquals(List.of(YearMonth.of(2026, 10), YearMonth.of(2026, 11)), store.monthsOnDisk());
      assertEquals(Long.valueOf(1), await(store.read(YearMonth.of(2026, 10), MonthlyStoreTest::rows)));
      assertEquals(Long.valueOf(2), await(store.read(YearMonth.of(2026, 11), MonthlyStoreTest::rows)));
      // 不存在的月份：不執行、不建立檔案
      assertNull(await(store.read(YearMonth.of(2026, 9), MonthlyStoreTest::rows)));
      assertFalse(store.exists(YearMonth.of(2026, 9)));

      await(store.closeBefore(YearMonth.of(2026, 11)));
      assertEquals(List.of(YearMonth.of(2026, 11)), store.openMonths());
      await(store.closeAll());
      assertTrue(store.openMonths().isEmpty());
      assertTrue(Files.isRegularFile(dir.resolve("section_202610.db")));
    } finally {
      await(store.closeAll());
      await(vertx.close());
    }
  }

  private static Long rows(java.sql.Connection c) throws Exception {
    try (var st = c.createStatement(); var rs = st.executeQuery("SELECT COUNT(*) FROM section_history")) {
      rs.next();
      return rs.getLong(1);
    }
  }
}

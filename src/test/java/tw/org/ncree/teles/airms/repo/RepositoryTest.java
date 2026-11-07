package tw.org.ncree.teles.airms.repo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;
import tw.org.ncree.teles.airms.Fixtures;
import tw.org.ncree.teles.airms.db.Db;
import tw.org.ncree.teles.airms.db.Schema;
import tw.org.ncree.teles.airms.feed.SafeXml;
import tw.org.ncree.teles.airms.parse.RmsParser;
import tw.org.ncree.teles.airms.parse.SectionParser;
import tw.org.ncree.teles.airms.parse.VdParser;

class RepositoryTest {
  private static final YearMonth MONTH = YearMonth.of(2026, 10);

  private static Connection open(String name) throws Exception {
    Path dir = Files.createTempDirectory("airms-db");
    return Db.open(dir.resolve(name), Db.Durability.NORMAL);
  }

  private static long count(Connection c, String sql) throws Exception {
    try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  @Test
  void vdSnapshotWrittenOnceWithRawValues() throws Exception {
    try (Connection c = open("vd_202610.db")) {
      Schema.migrateVd(c);
      Schema.migrateVd(c); // 再跑一次不應出錯
      VdParser.Snapshot s = VdParser.parse(SafeXml.parse(Fixtures.bytes("vd_small.xml"), Fixtures.LIMITS));
      VdRepository repo = new VdRepository();
      VdRepository.Result r1 = Db.inTransaction(c, conn -> repo.write(conn, MONTH, s, "sha-a", 1L));
      assertTrue(r1.inserted());
      assertEquals(3, r1.lanes());
      VdRepository.Result r2 = Db.inTransaction(c, conn -> repo.write(conn, MONTH, s, "sha-a", 2L));
      assertFalse(r2.inserted());
      // 同一時間、不同內容（來源更正）也保留
      VdRepository.Result r3 = Db.inTransaction(c, conn -> repo.write(conn, MONTH, s, "sha-b", 3L));
      assertTrue(r3.inserted());
      assertEquals(2, count(c, "SELECT COUNT(*) FROM vd_snapshot"));
      assertEquals(3, count(c, "SELECT COUNT(*) FROM vd_device"));
      assertEquals(3, count(c, "SELECT COUNT(*) FROM vd_lane"));
      assertEquals(6, count(c, "SELECT COUNT(*) FROM vd_lane_sample"));
      assertEquals(-2, count(c, "SELECT lane_total_car_volume FROM vd_lane_sample s JOIN vd_lane l USING(lane_key) "
          + "JOIN vd_device d USING(device_id) WHERE d.eq_id='VD-T74-E-1.000-M-LOOP' LIMIT 1"));
      try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(
          "SELECT lane_use_rate, extra_json FROM vd_lane_sample s JOIN vd_lane l USING(lane_key) "
              + "JOIN vd_device d USING(device_id) WHERE d.eq_id='VD-T74-E-1.000-M-LOOP' LIMIT 1")) {
        rs.next();
        rs.getInt(1);
        assertTrue(rs.wasNull());                         // NA 不是整數 → NULL
        assertEquals("{\"lane_use_rate\":\"NA\"}", rs.getString(2)); // 原字串保留
      }
    }
  }

  @Test
  void vdKeysAreCachedPerMonthFile() throws Exception {
    VdParser.Snapshot s = VdParser.parse(SafeXml.parse(Fixtures.bytes("vd_small.xml"), Fixtures.LIMITS));
    VdRepository repo = new VdRepository();
    try (Connection oct = open("vd_202610.db"); Connection nov = open("vd_202611.db")) {
      Schema.migrateVd(oct);
      Schema.migrateVd(nov);
      try (Statement st = nov.createStatement()) {
        // 11 月檔的編號從別的數字開始，確認不會沿用 10 月檔的編號
        st.execute("INSERT INTO vd_device(device_id, eq_id, first_seen_ms) VALUES (100, 'VD-OTHER', 0)");
      }
      Db.inTransaction(oct, conn -> repo.write(conn, MONTH, s, "sha-a", 1L));
      Db.inTransaction(nov, conn -> repo.write(conn, MONTH.plusMonths(1), s, "sha-a", 1L));
      assertEquals(0, count(nov, "SELECT COUNT(*) FROM vd_lane l LEFT JOIN vd_device d USING(device_id) "
          + "WHERE d.device_id IS NULL"));
      assertEquals(4, count(nov, "SELECT COUNT(*) FROM vd_device"));
    }
  }

  @Test
  void sectionLatestOnlyMovesForwardAndHistoryDeduplicates() throws Exception {
    SectionParser.Snapshot s = SectionParser.parse(SafeXml.parse(Fixtures.bytes("section_small.xml"),
        Fixtures.LIMITS), SectionParser.Filter.from(Fixtures.sectionFilter()));
    try (Connection live = Db.openMemory(); Connection hist = open("section_202610.db")) {
      Schema.migrateLive(live);
      Schema.migrateSection(hist);
      SectionRepository.writeLatest(live, s, 1L);
      int first = Db.inTransaction(hist, conn -> SectionRepository.writeHistory(conn, s, 1L));
      assertEquals(2, first);
      int again = Db.inTransaction(hist, conn -> SectionRepository.writeHistory(conn, s, 2L));
      assertEquals(0, again); // 同時間重抓
      assertEquals(2, count(live, "SELECT COUNT(*) FROM section_latest")); // 國1、國3 同里程不互相覆蓋
      SectionParser.Snapshot older = new SectionParser.Snapshot("2026-10-28 16:07:31",
          s.feedTimeMs() - 60000, s.rows(), s.stats());
      SectionRepository.writeLatest(live, older, 3L);
      Db.inTransaction(hist, conn -> SectionRepository.writeHistory(conn, older, 3L));
      assertEquals(s.feedTimeMs(), count(live, "SELECT MIN(feed_time_ms) FROM section_latest")); // 舊資料不倒退
      assertEquals(4, count(hist, "SELECT COUNT(*) FROM section_history"));
    }
  }

  @Test
  void rmsStoresNullForMissingRate() throws Exception {
    RmsParser.Snapshot s = RmsParser.parse(SafeXml.parse(Fixtures.bytes("rms_small.xml"), Fixtures.LIMITS),
        RmsParser.Filter.from(Fixtures.rmsFilter()));
    try (Connection live = Db.openMemory(); Connection hist = open("rms_202610.db")) {
      Schema.migrateLive(live);
      Schema.migrateRms(hist);
      RmsRepository.writeLatest(live, s, 1L);
      int written = Db.inTransaction(hist, conn -> RmsRepository.writeHistory(conn, s, 1L));
      assertEquals(2, written);
      try (Statement st = live.createStatement(); ResultSet rs = st.executeQuery(
          "SELECT rms_rate FROM rms_latest WHERE eq_id LIKE 'RMS-N3%'")) {
        rs.next();
        assertNull(rs.getString(1));
      }
      assertEquals(1, count(hist, "SELECT COUNT(*) FROM rms_history WHERE eq_id LIKE 'RMS-N3%' AND rms_rate IS NULL"));
    }
  }

  @Test
  void refusesForeignDatabase() throws Exception {
    try (Connection c = open("other.db")) {
      try (Statement st = c.createStatement()) {
        st.execute("CREATE TABLE something(x)");
      }
      assertThrows(IllegalStateException.class, () -> Schema.migrateCore(c));
      assertEquals(1, count(c, "SELECT COUNT(*) FROM sqlite_schema WHERE name='something'")); // 沒被動到
    }
  }

  @Test
  void eachFileKindHasItsOwnIdentity() throws Exception {
    try (Connection c = open("core.db")) {
      Schema.migrateCore(c);
      Schema.migrateCore(c);
      assertEquals(1, count(c, "PRAGMA user_version"));
      assertEquals(Schema.CORE_APP_ID, count(c, "PRAGMA application_id"));
      assertEquals(9, count(c, "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name IN "
          + "('account','account_permission','login_history','audit_log','app_setting','ramp_override',"
          + "'service_run','backup_log','backup_done')"));
      assertThrows(IllegalStateException.class, () -> Schema.migrateVd(c));
      assertThrows(IllegalStateException.class, () -> Schema.migrateSection(c));
    }
    try (Connection c = open("section_202610.db")) {
      Schema.migrateSection(c);
      assertThrows(IllegalStateException.class, () -> Schema.migrateRms(c));
      assertThrows(IllegalStateException.class, () -> Schema.migrateCore(c));
    }
    try (Connection c = open("feedlog_202610.db")) {
      Schema.migrateFeedLog(c);
      assertEquals(Schema.FEEDLOG_APP_ID, count(c, "PRAGMA application_id"));
      assertEquals(2, count(c, "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name IN "
          + "('feed_attempt','raw_xml_file')"));
    }
  }

  @Test
  void tracksRunsInCoreDatabase() throws Exception {
    try (Connection c = open("core.db")) {
      Schema.migrateCore(c);
      long r1 = ServiceRunRepository.start(c, 1000, "host", "0.1.0");
      ServiceRunRepository.heartbeat(c, r1, 61000);
      // 強制結束：沒有呼叫 stop
      ServiceRunRepository.PreviousRun prev = ServiceRunRepository.previous(c);
      assertEquals(61000L, prev.lastHeartbeatMs());
      assertNull(prev.stoppedAtMs());
      long r2 = ServiceRunRepository.start(c, 200000, "host", "0.1.0");
      ServiceRunRepository.stop(c, r2, 260000);
      assertEquals(Long.valueOf(260000L), ServiceRunRepository.previous(c).stoppedAtMs());
    }
  }
}

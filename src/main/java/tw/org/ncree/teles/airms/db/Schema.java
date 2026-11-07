package tw.org.ncree.teles.airms.db;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * 資料庫檔的結構。用 application_id 認檔、user_version 記版本：
 * 不認得的檔（別的程式的資料庫、比程式新的版本）一律拒絕，不 DROP、不覆蓋。
 *
 * <ul>
 *   <li>core.db：帳號、權限、登入與操作紀錄、系統設定、路網匝道修改、程式啟停、備份紀錄（不分月、不自動刪除）。</li>
 *   <li>每月一個檔：vd_yyyyMM.db、section_yyyyMM.db、rms_yyyyMM.db、feedlog_yyyyMM.db（見 {@link Series}）。</li>
 *   <li>即時資料（路段、RMS 最新一筆）放在記憶體資料庫，不寫入 SD 卡；重啟後下一輪抓取即恢復。</li>
 * </ul>
 */
public final class Schema {
  private Schema() { }

  /** 'AiCO' / 'AiVD' / 'AiSE' / 'AiRS' / 'AiFL' / 'AiLV' 的 ASCII，避免誤開別的 SQLite 檔。 */
  public static final int CORE_APP_ID = 0x4169434F;
  public static final int VD_APP_ID = 0x41695644;
  public static final int SECTION_APP_ID = 0x41695345;
  public static final int RMS_APP_ID = 0x41695253;
  public static final int FEEDLOG_APP_ID = 0x4169464C;
  public static final int LIVE_APP_ID = 0x41694C56;

  /** 路段資料的整數欄位（XML 屬性名稱照原樣當欄名，方便對照原始檔）。 */
  public static final List<String> SECTION_INT_FIELDS = List.of(
      "section_upper_limit", "section_lower_limit", "section_total_car_volume",
      "section_small_car_volume", "section_big_car_volume", "section_connect_car_volume",
      "section_average_speed", "section_small_car_average_speed", "section_big_car_average_speed",
      "section_connect_car_average_speed", "section_average_occupancy", "travel_time",
      "section_congestion_level", "section_ramp_in_total_volume", "section_ramp_out_total_volume", "lanes");

  /** RMS 資料的文字欄位（全部存原值，不解讀）。 */
  public static final List<String> RMS_FIELDS = List.of(
      "eq_comm_status", "eq_hw_status", "rms_rate", "rms_control_mode", "rms_merge_mode",
      "rms_queue_mode", "rms_checkio_mode", "bos1_message", "bos2_message");

  /** VD 車道的 14 個量測欄位，整數原值（-2 照存）。 */
  public static final List<String> VD_LANE_FIELDS = List.of(
      "small_car_volume", "small_car_average_speed", "small_car_average_length",
      "big_car_volume", "big_car_average_speed", "big_car_average_length",
      "connect_car_volume", "connect_car_average_speed", "connect_car_average_length",
      "lane_total_car_volume", "lane_average_car_speed", "lane_average_car_interval",
      "lane_average_occupancy", "lane_use_rate");

  public static void migrateCore(Connection c) throws Exception {
    migrate(c, CORE_APP_ID, "core.db", List.of(Schema::createCoreV1));
  }

  public static void migrateVd(Connection c) throws Exception {
    migrate(c, VD_APP_ID, "VD 月檔", List.of(Schema::createVdV1));
  }

  public static void migrateSection(Connection c) throws Exception {
    migrate(c, SECTION_APP_ID, "路段月檔", List.of(Schema::createSectionV1));
  }

  public static void migrateRms(Connection c) throws Exception {
    migrate(c, RMS_APP_ID, "RMS 月檔", List.of(Schema::createRmsV1));
  }

  public static void migrateFeedLog(Connection c) throws Exception {
    migrate(c, FEEDLOG_APP_ID, "抓取紀錄月檔", List.of(Schema::createFeedLogV1));
  }

  public static void migrateLive(Connection c) throws Exception {
    migrate(c, LIVE_APP_ID, "即時資料", List.of(Schema::createLiveV1));
  }

  @FunctionalInterface
  private interface Ddl {
    void apply(Statement st) throws SQLException;
  }

  /** steps.get(i) 把版本 i 升到 i+1；每一步一個交易，失敗就停在原版本。 */
  private static void migrate(Connection c, int appId, String name, List<Ddl> steps) throws Exception {
    int target = steps.size();
    int existingApp = pragmaInt(c, "application_id");
    int existingVersion = pragmaInt(c, "user_version");
    boolean empty = !hasAnyTable(c);
    if (!(empty && existingVersion == 0)) {
      if (existingApp != appId) {
        throw new IllegalStateException(name + " 不是本系統這一類的資料庫（application_id 不符），拒絕使用");
      }
      if (existingVersion > target) {
        throw new IllegalStateException(name + " 版本 " + existingVersion + " 比程式支援的 " + target + " 新，拒絕寫入");
      }
    }
    for (int v = existingVersion; v < target; v++) {
      Ddl step = steps.get(v);
      int next = v + 1;
      Db.inTransaction(c, conn -> {
        try (Statement st = conn.createStatement()) {
          step.apply(st);
          if (next == 1) st.execute("PRAGMA application_id=" + appId);
          st.execute("PRAGMA user_version=" + next);
        }
        return null;
      });
    }
  }

  // ---------------------------------------------------------------- core.db

  /**
   * 帳號（Argon2id 雜湊；登入中的 Session 只放記憶體）、系統設定、匝道修改、程式啟停、備份紀錄。
   * 這些資料無法從原始 XML 重建，所以集中在一個小檔、每天整份備份。
   */
  private static void createCoreV1(Statement st) throws SQLException {
    st.execute("""
        CREATE TABLE account (
          account_id             INTEGER PRIMARY KEY,
          username               TEXT    NOT NULL UNIQUE COLLATE NOCASE,
          display_name           TEXT    NOT NULL,
          password_hash          TEXT    NOT NULL,
          role                   TEXT    NOT NULL CHECK (role IN ('ADMIN','USER')),
          enabled                INTEGER NOT NULL CHECK (enabled IN (0,1)),
          expires_at_ms          INTEGER,
          must_change_password   INTEGER NOT NULL DEFAULT 0 CHECK (must_change_password IN (0,1)),
          failed_count           INTEGER NOT NULL DEFAULT 0,
          locked_until_ms        INTEGER,
          last_login_at_ms       INTEGER,
          password_changed_at_ms INTEGER NOT NULL,
          created_at_ms          INTEGER NOT NULL,
          updated_at_ms          INTEGER NOT NULL
        )""");
    st.execute("""
        CREATE TABLE account_permission (
          account_id INTEGER NOT NULL REFERENCES account(account_id) ON DELETE CASCADE,
          permission TEXT    NOT NULL,
          PRIMARY KEY (account_id, permission)
        ) WITHOUT ROWID""");
    st.execute("""
        CREATE TABLE login_history (
          login_id   INTEGER PRIMARY KEY,
          at_ms      INTEGER NOT NULL,
          username   TEXT    NOT NULL,
          account_id INTEGER,
          outcome    TEXT    NOT NULL,
          ip         TEXT,
          user_agent TEXT
        )""");
    st.execute("CREATE INDEX login_history_time ON login_history(at_ms)");
    st.execute("""
        CREATE TABLE audit_log (
          audit_id    INTEGER PRIMARY KEY,
          at_ms       INTEGER NOT NULL,
          actor       TEXT    NOT NULL,
          action      TEXT    NOT NULL,
          target      TEXT,
          detail_json TEXT,
          ip          TEXT
        )""");
    st.execute("CREATE INDEX audit_log_time ON audit_log(at_ms)");

    // 系統設定頁修改的值。只存「與 config.json 預設不同」的項目，刪除該列即還原預設。
    st.execute("""
        CREATE TABLE app_setting (
          setting_key   TEXT PRIMARY KEY,
          value_json    TEXT    NOT NULL,
          updated_at_ms INTEGER NOT NULL,
          updated_by    TEXT    NOT NULL
        ) WITHOUT ROWID""");
    // 「路網匝道」管理頁對個別匝道圈的修改；visible、rms_json 為 NULL 表示沿用路網設定檔。
    st.execute("""
        CREATE TABLE ramp_override (
          ramp_key      TEXT PRIMARY KEY,
          visible       INTEGER CHECK (visible IN (0,1)),
          rms_json      TEXT,
          updated_at_ms INTEGER NOT NULL,
          updated_by    TEXT    NOT NULL
        ) WITHOUT ROWID""");
    // 程式每次啟動一列，定時更新心跳；stopped_at_ms 為空表示不是正常停止（強制結束、當機、斷電）。
    st.execute("""
        CREATE TABLE service_run (
          run_id            INTEGER PRIMARY KEY,
          started_at_ms     INTEGER NOT NULL,
          last_heartbeat_ms INTEGER NOT NULL,
          stopped_at_ms     INTEGER,
          host              TEXT,
          app_version       TEXT
        )""");
    // 每次備份作業一列（成功、失敗都記），供首頁顯示與事後查核。
    st.execute("""
        CREATE TABLE backup_log (
          backup_id      INTEGER PRIMARY KEY,
          started_at_ms  INTEGER NOT NULL,
          finished_at_ms INTEGER NOT NULL,
          outcome        TEXT    NOT NULL,
          detail_json    TEXT,
          error          TEXT
        )""");
    // 已確認複製到備份硬碟的單位：kind = 'xml:vd'、'db:vd'…；unit = 日期（yyyy-MM-dd）或月份（yyyyMM）。
    // 啟用備份時，資料保留只刪除這裡有記錄的單位。
    st.execute("""
        CREATE TABLE backup_done (
          kind       TEXT    NOT NULL,
          unit       TEXT    NOT NULL,
          done_at_ms INTEGER NOT NULL,
          files      INTEGER NOT NULL,
          bytes      INTEGER NOT NULL,
          PRIMARY KEY (kind, unit)
        ) WITHOUT ROWID""");
  }

  // ---------------------------------------------------------------- 月檔

  private static void createSectionV1(Statement st) throws SQLException {
    String sectionInts = String.join(",\n", SECTION_INT_FIELDS.stream().map(f -> "  " + f + " INTEGER").toList());
    st.execute("""
        CREATE TABLE section_history (
          freeway_id     TEXT NOT NULL,
          expressway_id  TEXT,
          direction_id   TEXT NOT NULL,
          from_milepost  TEXT NOT NULL,
          end_milepost   TEXT NOT NULL,
          feed_time_ms   INTEGER NOT NULL,
        """ + sectionInts + """
        ,
          received_at_ms INTEGER NOT NULL,
          PRIMARY KEY (freeway_id, direction_id, from_milepost, end_milepost, feed_time_ms)
        ) WITHOUT ROWID""");
    st.execute("CREATE INDEX section_history_time ON section_history(feed_time_ms)");
  }

  private static void createRmsV1(Statement st) throws SQLException {
    String rmsCols = String.join(",\n", RMS_FIELDS.stream().map(f -> "  " + f + " TEXT").toList());
    st.execute("""
        CREATE TABLE rms_history (
          eq_id          TEXT    NOT NULL,
          feed_time_ms   INTEGER NOT NULL,
        """ + rmsCols + """
        ,
          received_at_ms INTEGER NOT NULL,
          PRIMARY KEY (eq_id, feed_time_ms)
        ) WITHOUT ROWID""");
    st.execute("CREATE INDEX rms_history_time ON rms_history(feed_time_ms)");
  }

  private static void createFeedLogV1(Statement st) throws SQLException {
    // 每次抓取一列（含失敗與內容未變），用來看來源是否正常更新。
    st.execute("""
        CREATE TABLE feed_attempt (
          attempt_id     INTEGER PRIMARY KEY,
          source         TEXT    NOT NULL,
          started_at_ms  INTEGER NOT NULL,
          finished_at_ms INTEGER NOT NULL,
          http_status    INTEGER,
          bytes          INTEGER,
          sha256         TEXT,
          outcome        TEXT    NOT NULL,
          error          TEXT,
          feed_time_raw  TEXT,
          feed_time_ms   INTEGER
        )""");
    st.execute("CREATE INDEX feed_attempt_source_time ON feed_attempt(source, started_at_ms)");
    // 每一份「內容不同」的原始 XML 一列；檔案本體在 cache/xml_raw/ 目錄（gzip）。
    st.execute("""
        CREATE TABLE raw_xml_file (
          raw_id         INTEGER PRIMARY KEY,
          source         TEXT    NOT NULL,
          sha256         TEXT    NOT NULL,
          rel_path       TEXT    NOT NULL,
          bytes          INTEGER NOT NULL,
          stored_bytes   INTEGER NOT NULL,
          received_at_ms INTEGER NOT NULL,
          feed_time_raw  TEXT,
          feed_time_ms   INTEGER,
          parse_outcome  TEXT    NOT NULL,
          rows_total     INTEGER,
          rows_bad       INTEGER,
          note           TEXT,
          UNIQUE (source, sha256)
        )""");
    st.execute("CREATE INDEX raw_xml_file_source_time ON raw_xml_file(source, received_at_ms)");
  }

  private static void createVdV1(Statement st) throws SQLException {
    // 每一份內容不同的 VD 檔一列。同一檔頭時間若出現不同內容（來源更正），兩份都保留。
    st.execute("""
        CREATE TABLE vd_snapshot (
          snapshot_id       INTEGER PRIMARY KEY,
          feed_time_raw     TEXT    NOT NULL,
          feed_time_ms      INTEGER NOT NULL,
          control_center_id TEXT,
          file_name         TEXT,
          received_at_ms    INTEGER NOT NULL,
          raw_sha256        TEXT    NOT NULL UNIQUE,
          device_count      INTEGER NOT NULL,
          lane_count        INTEGER NOT NULL,
          rows_bad          INTEGER NOT NULL
        )""");
    st.execute("CREATE INDEX vd_snapshot_time ON vd_snapshot(feed_time_ms)");
    st.execute("""
        CREATE TABLE vd_device (
          device_id     INTEGER PRIMARY KEY,
          eq_id         TEXT    NOT NULL UNIQUE,
          first_seen_ms INTEGER NOT NULL
        )""");
    st.execute("""
        CREATE TABLE vd_lane (
          lane_key      INTEGER PRIMARY KEY,
          device_id     INTEGER NOT NULL REFERENCES vd_device(device_id),
          lane_ref      TEXT    NOT NULL,
          first_seen_ms INTEGER NOT NULL,
          UNIQUE (device_id, lane_ref)
        )""");
    st.execute("""
        CREATE TABLE vd_device_sample (
          snapshot_id      INTEGER NOT NULL REFERENCES vd_snapshot(snapshot_id),
          device_id        INTEGER NOT NULL REFERENCES vd_device(device_id),
          eq_comm_status   TEXT,
          lanes            TEXT,
          congestion_level TEXT,
          extra_json       TEXT,
          PRIMARY KEY (snapshot_id, device_id)
        ) WITHOUT ROWID""");
    String laneCols = String.join(",\n", VD_LANE_FIELDS.stream().map(f -> "  " + f + " INTEGER").toList());
    // 14 欄整數原值；非整數或未知屬性放 extra_json（JSON 物件，原字串）。
    st.execute("""
        CREATE TABLE vd_lane_sample (
          snapshot_id   INTEGER NOT NULL REFERENCES vd_snapshot(snapshot_id),
          lane_key      INTEGER NOT NULL REFERENCES vd_lane(lane_key),
          is_data_valid TEXT,
        """ + laneCols + """
        ,
          extra_json    TEXT,
          PRIMARY KEY (snapshot_id, lane_key)
        ) WITHOUT ROWID""");
    st.execute("CREATE INDEX vd_lane_sample_lane ON vd_lane_sample(lane_key, snapshot_id)");
    st.execute("CREATE INDEX vd_device_sample_device ON vd_device_sample(device_id, snapshot_id)");
  }

  // ---------------------------------------------------------------- 記憶體

  /** 每個路段、每台 RMS 的最新一筆。主鍵含國道與方向，避免國1/國3 里程相同時互相覆蓋。 */
  private static void createLiveV1(Statement st) throws SQLException {
    String sectionInts = String.join(",\n", SECTION_INT_FIELDS.stream().map(f -> "  " + f + " INTEGER").toList());
    st.execute("""
        CREATE TABLE section_latest (
          freeway_id     TEXT NOT NULL,
          expressway_id  TEXT,
          direction_id   TEXT NOT NULL,
          from_location  TEXT,
          from_milepost  TEXT NOT NULL,
          end_location   TEXT,
          end_milepost   TEXT NOT NULL,
        """ + sectionInts + """
        ,
          feed_time_raw  TEXT    NOT NULL,
          feed_time_ms   INTEGER NOT NULL,
          updated_at_ms  INTEGER NOT NULL,
          PRIMARY KEY (freeway_id, direction_id, from_milepost, end_milepost)
        ) WITHOUT ROWID""");
    String rmsCols = String.join(",\n", RMS_FIELDS.stream().map(f -> "  " + f + " TEXT").toList());
    st.execute("""
        CREATE TABLE rms_latest (
          eq_id          TEXT PRIMARY KEY,
        """ + rmsCols + """
        ,
          feed_time_raw  TEXT    NOT NULL,
          feed_time_ms   INTEGER NOT NULL,
          updated_at_ms  INTEGER NOT NULL
        ) WITHOUT ROWID""");
  }

  public static int pragmaInt(Connection c, String pragma) throws SQLException {
    try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("PRAGMA " + pragma)) {
      return rs.next() ? rs.getInt(1) : 0;
    }
  }

  public static boolean hasAnyTable(Connection c) throws SQLException {
    try (Statement st = c.createStatement();
         ResultSet rs = st.executeQuery("SELECT 1 FROM sqlite_schema WHERE type='table' LIMIT 1")) {
      return rs.next();
    }
  }
}

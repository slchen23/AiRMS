package tw.org.ncree.teles.airms.db;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * 三個資料庫檔（airms.db、vd_health.db、auth.db）的結構。用 application_id 認檔、user_version 記版本：
 * 不認得的檔（別的程式的資料庫、比程式新的版本）一律拒絕，不 DROP、不覆蓋。
 */
public final class Schema {
  private Schema() { }

  /** 'AiRM' / 'AiVD' / 'AiAU' 的 ASCII，避免誤開別的 SQLite 檔。 */
  public static final int AIRMS_APP_ID = 0x4169524D;
  public static final int VD_APP_ID = 0x41695644;
  public static final int AUTH_APP_ID = 0x41694155;


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

  public static void migrateAirms(Connection c) throws Exception {
    migrate(c, AIRMS_APP_ID, "airms.db",
        List.of(Schema::createAirmsV1, Schema::airmsV2, Schema::airmsV3, Schema::airmsV4));
  }

  public static void migrateAuth(Connection c) throws Exception {
    migrate(c, AUTH_APP_ID, "auth.db", List.of(Schema::createAuthV1));
  }

  public static void migrateVd(Connection c) throws Exception {
    migrate(c, VD_APP_ID, "vd_health.db", List.of(Schema::createVdV1));
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
        throw new IllegalStateException(name + " 不是本系統的資料庫（application_id 不符），拒絕使用");
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

  private static void createAirmsV1(Statement st) throws SQLException {
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

    // 每一份「內容不同」的原始 XML 一列；檔案本體在 xml_raw/ 目錄（gzip）。
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

    String sectionInts = String.join(",\n", SECTION_INT_FIELDS.stream().map(f -> "  " + f + " INTEGER").toList());
    // 每個路段的最新一筆。主鍵含國道與方向，避免國1/國3 里程相同時互相覆蓋。
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

  /**
   * v2：程式每次啟動一列，定時更新心跳。用來區分「系統沒在跑」與「來源沒資料」：
   * stopped_at_ms 為空表示不是正常停止（強制結束、當機、斷電），停機期間 = 下一次啟動 − 最後心跳。
   */
  private static void airmsV2(Statement st) throws SQLException {
    st.execute("""
        CREATE TABLE service_run (
          run_id            INTEGER PRIMARY KEY,
          started_at_ms     INTEGER NOT NULL,
          last_heartbeat_ms INTEGER NOT NULL,
          stopped_at_ms     INTEGER,
          host              TEXT,
          app_version       TEXT
        )""");
  }

  /** v3：系統設定頁修改的值。只存「與 config.json 預設不同」的項目，刪除該列即還原預設。 */
  private static void airmsV3(Statement st) throws SQLException {
    st.execute("""
        CREATE TABLE app_setting (
          setting_key   TEXT PRIMARY KEY,
          value_json    TEXT    NOT NULL,
          updated_at_ms INTEGER NOT NULL,
          updated_by    TEXT    NOT NULL
        ) WITHOUT ROWID""");
  }

  /**
   * v4：「路網匝道」管理頁對個別匝道圈的修改（顯示與否、RMS 代碼）。只存與路網設定檔不同的部分；
   * visible、rms_json 為 NULL 表示沿用設定檔。也把 v3 時期放在系統設定（ramps.*）的值搬過來。
   */
  private static void airmsV4(Statement st) throws SQLException {
    st.execute("""
        CREATE TABLE ramp_override (
          ramp_key      TEXT PRIMARY KEY,
          visible       INTEGER CHECK (visible IN (0,1)),
          rms_json      TEXT,
          updated_at_ms INTEGER NOT NULL,
          updated_by    TEXT    NOT NULL
        ) WITHOUT ROWID""");
    st.execute("""
        INSERT INTO ramp_override(ramp_key, visible, rms_json, updated_at_ms, updated_by)
        SELECT k,
               MAX(CASE WHEN f = 'visible' THEN json_extract(value_json, '$.v') END),
               MAX(CASE WHEN f = 'rmsId' AND json_extract(value_json, '$.v') <> ''
                        THEN json_array(json_extract(value_json, '$.v')) END),
               MAX(updated_at_ms), MAX(updated_by)
        FROM (SELECT substr(setting_key, 7, instr(substr(setting_key, 7), '.') - 1) AS k,
                     substr(substr(setting_key, 7), instr(substr(setting_key, 7), '.') + 1) AS f,
                     value_json, updated_at_ms, updated_by
              FROM app_setting WHERE setting_key LIKE 'ramps.%')
        GROUP BY k""");
    st.execute("DELETE FROM app_setting WHERE setting_key LIKE 'ramps.%'");
  }

  /**
   * auth.db：帳號、功能權限、登入紀錄、管理操作紀錄。密碼只存 Argon2id 雜湊；
   * 登入中的 Session 只放記憶體（重啟後需重新登入），不落地。
   */
  private static void createAuthV1(Statement st) throws SQLException {
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

  private static int pragmaInt(Connection c, String pragma) throws SQLException {
    try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("PRAGMA " + pragma)) {
      return rs.next() ? rs.getInt(1) : 0;
    }
  }

  private static boolean hasAnyTable(Connection c) throws SQLException {
    try (Statement st = c.createStatement();
         ResultSet rs = st.executeQuery("SELECT 1 FROM sqlite_schema WHERE type='table' LIMIT 1")) {
      return rs.next();
    }
  }
}

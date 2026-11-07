package tw.org.ncree.teles.airms.db;

import java.sql.Connection;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 每月一個檔的資料系列。檔名 &lt;key&gt;_yyyyMM.db，放在 db/&lt;key&gt;/ 資料夾；
 * 月份依資料時間（台北時區）決定，抓取紀錄依抓取開始時間。
 * 每個系列可在系統設定頁分別設定保留月數（retention.&lt;settingKey&gt;）。
 */
public enum Series {
  VD("vd", "VD", "retention.vdMonths", Db.Durability.FULL),
  SECTION("section", "路段", "retention.sectionMonths", Db.Durability.NORMAL),
  RMS("rms", "RMS", "retention.rmsMonths", Db.Durability.NORMAL),
  FEEDLOG("feedlog", "抓取紀錄", "retention.feedLogMonths", Db.Durability.NORMAL);

  private static final DateTimeFormatter YM = DateTimeFormatter.ofPattern("yyyyMM");

  private final String key;
  private final String label;
  private final String retentionSetting;
  private final Db.Durability durability;
  private final Pattern filePattern;

  Series(String key, String label, String retentionSetting, Db.Durability durability) {
    this.key = key;
    this.label = label;
    this.retentionSetting = retentionSetting;
    this.durability = durability;
    this.filePattern = Pattern.compile(key + "_(\\d{6})\\.db");
  }

  public String key() {
    return key;
  }

  public String label() {
    return label;
  }

  /** 系統設定中「保留月數」的設定鍵。 */
  public String retentionSetting() {
    return retentionSetting;
  }

  public Db.Durability durability() {
    return durability;
  }

  public String fileName(YearMonth month) {
    return key + "_" + YM.format(month) + ".db";
  }

  /** 檔名不符合本系列格式時回傳 null（例如 -wal、-shm、暫存檔或別的檔案）。 */
  public YearMonth monthOf(String fileName) {
    Matcher m = filePattern.matcher(fileName);
    if (!m.matches()) return null;
    try {
      return YearMonth.parse(m.group(1), YM);
    } catch (RuntimeException e) {
      return null;
    }
  }

  public static String unit(YearMonth month) {
    return YM.format(month);
  }

  /** 建立或升級這個系列的資料表。 */
  public void migrate(Connection c) throws Exception {
    switch (this) {
      case VD -> Schema.migrateVd(c);
      case SECTION -> Schema.migrateSection(c);
      case RMS -> Schema.migrateRms(c);
      case FEEDLOG -> Schema.migrateFeedLog(c);
    }
  }

  public int appId() {
    return switch (this) {
      case VD -> Schema.VD_APP_ID;
      case SECTION -> Schema.SECTION_APP_ID;
      case RMS -> Schema.RMS_APP_ID;
      case FEEDLOG -> Schema.FEEDLOG_APP_ID;
    };
  }
}

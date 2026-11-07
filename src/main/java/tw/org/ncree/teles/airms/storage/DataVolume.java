package tw.org.ncree.teles.airms.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import tw.org.ncree.teles.airms.db.Series;

/**
 * 資料目錄（正式機在 SD 卡上）。資料目錄裡必須有標記檔才允許使用，避免設定錯路徑時
 * 默默在別處建立一套新的空資料庫。
 *
 * <pre>
 * &lt;資料目錄&gt;/
 *   .airms-volume            標記檔（人工建立的空檔）
 *   .airms-lock              執行中鎖定
 *   cache/xml_raw/           原始 XML（依保留天數刪除）
 *   db/core.db               帳號、設定、操作紀錄
 *   db/vd/vd_yyyyMM.db       VD，每月一個檔
 *   db/section/…、db/rms/…、db/feedlog/…
 * </pre>
 */
public final class DataVolume {
  private final Path root;

  private DataVolume(Path root) {
    this.root = root;
  }

  public static DataVolume verify(Path dataDir, String markerName) {
    if (dataDir == null) throw new IllegalStateException("未設定資料目錄");
    Path root = dataDir.toAbsolutePath().normalize();
    if (!Files.isDirectory(root)) {
      throw new IllegalStateException("資料目錄不存在：" + root);
    }
    Path marker = root.resolve(markerName);
    if (!Files.isRegularFile(marker)) {
      throw new IllegalStateException("資料目錄缺少標記檔 " + marker
          + "：請確認 config.json 的 storage.dataDir 是正確的資料目錄，確認後手動建立這個空檔案再啟動。");
    }
    if (!Files.isWritable(root)) {
      throw new IllegalStateException("資料目錄不可寫入：" + root);
    }
    DataVolume v = new DataVolume(root);
    try {
      Files.createDirectories(v.cacheRoot());
      for (Series s : Series.values()) Files.createDirectories(v.monthlyDir(s));
    } catch (IOException e) {
      throw new IllegalStateException("無法建立資料子目錄：" + e.getMessage());
    }
    return v;
  }

  public Path root() {
    return root;
  }

  public Path cacheRoot() {
    return root.resolve("cache");
  }

  /** 原始 XML：cache/xml_raw/&lt;來源&gt;/yyyy/MM/dd/*.xml.gz。 */
  public Path rawXmlRoot() {
    return cacheRoot().resolve("xml_raw");
  }

  public Path dbRoot() {
    return root.resolve("db");
  }

  public Path coreDb() {
    return dbRoot().resolve("core.db");
  }

  public Path monthlyDir(Series s) {
    return dbRoot().resolve(s.key());
  }
}

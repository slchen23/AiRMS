package tw.org.ncree.teles.airms.storage;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 外接硬碟的防呆：資料目錄裡必須有標記檔，才允許開資料庫或寫原始 XML。
 * 硬碟沒掛上時，掛載點只是系統碟上的空目錄，沒有標記檔 → 拒絕啟動，
 * 避免把資料默默寫到 SD 卡或 C 槽上的新空資料庫。
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
      throw new IllegalStateException("資料目錄不存在：" + root + "（外接硬碟是否已接上？）");
    }
    Path marker = root.resolve(markerName);
    if (!Files.isRegularFile(marker)) {
      throw new IllegalStateException("資料目錄缺少標記檔 " + marker
          + "：可能是外接硬碟沒有掛上。確認是正確的硬碟後，手動建立這個空檔案再啟動。");
    }
    if (!Files.isWritable(root)) {
      throw new IllegalStateException("資料目錄不可寫入：" + root);
    }
    return new DataVolume(root);
  }

  public Path root() {
    return root;
  }

  public Path airmsDb() {
    return root.resolve("airms.db");
  }

  public Path vdHealthDb() {
    return root.resolve("vd_health.db");
  }

  public Path authDb() {
    return root.resolve("auth.db");
  }

  public Path rawXmlRoot() {
    return root.resolve("xml_raw");
  }
}

package tw.org.ncree.teles.airms.feed;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;
import tw.org.ncree.teles.airms.parse.FeedTime;

/**
 * 原始 XML 證據檔：xml_raw/<來源>/yyyy/MM/dd/yyyyMMdd_HHmmss_<sha前12碼>.xml.gz（收到時間，台北時區）。
 * 先寫同目錄暫存檔再原子改名，不會出現寫一半的正式檔。檔名只用時間與雜湊，不用來源內容。
 * 這個類別做的是阻塞式檔案 IO，只能在 worker 執行緒呼叫。
 */
public final class RawXmlArchive {
  private static final DateTimeFormatter DIR = DateTimeFormatter.ofPattern("yyyy/MM/dd").withZone(FeedTime.TAIPEI);
  private static final DateTimeFormatter NAME = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(FeedTime.TAIPEI);

  public record Stored(String relativePath, long storedBytes) { }

  private final Path root;

  public RawXmlArchive(Path root) {
    this.root = root.toAbsolutePath().normalize();
  }

  public static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public Stored store(FeedKind kind, byte[] bytes, String sha256, Instant receivedAt) throws IOException {
    String rel = kind.key() + "/" + DIR.format(receivedAt) + "/"
        + NAME.format(receivedAt) + "_" + sha256.substring(0, 12) + ".xml.gz";
    Path target = root.resolve(rel).normalize();
    if (!target.startsWith(root)) throw new IOException("歸檔路徑超出資料目錄");
    Files.createDirectories(target.getParent());
    if (Files.exists(target)) {
      return new Stored(rel, Files.size(target));
    }
    Path tmp = target.resolveSibling(".tmp-" + UUID.randomUUID() + ".gz");
    try {
      try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(tmp), 65536)) {
        out.write(bytes);
      }
      try {
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(tmp, target);
      } catch (FileAlreadyExistsException e) {
        // 另一次寫入已完成同一份內容
      }
      return new Stored(rel, Files.size(target));
    } finally {
      Files.deleteIfExists(tmp);
    }
  }
}

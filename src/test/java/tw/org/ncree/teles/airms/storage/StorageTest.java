package tw.org.ncree.teles.airms.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import tw.org.ncree.teles.airms.feed.FeedKind;
import tw.org.ncree.teles.airms.feed.RawXmlArchive;

class StorageTest {

  @Test
  void volumeRequiresMarkerFile() throws Exception {
    Path dir = Files.createTempDirectory("airms-vol");
    IllegalStateException e = assertThrows(IllegalStateException.class, () -> DataVolume.verify(dir, ".airms-volume"));
    assertTrue(e.getMessage().contains("標記檔"));
    Files.createFile(dir.resolve(".airms-volume"));
    DataVolume v = DataVolume.verify(dir, ".airms-volume");
    assertEquals(dir.resolve("vd_health.db").toAbsolutePath().normalize(), v.vdHealthDb());
    assertThrows(IllegalStateException.class, () -> DataVolume.verify(dir.resolve("missing"), ".airms-volume"));
  }

  @Test
  void archiveStoresGzipByTaipeiDateAndIsIdempotent() throws Exception {
    Path dir = Files.createTempDirectory("airms-raw");
    RawXmlArchive archive = new RawXmlArchive(dir);
    byte[] body = "<r time=\"2026-10-28 16:05:00\"/>".getBytes(StandardCharsets.UTF_8);
    String sha = RawXmlArchive.sha256(body);
    Instant at = Instant.parse("2026-10-28T16:30:00Z"); // 台北時間 10/29 00:30
    RawXmlArchive.Stored s1 = archive.store(FeedKind.VD, body, sha, at);
    assertEquals("vd/2026/10/29/20261029_003000_" + sha.substring(0, 12) + ".xml.gz", s1.relativePath());
    RawXmlArchive.Stored s2 = archive.store(FeedKind.VD, body, sha, at);
    assertEquals(s1, s2);
    try (InputStream in = new GZIPInputStream(Files.newInputStream(dir.resolve(s1.relativePath())))) {
      assertArrayEquals(body, in.readAllBytes());
    }
    try (var files = Files.walk(dir)) {
      assertTrue(files.noneMatch(p -> p.getFileName().toString().startsWith(".tmp-")));
    }
  }

  @Test
  void secondInstanceCannotUseSameDataDirectory() throws Exception {
    Path dir = Files.createTempDirectory("airms-lock");
    Files.createFile(dir.resolve(".airms-volume"));
    DataVolume v = DataVolume.verify(dir, ".airms-volume");
    DataDirLock first = DataDirLock.acquire(v);
    IllegalStateException e = assertThrows(IllegalStateException.class, () -> DataDirLock.acquire(v));
    assertTrue(e.getMessage().contains("另一個 AiRMS"));
    first.close();
    DataDirLock.acquire(v).close(); // 釋放後可以再取得
  }

  @Test
  void restartDoesNotDuplicateArchiveOrKeepStaleTemp() throws Exception {
    Path dir = Files.createTempDirectory("airms-raw2");
    RawXmlArchive archive = new RawXmlArchive(dir);
    byte[] body = "<r time=\"2026-10-28 16:05:00\"/>".getBytes(StandardCharsets.UTF_8);
    String sha = RawXmlArchive.sha256(body);
    RawXmlArchive.Stored s1 = archive.store(FeedKind.VD, body, sha, Instant.parse("2026-10-28T08:05:10Z"));
    // 重啟後同一天又收到同一份內容（時間不同）→ 沿用原檔
    RawXmlArchive.Stored s2 = archive.store(FeedKind.VD, body, sha, Instant.parse("2026-10-28T08:06:10Z"));
    assertEquals(s1.relativePath(), s2.relativePath());
    Path dayDir = dir.resolve(s1.relativePath()).getParent();
    Path staleTmp = dayDir.resolve(".tmp-abc.gz");
    Path freshTmp = dayDir.resolve(".tmp-def.gz");
    Files.writeString(staleTmp, "x");
    Files.writeString(freshTmp, "x");
    Files.setLastModifiedTime(staleTmp, java.nio.file.attribute.FileTime.from(Instant.parse("2026-10-28T07:50:00Z")));
    Files.setLastModifiedTime(freshTmp, java.nio.file.attribute.FileTime.from(Instant.parse("2026-10-28T08:09:00Z")));
    assertEquals(1, archive.cleanStaleTemp(Instant.parse("2026-10-28T08:10:00Z")));
    assertTrue(Files.exists(freshTmp));          // 可能正在寫的暫存檔不刪
    assertTrue(Files.exists(dir.resolve(s1.relativePath())));
  }
}

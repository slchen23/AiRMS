package tw.org.ncree.teles.airms.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.json.JsonObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import tw.org.ncree.teles.airms.feed.FeedKind;

class AppConfigTest {

  private static JsonObject example() throws Exception {
    return new JsonObject(Files.readString(Path.of("config.example.json"), StandardCharsets.UTF_8));
  }

  @Test
  void exampleConfigIsValid() throws Exception {
    AppConfig c = AppConfig.parse(example());
    assertEquals(8081, c.http().port());
    assertTrue(c.http().tlsEnabled());
    assertEquals(3, c.feeds().size());
    assertEquals(60, c.feed(FeedKind.VD).intervalSeconds());
    assertEquals(30, c.retention().rawXmlDays());
    assertEquals(12, c.retention().vdMonths());
    assertEquals(25, c.retention().sectionMonths());
    assertEquals(25, c.retention().rmsMonths());
    assertEquals(3, c.retention().feedLogMonths());
    assertFalse(c.backup().enabled());           // 範本預設不備份（測試機）
    assertEquals(java.time.LocalTime.of(2, 0), c.backup().dailyAt());
    assertEquals(30, c.backup().coreKeep());
    assertEquals(15, c.storage().minFreePercent());
    assertTrue(c.notices().isEmpty());
  }

  @Test
  void backupNeedsTargetWhenEnabled() throws Exception {
    JsonObject j = example();
    j.getJsonObject("backup").put("enabled", true).remove("targetDir");
    j.getJsonObject("backup").put("dailyAt", "25:00");
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> AppConfig.parse(j));
    assertTrue(e.getMessage().contains("backup.targetDir"));
    assertTrue(e.getMessage().contains("backup.dailyAt"));

    JsonObject same = example();
    same.getJsonObject("backup").put("enabled", true).put("targetDir", "E:/airms-data/backup");
    e = assertThrows(IllegalArgumentException.class, () -> AppConfig.parse(same));
    assertTrue(e.getMessage().contains("不可與 storage.dataDir"));

    JsonObject ok = example();
    ok.getJsonObject("backup").put("enabled", true).put("targetDir", "F:/backup").put("dailyAt", "03:30");
    AppConfig c = AppConfig.parse(ok);
    assertTrue(c.backup().enabled());
    assertEquals(java.time.LocalTime.of(3, 30), c.backup().dailyAt());
  }

  @Test
  void oldRetentionKeysAreIgnoredWithNotice() throws Exception {
    JsonObject j = example();
    j.getJsonObject("retention").put("sectionHistoryDays", 760).put("feedAttemptDays", 90);
    AppConfig c = AppConfig.parse(j);
    assertEquals(2, c.notices().size());
    assertTrue(c.notices().get(0).contains("sectionHistoryDays"));
  }

  @Test
  void collectsAllErrors() throws Exception {
    JsonObject j = example();
    j.getJsonObject("http").put("port", 70000);
    j.getJsonObject("feeds").getJsonObject("vd").put("url", "ftp://user:pw@host/x.xml");
    j.getJsonObject("retention").put("vdMonths", -1);
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> AppConfig.parse(j));
    assertTrue(e.getMessage().contains("http.port"));
    assertTrue(e.getMessage().contains("feeds.vd.url"));
    assertTrue(e.getMessage().contains("retention.vdMonths"));
    assertFalse(e.getMessage().contains("pw"));
  }

  @Test
  void rejectsUnsupportedSchemaVersion() throws Exception {
    JsonObject j = example().put("schemaVersion", 2);
    assertThrows(IllegalArgumentException.class, () -> AppConfig.parse(j));
  }
}

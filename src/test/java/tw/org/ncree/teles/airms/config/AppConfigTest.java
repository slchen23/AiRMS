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
    assertEquals(760, c.retention().sectionHistoryDays());
  }

  @Test
  void collectsAllErrors() throws Exception {
    JsonObject j = example();
    j.getJsonObject("http").put("port", 70000);
    j.getJsonObject("feeds").getJsonObject("vd").put("url", "ftp://user:pw@host/x.xml");
    j.getJsonObject("retention").put("sectionHistoryDays", 0);
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> AppConfig.parse(j));
    assertTrue(e.getMessage().contains("http.port"));
    assertTrue(e.getMessage().contains("feeds.vd.url"));
    assertTrue(e.getMessage().contains("sectionHistoryDays"));
    assertFalse(e.getMessage().contains("pw"));
  }

  @Test
  void rejectsUnsupportedSchemaVersion() throws Exception {
    JsonObject j = example().put("schemaVersion", 2);
    assertThrows(IllegalArgumentException.class, () -> AppConfig.parse(j));
  }
}

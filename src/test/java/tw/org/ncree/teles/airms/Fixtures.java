package tw.org.ncree.teles.airms;

import io.vertx.core.json.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import tw.org.ncree.teles.airms.config.AppConfig;

/** 測試共用：讀 src/test/resources/fixtures 的檔案。 */
public final class Fixtures {
  private Fixtures() { }

  public static final AppConfig.XmlLimits LIMITS = new AppConfig.XmlLimits(50000, 32, 1024);

  public static byte[] bytes(String name) {
    try (InputStream in = Fixtures.class.getClassLoader().getResourceAsStream("fixtures/" + name)) {
      if (in == null) throw new IllegalArgumentException("找不到測試檔 " + name);
      return in.readAllBytes();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public static JsonObject sectionFilter() {
    return new JsonObject("{\"freewayIds\":[\"1\",\"3\"],\"fromMilepostMin\":99000,\"fromMilepostMax\":270000}");
  }

  public static JsonObject rmsFilter() {
    return new JsonObject("{\"eqIdPrefixes\":[\"RMS-N1\",\"RMS-N3\"]}");
  }
}

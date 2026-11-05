package tw.org.ncree.teles.airms.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

/** 路網路段查看頁：每筆路段在圖上的狀態、相鄰路段的空隙與重疊。 */
class SectionCoverageTest {

  private static JsonObject item(String fw, String dir, String fromName, int from, String toName, int to, boolean stale) {
    return new JsonObject().put("freewayId", fw).put("directionId", dir).put("fromName", fromName).put("from", from)
        .put("toName", toName).put("to", to).put("speed", 90).put("volume", 100).put("cls", "FREE").put("stale", stale)
        .put("feedTime", "2026-11-03 10:00:00");
  }

  private static JsonObject byKey(JsonArray arr, String key) {
    for (Object o : arr) if (key.equals(((JsonObject) o).getString("key"))) return (JsonObject) o;
    return null;
  }

  @Test
  void routesComeFromNetworkConfig() throws Exception {
    NetworkConfig net = NetworkConfig.load(null);
    assertEquals(4, net.routes().size());
    NetworkConfig.RouteDef n3s = net.route("3", "3");
    assertEquals("S", n3s.dir());
    assertEquals(269921, n3s.toMilepost());
    assertNull(net.route("5", "3"));
    assertEquals("99.4K", SectionCoverage.km(99400));
  }

  @Test
  void mapStatusAndSeams() throws Exception {
    NetworkConfig net = NetworkConfig.load(null);
    JsonArray items = new JsonArray()
        .add(item("1", "3", "頭份", 110400, "頭屋", 125940, false))
        .add(item("1", "3", "新竹系統", 99400, "頭份", 110400, false))          // 順序打亂也會依里程排
        .add(item("1", "3", "頭屋", 127000, "苗栗", 132000, false))            // 與上一段之間有空隙
        .add(item("1", "3", "苗栗", 131000, "銅鑼", 140000, false))            // 與上一段重疊
        .add(item("1", "3", "舊切法", 99400, "頭屋", 125940, true))             // 最新資料沒有：不算接縫
        .add(item("3", "3", "古坑系統", 269200, "古坑(南)", 271210, false))     // 超出圖面一部分
        .add(item("3", "4", "外", 300000, "更外", 310000, false))              // 完全在圖外
        .add(item("5", "3", "南港系統", 0, "石碇", 10000, false));              // 圖上沒有國5

    JsonObject r = SectionCoverage.analyze(net, items);
    JsonArray s = r.getJsonArray("sections");
    assertEquals("1|3|99400|110400", s.getJsonObject(0).getString("key"));
    assertEquals("國1 南下", s.getJsonObject(0).getString("routeLabel"));
    assertEquals("FULL", byKey(s, "1|3|99400|110400").getString("mapStatus"));
    JsonObject partial = byKey(s, "3|3|269200|271210");
    assertEquals("PARTIAL", partial.getString("mapStatus"));
    assertEquals(269921, partial.getInteger("drawnTo"));
    assertEquals("OUTSIDE", byKey(s, "3|4|300000|310000").getString("mapStatus"));
    assertEquals("NO_ROUTE", byKey(s, "5|3|0|10000").getString("mapStatus"));
    assertEquals("NO_ROUTE", s.getJsonObject(s.size() - 1).getString("mapStatus"));   // 沒有路線的排最後

    JsonArray issues = r.getJsonArray("issues");
    assertEquals(2, issues.size());
    assertEquals("GAP", issues.getJsonObject(0).getString("kind"));
    assertEquals("125.9K", issues.getJsonObject(0).getString("fromKm"));
    assertEquals("127.0K", issues.getJsonObject(0).getString("toKm"));
    assertEquals("OVERLAP", issues.getJsonObject(1).getString("kind"));
    assertEquals(131000, issues.getJsonObject(1).getInteger("from"));
    assertEquals(132000, issues.getJsonObject(1).getInteger("to"));

    JsonObject c = r.getJsonObject("counts");
    assertEquals(8, c.getInteger("total"));
    assertEquals(7, c.getInteger("current"));
    assertEquals(1, c.getInteger("notInLatest"));
    assertEquals(5, c.getInteger("full"));
    assertEquals(1, c.getInteger("partial"));
    assertEquals(1, c.getInteger("outside"));
    assertEquals(1, c.getInteger("noRoute"));
  }
}

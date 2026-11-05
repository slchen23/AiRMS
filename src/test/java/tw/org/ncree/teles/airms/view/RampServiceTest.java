package tw.org.ncree.teles.airms.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import tw.org.ncree.teles.airms.db.Db;
import tw.org.ncree.teles.airms.db.Schema;

/** 路網匝道管理：隱藏、改 RMS 代碼、還原預設、重新啟動後保留。 */
class RampServiceTest {

  static <T> T await(Future<T> f) throws Exception {
    try {
      return f.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    } catch (ExecutionException e) {
      if (e.getCause() instanceof Exception ex) throw ex;
      throw e;
    }
  }

  static String invalid(Future<?> f) throws Exception {
    try {
      await(f);
    } catch (RampService.InvalidRampException e) {
      return e.getMessage();
    }
    throw new AssertionError("預期失敗但成功了");
  }

  private static JsonObject ramp(JsonObject net, String key) {
    for (Object o : net.getJsonArray("nodes")) {
      JsonObject ramps = ((JsonObject) o).getJsonObject("ramps");
      for (String d : ramps.fieldNames()) {
        if (key.equals(ramps.getJsonObject(d).getString("key"))) return ramps.getJsonObject(d);
      }
    }
    return null;
  }

  @Test
  void hideShowAndReplaceRms() throws Exception {
    Vertx vertx = Vertx.vertx();
    Db db = new Db(vertx, Files.createTempDirectory("airms-ramp").resolve("airms.db"), "t-ramp", Db.Durability.NORMAL);
    try {
      await(db.run(c -> {
        Schema.migrateAirms(c);
        return null;
      }));
      NetworkConfig network = NetworkConfig.load(null);
      RampService s = new RampService(db, network);
      await(s.load());

      // 預設：大雅北上隱藏、大雅南下顯示
      JsonObject net = s.effectiveNetwork();
      assertNull(ramp(net, "n1-174200-n"));
      assertEquals("rms", ramp(net, "n1-174200-s").getString("kind"));

      // 隱藏大雅南下；RMS 資料不受影響（只是畫面不畫）
      RampService.Change ch = await(s.update("n1-174200-s", true, false, false, null, "admin", 10));
      assertTrue(ch.before().getBoolean("visible"));
      assertFalse(ch.after().getBoolean("visible"));
      assertNull(ramp(s.effectiveNetwork(), "n1-174200-s"));
      assertEquals(network.json(), NetworkConfig.load(null).json());   // 原始設定沒被改

      // 打開大雅北上並填 RMS 代碼
      await(s.update("n1-174200-n", true, true, true, List.of(" RMS-N1-N-174-I-SN-1-北上入口-大雅 "), "admin", 11));
      JsonObject r = ramp(s.effectiveNetwork(), "n1-174200-n");
      assertEquals("rms", r.getString("kind"));
      assertEquals("RMS-N1-N-174-I-SN-1-北上入口-大雅", r.getJsonArray("rms").getString(0));

      // 再存一次相同內容：沒有變更，最後修改時間不動
      RampService.Change same = await(s.update("n1-174200-n", true, true, true,
          List.of("RMS-N1-N-174-I-SN-1-北上入口-大雅"), "other", 99));
      assertFalse(same.changed());
      assertEquals(11L, (long) s.row("n1-174200-n", Set.of()).getLong("updatedAt"));
      assertTrue(ch.changed());

      // 檢查：格式、超過 3 個、與其他匝道重複（含相容字）、同一圈重複、不存在的匝道
      assertTrue(invalid(s.update("n1-118000-n", false, null, true, List.of("N1-118"), "admin", 12)).contains("RMS-"));
      assertTrue(invalid(s.update("n1-118000-n", false, null, true,
          List.of("RMS-A-1", "RMS-A-2", "RMS-A-3", "RMS-A-4"), "admin", 12)).contains("最多"));
      assertTrue(invalid(s.update("n1-118000-n", false, null, true,
          List.of("RMS-N1-N-110-I-SN-1-北上入口-頭份"), "admin", 12)).contains("頭份"));
      assertTrue(invalid(s.update("n1-118000-n", false, null, true, List.of("RMS-A-1", "RMS-A-1"), "admin", 12))
          .contains("重複"));
      assertTrue(invalid(s.update("nope", true, false, false, null, "admin", 12)).contains("找不到"));

      // 改回與預設相同 = 不存修改
      await(s.update("n1-174200-s", true, true, false, null, "admin", 13));
      assertFalse(s.list(Set.of()).stream().map(o -> (JsonObject) o)
          .filter(o -> o.getString("key").equals("n1-174200-s")).findFirst().orElseThrow().getBoolean("overridden"));

      // 重新載入（模擬重啟）後保留修改；資料狀態標示
      RampService again = new RampService(db, network);
      await(again.load());
      JsonObject row = again.row("n1-174200-n", Set.of(NetworkConfig.normId("RMS-N1-N-174-I-SN-1-北上入口-大雅")));
      assertTrue(row.getBoolean("visible"));
      assertTrue(row.getBoolean("overridden"));
      assertEquals("admin", row.getString("updatedBy"));
      JsonArray live = row.getJsonArray("live");
      assertTrue(live.getJsonObject(0).getBoolean("present"));

      // 還原預設；沒修改過的還原不算變更
      assertTrue(await(again.reset("n1-174200-n", "admin", 20)).changed());
      assertFalse(await(again.reset("n1-118000-n", "admin", 21)).changed());
      assertNull(ramp(again.effectiveNetwork(), "n1-174200-n"));
      long rows = await(db.run(c -> {
        try (var st = c.createStatement(); var rs = st.executeQuery("SELECT COUNT(*) FROM ramp_override")) {
          rs.next();
          return rs.getLong(1);
        }
      }));
      assertEquals(0, rows);
    } finally {
      await(db.close());
      await(vertx.close());
    }
  }
}

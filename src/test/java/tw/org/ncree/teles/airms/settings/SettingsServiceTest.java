package tw.org.ncree.teles.airms.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import tw.org.ncree.teles.airms.config.AppConfig;
import tw.org.ncree.teles.airms.db.Db;
import tw.org.ncree.teles.airms.db.Schema;
import tw.org.ncree.teles.airms.feed.FeedKind;
import tw.org.ncree.teles.airms.view.NetworkConfig;
import tw.org.ncree.teles.airms.view.TrafficRules;

class SettingsServiceTest {

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
    } catch (SettingsService.InvalidSettingException e) {
      return e.getMessage();
    }
    throw new AssertionError("預期失敗但成功了");
  }

  @Test
  void screenlineSettingsComeFromNetworkConfig() throws Exception {
    Vertx vertx = Vertx.vertx();
    Db db = new Db(vertx, Files.createTempDirectory("airms-settings2").resolve("core.db"), "t-settings2",
        Db.Durability.NORMAL);
    try {
      await(db.run(c -> {
        Schema.migrateCore(c);
        return null;
      }));
      AppConfig config = AppConfig.parse(new JsonObject(
          Files.readString(Path.of("config.example.json"), StandardCharsets.UTF_8)));
      SettingsService s = new SettingsService(db, config, NetworkConfig.load(null));
      await(s.load());
      assertEquals("VD-N1-S-249.200-M-LOOP", s.stringValue(SettingsService.screenlineKey("n1-250-s", "vdId")));
      assertEquals(4200, s.intValue(SettingsService.screenlineKey("n1-250-s", "volLimit")));
      assertTrue(s.boolValue("view.requireLogin"));          // 預設首頁與即時監視都要登入
      assertTrue(invalid(s.update(SettingsService.screenlineKey("n1-250-s", "vdId"), "N1 246", "admin", 1))
          .contains("VD-"));
      await(s.update(SettingsService.screenlineKey("n1-250-s", "vdId"), " VD-N1-S-246.150-M-RS ", "admin", 1));
      assertEquals("VD-N1-S-246.150-M-RS", s.stringValue(SettingsService.screenlineKey("n1-250-s", "vdId")));
      assertTrue(invalid(s.update(SettingsService.screenlineKey("n1-250-s", "volLimit"), 0, "admin", 1))
          .contains("1～100000"));
    } finally {
      await(db.close());
      await(vertx.close());
    }
  }

  @Test
  void overridesArePersistedValidatedAndResettable() throws Exception {
    Vertx vertx = Vertx.vertx();
    Path dir = Files.createTempDirectory("airms-settings");
    Db db = new Db(vertx, dir.resolve("core.db"), "t-settings", Db.Durability.NORMAL);
    try {
      await(db.run(c -> {
        Schema.migrateCore(c);
        return null;
      }));
      AppConfig config = AppConfig.parse(new JsonObject(
          Files.readString(Path.of("config.example.json"), StandardCharsets.UTF_8)));
      SettingsService s = new SettingsService(db, config);
      await(s.load());
      assertEquals(60, s.feedIntervalSeconds(FeedKind.VD));
      assertTrue(s.feedEnabled(FeedKind.RMS));
      assertEquals(30, s.intValue("auth.sessionIdleMinutes"));

      assertTrue(invalid(s.update("feeds.vd.intervalSeconds", 5, "admin", 1)).contains("10～3600"));
      assertTrue(invalid(s.update("feeds.vd.intervalSeconds", 30.5, "admin", 1)).contains("整數"));
      assertTrue(invalid(s.update("feeds.vd.enabled", "yes", "admin", 1)).contains("是/否"));
      assertTrue(invalid(s.update("feeds.vd.url", "ftp://host/x.xml", "admin", 1)).contains("http"));
      assertTrue(invalid(s.update("feeds.vd.url", "https://u:p@host/x.xml", "admin", 1)).contains("帳密"));
      assertTrue(invalid(s.update("feeds.vd.url", "", "admin", 1)).contains("必須有網址"));
      assertTrue(invalid(s.update("no.such.key", 1, "admin", 1)).contains("未定義"));

      assertEquals(60, await(s.update("feeds.vd.intervalSeconds", 120, "admin", 1)));
      assertEquals(120, s.feedIntervalSeconds(FeedKind.VD));
      await(s.update("feeds.rms.enabled", false, "admin", 1));
      assertFalse(s.feedEnabled(FeedKind.RMS));
      await(s.update("feeds.section.url", "http://10.0.0.5/section.xml", "admin", 1));

      // 重新啟動後讀回覆寫值
      SettingsService reloaded = new SettingsService(db, config);
      await(reloaded.load());
      assertEquals(120, reloaded.feedIntervalSeconds(FeedKind.VD));
      assertFalse(reloaded.feedEnabled(FeedKind.RMS));
      assertEquals("http://10.0.0.5/section.xml", reloaded.feedUrl(FeedKind.SECTION).toString());

      // 改回與預設相同的值 = 刪除覆寫
      await(reloaded.update("feeds.vd.intervalSeconds", 60, "admin", 2));
      await(reloaded.reset("feeds.rms.enabled", "admin", 2));
      long overridden = reloaded.toJson().stream().filter(o -> ((JsonObject) o).getBoolean("overridden")).count();
      assertEquals(1, overridden);
      long rows = await(db.run(c -> {
        try (var st = c.createStatement(); var rs = st.executeQuery("SELECT COUNT(*) FROM app_setting")) {
          rs.next();
          return rs.getLong(1);
        }
      }));
      assertEquals(1, rows);
    } finally {
      await(db.close());
      await(vertx.close());
    }
  }

  @Test
  void speedThresholdsAreValidatedAsOneSetting() throws Exception {
    Vertx vertx = Vertx.vertx();
    Db db = new Db(vertx, Files.createTempDirectory("airms-settings3").resolve("core.db"), "t-settings3",
        Db.Durability.NORMAL);
    try {
      await(db.run(c -> {
        Schema.migrateCore(c);
        return null;
      }));
      AppConfig config = AppConfig.parse(new JsonObject(
          Files.readString(Path.of("config.example.json"), StandardCharsets.UTF_8)));
      SettingsService s = new SettingsService(db, config);
      await(s.load());
      String key = SettingsService.SPEED_THRESHOLDS;
      assertEquals(TrafficRules.SpeedSteps.DEFAULT, s.speedSteps());
      assertEquals(10 * 60_000L, s.sectionStaleMs());
      assertEquals(90, s.intValue(SettingsService.TLS_EXPIRY_WARNING_DAYS));   // 憑證到期前 90 天開始提醒

      // 逗號、頓號、空白都可分隔，存成統一格式；四個一起改不會卡在「由小到大」
      await(s.update(key, "30、50 70,90", "admin", 1));
      assertEquals("30, 50, 70, 90", s.stringValue(key));
      assertEquals(new TrafficRules.SpeedSteps(30, 50, 70, 90, true), s.speedSteps());
      assertTrue(invalid(s.update(key, "30, 50, 70", "admin", 2)).contains("4 個"));
      assertTrue(invalid(s.update(key, "30, 70, 50, 90", "admin", 2)).contains("由小到大"));
      assertTrue(invalid(s.update(key, "30, 30, 70, 90", "admin", 2)).contains("由小到大"));
      assertTrue(invalid(s.update(key, "0, 50, 70, 90", "admin", 2)).contains("1～200"));
      assertTrue(invalid(s.update(key, "30, 50, 70, 900", "admin", 2)).contains("1～200"));
      assertTrue(invalid(s.update(key, "30, 50, 七十, 90", "admin", 2)).contains("整數"));
      assertTrue(invalid(s.update(key, 30, "admin", 2)).contains("逗號"));
      assertEquals("30, 50, 70, 90", s.stringValue(key));                  // 錯誤的不會存進去

      await(s.update(SettingsService.SPEED_LOW_NO_VOLUME, false, "admin", 3));
      await(s.update(SettingsService.SPEED_STALE_MINUTES, 30, "admin", 3));
      assertFalse(s.speedSteps().lowSpeedNoVolumeAsNoData());
      assertEquals(30 * 60_000L, s.sectionStaleMs());

      // 重新載入（模擬重啟）後保留；還原預設
      SettingsService again = new SettingsService(db, config);
      await(again.load());
      assertEquals(new TrafficRules.SpeedSteps(30, 50, 70, 90, false), again.speedSteps());
      await(again.reset(key, "admin", 4));
      assertEquals("20, 40, 60, 80", again.stringValue(key));
    } finally {
      await(db.close());
      await(vertx.close());
    }
  }
}

package tw.org.ncree.teles.airms.http;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import tw.org.ncree.teles.airms.auth.AuthService;
import tw.org.ncree.teles.airms.auth.Permission;
import tw.org.ncree.teles.airms.settings.SettingsService;
import tw.org.ncree.teles.airms.view.LiveViewService;
import tw.org.ncree.teles.airms.view.RampService;
import tw.org.ncree.teles.airms.view.TrafficRules;

/**
 * 即時監視 API（/api/view/*），只讀。預設不需登入（純監視頁依需求開放）；
 * 系統設定「即時監視需要登入」開啟後，需登入且有「監視」權限。
 */
final class ViewApi {
  static final String REQUIRE_LOGIN = "view.requireLogin";

  private final RampService ramps;
  private final LiveViewService live;
  private final SettingsService settings;

  ViewApi(RampService ramps, LiveViewService live, SettingsService settings) {
    this.ramps = ramps;
    this.live = live;
    this.settings = settings;
  }

  void mount(Router router) {
    router.route("/api/view/*").handler(this::guard);
    // 套用「路網匝道」管理頁修改（隱藏、RMS 代碼）後的路網；圖例文字依系統設定的速度分級門檻
    router.get("/api/view/network").handler(ctx ->
        Http.json(ctx, 200, withLegend(ramps.effectiveNetwork(), settings.speedSteps())));
    router.get("/api/view/live").handler(ctx -> Http.respond(ctx, live.current(), j -> Http.json(ctx, 200, j)));
  }

  /** 圖例文字換成目前門檻（顏色不變）；不修改傳入的物件。 */
  static JsonObject withLegend(JsonObject net, TrafficRules.SpeedSteps steps) {
    JsonObject out = net.copy();
    JsonArray legend = steps.legendJson();
    for (Object o : out.getJsonArray("speedClasses")) {
      JsonObject c = (JsonObject) o;
      for (Object l : legend) {
        JsonObject x = (JsonObject) l;
        if (x.getString("key").equals(c.getString("key"))) c.put("label", x.getString("label"));
      }
    }
    return out;
  }

  private void guard(RoutingContext ctx) {
    int status = accessStatus(ctx, settings);
    if (status == 401) Http.error(ctx, 401, "請先登入");
    else if (status == 403) Http.error(ctx, 403, "沒有監視權限");
    else ctx.next();
  }

  /** 200 可看、401 需登入、403 沒有權限。頁面守門也用這個判斷。 */
  static int accessStatus(RoutingContext ctx, SettingsService settings) {
    if (!settings.boolValue(REQUIRE_LOGIN)) return 200;
    AuthService.Principal p = Http.principal(ctx);
    if (p == null) return 401;
    return p.account().can(Permission.VIEW) ? 200 : 403;
  }
}

package tw.org.ncree.teles.airms.http;

import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import java.util.function.LongSupplier;
import tw.org.ncree.teles.airms.auth.AuthService;
import tw.org.ncree.teles.airms.auth.Permission;
import tw.org.ncree.teles.airms.control.AirmsControlService;
import tw.org.ncree.teles.airms.control.AirmsProtocol;

/**
 * AiRMS 控制 API（/api/airms/*）：一律需要登入、已變更初始密碼，且有「AiRMS 控制」權限（管理者本來就有）。
 * 不受「首頁與即時監視需要登入」設定影響。寫入請求的 CSRF 與來源檢查由 AuthApi.identify 統一處理。
 */
final class ControlApi {
  private static final long DAY_MS = 24L * 60 * 60 * 1000;

  private final AirmsControlService airms;
  private final LongSupplier clock;

  ControlApi(AirmsControlService airms, LongSupplier clock) {
    this.airms = airms;
    this.clock = clock;
  }

  void mount(Router router) {
    router.route("/api/airms/*").handler(this::guard);
    router.get("/api/airms/status").handler(ctx -> Http.respond(ctx, airms.status(), j -> Http.json(ctx, 200, j)));
    router.post("/api/airms/settings").handler(this::set);
    router.get("/api/airms/log").handler(this::log);
  }

  /** 200 可用、401 需登入、403 沒有權限；頁面守門也用這個判斷（必須改密碼另外處理）。 */
  static int accessStatus(RoutingContext ctx) {
    AuthService.Principal p = Http.principal(ctx);
    if (p == null) return 401;
    return p.account().can(Permission.AIRMS_CONTROL) ? 200 : 403;
  }

  private void guard(RoutingContext ctx) {
    int status = accessStatus(ctx);
    if (status == 401) {
      Http.error(ctx, 401, "請先登入");
    } else if (Http.principal(ctx).account().mustChangePassword()) {
      Http.json(ctx, 403, new JsonObject().put("error", "請先變更密碼").put("mustChangePassword", true));
    } else if (status == 403) {
      Http.error(ctx, 403, "沒有「AiRMS 控制」權限");
    } else {
      ctx.next();
    }
  }

  private void set(RoutingContext ctx) {
    JsonObject b = Http.body(ctx);
    Object s = b.getValue("sensitivity");
    if (!(s instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue())
        || !AirmsProtocol.validSensitivity(n.intValue())) {
      throw new Http.BadRequest("儀控敏感性必須是 " + AirmsProtocol.SENSITIVITY_MIN + "～" + AirmsProtocol.SENSITIVITY_MAX
          + "，每次 " + AirmsProtocol.SENSITIVITY_STEP);
    }
    Object on = b.getValue("on");
    if (!(on instanceof Boolean onValue)) throw new Http.BadRequest("AiRMS 開/關必須是 true 或 false");
    AuthService.Principal p = Http.principal(ctx);
    Http.respond(ctx, airms.set(p.account().username(), Http.ip(ctx), n.intValue(), onValue),
        j -> Http.json(ctx, 200, j));
  }

  /** 設定紀錄：from/to 為毫秒（預設最近 30 天，最長 366 天），limit 預設 500。 */
  private void log(RoutingContext ctx) {
    long now = clock.getAsLong();
    long to = parse(ctx.queryParams().get("to"), now + 1);
    long from = parse(ctx.queryParams().get("from"), to - 30 * DAY_MS);
    if (from >= to) throw new Http.BadRequest("起始時間必須早於結束時間");
    if (to - from > 366 * DAY_MS) throw new Http.BadRequest("查詢區間最長 366 天");
    int limit = (int) Math.min(5000, Math.max(1, parse(ctx.queryParams().get("limit"), 500)));
    Http.respond(ctx, airms.log(from, to, limit), rows -> Http.json(ctx, 200, new JsonObject().put("rows", rows)
        .put("device", airms.deviceText())));
  }

  private static long parse(String s, long def) {
    if (s == null || s.isBlank()) return def;
    try {
      return Long.parseLong(s.trim());
    } catch (NumberFormatException e) {
      throw new Http.BadRequest("參數格式錯誤");
    }
  }
}

package tw.org.ncree.teles.airms.http;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.core.Future;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.LongSupplier;
import tw.org.ncree.teles.airms.auth.Account;
import tw.org.ncree.teles.airms.auth.AuthService;
import tw.org.ncree.teles.airms.auth.Permission;
import tw.org.ncree.teles.airms.settings.SettingsService;
import tw.org.ncree.teles.airms.view.LiveViewService;
import tw.org.ncree.teles.airms.view.NetworkConfig;
import tw.org.ncree.teles.airms.view.RampService;
import tw.org.ncree.teles.airms.view.SectionCoverage;

/**
 * 管理工具 API（/api/admin/*）：使用者管理、登入管理、系統設定。全部需要管理者身分；
 * 必須變更密碼的帳號在改密碼前不能使用。每個異動都寫入 audit_log。
 */
final class AdminApi {
  private static final long DAY_MS = 24L * 60 * 60 * 1000;

  private final AuthService auth;
  private final SettingsService settings;
  private final RampService ramps;
  private final LiveViewService live;
  private final NetworkConfig network;
  private final LongSupplier clock;

  AdminApi(AuthService auth, SettingsService settings, RampService ramps, LiveViewService live, NetworkConfig network,
           LongSupplier clock) {
    this.auth = auth;
    this.settings = settings;
    this.ramps = ramps;
    this.live = live;
    this.network = network;
    this.clock = clock;
  }

  void mount(Router router) {
    router.route("/api/admin/*").handler(this::requireAdmin);
    router.get("/api/admin/meta").handler(this::meta);
    router.get("/api/admin/users").handler(ctx -> {
      JsonArray out = new JsonArray();
      auth.listAccounts().forEach(a -> out.add(a.toJson()));
      Http.json(ctx, 200, new JsonObject().put("users", out));
    });
    router.post("/api/admin/users").handler(this::createUser);
    router.put("/api/admin/users/:id").handler(this::updateUser);
    router.post("/api/admin/users/:id/password").handler(this::resetPassword);
    router.post("/api/admin/users/:id/unlock").handler(ctx -> Http.respond(ctx,
        auth.unlock(Http.principal(ctx), pathId(ctx), Http.ip(ctx)), v -> Http.noContent(ctx)));
    router.get("/api/admin/sessions").handler(ctx -> {
      String mine = Http.principal(ctx).session().publicId();
      JsonArray list = auth.activeSessions();
      list.forEach(o -> ((JsonObject) o).put("current", mine.equals(((JsonObject) o).getString("id"))));
      Http.json(ctx, 200, new JsonObject().put("sessions", list));
    });
    router.delete("/api/admin/sessions/:sid").handler(ctx -> Http.respond(ctx,
        auth.revokeSession(Http.principal(ctx), ctx.pathParam("sid"), Http.ip(ctx)), v -> Http.noContent(ctx)));
    router.get("/api/admin/login-history").handler(this::loginHistory);
    router.get("/api/admin/audit").handler(this::auditLog);
    router.get("/api/admin/settings").handler(ctx ->
        Http.json(ctx, 200, new JsonObject().put("settings", settings.toJson())));
    router.put("/api/admin/settings/:key").handler(this::updateSetting);
    router.delete("/api/admin/settings/:key").handler(this::resetSetting);
    router.get("/api/admin/ramps").handler(ctx -> Http.respond(ctx, liveRmsIds(), ids -> {
      // 資料中有、但沒有對應到任何匝道圈的 RMS（例如新設的 RMS），方便管理者挑選填入
      Set<String> assigned = ramps.assignedRmsIds();
      JsonArray unassigned = new JsonArray();
      ids.stream().filter(id -> !assigned.contains(id)).sorted().forEach(unassigned::add);
      Http.json(ctx, 200, new JsonObject().put("ramps", ramps.list(ids)).put("unassignedRms", unassigned)
          .put("liveRmsCount", ids.size()));
    }));
    router.put("/api/admin/ramps/:key").handler(this::updateRamp);
    router.delete("/api/admin/ramps/:key").handler(this::resetRamp);
    // 路網路段（查看用）：來源 XML 的每一筆路段畫在圖上哪裡、目前速度分級
    router.get("/api/admin/sections").handler(ctx -> Http.respond(ctx, live.current(), j -> {
      JsonObject sec = j.getJsonObject("sections");
      JsonObject out = SectionCoverage.analyze(network, sec.getJsonArray("items"))
          .put("feedTime", sec.getString("feedTime")).put("stale", sec.getBoolean("stale"))
          .put("speedClasses", j.getJsonArray("speedClasses"))
          .put("staleMinutes", settings.intValue(SettingsService.SPEED_STALE_MINUTES));
      Http.json(ctx, 200, out);
    }));
  }

  // ---------------------------------------------------------------- 路網匝道

  /** 目前 RMS 資料中有（且未過舊）的設備代碼；取不到時當作空集合，不影響管理頁。 */
  private Future<Set<String>> liveRmsIds() {
    return live.current().map(j -> {
      Set<String> ids = new HashSet<>();
      for (Object o : j.getJsonObject("rms").getJsonArray("items")) {
        JsonObject r = (JsonObject) o;
        if (!r.getBoolean("stale", false)) ids.add(r.getString("id"));
      }
      return ids;
    }).recover(e -> Future.succeededFuture(Set.of()));
  }

  private void updateRamp(RoutingContext ctx) {
    String key = ctx.pathParam("key");
    JsonObject b = Http.body(ctx);
    boolean setVisible = b.containsKey("visible");
    Object v = b.getValue("visible");
    if (v != null && !(v instanceof Boolean)) throw new Http.BadRequest("visible 必須是 true、false 或 null");
    boolean setRms = b.containsKey("rms");
    List<String> rms = null;
    Object r = b.getValue("rms");
    if (r != null) {
      if (!(r instanceof JsonArray arr)) throw new Http.BadRequest("rms 必須是陣列或 null");
      rms = new ArrayList<>();
      for (Object o : arr) {
        if (!(o instanceof String s)) throw new Http.BadRequest("rms 的每一項必須是文字");
        rms.add(s);
      }
    }
    if (!setVisible && !setRms) throw new Http.BadRequest("沒有要修改的項目");
    AuthService.Principal p = Http.principal(ctx);
    Http.respond(ctx, ramps.update(key, setVisible, (Boolean) v, setRms, rms, p.account().username(), clock.getAsLong())
            .compose(ch -> auditRamp(ctx, p, "RAMP_UPDATE", key, ch)).compose(x -> liveRmsIds()),
        ids -> Http.json(ctx, 200, ramps.row(key, ids)));
  }

  private void resetRamp(RoutingContext ctx) {
    String key = ctx.pathParam("key");
    AuthService.Principal p = Http.principal(ctx);
    Http.respond(ctx, ramps.reset(key, p.account().username(), clock.getAsLong())
            .compose(ch -> auditRamp(ctx, p, "RAMP_RESET", key, ch)).compose(x -> liveRmsIds()),
        ids -> Http.json(ctx, 200, ramps.row(key, ids)));
  }

  private Future<Void> auditRamp(RoutingContext ctx, AuthService.Principal p, String action, String key,
                                 RampService.Change ch) {
    if (!ch.changed()) return Future.succeededFuture();
    return auth.audit(p, action, ramps.label(key), new JsonObject().put("key", key).put("before", ch.before()).put("after", ch.after()),
        Http.ip(ctx));
  }

  private void requireAdmin(RoutingContext ctx) {
    AuthService.Principal p = Http.principal(ctx);
    if (p == null) {
      Http.error(ctx, 401, "請先登入");
    } else if (!p.account().isAdmin()) {
      Http.error(ctx, 403, "需要管理者權限");
    } else if (p.account().mustChangePassword()) {
      Http.json(ctx, 403, new JsonObject().put("error", "請先變更密碼").put("mustChangePassword", true));
    } else {
      ctx.next();
    }
  }

  /** 權限選項，讓前端不必寫死。 */
  private void meta(RoutingContext ctx) {
    JsonArray perms = new JsonArray();
    for (Permission p : Permission.values()) {
      perms.add(new JsonObject().put("key", p.name()).put("label", p.label()).put("description", p.description()));
    }
    Http.json(ctx, 200, new JsonObject().put("permissions", perms));
  }

  private void createUser(RoutingContext ctx) {
    JsonObject b = Http.body(ctx);
    Http.respond(ctx, auth.createAccount(Http.principal(ctx), Http.str(b, "username"), Http.str(b, "displayName"),
        role(b), Http.bool(b, "enabled", true), Http.longOrNull(b, "expiresAt"), permissions(b),
        Http.str(b, "password"), Http.ip(ctx)), a -> Http.json(ctx, 201, a.toJson()));
  }

  private void updateUser(RoutingContext ctx) {
    JsonObject b = Http.body(ctx);
    Http.respond(ctx, auth.updateAccount(Http.principal(ctx), pathId(ctx), Http.str(b, "displayName"), role(b),
        Http.bool(b, "enabled", true), Http.longOrNull(b, "expiresAt"), permissions(b), Http.ip(ctx)),
        a -> Http.json(ctx, 200, a.toJson()));
  }

  private void resetPassword(RoutingContext ctx) {
    JsonObject b = Http.body(ctx);
    Http.respond(ctx, auth.resetPassword(Http.principal(ctx), pathId(ctx), Http.str(b, "password"), Http.ip(ctx)),
        v -> Http.noContent(ctx));
  }

  private void loginHistory(RoutingContext ctx) {
    long[] range = range(ctx);
    Http.respond(ctx, auth.loginHistory(range[0], range[1], ctx.queryParams().get("username"),
        ctx.queryParams().get("outcome")), rows -> Http.json(ctx, 200, new JsonObject().put("rows", rows)));
  }

  private void auditLog(RoutingContext ctx) {
    long[] range = range(ctx);
    Http.respond(ctx, auth.auditLog(range[0], range[1]), rows -> Http.json(ctx, 200, new JsonObject().put("rows", rows)));
  }

  private void updateSetting(RoutingContext ctx) {
    String key = ctx.pathParam("key");
    JsonObject b = Http.body(ctx);
    if (!b.containsKey("value")) throw new Http.BadRequest("缺少 value");
    Object value = b.getValue("value");
    AuthService.Principal p = Http.principal(ctx);
    Http.respond(ctx, settings.update(key, value, p.account().username(), clock.getAsLong())
            .compose(old -> auth.audit(p, "SETTING_UPDATE", key,
                new JsonObject().put("from", old).put("to", settings.get(key)), Http.ip(ctx)))
            .compose(v -> refreshLiveIfNeeded(key)),
        v -> Http.json(ctx, 200, new JsonObject().put("settings", settings.toJson())));
  }

  private void resetSetting(RoutingContext ctx) {
    String key = ctx.pathParam("key");
    AuthService.Principal p = Http.principal(ctx);
    Http.respond(ctx, settings.reset(key, p.account().username(), clock.getAsLong())
            .compose(old -> auth.audit(p, "SETTING_RESET", key,
                new JsonObject().put("from", old).put("to", settings.get(key)), Http.ip(ctx)))
            .compose(v -> refreshLiveIfNeeded(key)),
        v -> Http.json(ctx, 200, new JsonObject().put("settings", settings.toJson())));
  }

  /** 改了路段速度或屏柵線設定，立刻重算即時資料（不必等下一輪 15 秒）；重算失敗不影響設定已儲存。 */
  private Future<Void> refreshLiveIfNeeded(String key) {
    if (!key.startsWith("speed.") && !key.startsWith("screenlines.")) return Future.succeededFuture();
    return live.refresh().<Void>mapEmpty().recover(e -> Future.succeededFuture());
  }

  /** 查詢區間：from/to 為毫秒；預設最近 7 天，最長 366 天。 */
  private long[] range(RoutingContext ctx) {
    long now = clock.getAsLong();
    long to = parseLong(ctx.queryParams().get("to"), now + 1);
    long from = parseLong(ctx.queryParams().get("from"), to - 7 * DAY_MS);
    if (from >= to) throw new Http.BadRequest("起始時間必須早於結束時間");
    if (to - from > 366 * DAY_MS) throw new Http.BadRequest("查詢區間最長 366 天");
    return new long[] {from, to};
  }

  private static long parseLong(String s, long def) {
    if (s == null || s.isBlank()) return def;
    try {
      return Long.parseLong(s.trim());
    } catch (NumberFormatException e) {
      throw new Http.BadRequest("時間格式錯誤");
    }
  }

  private static long pathId(RoutingContext ctx) {
    try {
      return Long.parseLong(ctx.pathParam("id"));
    } catch (NumberFormatException e) {
      throw new Http.BadRequest("帳號編號錯誤");
    }
  }

  private static Account.Role role(JsonObject b) {
    String r = Http.str(b, "role");
    if (r == null) return Account.Role.USER;
    try {
      return Account.Role.valueOf(r);
    } catch (IllegalArgumentException e) {
      throw new Http.BadRequest("角色錯誤");
    }
  }

  private static Set<Permission> permissions(JsonObject b) {
    Object v = b.getValue("permissions");
    Set<Permission> out = EnumSet.noneOf(Permission.class);
    if (v == null) return out;
    if (!(v instanceof JsonArray arr)) throw new Http.BadRequest("permissions 必須是陣列");
    for (Object o : arr) {
      out.add(Permission.parse(String.valueOf(o)).orElseThrow(() -> new Http.BadRequest("未知的權限：" + o)));
    }
    return out;
  }
}

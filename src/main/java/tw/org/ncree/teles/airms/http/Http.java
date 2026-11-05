package tw.org.ncree.teles.airms.http;

import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import tw.org.ncree.teles.airms.auth.AuthException;
import tw.org.ncree.teles.airms.auth.AuthService;
import tw.org.ncree.teles.airms.settings.SettingsService;

/** 路由共用工具：JSON 回應、錯誤轉換、取得登入身分與請求內容。 */
final class Http {
  private static final Logger log = LogManager.getLogger(Http.class);
  static final String PRINCIPAL = "principal";

  private Http() { }

  static void json(RoutingContext ctx, int status, Object body) {
    if (ctx.response().ended()) return;
    String encoded = body instanceof JsonObject o ? o.encode()
        : body instanceof io.vertx.core.json.JsonArray a ? a.encode() : String.valueOf(body);
    ctx.response().setStatusCode(status)
        .putHeader(HttpHeaders.CONTENT_TYPE, "application/json; charset=utf-8")
        .end(Buffer.buffer(encoded));
  }

  static void error(RoutingContext ctx, int status, String message) {
    json(ctx, status, new JsonObject().put("error", message));
  }

  static void noContent(RoutingContext ctx) {
    if (!ctx.response().ended()) ctx.response().setStatusCode(204).end();
  }

  /** 把 Future 的失敗轉成 HTTP 回應：已知的錯誤顯示訊息，其他一律「系統錯誤」並記 log。 */
  static <T> void respond(RoutingContext ctx, Future<T> f, java.util.function.Consumer<T> onSuccess) {
    f.onSuccess(v -> {
      try {
        onSuccess.accept(v);
      } catch (RuntimeException e) {
        fail(ctx, e);
      }
    }).onFailure(e -> fail(ctx, e));
  }

  static void fail(RoutingContext ctx, Throwable e) {
    if (e instanceof AuthException ae) {
      error(ctx, ae.status(), ae.getMessage());
    } else if (e instanceof SettingsService.InvalidSettingException se) {
      error(ctx, 400, se.getMessage());
    } else if (e instanceof tw.org.ncree.teles.airms.view.RampService.InvalidRampException re) {
      error(ctx, 400, re.getMessage());
    } else if (e instanceof BadRequest br) {
      error(ctx, 400, br.getMessage());
    } else {
      log.error("處理請求失敗：{} {}", ctx.request().method(), ctx.normalizedPath(), e);
      error(ctx, 500, "系統錯誤");
    }
  }

  static AuthService.Principal principal(RoutingContext ctx) {
    return ctx.get(PRINCIPAL);
  }

  static String ip(RoutingContext ctx) {
    var a = ctx.request().remoteAddress();
    return a == null ? null : a.hostAddress();
  }

  static String userAgent(RoutingContext ctx) {
    return ctx.request().getHeader("User-Agent");
  }

  /** 讀 JSON 本文；不是 JSON 物件時丟 BadRequest。 */
  static JsonObject body(RoutingContext ctx) {
    try {
      JsonObject o = ctx.body() == null ? null : ctx.body().asJsonObject();
      if (o == null) throw new BadRequest("請求內容必須是 JSON");
      return o;
    } catch (DecodeException | ClassCastException e) {
      throw new BadRequest("請求內容必須是 JSON");
    }
  }

  static String str(JsonObject o, String key) {
    Object v = o.getValue(key);
    if (v == null) return null;
    if (!(v instanceof String s)) throw new BadRequest(key + " 必須是文字");
    return s;
  }

  static boolean bool(JsonObject o, String key, boolean def) {
    Object v = o.getValue(key);
    if (v == null) return def;
    if (!(v instanceof Boolean b)) throw new BadRequest(key + " 必須是是/否");
    return b;
  }

  static Long longOrNull(JsonObject o, String key) {
    Object v = o.getValue(key);
    if (v == null) return null;
    if (!(v instanceof Number n)) throw new BadRequest(key + " 必須是數字");
    return n.longValue();
  }

  static final class BadRequest extends RuntimeException {
    private static final long serialVersionUID = 1L;

    BadRequest(String message) {
      super(message);
    }
  }
}

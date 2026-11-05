package tw.org.ncree.teles.airms.http;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.StaticHandler;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import tw.org.ncree.teles.airms.AppContext;
import tw.org.ncree.teles.airms.auth.AuthService;
import tw.org.ncree.teles.airms.feed.FeedKind;

/**
 * 路由與資安標頭。每個回應產生新的隨機 CSP nonce（不再用固定值）；
 * HTML 頁面中的 __CSP_NONCE__ 會換成這次的 nonce。請求本文有上限、不接受檔案上傳，
 * 任何錯誤都回應通用訊息，不外露例外細節。
 * 頁面權限：admin-*.html 需管理者、account.html 需登入；其餘頁面（監視類）不需登入。
 */
public final class WebServer {
  private static final Logger log = LogManager.getLogger(WebServer.class);
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final String NONCE_KEY = "cspNonce";
  private static final String NONCE_PLACEHOLDER = "__CSP_NONCE__";
  private static final long BODY_LIMIT = 64 * 1024;

  private WebServer() { }

  public static Router router(Vertx vertx, AppContext app) {
    Router router = Router.router(vertx);
    AuthApi authApi = new AuthApi(app.auth(), app.config().http().tlsEnabled());
    router.route().handler(WebServer::securityHeaders);
    router.route().method(HttpMethod.POST).method(HttpMethod.PUT).method(HttpMethod.DELETE).method(HttpMethod.PATCH)
        .handler(BodyHandler.create(false).setBodyLimit(BODY_LIMIT));
    router.route().handler(authApi::identify);

    router.get("/api/health").handler(ctx -> health(ctx, app));
    authApi.mount(router);
    new AdminApi(app.auth(), app.settings(), app.ramps(), app.liveView(), app.network(), app.clock()::millis).mount(router);
    new ViewApi(app.ramps(), app.liveView(), app.settings()).mount(router);
    router.route("/api/*").handler(ctx -> Http.error(ctx, 404, "找不到這個 API"));

    Map<String, String> htmlCache = new ConcurrentHashMap<>();
    router.getWithRegex("/(|[a-zA-Z0-9_-]+\\.html)").handler(ctx -> {
      if (pageAllowed(ctx, app)) html(ctx, htmlCache);
    });
    router.route().handler(StaticHandler.create("webroot").setIndexPage("index.html").setDirectoryListing(false)
        .setIncludeHidden(false));

    router.route().last().handler(ctx -> Http.error(ctx, 404, "找不到頁面"));
    // 處理器丟出的例外（例如請求內容格式錯誤）在這裡轉成適當的狀態碼；未預期的才記錄並回「系統錯誤」。
    router.errorHandler(500, ctx -> {
      if (ctx.failure() != null) Http.fail(ctx, ctx.failure());
      else Http.error(ctx, 500, "系統錯誤");
    });
    router.errorHandler(400, ctx -> Http.error(ctx, 400, "請求格式錯誤"));
    router.errorHandler(413, ctx -> Http.error(ctx, 413, "請求內容過大"));
    return router;
  }

  /** 頁面層級的權限檢查；不通過時已送出轉址或錯誤，回傳 false。API 另有各自的檢查。 */
  private static boolean pageAllowed(RoutingContext ctx, AppContext app) {
    String path = ctx.normalizedPath();
    AuthService.Principal p = Http.principal(ctx);
    if (path.equals("/setup.html")) {
      if (!app.auth().setupNeeded()) return redirect(ctx, "/login.html");
      return true;
    }
    if (path.equals("/view2.html")) {
      int status = ViewApi.accessStatus(ctx, app.settings());
      if (status == 401) return redirect(ctx, "/login.html?next=" + URLEncoder.encode(path, StandardCharsets.UTF_8));
      if (status == 403) return forbidden(ctx, "這個頁面需要「監視」權限。");
      return true;
    }
    boolean adminPage = path.startsWith("/admin-");
    if (!adminPage && !path.equals("/account.html")) return true;
    if (p == null) return redirect(ctx, "/login.html?next=" + URLEncoder.encode(path, StandardCharsets.UTF_8));
    if (adminPage && p.account().mustChangePassword()) return redirect(ctx, "/account.html?mustChange=1");
    if (adminPage && !p.account().isAdmin()) return forbidden(ctx, "這個頁面需要管理者權限。");
    return true;
  }

  private static boolean forbidden(RoutingContext ctx, String message) {
    ctx.response().setStatusCode(403).putHeader(HttpHeaders.CONTENT_TYPE, "text/html; charset=utf-8")
        .end("<!DOCTYPE html><html lang=\"zh-Hant-TW\"><meta charset=\"utf-8\"><title>AiRMS</title>"
            + "<p>" + message + "<a href=\"/index.html\">回首頁</a></p></html>");
    return false;
  }

  private static boolean redirect(RoutingContext ctx, String location) {
    ctx.response().setStatusCode(302).putHeader(HttpHeaders.LOCATION, location).end();
    return false;
  }

  /** 第三方套件放在 /vendor/<套件>-<版本>/，路徑含版本、內容不會變，可以長期快取；其他一律不快取。 */
  private static final String VENDOR_CACHE = "public, max-age=31536000, immutable";

  private static void securityHeaders(RoutingContext ctx) {
    byte[] bytes = new byte[18];
    RANDOM.nextBytes(bytes);
    String nonce = Base64.getEncoder().encodeToString(bytes);
    ctx.put(NONCE_KEY, nonce);
    HttpServerResponse r = ctx.response();
    r.putHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
    r.putHeader("X-Content-Type-Options", "nosniff");
    r.putHeader("X-Frame-Options", "SAMEORIGIN");
    r.putHeader("Referrer-Policy", "strict-origin-when-cross-origin");
    r.putHeader("Permissions-Policy", "geolocation=(), camera=(), microphone=()");
    r.putHeader("Cross-Origin-Opener-Policy", "same-origin");
    r.putHeader("Cross-Origin-Resource-Policy", "same-origin");
    r.putHeader("Cross-Origin-Embedder-Policy", "require-corp");
    r.putHeader("Cache-Control", ctx.normalizedPath().startsWith("/vendor/") ? VENDOR_CACHE : "no-store");
    r.putHeader("Content-Security-Policy", String.join(" ",
        "default-src 'self';",
        "script-src 'self' 'nonce-" + nonce + "' 'strict-dynamic';",
        "style-src 'self';",
        "img-src 'self' data:;",
        "font-src 'self';",
        "connect-src 'self';",
        "object-src 'none';",
        "base-uri 'self';",
        "frame-ancestors 'self';",
        "form-action 'self';"));
    ctx.next();
  }

  private static void health(RoutingContext ctx, AppContext app) {
    Set<FeedKind> enabled = EnumSet.noneOf(FeedKind.class);
    for (FeedKind k : FeedKind.values()) if (app.settings().feedEnabled(k)) enabled.add(k);
    JsonObject feeds = app.feedStatus().summary(enabled);
    boolean degraded = feeds.stream().anyMatch(e -> "DEGRADED".equals(((JsonObject) e.getValue()).getString("state")));
    Http.json(ctx, 200, new JsonObject().put("status", degraded ? "DEGRADED" : "OK").put("feeds", feeds));
  }

  private static void html(RoutingContext ctx, Map<String, String> cache) {
    String path = ctx.normalizedPath();
    String file = path.equals("/") ? "index.html" : path.substring(1);
    String page = cache.computeIfAbsent(file, WebServer::loadPage);
    if (page.isEmpty()) {
      ctx.next();
      return;
    }
    ctx.response().putHeader(HttpHeaders.CONTENT_TYPE, "text/html; charset=utf-8")
        .end(page.replace(NONCE_PLACEHOLDER, (String) ctx.get(NONCE_KEY)));
  }

  /** 讀 classpath 的 webroot 頁面；不存在回空字串交給後面的 404。檔名已由路由的正規式限制。 */
  private static String loadPage(String file) {
    try (InputStream in = WebServer.class.getClassLoader().getResourceAsStream("webroot/" + file)) {
      return in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      log.warn("讀取頁面失敗：{}", file);
      return "";
    }
  }
}

package tw.org.ncree.teles.airms.http;

import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
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
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import tw.org.ncree.teles.airms.AppContext;
import tw.org.ncree.teles.airms.config.AppConfig;
import tw.org.ncree.teles.airms.feed.FeedKind;

/**
 * 路由與資安標頭。每個回應產生新的隨機 CSP nonce（不再用固定值）；
 * HTML 頁面中的 __CSP_NONCE__ 會換成這次的 nonce。請求本文有上限、不接受檔案上傳，
 * 任何錯誤都回應通用訊息，不外露例外細節。
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
    router.route().handler(WebServer::securityHeaders);
    router.route().method(HttpMethod.POST).handler(BodyHandler.create(false).setBodyLimit(BODY_LIMIT));

    router.get("/api/health").handler(ctx -> health(ctx, app));

    Map<String, String> htmlCache = new ConcurrentHashMap<>();
    router.getWithRegex("/(|[a-zA-Z0-9_-]+\\.html)").handler(ctx -> html(ctx, htmlCache));
    router.route().handler(StaticHandler.create("webroot").setIndexPage("index.html").setDirectoryListing(false)
        .setIncludeHidden(false));

    router.route().last().handler(ctx -> error(ctx, 404, "找不到頁面"));
    router.errorHandler(500, ctx -> {
      if (ctx.failure() != null) log.error("處理請求失敗：{}", ctx.normalizedPath(), ctx.failure());
      error(ctx, 500, "系統錯誤");
    });
    router.errorHandler(413, ctx -> error(ctx, 413, "請求內容過大"));
    return router;
  }

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
    r.putHeader("Cache-Control", "no-store");
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
    List<FeedKind> enabled = app.config().feeds().stream()
        .filter(AppConfig.Feed::enabled).map(AppConfig.Feed::kind).toList();
    JsonObject feeds = app.feedStatus().summary(enabled);
    boolean degraded = feeds.stream().anyMatch(e -> "DEGRADED".equals(((JsonObject) e.getValue()).getString("state")));
    json(ctx, 200, new JsonObject().put("status", degraded ? "DEGRADED" : "OK").put("feeds", feeds));
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

  static void json(RoutingContext ctx, int status, JsonObject body) {
    if (ctx.response().ended()) return;
    ctx.response().setStatusCode(status)
        .putHeader(HttpHeaders.CONTENT_TYPE, "application/json; charset=utf-8")
        .end(Buffer.buffer(body.encode()));
  }

  static void error(RoutingContext ctx, int status, String message) {
    json(ctx, status, new JsonObject().put("error", message));
  }
}

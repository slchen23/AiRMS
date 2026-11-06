package tw.org.ncree.teles.airms.http;

import io.vertx.core.http.Cookie;
import io.vertx.core.http.CookieSameSite;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.HostAndPort;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import java.net.URI;
import java.util.Set;
import tw.org.ncree.teles.airms.auth.AuthService;

/**
 * 登入相關路由與共用的身分檢查。
 * Cookie：HttpOnly、SameSite=Strict、HTTPS 時加 Secure；只放隨機 token。
 * 會改變資料的請求（POST/PUT/DELETE）一律要帶 X-CSRF-Token（登入後由 /api/auth/me 取得），
 * 且若有 Origin 標頭必須是本站。
 */
final class AuthApi {
  static final String COOKIE = "airms_sid";
  static final String CSRF_HEADER = "X-CSRF-Token";
  private static final Set<HttpMethod> UNSAFE = Set.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE,
      HttpMethod.PATCH);
  /** 還沒登入就要能呼叫的寫入端點（本身另有防護）。 */
  private static final Set<String> CSRF_EXEMPT = Set.of("/api/auth/login", "/api/setup");

  private final AuthService auth;
  private final boolean secureCookie;

  AuthApi(AuthService auth, boolean secureCookie) {
    this.auth = auth;
    this.secureCookie = secureCookie;
  }

  /** 每個請求：讀 Cookie 找出登入身分；寫入請求檢查來源與 CSRF。 */
  void identify(RoutingContext ctx) {
    Cookie c = ctx.request().getCookie(COOKIE);
    if (c != null) {
      AuthService.Principal p = auth.authenticate(c.getValue());
      if (p != null) ctx.put(Http.PRINCIPAL, p);
    }
    if (UNSAFE.contains(ctx.request().method()) && ctx.normalizedPath().startsWith("/api/")) {
      String origin = ctx.request().getHeader("Origin");
      if (origin != null && !sameOrigin(ctx, origin)) {
        Http.error(ctx, 403, "不允許跨站請求");
        return;
      }
      if (!CSRF_EXEMPT.contains(ctx.normalizedPath())) {
        AuthService.Principal p = Http.principal(ctx);
        if (p == null) {
          Http.error(ctx, 401, "請先登入");
          return;
        }
        String token = ctx.request().getHeader(CSRF_HEADER);
        if (token == null || !token.equals(p.session().csrfToken())) {
          Http.error(ctx, 403, "請重新整理頁面後再試");
          return;
        }
      }
    }
    ctx.next();
  }

  /** Origin 的 scheme、主機、port 都要與這次請求相同（省略的 port 以 80/443 計）。 */
  static boolean sameOrigin(RoutingContext ctx, String origin) {
    HostAndPort a = ctx.request().authority();
    if (a == null) return false;
    try {
      URI o = URI.create(origin);
      boolean ssl = ctx.request().isSSL();
      if (!(ssl ? "https" : "http").equalsIgnoreCase(o.getScheme()) || o.getHost() == null) return false;
      int def = ssl ? 443 : 80;
      int originPort = o.getPort() < 0 ? def : o.getPort();
      int requestPort = a.port() < 0 ? def : a.port();
      return bare(a.host()).equalsIgnoreCase(bare(o.getHost())) && originPort == requestPort;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  private static String bare(String host) {
    return host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
  }

  void mount(Router router) {
    router.get("/api/setup/status").handler(ctx ->
        Http.json(ctx, 200, new JsonObject().put("needed", auth.setupNeeded())));
    router.post("/api/setup").handler(this::setup);
    router.post("/api/auth/login").handler(this::login);
    router.post("/api/auth/logout").handler(this::logout);
    router.get("/api/auth/me").handler(this::me);
    router.post("/api/auth/password").handler(this::changePassword);
  }

  private void setup(RoutingContext ctx) {
    JsonObject b = Http.body(ctx);
    Http.respond(ctx, auth.setup(Http.str(b, "code"), Http.str(b, "username"), Http.str(b, "displayName"),
        Http.str(b, "password"), Http.ip(ctx)), a -> Http.json(ctx, 200, new JsonObject().put("username", a.username())));
  }

  private void login(RoutingContext ctx) {
    JsonObject b = Http.body(ctx);
    Cookie current = ctx.request().getCookie(COOKIE);
    Http.respond(ctx, auth.login(Http.str(b, "username"), Http.str(b, "password"), Http.ip(ctx), Http.userAgent(ctx),
            current == null ? null : current.getValue()),
        ok -> {
          ctx.response().addCookie(Cookie.cookie(COOKIE, ok.token()).setHttpOnly(true).setSecure(secureCookie)
              .setSameSite(CookieSameSite.STRICT).setPath("/"));
          Http.json(ctx, 200, meJson(ok.principal()));
        });
  }

  private void logout(RoutingContext ctx) {
    Cookie c = ctx.request().getCookie(COOKIE);
    if (c != null) auth.logout(c.getValue());
    ctx.response().addCookie(Cookie.cookie(COOKIE, "").setHttpOnly(true).setSecure(secureCookie)
        .setSameSite(CookieSameSite.STRICT).setPath("/").setMaxAge(0));
    Http.noContent(ctx);
  }

  /** 目前登入者；未登入時回 {"user": null}（不回 401，純監視頁面不登入是正常狀態）。 */
  private void me(RoutingContext ctx) {
    AuthService.Principal p = Http.principal(ctx);
    Http.json(ctx, 200, p == null ? new JsonObject().putNull("user") : meJson(p));
  }

  private void changePassword(RoutingContext ctx) {
    AuthService.Principal p = Http.principal(ctx);
    JsonObject b = Http.body(ctx);
    Http.respond(ctx, auth.changeOwnPassword(p, Http.str(b, "currentPassword"), Http.str(b, "newPassword"),
        Http.ip(ctx)), v -> Http.noContent(ctx));
  }

  private static JsonObject meJson(AuthService.Principal p) {
    return new JsonObject().put("user", p.account().toJson()).put("csrfToken", p.session().csrfToken());
  }
}

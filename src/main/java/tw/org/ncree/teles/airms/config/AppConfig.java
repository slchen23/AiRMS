package tw.org.ncree.teles.airms.config;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import tw.org.ncree.teles.airms.feed.FeedKind;

/**
 * 啟動時讀一次 config.json，驗證後成為不可變的設定快照；修改設定檔要重啟才生效。
 * 相對路徑一律以程式的工作目錄為準。設定檔不放任何密碼，密碼在 secretsFile。
 */
public record AppConfig(
    Http http,
    Path secretsFile,
    Storage storage,
    FeedHttp feedHttp,
    XmlLimits xmlLimits,
    List<Feed> feeds,
    Retention retention,
    Path networkFile) {

  public static final int SUPPORTED_SCHEMA_VERSION = 1;

  public record Http(String host, int port, boolean tlsEnabled, Path keystorePath) { }

  public record Storage(Path dataDir, String volumeMarker) { }

  public record FeedHttp(int connectTimeoutMs, int idleTimeoutMs, int downloadTimeoutMs, int maxBytes) { }

  public record XmlLimits(int maxElements, int maxDepth, int maxAttributeLength) { }

  /** filter 依來源不同：section 用 freewayIds/milepost，rms 用 eqIdPrefixes，vd 不過濾。 */
  public record Feed(FeedKind kind, boolean enabled, URI url, int intervalSeconds, JsonObject filter) { }

  /** 天數為正整數；VD 與原始 XML 永久保存，不在這裡設定。 */
  public record Retention(int runEveryHours, int sectionHistoryDays, int rmsHistoryDays, int feedAttemptDays) { }

  public Feed feed(FeedKind kind) {
    return feeds.stream().filter(f -> f.kind() == kind).findFirst()
        .orElseThrow(() -> new IllegalStateException("缺少來源設定：" + kind.key()));
  }

  public static AppConfig parse(JsonObject json) {
    List<String> errors = new ArrayList<>();
    int schema = json.getInteger("schemaVersion", -1);
    if (schema != SUPPORTED_SCHEMA_VERSION) {
      errors.add("schemaVersion 必須是 " + SUPPORTED_SCHEMA_VERSION + "（目前：" + schema + "）");
    }

    JsonObject http = section(json, "http", errors);
    JsonObject tls = http.getJsonObject("tls", new JsonObject());
    boolean tlsEnabled = tls.getBoolean("enabled", true);
    String keystore = tls.getString("keystorePath");
    if (tlsEnabled && blank(keystore)) errors.add("http.tls.keystorePath 未設定");
    Http httpCfg = new Http(
        http.getString("host", "0.0.0.0"),
        intIn(http, "port", 8081, 1, 65535, "http.port", errors),
        tlsEnabled,
        blank(keystore) ? null : Path.of(keystore));

    String secrets = json.getString("secretsFile", "secrets.json");

    JsonObject storage = section(json, "storage", errors);
    String dataDir = storage.getString("dataDir");
    if (blank(dataDir)) errors.add("storage.dataDir 未設定");
    String marker = storage.getString("volumeMarker", ".airms-volume");
    if (blank(marker) || marker.contains("/") || marker.contains("\\")) {
      errors.add("storage.volumeMarker 必須是單純的檔名");
    }
    Storage storageCfg = new Storage(blank(dataDir) ? null : Path.of(dataDir), marker);

    JsonObject feeds = section(json, "feeds", errors);
    JsonObject fh = feeds.getJsonObject("http", new JsonObject());
    FeedHttp feedHttp = new FeedHttp(
        intIn(fh, "connectTimeoutMs", 10000, 100, 120000, "feeds.http.connectTimeoutMs", errors),
        intIn(fh, "idleTimeoutMs", 15000, 100, 180000, "feeds.http.idleTimeoutMs", errors),
        intIn(fh, "downloadTimeoutMs", 30000, 500, 300000, "feeds.http.downloadTimeoutMs", errors),
        intIn(fh, "maxBytes", 8 * 1024 * 1024, 1024, 32 * 1024 * 1024, "feeds.http.maxBytes", errors));
    JsonObject xl = feeds.getJsonObject("xml", new JsonObject());
    XmlLimits xmlLimits = new XmlLimits(
        intIn(xl, "maxElements", 50000, 100, 500000, "feeds.xml.maxElements", errors),
        intIn(xl, "maxDepth", 32, 4, 128, "feeds.xml.maxDepth", errors),
        intIn(xl, "maxAttributeLength", 1024, 16, 65536, "feeds.xml.maxAttributeLength", errors));

    List<Feed> feedList = new ArrayList<>();
    for (FeedKind kind : FeedKind.values()) {
      JsonObject f = feeds.getJsonObject(kind.key());
      if (f == null) {
        errors.add("缺少 feeds." + kind.key());
        continue;
      }
      boolean enabled = f.getBoolean("enabled", true);
      URI uri = null;
      String url = f.getString("url");
      // 停用的來源也可以先填網址（之後在系統設定頁啟用）；有填就檢查格式。
      if (enabled || (url != null && !url.isBlank())) {
        try {
          uri = URI.create(url == null ? "" : url.trim());
          String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
          if (!(scheme.equals("https") || scheme.equals("http")) || uri.getHost() == null
              || uri.getRawUserInfo() != null) {
            errors.add("feeds." + kind.key() + ".url 必須是 http(s) 網址，且不可含帳密");
          }
        } catch (IllegalArgumentException e) {
          errors.add("feeds." + kind.key() + ".url 格式錯誤");
        }
      }
      int interval = intIn(f, "intervalSeconds", 60, 10, 3600, "feeds." + kind.key() + ".intervalSeconds", errors);
      JsonObject filter = f.getJsonObject("filter", new JsonObject());
      validateFilter(kind, filter, errors);
      feedList.add(new Feed(kind, enabled, uri, interval, filter.copy()));
    }

    JsonObject r = json.getJsonObject("retention", new JsonObject());
    Retention retention = new Retention(
        intIn(r, "runEveryHours", 12, 1, 168, "retention.runEveryHours", errors),
        intIn(r, "sectionHistoryDays", 760, 1, 36500, "retention.sectionHistoryDays", errors),
        intIn(r, "rmsHistoryDays", 760, 1, 36500, "retention.rmsHistoryDays", errors),
        intIn(r, "feedAttemptDays", 90, 1, 36500, "retention.feedAttemptDays", errors));

    // 路網設定檔：不填就用程式內建的 network/network.json；要增減匝道時可指定外部檔案。
    Object networkFile = json.getValue("networkFile");
    if (networkFile != null && !(networkFile instanceof String)) errors.add("networkFile 必須是檔案路徑文字");
    Path network = networkFile instanceof String s && !s.isBlank() ? Path.of(s.trim()) : null;

    if (!errors.isEmpty()) {
      throw new IllegalArgumentException("設定檔有誤：\n- " + String.join("\n- ", errors));
    }
    return new AppConfig(httpCfg, Path.of(secrets), storageCfg, feedHttp, xmlLimits, List.copyOf(feedList), retention,
        network);
  }

  private static void validateFilter(FeedKind kind, JsonObject filter, List<String> errors) {
    String prefix = "feeds." + kind.key() + ".filter";
    switch (kind) {
      case SECTION -> {
        if (!(filter.getValue("freewayIds", new JsonArray()) instanceof JsonArray)) {
          errors.add(prefix + ".freewayIds 必須是陣列");
        }
        Object min = filter.getValue("fromMilepostMin");
        Object max = filter.getValue("fromMilepostMax");
        if ((min != null && !(min instanceof Number)) || (max != null && !(max instanceof Number))) {
          errors.add(prefix + " 的里程上下限必須是數字");
        }
      }
      case RMS -> {
        if (!(filter.getValue("eqIdPrefixes", new JsonArray()) instanceof JsonArray)) {
          errors.add(prefix + ".eqIdPrefixes 必須是陣列");
        }
      }
      case VD -> { }
    }
  }

  private static JsonObject section(JsonObject json, String key, List<String> errors) {
    Object v = json.getValue(key);
    if (v instanceof JsonObject o) return o;
    errors.add("缺少區塊 " + key);
    return new JsonObject();
  }

  private static int intIn(JsonObject o, String key, int def, int min, int max, String name, List<String> errors) {
    Object v = o.getValue(key);
    if (v == null) return def;
    if (!(v instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue())) {
      errors.add(name + " 必須是整數");
      return def;
    }
    long value = n.longValue();
    if (value < min || value > max) {
      errors.add(name + " 必須介於 " + min + "～" + max + "（目前：" + value + "）");
      return def;
    }
    return (int) value;
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }
}

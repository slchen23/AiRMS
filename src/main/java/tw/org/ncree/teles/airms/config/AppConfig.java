package tw.org.ncree.teles.airms.config;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.net.URI;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import tw.org.ncree.teles.airms.control.AirmsProtocol;
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
    Backup backup,
    AirmsDevice airmsDevice,
    Path networkFile,
    List<String> notices) {

  public static final int SUPPORTED_SCHEMA_VERSION = 1;

  public record Http(String host, int port, boolean tlsEnabled, Path keystorePath) { }

  /** minFreePercent：資料目錄所在磁碟剩餘空間低於這個百分比時，首頁顯示警告。 */
  public record Storage(Path dataDir, String volumeMarker, int minFreePercent) { }

  public record FeedHttp(int connectTimeoutMs, int idleTimeoutMs, int downloadTimeoutMs, int maxBytes) { }

  public record XmlLimits(int maxElements, int maxDepth, int maxAttributeLength) { }

  /** filter 依來源不同：section 用 freewayIds/milepost，rms 用 eqIdPrefixes，vd 不過濾。 */
  public record Feed(FeedKind kind, boolean enabled, URI url, int intervalSeconds, JsonObject filter) { }

  /**
   * 資料保留的預設值（系統設定頁可改）。rawXmlDays：原始 XML 保留天數；logDays：程式 log 檔保留天數；
   * 其餘為月檔「本月以外再保留幾個月」。0 表示不刪除。runEveryHours 只能在 config.json 設定。
   */
  public record Retention(int runEveryHours, int rawXmlDays, int vdMonths, int sectionMonths, int rmsMonths,
                          int feedLogMonths, int logDays) { }

  /**
   * 備份到外接硬碟。每台機器各自設定（測試機設 enabled=false）；修改後重啟生效。
   * targetDir 內必須有 volumeMarker 標記檔才會寫入，避免硬碟沒接上時寫到 SD 卡的空資料夾。
   */
  public record Backup(boolean enabled, Path targetDir, String volumeMarker, LocalTime dailyAt, int coreKeep) { }

  /**
   * AiRMS 設備（AiRMS 控制頁讀取與設定）。host、port 是預設值，可在系統設定頁改；host 空白表示尚未設定。
   * 逾時只能在 config.json 設定：connectTimeoutMs 為每次連線的上限（失敗會再試 2 次），
   * responseTimeoutMs 為連上後等設備回覆的上限。
   */
  public record AirmsDevice(String host, int port, int connectTimeoutMs, int responseTimeoutMs) { }

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
    if (!plainFileName(marker)) errors.add("storage.volumeMarker 必須是單純的檔名");
    Storage storageCfg = new Storage(blank(dataDir) ? null : Path.of(dataDir), marker,
        intIn(storage, "minFreePercent", 15, 1, 90, "storage.minFreePercent", errors));

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

    List<String> notices = new ArrayList<>();
    JsonObject r = json.getJsonObject("retention", new JsonObject());
    for (String old : List.of("sectionHistoryDays", "rmsHistoryDays", "feedAttemptDays")) {
      if (r.containsKey(old)) {
        notices.add("config.json 的 retention." + old + " 已不再使用（改為月檔保留月數，見 config.example.json），已略過");
      }
    }
    Retention retention = new Retention(
        intIn(r, "runEveryHours", 12, 1, 168, "retention.runEveryHours", errors),
        intIn(r, "rawXmlDays", 30, 0, 36500, "retention.rawXmlDays", errors),
        intIn(r, "vdMonths", 12, 0, 1200, "retention.vdMonths", errors),
        intIn(r, "sectionMonths", 25, 0, 1200, "retention.sectionMonths", errors),
        intIn(r, "rmsMonths", 25, 0, 1200, "retention.rmsMonths", errors),
        intIn(r, "feedLogMonths", 3, 0, 1200, "retention.feedLogMonths", errors),
        intIn(r, "logDays", 90, 0, 36500, "retention.logDays", errors));

    Backup backup = parseBackup(json.getJsonObject("backup", new JsonObject()), storageCfg, errors);
    AirmsDevice airms = parseAirmsDevice(json.getValue("airmsDevice"), errors);

    // 路網設定檔：不填就用程式內建的 network/network.json；要增減匝道時可指定外部檔案。
    Object networkFile = json.getValue("networkFile");
    if (networkFile != null && !(networkFile instanceof String)) errors.add("networkFile 必須是檔案路徑文字");
    Path network = networkFile instanceof String s && !s.isBlank() ? Path.of(s.trim()) : null;

    if (!errors.isEmpty()) {
      throw new IllegalArgumentException("設定檔有誤：\n- " + String.join("\n- ", errors));
    }
    return new AppConfig(httpCfg, Path.of(secrets), storageCfg, feedHttp, xmlLimits, List.copyOf(feedList), retention,
        backup, airms, network, List.copyOf(notices));
  }

  private static AirmsDevice parseAirmsDevice(Object value, List<String> errors) {
    if (value != null && !(value instanceof JsonObject)) errors.add("airmsDevice 必須是物件");
    JsonObject a = value instanceof JsonObject o ? o : new JsonObject();
    Object hostValue = a.getValue("host");
    String host = "";
    if (hostValue != null && !(hostValue instanceof String)) {
      errors.add("airmsDevice.host 必須是文字");
    } else if (hostValue instanceof String s && !s.isBlank()) {
      host = s.trim();
      if (!AirmsProtocol.validHost(host)) errors.add("airmsDevice.host 必須是 IP 或主機名稱（不含 port 與 http://）");
    }
    return new AirmsDevice(host,
        intIn(a, "port", 9876, 1, 65535, "airmsDevice.port", errors),
        intIn(a, "connectTimeoutMs", 2000, 100, 30000, "airmsDevice.connectTimeoutMs", errors),
        intIn(a, "responseTimeoutMs", 5000, 500, 60000, "airmsDevice.responseTimeoutMs", errors));
  }

  private static Backup parseBackup(JsonObject b, Storage storage, List<String> errors) {
    Object enabledValue = b.getValue("enabled");
    if (enabledValue != null && !(enabledValue instanceof Boolean)) errors.add("backup.enabled 必須是 true 或 false");
    boolean enabled = Boolean.TRUE.equals(enabledValue);
    String target = b.getString("targetDir");
    Path targetDir = blank(target) ? null : Path.of(target.trim());
    if (enabled && targetDir == null) errors.add("backup.enabled 為 true 時必須設定 backup.targetDir");
    if (enabled && targetDir != null && storage.dataDir() != null) {
      Path t = targetDir.toAbsolutePath().normalize();
      Path d = storage.dataDir().toAbsolutePath().normalize();
      if (t.startsWith(d) || d.startsWith(t)) errors.add("backup.targetDir 不可與 storage.dataDir 相同或互相包含");
    }
    String marker = b.getString("volumeMarker", ".airms-backup");
    if (!plainFileName(marker)) errors.add("backup.volumeMarker 必須是單純的檔名");
    LocalTime dailyAt = LocalTime.of(2, 0);
    String at = b.getString("dailyAt");
    if (at != null) {
      try {
        dailyAt = LocalTime.parse(at.trim(), DateTimeFormatter.ofPattern("HH:mm"));
      } catch (DateTimeParseException e) {
        errors.add("backup.dailyAt 必須是 HH:mm，例如 02:00");
      }
    }
    int keep = intIn(b, "coreKeep", 30, 1, 3650, "backup.coreKeep", errors);
    return new Backup(enabled, targetDir, marker, dailyAt, keep);
  }

  private static boolean plainFileName(String s) {
    return !blank(s) && !s.contains("/") && !s.contains("\\") && !s.equals(".") && !s.equals("..");
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

package tw.org.ncree.teles.airms.view;

import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 路網設定檔（network.json）：路線、匝道圓圈、RMS 對應、屏柵線。view2、airms2、query 三頁共用。
 * 啟動時讀一次並檢查格式，有錯就列出全部錯誤、停止啟動；前端拿到的是檢查過的同一份內容。
 */
public final class NetworkConfig {
  public static final String BUILT_IN = "network/network.json";
  private static final Pattern SCREENLINE_ID = Pattern.compile("^[a-z0-9-]{1,32}$");
  private static final Pattern RAMP_KEY = Pattern.compile("^[a-z0-9-]{1,40}$");

  /** 屏柵線預設值；VD 與門檻可在系統設定頁覆寫。 */
  public record ScreenlineDef(String id, String label, String freewayId, String direction, String vdId,
                              int volLimit, int volContinuous) { }

  /**
   * 路網上的一個匝道圈（某交流道的某個方向）。kind：rms／outside（範圍外）／plain（無 RMS）。
   * rms 為設定檔中的 RMS 代碼；visible 為預設是否顯示。「路網匝道」管理頁可覆寫顯示與 RMS 代碼。
   */
  public record RampDef(String key, String label, String nodeId, String freewayId, String direction, String name,
                        String km, int milepost, String kind, List<String> rms, boolean visible) { }

  /** 一條畫在圖上的路線（某國道某方向），里程範圍為路線第一點到最後一點。dir 為 S／N。 */
  public record RouteDef(String freewayId, String dir, String directionId, int fromMilepost, int toMilepost) { }

  private final JsonObject json;
  private final List<RouteDef> routes;
  private final List<ScreenlineDef> screenlines;
  private final List<RampDef> ramps;
  private final Set<String> rmsIds;
  private final String source;

  private NetworkConfig(JsonObject json, List<RouteDef> routes, List<ScreenlineDef> screenlines, List<RampDef> ramps,
                        Set<String> rmsIds, String source) {
    this.json = json;
    this.routes = List.copyOf(routes);
    this.screenlines = List.copyOf(screenlines);
    this.ramps = List.copyOf(ramps);
    this.rmsIds = Set.copyOf(rmsIds);
    this.source = source;
  }

  /** file 為 null 時讀程式內建的設定檔。 */
  public static NetworkConfig load(Path file) throws IOException {
    if (file != null) {
      return parse(Files.readString(file, StandardCharsets.UTF_8), file.toAbsolutePath().toString());
    }
    try (InputStream in = NetworkConfig.class.getClassLoader().getResourceAsStream(BUILT_IN)) {
      if (in == null) throw new IOException("找不到內建的路網設定檔 " + BUILT_IN);
      return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8), "內建 " + BUILT_IN);
    }
  }

  public static NetworkConfig parse(String text, String source) {
    JsonObject json;
    try {
      json = new JsonObject(text);
    } catch (DecodeException e) {
      throw new IllegalArgumentException("路網設定檔（" + source + "）不是正確的 JSON");
    }
    List<String> errors = new ArrayList<>();
    if (json.getInteger("schemaVersion", -1) != 1) errors.add("schemaVersion 必須是 1");
    JsonArray viewBox = json.getJsonArray("viewBox");
    if (viewBox == null || viewBox.size() != 4 || !viewBox.stream().allMatch(v -> v instanceof Number)) {
      errors.add("viewBox 必須是 4 個數字");
    }
    checkSpeedClasses(json.getJsonArray("speedClasses"), errors);
    checkFreeways(json.getJsonArray("freeways"), errors);
    Set<String> rmsIds = new HashSet<>();
    List<RampDef> ramps = new ArrayList<>();
    checkNodes(json.getJsonArray("nodes"), rmsIds, ramps, errors);
    List<ScreenlineDef> screenlines = parseScreenlines(json.getJsonArray("screenlines"), errors);
    if (!errors.isEmpty()) {
      throw new IllegalArgumentException("路網設定檔（" + source + "）有誤：\n- " + String.join("\n- ", errors));
    }
    List<RouteDef> routes = new ArrayList<>();
    for (Object o : json.getJsonArray("freeways")) {
      JsonObject fw = (JsonObject) o;
      JsonObject rs = fw.getJsonObject("routes");
      for (String dir : rs.fieldNames()) {
        JsonArray pts = rs.getJsonObject(dir).getJsonArray("points");
        routes.add(new RouteDef(fw.getString("id"), dir, rs.getJsonObject(dir).getString("directionId"),
            (int) Math.round(pts.getJsonArray(0).getDouble(2)), (int) Math.round(pts.getJsonArray(pts.size() - 1).getDouble(2))));
      }
    }
    return new NetworkConfig(json, routes, screenlines, ramps, rmsIds, source);
  }

  /** 設備代碼比對前統一用 NFKC：來源資料有時用相容字（例如「北」U+F963）。 */
  public static String normId(String id) {
    return id == null ? null : Normalizer.normalize(id, Normalizer.Form.NFKC).trim();
  }

  public JsonObject json() {
    return json;
  }

  /** 圖上的路線（國道、方向、里程範圍），依設定檔順序。 */
  public List<RouteDef> routes() {
    return routes;
  }

  /** 依來源的 freewayId、directionId 找路線；找不到回傳 null。 */
  public RouteDef route(String freewayId, String directionId) {
    for (RouteDef r : routes) if (r.freewayId().equals(freewayId) && r.directionId().equals(directionId)) return r;
    return null;
  }

  public List<ScreenlineDef> screenlines() {
    return screenlines;
  }

  /** 所有匝道圈，依設定檔順序（國道、里程）。 */
  public List<RampDef> ramps() {
    return ramps;
  }

  /** 設定檔中所有 RMS 代碼（已正規化）。 */
  public Set<String> rmsIds() {
    return rmsIds;
  }

  public String source() {
    return source;
  }

  public int nodeCount() {
    return json.getJsonArray("nodes").size();
  }

  // ---------------------------------------------------------------- 檢查

  private static void checkSpeedClasses(JsonArray arr, List<String> errors) {
    Set<String> required = Set.of("NO_DATA", "C20", "C40", "C60", "C80", "FREE");
    Set<String> found = new HashSet<>();
    if (arr != null) {
      for (Object o : arr) {
        if (o instanceof JsonObject c && c.getValue("key") instanceof String k
            && c.getValue("color") instanceof String color && color.matches("^#[0-9A-Fa-f]{6}$")) {
          found.add(k);
        } else {
          errors.add("speedClasses 每一項要有 key 與 #RRGGBB 顏色");
        }
      }
    }
    if (!found.containsAll(required)) errors.add("speedClasses 必須包含 " + required);
  }

  private static void checkFreeways(JsonArray arr, List<String> errors) {
    if (arr == null || arr.isEmpty()) {
      errors.add("freeways 不可為空");
      return;
    }
    for (Object o : arr) {
      if (!(o instanceof JsonObject fw) || !(fw.getValue("id") instanceof String id)) {
        errors.add("freeways 每一項要有 id");
        continue;
      }
      JsonObject routes = fw.getJsonObject("routes");
      if (routes == null || routes.isEmpty()) {
        errors.add("國道 " + id + " 沒有 routes");
        continue;
      }
      for (String dir : routes.fieldNames()) {
        JsonObject r = routes.getJsonObject(dir);
        String where = "國道 " + id + " " + dir;
        if (r == null || !(r.getValue("directionId") instanceof String)) {
          errors.add(where + " 缺少 directionId");
          continue;
        }
        JsonArray pts = r.getJsonArray("points");
        if (pts == null || pts.size() < 2) {
          errors.add(where + " 路線點至少 2 個");
          continue;
        }
        double prev = Double.NEGATIVE_INFINITY;
        for (Object p : pts) {
          if (!(p instanceof JsonArray xy) || xy.size() != 3 || !xy.stream().allMatch(v -> v instanceof Number)) {
            errors.add(where + " 路線點必須是 [x, y, 里程]");
            break;
          }
          double m = xy.getDouble(2);
          if (m <= prev) {
            errors.add(where + " 路線點的里程必須遞增");
            break;
          }
          prev = m;
        }
      }
    }
  }

  private static void checkNodes(JsonArray arr, Set<String> rmsIds, List<RampDef> ramps, List<String> errors) {
    if (arr == null || arr.isEmpty()) {
      errors.add("nodes 不可為空");
      return;
    }
    Set<String> ids = new HashSet<>();
    for (Object o : arr) {
      if (!(o instanceof JsonObject n) || !(n.getValue("id") instanceof String id) || !(n.getValue("name") instanceof String)) {
        errors.add("nodes 每一項要有 id 與 name");
        continue;
      }
      if (!ids.add(id)) errors.add("節點 id 重複：" + id);
      if (!(n.getValue("label") instanceof JsonObject)) errors.add("節點 " + id + " 缺少 label");
      JsonObject rampObj = n.getJsonObject("ramps", new JsonObject());
      for (String dir : rampObj.fieldNames()) {
        JsonObject r = rampObj.getJsonObject(dir);
        String where = "節點 " + id + " " + dir;
        if (r == null || !(r.getValue("x") instanceof Number) || !(r.getValue("y") instanceof Number)) {
          errors.add(where + " 缺少座標");
          continue;
        }
        String kind = r.getString("kind", "");
        switch (kind) {
          case "outside", "plain" -> { }
          case "rms" -> {
            JsonArray list = r.getJsonArray("rms");
            if (list == null || list.isEmpty() || list.size() > 3) {
              errors.add(where + " 的 rms 要有 1～3 個設備代碼");
              break;
            }
            for (Object v : list) {
              if (!(v instanceof String s) || s.isBlank()) {
                errors.add(where + " 的 rms 代碼不可空白");
              } else if (!rmsIds.add(normId(s))) {
                errors.add("RMS 代碼重複：" + s);
              }
            }
          }
          default -> errors.add(where + " 的 kind 必須是 rms、outside 或 plain");
        }
        Object key = r.getValue("key");
        if (!(key instanceof String k) || !RAMP_KEY.matcher(k).matches()) {
          errors.add(where + " 的 key 必須是小寫英數字與 -");
        } else if (ramps.stream().anyMatch(x -> x.key().equals(k))) {
          errors.add("匝道圈 key 重複：" + k);
        } else if (!(r.getValue("visible", true) instanceof Boolean visible)) {
          errors.add(where + " 的 visible 必須是 true/false");
        } else {
          List<String> codes = new ArrayList<>();
          if ("rms".equals(kind) && r.getJsonArray("rms") != null) {
            r.getJsonArray("rms").forEach(v -> codes.add(String.valueOf(v)));
          }
          ramps.add(new RampDef(k, r.getString("label", id + " " + dir), id, n.getString("freeway", ""), dir,
              n.getString("name"), n.getString("km", ""), n.getInteger("milepost", 0), kind, List.copyOf(codes),
              visible));
        }
      }
    }
  }

  private static List<ScreenlineDef> parseScreenlines(JsonArray arr, List<String> errors) {
    List<ScreenlineDef> out = new ArrayList<>();
    if (arr == null) return out;
    Set<String> ids = new HashSet<>();
    for (Object o : arr) {
      if (!(o instanceof JsonObject s)) {
        errors.add("screenlines 每一項必須是物件");
        continue;
      }
      String id = s.getValue("id") instanceof String v ? v : "";
      if (!SCREENLINE_ID.matcher(id).matches()) {
        errors.add("屏柵線 id 必須是小寫英數字與 -：" + id);
        continue;
      }
      if (!ids.add(id)) errors.add("屏柵線 id 重複：" + id);
      String vdId = s.getValue("vdId") instanceof String v ? v.trim() : "";
      if (!validVdId(vdId)) errors.add("屏柵線 " + id + " 的 vdId 格式錯誤");
      int limit = s.getValue("volLimit") instanceof Number n ? n.intValue() : -1;
      int cont = s.getValue("volContinuous") instanceof Number n ? n.intValue() : -1;
      if (limit < 1 || cont < 1) errors.add("屏柵線 " + id + " 的門檻必須是正整數");
      if (!(s.getValue("bell") instanceof JsonObject)) errors.add("屏柵線 " + id + " 缺少 bell 位置");
      out.add(new ScreenlineDef(id, s.getString("label", id), s.getString("freeway", ""), s.getString("direction", ""),
          vdId, limit, cont));
    }
    return out;
  }

  /** RMS 代碼：RMS- 開頭、不含空白與控制字元，最長 100 字。 */
  public static boolean validRmsId(String s) {
    if (s == null || s.length() < 5 || s.length() > 100 || !s.startsWith("RMS-")) return false;
    for (char ch : s.toCharArray()) if (Character.isISOControl(ch) || Character.isWhitespace(ch)) return false;
    return true;
  }

  /** VD 代碼：VD- 開頭、不含控制字元，最長 100 字。 */
  public static boolean validVdId(String s) {
    if (s == null || s.length() < 4 || s.length() > 100 || !s.startsWith("VD-")) return false;
    for (char ch : s.toCharArray()) if (Character.isISOControl(ch) || Character.isWhitespace(ch)) return false;
    return true;
  }
}

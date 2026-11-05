package tw.org.ncree.teles.airms.view;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import tw.org.ncree.teles.airms.db.Db;

/**
 * 「路網匝道」管理：每個匝道圈可個別隱藏、修改 RMS 代碼（例如匝道廢除、新增 RMS、設備改名）。
 * 路網設定檔是預設值，修改的部分存在 airms.db 的 ramp_override；畫面拿到的路網都先套用這些修改。
 */
public final class RampService {
  private static final Logger log = LogManager.getLogger(RampService.class);
  public static final int MAX_RMS = 3;

  /** 對設定檔的修改；欄位為 null 表示沿用設定檔。 */
  public record Override(Boolean visible, List<String> rms, long updatedAtMs, String updatedBy) {
    boolean empty() {
      return visible == null && rms == null;
    }
  }

  /** 修改前後（寫操作紀錄用）。 */
  public record Change(JsonObject before, JsonObject after) {
    public boolean changed() {
      return !before.equals(after);
    }
  }

  public static final class InvalidRampException extends Exception {
    private static final long serialVersionUID = 1L;

    public InvalidRampException(String message) {
      super(message);
    }
  }

  private final Db db;
  private final NetworkConfig network;
  private final Map<String, NetworkConfig.RampDef> defs = new LinkedHashMap<>();
  private final Map<String, Override> overrides = new ConcurrentHashMap<>();

  public RampService(Db airmsDb, NetworkConfig network) {
    this.db = airmsDb;
    this.network = network;
    for (NetworkConfig.RampDef d : network.ramps()) defs.put(d.key(), d);
  }

  /** 啟動時載入修改；路網設定檔已沒有的匝道圈略過（資料保留，不刪）。 */
  public Future<Void> load() {
    return db.run(c -> {
      int unknown = 0;
      try (Statement st = c.createStatement();
           ResultSet rs = st.executeQuery("SELECT ramp_key, visible, rms_json, updated_at_ms, updated_by FROM ramp_override")) {
        while (rs.next()) {
          String key = rs.getString(1);
          if (!defs.containsKey(key)) {
            unknown++;
            continue;
          }
          int v = rs.getInt(2);
          Boolean visible = rs.wasNull() ? null : v == 1;
          String json = rs.getString(3);
          List<String> rms = json == null ? null : new JsonArray(json).stream().map(String::valueOf).toList();
          overrides.put(key, new Override(visible, rms, rs.getLong(4), rs.getString(5)));
        }
      }
      if (unknown > 0) log.warn("路網匝道修改中有 {} 筆對應的匝道已不在路網設定檔，已略過", unknown);
      if (!overrides.isEmpty()) log.info("路網匝道：套用 {} 筆管理頁修改", overrides.size());
      return null;
    });
  }

  // ---------------------------------------------------------------- 套用

  public boolean visible(String key) {
    Override o = overrides.get(key);
    return o != null && o.visible() != null ? o.visible() : defs.get(key).visible();
  }

  public List<String> rms(String key) {
    Override o = overrides.get(key);
    return o != null && o.rms() != null ? o.rms() : defs.get(key).rms();
  }

  private String kind(String key) {
    if (!rms(key).isEmpty()) return "rms";
    return "outside".equals(defs.get(key).kind()) ? "outside" : "plain";
  }

  /** 給畫面用的路網：隱藏的匝道圈拿掉、RMS 代碼換成修改後的。沒有修改時直接回傳原設定。 */
  public JsonObject effectiveNetwork() {
    if (overrides.isEmpty() && defs.values().stream().allMatch(NetworkConfig.RampDef::visible)) return network.json();
    JsonObject out = network.json().copy();
    for (Object o : out.getJsonArray("nodes")) {
      JsonObject ramps = ((JsonObject) o).getJsonObject("ramps");
      if (ramps == null) continue;
      for (String dir : List.copyOf(ramps.fieldNames())) {
        JsonObject r = ramps.getJsonObject(dir);
        String key = r.getString("key");
        if (key == null || !defs.containsKey(key)) continue;
        if (!visible(key)) {
          ramps.remove(dir);
          continue;
        }
        r.put("kind", kind(key));
        if (rms(key).isEmpty()) r.remove("rms");
        else r.put("rms", new JsonArray(new ArrayList<>(rms(key))));
      }
    }
    return out;
  }

  // ---------------------------------------------------------------- 管理頁

  /** 管理頁列表。liveIds：目前 RMS 資料中有的設備代碼（已正規化），用來標示哪些代碼有資料。 */
  public JsonArray list(Set<String> liveIds) {
    JsonArray out = new JsonArray();
    for (NetworkConfig.RampDef d : defs.values()) out.add(row(d, liveIds));
    return out;
  }

  /** 目前所有匝道圈用到的 RMS 代碼（含隱藏的，已正規化）。 */
  public Set<String> assignedRmsIds() {
    Set<String> out = new HashSet<>();
    for (String key : defs.keySet()) for (String id : rms(key)) out.add(NetworkConfig.normId(id));
    return out;
  }

  /** 匝道圈名稱（例如「國1北上 大雅 174K」）；找不到時回傳 key。 */
  public String label(String key) {
    NetworkConfig.RampDef d = defs.get(key);
    return d == null ? key : d.label();
  }

  public JsonObject row(String key, Set<String> liveIds) {
    return row(defs.get(key), liveIds);
  }

  private JsonObject row(NetworkConfig.RampDef d, Set<String> liveIds) {
    Override o = overrides.get(d.key());
    JsonArray live = new JsonArray();
    for (String id : rms(d.key())) {
      live.add(new JsonObject().put("id", id).put("present", liveIds.contains(NetworkConfig.normId(id))));
    }
    return new JsonObject()
        .put("key", d.key()).put("label", d.label()).put("freewayId", d.freewayId()).put("direction", d.direction())
        .put("name", d.name()).put("km", d.km()).put("milepost", d.milepost())
        .put("defaultKind", d.kind()).put("defaultVisible", d.visible()).put("defaultRms", new JsonArray(d.rms()))
        .put("kind", kind(d.key())).put("visible", visible(d.key())).put("rms", new JsonArray(rms(d.key())))
        .put("overrideVisible", o == null ? null : o.visible())
        .put("overrideRms", o == null || o.rms() == null ? null : new JsonArray(o.rms()))
        .put("overridden", o != null)
        .put("updatedAt", o == null ? null : o.updatedAtMs()).put("updatedBy", o == null ? null : o.updatedBy())
        .put("live", live);
  }

  /**
   * 修改一個匝道圈。setVisible／setRms 為 false 表示該欄不變；visible／rms 為 null 表示改回設定檔預設。
   * rms 為空清單表示「沒有 RMS」。
   */
  public Future<Change> update(String key, boolean setVisible, Boolean visible, boolean setRms, List<String> rms,
                               String actor, long nowMs) {
    NetworkConfig.RampDef d = defs.get(key);
    if (d == null) return Future.failedFuture(new InvalidRampException("找不到這個匝道圈"));
    Override cur = overrides.get(key);
    Boolean newVisible = setVisible ? visible : cur == null ? null : cur.visible();
    List<String> newRms;
    try {
      newRms = setRms ? clean(key, rms) : cur == null ? null : cur.rms();
    } catch (InvalidRampException e) {
      return Future.failedFuture(e);
    }
    // 跟預設相同的部分不必存
    if (newVisible != null && newVisible == d.visible()) newVisible = null;
    if (newRms != null && sameIds(newRms, d.rms())) newRms = null;
    // 沒有實際變更：不寫資料庫，最後修改時間也不動
    Boolean curVisible = cur == null ? null : cur.visible();
    List<String> curRms = cur == null ? null : cur.rms();
    if (java.util.Objects.equals(newVisible, curVisible)
        && (newRms == null ? curRms == null : curRms != null && sameIds(newRms, curRms))) {
      JsonObject same = snapshot(key);
      return Future.succeededFuture(new Change(same, same));
    }
    return save(key, new Override(newVisible, newRms, nowMs, actor));
  }

  public Future<Change> reset(String key, String actor, long nowMs) {
    if (!defs.containsKey(key)) return Future.failedFuture(new InvalidRampException("找不到這個匝道圈"));
    if (!overrides.containsKey(key)) {
      JsonObject same = snapshot(key);
      return Future.succeededFuture(new Change(same, same));
    }
    return save(key, new Override(null, null, nowMs, actor));
  }

  private Future<Change> save(String key, Override o) {
    JsonObject before = snapshot(key);
    return db.run(c -> {
      if (o.empty()) delete(c, key);
      else upsert(c, key, o);
      return null;
    }).map(v -> {
      if (o.empty()) overrides.remove(key);
      else overrides.put(key, o);
      return new Change(before, snapshot(key));
    });
  }

  private JsonObject snapshot(String key) {
    return new JsonObject().put("visible", visible(key)).put("rms", new JsonArray(new ArrayList<>(rms(key))));
  }

  /** 檢查 RMS 代碼：1～3 個、格式正確、不重複、沒有用在其他匝道圈。 */
  private List<String> clean(String key, List<String> rms) throws InvalidRampException {
    if (rms == null) return null;
    List<String> out = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (String raw : rms) {
      String t = raw == null ? "" : raw.trim();
      if (t.isEmpty()) continue;
      if (!NetworkConfig.validRmsId(t)) {
        throw new InvalidRampException("RMS 代碼必須是 RMS- 開頭的完整設備代碼，不可含空白：" + t);
      }
      if (!seen.add(NetworkConfig.normId(t))) throw new InvalidRampException("RMS 代碼重複：" + t);
      out.add(t);
    }
    if (out.size() > MAX_RMS) throw new InvalidRampException("一個匝道圈最多 " + MAX_RMS + " 台 RMS");
    for (NetworkConfig.RampDef other : defs.values()) {
      if (other.key().equals(key)) continue;
      for (String id : rms(other.key())) {
        if (seen.contains(NetworkConfig.normId(id))) {
          throw new InvalidRampException(id + " 已經用在「" + other.label() + "」");
        }
      }
    }
    return List.copyOf(out);
  }

  private static boolean sameIds(List<String> a, List<String> b) {
    if (a.size() != b.size()) return false;
    for (int i = 0; i < a.size(); i++) {
      if (!NetworkConfig.normId(a.get(i)).equals(NetworkConfig.normId(b.get(i)))) return false;
    }
    return true;
  }

  private static void upsert(Connection c, String key, Override o) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement("""
        INSERT INTO ramp_override(ramp_key, visible, rms_json, updated_at_ms, updated_by) VALUES (?,?,?,?,?)
        ON CONFLICT(ramp_key) DO UPDATE SET visible=excluded.visible, rms_json=excluded.rms_json,
          updated_at_ms=excluded.updated_at_ms, updated_by=excluded.updated_by""")) {
      ps.setString(1, key);
      if (o.visible() == null) ps.setNull(2, java.sql.Types.INTEGER);
      else ps.setInt(2, o.visible() ? 1 : 0);
      ps.setString(3, o.rms() == null ? null : new JsonArray(new ArrayList<>(o.rms())).encode());
      ps.setLong(4, o.updatedAtMs());
      ps.setString(5, o.updatedBy());
      ps.executeUpdate();
    }
  }

  private static void delete(Connection c, String key) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement("DELETE FROM ramp_override WHERE ramp_key=?")) {
      ps.setString(1, key);
      ps.executeUpdate();
    }
  }
}

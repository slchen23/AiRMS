package tw.org.ncree.teles.airms.parse;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tw.org.ncree.teles.airms.db.Schema;
import tw.org.ncree.teles.airms.feed.XmlNode;

/**
 * 匝道儀控運作資料（rms 元素）：全部欄位存原值（例如 rms_rate「閃黃」照存，不轉換）。
 * 某欄缺少時存 NULL、該列記為有問題但照存；舊系統會因此整份標為部分失敗，新系統不會。
 * eqId 空白的列是來源的無效列，略過不算錯誤。
 */
public final class RmsParser {
  private RmsParser() { }

  public record Row(String eqId, Map<String, String> values) { }

  public record Snapshot(String feedTimeRaw, long feedTimeMs, List<Row> rows, ParseStats stats) { }

  public record Filter(List<String> eqIdPrefixes) {
    public static Filter from(JsonObject json) {
      List<String> prefixes = new ArrayList<>();
      JsonArray arr = json.getJsonArray("eqIdPrefixes", new JsonArray());
      for (Object o : arr) prefixes.add(String.valueOf(o));
      return new Filter(List.copyOf(prefixes));
    }

    boolean accepts(String eqId) {
      if (eqIdPrefixes.isEmpty()) return true;
      for (String p : eqIdPrefixes) {
        if (eqId.startsWith(p) && eqId.length() > p.length()) return true;
      }
      return false;
    }
  }

  public static Snapshot parse(XmlNode root, Filter filter) throws FeedRejectedException {
    String timeRaw = root.attr("time");
    Long timeMs = FeedTime.toEpochMs(timeRaw);
    if (timeMs == null) throw new FeedRejectedException("檔頭時間無法解析：" + ParseStats.clean(timeRaw));

    ParseStats stats = new ParseStats();
    List<Row> rows = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    List<XmlNode> nodes = root.findAll("rms");
    for (XmlNode n : nodes) {
      String eqId = n.attr("eqId");
      if (eqId == null || eqId.isBlank() || !filter.accepts(eqId)) {
        stats.skipped();
        continue;
      }
      stats.row();
      if (!seen.add(eqId)) {
        stats.bad("RMS 重複：" + eqId);
        continue;
      }
      Map<String, String> values = new LinkedHashMap<>();
      List<String> missing = new ArrayList<>();
      for (String f : Schema.RMS_FIELDS) {
        String v = n.attr(f);
        if (v == null) missing.add(f);
        values.put(f, v);
      }
      if (!missing.isEmpty()) stats.bad("RMS " + eqId + " 缺少欄位：" + String.join(", ", missing));
      rows.add(new Row(eqId, values));
    }
    if (nodes.isEmpty()) throw new FeedRejectedException("檔案裡沒有任何 RMS 資料");
    if (rows.isEmpty()) throw new FeedRejectedException("沒有符合設定範圍的 RMS");
    return new Snapshot(timeRaw, timeMs, rows, stats);
  }
}

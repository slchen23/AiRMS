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
 * 路段交通資料：只保存設定範圍內的路段（預設國1、國3，起點里程 99000～270000）。
 * 某欄缺值或不是整數時存 NULL、該列記為有問題，但其他欄位照存，不整列丟掉。
 */
public final class SectionParser {
  private SectionParser() { }

  public record Row(String freewayId, String expresswayId, String directionId,
                    String fromLocation, String fromMilepost, String endLocation, String endMilepost,
                    Map<String, Integer> values) { }

  public record Snapshot(String feedTimeRaw, long feedTimeMs, List<Row> rows, ParseStats stats) { }

  public record Filter(Set<String> freewayIds, Double fromMilepostMin, Double fromMilepostMax) {
    public static Filter from(JsonObject json) {
      Set<String> ids = new HashSet<>();
      JsonArray arr = json.getJsonArray("freewayIds", new JsonArray());
      for (Object o : arr) ids.add(String.valueOf(o));
      Number min = (Number) json.getValue("fromMilepostMin");
      Number max = (Number) json.getValue("fromMilepostMax");
      return new Filter(Set.copyOf(ids), min == null ? null : min.doubleValue(), max == null ? null : max.doubleValue());
    }

    boolean accepts(String freewayId, double fromMilepost) {
      if (!freewayIds.isEmpty() && !freewayIds.contains(freewayId)) return false;
      if (fromMilepostMin != null && fromMilepost < fromMilepostMin) return false;
      return fromMilepostMax == null || fromMilepost <= fromMilepostMax;
    }
  }

  public static Snapshot parse(XmlNode root, Filter filter) throws FeedRejectedException {
    String timeRaw = root.attr("time");
    Long timeMs = FeedTime.toEpochMs(timeRaw);
    if (timeMs == null) throw new FeedRejectedException("檔頭時間無法解析：" + ParseStats.clean(timeRaw));

    ParseStats stats = new ParseStats();
    List<Row> rows = new ArrayList<>();
    Set<String> keys = new HashSet<>();
    List<XmlNode> nodes = root.findAll("traffic_data");
    for (XmlNode n : nodes) {
      String freeway = n.attr("freewayId");
      String direction = n.attr("directionId");
      String from = n.attr("from_milepost");
      String end = n.attr("end_milepost");
      Double fromValue = parseDouble(from);
      if (freeway == null || direction == null || fromValue == null || end == null || end.isBlank()) {
        stats.row();
        stats.bad("路段缺少國道、方向或里程：freeway=" + freeway + ", from=" + from);
        continue;
      }
      if (!filter.accepts(freeway, fromValue)) {
        stats.skipped();
        continue;
      }
      stats.row();
      String key = freeway + "|" + direction + "|" + from + "|" + end;
      if (!keys.add(key)) {
        stats.bad("路段重複：" + key);
        continue;
      }
      Map<String, Integer> values = new LinkedHashMap<>();
      List<String> problems = new ArrayList<>();
      for (String f : Schema.SECTION_INT_FIELDS) {
        String raw = n.attr(f);
        Integer v = parseInt(raw);
        if (v == null) problems.add(f + "=" + (raw == null ? "(缺)" : raw));
        values.put(f, v);
      }
      if (!problems.isEmpty()) stats.bad("路段 " + key + " 欄位無法使用：" + String.join(", ", problems));
      rows.add(new Row(freeway, n.attr("expresswayId"), direction, n.attr("from_location"), from,
          n.attr("end_location"), end, values));
    }
    if (nodes.isEmpty()) throw new FeedRejectedException("檔案裡沒有任何路段資料");
    if (rows.isEmpty()) throw new FeedRejectedException("沒有符合設定範圍的路段");
    return new Snapshot(timeRaw, timeMs, rows, stats);
  }

  static Integer parseInt(String s) {
    if (s == null) return null;
    try {
      return Integer.valueOf(s.trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static Double parseDouble(String s) {
    if (s == null) return null;
    try {
      double d = Double.parseDouble(s.trim());
      return Double.isFinite(d) ? d : null;
    } catch (NumberFormatException e) {
      return null;
    }
  }
}

package tw.org.ncree.teles.airms.parse;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tw.org.ncree.teles.airms.db.Schema;
import tw.org.ncree.teles.airms.feed.XmlNode;

/**
 * VD 五分鐘資料：file_attribute → fivemin_vd_data → traffic_data → lane。
 * 原值照存，不解讀：-2 就是 -2、0 就是 0，有效旗標與通訊狀態存原碼。
 */
public final class VdParser {
  private VdParser() { }

  public record Lane(String laneRef, String isDataValid, Map<String, String> values, Map<String, String> extra) { }

  public record Device(String eqId, String commStatus, String lanes, String congestionLevel,
                       Map<String, String> extra, List<Lane> laneList) { }

  public record Snapshot(String feedTimeRaw, long feedTimeMs, String controlCenterId, String fileName,
                         List<Device> devices, ParseStats stats) {
    public int laneCount() {
      return devices.stream().mapToInt(d -> d.laneList().size()).sum();
    }
  }

  private static final Set<String> DEVICE_KNOWN = Set.of("eqId", "eq_comm_status", "lanes", "congestion_level");
  private static final Set<String> LANE_KNOWN;

  static {
    Set<String> s = new HashSet<>(Schema.VD_LANE_FIELDS);
    s.add("laneId");
    s.add("is_data_valid");
    LANE_KNOWN = Set.copyOf(s);
  }

  public static Snapshot parse(XmlNode root) throws FeedRejectedException {
    String timeRaw = root.attr("time");
    Long timeMs = FeedTime.toEpochMs(timeRaw);
    if (timeMs == null) throw new FeedRejectedException("檔頭時間無法解析：" + ParseStats.clean(timeRaw));

    ParseStats stats = new ParseStats();
    List<Device> devices = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (XmlNode td : root.findAll("traffic_data")) {
      stats.row();
      String eqId = td.attr("eqId");
      if (eqId == null || eqId.isBlank()) {
        stats.bad("VD 缺少 eqId");
        continue;
      }
      if (!seen.add(eqId)) {
        stats.bad("VD 重複：" + eqId);
        continue;
      }
      List<Lane> lanes = new ArrayList<>();
      Set<String> laneSeen = new HashSet<>();
      for (XmlNode ln : td.children()) {
        if (!ln.name().equals("lane")) continue;
        String laneRef = ln.attr("laneId");
        if (laneRef == null || laneRef.isBlank()) {
          stats.bad(eqId + " 有車道缺少 laneId");
          continue;
        }
        if (!laneSeen.add(laneRef)) {
          stats.bad(eqId + " 車道重複：" + laneRef);
          continue;
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (String f : Schema.VD_LANE_FIELDS) {
          String v = ln.attr(f);
          if (v == null) stats.bad(eqId + " 車道 " + laneRef + " 缺少欄位 " + f);
          values.put(f, v);
        }
        lanes.add(new Lane(laneRef, ln.attr("is_data_valid"), values, extraOf(ln.attributes(), LANE_KNOWN)));
      }
      devices.add(new Device(eqId, td.attr("eq_comm_status"), td.attr("lanes"), td.attr("congestion_level"),
          extraOf(td.attributes(), DEVICE_KNOWN), lanes));
    }
    if (devices.isEmpty()) throw new FeedRejectedException("檔案裡沒有任何 VD 資料");
    return new Snapshot(timeRaw, timeMs, root.attr("control_center_id"), root.attr("file_name"), devices, stats);
  }

  private static Map<String, String> extraOf(Map<String, String> attrs, Set<String> known) {
    Map<String, String> extra = new LinkedHashMap<>();
    attrs.forEach((k, v) -> {
      if (!known.contains(k)) extra.put(k, v);
    });
    return extra;
  }
}

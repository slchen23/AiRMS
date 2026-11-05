package tw.org.ncree.teles.airms.view;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 「路網路段」查看頁：來源 XML 的每一筆路段畫在圖上的哪裡。
 * 路段依「國道＋方向＋起訖里程」自動畫到路線上；這裡標出完整、部分在圖外、不在圖上，
 * 以及同一路線相鄰路段之間的空隙或重疊（高公局重新切段時容易出現）。
 */
public final class SectionCoverage {
  private SectionCoverage() { }

  public enum MapStatus { FULL, PARTIAL, OUTSIDE, NO_ROUTE }

  private record Seg(JsonObject out, int routeIndex, int lo, int hi, boolean current) { }

  public static MapStatus status(NetworkConfig.RouteDef route, int lo, int hi) {
    if (route == null) return MapStatus.NO_ROUTE;
    if (hi <= route.fromMilepost() || lo >= route.toMilepost()) return MapStatus.OUTSIDE;
    if (lo >= route.fromMilepost() && hi <= route.toMilepost()) return MapStatus.FULL;
    return MapStatus.PARTIAL;
  }

  public static String routeLabel(String freewayId, NetworkConfig.RouteDef route, String directionId) {
    if (route == null) return "國" + freewayId + " 方向代碼 " + directionId;
    return "國" + freewayId + " " + ("S".equals(route.dir()) ? "南下" : "北上");
  }

  /** 里程 99400 →「99.4K」。 */
  public static String km(int milepost) {
    return String.format("%.1fK", milepost / 1000.0);
  }

  /** items：即時資料的路段（LiveViewService.sections 的 items）。 */
  public static JsonObject analyze(NetworkConfig network, JsonArray items) {
    List<NetworkConfig.RouteDef> routes = network.routes();
    List<Seg> segs = new ArrayList<>();
    int full = 0;
    int partial = 0;
    int outside = 0;
    int noRoute = 0;
    int notInLatest = 0;
    for (Object o : items) {
      JsonObject s = (JsonObject) o;
      String fw = s.getString("freewayId");
      String dirId = s.getString("directionId");
      int from = s.getInteger("from");
      int to = s.getInteger("to");
      int lo = Math.min(from, to);
      int hi = Math.max(from, to);
      NetworkConfig.RouteDef route = network.route(fw, dirId);
      MapStatus st = status(route, lo, hi);
      boolean stale = s.getBoolean("stale", false);
      JsonObject out = s.copy()
          .put("key", fw + "|" + dirId + "|" + from + "|" + to)
          .put("dir", route == null ? null : route.dir())
          .put("routeLabel", routeLabel(fw, route, dirId))
          .put("lo", lo).put("hi", hi).put("length", hi - lo)
          .put("fromKm", km(from)).put("toKm", km(to))
          .put("mapStatus", st.name());
      if (st == MapStatus.FULL || st == MapStatus.PARTIAL) {
        out.put("drawnFrom", Math.max(lo, route.fromMilepost())).put("drawnTo", Math.min(hi, route.toMilepost()));
      }
      switch (st) {
        case FULL -> full++;
        case PARTIAL -> partial++;
        case OUTSIDE -> outside++;
        default -> noRoute++;
      }
      if (stale) notInLatest++;
      segs.add(new Seg(out, route == null ? Integer.MAX_VALUE : routes.indexOf(route), lo, hi, !stale));
    }
    segs.sort(Comparator.comparingInt(Seg::routeIndex).thenComparing(x -> x.out().getString("freewayId"))
        .thenComparing(x -> x.out().getString("directionId")).thenComparingInt(Seg::lo).thenComparingInt(Seg::hi));

    // 同一路線、最新資料中的相鄰路段：空隙或重疊
    JsonArray issues = new JsonArray();
    Seg prev = null;
    for (Seg cur : segs) {
      if (!cur.current() || cur.routeIndex() == Integer.MAX_VALUE) continue;
      if (prev != null && prev.routeIndex() == cur.routeIndex()) {
        if (cur.lo() > prev.hi()) issues.add(issue("GAP", prev, cur, prev.hi(), cur.lo()));
        else if (cur.lo() < prev.hi()) issues.add(issue("OVERLAP", prev, cur, cur.lo(), Math.min(prev.hi(), cur.hi())));
      }
      if (prev == null || prev.routeIndex() != cur.routeIndex() || cur.hi() > prev.hi()) prev = cur;
    }

    JsonArray sections = new JsonArray();
    segs.forEach(x -> sections.add(x.out()));
    return new JsonObject().put("sections", sections).put("issues", issues)
        .put("counts", new JsonObject().put("total", segs.size()).put("current", segs.size() - notInLatest)
            .put("full", full).put("partial", partial).put("outside", outside).put("noRoute", noRoute)
            .put("notInLatest", notInLatest).put("issues", issues.size()));
  }

  private static JsonObject issue(String kind, Seg a, Seg b, int from, int to) {
    JsonObject x = a.out();
    JsonObject y = b.out();
    return new JsonObject().put("kind", kind).put("routeLabel", x.getString("routeLabel"))
        .put("from", from).put("to", to).put("fromKm", km(from)).put("toKm", km(to))
        .put("a", x.getString("key")).put("b", y.getString("key"))
        .put("aText", text(x)).put("bText", text(y));
  }

  private static String text(JsonObject s) {
    return s.getString("fromName", "") + "→" + s.getString("toName", "") + "（" + s.getString("fromKm") + "～"
        + s.getString("toKm") + "）";
  }
}

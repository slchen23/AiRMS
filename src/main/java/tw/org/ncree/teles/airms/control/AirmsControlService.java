package tw.org.ncree.teles.airms.control;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.function.LongSupplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import tw.org.ncree.teles.airms.auth.AuthLogRepository;
import tw.org.ncree.teles.airms.db.Db;

/**
 * AiRMS 控制頁的 AiRMS 設備讀取與設定。讀取（輪詢）不寫資料庫；每次「送出」不論成敗都寫一列
 * airms_control_log（送出前最後讀到的狀態與讀到的時間、送出的值、設備確認的值與原始回覆），
 * 並在操作紀錄（audit_log）記 AIRMS_SET。送出時同舊系統只送設定指令，「送出前」不另外查詢。
 */
public final class AirmsControlService {
  private static final Logger log = LogManager.getLogger(AirmsControlService.class);

  /** 送出設定的結果。 */
  public enum Outcome {
    OK("完成"),
    REFUSED("設備無法設定"),
    MISMATCH("設備確認的值不同"),
    NOT_CONFIGURED("尚未設定設備位址"),
    CONNECT_FAILED("無法連線"),
    TIMEOUT("設備沒有回應"),
    BAD_RESPONSE("回覆格式不對");

    private final String label;

    Outcome(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    static Outcome of(AirmsClient.Failure f) {
      return switch (f) {
        case NOT_CONFIGURED -> NOT_CONFIGURED;
        case CONNECT_FAILED -> CONNECT_FAILED;
        case TIMEOUT -> TIMEOUT;
        case BAD_RESPONSE -> BAD_RESPONSE;
      };
    }
  }

  public static final String AUDIT_ACTION = "AIRMS_SET";
  private static final int LOG_LIMIT_MAX = 5000;

  private final Db db;
  private final AirmsClient client;
  private final AirmsClient.TargetSource targets;
  private final LongSupplier clock;

  public AirmsControlService(Db coreDb, AirmsClient client, AirmsClient.TargetSource targets, LongSupplier clock) {
    this.db = coreDb;
    this.client = client;
    this.targets = targets;
    this.clock = clock;
  }

  public Future<Void> close() {
    return client.close();
  }

  /** 目前設定的設備位址文字；未設定時為 null。 */
  public String deviceText() {
    AirmsClient.Target t = targets.current();
    return t == null || t.host() == null || t.host().isBlank() ? null : t.text();
  }

  /**
   * 讀取設備狀態。成功：{ok:true, sensitivity, on, raw, readAtMs, device}；
   * 失敗：{ok:false, failure, message, raw?, readAtMs, device}（設備問題不當成 HTTP 錯誤，由畫面顯示）。
   */
  public Future<JsonObject> status() {
    String device = deviceText();
    return client.read().map(r -> {
      JsonObject out = base(device);
      if (r.kind() != AirmsProtocol.Kind.STATUS) {
        return out.put("ok", false).put("failure", "REFUSED").put("raw", r.raw())
            .put("message", "AiRMS 設備回覆系統異常（" + r.raw() + "），無法讀取狀態");
      }
      return out.put("ok", true).put("sensitivity", r.sensitivity()).put("on", r.on()).put("raw", r.raw());
    }).recover(e -> {
      if (!(e instanceof AirmsClient.AirmsException ae)) return Future.failedFuture(e);
      return Future.succeededFuture(base(device).put("ok", false).put("failure", ae.failure().name())
          .put("message", ae.getMessage()).put("raw", ae.raw()));
    });
  }

  private JsonObject base(String device) {
    return new JsonObject().put("device", device).put("readAtMs", clock.getAsLong());
  }

  /**
   * 送出設定並記錄。敏感性須已檢查（40～80，每 5）。回傳結果 JSON（ok、outcome、message、before、after…），
   * 設備層面的失敗也正常回傳；只有寫入紀錄失敗才是 Future 失敗。
   */
  public Future<JsonObject> set(String username, String ip, int sensitivity, boolean on) {
    if (!AirmsProtocol.validSensitivity(sensitivity)) {
      return Future.failedFuture(new IllegalArgumentException("敏感性超出範圍：" + sensitivity));
    }
    String device = deviceText();
    long started = clock.getAsLong();
    String request = AirmsProtocol.setCommand(sensitivity, on);
    return client.set(sensitivity, on).map(r -> {
      AirmsProtocol.Reply after = r.after();
      AirmsClient.Known before = r.before();
      Outcome outcome;
      String message;
      if (after.kind() == AirmsProtocol.Kind.REFUSED) {
        outcome = Outcome.REFUSED;
        message = "AiRMS 設備回覆：系統異常，無法設定（" + after.raw() + "）";
      } else if (after.sensitivity() != sensitivity || after.on() != on) {
        outcome = Outcome.MISMATCH;
        message = "AiRMS 設備確認的值與送出的不同：設備回 敏感性 " + after.sensitivity() + "，AiRMS " + onOff(after.on());
      } else {
        outcome = Outcome.OK;
        message = "設定完成：敏感性 " + sensitivity + "，AiRMS " + onOff(on);
      }
      return new Result(outcome, message, before, outcome == Outcome.REFUSED ? null : after, after.raw());
    }).recover(e -> {
      if (!(e instanceof AirmsClient.AirmsException ae)) return Future.failedFuture(e);
      return Future.succeededFuture(new Result(Outcome.of(ae.failure()), ae.getMessage(), knownBefore(device), null,
          ae.raw()));
    }).compose(res -> {
      long now = clock.getAsLong();
      JsonObject row = new JsonObject().put("atMs", now).put("username", username).put("ip", ip)
          .put("device", device).put("request", new JsonObject().put("sensitivity", sensitivity).put("on", on))
          .put("before", knownJson(res.before())).put("after", replyJson(res.after()))
          .put("outcome", res.outcome().name()).put("outcomeLabel", res.outcome().label())
          .put("ok", res.outcome() == Outcome.OK).put("message", res.message()).put("response", res.raw())
          .put("durationMs", now - started);
      if (res.outcome() == Outcome.OK) {
        log.info("{} 設定 AiRMS 設備 {}：送 {}，結果 {}", username, device, request, res.outcome());
      } else {
        log.warn("{} 設定 AiRMS 設備 {}：送 {}，結果 {}（{}）", username, device, request, res.outcome(), res.message());
      }
      return db.tx(c -> {
        long id = insert(c, row);
        AuthLogRepository.audit(c, now, username, AUDIT_ACTION, device == null ? "AiRMS" : device,
            new JsonObject().put("request", row.getJsonObject("request")).put("before", row.getValue("before"))
                .put("after", row.getValue("after")).put("outcome", res.outcome().name())
                .put("response", res.raw()), ip);
        return id;
      }).map(id -> row.put("id", id));
    });
  }

  private record Result(Outcome outcome, String message, AirmsClient.Known before, AirmsProtocol.Reply after,
                        String raw) { }

  /** 沒能送到設備時（連不上等），紀錄的「送出前」仍取目前已知的最後狀態。 */
  private AirmsClient.Known knownBefore(String device) {
    AirmsClient.Known k = client.lastKnown();
    return k != null && k.device().equals(device) ? k : null;
  }

  private static JsonObject knownJson(AirmsClient.Known k) {
    return k == null ? null : new JsonObject().put("sensitivity", k.sensitivity()).put("on", k.on()).put("atMs", k.atMs());
  }

  private static String onOff(boolean on) {
    return on ? "ON" : "OFF";
  }

  private static JsonObject replyJson(AirmsProtocol.Reply r) {
    return r == null ? null : new JsonObject().put("sensitivity", r.sensitivity()).put("on", r.on());
  }

  /** 最近的設定紀錄（新到舊），時間區間 [fromMs, toMs)。 */
  public Future<JsonArray> log(long fromMs, long toMs, int limit) {
    int n = Math.max(1, Math.min(LOG_LIMIT_MAX, limit));
    return db.run(c -> {
      JsonArray out = new JsonArray();
      try (PreparedStatement ps = c.prepareStatement("""
          SELECT log_id, at_ms, username, ip, device, before_sensitivity, before_on, before_at_ms,
                 request_sensitivity, request_on,
                 after_sensitivity, after_on, outcome, response, message, duration_ms
          FROM airms_control_log WHERE at_ms >= ? AND at_ms < ? ORDER BY at_ms DESC, log_id DESC LIMIT ?""")) {
        ps.setLong(1, fromMs);
        ps.setLong(2, toMs);
        ps.setInt(3, n);
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) out.add(rowJson(rs));
        }
      }
      return out;
    });
  }

  private static JsonObject rowJson(ResultSet rs) throws SQLException {
    Outcome o;
    try {
      o = Outcome.valueOf(rs.getString("outcome"));
    } catch (IllegalArgumentException e) {
      o = null;
    }
    return new JsonObject().put("id", rs.getLong("log_id")).put("atMs", rs.getLong("at_ms"))
        .put("username", rs.getString("username")).put("ip", rs.getString("ip")).put("device", rs.getString("device"))
        .put("before", withTime(pair(rs, "before_sensitivity", "before_on"), nullableLong(rs, "before_at_ms")))
        .put("request", pair(rs, "request_sensitivity", "request_on"))
        .put("after", pair(rs, "after_sensitivity", "after_on"))
        .put("outcome", rs.getString("outcome")).put("outcomeLabel", o == null ? rs.getString("outcome") : o.label())
        .put("ok", o == Outcome.OK).put("response", rs.getString("response")).put("message", rs.getString("message"))
        .put("durationMs", nullableLong(rs, "duration_ms"));
  }

  private static JsonObject pair(ResultSet rs, String sCol, String onCol) throws SQLException {
    int s = rs.getInt(sCol);
    if (rs.wasNull()) return null;
    int on = rs.getInt(onCol);
    if (rs.wasNull()) return null;
    return new JsonObject().put("sensitivity", s).put("on", on == 1);
  }

  private static JsonObject withTime(JsonObject p, Long atMs) {
    return p == null || atMs == null ? p : p.put("atMs", atMs);
  }

  private static Long nullableLong(ResultSet rs, String col) throws SQLException {
    long v = rs.getLong(col);
    return rs.wasNull() ? null : v;
  }

  private static long insert(Connection c, JsonObject row) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement("""
        INSERT INTO airms_control_log(at_ms, username, ip, device, before_sensitivity, before_on, request_sensitivity,
          request_on, after_sensitivity, after_on, outcome, response, message, duration_ms, before_at_ms)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""", PreparedStatement.RETURN_GENERATED_KEYS)) {
      ps.setLong(1, row.getLong("atMs"));
      ps.setString(2, row.getString("username"));
      ps.setString(3, row.getString("ip"));
      ps.setString(4, row.getString("device") == null ? "（未設定）" : row.getString("device"));
      setPair(ps, 5, row.getJsonObject("before"));
      setPair(ps, 7, row.getJsonObject("request"));
      setPair(ps, 9, row.getJsonObject("after"));
      ps.setString(11, row.getString("outcome"));
      ps.setString(12, row.getString("response"));
      ps.setString(13, row.getString("message"));
      ps.setLong(14, row.getLong("durationMs"));
      JsonObject before = row.getJsonObject("before");
      if (before == null || before.getLong("atMs") == null) ps.setNull(15, Types.INTEGER);
      else ps.setLong(15, before.getLong("atMs"));
      ps.executeUpdate();
      try (ResultSet keys = ps.getGeneratedKeys()) {
        return keys.next() ? keys.getLong(1) : -1;
      }
    }
  }

  private static void setPair(PreparedStatement ps, int idx, JsonObject p) throws SQLException {
    if (p == null) {
      ps.setNull(idx, Types.INTEGER);
      ps.setNull(idx + 1, Types.INTEGER);
    } else {
      ps.setInt(idx, p.getInteger("sensitivity"));
      ps.setInt(idx + 1, p.getBoolean("on") ? 1 : 0);
    }
  }
}

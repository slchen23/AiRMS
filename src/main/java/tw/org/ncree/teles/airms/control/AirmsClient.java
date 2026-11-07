package tw.org.ncree.teles.airms.control;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetClientOptions;
import io.vertx.core.net.NetSocket;
import java.nio.charset.StandardCharsets;
import java.util.function.LongSupplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 連到 AiRMS 設備送一筆指令、收一筆回覆（每次一條新連線，收到回覆就關閉，同舊系統）。
 * 同一時間只跟設備進行一段對話：多個請求依序排隊；同時有多個畫面要讀狀態時共用同一次查詢。
 * 連線失敗會再試 2 次（間隔 300 毫秒）；回覆逾時、格式不對都明確回報，不猜測。
 */
public final class AirmsClient {
  private static final Logger log = LogManager.getLogger(AirmsClient.class);

  /** 與設備對話失敗的原因。 */
  public enum Failure { NOT_CONFIGURED, CONNECT_FAILED, TIMEOUT, BAD_RESPONSE }

  public static final class AirmsException extends Exception {
    private static final long serialVersionUID = 1L;
    private final Failure failure;
    private final String raw;

    public AirmsException(Failure failure, String message, String raw) {
      super(message, null, false, false);
      this.failure = failure;
      this.raw = raw;
    }

    public Failure failure() {
      return failure;
    }

    /** 設備實際回的內容（已清理），沒有時為 null。 */
    public String raw() {
      return raw;
    }
  }

  /** 設備位址（系統設定可改，每次對話前重新取得）。 */
  public record Target(String host, int port) {
    public String text() {
      return host + ":" + port;
    }
  }

  public interface TargetSource {
    /** 回傳 null 或 host 為空表示尚未設定。 */
    Target current();
  }

  /** 最後一次從設備得知的狀態（查詢回 91 或設定回 92），含當時的設備位址與時間。 */
  public record Known(String device, int sensitivity, boolean on, long atMs) { }

  /** 設定的結果：before 為送出時已知的最後狀態（還沒讀過或換了設備位址為 null），after 為設備的回覆。 */
  public record SetResult(Known before, AirmsProtocol.Reply after) { }

  private final Vertx vertx;
  private final TargetSource targets;
  private final int responseTimeoutMs;
  private final LongSupplier clock;
  private final NetClient net;
  private Future<?> tail = Future.succeededFuture();
  private Future<AirmsProtocol.Reply> readInFlight;
  private volatile Known lastKnown;

  public AirmsClient(Vertx vertx, TargetSource targets, int connectTimeoutMs, int responseTimeoutMs) {
    this(vertx, targets, connectTimeoutMs, responseTimeoutMs, System::currentTimeMillis);
  }

  public AirmsClient(Vertx vertx, TargetSource targets, int connectTimeoutMs, int responseTimeoutMs,
                     LongSupplier clock) {
    this.vertx = vertx;
    this.targets = targets;
    this.responseTimeoutMs = responseTimeoutMs;
    this.clock = clock;
    this.net = vertx.createNetClient(new NetClientOptions().setConnectTimeout(connectTimeoutMs)
        .setReconnectAttempts(2).setReconnectInterval(300));
  }

  public Future<Void> close() {
    return net.close();
  }

  /** 讀取設備狀態（01 → 91）。已排隊的讀取還沒結束、之後也沒有排入設定時再要求，就共用那一次的結果。 */
  public synchronized Future<AirmsProtocol.Reply> read() {
    if (readInFlight != null) return readInFlight;
    Future<AirmsProtocol.Reply> f = enqueue(() -> exchange(AirmsProtocol.QUERY, AirmsProtocol.Kind.STATUS));
    readInFlight = f;
    f.onComplete(ar -> {
      synchronized (this) {
        if (readInFlight == f) readInFlight = null;
      }
    });
    return f;
  }

  /**
   * 設定（02 → 92），同舊系統只送設定指令、不先查詢。紀錄用的「送出前」取輪到這筆送出時已知的最後狀態
   * （最近一次讀取或設定確認的值；還沒讀過、或之後換了設備位址則為 null）。
   * 設備回 99 時以 REFUSED 回傳（不是例外），由呼叫端記錄為「設備無法設定」。
   */
  public Future<SetResult> set(int sensitivity, boolean on) {
    String command = AirmsProtocol.setCommand(sensitivity, on);
    synchronized (this) {
      readInFlight = null;   // 設定之後才要求的讀取，必須在設定完成後重新讀，不能共用設定前的那一次
    }
    return enqueue(() -> {
      Target t = targets.current();
      Known k = lastKnown;
      Known before = k != null && t != null && k.device().equals(t.text()) ? k : null;
      return exchange(command, AirmsProtocol.Kind.SET_OK).map(after -> new SetResult(before, after));
    });
  }

  /** 最後一次從設備得知的狀態；沒有時為 null。 */
  public Known lastKnown() {
    return lastKnown;
  }

  // ---------------------------------------------------------------- 內部

  private interface Task<T> {
    Future<T> run();
  }

  /** 依序執行：前一段對話（不論成敗）結束後才開始下一段。 */
  private synchronized <T> Future<T> enqueue(Task<T> task) {
    Future<T> next = tail.transform(ignored -> task.run());
    tail = next.transform(ignored -> Future.succeededFuture());
    return next;
  }

  /** 一次連線：送 request、收 6 個字元的回覆。expect 為預期的回覆種類（另接受 99）。 */
  private Future<AirmsProtocol.Reply> exchange(String request, AirmsProtocol.Kind expect) {
    Target t = targets.current();
    if (t == null || t.host() == null || t.host().isBlank()) {
      return Future.failedFuture(new AirmsException(Failure.NOT_CONFIGURED,
          "尚未設定 AiRMS 設備位址（系統設定 → AiRMS 設備）", null));
    }
    Promise<AirmsProtocol.Reply> promise = Promise.promise();
    long started = System.nanoTime();
    net.connect(t.port(), t.host()).onComplete(ar -> {
      if (ar.failed()) {
        log.warn("AiRMS 設備 {} 連線失敗：{}", t.text(), ar.cause().getMessage());
        promise.tryFail(new AirmsException(Failure.CONNECT_FAILED, "無法連線到 AiRMS 設備（" + t.text() + "）", null));
        return;
      }
      NetSocket socket = ar.result();
      Buffer received = Buffer.buffer();
      long timer = vertx.setTimer(responseTimeoutMs, id -> {
        if (promise.tryFail(new AirmsException(Failure.TIMEOUT,
            "AiRMS 設備沒有回應（" + responseTimeoutMs / 1000.0 + " 秒逾時）",
            received.length() == 0 ? null : AirmsProtocol.printable(received.getBytes())))) {
          log.warn("AiRMS 設備 {} 回覆逾時（已收到 {} 位元組）", t.text(), received.length());
          socket.close();
        }
      });
      socket.handler(buf -> {
        received.appendBuffer(buf);
        if (received.length() < AirmsProtocol.LENGTH) return;   // 回覆可能分成好幾段到達
        vertx.cancelTimer(timer);
        byte[] bytes = received.getBytes();
        String raw = AirmsProtocol.printable(bytes);
        // 多出的只允許換行或空白（有些設備會補換行）
        String extra = new String(bytes, AirmsProtocol.LENGTH, bytes.length - AirmsProtocol.LENGTH, StandardCharsets.US_ASCII);
        AirmsProtocol.Reply reply = extra.isBlank()
            ? AirmsProtocol.parse(new String(bytes, 0, AirmsProtocol.LENGTH, StandardCharsets.US_ASCII)) : null;
        socket.close();
        if (reply == null || (reply.kind() != expect && reply.kind() != AirmsProtocol.Kind.REFUSED)) {
          log.warn("AiRMS 設備 {} 回覆格式不對：送 {} 收 {}", t.text(), request, raw);
          promise.tryFail(new AirmsException(Failure.BAD_RESPONSE, "AiRMS 設備回覆的格式不對（" + raw + "）", raw));
          return;
        }
        log.info("AiRMS 設備 {}：送 {} 收 {}（{} 毫秒）", t.text(), request, raw, (System.nanoTime() - started) / 1_000_000);
        if (reply.kind() != AirmsProtocol.Kind.REFUSED) {
          lastKnown = new Known(t.text(), reply.sensitivity(), reply.on(), clock.getAsLong());
        }
        promise.tryComplete(reply);
      });
      socket.closeHandler(v -> {
        vertx.cancelTimer(timer);
        if (promise.tryFail(new AirmsException(Failure.BAD_RESPONSE,
            "AiRMS 設備在回覆完整前就中斷連線" + (received.length() > 0 ? "（收到 " + AirmsProtocol.printable(received.getBytes()) + "）" : ""),
            received.length() == 0 ? null : AirmsProtocol.printable(received.getBytes())))) {
          log.warn("AiRMS 設備 {} 在回覆完整前中斷連線", t.text());
        }
      });
      socket.exceptionHandler(e -> {
        vertx.cancelTimer(timer);
        if (promise.tryFail(new AirmsException(Failure.CONNECT_FAILED, "與 AiRMS 設備的連線發生錯誤：" + e.getMessage(), null))) {
          socket.close();
        }
      });
      socket.write(Buffer.buffer(request, "US-ASCII"));
    });
    return promise.future();
  }
}

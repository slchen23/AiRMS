package tw.org.ncree.teles.airms.feed;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.PoolOptions;
import io.vertx.core.http.RequestOptions;
import java.net.URI;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import tw.org.ncree.teles.airms.config.AppConfig;

/**
 * 下載開放資料 XML。限制：連線/閒置逾時、整體下載期限、大小上限、不跟隨轉址、不接受壓縮編碼。
 * 任何一項失敗都中斷連線並回傳失敗，不會留下半份資料。
 */
public final class FeedHttpClient {

  public record Response(int status, byte[] body) { }

  public static final class FetchException extends Exception {
    private static final long serialVersionUID = 1L;
    private final Integer status;

    public FetchException(String message, Integer status) {
      super(message);
      this.status = status;
    }

    public Integer status() {
      return status;
    }
  }

  private final Vertx vertx;
  private final AppConfig.FeedHttp limits;
  private final HttpClient client;

  public FeedHttpClient(Vertx vertx, AppConfig.FeedHttp limits) {
    this.vertx = vertx;
    this.limits = limits;
    this.client = vertx.createHttpClient(
        new HttpClientOptions()
            .setConnectTimeout(limits.connectTimeoutMs())
            .setIdleTimeout(limits.idleTimeoutMs())
            .setIdleTimeoutUnit(TimeUnit.MILLISECONDS)
            .setKeepAlive(false)
            .setDecompressionSupported(false),
        new PoolOptions().setHttp1MaxSize(3).setMaxWaitQueueSize(3));
  }

  public Future<Response> get(URI uri, String userAgent) {
    Promise<Response> promise = Promise.promise();
    Download d = new Download(promise);
    d.timer = vertx.setTimer(limits.downloadTimeoutMs(),
        id -> d.fail(new FetchException("下載超過 " + limits.downloadTimeoutMs() + " ms", null)));
    RequestOptions options = new RequestOptions()
        .setMethod(HttpMethod.GET)
        .setAbsoluteURI(uri.toString())
        .setFollowRedirects(false)
        .setConnectTimeout(limits.connectTimeoutMs())
        .setIdleTimeout(limits.idleTimeoutMs())
        .putHeader("User-Agent", userAgent)
        .putHeader("Accept", "application/xml, text/xml, */*")
        .putHeader("Accept-Encoding", "identity");
    client.request(options).onComplete(created -> {
      if (created.failed()) {
        d.fail(created.cause());
        return;
      }
      HttpClientRequest request = created.result();
      d.request = request;
      if (d.done) {
        request.reset();
        return;
      }
      request.exceptionHandler(d::fail);
      request.send().onComplete(sent -> {
        if (sent.failed()) {
          d.fail(sent.cause());
          return;
        }
        var response = sent.result();
        if (d.done) {
          request.reset();
          return;
        }
        int status = response.statusCode();
        if (status != 200) {
          d.fail(new FetchException("HTTP 狀態 " + status, status));
          return;
        }
        String encoding = response.getHeader("Content-Encoding");
        if (encoding != null && !encoding.isBlank() && !encoding.equalsIgnoreCase("identity")) {
          d.fail(new FetchException("不支援的內容編碼 " + encoding, status));
          return;
        }
        String length = response.getHeader("Content-Length");
        if (length != null) {
          try {
            if (Long.parseLong(length.trim()) > limits.maxBytes()) {
              d.fail(new FetchException("檔案大小超過上限 " + limits.maxBytes(), status));
              return;
            }
          } catch (NumberFormatException ignored) {
            // 以實際收到的位元組數為準
          }
        }
        response.exceptionHandler(d::fail);
        response.handler(chunk -> {
          if (d.done) return;
          if ((long) d.body.length() + chunk.length() > limits.maxBytes()) {
            d.fail(new FetchException("檔案大小超過上限 " + limits.maxBytes(), status));
            return;
          }
          d.body.appendBuffer(chunk);
        });
        response.endHandler(v -> d.succeed(status));
      });
    });
    return promise.future();
  }

  public Future<Void> close() {
    return client.close();
  }

  private final class Download {
    final Promise<Response> promise;
    final Buffer body = Buffer.buffer();
    HttpClientRequest request;
    long timer = -1;
    boolean done;

    Download(Promise<Response> promise) {
      this.promise = promise;
    }

    void fail(Throwable error) {
      if (done) return;
      done = true;
      vertx.cancelTimer(timer);
      if (request != null) request.reset();
      promise.tryFail(error instanceof TimeoutException ? new FetchException(error.getMessage(), null) : error);
    }

    void succeed(int status) {
      if (done) return;
      if (body.length() == 0) {
        fail(new FetchException("回應內容是空的", status));
        return;
      }
      done = true;
      vertx.cancelTimer(timer);
      promise.tryComplete(new Response(status, body.getBytes()));
    }
  }
}

package tw.org.ncree.teles.airms;

import io.vertx.launcher.application.HookContext;
import io.vertx.launcher.application.VertxApplication;
import io.vertx.launcher.application.VertxApplicationHooks;
import org.apache.logging.log4j.LogManager;

/**
 * 程式進入點。沿用 Vert.x 5 的 VertxApplication（同 StockVX），主 verticle 由 jar 的 Main-Verticle 指定。
 * 執行：java -Duser.timezone=Asia/Taipei -jar airms-fat.jar
 * log4j 的關閉交給這裡：等 Vert.x 停完（資料庫已關閉）才關 log，停止過程的訊息才不會遺失。
 */
public final class AirmsApplication extends VertxApplication implements VertxApplicationHooks {
  public AirmsApplication(String[] args) {
    super(args);
  }

  public static void main(String[] args) {
    new AirmsApplication(args).launch();
  }

  @Override
  public void afterVertxStopped(HookContext context) {
    LogManager.shutdown();
  }

  @Override
  public void afterFailureToStopVertx(HookContext context, Throwable t) {
    LogManager.getLogger(AirmsApplication.class).error("Vert.x 停止失敗", t);
    LogManager.shutdown();
  }
}

package tw.org.ncree.teles.airms.db;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.WorkerExecutor;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 一個 SQLite 檔一個 Db：所有存取排進同一條單執行緒 worker，天然只有一個寫入者，
 * 不會在 event loop 上做 JDBC。交易失敗自動 rollback。
 * 同一系列的月檔用同一個 poolName，共用同一條執行緒（例如所有 VD 月檔依序寫入）。
 * {@link #memory} 建立只在記憶體的資料庫（即時資料用，不寫入 SD 卡；程式重啟即清空）。
 */
public final class Db {
  private static final Logger log = LogManager.getLogger(Db.class);

  /** 同步層級：VD 永久資料用 FULL（斷電最多少最後一筆交易），營運資料用 NORMAL。 */
  public enum Durability { NORMAL, FULL }

  @FunctionalInterface
  public interface Work<T> {
    T apply(Connection c) throws Exception;
  }

  /** 記憶體資料庫沒有檔案路徑。 */
  private final Path path;
  private final Durability durability;
  private final WorkerExecutor executor;
  private Connection conn;

  public Db(Vertx vertx, Path path, String poolName, Durability durability) {
    this.path = path == null ? null : path.toAbsolutePath().normalize();
    this.durability = durability;
    this.executor = vertx.createSharedWorkerExecutor(poolName, 1, 10, TimeUnit.MINUTES);
  }

  /** 只在記憶體的資料庫；連線一直保持開啟（關閉即清空）。 */
  public static Db memory(Vertx vertx, String poolName) {
    return new Db(vertx, null, poolName, Durability.NORMAL);
  }

  /** 檔案路徑；記憶體資料庫為 null。 */
  public Path path() {
    return path;
  }

  public <T> Future<T> run(Work<T> work) {
    return executor.executeBlocking(() -> work.apply(connection()), true);
  }

  public <T> Future<T> tx(Work<T> work) {
    return run(c -> inTransaction(c, work));
  }

  public static <T> T inTransaction(Connection c, Work<T> work) throws Exception {
    boolean auto = c.getAutoCommit();
    c.setAutoCommit(false);
    try {
      T result = work.apply(c);
      c.commit();
      return result;
    } catch (Exception e) {
      try {
        c.rollback();
      } catch (SQLException rollbackError) {
        e.addSuppressed(rollbackError);
      }
      throw e;
    } finally {
      c.setAutoCommit(auto);
    }
  }

  private Connection connection() throws SQLException {
    if (conn == null || conn.isClosed()) {
      if (path == null) {
        conn = openMemory();
      } else {
        conn = open(path, durability);
        log.debug("SQLite 已開啟：{}", path);
      }
    }
    return conn;
  }

  /** 父目錄必須已存在（由 DataVolume 建立），這裡不建立目錄。 */
  public static Connection open(Path file, Durability durability) throws SQLException {
    Connection c = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
    try (Statement st = c.createStatement()) {
      st.execute("PRAGMA journal_mode=WAL");
      st.execute("PRAGMA synchronous=" + durability.name());
      st.execute("PRAGMA busy_timeout=10000");
      st.execute("PRAGMA foreign_keys=ON");
    } catch (SQLException e) {
      c.close();
      throw e;
    }
    return c;
  }

  public static Connection openMemory() throws SQLException {
    Connection c = DriverManager.getConnection("jdbc:sqlite::memory:");
    try (Statement st = c.createStatement()) {
      st.execute("PRAGMA foreign_keys=ON");
    } catch (SQLException e) {
      c.close();
      throw e;
    }
    return c;
  }

  public Future<Void> close() {
    return executor.executeBlocking(() -> {
      if (conn != null) {
        try (Statement st = conn.createStatement()) {
          if (path != null) st.execute("PRAGMA optimize");
        } catch (SQLException e) {
          log.debug("PRAGMA optimize 略過：{}", e.getMessage());
        }
        try {
          conn.close();
        } catch (SQLException e) {
          log.warn("關閉 SQLite 失敗：{}", path, e);
        }
        conn = null;
      }
      return (Void) null;
    }, true).eventually(executor::close);
  }
}

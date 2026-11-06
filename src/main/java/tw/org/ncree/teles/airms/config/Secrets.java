package tw.org.ncree.teles.airms.config;

import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;
import tw.org.ncree.teles.airms.StartupException;

/**
 * 機密值（例如憑證密碼）。來源優先順序：環境變數 → secrets.json。
 * secrets.json 不進 git；錯誤訊息不含任何機密內容，只說明找了哪裡。
 */
public final class Secrets {
  public static final String ENV_TLS_PASSWORD = "AIRMS_TLS_KEYSTORE_PASSWORD";

  private final JsonObject values;
  private final Path file;
  private final boolean fileFound;
  private final Function<String, String> env;

  private Secrets(JsonObject values, Path file, boolean fileFound, Function<String, String> env) {
    this.values = values;
    this.file = file;
    this.fileFound = fileFound;
    this.env = env;
  }

  public static Secrets load(Path file) {
    return load(file, System::getenv);
  }

  /** env：讀環境變數的方法（測試時可換掉）。 */
  static Secrets load(Path file, Function<String, String> env) {
    Path abs = file == null ? null : file.toAbsolutePath();
    if (abs == null || !Files.isRegularFile(abs)) return new Secrets(new JsonObject(), abs, false, env);
    try {
      return new Secrets(new JsonObject(Files.readString(abs, StandardCharsets.UTF_8)), abs, true, env);
    } catch (IOException | DecodeException e) {
      throw new StartupException("無法讀取機密設定檔 " + abs + "（JSON 格式或檔案權限錯誤）");
    }
  }

  public String tlsKeystorePassword() {
    String fromEnv = env.apply(ENV_TLS_PASSWORD);
    if (fromEnv != null && !fromEnv.isEmpty()) return fromEnv;
    String v = values.getString("tlsKeystorePassword");
    if (v != null && !v.isEmpty()) return v;
    String where = fileFound
        ? "機密設定檔 " + file + " 裡沒有 tlsKeystorePassword"
        : "找不到機密設定檔 " + file + "（config.json 的 secretsFile；相對路徑以啟動時的工作目錄為準）";
    throw new StartupException("未提供 HTTPS 憑證密碼：" + where + "，也沒有設定環境變數 " + ENV_TLS_PASSWORD + "。\n"
        + TlsKeystore.HOW_TO);
  }
}

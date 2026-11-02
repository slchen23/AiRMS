package tw.org.ncree.teles.airms.config;

import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 機密值（例如憑證密碼）。來源優先順序：環境變數 → secrets.json。
 * secrets.json 不進 git；錯誤訊息不含任何機密內容。
 */
public final class Secrets {
  public static final String ENV_TLS_PASSWORD = "AIRMS_TLS_KEYSTORE_PASSWORD";

  private final JsonObject values;

  private Secrets(JsonObject values) {
    this.values = values;
  }

  public static Secrets load(Path file) {
    if (file == null || !Files.isRegularFile(file)) return new Secrets(new JsonObject());
    try {
      return new Secrets(new JsonObject(Files.readString(file, StandardCharsets.UTF_8)));
    } catch (IOException | DecodeException e) {
      throw new IllegalStateException("無法讀取機密設定檔 " + file.toAbsolutePath() + "（格式或權限錯誤）");
    }
  }

  public String tlsKeystorePassword() {
    String env = System.getenv(ENV_TLS_PASSWORD);
    if (env != null && !env.isEmpty()) return env;
    String v = values.getString("tlsKeystorePassword");
    if (v == null || v.isEmpty()) {
      throw new IllegalStateException("未提供憑證密碼：請設定環境變數 " + ENV_TLS_PASSWORD
          + " 或在機密設定檔填 tlsKeystorePassword");
    }
    return v;
  }
}

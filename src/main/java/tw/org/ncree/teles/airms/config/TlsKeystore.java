package tw.org.ncree.teles.airms.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.UnrecoverableKeyException;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import tw.org.ncree.teles.airms.StartupException;

/**
 * 啟動時（開資料庫之前）先檢查 HTTPS 憑證：檔案在不在、密碼對不對、還有沒有效。
 * 憑證與密碼刻意不進 git，所以每台新機器（含樹莓派）都要先產生或複製；有問題時訊息直接說明怎麼處理。
 */
public final class TlsKeystore {
  private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy/MM/dd").withZone(ZoneId.of("Asia/Taipei"));

  static final String HOW_TO = "新機器請在專案資料夾執行 tools\\tls\\new-cert.ps1（Windows）或 tools/tls/new-cert.sh（樹莓派）"
      + "產生憑證與密碼檔，再依畫面提示修改 config.json；或從原本的機器複製憑證檔與 secrets.json。詳見 README「HTTPS 憑證」。";

  /** 憑證的公開資訊（不含密碼，可放進 AppContext、給首頁顯示）。 */
  public record Cert(String subject, Instant notAfter) {
    public boolean expired(Instant now) {
      return now.isAfter(notAfter);
    }

    /** 剩幾天（不足一天算 0；已過期為負數）。 */
    public long daysLeft(Instant now) {
      return expired(now) ? -Math.max(1, Duration.between(notAfter, now).toDays()) : Duration.between(now, notAfter).toDays();
    }

    public String notAfterText() {
      return DATE.format(notAfter);
    }
  }

  public record Info(String password, Cert cert) { }

  private TlsKeystore() { }

  /** 檢查並回傳憑證密碼與到期日。過期不阻止啟動（由 expiryWarning 提醒）。 */
  public static Info check(Path keystorePath, Secrets secrets) {
    Path ks = keystorePath.toAbsolutePath();
    if (!Files.isRegularFile(ks)) {
      throw new StartupException("找不到 HTTPS 憑證檔：" + ks + "（config.json 的 http.tls.keystorePath）。\n" + HOW_TO);
    }
    String password = secrets.tlsKeystorePassword();
    KeyStore store;
    try (InputStream in = Files.newInputStream(ks)) {
      store = KeyStore.getInstance("PKCS12");
      store.load(in, password.toCharArray());
    } catch (IOException e) {
      if (e.getCause() instanceof UnrecoverableKeyException || String.valueOf(e.getMessage()).contains("password")) {
        throw new StartupException("HTTPS 憑證密碼不正確：" + ks
            + "（請確認 secrets.json 的 tlsKeystorePassword 或環境變數 " + Secrets.ENV_TLS_PASSWORD + " 是這個憑證的密碼）");
      }
      throw new StartupException("無法讀取 HTTPS 憑證檔 " + ks + "：不是 PKCS#12（.pfx）格式或檔案已損壞");
    } catch (GeneralSecurityException e) {
      throw new StartupException("無法讀取 HTTPS 憑證檔 " + ks + "：" + e.getMessage());
    }
    try {
      for (String alias : Collections.list(store.aliases())) {
        if (!store.isKeyEntry(alias)) continue;
        Certificate c = store.getCertificate(alias);
        if (!(c instanceof X509Certificate x)) continue;
        return new Info(password, new Cert(x.getSubjectX500Principal().getName(), x.getNotAfter().toInstant()));
      }
    } catch (GeneralSecurityException e) {
      throw new StartupException("無法讀取 HTTPS 憑證檔 " + ks + "：" + e.getMessage());
    }
    throw new StartupException("HTTPS 憑證檔 " + ks + " 裡沒有私鑰與憑證（請用 tools/tls/new-cert 重新產生）");
  }

  /**
   * 到期提醒：已過期，或剩下的天數少於 warnDays（系統設定「HTTPS 憑證 → 到期前幾天開始提醒」）時回傳訊息，否則 null。
   */
  public static String expiryWarning(Cert c, Instant now, int warnDays) {
    if (c == null) return null;
    if (c.expired(now)) {
      return "HTTPS 憑證已於 " + c.notAfterText() + " 過期（" + c.subject() + "），瀏覽器會顯示警告；"
          + "請用 tools/tls/new-cert 重新產生後重新啟動";
    }
    long left = c.daysLeft(now);
    if (left < warnDays) {
      return "HTTPS 憑證將於 " + c.notAfterText() + " 到期（還有 " + left + " 天，" + c.subject() + "），"
          + "請用 tools/tls/new-cert 重新產生後重新啟動";
    }
    return null;
  }
}

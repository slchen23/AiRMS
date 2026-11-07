package tw.org.ncree.teles.airms.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import tw.org.ncree.teles.airms.StartupException;

/** 啟動前的 HTTPS 憑證檢查：缺檔、缺密碼、密碼錯誤都要有看得懂的訊息。 */
class TlsKeystoreTest {
  private static final String PW = "Test-Keystore-Pw-1";

  /** 用 JDK 內附的 keytool 產生測試憑證（同 tools/tls/new-cert）。 */
  private static Path makeKeystore(Path dir, String... extra) throws Exception {
    Path pfx = dir.resolve("server.pfx");
    String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
    List<String> cmd = new java.util.ArrayList<>(List.of(keytool, "-genkeypair", "-alias", "airms", "-keyalg", "EC",
        "-groupname", "secp256r1", "-storetype", "PKCS12", "-keystore", pfx.toString(), "-storepass", PW,
        "-dname", "CN=test, O=AiRMS"));
    cmd.addAll(List.of(extra));
    Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
    p.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
    assertTrue(p.waitFor(60, TimeUnit.SECONDS));
    assertEquals(0, p.exitValue());
    return pfx;
  }

  private static Secrets secretsFile(Path dir, String json) throws Exception {
    Path f = dir.resolve("secrets.json");
    Files.writeString(f, json, StandardCharsets.UTF_8);
    return Secrets.load(f, Map.<String, String>of()::get);
  }

  @Test
  void explainsWhatIsMissing() throws Exception {
    Path dir = Files.createTempDirectory("airms-tls");
    Secrets none = Secrets.load(dir.resolve("secrets.json"), Map.<String, String>of()::get);

    String noPfx = assertThrows(StartupException.class,
        () -> TlsKeystore.check(dir.resolve("server.pfx"), none)).getMessage();
    assertTrue(noPfx.contains("找不到 HTTPS 憑證檔") && noPfx.contains(dir.resolve("server.pfx").toString()));
    assertTrue(noPfx.contains("new-cert"));

    Path pfx = makeKeystore(dir, "-validity", "365");
    String noSecrets = assertThrows(StartupException.class, () -> TlsKeystore.check(pfx, none)).getMessage();
    assertTrue(noSecrets.contains("找不到機密設定檔") && noSecrets.contains(dir.resolve("secrets.json").toString()));
    assertTrue(noSecrets.contains(Secrets.ENV_TLS_PASSWORD));

    String empty = assertThrows(StartupException.class,
        () -> TlsKeystore.check(pfx, secretsFile(dir, "{}"))).getMessage();
    assertTrue(empty.contains("裡沒有 tlsKeystorePassword"));

    String wrong = assertThrows(StartupException.class,
        () -> TlsKeystore.check(pfx, secretsFile(dir, "{\"tlsKeystorePassword\":\"nope\"}"))).getMessage();
    assertTrue(wrong.contains("密碼不正確"), wrong);

    Files.writeString(dir.resolve("broken.pfx"), "not a keystore");
    String broken = assertThrows(StartupException.class, () -> TlsKeystore.check(dir.resolve("broken.pfx"),
        secretsFile(dir, "{\"tlsKeystorePassword\":\"" + PW + "\"}"))).getMessage();
    assertTrue(broken.contains("無法讀取") || broken.contains("密碼不正確"), broken);
  }

  @Test
  void acceptsGoodKeystoreAndEnvironmentPassword() throws Exception {
    Path dir = Files.createTempDirectory("airms-tls2");
    Path pfx = makeKeystore(dir, "-validity", "365");
    TlsKeystore.Info ok = TlsKeystore.check(pfx, secretsFile(dir, "{\"tlsKeystorePassword\":\"" + PW + "\"}"));
    assertEquals(PW, ok.password());
    assertTrue(ok.cert().subject().contains("CN=test"));
    assertTrue(ok.cert().notAfter().isAfter(Instant.now().plusSeconds(300L * 86400)));

    // 環境變數優先於 secrets.json
    Secrets env = Secrets.load(dir.resolve("none.json"), Map.of(Secrets.ENV_TLS_PASSWORD, PW)::get);
    assertEquals(PW, TlsKeystore.check(pfx, env).password());

    // 已過期：只警告、不阻止啟動
    Path old = Files.createTempDirectory("airms-tls3");
    Path expired = makeKeystore(old, "-startdate", "-400d", "-validity", "30");
    TlsKeystore.Info info = TlsKeystore.check(expired, secretsFile(old, "{\"tlsKeystorePassword\":\"" + PW + "\"}"));
    assertTrue(info.cert().notAfter().isBefore(Instant.now()));
  }

  @Test
  void expiryWarningUsesConfiguredDays() {
    Instant now = Instant.parse("2026-11-05T00:00:00Z");
    TlsKeystore.Cert c = new TlsKeystore.Cert("CN=pi", Instant.parse("2027-01-04T00:00:00Z"));   // 還有 60 天
    assertEquals(60, c.daysLeft(now));
    assertEquals(null, TlsKeystore.expiryWarning(c, now, 30));
    String w = TlsKeystore.expiryWarning(c, now, 90);
    assertTrue(w.contains("2027/01/04") && w.contains("還有 60 天"), w);
    TlsKeystore.Cert gone = new TlsKeystore.Cert("CN=pi", Instant.parse("2026-11-01T00:00:00Z"));
    assertTrue(gone.expired(now));
    assertEquals(-4, gone.daysLeft(now));
    assertTrue(TlsKeystore.expiryWarning(gone, now, 7).contains("已於 2026/11/01 過期"));
    assertEquals(null, TlsKeystore.expiryWarning(null, now, 90));
  }
}

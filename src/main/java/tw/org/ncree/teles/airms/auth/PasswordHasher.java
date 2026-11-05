package tw.org.ncree.teles.airms.auth;

import de.mkammerer.argon2.Argon2Factory;
import java.util.Arrays;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * Argon2id 密碼雜湊，參數與 StockVX / EMS 相同（t=3, m=64 MiB, p=1）。
 * 很耗 CPU 與記憶體，只能在專用的 worker 執行緒呼叫；輸入的字元陣列用完即清除。
 */
public final class PasswordHasher {
  private static final Pattern HASH = Pattern.compile(
      "^\\$argon2id\\$v=19\\$m=(\\d{1,6}),t=(\\d{1,2}),p=(\\d)\\$([A-Za-z0-9+/]{11,86})\\$([A-Za-z0-9+/]{22,86})$");

  private PasswordHasher() { }

  public static String hash(char[] password) {
    try {
      if (password == null || password.length == 0 || password.length > 1024) {
        throw new IllegalArgumentException("密碼長度不合法");
      }
      return Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id).hash(3, 65_536, 1, password);
    } finally {
      wipe(password);
    }
  }

  public static boolean verify(String hash, char[] password) {
    try {
      if (!validHash(hash) || password == null || password.length == 0 || password.length > 1024) return false;
      return Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id).verify(hash, password);
    } finally {
      wipe(password);
    }
  }

  /** 拒絕格式錯誤、非 Argon2id 或成本參數異常的雜湊。 */
  public static boolean validHash(String hash) {
    if (hash == null) return false;
    var match = HASH.matcher(hash);
    if (!match.matches()) return false;
    int memory = Integer.parseInt(match.group(1));
    int iterations = Integer.parseInt(match.group(2));
    int parallelism = Integer.parseInt(match.group(3));
    if (memory < 19_456 || memory > 131_072 || iterations < 2 || iterations > 6
        || parallelism < 1 || parallelism > 4) return false;
    try {
      byte[] salt = Base64.getDecoder().decode(match.group(4));
      byte[] digest = Base64.getDecoder().decode(match.group(5));
      return salt.length >= 8 && digest.length >= 16;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  static void wipe(char[] password) {
    if (password != null) Arrays.fill(password, '\0');
  }
}

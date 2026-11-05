package tw.org.ncree.teles.airms.auth;

import java.util.regex.Pattern;

/** 帳號名稱、顯示名稱與密碼的規則。回傳錯誤訊息，通過時回傳 null。 */
public final class AccountRules {
  private AccountRules() { }

  public static final int PASSWORD_MIN = 10;
  public static final int PASSWORD_MAX = 128;
  private static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9._-]{3,32}$");

  public static String checkUsername(String username) {
    if (username == null || !USERNAME.matcher(username).matches()) {
      return "帳號需為 3～32 個英數字，可含 . _ -";
    }
    return null;
  }

  public static String checkDisplayName(String name) {
    if (name == null || name.isBlank()) return "請填寫顯示名稱";
    if (name.length() > 40) return "顯示名稱最多 40 字";
    for (char ch : name.toCharArray()) if (Character.isISOControl(ch)) return "顯示名稱含有不允許的字元";
    return null;
  }

  public static String checkPassword(String password, String username) {
    if (password == null || password.length() < PASSWORD_MIN) return "密碼至少 " + PASSWORD_MIN + " 個字元";
    if (password.length() > PASSWORD_MAX) return "密碼最多 " + PASSWORD_MAX + " 個字元";
    boolean letter = false;
    boolean digit = false;
    for (char ch : password.toCharArray()) {
      if (Character.isISOControl(ch)) return "密碼含有不允許的字元";
      if (Character.isLetter(ch)) letter = true;
      if (Character.isDigit(ch)) digit = true;
    }
    if (!letter || !digit) return "密碼需同時包含英文字母與數字";
    if (username != null && password.toLowerCase().contains(username.toLowerCase())) return "密碼不可包含帳號名稱";
    return null;
  }
}

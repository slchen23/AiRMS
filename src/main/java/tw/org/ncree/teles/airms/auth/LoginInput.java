package tw.org.ncree.teles.airms.auth;

/**
 * 登入時輸入的帳號名稱檢查（比照 EMS）：最長 50 個 Java 字元，拒絕控制、格式控制、行／段分隔、
 * 落單代理字元、私用區與未指派的字元。寫進登入紀錄前另外清理，避免把奇怪字元或超長字串存進紀錄。
 * 這是登入時的入口檢查；建立帳號時另有更嚴格的規則（AccountRules：英數與 . _ -，3～32 字）。
 */
public final class LoginInput {
  public static final int USERNAME_MAX = 50;

  private LoginInput() { }

  /** 去掉前後空白（Unicode 空白）；null 視為空字串。 */
  public static String normalize(String raw) {
    return raw == null ? "" : raw.strip();
  }

  /** 檢查已 normalize 的名稱；合法回傳 null，否則回傳錯誤訊息。空字串由呼叫端另外處理。 */
  public static String check(String name) {
    if (name.length() > USERNAME_MAX) return "帳號格式不正確（最多 " + USERNAME_MAX + " 個字）";
    for (int i = 0; i < name.length(); ) {
      int cp = name.codePointAt(i);
      if (forbidden(cp)) return "帳號格式不正確（含有不允許的字元）";
      i += Character.charCount(cp);
    }
    return null;
  }

  static boolean forbidden(int cp) {
    return switch (Character.getType(cp)) {
      case Character.CONTROL, Character.FORMAT, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR,
           Character.SURROGATE, Character.PRIVATE_USE, Character.UNASSIGNED -> true;
      default -> false;
    };
  }

  /** 寫進登入紀錄用：不允許的字元換成「?」，超過上限截斷並加「…」（不切斷代理字元對）。 */
  public static String forLog(String raw) {
    if (raw == null) return "";
    StringBuilder sb = new StringBuilder(Math.min(raw.length(), USERNAME_MAX) + 1);
    int i = 0;
    while (i < raw.length()) {
      int cp = raw.codePointAt(i);
      int n = Character.charCount(cp);
      if (sb.length() + n > USERNAME_MAX) {
        sb.append('…');
        break;
      }
      if (forbidden(cp)) sb.append('?');
      else sb.appendCodePoint(cp);
      i += n;
    }
    return sb.toString();
  }
}

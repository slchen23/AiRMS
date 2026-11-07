package tw.org.ncree.teles.airms.control;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AiRMS 設備的 TCP 指令（沿用舊系統，每次連線送一筆、收一筆，都是 6 個 ASCII 字元）：
 * <ul>
 *   <li>查詢：送 {@code 01,000}，回 {@code 91,SSX}</li>
 *   <li>設定：送 {@code 02,SSX}，成功回 {@code 92,SSX}；設備無法設定回 {@code 99,000}</li>
 * </ul>
 * SS 為兩位數儀控敏感性，X 為 AiRMS 開（1）／關（0）。
 */
public final class AirmsProtocol {
  /** 畫面與 API 允許的敏感性（同舊畫面：40～80，每次 5）。 */
  public static final int SENSITIVITY_MIN = 40;
  public static final int SENSITIVITY_MAX = 80;
  public static final int SENSITIVITY_STEP = 5;
  public static final int LENGTH = 6;

  public static final String QUERY = "01,000";
  private static final Pattern REPLY = Pattern.compile("^(9[12]),(\\d{2})([01])$");
  private static final String REFUSED = "99,000";

  public enum Kind { STATUS, SET_OK, REFUSED }

  /** 設備回覆。REFUSED 時 sensitivity/on 沒有意義。 */
  public record Reply(Kind kind, int sensitivity, boolean on, String raw) { }

  private AirmsProtocol() { }

  public static boolean validSensitivity(int s) {
    return s >= SENSITIVITY_MIN && s <= SENSITIVITY_MAX && (s - SENSITIVITY_MIN) % SENSITIVITY_STEP == 0;
  }

  public static String setCommand(int sensitivity, boolean on) {
    if (!validSensitivity(sensitivity)) throw new IllegalArgumentException("敏感性超出範圍：" + sensitivity);
    return String.format("02,%02d%d", sensitivity, on ? 1 : 0);
  }

  /** 解析設備回覆；格式不對回傳 null。 */
  public static Reply parse(String raw) {
    if (raw == null) return null;
    if (REFUSED.equals(raw)) return new Reply(Kind.REFUSED, 0, false, raw);
    Matcher m = REPLY.matcher(raw);
    if (!m.matches()) return null;
    Kind kind = m.group(1).equals("91") ? Kind.STATUS : Kind.SET_OK;
    return new Reply(kind, Integer.parseInt(m.group(2)), m.group(3).equals("1"), raw);
  }

  /** 記錄用：只留可見的 ASCII，其餘換成「?」，最多 32 字。 */
  public static String printable(byte[] bytes) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < bytes.length && sb.length() < 32; i++) {
      int b = bytes[i] & 0xff;
      sb.append(b >= 0x20 && b < 0x7f ? (char) b : '?');
    }
    if (bytes.length > 32) sb.append('…');
    return sb.toString();
  }

  /** 設備位址：IPv4 或主機名稱（不含 http://、port、路徑）。 */
  public static boolean validHost(String host) {
    if (host == null || host.isEmpty() || host.length() > 253) return false;
    if (host.matches("^\\d{1,3}(\\.\\d{1,3}){3}$")) {
      for (String p : host.split("\\.")) if (Integer.parseInt(p) > 255) return false;
      return true;
    }
    for (String label : host.split("\\.", -1)) {
      if (!label.matches("^[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?$")) return false;
    }
    return true;
  }
}

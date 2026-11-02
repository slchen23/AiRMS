package tw.org.ncree.teles.airms.parse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 一份檔案的解析統計。警告最多留 20 筆，避免 log 與資料庫被灌爆。 */
public final class ParseStats {
  private static final int MAX_WARNINGS = 20;

  private int total;
  private int bad;
  private int skipped;
  private final List<String> warnings = new ArrayList<>();

  public void row() {
    total++;
  }

  /** 資料列有問題但仍可保存（例如某欄缺值），或整列無法保存。 */
  public void bad(String message) {
    bad++;
    warn(message);
  }

  /** 依設定過濾掉的列，不算錯誤。 */
  public void skipped() {
    skipped++;
  }

  public void warn(String message) {
    if (warnings.size() < MAX_WARNINGS) warnings.add(clean(message));
  }

  public int total() {
    return total;
  }

  public int bad() {
    return bad;
  }

  public int skippedCount() {
    return skipped;
  }

  public List<String> warnings() {
    return Collections.unmodifiableList(warnings);
  }

  /** 去除換行並截斷，避免來源內容造成 log 注入。 */
  public static String clean(String s) {
    if (s == null) return "";
    String t = s.replace('\r', ' ').replace('\n', ' ');
    return t.length() > 200 ? t.substring(0, 200) + "…" : t;
  }
}

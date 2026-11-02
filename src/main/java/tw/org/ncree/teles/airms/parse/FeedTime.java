package tw.org.ncree.teles.airms.parse;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;

/**
 * 來源檔頭時間（台北時間 "yyyy-MM-dd HH:mm:ss"）轉 UTC 毫秒。
 * 全系統一律明確指定 Asia/Taipei，不依賴主機時區設定。
 */
public final class FeedTime {
  private FeedTime() { }

  public static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
  private static final DateTimeFormatter FORMAT =
      DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT);

  /** 格式不符回 null，由呼叫端標為「時間無法解析」。 */
  public static Long toEpochMs(String raw) {
    if (raw == null) return null;
    try {
      return LocalDateTime.parse(raw.trim(), FORMAT).atZone(TAIPEI).toInstant().toEpochMilli();
    } catch (DateTimeParseException e) {
      return null;
    }
  }
}

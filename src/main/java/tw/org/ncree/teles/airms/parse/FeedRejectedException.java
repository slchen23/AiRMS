package tw.org.ncree.teles.airms.parse;

/** 整份檔案不能用（時間無法解析、沒有任何資料列等）。原始檔仍會歸檔當證據。 */
public final class FeedRejectedException extends Exception {
  private static final long serialVersionUID = 1L;

  public FeedRejectedException(String message) {
    super(message);
  }
}

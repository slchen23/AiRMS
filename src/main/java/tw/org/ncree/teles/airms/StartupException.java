package tw.org.ncree.teles.airms;

/**
 * 啟動前檢查發現的設定問題（缺檔、密碼錯誤等）。只需要訊息、不需要程式堆疊：
 * log 只印訊息（可多行），讓現場人員一眼看到要做什麼。
 */
public final class StartupException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public StartupException(String message) {
    super(message, null, false, false);
  }
}

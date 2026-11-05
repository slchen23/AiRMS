package tw.org.ncree.teles.airms.auth;

/** 帳號相關操作的錯誤：HTTP 狀態碼與可以直接顯示給使用者的訊息（不含內部細節）。 */
public final class AuthException extends Exception {
  private static final long serialVersionUID = 1L;
  private final int status;

  public AuthException(int status, String message) {
    super(message);
    this.status = status;
  }

  public int status() {
    return status;
  }
}

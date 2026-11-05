package tw.org.ncree.teles.airms.auth;

import java.util.Optional;

/** 一般使用者的功能權限，由管理者逐帳號勾選。管理者角色本身擁有全部權限。 */
public enum Permission {
  AIRMS_CONTROL("AiRMS 控制", "airms2 頁面調整敏感度與啟停"),
  DATA_DOWNLOAD("資料下載", "query 頁面下載儀控時制"),
  VIEW("監視", "view2 改成需要登入時使用");

  private final String label;
  private final String description;

  Permission(String label, String description) {
    this.label = label;
    this.description = description;
  }

  public String label() {
    return label;
  }

  public String description() {
    return description;
  }

  public static Optional<Permission> parse(String s) {
    for (Permission p : values()) if (p.name().equals(s)) return Optional.of(p);
    return Optional.empty();
  }
}

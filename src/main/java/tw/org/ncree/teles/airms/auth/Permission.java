package tw.org.ncree.teles.airms.auth;

import java.util.Optional;

/** 一般使用者的功能權限，由管理者逐帳號勾選。管理者角色本身擁有全部權限。 */
public enum Permission {
  AIRMS_CONTROL("AiRMS 控制", "讀取與設定 AiRMS 設備的儀控敏感性與開關，也可看即時監視"),
  DATA_DOWNLOAD("資料下載", "依匝道與時間區間下載儀控時制"),
  VIEW("監視", "看即時監視；系統設定「首頁與即時監視需要登入」開啟時才需要");

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

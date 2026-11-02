package tw.org.ncree.teles.airms.feed;

/** 三個開放資料來源。key 用在設定檔、資料庫 source 欄位與原始檔目錄名稱。 */
public enum FeedKind {
  VD("vd", "VD 五分鐘資料"),
  SECTION("section", "路段交通資料"),
  RMS("rms", "匝道儀控運作資料");

  private final String key;
  private final String label;

  FeedKind(String key, String label) {
    this.key = key;
    this.label = label;
  }

  public String key() {
    return key;
  }

  public String label() {
    return label;
  }
}

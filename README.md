# AiRMS

國道匝道儀控（AiRMS）監控系統新版。取代舊的 starter（Vert.x 4 + MongoDB），改用 Vert.x 5 + SQLite，
最終在 Raspberry Pi 5 上離線運作；過渡期先在 Windows 與舊系統並行（新系統 8081、舊系統 8082）。

- 技術：Java 21、Vert.x 5.2.2、SQLite（sqlite-jdbc 3.53.4.0）、Gradle 8.14.5（與 StockVX 一致）
- 套件：`tw.org.ncree.teles.airms`
- 進度：P1 資料抓取與保存（`docs/P1.md`）、P2 帳號系統與管理工具頁（`docs/P2.md`）、
  **P3 路網設定檔與即時監視頁 view2**（`docs/P3.md`）
- 前端套件全部放在本機（`docs/vendor.md`），正式機不需要對外網路

## 第一次在 Windows 上執行

1. **準備外接硬碟的資料目錄**，例如 `E:\airms-data`，在裡面建立一個空檔 `.airms-volume`。
   沒有這個標記檔程式會拒絕啟動，防止硬碟沒接上時把資料寫到 C 槽。
2. **憑證**：把 HTTPS 憑證（PKCS#12，.pfx）放在專案資料夾以外，例如 `E:\airms-secrets\server.pfx`。
   憑證與密碼不可放進專案資料夾或 git。
3. **設定檔**：複製 `config.example.json` 為 `config.json`，修改 `storage.dataDir` 與 `http.tls.keystorePath`。
4. **憑證密碼**：複製 `secrets.example.json` 為 `secrets.json` 填入密碼；
   或改用環境變數 `AIRMS_TLS_KEYSTORE_PASSWORD`（優先）。
5. **執行**（工作目錄必須是專案資料夾）：
   - 開發：`gradlew run`
   - 打包：`gradlew shadowJar` → `build/libs/airms-<版本>-fat.jar`（例如 `airms-0.3.0-fat.jar`），執行
     `java -Duser.timezone=Asia/Taipei -jar build\libs\airms-0.3.0-fat.jar`
   - 版本號只改 `build.gradle.kts` 的 `version`；啟動完成的 log「AiRMS 0.3.0 已啟動…」與 `service_run` 表都會記錄版本
6. **建立第一個管理者**：第一次啟動時 log 會印出一組設定碼，30 分鐘內打開
   `https://localhost:8081/setup.html` 輸入設定碼並設定管理者帳號密碼（沒有預設帳密）。
7. 首頁 `https://localhost:8081/` 顯示三個來源的抓取狀態；即時監視頁 `https://localhost:8081/view2.html`
   （不需登入）。登入後右上角選單進入帳號設定，管理者左側有「使用者管理、登入管理、路網匝道、路網路段、系統設定」。

`config.json`、`secrets.json`、`*.pfx`、資料庫與原始 XML 都已列在 `.gitignore`。

## 開發時常常停機重跑

- **資料不會壞**：資料庫用 WAL 模式，每份資料在一個交易內寫完；程式被強制結束（例如 IntelliJ 按停止）
  只會丟掉還沒寫完的那一份，重啟後自動補做，不會重複寫入。
- **停機期間的資料抓不回來**：開放資料只提供當下那一份，停機那段時間的 VD、路段、RMS 會是缺口。
  每次啟動、每分鐘心跳、正常停止都記在 `airms.db` 的 `service_run` 表，日後分析時可以分辨
  「系統沒在跑」和「來源沒資料」；上次不是正常停止時，啟動 log 會列出停機時間。
- **同一個資料目錄只能有一個 AiRMS**：第二個會拒絕啟動（`.airms-lock`，程式結束時自動釋放）。
- 中斷留下的暫存檔會在下次啟動時清掉；同一天重複收到同一份內容不會多存檔。

**建議分開兩份資料**：要長期累積、之後搬到 Pi 的正式資料，用打包好的 jar 長時間執行；
開發測試另外用一個資料目錄（例如 `E:\airms-data-dev`，同樣放 `.airms-volume`），
開發時用 `-Dairms.config=config.dev.json` 指定另一份設定（不同 dataDir 與 port）。
這樣開發中的停機、測試資料都不會混進正式資料。

## 資料目錄（外接硬碟）

```
airms-data/
├─ .airms-volume      掛載標記檔（手動建立）
├─ .airms-lock        執行中的程式持有的鎖（自動產生）
├─ airms.db           路段、RMS 最新與歷史、抓取紀錄、啟停紀錄（歷史依 retention 天數清除）
├─ vd_health.db       全部 VD 的車道原值，永久保存
├─ auth.db            帳號、權限、登入紀錄、操作紀錄
└─ xml_raw/           三種來源的原始 XML（gzip），永久保存
   ├─ vd/2026/11/02/20261102_160512_<雜湊>.xml.gz
   ├─ section/…
   └─ rms/…
```

- 內容沒變的 XML 不重複存；結構有問題、解析失敗的檔一定會存，當作來源異常的證據。
- `raw_xml_file` 表記錄每個原始檔的解析結果與問題摘要；`feed_attempt` 記錄每一次抓取。
- SQLite 檔可直接複製到 Pi（先停止程式），格式跨平台通用。
- `auth.db` 含帳號密碼雜湊，備份時與其他資料庫一樣要妥善保管。

## 測試

```
gradlew test
```

## 設定重點

| 項目 | 說明 |
|---|---|
| `feeds.*.url` | 開發期用高公局外網網址；正式機改成內網來源網址即可，不用改程式 |
| `feeds.*.intervalSeconds` | 抓取間隔（每一輪完成後才排下一輪，不會堆積） |
| `feeds.section.filter` | 只存國1、國3、起點里程 99000～270000 的路段 |
| `feeds.rms.filter` | 只存 RMS-N1、RMS-N3 開頭的設備 |
| `retention.*Days` | 路段/RMS 歷史與抓取紀錄的保留天數；VD 與原始 XML 永久保存 |
| `networkFile`（選填） | 路網設定檔路徑；不填用程式內建的 `network/network.json`。增減匝道見 `docs/P3.md`；只是隱藏匝道圈或改 RMS 代碼，用管理選單「路網匝道」頁即可 |

設定檔修改後需重新啟動才生效；設定有誤時程式會列出所有錯誤並停止。
來源的啟用/網址/間隔、保留天數、登入安全設定也可以由管理者在「系統設定」頁修改（立即生效，
存在 airms.db；按「還原預設」回到 config.json 的值）。

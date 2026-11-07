# AiRMS

國道匝道儀控（AiRMS）監控系統新版。取代舊的 starter（Vert.x 4 + MongoDB），改用 Vert.x 5 + SQLite，
最終在 Raspberry Pi 5 上離線運作；過渡期先在 Windows 與舊系統並行（新系統 8081、舊系統 8082）。

- 技術：Java 21、Vert.x 5.2.2、SQLite（sqlite-jdbc 3.53.4.0）、Gradle 8.14.5（與 StockVX 一致）
- 套件：`tw.org.ncree.teles.airms`
- 進度：P1 資料抓取與保存（`docs/P1.md`）、P2 帳號系統與管理工具頁（`docs/P2.md`）、
  P3 路網設定檔與即時監視頁 view.html（`docs/P3.md`）、資料保存改為 SD 卡＋月檔＋外接硬碟備份（`docs/STORAGE.md`）、
  P4 AiRMS 控制頁 airms.html（`docs/P4.md`）、**P5 資料下載頁 query.html**（`docs/P5.md`）
- 前端套件全部放在本機（`docs/vendor.md`），正式機不需要對外網路

## 第一次在 Windows 上執行

1. **準備資料目錄**，例如 `E:\airms-data`（正式機為 SD 卡上的資料夾），在裡面建立一個空檔 `.airms-volume`。
   沒有這個標記檔程式會拒絕啟動，防止路徑設錯時在別處建立一套新的空資料庫。
   要備份到外接硬碟時，另在外接硬碟的備份資料夾建立空檔 `.airms-backup`（見 `docs/STORAGE.md`）。
2. **HTTPS 憑證**（每台新電腦都要做一次，見下方「HTTPS 憑證」）：在專案資料夾執行
   `powershell -ExecutionPolicy Bypass -File tools\tls\new-cert.ps1`，會在 `D:\airms-secrets` 產生
   `server.pfx`、`secrets.json`（隨機密碼）、`server.cer`。
3. **設定檔**：複製 `config.example.json` 為 `config.json`，修改 `storage.dataDir`，
   並依上一步畫面提示設定 `http.tls.keystorePath` 與 `secretsFile`。
   正式機把 `backup.enabled` 設為 `true` 並填 `backup.targetDir`；測試機維持 `false`。
4. 憑證密碼也可以改用環境變數 `AIRMS_TLS_KEYSTORE_PASSWORD`（優先於 secrets.json）。
5. **執行**（工作目錄必須是專案資料夾）：
   - 開發：`gradlew run`
   - 打包：`gradlew shadowJar` → `build/libs/airms-<版本>-fat.jar`（例如 `airms-0.5.0-fat.jar`），執行
     `java -Duser.timezone=Asia/Taipei -jar build\libs\airms-0.5.0-fat.jar`
   - 版本號只改 `build.gradle.kts` 的 `version`；啟動完成的 log「AiRMS 0.5.0 已啟動…」與 `service_run` 表都會記錄版本
6. **建立第一個管理者**：第一次啟動時 log 會印出一組設定碼，30 分鐘內打開
   `https://localhost:8081/setup.html` 輸入設定碼並設定管理者帳號密碼（沒有預設帳密）。
7. 首頁 `https://localhost:8081/`（同一台電腦也可用 `https://127.0.0.1:8081/`；其他電腦用 `https://<主機名稱>:8081/`，啟動 log 會列出）
   顯示三個來源的抓取狀態、資料目錄剩餘空間、備份與憑證狀態。功能頁面：
   - 即時監視 `view.html`（P3）：預設要登入，且需「監視」或「AiRMS 控制」權限；只在內網使用時，可到「系統設定 → 首頁與即時監視需要登入」關閉
   - AiRMS 控制 `airms.html`（P4）：需「AiRMS 控制」權限；設備 IP 在「系統設定 → AiRMS 設備」填
   - 資料下載 `query.html`（P5）：需「資料下載」權限
   左側選單依權限顯示；管理者另有「使用者管理、登入管理、路網匝道、路網路段、系統設定」。

`config.json`、`secrets.json`、`*.pfx`、資料庫與原始 XML 都已列在 `.gitignore`。

## HTTPS 憑證

憑證檔（.pfx）與密碼檔（secrets.json）刻意不進 git，所以**每台新電腦（含樹莓派）都要先產生**，否則啟動時會停下並說明缺哪個檔。

| 電腦 | 指令（在專案資料夾執行） | 預設輸出 |
|---|---|---|
| Windows | `powershell -ExecutionPolicy Bypass -File tools\tls\new-cert.ps1` | `D:\airms-secrets` |
| 樹莓派／Linux | `sudo tools/tls/new-cert.sh -o /etc/airms -u <執行 AiRMS 的帳號>` | `~/airms-secrets` |

- 產生自簽憑證（EC P-256，預設有效 10 年），名稱含 localhost、本機名稱、本機 IP；其他對外名稱或 IP 可加在後面
  （Windows：`-Names airms.local,10.1.2.3`；Linux：直接接在指令後面）。
- 有效天數可改（Windows `-Days`、Linux `-d`）。自簽憑證只給 Windows／Android 的 Chrome、Edge、Firefox 使用時沒有上限；
  **會用 iPhone、iPad、Mac 開的話，Apple 規定不可超過 825 天**，請用 `-Days 825`／`-d 825`。
- 密碼隨機產生，只寫進 secrets.json，不顯示、不出現在命令列；資料夾權限只給目前使用者（Pi 為 700／600）。
- 已有憑證時不會覆蓋，要重新產生加 `-Force`（Windows）或 `-f`（Linux）。
- 完成後畫面會列出 config.json 要填的 `keystorePath` 與 `secretsFile`（可用絕對路徑）。
- 瀏覽器會提示自簽憑證；把 `server.cer` 匯入使用者電腦的「受信任的根憑證授權單位」即可消除。
- 啟動時會先檢查憑證（在開資料庫之前）：缺檔、缺密碼、密碼不對都會直接說明。
- 到期提醒：剩下天數少於「系統設定 → HTTPS 憑證 → 到期前幾天開始提醒」（預設 90 天）時，首頁顯示提醒、log 每天警告一次；
  首頁下方平常也會顯示憑證有效到哪一天。

## 開發時常常停機重跑

- **資料不會壞**：資料庫用 WAL 模式，每份資料在一個交易內寫完；程式被強制結束（例如 IntelliJ 按停止）
  只會丟掉還沒寫完的那一份，重啟後自動補做，不會重複寫入。
- **停機期間的資料抓不回來**：開放資料只提供當下那一份，停機那段時間的 VD、路段、RMS 會是缺口。
  每次啟動、每分鐘心跳、正常停止都記在 `db/core.db` 的 `service_run` 表，日後分析時可以分辨
  「系統沒在跑」和「來源沒資料」；上次不是正常停止時，啟動 log 會列出停機時間。
- **同一個資料目錄只能有一個 AiRMS**：第二個會拒絕啟動（`.airms-lock`，程式結束時自動釋放）。
- 中斷留下的暫存檔會在下次啟動時清掉；同一天重複收到同一份內容不會多存檔。

**建議分開兩份資料**：要長期累積、之後搬到 Pi 的正式資料，用打包好的 jar 長時間執行；
開發測試另外用一個資料目錄（例如 `E:\airms-data-dev`，同樣放 `.airms-volume`），
開發時用 `-Dairms.config=config.dev.json` 指定另一份設定（不同 dataDir 與 port）。
這樣開發中的停機、測試資料都不會混進正式資料。

## 資料目錄

```
airms-data/                      正式機在 SD 卡上
├─ .airms-volume                 標記檔（手動建立）
├─ .airms-lock                   執行中的程式持有的鎖（自動產生）
├─ cache/xml_raw/                三種來源的原始 XML（gzip），保留 30 天（可設定）
├─ cache/export/                 資料下載產生中的暫存檔（送出後刪除）
│  ├─ vd/2026/11/02/20261102_160512_<雜湊>.xml.gz
│  ├─ section/…
│  └─ rms/…
└─ db/
   ├─ core.db                    帳號、權限、登入與操作紀錄、系統設定、路網匝道修改、AiRMS 設定紀錄、啟停與備份紀錄
   ├─ vd/vd_202611.db            全部 VD 的車道原值，每月一個檔
   ├─ section/section_202611.db  路段歷史，每月一個檔
   ├─ rms/rms_202611.db          RMS 歷史，每月一個檔
   └─ feedlog/feedlog_202611.db  每次抓取的結果與原始檔索引，每月一個檔
```

- 路段、RMS 的「最新一筆」只放在記憶體，不寫入 SD 卡；程式重啟後下一輪抓取（約 1 分鐘）就恢復。
- 內容沒變的 XML 不重複存；結構有問題、解析失敗的檔一定會存，當作來源異常的證據。
- 保留期限、備份方式、測試機使用方式、舊版資料目錄的處理：見 `docs/STORAGE.md`。
- `core.db` 含帳號密碼雜湊，備份時要妥善保管。

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
| `retention.*` | 保留期限的預設值：原始 XML 天數、VD／路段／RMS／抓取紀錄月數、程式 log 天數（預設 90）；0 表示不刪除（系統設定頁可改） |
| `backup.*` | 是否備份到外接硬碟、備份位置、每天幾點；測試機設 `enabled: false`（見 `docs/STORAGE.md`） |
| `storage.minFreePercent` | 資料目錄所在磁碟剩餘空間低於這個百分比時，首頁顯示警告（預設 15） |
| `airmsDevice`（選填） | AiRMS 設備的預設 IP／port（系統設定頁可改）與連線、回覆逾時；範本 `host` 空白＝未設定。開發時可用 `tools/airms-sim` 模擬器（見 `docs/P4.md`） |
| `networkFile`（選填） | 路網設定檔路徑；不填用程式內建的 `network/network.json`。增減匝道見 `docs/P3.md`；只是隱藏匝道圈或改 RMS 代碼，用管理選單「路網匝道」頁即可 |

設定檔修改後需重新啟動才生效；設定有誤時程式會列出所有錯誤並停止。
來源的啟用/網址/間隔、保留期限、登入安全設定、AiRMS 設備位址也可以由管理者在「系統設定」頁修改（立即生效，
存在 core.db；按「還原預設」回到 config.json 的值）。備份開關與位置只能改 config.json。

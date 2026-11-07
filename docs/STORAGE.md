# 資料保存：SD 卡＋月檔＋外接硬碟備份

2026-11-06 決定、實作。取代 P1～P3 的「全部存在外接硬碟、VD 與原始 XML 永久保存」。

## 原則

- 所有資料先寫在資料目錄（正式機為樹莓派的 SD 卡，128 或 256 GB）。
- 原始 XML（快取）與 SQLite 資料庫放在不同資料夾。
- SQLite 以「月」為單位分檔；月檔就是保存、備份、刪除的單位。
- 外接硬碟只當備份目的地；沒接上時系統照常運作，只是備份暫停、首頁顯示警告。
- **啟用備份時，尚未備份到外接硬碟的資料不刪除。**
- 測試機用 `backup.enabled: false`，不做備份，保留期限一到就直接刪除。

## 資料目錄

```
<storage.dataDir>/
├─ .airms-volume                 標記檔（手動建立；沒有就拒絕啟動）
├─ .airms-lock                   同一資料目錄只能有一個 AiRMS
├─ cache/xml_raw/<來源>/yyyy/MM/dd/*.xml.gz
└─ db/
   ├─ core.db
   ├─ vd/vd_yyyyMM.db
   ├─ section/section_yyyyMM.db
   ├─ rms/rms_yyyyMM.db
   └─ feedlog/feedlog_yyyyMM.db
```

| 檔案 | 內容 | 同步層級 | 刪除 |
|---|---|---|---|
| `core.db` | 帳號、權限、登入紀錄、操作紀錄、系統設定（`app_setting`）、路網匝道修改（`ramp_override`）、啟停紀錄（`service_run`）、備份紀錄（`backup_log`、`backup_done`）、AiRMS 設定紀錄（`airms_control_log`，P4 起，資料庫版本 2） | FULL | 不自動刪除 |
| `vd_yyyyMM.db` | `vd_snapshot`、`vd_device`、`vd_lane`、`vd_device_sample`、`vd_lane_sample`（14 欄原值） | FULL | 保留月數 |
| `section_yyyyMM.db` | `section_history` | NORMAL | 保留月數 |
| `rms_yyyyMM.db` | `rms_history` | NORMAL | 保留月數 |
| `feedlog_yyyyMM.db` | `feed_attempt`（每次抓取）、`raw_xml_file`（每份原始檔的解析結果） | NORMAL | 保留月數 |
| 記憶體 | `section_latest`、`rms_latest`（畫面用的最新一筆） | — | 重啟即清空，下一輪抓取恢復 |

- 月份依資料時間（台北時區）決定；抓取紀錄依抓取開始時間。
- 每個系列的所有月檔共用一條寫入執行緒，仍然只有一個寫入者。
- 月檔在第一次寫入或讀取該月時才開啟；讀取不存在的月份不會建立空檔。
- 各月檔有自己的 VD 設備／車道編號，跨月分析請用 `eq_id` 與 `lane_ref` 對應。
- 屏柵線查近 80 分鐘：跨月時同時讀上個月的 VD 月檔，計算方式不變。

## 保留期限（系統設定頁「資料保留」）

| 設定 | 預設 | 意思 |
|---|---|---|
| 原始 XML 保留天數 | 30 | 保留最近 30 天（含今天）的日期資料夾 |
| VD 保留月數 | 12 | 本月以外再保留 12 個月；例如 2026/11 時保留 2025-11～2026-11 |
| 路段保留月數 | 25 | 約 760 天 |
| RMS 保留月數 | 25 | 約 760 天 |
| 抓取紀錄保留月數 | 3 | |

- 設為 0 表示不刪除。
- 預設值來自 `config.json` 的 `retention`（`rawXmlDays`、`vdMonths`、`sectionMonths`、`rmsMonths`、`feedLogMonths`）。
- 清除每 `retention.runEveryHours` 小時（預設 12）執行一次；啟動後 10 分鐘第一次。
- 月檔整個刪除（含 -wal、-shm），SD 卡空間立即釋放，不需要 VACUUM。
- 舊設定 `retention.sectionHistoryDays` 等已不使用；`config.json` 還有的話啟動時 log 會提醒並略過。

## 備份（config.json 的 `backup`）

```json
"backup": {
  "enabled": true,
  "targetDir": "/mnt/airms-backup",
  "volumeMarker": ".airms-backup",
  "dailyAt": "02:00",
  "coreKeep": 30
}
```

- 備份資料夾裡必須有標記檔 `.airms-backup`（手動建立），避免硬碟沒接上時寫到 SD 卡的空資料夾。
- `targetDir` 不可與 `storage.dataDir` 相同或互相包含。
- 每 10 分鐘檢查一次：已過 `dailyAt`、今天還沒成功就執行；失敗後隔一小時再試。

每次備份做三件事，目的地結構與資料目錄相同：

| 項目 | 做法 | 目的地 |
|---|---|---|
| 原始 XML | 只複製目的地沒有（或大小不同）的檔；今天以前的日期全部到齊後記為已備份 | `cache/xml_raw/…`（不自動刪除） |
| `core.db` | 整份（`VACUUM INTO`，執行中也安全），檢查後保留最新 `coreKeep` 份 | `db/core/core_yyyyMMdd_HHmmss.db` |
| 已結束的月檔 | 每個只複製一次，`quick_check` 通過後記為已備份；上個月要等月初 1 小時後（讓最後一期資料寫完） | `db/<系列>/<系列>_yyyyMM.db`（不自動刪除） |

- **本月的月檔不另外備份**：SD 卡壞掉時，本月資料可以用外接硬碟上的原始 XML 重新解析補回。
  （重新解析的工具尚未製作，需要時再做。）
- 首頁「資料保存與備份」顯示資料目錄剩餘空間、備份狀態、最後成功時間與最近錯誤；
  空間低於 `storage.minFreePercent`（預設 15%）、備份失敗或今天未完成時，上方顯示警告。

## 測試機

- `backup.enabled: false`（範本預設）：不備份、不檢查外接硬碟，首頁顯示「未啟用」。
- 從正式機複製月檔到測試機時，直接放進對應的 `db/<系列>/` 資料夾即可，程式依檔名判斷月份，不需匯入。
- **測試機的保留月數請設為 0**，否則複製過來的舊月份超過期限會被刪除。
- 不需要抓資料時，在系統設定停用三個來源。

## 舊版資料目錄

0.3.0 及更早版本的資料（資料目錄最上層的 `airms.db`、`vd_health.db`、`auth.db` 與 `xml_raw/`）不轉換，
改版時刪除這些檔案、從頭開始累積（只保留標記檔 `.airms-volume`）。帳號需重新用設定碼建立，
系統設定頁與路網匝道頁改過的值回到預設。

## 容量粗估（128 GB SD 卡）

| 項目 | 每天約 | 保留 | 佔用約 |
|---|---|---|---|
| 作業系統、程式 | — | — | 10 GB |
| 原始 XML | 40–60 MB | 30 天 | 1.2–1.8 GB |
| VD | 70 MB | 12 個月 | 25 GB |
| 路段／RMS 歷史 | 30–40 MB | 25 個月 | 25–30 GB |
| core.db、抓取紀錄、log | 很少 | — | 1 GB 以下 |

路段／RMS 與 RMS 原始檔大小為推估，請以實際資料目錄一天的成長量核對。

## 程式位置

| 類別 | 用途 |
|---|---|
| `storage/DataVolume` | 資料目錄結構與標記檔檢查 |
| `db/Series`、`db/MonthlyStore` | 月檔系列、依時間找到月檔、開關連線 |
| `db/Schema` | core.db、各月檔、記憶體資料庫的結構（各有獨立 application_id） |
| `backup/BackupService`、`BackupVerticle` | 每日備份 |
| `retention/RetentionService`、`RetentionVerticle` | 依保留期限刪除 |

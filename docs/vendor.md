# 前端第三方套件（離線）

正式機沒有對外網路，所有前端套件都放在 `src/main/resources/webroot/vendor/<套件>-<版本>/`，
頁面一律引用本機路徑，不使用 CDN。每個套件目錄附原始授權檔（全部為 MIT）。

| 套件 | 版本 | 用到的檔案 | 來源（npm） |
|---|---|---|---|
| AdminLTE | 4.10.0 | `css/adminlte.min.css`（已內含 Bootstrap 5.3 CSS）、`js/adminlte.min.js` | `admin-lte@4.10.0` |
| Bootstrap | 5.3.8 | `js/bootstrap.bundle.min.js`（含 Popper） | `bootstrap@5.3.8` |
| Bootstrap Icons | 1.13.2 | `bootstrap-icons.min.css`、`fonts/*.woff2/.woff` | `bootstrap-icons@1.13.2` |
| DataTables | 3.1.5 | `js/dataTables.min.js`、`js/dataTables.bootstrap5.min.js`、`css/dataTables.bootstrap5.min.css` | `datatables.net@3.1.5`、`datatables.net-bs5@3.1.5` |
| flatpickr | 4.6.13 | `flatpickr.min.js`、`l10n/zh-tw.js`、`themes/dark.css`（資料下載頁的月曆，P5） | `flatpickr@4.6.13` |

- DataTables 3 不需要 jQuery，P2 頁面都沒有載入 jQuery。之後的頁面若需要 jQuery 4，再同樣放進 vendor。
- AdminLTE 預設字型 Source Sans 3 需要從 Google Fonts 下載，這裡不使用；改用系統字型（微軟正黑體／Noto Sans TC），定義在 `css/app.css`。
- 路徑含版本號、內容不會變，伺服器對 `/vendor/` 回應長期快取；其他檔案一律 `no-store`。
- 升級套件：新增一個新版本目錄、改頁面引用、更新本表的雜湊，舊目錄確認沒用到再刪。

## 檔案雜湊（SHA-256）

| 檔案 | SHA-256 |
|---|---|
| `adminlte-4.10.0/LICENSE` | `9e8b0d4e7ce8c13e67a2373d5094910c263fd95b1e49bdf45dc2a9813be5996b` |
| `adminlte-4.10.0/css/adminlte.min.css` | `f96a340f2a464d0778d086a20cdf28ec2df433d37d4b101b2070bce5e35d62ab` |
| `adminlte-4.10.0/css/adminlte.min.css.map` | `6b064bf762fdd22368267283e5c977b0bbcced66c19d2c1a8831f18da0f42651` |
| `adminlte-4.10.0/js/adminlte.min.js` | `c0126e88bd3f051519fb1a95da29b19807f6790a81d5f421c1e569f272c66c2d` |
| `adminlte-4.10.0/js/adminlte.min.js.map` | `001ed020495f79fc57274992e0e26e21bfeb00dc214dfd9109c93a243f325ca8` |
| `bootstrap-5.3.8/LICENSE` | `4620c84ad5ce8602ff65640ed6b7c8b78ebb9e036584f0ebc1ccc88206a4bb51` |
| `bootstrap-5.3.8/js/bootstrap.bundle.min.js` | `e4fd49181388c48ec5040bd3fe66f57c29c8e67fcd8502b3354b96ec7ab47cc7` |
| `bootstrap-5.3.8/js/bootstrap.bundle.min.js.map` | `c61123e58cc0a4b65d737ba070c485911b3dbec6d7b802bdf6628395abd9c08b` |
| `bootstrap-icons-1.13.2/LICENSE` | `0fb3e11bd57e896c5a512afd64864d28a37de45d19835016c87ca1ad19ead969` |
| `bootstrap-icons-1.13.2/bootstrap-icons.min.css` | `79c99c1a1e0e937f808a58165728b7ed00800f02ef5efcfcd10dfe71464e675d` |
| `bootstrap-icons-1.13.2/fonts/bootstrap-icons.woff` | `24929e8a44da027588f833d4e82240ee734bd65bf962b08b0545af58a9ce80fa` |
| `bootstrap-icons-1.13.2/fonts/bootstrap-icons.woff2` | `2b7689746f87e0beeae25338bc021dd2515c2f6f003f3d3d064d878d380b2150` |
| `datatables-3.1.5/LICENSE` | `62b5fdd54371b4f6bd07a60dbc78b38814f49a8028213f540dc2733cad605382` |
| `datatables-3.1.5/css/dataTables.bootstrap5.min.css` | `5ff84d5553f3a60b4927ae5732c2e3799d10c348f10d5b5ebf49f0035352aa05` |
| `datatables-3.1.5/js/dataTables.bootstrap5.min.js` | `f21ebd37ba224864e9224c99bd8d29895dfc36f4be4af516d4c48404017f6503` |
| `datatables-3.1.5/js/dataTables.min.js` | `38017a5586acd9c6eb6bbfe8b6938e1a25bdc717c5acfcb07e0c9d663282c59d` |
| `flatpickr-4.6.13/LICENSE.md` | `5fd57afc3905651175947ae44818e91ffcf5b605e4c88fac42d9700243bd9095` |
| `flatpickr-4.6.13/flatpickr.min.js` | `1eeab1cb779471a0b0aaa93dd91c2eb1aa537d696f01ab05ea9dabc55e8525a1` |
| `flatpickr-4.6.13/l10n/zh-tw.js` | `c57f9846f40d3299ff0057e5670c3b017c285b908eb30826a75360393731a3d2` |
| `flatpickr-4.6.13/themes/dark.css` | `47798b76a38ac3a62b1ae658c566e0ed3b4cbcb115173ae620f0db8952f93612` |

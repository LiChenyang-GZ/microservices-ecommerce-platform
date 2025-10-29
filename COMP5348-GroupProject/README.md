# 🏗️ COMP5348 Group Project — Project Directory Tree

這是本專案的目錄結構與各模組職責說明，採用微服務架構（Store、Bank、Warehouse、Delivery、Email）+ React 前端。

```text
COMP5348-GroupProject/
├── backend/
│   ├── store-service/              ← 主系統：使用者、訂單、庫存協調、外部服務交互
│   ├── bank-service/               ← 銀行系統：帳戶、餘額、轉帳、退款
│   ├── warehouse-service/          ← 倉庫系統：多倉管理、預留、扣減、回補庫存
│   ├── deliveryco-service/         ← 物流系統：發貨、狀態更新、呼叫 Email
│   ├── email-service/              ← 郵件系統：異步通知、訊息佇列
│   ├── settings.gradle             ← 模組管理設定
│   └── build.gradle                ← 共用 Gradle 設定
│
├── frontend/                       ← React / Vue 前端專案
│   ├── package.json                ← 前端依賴設定
│   ├── src/
│   │   ├── pages/                  ← 各功能頁面
│   │   │   ├── LoginPage.jsx
│   │   │   ├── RegisterPage.jsx
│   │   │   ├── ForgotPasswordPage.jsx
│   │   │   ├── MainPage.jsx
│   │   │   ├── MyOrdersPage.jsx
│   │   │   └── warehouse/
│   │   │       ├── AdminLoginPage.jsx
│   │   │       ├── InventoryPage.jsx
│   │   │       ├── EditStockPage.jsx
│   │   │       └── ReservationsPage.jsx
│   │   ├── components/             ← 共用元件
│   │   │   ├── Navbar.jsx
│   │   │   ├── ItemCard.jsx
│   │   │   └── OrderCard.jsx
│   │   ├── services/               ← API 連線層 (axios)
│   │   │   ├── api.js
│   │   │   ├── storeService.js
│   │   │   └── warehouseService.js
│   │   ├── styles/                 ← 全域樣式
│   │   │   └── main.css
│   │   ├── App.jsx
│   │   └── index.jsx
│   └── public/
│       └── index.html
│
└── PROJECT_STRUCTURE.md             ← 架構文件（報告用）

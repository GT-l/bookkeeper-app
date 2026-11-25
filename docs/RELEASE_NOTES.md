# Bookkeeper · 个人记账 App — Debug APK v1.0.0

一款**完全本地离线**的 Android 原生记账应用。Kotlin + Jetpack Compose 编写，支持手动记账与四路自动捕获，数据经 SQLCipher 加密存储在本地，不联网、不上传、不收集任何个人信息。

---

## 📦 下载与安装

| 项目 | 说明 |
|---|---|
| 安装包 | `bookkeeper-debug.apk`（约 86 MB） |
| 系统要求 | Android 8.0（API 26）及以上 |
| 签名 | Debug 调试签名（个人自用足够，可覆盖安装旧版） |
| 网络 | 核心功能完全离线，无需联网 |

**两种安装方式（任选其一）：**

1. **手机直接安装**：用手机浏览器打开本 Release 页面 → 下载 `bookkeeper-debug.apk` → 点击安装（需允许「安装未知来源应用」）。
2. **电脑通过 adb 安装**：
   ```bash
   adb install -r bookkeeper-debug.apk
   ```

首次启动会申请通知权限（用于记账提醒）。**自动化相关权限在「我的 → 自动化设置」中按需开启，不开也完全不影响手动记账核心功能。**

---

## ✨ 功能全景

### 1. 手动记账
- 支出 / 收入 / 转账三类账单，金额以「分」为单位用 `Long` 存储，全链路无浮点误差
- 分类九宫格、标签多选、账户选择、备注、日期、周期账单
- 账单列表按日分组，支持搜索与多维筛选（分类 / 账户 / 标签 / 时间 / 来源）
- 编辑、删除、复制账单；账户余额与账单变更在同一 Room 事务内**原子提交**，不会出现「记了账但余额没变」

### 2. 四路自动捕获（核心亮点）
| 捕获通道 | 说明 | 所需权限 |
|---|---|---|
| 读屏 Accessibility | 读取支付完成界面的文字节点，实时性最好 | 无障碍服务 |
| 通知监听 | 读取支付类 App 的支付结果通知 | 通知读取 |
| 银行扣款短信 | 解析银行短信的金额 / 时间 / 卡尾，支持 ±180s 容差去重 | `READ_SMS` / `RECEIVE_SMS` |
| 截屏 OCR | ML Kit 离线文字识别，用户逐次授权 | MediaProjection |

四路事件统一进入 `CaptureCoordinator → FieldExtractor → ClassificationRuleEngine → ConfidenceCalculator → DuplicateDetector` 管线。低置信度（默认阈值 0.75）或关键字段缺失时**弹出校对窗让用户修改，确认后才入账**——不会静默记错账。抖音支付包名 `com.ss.android.ugc.aweme` 已纳入种子规则。

### 3. 数据统计（7 个视图）
概览 / 支出饼图 / 收入饼图 / 月度趋势 / 分类明细 / 账户分析 / 标签统计。点击图表元素可下钻到对应账单列表。

### 4. 导入导出与备份
- CSV / XLSX 导入 5 步向导：选源 → 上传 → 字段映射 → 预览校对（失败行标红）→ 确认导入
- 自动处理 UTF-8/GBK 编码、BOM、TSV、多时间格式、金额清洗
- CSV / Excel 导出；本地备份 / 恢复
- 数据库 SQLCipher 加密，截图 EncryptedFile（AES-256-GCM + Keystore）加密

### 5. 资产与预算
- 多账户资产总览、默认付款账户、账户间转账
- 总预算 + 分类预算，进度条实时联动
- 多账本切换与独立统计

### 6. 快捷入口
App Shortcut、桌面小组件（Glance）、悬浮球、前台服务通知，统一走 `QuickCaptureRequest` 契约预填记账表单，最快 3 秒补记一笔。

---

## 🔧 技术栈

| 层 | 技术 |
|---|---|
| UI | Jetpack Compose + Material3 + Navigation Compose |
| 架构 | MVVM + UseCase + Repository，单向数据流（StateFlow / UiIntent） |
| 依赖注入 | Hilt 2.51.1（KSP 2.0.21-1.0.28） |
| 本地存储 | Room 2.6.1 + SQLCipher 4.5.5 + DataStore + EncryptedFile |
| 异步 | Coroutines + Flow + WorkManager |
| OCR | ML Kit Text Recognition（离线） |
| 图表 | MPAndroidChart 3.1.0 |
| 导入导出 | kotlin-csv + Apache POI 5.2.5 |
| 小组件 | Glance 1.1.0 |
| 构建 | AGP 8.5.2 / Kotlin 2.0.21 / Gradle 8.9 / JDK 17 / compileSdk 35 |

---

## ⚠️ 能力边界（如实说明，不做夸大宣传）

标准 Android 沙盒内**做不到**以下能力，本项目未实现，也不宣称支持：

- **强杀后无条件自启动**：Android 3.1+ 系统限制，只能引导用户加入厂商电池白名单，无法保证 100% 存活
- **锁屏界面直接识别并自动付款**：标准 API 无法读锁屏界面内容，只能读锁屏通知
- **iOS 上的所有后台自动化能力**：沙盒隔离，完全不可行（本项目仅 Android）
- **抖音免密支付的「真无感」实时检测**：当无通知、无 UI、无短信时，标准 Android 没有任何全局交易事件可供监听。本项目通过「读屏 + 通知 + 银行短信 + 快捷补记」四路兜底，**存在延迟与漏记可能**

任何声称「全自动无感记账」的宣传在这个技术前提下都不成立。上述降级方案已在设置页以固定文案如实告知用户。

---

## 📝 已知限制与注意事项

- 这是 **Debug 构建**，未做混淆与正式签名，包体较大（约 86 MB，含调试符号）；个人自用无碍
- 四路自动捕获依赖用户主动授予对应权限，且不同厂商 ROM 的后台保活策略不同，实际效果因机型而异
- XLSX 导入目前使用 POI DOM 方式 + 10 MB 文件上限，超大文件的真流式（SAX）解析列入二期
- 自动备份为本地文件，未接入云端同步（云同步属二期规划）

---

## 🔨 构建信息

- **构建方式**：GitHub Actions 云端自动构建（`.github/workflows/android.yml`）
- **触发条件**：每次 push 到 `main` 分支或手动 workflow_dispatch
- **验证情况**：`assembleDebug` 与 `testDebugUnitTest`（96 个用例）在 CI 中均真实执行通过
- **源码与文档**：<https://github.com/GT-l/bookkeeper-app>（含 README、完整 PRD、架构设计、QA 报告及 10 轮 CI 构建日志）

---

## 📮 反馈

使用中遇到问题，欢迎在仓库提交 Issue，附上机型、Android 版本与复现步骤。

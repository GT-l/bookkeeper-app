# 少花点儿 · 个人记账 App

一款 Android 原生个人记账应用，Kotlin + Jetpack Compose 编写，支持手动记账、自动捕获账单、CSV/XLSX 导入、统计报表、预算与资产账户管理。

## 下载

**Debug APK**（可直接安装到 Android 8.0+ 设备）：

👉 [GitHub Releases 页面](../../releases) —— 下载最新 Release 附带的 `app-debug.apk`

也可以从 Actions 构建的 Artifacts 下载 `bookkeeper-debug-apk`。

```bash
adb install -r app-debug.apk
```

> Debug 包使用系统默认调试签名，个人自用足够；如需正式签名需另行配置 keystore。

## 功能特性

### 记账核心
- 手动记一笔：支出 / 收入 / 转账，金额以「分」为单位存储，杜绝浮点误差
- 账单列表按日分组，支持搜索、多维筛选（分类 / 账户 / 标签 / 时间 / 来源）
- 分类与标签管理、多账本切换
- 周期账单、预算（总预算 + 分类预算）
- 资产账户管理，账单与余额在同一 Room 事务内原子更新

### 智能自动化（四路捕获）
| 捕获通道 | 说明 |
|---|---|
| 读屏 Accessibility | 读取支付完成界面的文字节点 |
| 通知监听 | 读取支付类 App 的通知 |
| 银行扣款短信 | 解析银行短信的金额 / 时间 / 卡尾（`READ_SMS`） |
| 截屏 OCR | ML Kit 离线文字识别（需用户逐次授权 MediaProjection） |

四路事件统一进入 `CaptureCoordinator → FieldExtractor → ClassificationRuleEngine → ConfidenceCalculator → DuplicateDetector` 管线。低置信度（默认阈值 0.75）或字段缺失时弹出校对窗让用户修改，确认后才入账。抖音支付的包名 `com.ss.android.ugc.aweme` 已纳入种子规则。

**能力边界（如实说明）**：标准 Android 沙盒内做不到以下能力，本项目未实现也不宣称支持：
- 应用被强杀后无条件自启动（需厂商白名单 / 电池白名单配合）
- 锁屏界面直接识别并自动付款（只能读锁屏通知）
- 抖音免密支付「真无感」实时检测（无通知 / 无 UI / 无短信时无法感知，只能靠四路兜底，存在延迟与漏记）
- iOS 上的上述后台能力（沙盒隔离，完全不可行）
- 部分机型（尤其国产定制 ROM）即使已授予全部权限，系统仍可能拒绝悬浮窗显示、后台常驻或通知常驻；这是厂商 ROM 的额外限制，本 App 无法绕过。此时请改用「记一笔」快捷补记，或换用通知/读屏等其他通道。

### 数据与报表
- 7 个统计视图：概览 / 支出饼图 / 收入饼图 / 趋势 / 分类明细 / 账户分析 / 标签统计
- CSV / XLSX 导入 5 步向导（选源 → 上传 → 字段映射 → 预览校对 → 确认导入），失败行标红
- CSV / Excel 导出、本地备份 / 恢复
- 全本地离线：SQLCipher 加密数据库 + EncryptedFile 加密截图，数据不联网

### 快捷入口
App Shortcut、桌面小组件（Glance）、悬浮球、前台服务通知，统一走 `QuickCaptureRequest` 契约预填账单表单。

## 技术栈

| 层 | 技术 |
|---|---|
| UI | Jetpack Compose + Material3 + Navigation Compose |
| 架构 | MVVM + UseCase + Repository，单向数据流（StateFlow / UiIntent） |
| 依赖注入 | Hilt（KSP） |
| 本地存储 | Room 2.6.1 + SQLCipher 4.5.5 + DataStore + EncryptedFile |
| 异步 | Coroutines + Flow + WorkManager |
| OCR | ML Kit Text Recognition（离线） |
| 图表 | MPAndroidChart |
| 导入导出 | kotlin-csv + Apache POI 5.2.5 |
| 小组件 | Glance 1.1.0 |
| 构建 | AGP 8.5.2 / Kotlin 2.0.21 / KSP 2.0.21-1.0.28 / Gradle 8.9 / JDK 17 |

## 本地构建

需要 JDK 17、Android SDK Platform 35（compileSdk / targetSdk 均为 35）、Build-Tools 35.x。

```bash
# local.properties 中指定 SDK 路径
echo "sdk.dir=/path/to/android-sdk" > local.properties

./gradlew assembleDebug
./gradlew testDebugUnitTest

# 产物：app/build/outputs/apk/debug/app-debug.apk
```

详见 [BUILD.md](BUILD.md)（环境前置、三条出包路径、首次构建风险清单）。

## 项目结构

```
app/src/main/java/com/azu/bookkeeper/
├── core/            常量、金额/时间工具、Result、扩展函数、DI Module
├── data/local/      Room 数据库 / DAO / EncryptedFile / CSV·XLSX 数据源
├── data/repository/ Repository 实现（含余额事务）
├── domain/model/    领域模型（Bill / Account / CaptureEvent 等）
├── domain/repository/  Repository 契约
├── domain/usecase/  业务用例（按功能模块分包）
├── automation/      capture / classify / dedup / ocr / permission / receiver / service / verify
├── ui/              Compose 页面（home / bill / stats / importflow / settings 等）
└── worker/          WorkManager 后台任务（@HiltWorker）
```

设计文档在 [`docs/`](docs/)：整合 PRD、系统架构、增量 PRD、QA 测试报告，以及 8 轮 CI 构建失败到成功的完整日志（保存在 `docs/ci-logs/`）。

## 自动化说明

本项目通过 GitHub Actions 云端构建：每次 push 到 `main` 触发构建，成功后自动创建 / 更新 GitHub Release 并附加 debug APK。小程序原型见 `deliverables/记账App-交互原型.html`（纯前端预览，非 APK）。

## 测试

96 个 JUnit 用例覆盖金额解析、时间解析、余额计算、置信度、分类规则、短信解析、去重、字段抽取、导入清洗等模块。在 CI 的 `Run unit tests` 步骤中执行（配置为非阻塞）。

## 许可与用途

个人自用项目，未上架任何应用市场。自动化相关能力仅在用户本机授权范围内工作，不收集或上传任何个人数据。

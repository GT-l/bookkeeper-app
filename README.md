# 少花点儿 · Android 记账 App

**纯本地记账应用。** 手动记 / 自动识别 / 一键补记三条路都能进账，数据用 SQLCipher 加密后只存在这台手机上；只有你在关于页主动点「检查更新」时才会联网读取 GitHub Releases。

当前版本 **v1.3.1**（versionCode 8，Debug APK，Android 8.0+）

---

## 这一版（v1.3.1）改了什么

本轮全部来自真机反馈，重点是「少打扰 + 结果一致 + 能自愈」。

- **高置信度静默入账**：金额 + 有效商户都识别到就直接记账，弹一张 3 秒自消的底部轻卡片「✓ 记账成功」；只有把握低时才弹轻确认。所有反馈都不拉起软件界面、不落常驻通知栏。
- **修好「只识别到『支付』两个字的回归」**：v1.3.0 遍历后 `child.recycle()`，而多个根节点共享同一子树，第一次回收后后面的遍历只剩根节点标题。现在统一走 `ScreenTextCollector`：整页优先、按行去重、不回收共享节点；日志新增 `len=` / `preview=` 便于定位。
- **两条链路结果一致**：摇一摇与自动识屏现在共用同一采集/去重/标签补齐入口；自动链路也用 `PaymentTextAnalyzer` 抽商户与购买内容，备注统一写成「商户 · 购买内容」，去重键随之统一，跨链路重复也能识别。
- **打开同一笔付款记录也能提示重复**：账单详情页不再被直接丢弃，会提交去重；确定重复弹「已记过」轻提示，疑似重复弹底部轻卡片。
- **抖音订单页修复**：支持只有「实付 ¥xx + 订单详情」、没有通用成功词的页面，并补齐金额/商户标签。
- **无障碍守护**：IMPORTANCE_MIN 静默前台通知 + 每 60 秒检查系统开关；已授予 `WRITE_SECURE_SETTINGS` 时自动写回恢复，未授予时限频弹底部轻提示。ADB 脚本新增 `grant` / `restore`。
- **关于页自动更新**：检查 GitHub Releases → 自动下载 APK → 拉起系统安装器（系统不允许无声安装，最后一步需点「安装」；同签名覆盖不丢账单）。

### 上一版（v1.2.3 / v1.2.2）已有

支付页面识别、微信空节点有界重读与文案扩展、通知多字段补齐、底部轻确认卡片；侧载受限设置判因、ADB 一键解锁、无障碍最小权限声明；诊断自动判因与完整日志时间线、vivo / OriginOS 引导可见性。

### 更早（v1.2.0 / v1.1.0）仍在

日历 / 热力图 / 同比环比、年度预算、退款与报销、批量记账、债务与分期、信用卡提醒、数据库 v1→v2 幂等迁移；「我的」tab、设置页瘦身、存储排查、识屏三档分流与购买内容识别、重复账单确认、摇一摇加固。

## 能做什么

- **手动记账**：支出、收入、转账；金额按「分」存储（整数运算，不碰浮点）；按日分组、搜索筛选、周期账单、多账本、分类两级、标签、资产账户与余额联动。
- **自动识别账单**（全部本地处理，不联网）：
  - 无障碍节点读屏（主链路，不怕防截屏）→ 截图 OCR 兜底；
  - 支付通知监听、银行扣款短信；
  - 识别不清时弹小卡片确认或转完整表单，绝不悄悄记错账。
- **一键记一笔**：桌面快捷方式、桌面小组件、常驻通知按钮、悬浮球（单击记账 / 长按识屏 / 双击收起）、摇一摇（独立前台服务，可选「打开记一笔」或「直接识屏」）。
- **常驻通知**：今日支出、本月支出、预算剩余、最近一笔、今日笔数；样式与显示项可自选，也能直接控制悬浮球。
- **预算与报表**：总预算未设置时按分类预算求和；预算页有日均可用与超支提醒；7 个统计视图（概览 / 支出 / 收入 / 趋势 / 分类 / 账户 / 标签），图表点击可下钻到账单。
- **数据进出**：CSV / XLSX 导入向导、CSV / Excel 导出、本地加密备份与恢复。
- **安全与稳定**：崩溃探针与全局捕获（重进 App 可看到完整堆栈并一键复制）、异常熔断进入安全模式、悬浮层独立进程、识屏隔离进程、版本升级自动清理历史临时文件。

标准 Android 沙盒内做不到的（全局交易事件、被强杀后无条件自启）没有实现；识别不到时用「记一笔」补记即可。

## 下载安装

从 [Releases](../../releases) 下载最新 `bookkeeper-debug.apk`（Android 8.0+ / API 26+）：

```bash
adb install -r bookkeeper-debug.apk
```

每次 push 到 `main`，GitHub Actions 会自动构建、跑单元测试，并把 APK 更新到对应的 Release。

> 签名说明：v1.2.1 起 CI 使用仓库内固定 debug 密钥（`keystore/bookkeeper-debug.p12`，仅用于调试，不能上架）。若你还停在更早的版本（每次 CI 重新生成 debug 签名），升级时系统仍会报签名不一致，**需要先卸载旧包一次**；从 v1.2.1 起的所有后续版本（含 v1.3.1）可直接覆盖安装。

## 技术栈

Kotlin + Jetpack Compose（Material3 / Navigation）、MVVM + UseCase + Repository、Hilt（KSP）、Room + SQLCipher、DataStore、EncryptedFile、Coroutines / Flow、WorkManager、ML Kit 离线 OCR、Glance 小组件、MPAndroidChart、Apache POI。

构建环境：JDK 17、Android SDK Platform 35、Gradle 8.9。

v1.3.1 的测试记录见 [`docs/testing/v1.3.1/`](docs/testing/v1.3.1/)；静态一致性测试可执行 `python3 scripts/verify_v1_3_1.py`。ADB 一键解除受限设置 / 自动恢复见 [`scripts/android_unlock_accessibility.sh`](scripts/android_unlock_accessibility.sh)（`unlock` / `grant` / `restore`）。历史记录见 [`docs/testing/v1.3.0/`](docs/testing/v1.3.0/)、[`docs/testing/v1.2.3/`](docs/testing/v1.2.3/)、[`docs/testing/v1.2.2/`](docs/testing/v1.2.2/)、[`docs/testing/v1.2.1/`](docs/testing/v1.2.1/)、[`docs/testing/v1.2.0/`](docs/testing/v1.2.0/) 与 [`docs/testing/v1.1.0/`](docs/testing/v1.1.0/)。

```bash
./gradlew assembleDebug        # 构建
./gradlew testDebugUnitTest    # 单元测试
```

## 项目结构

```
app/src/main/java/com/azu/bookkeeper/
├── core/          常量、工具、安全正则、崩溃探针、遗留清理、DI
├── data/          Room / DAO / DataStore / 加密文件 / Repository 实现
├── domain/        模型、Repository 契约、UseCase
├── automation/    识屏管线、权限引导、无障碍服务、前台服务、OCR
├── ui/            Compose 页面（home / profile / bill / stats / budget / settings …）
└── worker/        WorkManager 定时任务
```

单元测试覆盖金额与时间解析、余额事务、识别置信度、分类匹配、短信规则、去重、导入清洗、备注合成，以及统计聚合 / 同比环比 / 业务模型边界；CI 每次全量执行。

## 数据与隐私

- 账本、账单、账户全部存在本机 `bookkeeper.db`（SQLCipher 加密），密钥由 Android Keystore 保管的 EncryptedSharedPreferences 托管。
- 没有账号体系、没有服务端、不采集任何统计信息。
- 卸载 App 会连同数据一起删除；重要数据请先用「备份与恢复」导出。
- 权限用途：无障碍用于读取支付页文字（仅在你主动摇一摇或开启自动记账时工作）、通知使用权与短信用于识别支付通知/银行短信、悬浮窗仅用于悬浮球。全部可在系统设置里随时关闭。

## 已知限制

- Debug 签名，仅供自己安装使用，未上架任何应用市场。
- 部分支付页面会拦截截屏（FLAG_SECURE），此时走无障碍读屏；两者都拿不到时会直接开表单让你手填。
- 各厂商 ROM 对后台与无障碍服务的限制不同，若自动记账失效，先看 App 内「识屏自检」页的逐项检查结果。

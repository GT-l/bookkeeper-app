# 少花点儿 · Android 记账 App

**纯本地、不联网的个人记账应用。** 手动记 / 自动识别 / 一键补记三条路都能进账，数据用 SQLCipher 加密后只存在这台手机上。

当前版本 **v1.2.2**（versionCode 5，Debug APK，Android 8.0+）

---

## 这一版（v1.2.2）改了什么

依据 vivo V2505A / Android 17 真机诊断日志（服务连上后仅存活 1.0 秒 / 27.3 秒，系统开关已变「关」）修正无障碍排查链路：

- **诊断自动判因**：确认这是「系统主动撤销无障碍授权」，不是 App 崩溃、ANR 或省电杀进程——后台被杀时开关通常还亮着，而这里 `Settings.Secure` 已是「关」。弹窗与复制的 log 直接给出结论。
- **修正误导性日志**：`onUnbind` / `onDestroy` 原本写成「被系统销毁 / 进程退出」，实际是解绑后的正常销毁；现改为准确措辞，并记录存活时长、pid、进程已运行时长、累计连接 / 被撤销次数。
- **连接事件强制落盘**：v1.2.1 只记录断开，复制回来的 log 全是 ERROR；现在连上也写一条 INFO，能还原完整时间线。
- **修复 vivo 看不到保活卡片**：判断原来只覆盖 OPPO 系，vivo / iQOO（OriginOS）用户完全看不到；现在按厂商显示标题并覆盖两家。
- **补两条系统侧关键设置**：Android 13+ 侧载 APK 需先「允许受限设置」（右上角 ⋮）；vivo 官方对「无障碍自动关闭」要求「后台高耗电 + 一键加速白名单」。诊断报告里也带上安装来源。
- **关于页**：补上 v1.2.2 更新介绍。

### 上一版（v1.2.1）已有

无障碍健康检查弹窗与一键复制 log、OrangeOS 稳定运行设置、固定调试签名（覆盖安装）、关于页 v1.2.0 介绍。

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

> 签名说明：v1.2.1 起 CI 使用仓库内固定 debug 密钥（`keystore/bookkeeper-debug.p12`，仅用于调试，不能上架）。若你还停在更早的版本（每次 CI 重新生成 debug 签名），升级时系统仍会报签名不一致，**需要先卸载旧包一次**；从 v1.2.1 升到 v1.2.2 可直接覆盖安装。

## 技术栈

Kotlin + Jetpack Compose（Material3 / Navigation）、MVVM + UseCase + Repository、Hilt（KSP）、Room + SQLCipher、DataStore、EncryptedFile、Coroutines / Flow、WorkManager、ML Kit 离线 OCR、Glance 小组件、MPAndroidChart、Apache POI。

构建环境：JDK 17、Android SDK Platform 35、Gradle 8.9。

v1.2.2 的测试记录见 [`docs/testing/v1.2.2/`](docs/testing/v1.2.2/)；静态一致性测试可执行 `python3 scripts/verify_v1_2_2.py`。历史记录见 [`docs/testing/v1.2.1/`](docs/testing/v1.2.1/)、[`docs/testing/v1.2.0/`](docs/testing/v1.2.0/) 与 [`docs/testing/v1.1.0/`](docs/testing/v1.1.0/)。

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

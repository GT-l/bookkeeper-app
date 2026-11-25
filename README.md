# 少花点儿 · Android 记账 App

纯本地、无联网的个人记账应用：手动记 / 自动识别 / 一键补记，数据用 SQLCipher 加密存在手机里。

## 能做什么

- **手动记账**：支出、收入、转账；金额按「分」存储；按日分组、搜索、筛选、周期账单、多账本、分类标签、资产账户与余额联动。
- **自动识别账单**（本地处理，不联网）：支付完成页读屏、支付通知、银行扣款短信、截屏 OCR 四条通道；识别不清时弹窗让你确认或改写，不会悄悄记错账。
- **一键记一笔**：长按桌面图标、桌面小组件、常驻通知按钮、悬浮球（单击记账 / 长按识屏 / 双击收起）、系统手势（摇一摇走独立前台服务；指纹四向滑动需开启读屏服务）。
- **常驻通知**：显示今日支出、本月支出、预算剩余、最近一笔、今日笔数；样式（简洁 / 明细 / 大字）和显示项都能在「设置 → 通知栏显示内容」里自己选，还能直接显示 / 隐藏悬浮球。
- **预算与报表**：总预算未设置时自动按分类预算求和；7 个统计视图；CSV / XLSX 导入向导、CSV / Excel 导出、本地备份恢复。
- **安全与稳定**：崩溃熔断（异常时自动进入安全模式，保证 App 能打开）、悬浮层独立进程、版本升级时自动清理历史临时文件。

标准 Android 沙盒内做不到的（如全局交易事件、强杀后无条件自启）没有实现；识别不到时用「记一笔」补记即可。

## 下载安装

从 [Releases](../../releases) 下载最新 `bookkeeper-debug.apk`（Android 8.0+ / API 26+）：

```bash
adb install -r bookkeeper-debug.apk
```

每次 push 到 `main`，GitHub Actions 会构建并更新同一个 Release 的 APK；测试报告同时随构建上传。

## 技术栈

Kotlin + Jetpack Compose（Material3 / Navigation）、MVVM + UseCase + Repository、Hilt（KSP）、Room + SQLCipher、DataStore、EncryptedFile、Coroutines / Flow、WorkManager、ML Kit 离线 OCR、Glance 小组件、MPAndroidChart、Apache POI。

构建环境：JDK 17、Android SDK Platform 35、Gradle 8.9（`./gradlew assembleDebug` / `./gradlew testDebugUnitTest`）。

## 项目结构

```
app/src/main/java/com/azu/bookkeeper/
├── core/          常量、工具、崩溃熔断、遗留清理、DI
├── data/          Room / DAO / DataStore / 加密文件 / Repository 实现
├── domain/        模型、Repository 契约、UseCase
├── automation/    识别管线、权限引导、无障碍与前台服务
├── ui/            Compose 页面、快捷入口、设置页
└── worker/        WorkManager 定时任务
```

96 个单元测试覆盖金额 / 时间解析、余额、置信度、分类、短信、去重、导入清洗等模块。设计文档在 `docs/`。

个人自用项目，未上架应用市场；不收集、不上传任何个人数据。

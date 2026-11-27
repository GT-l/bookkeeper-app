# v1.3.1 测试报告

## 一、用户反馈与根因

| 反馈 | 根因（代码级） | 修复 |
| --- | --- | --- |
| 每次都问是否记账 | 高置信度自动入账后只有 `Timber.d`，无任何 UI 反馈；低置信度走 `VerificationController.requestVerify`，overlay 失败时同时发前台 Dialog + 常驻通知 | 高分入账后 `notifyRecorded` → 3 秒自消底部成功卡片；低分只走轻卡片，不再默认弹 Dialog / 发通知 |
| 只识别到「支付」 | `AccessibilityCaptureParser.collect()` 遍历后 `child.recycle()`，而自动识屏对 4 个共享同一子树的 root 依次遍历；第一个回收后，后续只剩根节点标题行。多根字符串直接相加又让 6000 字上限被重复行吃满 | 新建 `ScreenTextCollector`：整页优先、按行去重、不回收共享节点；稀疏文本触发有界重读；日志带 `len=`/`preview=` |
| 摇一摇与自动识屏不一致 | 采集器不同（深度 24 vs 50、是否 recycle）、抽取器不同（自动只用 `FieldExtractor`、摇一摇还用 `PaymentTextAnalyzer`）、备注不同（自动只写 merchant） | 两条链路共用 `ScreenTextCollector` + `PaymentTextAnalyzer`；自动链路备注也写成 `composeRemark(商户, 购买内容)`，去重键随之统一 |
| 打开同一付款记录不提示重复 | `PaymentScreen.DETAIL` 在 `dispatchEvent` 直接 `return`，从未产生捕获事件；`findRecentDuplicate` 还要求商户名 ≥2 字 | DETAIL 提交捕获走去重；确定重复弹「已记过」轻提示，疑似重复弹底部轻卡片；商户抽取修好后不再被长度门槛挡住 |
| 重复变成通知栏 | `OverlayVerifyView` 只支持 `TYPE_APPLICATION_OVERLAY`，无悬浮窗权限即返回 false，调用方降级发通知；`requestVerify` 在 overlay 失败时还会 emit 前台 Dialog + showVerify | 优先 `TYPE_ACCESSIBILITY_OVERLAY`（无障碍窗口，不需要悬浮窗权限）；addView 切主线程；失败仅发 8 秒自消横幅 |
| 抖音订单页识别失败 | 成功词表只有通用支付词；抖音订单页只有「实付 ¥xx + 订单详情」；金额/商户无标签行 | 新增抖音订单页类名 token 与「实付 + 订单上下文」判定；补 `金额：`/`商户：` 标签行；收银台类名拦截避免误判 |
| 长时间运行后无障碍被系统关闭 | 真机日志证明 pid 不变、进程没死，是系统撤销授权；应用无签名级权限无法写回设置 | 静默前台守护服务每 60 秒检查；有 `WRITE_SECURE_SETTINGS` 时自动写回恢复；无权限时限频轻卡片提示；ADB 脚本新增 `grant`/`restore` |
| 用户想从 App 内检查更新 | 原先无网络权限、无更新入口 | 关于页新增检查更新：GitHub Releases → 下载 APK → FileProvider 拉起系统安装器（系统限制最后一步需点「安装」） |

## 二、修改清单（本轮）

### 统一与采集
- 新增 `automation/capture/ScreenTextCollector.kt`：节点采集、按行去重、稀疏判定、按包名补标签、诊断预览。
- `AccessibilityCaptureParser` 委托统一采集器，移除 `child.recycle()`。
- `PaymentAccessibilityService`：`readScreenText` 整页优先 + 稀疏补根；DETAIL 提交；所有分支写 `SCREEN` 日志；`readScreenSnapshot` 与摇一摇共用采集器。
- `PaymentScreenRecognizer`：抖音订单页识别与标签补齐、微信账单详情补标签、噪声词扩容、DETAIL 判定提前到 ENTRY 之前。

### 轻打扰反馈
- `OverlayVerifyView` 重写：成功/纯提示/确认三种卡片、`TYPE_ACCESSIBILITY_OVERLAY` 优先、主线程操作、自动消失。
- `VerificationController`：新增 `notifyRecorded` / `notifyDuplicated`；`requestVerify` 不再默认发通知/Dialog，仅 8 秒横幅兜底。
- `VerificationNotifier`：新增 `showTransientVerify`。
- `CaptureCoordinator`：统一抽取（analyzer + composeRemark）、高分轻提示、重复轻提示。
- `ShakeQuickAddService` / `LightweightCapture` / `CaptureProgressNotifier`：去掉 Toast 与常驻结果通知，改轻卡片 + 清除进度通知。

### 无障碍守护
- 新增 `automation/service/A11yWatchdogService.kt`（静默前台通知、60 秒检查、10 分钟限频轻提示）。
- `AccessibilityHealth.tryRestore()`：`WRITE_SECURE_SETTINGS` 已授予时写回系统列表。
- `AndroidManifest.xml` 声明守护服务与权限；`BookkeeperApp` / `BootReceiver` 启动守护。
- `scripts/android_unlock_accessibility.sh` 新增 `grant` / `restore`。

### 自动更新
- 新增 `core/update/GithubUpdateChecker.kt`、`core/update/UpdateInstaller.kt`、`ui/settings/AboutViewModel.kt`。
- `AboutScreen` 新增更新卡片与 v1.3.1 更新介绍；`AndroidManifest` 加 INTERNET / REQUEST_INSTALL_PACKAGES；`file_paths.xml` 开放 updates 目录。

## 三、自动验证

| 项目 | 结果 |
| --- | --- |
| 静态一致性 | `python3 scripts/verify_v1_3_1.py`：**156/156 通过** |
| ADB 脚本语法 | `bash -n scripts/android_unlock_accessibility.sh` 通过 |
| JVM 单测 | **T168-F0-E0-S0**（168 项全过，含本轮 12 项新增） |
| Kotlin 编译 | `assembleDebug` 成功，`bookkeeper-debug.apk` 约 92.5 MB |

## 四、CI 结论

- 最终 run **`46032727986`**（commit `f538b0d`）：`completed success`，静态测试 / 编译 / 单测 / Release 全部成功。
- 上一轮 run `46031548772`（`2a71709`）单测失败 1 项：`抖音收银台文本不被补标签` —— 标签补齐触发条件只看「实付」，收银台也含该词。已改为「订单详情 或 实付 + 订单上下文」才补齐，修复后全绿。
- 单测 artifact：`unit-test-reports-T168-F0-E0-S0`。
- Release：[`v1.3.1-debug`](../../releases/tag/v1.3.1-debug)，附件 `bookkeeper-debug.apk`（92,527,156 字节）。
- 从 v1.3.0 可直接覆盖安装（固定调试签名，账单不丢）。

## 五、边界与待确认

- 系统不允许普通 App 静默安装，自动更新最后一步需用户点系统安装器「安装」。
- 未授予 `WRITE_SECURE_SETTINGS` 时守护只能检测与轻提醒，不能替用户改系统开关。
- 页面识别是启发式：漏判时按 `SCREEN` 日志里的 `cls=` / `len=` / `preview=` 补规则。
- 真机用例 TC-01~TC-19 需用户按 `TEST_CASES.md` 执行；未执行项不记通过。

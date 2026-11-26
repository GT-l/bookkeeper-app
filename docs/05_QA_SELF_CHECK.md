# 程序自检清单（主流 ROM 兼容矩阵）

> 第 N 轮：OrangeOS 特调 + 已知问题检索 + 全 ROM 自检。
> 每一项都标注了对应的代码防护位置，CI 构建的 debug APK 可直接按此清单真机自测。

## 一、通知权限（Android 13+ POST_NOTIFICATIONS）——本轮重点

| # | 用例 | 预期 | 代码防护 |
|---|------|------|----------|
| N-1 | 全新安装 → 首次开「启用自动记账」→ 弹通知权限 → 允许 | 常驻通知立刻出现，不闪退 | `NotifyGuard` 全量接管 notify；`onNotificationPermissionResult` → `setEnabled(true)` |
| N-2 | 同上 → 拒绝 | 不闪退；提示「自动记账仍可运行」；开关仍打开 | `AutomationSettingsViewModel.onNotificationPermissionResult` |
| N-3 | 通知权限关闭状态下，所有通知路径（常驻刷新 / 摇一摇 / 手势兜底 / 校对 / 预算提醒） | 静默跳过，主线程不阻塞（部分 OEM ROM 阻塞主线程导致 ANR 的已知问题） | `core/NotifyGuard.kt`：先 `areNotificationsEnabled()` 再投递 + runCatching |
| N-4 | 授权通知权限后常驻通知不出现（Android 13 授权前投递的通知会被丢弃且不补发） | 回到 App 前台后通知补发 | `MainActivity.onResume` → `ensureRunning`；`onNotificationPermissionResult` → 补发 |
| N-5 | 权限弹窗刚关闭的瞬间开总开关（OrangeOS/ColorOS 误判后台） | 服务照常启动 | `setEnabled` 内 `!requested` → 延迟 500ms 重试一次 |
| N-6 | startForeground 失败 → 状态落盘失败 | 只记日志，不崩进程 | `AutoCaptureForegroundService.onStartCommand` 失败分支 `runCatching` 包裹 |

## 二、OrangeOS / ColorOS 专项（N1）

| # | 用例 | 预期 | 代码防护 |
|---|------|------|----------|
| O-1 | OPPO/一加/realme/OrangeOS 设备进入自动化设置页 | 出现「OrangeOS / ColorOS 用户请确认」引导卡（自启动 / 通知 / 电池三项） | `core/RomCompat.needsOppoGuidance()` + 设置页引导卡 |
| O-2 | 开启无障碍 → 返回再进入被自动取消（Orange 7 实测） | 服务不崩（无字段注入 + 全链路 runCatching）；设置页出现「服务未连接」警告与重开指引 | 第 M 轮：`AccessibilityDepsEntryPoint` + 心跳 `accessibilityServiceEverConnected()` |
| O-3 | 摇一摇 → 后台拉起表单被拦 | 全屏意图通知兜底（独立高重要级渠道 `shake_gesture_high`） | `ShakeQuickAddService.showFullScreenFallback` |
| O-4 | 摇一摇常驻通知有时响铃有时静音 | 摇一摇改用独立低重要级渠道 `shake_ongoing`，不再与自动化服务（LOW）/校对（DEFAULT）抢渠道 | 本轮修复：渠道重要级冲突 |

## 三、主流 ROM 通用自检矩阵（N3）

| ROM | 重点用例 | 关键检查点 |
|-----|----------|-----------|
| 原生 Android（Pixel，API 33-35） | POST_NOTIFICATIONS 流程 N-1~N-4；API 34+ FGS 必须带 specialUse 类型 | manifest `foregroundServiceType` 与 `startForeground` 类型一致 |
| OrangeOS / ColorOS（OPPO 系） | O-1~O-4 + N-5；自启动 / 电池白名单 | `RomCompat` 引导卡 |
| HyperOS（小米） | 通知权限 + 「后台弹出界面」权限；FGS 被杀后回前台恢复 | `MainActivity.onResume` 恢复链 |
| MagicOS（荣耀）/ EMUI（华为） | 后台启动限制：常驻通知点击「记一笔」可用（走 PendingIntent，不经后台 startActivity） | `QuickCaptureLauncher.pendingIntent` |
| HarmonyOS / OriginOS（vivo） | 摇一摇 / 指纹手势触发后 Activity 拉起失败时通知兜底 | 全屏意图 + 通知守卫 |
| 各 ROM 通用 | 悬浮球崩溃只崩 `:overlay` 进程，主 App 可正常打开 | `android:process=":overlay"` + `SafeMode` 熔断 |

## 四、已知问题参考来源（N2 检索）

1. Android 14 强制 FGS 声明类型，缺失抛 `MissingForegroundServiceTypeException`（manifest 两个服务均已声明 specialUse + subtype property）。
2. Android 12+ 后台启动 FGS 抛 `ForegroundServiceStartNotAllowedException`（N-5 延迟重试 + onResume 补拉）。
3. Android 13+ 未授权 POST_NOTIFICATIONS 时 `notify()` 抛 `SecurityException`，部分 OEM ROM 阻塞主线程致 ANR（`NotifyGuard` 先查权限）。
4. 通知渠道重要级以「先建者为准」且系统永久保留，多组件抢建同一渠道导致表现不稳定（本轮拆分 `shake_ongoing` 渠道）。
5. ColorOS「自启动管理 / 电池优化 / 通知管理」三项系统开关是国产 ROM 功能失效主因（O-1 引导卡）。

## 五、回归底线

- 任何通知层故障（权限被关 / 渠道被用户手动关闭 / ROM 拦截）都**不允许**影响：记账主流程、无障碍服务存活、悬浮球主进程。
- 无障碍服务内任何异常都不允许外抛（ROM 会禁用服务且不自动恢复）。

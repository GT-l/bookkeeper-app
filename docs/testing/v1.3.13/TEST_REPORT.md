# v1.3.13 测试报告

## 一、结论

| 项目 | 结果 |
| --- | --- |
| 静态一致性 `scripts/verify_v1_3_13.py` | 待回填 |
| JVM 单元测试 | 待回填 |
| CI 构建 | 待回填 |
| Release | 待回填 |

## 二、变更文件

| 文件 | 改动 |
| --- | --- |
| `core/AccessibilityEventStats.kt` | 新增：事件到达统计（总数 / 支付应用数 / 最后包名与时间），按天重置、5 秒节流落盘 |
| `automation/service/PaymentAccessibilityService.kt` | `dispatchEvent` 第一行计数，位于任何过滤之前 |
| `ui/settings/CaptureDiagnosticsViewModel.kt` | 新增「无障碍事件到达」检查项，放在通道检查之前 |
| `ui/settings/StorageAuditViewModel.kt` | 新增 `Kind.APK`，扫描 `files/updates`、`cache/incoming-apk`、`cache/*.apk`；识屏日志单独标注 |
| `core/CaptureLog.kt` | 默认开启（原默认关闭） |
| `MainActivity.kt` | `onResume` 调用 `A11yWatchdogService.checkNow`，回前台即体检/恢复 |
| `ui/settings/AboutScreen.kt` | 更新介绍改为 v1.3.13 |
| `app/build.gradle.kts` | versionCode 20 / versionName 1.3.13 |
| `.github/workflows/android.yml` | 静态脚本与 Release tag 切到 v1.3.13 |

## 三、设计说明

### 为什么计数必须放在过滤之前

`dispatchEvent` 的原有顺序是「取包名 → 白名单过滤 → 读页面 → 分类」。
如果把计数放在过滤之后，一旦包名判定有问题，统计也会一起被过滤掉，用户看到的仍然是 0 —— 诊断本身失真。
所以计数是 `dispatchEvent` 的第一条语句（除 null 判断外）。

### 为什么不用数据库存计数

事件在主线程回调，微信页面事件非常密集。写数据库会引入明显卡顿。
这里用内存计数 + 5 秒节流 `SharedPreferences.apply()`，进程重启后从 prefs 恢复当日基线，成本可忽略。

## 四、风险

- 统计只反映「事件是否到达」，不判断内容，隐私上只存包名与条数，不含页面文本。
- 日志默认开启后文件仍受 256KB 上限与 600 行裁剪约束，不会无限增长。

## 五、下一步（如果 A-01 仍为 0 条）

按 `PLAN.md` 第五节执行：重开无障碍 → 检查 realme 自启动/省电限制 → 跑 ADB restore 脚本。

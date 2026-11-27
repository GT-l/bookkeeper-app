# v1.2.3 测试用例 · 侧载受限设置与最小权限声明

> 测试对象：v1.2.3（versionCode 6）。依据 2026-11-27 第二轮真机诊断日志（vivo V2505A / Android 17 API 37）编写。
> 本机无 JDK / Android SDK / 模拟器；编译与单测以 GitHub Actions 为准，真机项需用户执行。

## 一、第二轮日志的决定性证据

诊断头部：`系统开关：关`、`服务曾连接：true`、`最后连接 11:50:49` / `最后断开 11:50:52`、`本次服务存活 3.3 秒`、`累计连接 / 被撤销：6 次 / 4 次`、`连接时 pid 23522`、`进程已运行 3.4 分钟`、`安装来源 com.android.packageinstaller`、`Android 17 (API 37)`。

| 时间 | 事件 | 存活 |
| --- | --- | --- |
| 11:47:27.256 | `INFO 服务已连接 #1 pid=23522 进程已运行=758ms` | — |
| 11:48:34.591 | `ERROR 被解绑（系统撤销授权）存活=67335ms pid=23522` | 67.3 秒 |
| 11:48:39.828 | `ERROR 无障碍异常 kind=closed … revokeCount=1 source=com.android.packageinstaller` | — |
| 11:49:09.643 | `INFO 服务已连接 #2 pid=23522 进程已运行=103146ms` | — |
| 11:49:10.308 | `ERROR 被解绑 存活=665ms pid=23522` | 0.7 秒 |
| 11:49:39.192 | `INFO 服务已连接 #3 pid=23522` | — |
| 11:49:40.588 | `ERROR 被解绑 存活=1396ms pid=23522` | 1.4 秒 |
| 11:49:42.115 | `ERROR 无障碍异常 revokeCount=2` | — |
| 11:50:20.900 | `INFO 服务已连接 #4 pid=23522` | — |
| 11:50:22.379 | `ERROR 被解绑 存活=1479ms pid=23522` | 1.5 秒 |
| 11:50:22.992 | `ERROR 无障碍异常 revokeCount=3` | — |
| 11:50:49 → 11:50:52 | 报告中的最后一次（第 5/6 次连接） | 3.3 秒 |

**全部连接与断开的 pid 都是 23522，进程连续存活 3.4 分钟。**

## 二、判因（推翻 v1.2.2 的排查方向）

| 证据 | 结论 |
| --- | --- |
| pid 全程不变、进程已运行 3.4 分钟 | **进程从未被杀**。后台保活、自启动、电池白名单、一键加速、最近任务锁定对本案完全无效 —— v1.2.2 把这些放在引导第一位是错的 |
| 代码库无 `disableSelf()`、无写 `Settings.Secure` 的调用 | 开关不是 App 自己关的 |
| `系统开关：关` + `wasEnabled=true` | 系统从 `ENABLED_ACCESSIBILITY_SERVICES` 移除了本服务 |
| 服务连上 0.7~67 秒即被撤销，无崩溃 / ANR | 属于「开启后立刻被系统复核重置」，不是长时间后台被清理 |
| `安装来源 com.android.packageinstaller` | **手动装 APK 的侧载**。Android 13+「受限设置」正是针对侧载应用的无障碍 / 通知监听授权 |
| `Android 17 (API 37)` | 该版本新增「高级保护模式」，开启时会强制撤销非辅助类应用的无障碍权限 |

高概率成因排序：① 侧载「受限设置」未解除；② Android 17 高级保护模式开启；③ 厂商对侧载 + 声明监听支付/银行窗口的服务做支付风控复核。

> 直接导致上一轮白忙的代码缺陷：`diagnose()` 只把含「未知 / 侧载」字样的来源当成侧载，`com.android.packageinstaller` 被漏判，结论里于是一句「受限设置」都没提。

## 三、本轮改动对照

| 编号 | 改动 | 目的 | 静态断言 |
| --- | --- | --- | --- |
| D-01 | `isSideloaded()` 识别 `packageinstaller` / `com.android.shell` / adb | 不再漏判侧载 | T-SIDE-01 |
| D-02 | `diagnose()` 用 `pidStable` 直接排除保活方向，把受限设置 / 高级保护列为首要嫌疑 | 纠正排查方向 | T-SIDE-03 / 04 |
| D-03 | 新增 `KEY_PID_CHANGED` + `accessibilityPidStable()`，报告输出「进程 pid 是否稳定」 | 把决定性证据自动化 | T-SIDE-04 |
| D-04 | `adbUnlockText()` + `scripts/android_unlock_accessibility.sh`（status/unlock/check） | 给确定性、无需 root 的一次性授权解法 | T-SIDE-02 / 07 |
| D-05 | 稳定运行设置重排：高级保护 → 受限设置 → ADB → 会话安装器 → 保活（降级）→ 系统级锁定 | 引导顺序与根因一致 | T-SIDE-03 |
| D-06 | 移除 `android:packageNames` | 不再向系统明示「监听微信/支付宝/银行」，代码里本就用 `Constants.PAYMENT_PACKAGES` 过滤，零功能损失 | T-SIDE-05 |
| D-07 | 移除 `android:canTakeScreenshot` | 去掉高风险能力声明；该通道默认关闭，兜底有媒体投影 / OCR | T-SIDE-05 |
| D-08 | 自检页 `capabilityRemovedByDesign` | `canCaptureScreen()=false` 不再误报成故障 | T-SIDE-08 |
| D-09 | 关于页 / README / Release 升到 v1.2.3 | 版本一致 | T-VER-01..03 / T-A11Y-04 |

`python3 scripts/verify_v1_2_3.py` → **119 项全部通过**（含全部 Kotlin 文件括号配平）；`bash -n scripts/android_unlock_accessibility.sh` 通过。

## 四、真机测试用例（需用户在 vivo V2505A 执行）

| 编号 | 步骤 | 期望结果 |
| --- | --- | --- |
| TC-01 | 设置 → 安全 → 高级保护模式（或顶栏搜「高级保护」）：确认关闭 | 该模式为关；若原本是开，关闭后直接试 TC-05 |
| TC-02 | 设置 → 应用管理 → 少花点儿 → 右上角 ⋮ → 允许受限设置，验证锁屏密码 | 菜单可见并能开启；vivo 隐藏入口则跳 TC-03 |
| TC-03 | 电脑连手机（或无线调试），执行 `bash scripts/android_unlock_accessibility.sh unlock`，再 `check` | 输出 `PASS 受限设置已解除` 与 `PASS 无障碍服务已在列表` |
| TC-04 | 覆盖安装 v1.2.3（不卸载），关于页显示 v1.2.3 | 安装成功、账单数据保留 |
| TC-05 | 设置 → 快捷与辅助 → 无障碍 → 已下载的服务 → 少花点儿，打开开关，静置 5 分钟 | 开关保持「开」，自动化设置显示「服务状态：运行中」 |
| TC-06 | 若仍秒关，用 SAI / 会话安装器覆盖安装同一 APK，重复 TC-05 | 安装来源不再是手动装 APK；部分 ROM 会因此放行 |
| TC-07 | 若仍秒关，用 Shevery / Shizuku 的「无障碍服务锁定」锁定本服务，重复 TC-05 | 系统不再自动撤销授权（系统级能力，App 无法替代） |
| TC-08 | 打开自动化设置 → 复制 log | 报告含「进程 pid 是否稳定：是」「安装来源：com.android.packageinstaller（侧载）」「=== 诊断结论 ===」且结论明确写「不要再往保活方向排查」 |
| TC-09 | 打开识屏自检页 | 「无障碍截屏通道」显示「已按最小权限关闭（预期行为）」而非红色故障；「无障碍服务开关」为正常 |
| TC-10 | 触发一次真实支付识屏 | 读节点主链路仍能识别金额与商户（验证去掉 `packageNames` 无功能损失） |

> 未执行项不得记为通过。TC-07 之后仍秒关，请把 TC-08 的完整 log 发回，用于判断是否属于系统白名单硬限制。

## 五、CI 结论

见 `TEST_REPORT.md`「CI 结论」小节（构建完成后回填）。

# v1.2.3 测试报告

> 对象：v1.2.3（versionCode 6），针对 vivo V2505A / Android 17 API 37 上「无障碍开关秒关」的第二轮定向修复。
> 静态测试在本机执行；编译 / 单测以 GitHub Actions 为准；真机流程需用户确认。

## 一、结论摘要

第二轮真机日志提供决定性证据：**连接 6 次、被撤销 4 次，pid 始终 23522、进程连续存活 3.4 分钟**。进程根本没被杀，因此 v1.2.2 把后台保活 / 电池白名单列为首要解法是错的；安装来源 `com.android.packageinstaller` 表明是侧载，叠加 Android 17 的「高级保护模式」，才构成「开关打开几秒后被系统复核撤销」的完整解释。

同时确认代码库里没有任何 `disableSelf()` 或写 `Settings.Secure` 的调用，开关不是 App 自己关的。

本轮做了两类实事：① 给出确定性的系统侧解法（ADB 一次性解除受限设置，无需 root）；② 把无障碍服务声明最小权限化，降低厂商支付风控触发面。诊断与引导顺序也按新根因重排。

## 二、测试结果

| 项目 | 结果 |
| --- | --- |
| 静态一致性测试 | **119 / 119 通过**（`scripts/verify_v1_2_3.py`，含括号配平） |
| ADB 脚本语法 | `bash -n scripts/android_unlock_accessibility.sh` 通过 |
| CI `assembleDebug` / `testDebugUnitTest` | **Success**：run `45943449003`（提交 `fa59b21`，3m16s）；单测 `T144-F0-E0-S0`，144 例全过 |
| 真机 TC-01~TC-10 | 需用户按 `TEST_CASES.md` 执行；本机无 Android SDK / JDK / 模拟器 |

## 三、本轮修复清单

| 编号 | 问题 | 修复 | 状态 |
| --- | --- | --- | --- |
| D-01 | `com.android.packageinstaller` 被当成正常来源，结论漏提受限设置 | `isSideloaded()` 覆盖手动安装 / adb / shell 来源 | 已修 |
| D-02 | 引导把后台保活放第一位，与 pid 稳定的现场矛盾 | `diagnose()` 用 pid 稳定性直接排除保活方向，重排引导顺序 | 已修 |
| D-03 | 缺少「进程是否被杀」的自动判据 | 新增 `KEY_PID_CHANGED` / `accessibilityPidStable()`，报告输出该字段 | 已修 |
| D-04 | 只有「让用户去点设置」的建议，vivo 可能隐藏菜单 | 新增 ADB 一键脚本与 App 内可复制的完整命令 | 已修 |
| D-05 | Android 17 高级保护模式未提示 | 列为稳定运行设置第 1 步 | 已修 |
| D-06 | XML 直接列出微信 / 支付宝 / 银行包名，易触发支付风控 | 移除 `packageNames`（代码里已有 `Constants.PAYMENT_PACKAGES` 过滤） | 已修 |
| D-07 | `canTakeScreenshot` 高风险能力、默认关闭 | 移除声明，兜底保留媒体投影 / OCR | 已修 |
| D-08 | 自检页把无截屏能力显示成故障 | 标记 `capabilityRemovedByDesign`，显示「预期行为」 | 已修 |
| D-09 | 关于页 / README / Release 停在 v1.2.2 | 全部同步 v1.2.3 | 已修 |

每项均有静态回归断言：T-SIDE-01..08、T-VER-01..03、T-A11Y-04。

## 四、功能影响评估

- `packageNames` 移除后，系统仍会把所有窗口事件交给服务，但 `PaymentAccessibilityService.dispatchEvent` 里的白名单过滤保持不变，**识别能力零变化**。代价是事件量略增，去抖与包名为先的判断已挡住开销。
- `canTakeScreenshot` 移除后 `canCaptureScreen()` 返回 false，`AccessibilityScreenshotSource.isReady()` 为 false。该通道由 `A11yShotOptIn` 控制且默认关闭，兜底有媒体投影与独立进程 OCR；**识屏主链路（读节点）不受影响**。自检页已相应改为「预期行为」。
- 保留 `flagRetrieveInteractiveWindows` 与 `flagRequestFingerprintGestures`，读节点树与指纹手势记账不受影响。

## 五、边界说明（不宣称已解决）

- App 仍**不能**写 `Settings.Secure` 改系统开关；ADB appops 与 Shevery / Shizuku 是系统级 / 部署级手段，不属于 App 运行时能力。
- 移除两项声明是「降低被风控判定为高风险服务的概率」，不是对系统策略的保证绕过；是否见效取决于 TC-05 的真机验证。
- 若高级保护模式被用户主动开启且不允许关闭，或厂商把非商店应用硬列入黑名单，则本轮所有手段都可能无效 —— 这是系统策略硬边界，需要如实反馈，不记通过。
- CI 绿色只证明编译与单测通过，不证明设备上的授权撤销已消失。

## 六、CI 结论

- **run**：`45943449003`，提交 `fa59b21`，2026-11-27 05:02:58Z → 05:06:14Z，`completed success`。
- **步骤**：`Static consistency tests (v1.2.3)` / `Build debug APK` / `Run unit tests` / `Create GitHub Release with APK` 全部 Success；`Summarize build errors` 按预期 skipped。
- **单测**：`T144-F0-E0-S0`（144 例，0 失败 / 0 错误 / 0 跳过）。
- **产物**：artifact `bookkeeper-debug-apk`（55,325,722 字节，压缩后）、`unit-test-reports-T144-F0-E0-S0`；Release `v1.2.3-debug`「少花点儿 v1.2.3 (Debug APK)」已附 `bookkeeper-debug.apk`（92,461,484 字节），下载链接返回 HTTP 200。
- 真机 TC-01~TC-10 未执行项仍为「待确认」，不记通过；本次 CI 绿色只证明编译与单测通过，**不证明设备上的授权撤销已消失**。

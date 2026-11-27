# v1.2.2 测试报告

> 对象：v1.2.2（versionCode 5），依据 vivo V2505A / Android 17 真机诊断日志的一轮定向修复。
> 静态测试在本机执行；编译 / 单测以 GitHub Actions 为准；真机流程需用户确认。

## 一、结论摘要

用户复制回的诊断日志显示：无障碍服务连上后只存活 **1.001 秒** 与 **27.273 秒**，随后 `系统开关：关`、`wasEnabled=true`，`onUnbind` 与 `onDestroy` 相隔 1~14ms 成对出现。

**判因：系统主动撤销无障碍授权**，不是 App 崩溃 / ANR / 长时间后台被省电清理。依据是 `Settings.Secure` 中开关已被系统移除，而进程被杀时开关通常仍亮着；且全程无崩溃或 ANR 日志。

这也暴露了 v1.2.1 的三个实际缺陷：日志措辞误导（把正常解绑写成「进程退出」）、连接事件不落盘导致时间线残缺、**vivo 机型根本看不到保活引导卡片**。本轮均已修复。

## 二、测试结果

| 项目 | 结果 |
| --- | --- |
| 静态一致性测试 | **111 / 111 通过**（`scripts/verify_v1_2_2.py`，含括号配平） |
| CI `assembleDebug` / `testDebugUnitTest` | **Success**：run `45931241434`（提交 `81e420a`，2m56s）；单测 `T144-F0-E0-S0`，144 例全过 |
| 真机 TC-01~TC-09 | 需用户按 `TEST_CASES.md` 执行；本机无 Android SDK / JDK / 模拟器 |

## 三、本轮修复清单

| 编号 | 问题 | 修复 | 状态 |
| --- | --- | --- | --- |
| D-01 | 诊断只有状态无结论 | 新增自动判因 `diagnose()`，弹窗与复制 log 直接给结论 | 已修 |
| D-02 | 解绑 / 销毁日志措辞误导排查方向 | 改为「系统撤销授权」「解绑后的正常销毁，非崩溃证据」 | 已修 |
| D-03 | 只有断开 ERROR、没有连接记录 | 新增 `CaptureLog.forceInfo`，连接事件强制落盘 | 已修 |
| D-04 | 缺存活时长 / pid / 次数 / 安装来源 | 新增 4 个 SP 字段与 `installSourceText()`，报告全部带上 | 已修 |
| D-05 | vivo / iQOO 看不到保活卡片 | `isVivoFamily()` + `needsKeepAliveGuidance()`，标题按厂商显示 | 已修 |
| D-06 | 漏 Android 13+ 侧载「允许受限设置」 | 列为稳定运行设置第 1 步 | 已修 |
| D-07 | vivo 需要的是「后台高耗电 + 一键加速白名单」 | vivo 分支给专属两条与正确设置路径 | 已修 |
| D-08 | 关于页停在 v1.2.1 | 新增 v1.2.2 更新介绍 | 已修 |

每项均补了静态回归断言（T-ROM-01..04、T-DIAG-01..06、T-LOG-01..05、T-A11Y-04）。

## 四、边界说明（不宣称已解决）

- App **不能**阻止系统撤销无障碍授权，也不能自己锁定该服务。Shevery / Shizuku 的「无障碍服务锁定」是系统级能力，只能引导用户操作。
- 本轮修复的是「判因准确度、日志完整度、vivo 引导可见性、系统侧步骤正确性」，**不等于**用户设备上的秒关问题已经消失；是否消失取决于 TC-01~TC-07 的系统侧设置。
- Android 13+ 的「受限设置」菜单可能被部分 ROM 隐藏或改名，报告里的安装来源字段用于确认是否属于侧载拦截。

## 五、CI 结论

- **run**：`45931241434`，提交 `81e420a`，2026-11-27 02:56:25Z → 02:59:21Z，`completed success`。
- **步骤**：`Static consistency tests (v1.2.2)` / `Build debug APK` / `Run unit tests` / `Create GitHub Release with APK` 全部 Success；`Summarize build errors` 按预期 skipped（构建成功时不需要）。
- **单测**：`T144-F0-E0-S0`（144 例，0 失败 / 0 错误 / 0 跳过）。
- **产物**：artifact `bookkeeper-debug-apk`（55,322,814 字节，压缩后）、`unit-test-reports-T144-F0-E0-S0`；Release `v1.2.2-debug`「少花点儿 v1.2.2 (Debug APK)」已附 `bookkeeper-debug.apk`（92,461,600 字节），下载链接返回 HTTP 200。
- 真机 TC-01~TC-09 未执行项仍为「待确认」，不记通过；本次 CI 绿色只证明编译与单测通过，**不证明用户设备上的授权撤销问题已经消失**。

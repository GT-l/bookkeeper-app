# v1.2.1 测试报告

日期：2026-11-27
对象：v1.2.1（versionCode 4）——关于页更新介绍、无障碍健康检查 / 异常日志、OrangeOS 稳定运行引导、固定调试签名。

## 一、本次修复

| # | 问题 | 处理 |
|---|---|---|
| 1 | 软件内「关于」仍只显示 v1.1.0，看不到 v1.2.0 / v1.2.1 | `AboutScreen` 增加 v1.2.1、v1.2.0 两段更新介绍，头部版本号跟随 BuildConfig |
| 2 | OrangeOS 上无障碍服务被系统关闭 / 解绑后无提示、无日志 | 新增 `AccessibilityHealth`：检测系统开关关闭与服务解绑，强制写 A11Y 日志，自动化设置与识屏自检页弹窗，可一键复制 log |
| 3 | 用户不知道 OrangeOS 怎么保活 | 参照咔皮记账「稳定运行设置 / 锁定与电量」，新增 5 步保活清单：最近任务锁定、自启动、电池白名单、无障碍服务锁定（Shevery / Shizuku）、重启后确认服务 |
| 4 | 每次 CI 构建的 debug 签名不同，覆盖安装报签名不一致 | 仓库内固定 `keystore/bookkeeper-debug.p12`（debug-only），`build.gradle.kts` 的 debug buildType 使用 `stableDebug`；版本升到 1.2.1 / versionCode 4 |
| 5 | 无障碍服务销毁时没有可复制的现场 | `CaptureLog.force` 强制落盘；`onUnbind` / `onDestroy` 写机型与时间线 |

## 二、测试结果

| 测试 | 结果 |
|---|---|
| `python3 scripts/verify_v1_2_1.py` | **96/96 通过**（本地） |
| CI `assembleDebug` / `testDebugUnitTest` | **成功**：run `45924605332`（提交 `f01b1e6`），`unit-test-reports-T144-F0-E0-S0`，144 例全过 |
| Release | `v1.2.1-debug` 已创建，`bookkeeper-debug.apk` 约 55.3 MB；正文为 v1.2.1 修复模板 |
| 真机保活 / 覆盖安装 | 需用户按 `TEST_CASES.md` 执行；本机无 Android SDK / JDK / 模拟器 |

## 三、风险与说明

1. **固定调试密钥是公开的 debug 密钥**，只能用于本地调试；不能用于上架或正式发布。若将来做 release，请另建私有 release keystore，不要复用此文件。
2. **首次从旧签名升级仍需卸载一次**：旧版每次 CI 重新生成 debug 签名，这个既成事实无法在应用内改变；从 v1.2.1 起后续构建签名一致，可覆盖安装。
3. **Shevery / Shizuku 不是内置能力**：应用无法绕过系统权限改无障碍开关，只能检测异常并引导；第三方工具的具体入口以该工具当前版本为准。
4. **无障碍健康检查依赖服务心跳**：从未连接过的设备不会误报；服务连接过一次后，被系统关闭 / 解绑才会触发。
5. **真机项未执行**：OrangeOS 的实际保活效果、通知权限、无障碍开关行为需用户在设备上确认。

## 四、结论

- 静态一致性 96/96 通过；关于页、无障碍诊断、固定签名均已落地。
- 编译与单测已由 GitHub Actions run `45924605332` 验证通过；Release `v1.2.1-debug` 与 APK 已发布。真机保活与覆盖安装仍需用户按测试用例确认。

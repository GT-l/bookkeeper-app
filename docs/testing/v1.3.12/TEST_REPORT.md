# v1.3.12 测试报告

## 一、结论

| 项目 | 结果 |
| --- | --- |
| 静态一致性 `scripts/verify_v1_3_12.py` | 待回填 |
| JVM 单元测试 | 待回填 |
| CI 构建 | 待回填 |
| Release | 待回填 |

## 二、变更文件

| 文件 | 改动 |
| --- | --- |
| `res/xml/accessibility_service_config.xml` | 恢复 `android:canTakeScreenshot="true"`，仍不声明 packageNames |
| `core/A11yShotOptIn.kt` | 默认值 false → true |
| `automation/service/PaymentAccessibilityService.kt` | EntryPoint 增加 channels/ocrIpc；新增 `scheduleScreenshotFallback`（15s 节流） |
| `ui/quick/LightweightCapture.kt` | 暴露 `captureChannelReady()` / `projectionSessionActive()` |
| `ui/quick/ShakeQuickAddService.kt` | 新增 `shouldGuideToAuth` 与 60s 节流，无通道时拉起录屏授权 |
| `ui/settings/CaptureDiagnosticsViewModel.kt` | 删掉「按最小权限关闭属预期」的错误文案，改为提示重开服务注册能力 |
| `ui/settings/AboutScreen.kt` | 更新介绍改为 v1.3.12 |
| `app/build.gradle.kts` | versionCode 19 / versionName 1.3.12 |
| `.github/workflows/android.yml` | 静态脚本与 Release tag 切到 v1.3.12 |

## 三、关键验证点

1. `<accessibility-service>` 里 `canTakeScreenshot="true"` 会在服务绑定时注册为 `CAPABILITY_CAN_TAKE_SCREENSHOT`，`canCaptureScreen()` 返回 true。
   注意：**能力在绑定时读取 XML**，覆盖安装后必须重开一次无障碍服务才生效。
2. `CaptureChannels.accessibilityReady()` = 开关开（现在默认开）+ 能力在 + 未被熔断。
3. 节点为空 → `scheduleScreenshotFallback` → `channels.capture()` → `OcrIpcClient.recognize` → `classify` → `submitCapture`。
4. 摇一摇失败且无通道 → `shouldGuideToAuth` → `ScreenCaptureAuthActivity` → 授权成功回调 `captureNow()`。

## 四、风险与兜底

- 无障碍 `takeScreenshot` 在个别 ROM 上可能 native 崩溃。`CaptureBreakerStore.markInflight` 在调用前落盘，崩溃后下次启动自动熔断，诊断页可见并可手动恢复。
- 安卓 14+ 媒体投影会话重启失效无法绕过，属于系统限制。

## 五、真机待验

用户设备 realme RMX3888 / Android 37：安装后重开无障碍 → 打开微信账单详情 → 自动识屏 + 摇一摇双路径验证。

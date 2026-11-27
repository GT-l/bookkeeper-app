# v1.3.2 测试报告

## 一、本轮问题与根因

| 问题 | 根因 | 修复 |
| --- | --- | --- |
| 触发一次后每笔都判重复，金额不同也一样 | 金额抽不到时去重键写 0、商户只抽到「支付」等页面标题时所有备注前缀相同 → 键退化成同一个 | 新增 `DuplicateRules`：金额必须 > 0、商户必须具体（长度 ≥2 且非泛词），否则一律不判重复 |
| 同一笔旧账单反复提醒 | `findRecentDuplicate` 每次都命中同一笔历史账单 | 同一 `billId` 只提醒一次（进程内去重） |
| 非实付金额被当成支付金额 | `PaymentTextAnalyzer` 会把「余额 / 优惠 / 退款」行的金额当候选 | 排除非实付金额行（实付 / 付款金额等强标签除外） |
| 点轻卡片「记一笔」没记录 | 候选 `categoryId` 为 0 时 `VerificationController.submitDecision` 直接返回错误，而 `submitFromOverlay` 把错误静默吞掉 | 源头上用默认 / 其他支出分类兜底；失败时弹 3 秒轻提示说明原因 |
| 无障碍仍被系统撤销 | 守护写回后没读回验证，vivo 立刻再次撤销时误以为恢复成功 | 15 秒检查 + 写回后读回验证 + 连续失败计数 + 3 次后明确提示系统级解法 |
| 弹窗丑 / 文案啰嗦 | 旧卡片是灰底 + 原生 Button；关于页文案带 GitHub 字样、更新介绍长篇 | 按草莓粉主题重做圆角卡片与胶囊按钮；「检查 GitHub 更新」→「检查更新」；更新介绍只写更新/修复了什么 |
| 通知栏常驻三条 | 自动记账 / 无障碍守护 / 摇一摇各起一个前台服务 | 守护检查并入自动记账服务（15 秒一次、无独立通知）；摇一摇复用自动记账通知 ID；下拉只剩一条 |

## 二、修改文件（摘要）

- 新增 `automation/dedup/DuplicateRules.kt` 与 `DuplicateRulesTest.kt`。
- `DuplicateDetector` / `AutoRecordUseCase` / `CaptureCoordinator`：统一准入规则 + 严格三元组比对 + 旧账单只提醒一次。
- `PaymentTextAnalyzer`：排除非实付金额。
- `CaptureCoordinator`：注入 `CategoryRepository`，候选分类兜底。
- `VerificationController`：确认失败给轻提示。
- `OverlayVerifyView`：按 App 主题色（草莓粉 / 奶油白 / 深色适配）重做卡片，胶囊按钮。
- `A11yWatchdogService`：15 秒检查、写回验证、连续失败计数与提示。
- `AccessibilityHealth`：新增 `canAutoRestore()`，诊断报告显示自动恢复权限。
- `AboutScreen`：更名「检查更新」，更新介绍精简到 9 个版本、每条一句短句。
- `README.md` / `.github/workflows/android.yml`：版本 v1.3.2、Release 正文精简。

## 三、自动验证

| 项目 | 结果 |
| --- | --- |
| 静态一致性 | `python3 scripts/verify_v1_3_2.py`：170/170 通过 |
| JVM 单测 | **T171-F0-E0-S0**（171 项全过，含本轮 3 项 `DuplicateRulesTest`） |
| Kotlin 编译 | `assembleDebug` 成功，APK 约 92.5 MB |

## 四、CI 结论

- 最终 run **`46040426762`**（远端 commit `4fd2e36`）：`completed success`，静态测试 / 编译 / 单测 / Release 全绿。
- 过程中修了两次编译错误：`DuplicateDetector` 传可空 `Long?` 给 DAO 的 `Long` 参数、`A11yWatchdogService.checkNow` 表达式体返回 `Result` 与裸 `return` 冲突、`OverlayVerifyView` 缺 Compose `toArgb` 扩展导入。
- Release：[`v1.3.2-debug`](../../releases/tag/v1.3.2-debug)，附件 `bookkeeper-debug.apk`（92,527,156 字节）。
- 从 v1.3.1 可直接覆盖安装（固定调试签名，账单不丢）。

## 五、边界

- 未授予 `WRITE_SECURE_SETTINGS` 时守护不能替用户改系统开关；系统反复撤销且高级保护开启时需 Shizuku / Shevery 系统级锁定。
- 自动更新最后一步仍需用户点系统安装器「安装」。
- 真机 TC-01~TC-14 需用户执行；未执行项不记通过。

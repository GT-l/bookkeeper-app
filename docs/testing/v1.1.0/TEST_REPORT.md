# v1.1.0 测试报告：问题清单、修复方案与风险点

日期：2026-11-26
对象：`db6d694..363b2db`（我的页 / 设置页 / 存储排查 / 首页与页面美化 / 统计页 / 转场 / 版本号）

## 一、测试环境与方法

| 项目 | 情况 |
|---|---|
| 本机 | macOS，**无 Android SDK、无 JDK、无模拟器** → 不能本地 `gradlew` 编译或跑 instrumentation |
| 可执行 | Python 静态一致性测试（59 项）、源码走查、CI 上的 JVM 单测与 `assembleDebug` |
| 不可执行 | Compose 真实点击、Activity 生命周期、文件删除、相册写入等真机流程 → 以走查 + 用户回归清单覆盖 |
| CI 单测可信度 | 原 workflow 给单测加 `continue-on-error: true`，失败也显示绿色；本轮已移除，并把统计写进 artifact 名 |

执行顺序严格按要求：**先测试 → 列出问题 → 再修复 → 回归验证**。

## 二、测试结果概览

| 测试 | 初测结果 | 修复后结果 |
|---|---|---|
| `python3 scripts/verify_v1_1_0.py` | 45/46 通过，1 项失败（删除提示被刷新抹掉） | **59/59 通过** |
| 新增 JVM 单测（StorageAuditLogicTest 7 例 + ProfileSummaryLogicTest 6 例） | 本机无法执行 | CI 执行中，结果见 Release 对应构建的 `unit-test-reports-T*-F*-E*-S*` artifact 名 |
| 逐函数 / 逐流程走查 | 记录 14 个问题（1 个 P1 逻辑错误、5 个 P1 功能/数据问题、8 个 P2 体验或风险） | 14 个问题均已修复并加回归断言 |
| 真机流程 | 未执行（环境限制） | 见 `TEST_CASES.md` 第三节回归清单，需用户确认 |

## 三、问题清单与修复

### P1（功能错误 / 数据口径错误）

| # | 问题 | 触发条件 → 实际行为 | 主流修复方式 | 风险点与规避 | 状态 |
|---|---|---|---|---|---|
| 1 | 统计页 📷 按钮在概览/分类/账户/标签 4 个页签点了没反应 | `requestScreenshot()` 只有饼图/趋势组件消费 | **按能力收敛入口**：只在 tab 1/2/3 显示按钮；纯列表页不承诺截图 | 用户可能期望全页截图；若后续要做，应改用 `PixelCopy` 全页方案而非逐组件 | 已修 |
| 2 | 截图后切页签重复写相册 | 图表内部 `remember` 的 `lastCapture` 随组合销毁重置，旧 `captureRequest` 被当成新请求 | **一次性事件消费**：`saveScreenshot()` 写回时把 `captureRequest` 归零 | 归零必须和保存回调同步；已放在同一 `_uiState.update` | 已修 |
| 3 | tab 复用导致统计页数据不刷新 | `saveState/restoreState` 复用 ViewModel，`init` 不重跑；换账本后回统计仍是旧数据 | **订阅数据源 + 页面进入刷新**：监听 `settings.defaultBookId`，并加 `refresh()` 由 `LaunchedEffect(Unit)` 触发 | 首次进入会重复触发一次加载；用 `loadJob.cancel()` 串行化，旧请求不会覆盖新结果 | 已修 |
| 4 | 「我的」页把余额校准流水算进收支 | `applyBills` 只过滤 `amountFen>0`，ADJUSTMENT 被计入支出/预算 | **统一统计口径**：排除 `isAdjustment` 与 `TRANSFER`，与 `GetStatisticsUseCase` 对齐 | 记账天数与第一笔时间会随之变化，属预期 | 已修 |
| 5 | 跨月范围套用起始月预算导致假超支 | 「本年」用 1 月预算 5000 对比全年支出 6 万，显示「超支 5.5 万」 | **同月才取预算**：`monthKey(start)==monthKey(end)` 才读预算，否则 `budgetLimitFen=0` 走无预算分支 | 跨月但恰好整月（6/1–6/30）仍视为同月；`monthKey` 为 `yyyyMM` 已含年份 | 已修 |
| 6 | 删除成功提示看不见 | `deleteSelected()` 写 message 后同步 `rescan()`，`rescan` 把 message 清成 null；StateFlow 只保留最终值 | **刷新保留提示**：`rescan(clearMessage=false)`，手动点重新扫描仍清空 | 保留旧提示可能误导；手动刷新走默认 `clearMessage=true` | 已修 |

### P2（体验 / 稳定性风险）

| # | 问题 | 主流修复方式 | 风险点与规避 | 状态 |
|---|---|---|---|---|
| 7 | 换账本时「我的」页账户数/净资产/预算残留旧值 | 切换账本时把三块字段一起清零 | 无；清空后各 Flow 会补上新值 | 已修 |
| 8 | 统计页提示条常驻不消失 | `LaunchedEffect(state.message)` + 3 秒 `dismissMessage()` | 新消息替换会重启计时，符合预期 | 已修 |
| 9 | 首页删除失败提示常驻 | 同上，3 秒 `consumeMessage()` | 与账单流的 `message=null` 写入不冲突 | 已修 |
| 10 | 底栏 `AnimatedVisibility` 在动画结束帧让页面高度二次跳变 | **回归即时切换**：去掉 AnimatedVisibility；转场已压到 220ms 内，不再需要底栏动画 | 失去底栏滑动观感，但消除了更明显的跳变；后续可用「底栏覆盖内容、不给 padding」方案兼顾 | 已修 |
| 11 | 统计页超支显示封顶 100%，「我的」页显示 150% | 进度条仍 `coerceIn(0,1)`，文字用 `Double` 算真实百分比 | 用 Double 避免 `Long*100` 溢出 | 已修 |
| 12 | 快速连点筛选项，旧请求可能覆盖新结果 | `loadJob` 保存当前任务，新请求先 `cancel()` | Room/DataStore 的 `first()` 可取消；取消后不写回旧 bundle | 已修 |
| 13 | 账户分析下钻看不到转账 | 下钻从 `allBills`（仅排除 ADJUSTMENT）取数，其它下钻仍用统计口径列表 | 转账行在 `BillRow` 已按灰色无符号处理 | 已修 |
| 14 | PIN 可存字母，解锁页数字键盘永远输不进去 | `savePin()` 增加 `pin.all { isDigit }` 校验并提示 | 旧版本已存的非数字 PIN 用户需重设；若发生会提示「密码只能是数字」 | 已修 |

## 四、修复后的回归断言

`scripts/verify_v1_1_0.py` 新增 `T-FIX-01..13`，逐项锁死上述修复：

- 我的页排除 ADJUSTMENT、换账本清空三字段；
- 跨月预算同月判定、截图请求消费、截图按钮范围、进入刷新、加载串行化、账户下钻全流水；
- 提示自动消失（统计/首页）、底栏无 AnimatedVisibility、预算百分比统一、PIN 数字校验。

初测失败的 `T-AUDIT-05` 已改为检查 `rescan(clearMessage=false)` 路径，修复后通过。

## 五、未覆盖与注意事项

1. **真机交互未执行**：本机环境限制。请按 `TEST_CASES.md` 第三节的 7 条清单在设备上确认。
2. **相册写入权限**：截图保存到 MediaStore 需要存储权限，CI 单测无法覆盖；失败时页面会明确提示。
3. **文件删除不可逆**：存储排查的删除只在 App 私有目录内，且二次确认；测试时请用无价值的临时文件。
4. **CI 单测统计**：本轮起不再用 `continue-on-error` 掩盖失败；若测试失败，构建会红且不会发布 Release。

## 六、结论

- 静态测试 **59/59 通过**（修复后）。
- 走查发现的 14 个问题均已按业界常用方式修复，并为每一项补了回归断言。
- JVM 单测 13 例随 CI 执行；APK 构建与单测结果以 GitHub Actions 最终状态为准。
- 真机层面的 UI 正确性仍需用户按清单回归；本报告不将未执行项记为通过。

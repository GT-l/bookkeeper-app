# v1.2.0 测试报告：问题清单、修复方案与风险点

日期：2026-11-27
对象：`31727bb..79a74ef`（数据库 v1→v2；统计增强；退款 / 报销 / 批量 / 债务 / 分期 / 年度预算 / 信用卡提醒；CI 错误摘要）

## 一、测试环境与方法

| 项目 | 情况 |
|---|---|
| 本机 | macOS，**无 Android SDK、无 JDK、无模拟器** → 不能本地 `gradlew` 编译或跑 Compose 交互 |
| 可执行 | Python 静态一致性脚本、逐函数 / 逐流程走查、CI 上的 `assembleDebug` 与 JVM 单测 |
| 不可执行 | Compose 真机点击、Activity 生命周期、通知实际弹出、Room 真机迁移 → 以走查 + `TEST_CASES.md` 第三节回归清单覆盖 |
| 单测可信度 | CI 已移除 `continue-on-error`，T/F/E/S 写入 artifact 名；编译失败的首条 Kotlin 错误写入 `build-log-<error>` artifact 名 |

执行顺序：**先测试 → 列问题 → 按主流方案修复 → 回归验证**。

## 二、测试结果概览

| 测试 | 初测结果 | 修复后结果 |
|---|---|---|
| `python3 scripts/verify_v1_2_0.py` | 82/82 通过 | **82/82 通过**（本地复跑确认） |
| CI `assembleDebug` | 连续 3 次失败：Release 正文表达式超长、Material3 `LinearProgressIndicator` API 变更、另有 1 项编译错误待定位 | 见下方「CI 结论」 |
| CI `testDebugUnitTest` | 未跑到（编译先失败） | 见下方「CI 结论」 |
| 逐函数 / 逐流程走查 | 记录 6 类风险点（见第三节） | 均已按主流方案修复并补静态断言 |
| 真机全流程 | 未执行（环境限制） | 见 `TEST_CASES.md` 第三节，需用户确认 |

### CI 结论

> 待本轮 CI 完成后填入：run ID、结论、artifact 名（T/F/E/S 与编译错误摘要）。

## 三、问题清单、修复方案与风险点

| # | 问题 | 触发条件 → 实际行为 | 主流修复方式 | 风险点与规避 | 状态 |
|---|---|---|---|---|---|
| 1 | Release 正文表达式超过 21000 字符 | GitHub Actions 解析 workflow 失败，run 未真正开始构建 | 把 `body:` 模板压缩为精简版，只保留本版新增、已有能力、隐私、反馈要求 | 正文细节减少；完整说明留在仓库 `README.md` 与 `docs/testing/` | 已修（`d6851dd`） |
| 2 | Material3 `LinearProgressIndicator.progress` 需要 lambda | Kotlin 编译报类型不匹配，3 处（预算 / 债务 / 分期） | 三处统一改为 `progress = { value }` 的 lambda 形式 | 低风险，仅 API 适配；静态脚本已锁住 3 个文件 | 已修（`f43c327`） |
| 3 | CI 编译失败原因无法从公开 API 直接读取 | 失败 run 的 `logs` 与 artifact 下载端点需要鉴权，本地 `gh` 不可用 | 新增 `Summarize build errors` 步骤，把首条 `e: ...` 清洗后写进 artifact 名，公开 API 可读 | artifact 名有长度限制，只保留首条错误的前 90 字符；完整日志仍需用户在 Actions 页下载 | 已修（`79a74ef`），用于定位剩余错误 |
| 4 | 新增业务可能绕过余额单一事务入口 | 退款 / 报销 / 债务 / 分期 / 批量如果直写 `balance_fen`，会出现两处写余额、对不上账 | 全部走 `BillRepository.addWithBalanceTx / updateWithBalanceTx / deleteWithBalanceTx`；静态检查 `T-TX-*` 禁止出现 `updateBalance(` 与 `balance_fen` | 逐笔调用带来部分成功语义，UI 需返回成功数并提示失败项 | 已修（架构约束 + 静态锁死） |
| 5 | 数据库升级可能重复建表 / 重复加列 | 老用户升级、或迁移被重试 | `CREATE TABLE IF NOT EXISTS`；加列前 `PRAGMA table_info` 查列；`addMigrations(MIGRATION_1_2)` 显式注册 | 迁移不可逆；升级前建议用户导出备份（已在真机清单第 1、10 条覆盖） | 已修 |
| 6 | 统计增强把余额校准与转账算成支出 | 资产校准后年度预算、热力图、同比环比虚增 | `StatsEnhanceCalculator` 统一过滤 `isAdjustment` 与 `BillType.TRANSFER`；JVM 单测覆盖 | 与旧统计口径保持一致；下钻明细仍可看到转账（灰色无符号） | 已修 |
| 7 | 分期除不尽时各期之和 ≠ 总额 | 1000 分 / 3 期按 333 分固定，会少 1 分 | 最后一期用 `totalFen - perPeriodFen * paidPeriods` 补齐尾差 | 若人工改过 `perPeriodFen`，尾差可能异常；已 `coerceAtLeast(0L)` 保护 | 已修（JVM 单测覆盖） |
| 8 | 退款可重复退超过原账单 | 同一原账单多次部分退款 | `refundDao.sumByBill` 累计校验，超过原金额返回 `E_REFUND_003` | 并发提交仍可能竞态；当前为本地单用户 App，风险可接受 | 已修 |

## 四、全局与全流程自测

| 层面 | 覆盖方式 | 结果 |
|---|---|---|
| 全局静态一致性 | 82 项脚本：版本、迁移、实体/DAO/DI、事务入口、路由入口、关键业务逻辑、CI 步骤、新增单测、语法配平 | 通过 |
| 全局编译 | CI `assembleDebug` | 见「CI 结论」 |
| 全局单测 | CI `testDebugUnitTest` 全套（含 v1.1.0 的 130 例 + v1.2.0 新增 2 个测试文件） | 见「CI 结论」 |
| 全流程（业务链） | 退款 / 报销 / 债务 / 分期 / 批量 / 统计 6 条链路逐函数走查 | 走查通过；真机待确认 |
| 全流程（用户路径） | 升级 → 记账 → 自动识屏 → 统计 → 备份恢复 10 条清单 | 需用户执行，本报告不代为标记通过 |

## 五、未覆盖与注意事项

1. **真机交互全部未执行**：本机无 JDK / Android SDK / 模拟器；Compose 点击、Room 迁移、通知弹出、通知权限兼容均需用户按清单回归。
2. **批量操作是部分成功语义**：返回成功数并提示失败项，UI 不应把「部分失败」显示成「全部成功」。
3. **债务 / 分期删除只删元数据**：已生成的账单保留，避免自动回滚造成账实不符；用户如需回滚请逐笔删除账单。
4. **通知依赖 ROM 权限**：部分 OEM 在无权限时不抛异常而是阻塞，`NotifyGuard` 已统一兜底，仍建议真机验证。
5. **Release 正文来自 workflow 模板**：每次改说明必须同时改 `.github/workflows/android.yml` 的 `body:`，否则 CI 会覆盖回旧文案。

## 六、结论

- 静态一致性 **82/82 通过**；走查发现的 8 项问题均已修复并补回归断言。
- 编译与单测的最终结论以本轮 GitHub Actions 为准（见「CI 结论」）；未拿到绿色结论前不宣称发布成功。
- 真机 10 条全流程清单需用户确认；在此之前，本报告只覆盖静态、JVM 与代码走查层面。

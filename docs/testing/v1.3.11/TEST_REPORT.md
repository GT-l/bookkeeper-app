# v1.3.11 测试报告

## 一、结论

| 项目 | 结果 |
| --- | --- |
| 静态一致性 `scripts/verify_v1_3_11.py` | **252/252 通过** |
| JVM 单元测试 | **T207 F0 E0 S0** |
| CI 构建 | run `46117391429`（`4a6d95f`）success |
| Release | `v1.3.11-debug`，APK 92,562,243 字节 |

## 二、变更文件

| 文件 | 改动 |
| --- | --- |
| `automation/ocr/PaymentTextAnalyzer.kt` | 英文续行拼接 `mergeLatinContinuation`；商户长度上限 30→60；购买内容排除账单链接 |
| `automation/capture/PaymentScreenRecognizer.kt` | 新增 `transactionTimeKey()` 与 `TIME_LABEL_TOKENS` / `TIME_TOKEN` |
| `automation/service/PaymentAccessibilityService.kt` | `SettledMark` 增加 `timeKey`；交易时间不同即放行 |
| `automation/capture/CaptureCoordinator.kt` | 页面类型加成分级（成功页 0.20 / 详情页 0.10）；账户兜底 |
| `test/.../PaymentTextAnalyzerTest.kt` | +2 用例 |
| `test/.../PaymentScreenRecognizerTest.kt` | +4 用例 |
| `ui/settings/AboutScreen.kt` | 更新介绍改为 v1.3.11 |
| `app/build.gradle.kts` | versionCode 18 / versionName 1.3.11 |
| `.github/workflows/android.yml` | 静态脚本与 Release tag 切到 v1.3.11 |

## 三、关键验证点

1. `Apple Distribution International` + `limited` 两行拼接后长度 39，在 60 上限内，且不含收单机构排除词 → 正确返回。
2. 收款成功页：`incomeHit = 已收款` → SUCCESS + INCOME + `WECHAT_TRANSFER_IN`，分数 `0.30+0.25+0.15+0.20 = 0.90` > 0.75，自动入账。
3. Apple 账单详情：分数 `0.30+0.25+0.15+0.10(商户)+0.05(账户)+0.20 = 1.05 → 封顶 1.0`，自动入账。
4. 交易时间 `2026年11月15日 00:00:07` 可被 `DateTimeUtils.parseFlexible` 的 `yyyy年M月d日 HH:mm:ss` 解析，账单时间不再用识屏时刻。

## 四、边界说明

- 真机仍需验证：微信账单列表连续点开两笔同金额记录，确认各记一笔；同一笔反复进出，确认只记一次。
- 页面时间只精确到分钟时，同分钟同金额两笔转账会被判重复（概率极低）。

# v1.3.14 测试报告

## 一、结论

| 项目 | 结果 |
| --- | --- |
| 静态一致性 `scripts/verify_v1_3_14.py` | **276/276 通过** |
| JVM 单元测试 | **T209 F0 E0 S0** |
| CI 构建 | run `46121938070`（`0a0fa97`）success |
| Release | `v1.3.14-debug`，APK 92,578,651 字节 |

## 二、变更文件

| 文件 | 改动 |
| --- | --- |
| `automation/capture/CaptureCoordinator.kt` | 账单时间优先用页面交易时间（`transactionTimeKey` + `parseFlexible`） |
| `automation/service/PaymentAccessibilityService.kt` | 抖音场景静默窗口 12s → 60s（`DOUYIN_QUIET_MS`） |
| `test/.../PaymentScreenRecognizerTest.kt` | +2 用例（抖音下单时间 / 缺时间） |
| `ui/settings/AboutScreen.kt` | 更新介绍改为 v1.3.14 |
| `app/build.gradle.kts` | versionCode 21 / versionName 1.3.14 |
| `.github/workflows/android.yml` | 静态脚本与 Release tag 切到 v1.3.14 |

## 三、关键链路

```
抖音订单页事件潮
  → submitCapture（5s 指纹 + 60s 静默 + 金额/时间指纹）
  → CaptureCoordinator
      → transactionTimeKey("下单时间 2026-11-28 10:55:00") = "2026-11-28 10:55:00"
      → parseFlexible → billTime
      → 去重键 bookId|秒|金额|账户|备注  ← 同一订单恒定
  → 第二次触发命中去重 → notifyDuplicated（轻提示「这笔已记过」）
```

## 四、风险

- 60 秒静默只作用于抖音场景；微信/支付宝仍为 12 秒，用户连续查看账单不受影响。
- 若抖音页面无时间字段，仍依赖静默窗口，属已知限制。

## 五、真机待验

抖音「我的订单 → 订单详情」停留 30 秒、切走切回 3 次，确认账单只有一条。

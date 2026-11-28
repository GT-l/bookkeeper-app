# v1.3.19 修复：微信收款记成支出、金额被记成 111845.00

## 一、现场（realme RMX3888 / Android 37，16:38:32）

```
INFO[OCR]     本地识别完成 277 字 blocks=28
INFO[CAPTURE] 识别成功 amount=11184500 merchant=拉卡拉支付股份有限公司 goods=- conf=1.0
INFO[CAPTURE] 自动记账成功 billId=43 amount=11184500 conf=1.0
```

用户实际记的是 **21.60 元的支出**，却落成 **111,845.00 元的支出**；而更早一笔自己的**微信收款**
也被记成支出。

## 二、根因（两条，都是代码层写死）

### 1. 摇一摇链路写死 EXPENSE

`AutoRecordUseCase.invoke` 里 `billType = BillType.EXPENSE` 是硬编码，`CaptureOutcome` 也**没有方向字段**。
自动识屏链路（`CaptureCoordinator`）有方向，摇一摇链路（`LightweightCapture` → `AutoRecordUseCase`）没有——
所以只要是摇一摇记的账，一律是支出，收款也只有支出。

### 2. 金额抽取没有任何合理性校验

`FieldExtractor.extractAmount` 取「第一个匹配就返回」：

```kotlin
val raw = amountWithSymbol.find(text)?.groupValues?.get(1) ?: ... ?: return null
```

页面上任何带 `¥` 的数字（累计收款、额度、流水号）都能被当成金额。
`111845.00` 这种 6 位整数完全不在旧规则（只挡 7 位以上整数）覆盖范围内。

## 三、修复

| 位置 | 修改 |
| --- | --- |
| `PaymentScreenRecognizer` | 新增 `looksLikeIncome`：`RECEIVE_HINTS`（微信收款/收款到账/二维码收款/已收钱…）判收入；`EXPENSE_STRONG_HINTS`（付款成功/已支付/转账已发出/支出…）优先级更高，避免「收款方」把支出页判成收入 |
| `CaptureOutcome` | 新增 `billType`，`succeed()` 里按原文判定并往下传 |
| `AutoRecordUseCase` | `invoke(..., billType: BillType = BillType.EXPENSE)`，落库用传入值 |
| `ShakeQuickAddService` | 自动记账、轻确认标题（识别到一笔收入/支出）、通知文案全部跟方向走 |
| `FieldExtractor` | 逐候选校验：7 位以上整数、单笔 ≥ ¥100,000、所在行含「单号/尾号/累计/余额/额度」一律跳过 |
| `PaymentTextAnalyzer` | 同步加 ≥ ¥100,000 上限，两条抽取链路口径一致 |
| 日志 | 成功日志带 `type=` 与原文预览；`EXTRACT_EMPTY` 失败也带原文 |

## 四、用例

| 编号 | 场景 | 期望 |
| --- | --- | --- |
| D-01 | 微信收款到账页摇一摇 | `type=收入`，落库为收入 |
| D-02 | 支付宝付款成功页 | `type=支出`（「收款方」不触发收入） |
| D-03 | 转账已发出页 | 支出（支出信号优先） |
| A-01 | 页面含「¥111845.00」 | 不抽取该金额（≥¥100,000） |
| A-02 | 页面含「交易单号 ¥1118450.00」 | 不抽取（7 位整数） |
| A-03 | 正常「¥21.60」 | 正常抽 2160 分 |
| A-04 | 「累计收款 ¥888.88」 | 不抽取（累计行） |

## 五、未解决 / 待确认

- **微信转账页识别不到信息**：本版没有针对性改动。现在日志会带上 OCR 原文，
  下次贴日志即可判断是「节点/截图没拿到信息」还是「抽取规则不认识」。
- 商户名仍可能出现收单机构（如「拉卡拉支付股份有限公司」）。这是 OCR 原文里唯一像商户的字段，
  需要看到原文才能决定是排除收单机构还是继续向下找。

## 六、回归

- 静态检查 `scripts/verify_v1_3_19.py`：366 项（新增 T-DIR-01~10、T-AMT-01~08）
- 单测新增 `FieldExtractorTest`（3 例）+ `PaymentScreenRecognizerTest`（3 例）

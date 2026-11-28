# v1.3.10 测试报告

## 一、结论

| 项目 | 结果 |
| --- | --- |
| 静态一致性 `scripts/verify_v1_3_10.py` | **240/240 通过** |
| JVM 单元测试 | **T201 F0 E0 S0** |
| CI 构建 | run `46115891232`（`ad823ba`）success |
| Release | `v1.3.10-debug`，APK 92,562,243 字节 |

## 二、变更文件

| 文件 | 改动 |
| --- | --- |
| `automation/ocr/PaymentTextAnalyzer.kt` | 新增 `MERCHANT_LABEL_FRAGMENTS`；「取下一行」改为向下扫描 3 行并跳过残片；`isPlausibleMerchant` 拦截残片 |
| `automation/capture/PaymentScreenRecognizer.kt` | 同样定义残片集合；`extractLabelledMerchant` 向下扫描 3 行；`isMerchantValue` 拦截残片；`NOISE_LINES` 加「全称/简称/名称」 |
| `test/.../PaymentTextAnalyzerTest.kt` | +3 用例 |
| `test/.../PaymentScreenRecognizerTest.kt` | +2 用例 |
| `ui/settings/AboutScreen.kt` | 更新介绍改为 v1.3.10 |
| `app/build.gradle.kts` | versionCode 17 / versionName 1.3.10 |
| `.github/workflows/android.yml` | 静态脚本与 Release tag 切到 v1.3.10 |

## 三、根因与验证要点

1. **正则回溯**：`商户全称` 独占一行时，`商户全称` 分支取不到后续字符，引擎回溯到 `商户` 备选，把 `全称` 作为捕获组返回。已用等价正则离线复现：`'商户全称' -> '全称'`。
2. **节点拆分**：微信无障碍把标签拆成两个 TextView 时，`removePrefix("商户")` 的剩余部分正好是 `全称`，长度 2 通过校验。
3. **修复后行为**：残片一律跳过并继续向下取值；取值不合法即停止，不越界取到收单机构；最终取不到则留空，不写错。

## 四、边界说明

- 无法覆盖所有 App 的标签写法。若某页面用「商户名」而非「商户名称」，「商户名」不在标签表中，仍会走兜底猜测——但残片与收单机构已被排除，最差结果是商户留空，不会写错。
- 真机仍需验证：微信账单详情、微信支付凭证、支付宝账单详情三处商户显示是否正确。

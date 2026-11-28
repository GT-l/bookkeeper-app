# v1.3.17 修复：ML Kit 在独立进程没初始化，截图 OCR 从未成功

## 一、现场（realme RMX3888 / Android 37，16:08:06 摇一摇）

```
INFO[A11Y]    takeScreenshot 成功 1264x2780
INFO[CHANNEL] 无障碍截屏帧体检 491x1080 采样2244 亮度均值244 [0..255] 近黑1% 近白94%
INFO[OCR]     启动 :ocr 进程 started=true
WARN[OCR]     识别异常 0 字 blocks=0 原因=IllegalStateException: MlKitContext has not been initialized
WARN[CAPTURE] 失败 OCR_PROCESS_FAILED：OCR 引擎异常：IllegalStateException: MlKitContext has not been initialized
```

关键点：**帧是正常的**（亮度均值 244、近白 94%、有 1% 近黑像素 = 白底黑字页面），
失败的只有 OCR 引擎本身。

## 二、根因

ML Kit 依赖自带的 `MlKitInitProvider`（ContentProvider）做自动初始化，provider 声明在
`com.google.mlkit:common` 的 AAR 里（已核实：`authorities=${applicationId}.mlkitinitprovider`）。
在这个 ROM / 系统的 `:ocr` 进程里它没有生效，于是 `TextRecognition.getClient()` 一调就抛
`MlKitContext has not been initialized`。

而 v1.3.16 之前的代码把异常 `runCatching` 成 `OcrResult("", 0f, 0)` —— 用户看到的永远是
「识别到文字但没找到扣款金额」。也就是说：**这台机器上的截图 OCR 从第一天起就没成功过**，
只是一直被伪装成「页面不是支付结果页」。

## 三、修复

1. **显式初始化**：`OcrEngine` 在 `by lazy` 里先调 `com.google.mlkit.common.MlKit.initialize(context)`
   再取识别器（幂等，主进程重复调用安全）；失败写 `MlKit 显式初始化失败：…` 到识屏日志。
   `MlKit.initialize` 的签名已从 `common-18.11.0.aar` 的字节码里核实过，不是猜测。
2. **依赖显式化**：`libs.versions.toml` 增加 `mlkit-common = 18.11.0`（与 text-recognition 传递版本一致），
   `app` 显式 `implementation(libs.mlkit.common)`，避免靠传递依赖拿 API。
3. **主进程兜底**：`OcrIpcClient` 注入 `OcrEngine`；跨进程结果命中「MlKitContext / not been initialized」
   时置 `ipcUnusable = true` 并改用主进程识别，后续不再每轮白等一次 IPC（省 1 秒以上）。
4. 兜底结果与跨进程结果结构一致（`OcrIpcPayload`），上层无需改动。

## 四、用例

| 编号 | 场景 | 期望 |
| --- | --- | --- |
| M-01 | 显式初始化后跨进程识别 | `识别完成 N 字`，不再是 `0 字` |
| M-02 | 系统仍拒绝初始化 | 日志「MlKit 显式初始化失败：…」，随后走主进程兜底 |
| M-03 | 主进程也失败 | 报「本地 OCR 失败：…」，不再伪装成「没找到金额」 |
| M-04 | 第二次摇一摇 | 直接本地识别（`跨进程 OCR 已停用`），无 1 秒 IPC 等待 |
| M-05 | 帧体检仍生效 | 空白帧依旧不进 OCR，改走录屏通道并引导授权 |

## 五、回归

- 静态检查 `scripts/verify_v1_3_17.py`：338 项（新增 T-MLK-01 ~ T-MLK-10）
- v1.3.16 的帧体检、v1.3.15 的下载改造全部保留
- 若本版日志显示「本地识别完成 N 字」仍为 0，则说明是模型层问题（与初始化无关），按帧体检 + 引擎 error 继续定位

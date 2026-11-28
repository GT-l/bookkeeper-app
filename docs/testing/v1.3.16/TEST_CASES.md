# v1.3.16 方案：截到空白帧却报「没找到金额」

## 一、现场（realme RMX3888 / Android 37，15:56:13 摇一摇）

```
INFO[CAPTURE] 阶段=节点读屏
INFO[CAPTURE] 阶段=截屏兜底
INFO[CHANNEL] 开始抓帧 a11yCapable=true a11yBreaker=false projSession=false projBreaker=false
INFO[A11Y]    takeScreenshot 成功 1264x2780
INFO[CHANNEL] 无障碍截屏返回 code=0 bitmap=1264x2780
INFO[OCR]     启动 :ocr 进程 started=true
INFO[OCR]     识别完成 0 字 conf=0.0 blocks=0
WARN[CAPTURE] 失败 EXTRACT_EMPTY：截图 OCR 完成但没找到金额（当前页可能不是支付结果页）
```

## 二、根因

1. **系统给的是空白帧**。截屏调用返回成功、尺寸正常，但整帧没有真实画面内容。
2. **旧的黑帧检查漏掉了它**：`isBlackFrame` 只算「平均亮度 < 8」，全白、全灰、半空帧全部放行。
3. **空图被丢进了 OCR**：ML Kit 自然返回 0 字，上层只能理解成「页面没有金额」。
4. **OCR 异常与空文本无法区分**：`OcrEngine` 的 `runCatching` 把异常也变成
   `OcrResult("", 0f, 0)`，日志里和「图里真没字」长得一模一样。
5. **用户没有出路**：`shouldGuideToAuth` 只在 `NO_CHANNEL / CHANNEL_DISABLED` 时引导录屏，
   而空白帧时 `captureChannelReady()` 仍为 true，于是永远不引导，用户只能反复失败。

## 三、修复

| 点 | 改动 |
| --- | --- |
| 帧体检 | 新增 `FrameSanity`：采样 32×64 点，算均值/最暗/最亮/近黑近白占比；亮暗跨度 < 12 判为单色帧（全黑/全白/全灰） |
| 通道决策 | 单色帧不再进 OCR，直接让给媒体投影通道；两条都空时报 `BLACK_FRAME` 并写明统计 |
| 日志 | 每次抓帧写「帧体检」统计（CHANNEL 日志），0 字结果也带上它 |
| OCR | `OcrResult` / `OcrIpcPayload` 增加 `error`；引擎异常与进程崩溃都会把原因回传主进程，日志显示「识别异常 原因=…」 |
| 引导 | `shouldGuideToAuth` 支持 `BLACK_FRAME`；已有录屏会话时不重复弹 |
| 文案 | 自检页步骤区分「引擎异常」「0 字（帧体检：…）」 |

## 四、用例

| 编号 | 场景 | 期望 |
| --- | --- | --- |
| F-01 | 无障碍截屏返回全黑帧 | 判为单色帧，不进 OCR，转媒体投影 |
| F-02 | 无障碍截屏返回全白/全灰帧 | 同上（旧实现会漏过） |
| F-03 | 正常支付页 | 亮暗跨度 > 100 → 判定非单色，正常 OCR |
| F-04 | 两条通道都返回空白帧 | 报「空白帧」并带统计，摇一摇触发一次录屏授权引导 |
| F-05 | OCR 模型抛异常 | 日志「识别异常 原因=…」，UI 报「OCR 引擎异常」而不是「没找到金额」 |
| F-06 | :ocr 进程内解码失败 | 回传 error，不再只显示「12 秒无响应」 |
| F-07 | 已有录屏会话且仍空白 | 不再反复弹授权（60s 节流 + 会话判断） |

## 五、回归

- 静态检查 `scripts/verify_v1_3_16.py`：328 项（含 T-FRAME / T-OCRERR / T-GUIDE）
- 单测 `FrameSanityTest`：单色判定边界 + 采样下限
- v1.3.15 的下载改造（断点续传/进度/直链优先/资源闸门）全部保留

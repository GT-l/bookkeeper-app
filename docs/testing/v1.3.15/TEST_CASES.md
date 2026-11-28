# v1.3.15 方案与测试用例：下载变慢

## 一、现象

用户反馈「下载变慢了」：检查更新能查到新版本，但下载 88MB 的 APK 体感明显比以前久，
进度条长时间停在 0% 附近。

## 二、根因（实测数据，非猜测）

| 观测项 | 数据 |
| --- | --- |
| v1.3.14 asset 体积 | 92,578,651 字节 = 88.3 MB（v1.0.0 为 87.9 MB，一直在同一量级） |
| api asset 通道 | 首字节 2.06s → 302 到 `release-assets.githubusercontent.com` |
| releases/download 直链 | 首字节 6.60s → **同样 302 到 `release-assets.githubusercontent.com`** |
| 本机实测吞吐 | 12 ~ 22 KB/s |

四个叠加因素：

1. **无断点续传**：单线程 `HttpURLConnection`，`readTimeout = 30s`，且 `saveBody` 的
   `finally` 无条件删除 `.part`。代理一抖动就整包重下 88MB，越下越像「卡住」。
2. **进度粒度太粗**：只回传整数百分比，88MB 下 1% ≈ 0.9MB；慢速网络前几十秒恒为 0%。
   同时每 32KB 回调一次（约 2800 次）跨线程推 StateFlow，主线程被重组淹没。
3. **通道多一跳无收益**：两条通道最终落到同一个 CDN 域名，API 通道只是多一次 302 握手。
4. **识屏抢 CPU**：v1.3.12 起的截图 OCR 兜底会在后台反复跑「截图 + ML Kit 中文模型推理」，
   与单线程下载争抢 CPU / IO。

体积本身不是原因：本版未做 ABI 裁剪（四个架构仍全打包），这是刻意保留的多机型兼容。

## 三、方案（用户指定 B ~ E）

### B. 断点续传

- `.part` 文件存在且长度 > 0 时，请求带 `Range: bytes=<已下字节>-`；
- 响应 206 → 追加写（`FileOutputStream(part, true)`），进度以「已下 + 剩余」为分母；
- 响应 200（服务器忽略 Range）→ 截断重写，避免拼出损坏文件；
- 响应 416 → 断点文件已等于远端大小，校验 `PK` 头后直接收尾，不再重下；
- 网络异常时**保留** `.part`（旧实现无条件删除）；只有「不是 APK」「大小对不上」这类
  确定性失败才删；
- 每轮落盘后 `fd.sync()`，进程被杀也保留进度；
- 续传后大小仍小于 `expectedSize` → 判为不完整，保留断点返回可读错误，下次继续；
- 脏断点（长度 > 远端 size）直接丢弃；
- 已下完但没装成的正式文件（`bookkeeper-<version>-debug.apk`）直接复用，秒完成。

### C. 进度可读

- 新 `DownloadProgress(percent, bytes, totalBytes, bytesPerSecond, etaSeconds)`；
- 200ms 或百分比变化才推一次，速率用四次采样平滑，剩余时间按当前速率折算；
- 关于页显示「下载进度 42% · 37.1 MB / 88.3 MB · 480 KB/s · 剩余 1 分 49 秒」；
- 读取缓冲 32KB → 128KB；`Accept-Encoding: identity` 保证 Range 与长度语义一致。

### D. 通道合并

- `downloadUrl` = `releases/download` 直链（一跳），`fallbackUrl` = API asset（兜底）；
- `browserUrl` 语义随之为「直链优先」；失败信息仍按「通道N（host）：原因」拼接。

### E. 下载期让出 CPU

- 新增 `core/CaptureBudget`：引用计数表示「有前台重活」，`isBusy` 判定，`runHeavy` 保证异常也关闸；
- 下载期间 `runHeavy` 包住 `GithubUpdateChecker.download`；
- `scheduleScreenshotFallback` 在节流判断后、以及真正截图前各判一次闸门，命中则跳过并写日志
  （读无障碍节点这条零成本路径不受影响）；
- 计数不允许掉到负数，避免闸门永久关死。

## 四、用例

| 编号 | 场景 | 期望 |
| --- | --- | --- |
| DL-01 | 下载到 40% 断网，恢复后重试 | 从 40% 继续，不回到 0%（日志 tag=UPDATE 显示「已断点续传」） |
| DL-02 | 下载中断后杀掉 App 进程，重进再检查更新 | `.part` 仍在，从断点续；落盘 fsync 保证不丢已下字节 |
| DL-03 | 断点文件恰好完整（上次只差 rename） | 服务端回 416 → 校验 PK → 直接进入安装，不重下 |
| DL-04 | 服务器不认 Range（回 200） | 截断重写，得到完整文件，不出现拼接损坏 |
| DL-05 | 已下载完成但用户没点安装 | 再次检查更新直接复用该文件，秒完成 |
| DL-06 | 代理返回 HTML 拦截页 | 删除断点并报「不是有效 APK（N 字节）」 |
| DL-07 | 正常下载 88MB | 进度每秒刷新约 5 次，显示字节/速率/剩余时间，不再长时间停在 0% |
| DL-08 | 下载中打开微信触发识屏 | 日志出现「截图兜底跳过：正在下载更新」，节点读取仍工作 |
| DL-09 | 下载结束（成功/失败） | `CaptureBudget` 归零，识屏兜底恢复 |
| DL-10 | 直链不可用（被规则拦） | 自动降级到 API 通道，错误信息仍带每跳 host 与 HTTP 码 |
| A11Y-01 | 恢复 `canTakeScreenshot` 后开启无障碍 | 设置页开关正常；Android 11 以下无该能力，自动退回 MediaProjection |

## 五、回归

- 静态检查：`scripts/verify_v1_3_15.py`（305 项，含 T-DL-01 ~ T-DL-29）
- 单测：`GithubUpdateCheckerTest` 新增速率/剩余时间/进度明细/平均速率 4 例
- 既有识屏、去重、安装器清理逻辑未改动

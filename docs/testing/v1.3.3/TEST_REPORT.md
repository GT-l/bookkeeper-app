# v1.3.3 测试报告

## 自动验证

| 项目 | 结果 |
| --- | --- |
| 静态一致性 `verify_v1_3_3.py` | 179/179 全部 PASS |
| `assembleDebug` | 成功 |
| `testDebugUnitTest` | `T174-F0-E0-S0` |
| CI run | `46043225319`（commit `beeb66e`）`completed success` |
| Release `v1.3.3-debug` | 已发布，APK 92,527,156 字节 |

## 本轮结论

- 更新下载改为双通道（api.github.com asset 通道 + releases/download 备用），手动逐跳跟随重定向，
  失败时返回具体 HTTP 码、域名与可操作提示（Clash 放行 githubusercontent.com / TUN 模式）。
- 下载内容校验 APK 头（PK），挡掉代理返回的 HTML 拦截页；失败清理 `.part` 临时文件。
- 关于页新增「用浏览器下载」与「选本地 APK 安装」两条兜底路径。
- 新增 3 个 JVM 单测（错误提示、浏览器链接回退、下载结果判定），单测总数 174。

## 边界

- Clash / 代理的域名放行需要用户在代理端配置，App 只负责把失败原因和可操作建议显示出来。
- 普通 App 不读取非 TUN 模式的 HTTP 代理；该场景由浏览器下载 / 本地安装兜底。
- 真机用例 TC-01~TC-14 无设备未执行，不记通过。

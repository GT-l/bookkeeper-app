# v1.3.7 测试用例

> 测试对象：`v1.3.7`（versionCode 14）。本轮只修「检查更新下载 APK 失败」。

## 一、自动验证

| 项目 | 命令 | 判定 |
| --- | --- | --- |
| 静态一致性 | `python3 scripts/verify_v1_3_7.py` | 全部 PASS |
| Kotlin 编译 | CI `assembleDebug` | 成功 |
| JVM 单测 | CI `testDebugUnitTest` | `T*-F0-E0-S0` |

## 二、真机用例

| 编号 | 步骤 | 预期 |
| --- | --- | --- |
| TC-01 | 开启 Clash（规则放行 api.github.com，但不放行 CDN）→ 关于页检查更新 | 不再出现「不是有效 APK (1668 字节)」；错误应指向 CDN 域名与放行建议 |
| TC-02 | Clash 放行 `githubusercontent.com` + TUN/全局 → 检查更新 | 正常下载到 100%，自动打开系统安装器 |
| TC-03 | 代理把 asset 接口响应改写成 JSON 元数据 | App 解析出 `browser_download_url` 后继续下载，不直接失败 |
| TC-04 | 下载完成后安装 | 覆盖安装成功，约 2 分钟后安装包自动删除 |

## 三、边界

- 代理是否放行 CDN 域名仍取决于用户 Clash 配置，App 只保证请求方式正确（精确 Accept）并把失败原因说清。
- 公司网络 / 强制 HTTPS 解密的代理可能改写响应，此时走「浏览器下载」或「本地 APK 安装」兜底。

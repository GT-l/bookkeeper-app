# v1.3.7 测试报告

## 自动验证

| 项目 | 结果 |
| --- | --- |
| 静态一致性 `verify_v1_3_7.py` | 205/205 全部 PASS |
| `assembleDebug` | 成功 |
| `testDebugUnitTest` | `T180-F0-E0-S0` |
| CI run | `46101617766`（commit `1355ecd`）`completed success` |
| Release `v1.3.7-debug` | 已发布，APK 92,544,603 字节 |

## 根因与修复

用户真机报错：`通道1 (api.github.com): api.github.com 返回的内容不是有效 APK (1668 字节)`。
1668 字节正是 GitHub asset 接口返回的 JSON 元数据。原因是请求头写成了
`Accept: application/octet-stream, */*`，内容协商退化为 JSON。

修复：
1. asset 接口（`api.github.com/.../releases/assets/{id}`）只发精确的 `Accept: application/octet-stream`，确保 302 到 CDN 直链；
2. 若仍拿到 JSON（代理改写等），解析 `browser_download_url` 后继续下载，不直接失败；
3. 错误提示保留具体 HTTP 码与域名。

## 边界

- CDN 域名（`release-assets.githubusercontent.com`）是否可通仍取决于用户代理配置；
  App 只保证请求方式正确，并在失败时提示放行 `githubusercontent.com` 或改用浏览器下载 / 本地安装。
- 真机 TC-01~TC-04 无设备未执行，不记通过。

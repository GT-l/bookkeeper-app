# v1.3.7 测试报告

> 状态：CI 运行中，结论待回填。

## 自动验证

| 项目 | 结果 |
| --- | --- |
| 静态一致性 `verify_v1_3_7.py` | 待回填 |
| `assembleDebug` | 待回填 |
| `testDebugUnitTest` | 待回填 |
| CI run | 待回填 |
| Release `v1.3.7-debug` | 待回填 |

## 本轮结论

待 CI 回填。

## 根因记录

用户真机报错：`通道1 (api.github.com): api.github.com 返回的内容不是有效 APK (1668 字节)`。
1668 字节正是 GitHub asset 接口返回的 JSON 元数据。原因是请求头写成了
`Accept: application/octet-stream, */*`，内容协商退化为 JSON。改为 asset 接口只发
`application/octet-stream`；并在拿到 JSON 时解析 `browser_download_url` 继续下载。

## 边界

- CDN 域名是否能通仍取决于用户代理配置，App 只保证请求方式正确并给出可操作提示。

# 第三方声明与派生来源

本项目“红果短剧 · Android TV”（仓库 `EDwinpzy/HongGuoTV`）是 Android TV 非官方客户端，按 **GPL-3.0-only** 发布。完整许可证见 [LICENSE](LICENSE)。

## 直接派生来源

1. **fyworld/hongguoTV**（GPL-3.0-only）
   - https://github.com/fyworld/hongguoTV
   - 本仓库以 `kotlin-v1.0.47` 标签（提交 `8430550`）的 `app/`、`core/` 和 Gradle 工程为基础，保留其 GPL 许可与上游归属。
2. **N3urda/hongguoTV**（GPL-3.0）
   - https://github.com/N3urda/hongguoTV
   - 为上述上游提供 TV 工程骨架、焦点导航、卡片布局和内容接口基础。
   - v5 移动端播放通道（`MobilePlaybackStreamSource`、`Signer` v5 签名、`AesV3`、`V5Constants`）移植自提交 `18c37c2a` / 发布 `0.16.5`，按 GPL-3.0-only 使用。
   - 2026-11-23 的播放空响应恢复（Medusa 第 21 字段 `377 -> 312`、7.4.5.32 `preload/v1` 请求、同身份 `video_tab` 回退和 ByteVC2-only 兼容编码协商）参考提交 `ef328f3a` / 发布 `0.16.14`，并按本项目 Kotlin 架构独立接入。
3. **qq294716498/HongguoTV-Phone-TV-NoAds**（仓库未声明许可证）
   - https://github.com/qq294716498/HongguoTV-Phone-TV-NoAds
   - 上游曾参考其接口链路，并在 Kotlin 中独立实现等价逻辑；本项目没有复制其 Flutter/Go 源码。该来源继续按上游说明保留，供追溯。
4. **drpys**（GPL-3.0）
   - https://github.com/woshishiq1/drpys/tree/22261adfa31435e3b3ef5a730b8a2a75bcaf1715
   - 上游的请求签名、媒体 URL/key 处理和加密媒体读取可追溯至该项目的 GPL-3.0 实现。
5. **tsukiyo-zero/hongguoTV**（GPL-3.0-only）
   - 部分 `core/` 源文件保留了该上游的适配注释，涉及账号、手机搜索、播放路由和设备会话等实现。

## 本仓库的修改

- 将用户可见名称改为“红果短剧”，applicationId 改为 `com.edwinpzy.hongguoduanju`
- 增加独立的 TV 图标、320×180 banner 和横屏启动配置
- 精简 TV 设置/版本/许可对话框，移除微信反馈入口
- 禁用到上游 Release 的自动更新，避免跨签名下载和安装
- 增加 TV 兼容性、实机验证与逆向分析说明

仓库没有包含原始商业 APK、抓取到的视频、测试截图或签名材料；`artifacts/`、`docs/upstream/` 和 `.keys/` 均被忽略。

## 内容与商标

“红果短剧”名称、内容封面和相关商标归其权利人所有。本项目仅用于互操作性和个人 TV 使用，不是官方客户端，也不声称获得官方授权。内容通过第三方接口获取；接口、签名、地区网络或版权状态变化可能导致部分内容不可用。

## 其他依赖

- ZXing core：https://github.com/zxing/zxing — Apache-2.0
- Kotlin 标准库：https://github.com/JetBrains/kotlin — Apache-2.0
- AndroidX Media3：https://github.com/androidx/media — Apache-2.0
- OkHttp / Okio：https://github.com/square/okhttp / https://github.com/square/okio — Apache-2.0
- Bouncy Castle：https://github.com/bcgit/bc-java — MIT
- jsoup：https://jsoup.org/ — MIT
- Android desugar_jdk_libs：https://github.com/google/desugar_jdk_libs — GPL-2.0 with Classpath Exception
- org.json（仅 JVM 工具，APK 使用 Android 内置实现）：https://github.com/stleary/JSON-java — 公有领域声明
- JUnit（仅测试）：https://github.com/junit-team/junit4 — EPL-1.0

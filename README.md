# 红果短剧 · Android TV

红果短剧的**非官方** Android TV 客户端，使用 Kotlin、原生 View 和 Media3 构建，界面采用 Apple TV / iOS 风格的深色玻璃面板与紧凑布局。安装一个 APK 即可浏览和播放，不依赖自建服务器。最低支持 Android 7.0（API 24）。

> 本项目不是红果短剧官方产品，也不代表内容平台。内容来自第三方公开接口，可用性可能随接口、签名或地区网络变化而变化。

## 功能

- 遥控器方向键、OK、返回键导航，卡片和按钮提供可见焦点
- 紧凑 7 列内容网格与固定 2:3 竖向矩形海报，搜索结果为紧凑 4 列
- 首页推荐、搜索、总榜/真人剧/漫剧/AI 剧排行榜
- TV 字母键盘与系统输入法搜索，支持拼音首字母联想
- 480P / 720P / 1080P 清晰度选择、暂停、快进/后退、倍速、自动连播和断点续播
- 播放中 `↑` / `↓` 直接切换上一集 / 下一集；长按 OK 或按菜单键打开更多播放面板
- 选集面板、收藏、观看进度、搜索历史和“接着看”
- 红果账号仅使用手机 App 扫码登录；启动和前台使用期间自动同步云端观看记录、收藏与预约，无需手动刷新
- 可选手机扫码输入与局域网记录备份；只在对应窗口打开时临时监听局域网，结束后停止

自动更新已关闭。升级请从本仓库的 Releases 手动下载 APK 覆盖安装。

## 下载与安装

从 [Releases](https://github.com/EDwinpzy/HongGuoTV/releases/latest) 下载签名 APK，在 Android TV 或投影仪上侧载安装。升级时使用与已安装版本相同的签名，否则 Android 会拒绝覆盖安装。

## 构建

环境：

- JDK 17 或更高版本（已在 JDK 21 上验证）
- Android SDK Platform 35、Build Tools 35.0.0
- Gradle 8.14.3 包装器（仓库已包含）

在根目录创建 `local.properties`：

```properties
sdk.dir=C:\\path\\to\\Android\\Sdk
```

Debug 构建：

```powershell
.\gradlew.bat :app:assembleDebug
```

验证与正式构建：

```powershell
.\gradlew.bat :core:test :app:lintVitalRelease :app:assembleRelease
```

产物：

- Debug：`app/build/outputs/apk/debug/app-debug.apk`
- Release：`app/build/outputs/apk/release/app-release.apk`

Release 签名读取根目录的 `keystore.properties`，其 `storeFile` 指向本地 keystore。签名材料已被 `.gitignore` 排除，不能提交。

## 工程结构

- `core/`：纯 Kotlin 内容接口、搜索、签名、播放地址解析和媒体处理；可在 JVM 中测试
- `app/`：Android TV 界面、遥控器焦点、本地记录、Media3 播放器

## 验证与限制

已在 JMGO S621（Android 13，1920×1080）上通过 ADB 注入方向键、OK 和菜单键，验证 iOS 风格首页、短剧网格、搜索与结果、详情、设置、榜单、选集面板和播放器 HUD；海报固定为 2:3 矩形，账号入口与 TextureView 播放链路保持可用。**尚未使用物理遥控器按键验证**；长期播放稳定性、更多 TV 型号和不同芯片解码能力仍待验证。详细记录见 [docs/VERIFICATION.md](docs/VERIFICATION.md)。

应用为派生项目，按 **GPL-3.0-only** 发布。来源、修改和第三方依赖见 [NOTICE.md](NOTICE.md)。

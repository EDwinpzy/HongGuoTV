# 逆向与实现说明

## 目标

将手机端“红果短剧”改造成可用电视遥控器浏览、搜索和播放的 Android TV 客户端，同时保持独立的包名和用户可见名称，避免与原 App 或上游 TV 项目混装。

## 对官方 Android App 的观察

分析对象为本地留存的 `com.phoenix.read` 7.3.9.32 APK，不随仓库分发。

| 项目 | 结果 |
| --- | --- |
| 文件大小 | 142,201,861 bytes |
| SHA-256 | `B8BD20ED2A32350BFBD8EB1982A030670D0F268CCE20ECAF42671362EBEB390C` |
| 代码形态 | 26 个 dex，Compose + Lynx 混合界面 |
| ABI | 仅 `armeabi-v7a` |
| 默认界面 | 竖屏手机布局 |
| S621 表现 | 居中竖屏窄列，没有可见 D-pad 焦点态；注入 OK 可触发部分点击，但方向键可能进入无关页面 |

结论：官方 APK 本身不适合作为电视端交付物，重新封装 WebView 也不能解决手机 UI 的焦点和布局问题。

## 网页接口边界

`https://hongguoduanju.com` 可公开读取部分元数据和 MP4，但页面只暴露前 5 集，第 6 集起返回 404。仅包装网页只能播放少量内容，因此没有采用简单 WebView 壳。

## 实现选择

本仓库选择 GPL-3.0-only 的 `fyworld/hongguoTV`（`kotlin-v1.0.47`，提交 `8430550`）作为 TV 工程基线，保留其 Kotlin + Media3 原生播放链路，并做以下产品化修改：

- 独立 applicationId `com.edwinpzy.hongguoduanju`
- 名称“红果短剧”、横屏 TV 启动、TV 图标和 banner
- 精简设置与许可对话框，移除无关反馈入口
- 关闭上游自动更新，改由本仓库手动发布
- 在 S621 上验证遥控器导航、搜索、详情、播放、暂停、选集和续播

该方案不复制原 App UI，也不分发原 APK；它通过独立的 TV 原生界面实现互操作性。详细实机边界见 [VERIFICATION.md](VERIFICATION.md)。

## 合规说明

本项目只进行互操作性分析和公开接口验证，不包含绕过付费或版权保护的实现。内容版权、接口稳定性和地区可用性由第三方决定，本仓库不保证长期可用。

## 对上游桌面客户端 v1.2.33 的静态分析（2026-11-27）

分析对象为 `yangbin1322/hongguo-player` v1.2.33 的 Windows 发布产物
`hongguo-v1.2.33-windows-amd64.exe`（32,473,600 bytes，SHA-256
`7569E1CC86E2BAFBB89AE690DB8D268648539AFFFE37A0D8991E4397B50E7E5D`，与 Release 资产
digest 一致）。该仓库不含源码，只发布二进制；产物是 Go（模块 `hg-go`，Wails +
WebView2 前端），因此分析方式为静态解析 PE 的 pclntab 恢复 22,659 个函数名
（22,643 个名称有效），再用 capstone 对取流相关函数做定向反汇编。全程未执行该
二进制，未修改任何系统或网络配置。

### 观察到的取流策略

| 行为 | 证据 | 说明 |
| --- | --- | --- |
| tab GET 延迟对冲 | `(*Client).race` 内 `time.NewTicker(250ms)`；`callPlayRace` 先 `mainTarget` 再 `PlayStruggling` 分支 | 首路立即发出，250ms 未出结果再发下一路，首个可用即取消其余 |
| tab 主机顺序 | `videoTabModel` 引用字符串 `api5-normal-sinfonlineb.fqnovel.com` 与 `api5-normal-sinfonlinec.fqnovel.com` | 仅这两家承担 GET；`hl`/`lf` 不在 GET 路由中 |
| 主机健康记忆 | `hostPicker.order` 比较 `time.Since(lastFail) >= 600s` | 成功主机排前，失败主机 600s 内降序 |
| 挣扎窗口 | `PlayStruggling` 比较 `time.Since(最近失败) < 120s` | 120s 内有失败视为“正在挣扎”，直接走备用分支 |
| 备用设备池 | `SpareDevice.register/order/at/use`；`noteTabDevice` 在 `tabDeviceErr` 为真时调用 `SpareDevice.use` | 空响应/110001 是按 `device_id` 生效的屏蔽，主设备被屏蔽时换预注册的备用设备重发 |
| 失败归因 | `tabDeviceErr`：`errors.As` 到业务码 `110001`（`0x1adb1`）或 `errors.Is(context.Canceled)` 取反 | 区分“设备被风控”与“请求被取消”，前者换设备，后者直接返回 |
| 前台预算 | `fetchFallback`/`SpareDevice.register` 均用 `context.WithTimeout` 包裹 | 单次请求有硬上限，不拖垮整个切换预算 |

### 与本仓库的对应修复

- **详情数据源迁移**（本轮实机发现的阻塞点）：网站 `/detail` 页自 2026-11 起不再暴露
  数字 `vid`，只返回前 5 集的 `v1_` 令牌，导致 `detail()` 抛“分集数据异常”、任何剧都
  无法播放。上游 `SeriesDetails` 走的是 App API
  `/novel/player/multi_video_detail/preload/v1`（与播放模型同一 v5 签名链，`biz_param`
  含 `caller_scene`/`detail_page_version`/`need_all_video_definition`/`video_id_type`/
  `screen_width_px` 等 12 个键），响应 `data[seriesId].video_data.video_list[]` 含全部
  集数（实测 785 集，每项有数字 `vid`、`vid_index`、`title`、`duration`、`cover`）。
  本仓库 `ContentRepository.detail()` 改为 App API 优先、网站兜底。
- `MobilePlaybackStreamSource` 的 tab 主机由 `sinfonlineb → hl → lf` 改为上游实测的
  `sinfonlineb → sinfonlinec`；`hl`/`lf` 仍保留在 `PlaybackRoutes` 的 POST 路由中。
- 新增备用设备身份（`PlaybackDeviceSession.spare`），对应上游 `SpareDevice`：主身份
  tab 全空时，用预注册的备用身份重发同一 GET，而不是让整集切换失败。备用身份只在
  前台请求时注册和使用，预取路径不触发。
- 保留既有的 1.2s 对冲延迟、10s ladder 预算与 18s 切集总预算；上游的 250ms 对冲
  与 600s/120s 健康记忆已由 `HedgedCall`、`PlaybackRoutes` 的等价策略覆盖，本轮不重复实现。

### 未覆盖与限制

- 静态分析不等于运行时抓包：上游在真实弱网下的实际时序、备用设备池大小、注册
  时机等仍是推断，未做运行时验证。
- 上游为 Go + WebView2 桌面应用，媒体栈与本仓库的 Android Media3 不同；可迁移的
  是网络层策略（主机顺序、备用身份、失败归因），不是解码/渲染路径。
- 未移植上游的 600s 主机健康记忆与 `PlayStruggling` 120s 窗口的完整语义，只保留
  现有等价实现。

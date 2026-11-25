# TV 兼容性与实机验证

## 验证环境

| 项目 | 值 |
| --- | --- |
| 设备 | JMGO S621 投影仪，Android 13 |
| 分辨率 | 1920×1080 |
| 应用版本 | 1.1.13（versionCode 10113） |
| 应用包名 | `com.edwinpzy.hongguoduanju` |
| 连接方式 | ADB TCP `192.168.1.47:5555` |
| 输入方式 | ADB 注入 `DPAD_UP/DOWN/LEFT/RIGHT`、`DPAD_CENTER`、`MENU` 和 `BACK`；长按确认通过 `/dev/input/event9` 注入 1 秒持续按键；搜索词使用 ADB 文本注入；账号登录由手机红果 App 实际扫码确认 |

## 已验证

| 场景 | 结果 | 证据摘要 |
| --- | --- | --- |
| 冷启动与首页 | PASS | 首页数据加载完成，初始卡片有可见焦点 |
| iOS 风格界面重设计（Debug） | PASS | 黑底蓝/粉静态光晕、浮层玻璃导航、白色焦点胶囊、紧凑标题层级和详情 Hero 均正常；账号入口仍在 |
| 紧凑内容网格（Debug） | PASS | 首页货架、短剧/漫剧/AI/榜单与搜索结果均为 7 列；卡片宽度、间距和页面边距一致 |
| 矩形海报比例（Debug） | PASS | 首页、短剧、榜单、搜索结果、详情季卡与相关剧集统一为固定 2:3 竖向矩形，不再按剩余高度压缩成近方形 |
| 首页信息精简（Debug） | PASS | 移除“发现”、副标题、焦点标题预览和操作提示；货架仅保留“接着看 / 短剧 / 漫剧 / AI剧” |
| 统一加载界面（Release 1.1.2） | PASS | 原生白色圆圈已替换为三点加载；榜单切换时先显示 7 列海报骨架与“正在同步”，焦点保持在榜单按钮；分页加载使用列表底部的同款三点提示 |
| 卡片焦点移动 | PASS | 左右移动会切换白色焦点描边与卡片标题，不再显示额外焦点标题预览 |
| 详情与播放 | PASS | OK 进入详情，再按 OK 启动 1080P 播放 |
| 播放画面（S621 Overlay 兼容） | PASS | 修复前仅声音无画面时，日志持续出现 `HWComposerMMVideo::present: hwc overlay number exceed limit`；播放器改为 `TextureView` 后，Release 包首次播放、从 `douyinTV` 切回、左右快进和下键切集均持续正常出画，日志无 Overlay 超限或播放失败 |
| 播放合成性能 | PASS | Release 包 1080P 播放 30 秒，`gfxinfo` 共 865 帧，Janky 45 帧（5.2%），90 分位 13 ms |
| 弹幕渲染优化（Release 1.1.2） | PASS | 弹幕层由全屏改为顶部 45%，刷新上限 30fps；同机 4 分钟 1080P 播放由 1.1.1 的 Janky 17.50%（中位 18ms、99 分位 42ms）降至 5.04%（8ms、23ms）；弹幕正常显示且无裁切 |
| 解码器失控清理（Release 1.1.2） | PASS | S621 重启前发现旧版播放会话遗留的 `k.tv.bm.preload` 线程连续空转约 10.5 小时、单核 80–94%，`C2BqBuffer` 峰值约 20 万行/3 秒；重启后 1.1.1 连播 12 分钟、1.1.2 连播 4 分钟均无刷屏；退出播放后 `C2BqBuffer` 0 行/3 秒、解码服务 CPU 0% |
| 播放器默认界面（Debug） | PASS | 进入播放器默认只显示视频和弹幕，不显示进度条、标题、集数或操作提示 |
| 播放器缓冲态（Release 1.1.2） | PASS | 首帧准备期间显示居中的深色胶囊 + 三点加载，不再出现系统白色圆圈；首帧就绪后自动隐藏并恢复视频 |
| 播放器菜单（1.1.2 历史） | PASS | 1.1.2 包按 `MENU` 呼出单一合并面板，包含标题、集数、进度、暂停、上一集、下一集、选集和播放设置；该布局已在本轮分支替换，新布局见下节 |
| 播放控件 | PASS | 默认界面 OK 暂停/继续；ExoPlayer 获取音频焦点，播放进度正常 |
| 预加载与上下集切换（1.1.3 Debug） | PASS | 预取窗口为后 20 集；前 3 集按缓冲 10 秒快速补齐，之后要求缓冲 25 秒并每集间隔 8 秒，缓冲时暂停 15 秒。S621 日志连续出现 `Prepared episode ... progress=1/20` 至 `20/20`，自动切集 `preloaded=true`，首帧约 0.83–1.06 秒，90 秒观察期内无丢帧或缓冲停预取日志 |
| 播放中途卡顿（1.1.3 Release） | PASS（改进） | 实测单次 768KB 网络读取常阻塞 1–1.8 秒，预取每集还会额外抢 1MB；改为播放读取 256KB 小块、预取只保留流校验与首块缓存。S621 Release 连续播放 2 分钟无慢读取告警、中途 `STATE_BUFFERING`、丢帧、音频断流或播放失败；Debug 三分钟及 120 秒录屏亦未检出异常冻结 |
| 续播过渡（1.1.3 Debug） | PASS（部分） | 入场改为先显示封面占位，首帧渲染后再淡出，避免等待播放地址时整屏纯黑；S621 冷启动续播首帧仍约 2.9–3.8 秒，黑屏已被封面覆盖，但首帧耗时未压缩，待后续优化 |
| 弹幕去重与完整拉取（1.1.3 Debug） | PASS | 按弹幕文本（忽略空白、零宽字符和大小写）在单集内去重，同时保留原有 ID 去重；移除“耗尽后循环重放旧弹幕”的补位池。S621 日志出现 `Danmaku deduplicated raw=3 unique=2`，并确认 0/30/60/90/120 秒窗口连续拉到片尾，不再只有开头一段 |
| v5 播放通道（2026-11-16） | PASS | 移植 N3urda 0.16.5 的 `POST /novel/player/multi_video_model/player/v1` gzip 签名链路；S621 上《借你一生来还》第 73–81 集真实出画，720P/1080P HEVC 均可播放；最终 Release 1.1.1 覆盖安装后再次播放第 74 集正常出画 |
| 连续播放与自动连播（2026-11-16） | PASS | 约 8 分钟内自动从第 73 集连播到第 81 集；另在 Release 包中从第 88 集连续播到第 93 集，并从《少夫人来自东北3》第 1 集自动连播到第 3 集；切集首帧约 1.1–1.9 秒，未出现 `ERROR_CODE_DECODING_FAILED`；期间使用 ADB 注入暂停、播放、快进和切集 |
| 解码失败学习（2026-11-16） | PASS | 失败记录把 `hvc1/hev1/bytevc1/hevc/h265` 归并为同一 HEVC 家族，并迁移旧 `hvc1|1920|1080` 记录；新增 JVM 单测覆盖 API 档位与 MP4 track 四字码互相命中 |
| 单档 1080P 兼容 | PASS | Debug 包注入 `hevc|1920|1080` 失败记录后，《借你一生来还》第 73 集日志确认 `forced best effort`，随后仍正常出画；全部档位被学习记录阻挡时不再直接报 `NoPlayableQualityException` |
| 双击返回彻底退出（Debug） | PASS | 首页卡片第一次返回仍停留应用，第二次返回回到投影仪桌面；`dumpsys activity activities` 无红果短剧任务记录 |
| 选集侧栏 | PASS | 最新 Debug 包从更多面板进入 iOS 玻璃侧栏，当前集自动获得焦点，集数网格、分组按钮和关闭入口均可操作 |
| 通用选择弹窗 | PASS | 最新 Debug 包中清晰度、倍速、定时和播放设置使用深色玻璃选择面板；当前值有蓝色标记与白色焦点描边 |
| 长列表与按键隔离 | PASS | “播放设置”8 个选项可向下滚动到末项；连续方向键不会穿透播放器或触发切集 |
| 返回栈 | PASS | 播放器返回详情，再返回首页 |
| 断点续播 | PASS | 返回首页后“接着看”显示第 3 集和观看进度 |
| 搜索输入（Debug） | PASS | 使用系统 `EditText` 和系统输入法，保留“搜索”按钮；移除“语音 / 手机输入 / 清空”三个按钮；系统输入法语音键由用户实测可用 |
| 搜索 | PASS | 电视端输入 `BFLC`，出现联想并返回 17 条结果 |
| 搜索结果卡片与焦点（Release 1.1.1） | PASS | 从搜索历史触发“东北阿姨成了他靠山”，返回 18 条结果；7 列卡片宽度与首页一致，焦点停在首张结果卡片，未跳回首页 |
| 榜单首次加载（Release 1.1.1） | PASS | 点击“榜单”首次进入即自动加载 AI剧榜，显示 7 列卡片与热度；加载遮罩正常移除，无“本页没有更多内容”空态 |
| 榜单滚动追加（Release 1.1.1） | PASS | 首屏 20 条后移动焦点到末行，成功追加后续页面，新增卡片继续显示排名与热度 |
| 设置与对话框 | PASS | 设置页、版本信息和 GPL 许可对话框可读，无文字截断 |
| 二维码登录入口 | PASS | 未登录时点击“红果账号”直接打开深色扫码登录弹窗，不显示手机号/验证码入口 |
| 二维码显示与可识别性 | PASS | 白色二维码框完整无裁切，“关闭”按钮初始聚焦；本地离线解码成功，目标域名为 `reading.snssdk.com`，未上传到第三方扫码服务 |
| 手机扫码登录 | PASS | 手机红果 App 扫码并确认后，TV 恢复账号昵称和登录态，设置页账号行立即更新；强制停止并覆盖安装后仍可恢复 |
| 启动自动同步 | PASS | 登录态恢复后自动同步 50 部观看记录和 5 部收藏，无需打开账号页或手动刷新 |
| 前台周期同步 | PASS | 最终 Release 包保持前台时，`17:54:10` 与 `17:55:40` 各自动同步一次，间隔约 90 秒，记录仍为 50 部观看记录、5 部收藏 |
| 账号菜单 | PASS | 只显示“云端继续观看（最近 50 部 · 自动同步）”“退出红果账号”和“同步状态”，无手动刷新入口 |
| 预约自动同步 | PASS | “我的预约”显示“自动同步手机红果 App 的预约 · 已读取 1 部预约记录”，页面无“刷新预约”按钮 |
| 桌面入口 | PASS | 启动器显示“红果短剧”、自定义图标和 `免费短剧 · Android TV` |
| Release 覆盖安装 | PASS | Release APK 已成功覆盖 debug 安装并启动 |
| 干净安装 | PASS | 卸载测试包和原 App 后安装最终 Release，冷启动和首页加载正常 |
| 旧播放接口（2026-11-16 复查） | BLOCKED | 旧 `video_tab` GET 与旧 POST 仍返回 HTTP 200 + 0 字节；已由 v5 签名通道替代，保留为次级回退 |
| 官网完整片源兜底 | PASS（有限） | `fallback-play-info` 对部分公开老剧返回完整未加密 H264 MP4（《少夫人来自东北3》第 1 集 178.966 秒 / 约 17.5 MB）；对新剧常返回 403，因此只作为有限兜底 |

构建验证：`core` 与 `app` JVM 测试共 228 项，0 失败、0 错误、9 跳过（需显式开启的联网探针）；`:app:lintVitalRelease`、`:app:assembleRelease` 和 `:app:bundleRelease` 均通过。最终 APK 版本为 `1.1.2 / 10102`，v2 签名校验通过。

## 2026-11-17 播放器与首页加载改造（代码 + 实机验证）

本轮基于 `codex/player-ui-refresh`，完成代码、JVM 测试、构建验证和 S621 Release 实机验证；设备输入均为 ADB 注入，不是物理遥控器。

本地门禁：`:app:testDebugUnitTest :core:test` 共 238 项，0 失败、0 错误、9 跳过；`:app:assembleRelease` 与 `:app:lintVitalRelease` 通过。

| 场景 | 结果 | 证据摘要 |
| --- | --- | --- |
| 1080P 设置却显示 360P | PASS（单测） | 根因是网页 360P 兜底被静态能力检查视为可用，导致单档 1080P HEVC 尚未真实尝试就被降级。现在主片源先尝试，网页兜底只在全部主档失败后启用；HUD 显示实际档位与编码尺寸，兜底时标注“网页兜底” |
| 选流回归测试 | PASS | `PlaybackSelectionTest` 覆盖 1080P HEVC 优先于 360P 兜底、已学习失败不重复强试、有可用高档不强制、仅兜底档不产生主片源尝试 |
| 播放器侧栏与设置合并 | PASS | Release 包中 `MENU` 打开右侧侧栏，选集与播放设置分页、清晰度档位、定时停止弹窗均可操作；关闭侧栏后恢复播放 |
| 首页标签静默加载 | NOT_RUN | 代码不再用全屏遮罩阻塞标签切换，分类与榜单先显示缓存/内置内容并在后台刷新；需实机确认焦点、滚动位置和请求取消行为 |
| 切集保画面与缓冲策略 | PASS | Release 包第 49→55 集自动连播和手动切集均正常出画；切集首帧约 0.3–0.6 秒；不再使用 `TextureView` |
| Range 续读与有限重试 | PASS（单测/编译） | 合法 206 分段从断点续读，5xx/408/429、超时和 EOF 有限重试，最多 16 次分段请求；实际网络抖动与长播表现未实机验证 |

## 2026-11-17 播放掉帧修复（Release 实机）

验证包为 `1.1.2 / 10102`，v2 签名通过；S621 使用 Release 覆盖安装。输入均为 ADB 注入。

| 场景 | 结果 | 证据摘要 |
| --- | --- | --- |
| 1080P HEVC 连续播放 | PASS | 《少夫人来自东北3》第 49–55 集，`1080x1920@25fps`、`c2.mtk.hevc.decoder`，自动连播加手动切集约 12 分钟；日志无 `Dropped video frames`，帧处理偏移保持正向 |
| 25fps 刷新率匹配 | PASS | 25fps 视频进入播放后活动显示模式为 50Hz；离开播放器时恢复系统默认，返回后重新匹配 50Hz |
| 渲染路径 | PASS | 播放器改为 `SurfaceView`；`dumpsys SurfaceFlinger` 中视频层为 `DEVICE`、应用 UI 层为 `CLIENT`，活动 `HWComposer Layer Count` 为 2 |
| 弹幕与 HUD | PASS | 弹幕开启并持续绘制；首帧后自动收起进度/标题 HUD，仅保留视频和弹幕；暂停时按 OK 可重新显示控制层 |
| 播放侧栏与定时弹窗 | PASS | 打开选集/播放设置侧栏及“定时停止”二级弹窗后关闭，视频恢复播放，日志无 `hwc overlay number exceed limit` |
| 应用切换 | PASS | 从 `douyinTV` 切回后重新创建播放器并出画；显示模式先恢复默认、再按 25fps 切回 50Hz，无持续掉帧 |
| 单档 1080P 兼容 | PASS | 当前集仅提供 1080P 时，即使清晰度上限设为 720P，HUD 标注“兼容资源”并实际播放 1080P；恢复 1080P 上限后正常重载 |

## 2026-11-17 加载态与播放器视觉统一（Release 实机）

验证包仍为 `1.1.2 / 10102`，使用本地 Release 签名覆盖安装；设备输入均为 ADB 注入。

| 场景 | 结果 | 证据摘要 |
| --- | --- | --- |
| 榜单加载骨架屏 | PASS | 从 AI 剧榜按方向键切到真人剧榜后，内容区立即显示 7 列海报骨架、标题占位和三点“正在同步”，无空白页或系统白色转圈；焦点保持在真人剧榜按钮 |
| 播放器缓冲态 | PASS | 从详情启动第 56 集后，黑场阶段显示居中深色胶囊内的白色三点；视频首帧到达后自动消失并正常出画 |
| 播放器侧栏视觉统一 | PASS | `MENU` 打开选集侧栏，深色面板由墨绿/金色改为石墨黑、蓝色选中和白色焦点，与首页、榜单、搜索页一致 |
| 分页加载提示 | PASS（代码共用/编译） | 无限滚动底部改为同款三点“正在加载更多”；本轮未单独触发第二页截图 |
| 首页 / 搜索 / 详情骨架屏 | PARTIAL | 三处都已接入同一骨架组件且完整门禁通过；接口响应过快，本轮仅稳定截到榜单骨架屏，未单独截到另外三处的加载瞬间 |
| 屏幕阅读器与物理遥控器 | NOT_RUN | 加载组件已标记加载语义；本轮只使用 ADB 方向键和截图，未做 TalkBack 或物理遥控器人工验证 |

构建验证：`:app:testDebugUnitTest :core:test` 共 238 项，0 失败、0 错误、9 跳过；`:app:assembleRelease`、`:app:lintVitalRelease` 通过。APK v2 签名校验通过，SHA-256 为 `2B4212B5A1C72164E92558FB4BBEB89DD98FB758A7AC9909CD72730DAA9375FD`。

## 2026-11-17 独立“推荐”标签页与页面头部精简（历史；卡片流已被 2026-11-18 推荐视频流替换）

验证包为 `1.1.2 / 10102`，使用本地 Release 签名覆盖安装；S621 输入均为 ADB 注入。

本地门禁：`:app:testDebugUnitTest :core:test` 共 241 项，0 失败、0 错误、9 跳过；`:app:assembleRelease`、`:app:lintVitalRelease` 通过。APK v2 签名校验通过，SHA-256 为 `2CE2FDD0A5B346BEE386D59300490E7303DAB04E91D96A836B67BA7D391C1000`。

| 场景 | 结果 | 证据摘要 |
| --- | --- | --- |
| 独立推荐入口 | PASS | 顶部导航新增平级“推荐”，顺序为首页、推荐、短剧、漫剧、AI剧、搜索、榜单、收藏、历史、预约、设置；11 个入口在 1920×1080 下完整显示 |
| 账号推荐首屏 | PASS | 登录账号走官方签名推荐流，首屏聚合为 14 部、两行 7 列；会员偏好说明缺失时仍能正常出图 |
| 游客推荐兜底 | PASS（JVM/接口） | 单测覆盖短剧、漫剧、AI剧三类交错混排；匿名接口探针返回 3 部可展示内容。未在已登录设备上清除账号做游客实机验证 |
| 页面头部精简 | PASS | 推荐、榜单、收藏、历史、预约页的重复大标题和说明行已移除；仅保留“换一批 / 刷新榜单 / 检查更新 / 管理收藏 / 管理记录”等功能入口 |
| 推荐直接播放 | PASS | 推荐页选中卡片按 OK 后跳过详情页直接启动第一集或续播位置；S621 输出视频画面，暂停/返回正常 |
| 换一批 | PASS | 按“换一批”后首卡内容变化，重新返回 14 部推荐且焦点落回首卡 |
| 焦点与返回链 | PASS | 推荐卡片 → “换一批” → 顶部“推荐” → 首页；榜单筛选栏与刷新按钮、收藏和历史的操作按钮焦点未被布局精简破坏 |
| 推荐续页 | PARTIAL | 本次账号推荐池在 14 部后未再返回新作品；焦点移到第二行后客户端停止追加并保持界面稳定。其他账号的更长推荐池、游客实机续页和长时间连续刷新未验证 |

## 2026-11-18 推荐视频流启动与播放器频道栏（Debug 实机）

验证包使用当前 `codex/recommend-tab` 工作区构建并覆盖安装到 S621；设备输入均为 ADB 注入。本轮重点修复推荐首屏被旧首页骨架和方向键拦截造成的“卡在推荐页”，并在播放条呼出时显示可切换频道的顶部标签栏。

| 场景 | 结果 | 证据摘要 |
| --- | --- | --- |
| 推荐首屏预热 | PASS | 启动后后台预热账号推荐首屏及首条详情；首次进入日志 `Recommendation start items=6 warmDetail=true elapsedMs=30` |
| 切换推荐不再卡旧页面 | PASS | 切到“推荐”后立即清除旧首页骨架，进入黑场视频层；180ms 截图显示顶部频道栏，约 4.8 秒后视频首帧出画 |
| 推荐重复进入 | PASS | 从短剧返回推荐日志 `Recommendation start elapsedMs=30`，约 2.7 秒后视频首帧出画，不再重复等待推荐接口聚合 |
| 推荐清晰度 | PASS | 推荐播放与预取均使用 1080P；横屏与竖屏片源日志分别确认 `1080P/bytevc1/1920x1080`、`1080P/bytevc1/1080x1920` |
| 播放条顶部频道栏 | PASS | 推荐预览按 OK 进入正常播放条后，顶部显示首页、推荐、短剧、漫剧、AI剧、搜索、榜单、收藏、历史、预约、设置；按上键可进入频道栏 |
| 播放中切换频道 | PASS | 播放条呼出后按上键进入频道栏，聚焦“短剧”“搜索”“设置”并按 OK 均能关闭播放器并进入对应页面；短剧页日志 `Display mode restored to system default`，无播放器画面或音频残留 |
| 推荐预览 MENU 隔离 | PASS | 推荐预览按 MENU 不弹出侧栏；按 OK 进入正常播放器后 MENU 仍打开原选集侧栏 |
| 推荐首屏缓存 | PASS（单测） | `RecommendationFeedTest.fetchedPagesStayWarmAfterPagingAdvances` 验证第二页推进后再次读取第一、二页不再发起网络请求，返回内容不跳页 |
| 推荐无缝换片 | PASS | 预览按 ↓ 后旧画面保持到新媒体就绪，日志显示 `Playback first frame episode=2 preloaded=true reused=true elapsedMs=2230` |

本轮实机输入均为 ADB 注入，不是物理遥控器。长时间连续推荐滑动、弱网重试、更多账号推荐池长度和播放中频道切换后的二次播放仍待验证。

最终 1.1.3 Release 候选复测：账号恢复前会预热当前身份，恢复后按账号身份再预热；从短剧返回推荐日志 `elapsedMs=30`，首帧约 `2.7s`，首次进入首帧约 `4.5s`。完整门禁 `:app:testDebugUnitTest :core:test :app:assembleRelease :app:bundleRelease :app:lintVitalRelease` 通过，242 项测试、0 失败、0 错误、9 跳过；Release APK/AAB 的最终签名摘要见 v1.1.3 GitHub Release。

## 2026-11-18 推荐页仅播放横屏视频（Debug 实机）

验证分支为 `codex/landscape-recommendation`。账号推荐流逐条读取 `video_list[0].vertical`：`true` 的竖屏作品在入队前剔除，未知值保留；剔除后继续翻页凑满首屏，服务端游标仍按原始条目推进。设备输入均为 ADB 注入。

| 场景 | 结果 | 证据摘要 |
| --- | --- | --- |
| 横屏推荐过滤 | PASS（单测） | 新增单测覆盖显式竖屏剔除、未知方向保留、纯竖屏首批继续翻页并保持原始 `offset` |
| S621 连续换片 | PASS | 推荐页连续切换 6 条，日志均为 `1080P/bytevc1/1920×1080`，未出现 `1080×1920` |
| 完整门禁 | PASS | `:app:testDebugUnitTest :core:test :app:assembleRelease :app:bundleRelease :app:lintVitalRelease` 通过；247 项测试、0 失败、0 错误、9 跳过 |

## 2026-11-19 每次切入推荐页刷新新一批（Debug 实机）

验证分支为 `codex/recommend-fresh`。进入推荐标签页时重开账号推荐会话并清空当前批页面缓存，同时保留已看 ID 过滤；原先的启动期首屏预热不再使用，避免把上一次的推荐页缓存重新播放。设备输入为 ADB 注入的点击与返回键。

| 场景 | 结果 | 证据摘要 |
| --- | --- | --- |
| 连续切入刷新 | PASS | S621 连续 3 次从首页切入推荐，日志均为 `Recommendation start items=6 fresh=true`；首批首个 ID 分别为 `7690038018282884120`、`7700436138195962904`、`7699769748476677144`，每次都是不同批次 |
| 旧页缓存不复用 | PASS（单测） | `resetStartsNewBatchWithoutReplayingSeenItems`：`reset()` 后重新请求 `offset=0` 且不带 `session_id`，服务端重复返回的旧条目被已看 ID 过滤，只输出新条目 |
| 稳定性 | PASS | 三次切入及返回均正常，日志无 `FATAL EXCEPTION` / `ANR` |
| 本地回归 | PASS | `:core:test :app:testDebugUnitTest :app:assembleDebug` 通过；247 项测试、0 失败、0 错误、9 跳过 |

限制：本轮只验证登录账号路径；未在已登录设备上清除账号复测游客混排，长时间连续切换与弱网重试未覆盖。

## 2026-11-19 v1.1.4 Release 发布验证

版本升级为 `1.1.4 / 10104`。完整门禁 `:app:testDebugUnitTest :core:test :app:assembleRelease :app:bundleRelease :app:lintVitalRelease` 通过；247 项测试、0 失败、0 错误、9 跳过。Release APK v2 签名校验通过，签名证书 SHA-256 为 `403BDDBE570501BB7B1081DF340D92F4750F8CDA69231C563B1D1BD07A3F13A5`。

| 场景 | 结果 | 证据摘要 |
| --- | --- | --- |
| Release 覆盖安装 | PASS | S621 从 1.1.3 Debug 覆盖安装 `1.1.4 / 10104` Release，应用数据与登录态保留 |
| Release 连续切入 | PASS | 连续 2 次从首页切入推荐，日志均为 `fresh=true`；首批首个 ID 为 `7688736133714299928`、`7704513023825693720`，批次不同，约 6.6 秒进入首屏 |
| Release 稳定性 | PASS | 切入、返回和播放均正常，日志无 `FATAL EXCEPTION` / `ANR` |
| 产物摘要 | PASS | 正式包 APK SHA-256 `FC3A38F504F48C7038F6130F350C6729190D3042AD9A61BD73AC9FF5BCECD689`；AAB SHA-256 `3491364B8B4333751ABA6595275AE596E9716F1279CABFCCCF24DEDE3AD6AD39` |

## 2026-11-19 账号观看历史与播放进度双端同步

验证包基于 `1.1.4 / 10104` 任务分支构建，使用原 Release 签名覆盖安装到 S621；输入仍为 ADB 注入。本轮修复“电视历史和手机历史互不同步”：历史读取改用 `/reading/bookapi/read_history/list/v`，播放进度继续兼容 `/read_progress/list/v1/`；上报时先写 `/read_history/update/v`，再写 `/read_progress/upload/v1/`。

| 场景 | 结果 | 证据摘要 |
| --- | --- | --- |
| 云端完整历史读取 | PASS | 修复前启动日志为 `auto sync history=50`；修复后为 `auto sync history=500 favorites=11`，说明电视已能读取手机历史列表而不只是 50 条进度窗口 |
| 历史与进度双写 | PASS | 播放《大唐：这小子路子太野》后日志为 `upload result=true err=-`；首次实测的错误 `100103` 已定位为 `is_multi_season` 应为整数，修正后上报成功 |
| 云端回读闭环 | PASS | 强制停止并重启后重新同步，首页“接着看”显示该剧第 9 集、`00:19`，确认更新后的集数和秒数已从云端重新取得 |
| 历史合并与去重 | PASS（单测） | 覆盖历史分页、历史与进度较新者合并、保留历史标题/封面、本机较新时上传、同时间戳不反复上传，以及删除 tombstone 阻止复活 |
| 完整门禁 | PASS | `:app:testDebugUnitTest :core:test :app:assembleRelease :app:bundleRelease :app:lintVitalRelease` 通过；255 项测试、0 失败、0 错误、9 跳过 |
| 签名 | PASS | APK v2 签名通过，证书 SHA-256 为 `403BDDBE570501BB7B1081DF340D92F4750F8CDA69231C563B1D1BD07A3F13A5` |

限制：本轮没有直接操作手机红果 App 的“观看历史”页面。已确认电视读取完整云端历史、历史/进度双写成功且重启后可从云端回读；手机端最终展示仍需用户在同一账号下人工核对。物理遥控器未使用，按键均为 ADB 注入。

## 2026-11-19 v1.1.5 Release 发布验证

版本升级为 `1.1.5 / 10105`。完整门禁 `:app:testDebugUnitTest :core:test :app:assembleRelease :app:bundleRelease :app:lintVitalRelease` 通过；255 项测试、0 失败、0 错误、9 跳过。

| 场景 | 结果 | 证据摘要 |
| --- | --- | --- |
| Release 覆盖安装 | PASS | S621 从 `1.1.4 / 10104` 覆盖安装 `1.1.5 / 10105` Release，应用数据与登录态保留；冷启动约 0.7 秒进入首页 |
| 完整历史同步 | PASS | 启动后日志为 `auto sync history=500 favorites=12`，历史页正常显示云端观看记录及集数、进度 |
| 播放与进度双写 | PASS | 从历史页播放《千年待我归》第 1 集，实际出画；日志 `upload result=true err=-`，进度持续上报到约 49 秒 |
| 稳定性 | PASS | 启动、同步、播放与返回过程中无 `FATAL EXCEPTION` / `ANR` |
| 签名 | PASS | APK v2 签名通过，证书 SHA-256 为 `403BDDBE570501BB7B1081DF340D92F4750F8CDA69231C563B1D1BD07A3F13A5` |
| 产物摘要 | PASS | APK SHA-256 `D2C30FA726EA9ECFE571DA32B74D12162878FA73894ADD707A3190F739B58266`；AAB SHA-256 `4286D9AB9C5914464D9E59DC9FDB96259D0EEC6544D201CE2DB82CD5AA4E3137` |

限制：本轮仍未直接操作手机红果 App 的“观看历史”页面，手机端最终展示需在同一账号下人工核对；S621 输入均为 ADB 注入，未使用物理遥控器。

## 2026-11-20 v1.1.6 Release 发布验证

版本升级为 `1.1.6 / 10106`。完整门禁 `:app:testDebugUnitTest :core:test :app:assembleRelease :app:bundleRelease :app:lintVitalRelease` 通过；255 项测试、0 失败、0 错误、9 跳过。

| 场景 | 结果 | 证据摘要 |
| --- | --- | --- |
| 列表管理按钮移除 | PASS | 收藏页不再显示“检查更新”和“管理收藏”，历史页不再显示“管理记录”；顶部操作栏整体移除，海报网格直接从导航栏下方开始 |
| 选中预览移除 | PASS | 历史、收藏及各列表页焦点移动到焦点居中后，均不再显示剧名、观看进度和分类预览区域 |
| Release 覆盖安装 | PASS | S621 从 `1.1.5 / 10105` 覆盖安装 `1.1.6 / 10106` Release，应用数据与登录态保留 |
| 播放回归 | PASS | 从历史页播放《阳谋正道：开局入宫，从内廷开始颠覆王朝》第 1 集实际出画，日志 `Playback first frame ... elapsedMs=3414`、`Playback ready` |
| 稳定性 | PASS | 启动、切换列表、移动焦点、播放和返回过程中无 `FATAL EXCEPTION` / `ANR` |
| 签名 | PASS | APK v2 签名通过，证书 SHA-256 为 `403BDDBE570501BB7B1081DF340D92F4750F8CDA69231C563B1D1BD07A3F13A5` |
| 产物摘要 | PASS | APK SHA-256 `BB1D9A5F90496DBE6027E2F344D80C8ABC84F0AD10473E189F929A0910FB60A7`；AAB SHA-256 `CB431C90164833EB24DD76381A2586C2A4A68A4711A7299D1143F27A5BFA3` |

限制：S621 输入均为 ADB 注入，未使用物理遥控器；收藏卡片底部的“尚未检查更新”是内容状态，不是已移除的顶部操作按钮。

## 2026-11-20 v1.1.7 Release 发布验证

版本升级为 `1.1.7 / 10107`。完整门禁 `:app:testDebugUnitTest :core:test :app:assembleRelease :app:bundleRelease :app:lintVitalRelease` 通过；255 项测试、0 失败、0 错误、9 跳过。

| 场景 | 结果 | 证据摘要 |
| --- | --- | --- |
| 集数分组宽度 | PASS | 分组按钮改为按内容自适应宽度；超出侧栏宽度时改为横向滚动，焦点切换时自动滚动到可见位置 |
| Release 覆盖安装 | PASS | S621 从 `1.1.6 / 10106` 覆盖安装 `1.1.7 / 10107` Release，应用数据保留 |
| 稳定性 | PASS | 启动后无 `FATAL EXCEPTION` / `ANR` |
| 签名 | PASS | APK v2 签名通过，证书 SHA-256 为 `403BDDBE570501BB7B1081DF340D92F4750F8CDA69231C563B1D1BD07A3F13A5` |
| 产物摘要 | PASS | APK SHA-256 `32897950ECF3D58DDAF77690FB8BB639ACF9352167D1471F85FE7515B54F3A0E`；AAB SHA-256 `0C6A58BAFF8FC9483AADCD01AAC87D98071E1FC8D1521E72F96F27FA8E590CD5` |

## 2026-11-21 v1.1.8 播放超时与 S621 黑屏修复（Release 实机）

版本升级为 `1.1.8 / 10108`。验证分支为 `codex/playback-connection-timeout`，验证包使用原 Release 签名覆盖安装到 S621。

根因有两层：

- 《阳谋正道》第 37 集只返回单一 `1080P/bytevc1/1922x1080` 档位。解码器实际支持 1080P，但旧的持久化失败记录按原始编码宽度 `1922` 保存，后续选流又按 `1920` 查询，单档被全部过滤后抛出 `NoPlayableQualityException`（R8 映射为 `J1`）；网页兜底同时返回 HTTP 403。
- S621 的 `SurfaceView` 会被平台 HWComposer Overlay 路径丢弃，播放器仍在走时只有声音、没有可见视频层。

本轮修复：

- 播放 API、设备注册、网页兜底和媒体分段请求各自限时（播放通道 8 秒、媒体分段 15 秒），单个卡死线路不再耗尽整轮恢复预算。
- 媒体分段可对 Socket/EOF/异常正文截断续读；取消请求不再被当作网络故障或继续尝试其他档位；主链路真实网络错误不再被误报成“无可播放清晰度”。
- `1922x1080`、`1282x720`、`962x540` 等对齐尺寸按 `1920x1080`、`1280x720`、`960x540` 探测解码能力并共享同一条失败记录。
- 同一档位首次解码失败只进入观察期，重复失败才持久化拒绝；连续播放 15 秒后，成功记录会清除该档位的旧失败记录，避免“首帧能放、10 秒后解码失败”的档位被误判为健康。
- 某集的主档位全部被过滤时，仍允许最优档位进行一次真实 ExoPlayer 尝试；只有真实解码失败才进入恢复流程。
- S621 播放视图改回 `TextureView`，避免 Overlay 层被系统丢弃导致黑屏。

| 场景 | 结果 | 证据摘要 |
| --- | --- | --- |
| 单一 1080P 档位 | PASS | 第 37 集选流为 `1080P/bytevc1/1922x1080`，`Playback first frame episode=37 ... elapsedMs=1135`；持续播放后旧失败记录被 `Cleared stale decoder failure after successful frame: hevc|1920|1080` 自动清除 |
| 重复验证 | PASS | 重新进入第 37 集，`Playback candidates ...=true`，首帧 `elapsedMs=1310`，画面正常 |
| 1080P 恢复播放 | PASS | 首页“接着看”第 38 集从 19.8 秒恢复，首帧 `elapsedMs=2901`，画面正常 |
| 连续切集 | PASS | 第 37→38→39 集连续切换，后两次均 `preloaded=true reused=true`，首帧分别为 `233ms`、`266ms` |
| 远距离拖动 | PASS | 拖到约 74 秒触发 `STATE_BUFFERING`，`stalledMs=1484` 后恢复；继续拖到 91.9 秒，`stalledMs=795` 后恢复，未出现播放失败 |
| 黑屏回归 | PASS | `SurfaceView` 版本在同一集上仅声音无画面；改为 `TextureView` 后，恢复播放、单档 1080P、连续切集和拖动后 ADB 截图均持续显示视频画面 |
| 完整门禁 | PASS | `:app:testDebugUnitTest :core:test :app:assembleRelease :app:bundleRelease :app:lintVitalRelease` 通过；263 项测试、0 失败、0 错误、9 跳过 |
| 签名与产物 | PASS | APK v2 签名通过，证书 SHA-256 `403BDDBE570501BB7B1081DF340D92F4750F8CDA69231C563B1D1BD07A3F13A5`；最终 APK/AAB 摘要见 v1.1.8 GitHub Release |

限制：S621 输入均为 ADB 注入，未使用物理遥控器；ADB `screencap` 与日志已确认视频层出画，但未用外部相机对投影幕布做独立人工拍摄确认。

## 2026-11-22 v1.1.15 选集分组平铺与播放条精简（Debug 实机）

版本升级为 `1.1.15 / 10115`。验证分支为 `codex/episode-flat-grid`，验证包为同签名 Debug 构建，覆盖安装到 S621。

本轮改动：

- 详情页选集的 20 集分组标签改为一行横向平铺，点选即切换到该组集数；移除“上一组 / 下一组 / 跳转集数”。
- 分组按钮宽度按文字自适应，不再按等分撑满整行。
- 播放条移除常驻的“当前 / 总时长 · 分辨率 · 编码尺寸 · 片源码率”信息，保留片名、简介、进度、播放/暂停和右侧集数；缓冲、结束、定时停止等临时状态提示不受影响。
- 修正选集焦点映射：分组标签左右移动在组之间循环，第一行集数向上回到当前选中的组。

| 场景 | 结果 | 证据摘要 |
| --- | --- | --- |
| 分组平铺 | PASS | 《灵气崩塌》（45 集）显示“第 1—20 集 / 第 21—40 集 / 第 41—45 集”三个标签同一行，无上一组、下一组、跳转集数 |
| 分组切换 | PASS | 《嫌疑人的秘密2藏金生死线》（88 集）选中“第 41—60 集”后，下方正确显示 41–60，焦点落在第 41 集 |
| 按钮宽度自适应 | PASS | 分组标签按文字宽度收紧排列，不再等分占满整行 |
| 播放条精简 | PASS | 播放《嫌疑人的秘密2藏金生死线》第 43 集，OK 呼出播放条后不再出现“当前时长 / 总时长 · 1080P · 1920×1080 · 片源码率”一行 |
| 编译 | PASS | `:app:compileDebugKotlin`、`:app:assembleDebug` 通过 |
| 回归测试 | PASS | `:core:test` 通过 |
| 实机焦点 | PARTIAL | 分组标签与集数的上下左右焦点移动已用 ADB 注入按键验证；未使用物理遥控器 |

限制：分组切换仍会重建详情页，超长剧集可能出现轻微闪烁；本轮只验证了 45 集和 88 集两部剧，未覆盖全部 1000 集和不同 TV 型号。S621 输入均为 ADB 注入。
## 2026-11-21 推荐预览与正常播放按键分离（Debug 实机）

验证分支为 `codex/recommendation-player-mode`，S621 覆盖安装 `1.1.13 / 10113` Debug 包。设备输入均为 ADB 注入，不是物理遥控器。推荐预览采用专用方向键映射：`↑/↓` 换片，`←/→` 按顶部标签顺序切页，不依赖焦点寻路。

| 场景 | 结果 | 证据摘要 |
| --- | --- | --- |
| 推荐预览常显界面 | PASS | 每部推荐的预览都同时显示顶部“首页/推荐/短剧/…/设置”菜单和底部片名、单行简介；截图中没有播放/暂停按钮、时间或进度条 |
| 推荐预览上下换片 | PASS | 预览按 `↓` 后从《少夫人来自东北3》切到《穿越八零，我把娘俩宠成宝》，顶部菜单与底部片名/简介立即随新片更新，没有把白色焦点交给顶部标签 |
| 推荐预览左右切页 | PASS | 推荐预览按 `←` 直接回首页；重新进入推荐后按 `→` 直接进入“短剧”，按顶部标签顺序切页，而不是移动菜单焦点 |
| OK 进入正常播放器 | PASS | 按 OK 后出现片名、集数、播放/暂停按钮、时间和进度条，流不重新打开 |
| 正常播放器隐藏顶栏 | PASS | 顶栏仅在推荐预览出现；按 OK 进入第 1 集、收起后重新呼出控制条的第 2 集，以及按 `↓` 切到的第 3 集，均只显示播放按钮、时间和进度条，顶部“首页/推荐/…”标签栏完全隐藏 |
| 正常播放器上下切集 | PASS | 推荐确认后的《穿书反派成了女主白月光》先按 `↓` 到第 2 集，再按 `↑` 回第 1 集；日志依次为 `Playback first frame episode=2`、`episode=1`，未切换到其他推荐 |
| 界面无返回按钮 | PASS（代码/界面） | 详情页标题、收藏/历史合集页、推荐错误页和播放错误页均不再渲染返回按钮；手机号登录失败弹窗的恢复动作改为“关闭”。物理遥控器 BACK 键仍执行原有返回/退出逻辑 |
| 编译与单测 | PASS | `:app:compileDebugKotlin :core:test` 通过 |
| 稳定性 | PASS | 推荐进入、换片、确认播放和上下切集期间无 `FATAL EXCEPTION` / `ANR` |

## 2026-11-21 v1.1.11 播放取流超时修复（Release 实机）

版本升级为 `1.1.11 / 10111`。验证分支为 `codex/playback-timeout-diagnosis`，Release 包使用原签名覆盖安装到 S621。

根因不是单纯的 CDN 超时：旧取流接口在服务端软拦截下返回 HTTP 200 + 0 字节；旧逻辑把它当作可重试超时，再叠加多条通道、身份轮换和媒体读取重试，最终形成数分钟等待。当前有效入口为：

`GET https://api5-normal-sinfonlineb.fqnovel.com/reading/bookapi/video_tab/multi_video_model/v1/`

本轮修复：

- `video_tab` 使用 v5 carrier（`x-khronos` / `x-helios` / `x-medusa`），并把 v5 Signer 提升为进程级共享实例；主播放和预加载不再表现为两个进程身份。
- 取流链改为 `video_tab` 优先，共享约 10 秒前台预算；空响应最多刷新一次匿名身份，普通超时不会被包装成确定性失败。
- 匿名注册超时不再错误设置线程中断；一次注册超时不会再让后续请求被判定为取消。
- 媒体分段支持短 `206` 续读、302 跟随、单请求 6 秒和单逻辑 Range 12 秒预算；总预算临近结束时，单请求超时同步缩短。
- 首帧 watchdog 绑定当前播放器；迟到的旧播放器事件不会清掉新播放器的 watchdog，暂停恢复后会重新计时。
- 网站完整 H264 兜底失败时，不丢弃已签名且设备可解码的 HEVC 档位；避免把可播放剧集误报为 `source-unavailable`。

| 场景 | 结果 | 证据摘要 |
| --- | --- | --- |
| 冷启动“接着看” | PASS | 第 351 集 `1080P/bytevc1`，首帧 `elapsedMs=3794`；随后自动续播第 352 集首帧 `elapsedMs=1000` |
| 目标第 350 集 | PASS | `1080P/bytevc1/1922x1080`，首帧 `elapsedMs=1329`，画面与字幕正常；后续自动推进到第 352、353 集，首帧分别为 `185ms`、`178ms` |
| HEVC-only + 网页 403 | PASS | 第 366 集 v5 仅返回 `1080P/bytevc1/1920x1080`，网站完整片源返回 403；应用继续播放 HEVC，首帧 `elapsedMs=1332`，不再显示 `source-unavailable` |
| 暂停恢复 | PASS | ADB 注入暂停 3 秒再恢复，画面继续出画，无 watchdog 误报 |
| 稳定性日志 | PASS | 第 350–353 集连续播放及第 366 集复测后，日志无 `FATAL EXCEPTION`、`ANR`、`source-unavailable`、`NoPlayable`、watchdog 误报或播放失败 |
| 完整门禁 | PASS | `:app:testDebugUnitTest :core:test` 共 269 项，0 失败、0 错误；`:app:assembleRelease`、`:app:bundleRelease`、`:app:lintVitalRelease` 通过 |
| 签名与产物 | PASS | APK v2 签名通过，证书 SHA-256 `403BDDBE570501BB7B1081DF340D92F4750F8CDA69231C563B1D1BD07A3F13A5`；APK SHA-256 `0C4679593E46A74A20E319A1FD6BCDF2559A51AA26A86AC7FF0121142D3962AC`；AAB SHA-256 `A3444324569A4FC5F6B9E49D250D4A1774FC576C03258EA56E63D87AEC437554` |

限制：S621 输入均为 ADB 注入，未使用物理遥控器；未完整播放全部 1000 集，也未覆盖数小时连续播放、所有网络环境和全部 TV 型号。分辨率/选集弹窗的“定位”按钮在 S621 上可能被挤出可视区域，这是非播放 UI 问题，本轮未修。

## 2026-11-21 v1.1.12 画质标注与真实码率修正（Release 实机）

版本升级为 `1.1.12 / 10112`。验证分支为 `codex/playback-timeout-diagnosis`，Release 包使用原签名覆盖安装到 S621。

本轮不是简单地把 1080P 标签改成更高数值，而是修正了“标签与真实片源不一致”的根因：

- 服务端存在 `definition=1080p`、但编码尺寸只有 `854×480`、码率 `323084 bps` 的片源。旧逻辑优先信任 `definition`，因此 HUD 会显示 1080P，实际解码却只有 480P。
- 即使是真实的 `1922×1080` 片源，当前剧集部分集数也只有约 `0.38–0.59 Mbps`，明显低于常见高码率 1080P，因此主观上会显得偏软。
- 新逻辑优先用 `vwidth` / `vheight` 计算实际清晰度，只有尺寸缺失时才回退到服务端 `definition`；同时解析 `real_bitrate` / `bitrate` 和帧率，同一分辨率优先选择高码率源。
- 播放条和播放侧栏现在显示真实解码尺寸与片源码率，例如 `1080P · 1922×1080 · 片源码率 508 kbps`；设置项改为“分辨率上限”，避免把分辨率标签误当成最终锐度。

| 场景 | 结果 | 证据摘要 |
| --- | --- | --- |
| 真实 1080P 播放 | PASS | 第 391 集选流 `1080P/bytevc1/1922x1080/508371bps`，首帧 `elapsedMs=1673`；ADB 截图中的播放条显示 `1080P · 1922×1080 · 片源码率 508 kbps`，播放侧栏同步显示相同参数 |
| 片源无 1080P 档 | PASS | 第 395 集服务端只返回 `480P/bytevc1/854x480/337670bps` 及更低档位；最终 1.1.12 播放条如实显示 `480P · 854×480 · 片源码率 338 kbps`，不再用设置上限冒充实际片源 |
| 服务端标签错标 | PASS | 实测同一剧集存在 `definition=1080p`、`854×480`、`323084bps` 的片源；新增单测确认新逻辑将其归类为 `480P / qualityValue=480`，不再显示为 1080P |
| 同分辨率高码率优先 | PASS | 新增单测用两条 1080P 片源验证：`2000000bps` 排在 `800000bps` 前，且兼容缺少码率字段的旧片源 |
| 预加载切集 | PASS | 第 391→392 集使用已预加载片源，首帧 `elapsedMs=224`；最终 1.1.12 包第 395→396 集首帧 `elapsedMs=297`，均无黑屏、超时或解码失败 |
| 完整门禁 | PASS | `:app:testDebugUnitTest :core:test` 共 273 项，0 失败、0 错误、9 跳过；`:app:assembleRelease`、`:app:bundleRelease`、`:app:lintVitalRelease` 通过 |
| 签名与产物 | PASS | APK v2 签名通过，证书 SHA-256 `403BDDBE570501BB7B1081DF340D92F4750F8CDA69231C563B1D1BD07A3F13A5`；APK SHA-256 `810D52435977309F9A06CC4D29375623659495820A314EEFC6D23B83AAA23F56`；AAB SHA-256 `BBEBEF489BA980B49CC8FDEB55546D8B5C6F6164DD664BC1C734A04C9023C49E` |

限制：服务端无法提供更高码率片源时，App 不会用锐化滤镜伪造“更清晰”；同一个 1080P 标签仍可能只有约 0.5 Mbps。连续探测期间匿名播放身份曾短暂返回空响应，等待约 60 秒后同一集恢复并真实出画，这是服务端额度/空响应限制，不是本轮播放超时回归。S621 输入均为 ADB 注入，未使用物理遥控器。

## 2026-11-21 v1.1.13 推荐预览与正常播放器分离（Release 实机）

版本升级为 `1.1.13 / 10113`。验证分支为 `codex/recommendation-player-mode`，已合入 `v1.1.12` 的播放取流、超时和真实码率修复。Release 包使用原签名覆盖安装到 S621，应用数据与登录态保留；设备输入均为 ADB 注入，不是物理遥控器。

推荐预览采用专用方向键映射：`↑/↓` 换片，`←/→` 按顶部标签顺序切页，不依赖焦点寻路；`OK` 进入正常播放器。正常播放器显示播放按钮、时间和进度条，顶部频道标签始终隐藏，`↑/↓` 切换上下集。

| 场景 | 结果 | 证据摘要 |
| --- | --- | --- |
| 推荐预览常显界面 | PASS | 推荐预览同时显示顶部“首页/推荐/短剧/…/设置”菜单和底部片名、单行简介；截图中没有播放/暂停按钮、时间或进度条 |
| 推荐预览上下换片 | PASS（Debug） | 预览按 `↓` 后从《少夫人来自东北3》切到《穿越八零，我把娘俩宠成宝》，顶部菜单与底部片名/简介立即随新片更新，没有把白色焦点交给顶部标签 |
| 推荐预览左右切页 | PASS（Debug） | 推荐预览按 `←` 直接回首页；重新进入推荐后按 `→` 直接进入“短剧”，按顶部标签顺序切页，而不是移动菜单焦点 |
| Release 预览与确认 | PASS | S621 Release 预览《夜晚的答案》显示顶部标签和底部片名/简介；按 OK 后仍为《夜晚的答案》第 1 集并正常出画，没有串片 |
| 正常播放器隐藏顶栏 | PASS | 按 OK 后出现片名、集数、播放按钮、时间和进度条，顶部标签栏消失；Debug 实机另行验证了控制条收起/重呼与第 3 集切换时顶栏均保持隐藏 |
| 正常播放器上下切集 | PASS（Debug） | 推荐确认后的《穿书反派成了女主白月光》先按 `↓` 到第 2 集，再按 `↑` 回第 1 集；日志依次为 `Playback first frame episode=2`、`episode=1`，未切换到其他推荐 |
| 界面无返回按钮 | PASS（代码/界面） | 详情页标题、收藏/历史合集页、推荐错误页和播放错误页均不再渲染返回按钮；物理 BACK 键仍执行原有返回/退出逻辑 |
| Release 覆盖安装 | PASS | S621 覆盖安装 `1.1.13 / 10113` Release 成功，应用数据与登录态保留 |
| 完整门禁 | PASS | `:app:testDebugUnitTest :core:test :app:assembleRelease :app:bundleRelease :app:lintVitalRelease` 通过；273 项测试、0 失败、0 错误、9 跳过 |
| 签名与产物 | PASS | APK v2 签名通过，证书 SHA-256 `403BDDBE570501BB7B1081DF340D92F4750F8CDA69231C563B1D1BD07A3F13A5`；APK SHA-256 `C09F56745CBD74B18B4467DF14A2924AA4835D5F79D652CB48E4B8589EFAB16E`；AAB SHA-256 `DABAD8F7BE0E6E0A62DAAF68AB92A852E140E8E1F5830F84319967411BC61C4D` |

限制：Release 仅完成推荐预览、OK 进入正常播放器和首次控制条显示验证；随后用户要求停止测试，未继续复跑 Release 控制条重开和上下切集。测试期间系统投屏应用 `com.jmgo.holacast.x` 曾抢占前台并显示其自身的“打开链接失败”，不是红果短剧崩溃；红果短剧进程保持运行，日志无 `FATAL EXCEPTION` / `ANR`。

## 2026-11-21 v1.1.14 弹幕拉取恢复（Release 实机）

版本升级为 `1.1.14 / 10114`。验证分支为 `codex/recommendation-player-mode`，Release 包使用原签名覆盖安装到 S621，应用数据与登录态保留；设备输入均为 ADB 注入。

本轮定位到两个叠加问题：

- 弹幕循环连续失败 5 次后会直接退出，且失败路径没有日志；短暂网络抖动或服务端限流后，本集后续弹幕会永久停止。
- 弹幕请求直接复用共享 `OkHttpClient`，没有单次调用截止时间；连接/TLS 路由被挂住时会在 `execute()` 内长时间等待，重试逻辑根本得不到异常。

修复为：

- 每次弹幕请求设置 8 秒调用超时，卡住的请求会进入失败路径。
- 失败后按 2 秒起步、最高 15 秒的退避持续重试，不再累计 5 次后退出；成功一页后重置失败计数。
- 增加开始、线程启动、重试、窗口完成和到达片尾日志，便于下次直接区分“服务端无数据”和“客户端未拉取”。
- 从推荐预览按 OK 进入正常播放器时，如果拉取任务已经结束，会补启动一次。

| 场景 | 结果 | 证据摘要 |
| --- | --- | --- |
| 服务端数据核对 | PASS | 《朝夕至爱》第 37 集 0–60 秒接口返回 180 条；《穿书反派自救指南》第 7 集仅 4 条，时间点为 3.4s、12.1s、14.9s、19.9s |
| 稀疏弹幕解释 | PASS | 第 7 集播放到 30 秒时 4 条均已飘过，因此画面为空并非拉取失败；客户端不会循环重放旧评论 |
| Debug 弹幕渲染 | PASS | 《穿书反派自救指南》播放中截图可见“好熟悉的剧情”“后面把男主当成拯救他的神”等滚动弹幕 |
| Release 弹幕渲染 | PASS | S621 覆盖安装 `1.1.14 / 10114` 后播放《朝夕至爱》，截图可见“这家庭也太和睦了吧，无条件赞”“瞬间放心了”等滚动弹幕 |
| 覆盖安装 | PASS | Release 包覆盖安装成功，`versionName=1.1.14`、`versionCode=10114`，应用数据与登录态保留 |
| 完整门禁 | PASS | `:app:testDebugUnitTest :core:test :app:assembleRelease :app:bundleRelease :app:lintVitalRelease` 通过；273 项测试、0 失败、0 错误、9 跳过 |
| 签名与产物 | PASS | APK v2 签名通过，证书 SHA-256 `403BDDBE570501BB7B1081DF340D92F4750F8CDA69231C563B1D1BD07A3F13A5`；APK SHA-256 `E5A901BF60A629BF04726005D8093B573D4E55B57F27D2FDDA3DE2906757D5EE`；AAB SHA-256 `CFC6D59B3F629FCD97B8E3223972BC547F885C79E288FDDAE5381576218167A8` |

限制：上游每集弹幕数量差异很大，部分集数只有个位数甚至 0 条；当前设计不会循环重放旧评论，也不会生成虚假弹幕。S621 输入均为 ADB 注入，未使用物理遥控器。

## 2026-11-22 弹幕分页游标修复（本地）

用户反馈弹幕只在开头出现。本轮仅检查本地代码和公开接口，未连接投影仪，未安装或发布新版本。

- 旧请求每页都发送空 `cursor`，只依赖 `next_query_danmaku_list_time`；该时间跳到片尾时会提前结束，无法继续真实分页。
- 现在同时解析并传递 `common_list_info.cursor` / `has_more`，时间游标停滞时只前进 1 秒并保留列表游标；由服务端明确返回 `has_more=false` 后结束。
- 本地公开接口样例验证了首页与后续页的服务端游标续取。截图对应《妹妹重生后当众让我抢婚》第 2 集的服务端计数只覆盖 0–30、30–60、60–90 秒；两分钟后是否有可见弹幕仍取决于该集真实评论，不能通过重放旧弹幕伪造。
- 本地新增状态机单测，覆盖跨 120 秒续页、时间提示跳到片尾但仍有分页、时间游标停滞、明确终页与预读节流。实机画面和长时间播放未验证。

## 2026-11-22 弹幕后台线程与连播修复（Debug 实机）

- 根因是弹幕后台线程直接读取 Media3 的 `duration/currentPosition`，触发 `Player is accessed on the wrong thread`，任务立即退出；切换集数时该问题会再次出现。
- 主线程保存真实时长和播放位置快照，后台只读快照；新集在播放器 READY 前等待，不再使用默认 600 秒或上一集的播放位置。
- 切集开始即取消旧任务，清理工作线程残留中断状态，并增加后台异常日志。播放器 HUD、历史与云端进度忽略 Media3 的 `TIME_UNSET`。

| 场景 | 结果 | 证据摘要 |
| --- | --- | --- |
| 单集后续分页 | PASS | 第 6 集真实时长 325 秒，弹幕按播放进度持续拉取至片尾 |
| 自动连播 | PASS | 第 7 集预加载切集后重新从 0 秒取弹幕，持续至 240–267 秒终页；第 8 集再次正常取到 90–120 秒窗口 |
| 实机弹幕画面 | PASS | 第 7、8 集截图均显示多条真实滚动弹幕；连续自然播放约 9 分钟 |
| 构建与回归 | PASS | `:core:test :app:testDebugUnitTest :app:assembleDebug` 通过 |
| 版本发布 | NOT_RUN | 仅覆盖安装 `1.1.14 / 10114` Debug 验证包；未构建或发布新 Release |

设备输入为 ADB 注入，不是物理遥控器。

## 未验证或有限制

| 项目 | 状态 | 说明 |
| --- | --- | --- |
| 物理遥控器按键 | NOT_RUN | 本次使用 ADB 注入按键；S621 是实体设备，但没有人工按物理遥控器 |
| 投影画面人工确认 | PARTIAL | ADB 截图、SurfaceFlinger 合成状态和播放日志均正常，但本轮没有独立人工/相机观察投影输出；`screencap` 不能替代物理画面确认 |
| 二维码取消与过期 | NOT_RUN | 最终 Release 验证时账号已登录；为避免清除已确认的登录态，未在最终包上重复执行退出后取消/等待过期 |
| 长时间连续播放 | PARTIAL | 2026-11-16 完成约 8 分钟、9 集自动连播；2026-11-17 完成 1.1.1 的 12 分钟连播和 1.1.2 的 4 分钟连播；数小时播放、内存和解码热稳定性仍未验证 |
| 多应用同时硬解 | NOT_RUN | 已验证从 `douyinTV` 切回红果后正常出画，但未验证两个应用同时在前台硬解播放器 |
| 其他 TV 型号 | NOT_RUN | 仅验证 S621；其他厂商的 D-pad、解码器和音频输出可能不同 |
| 手机扫码输入 / 备份 | NOT_RUN | 未在本轮做手机端联调 |
| 应用内更新 | N/A | 本分支已禁用上游自动更新，改由 GitHub Release 手动发布 |
| 当前视频播放 | PASS | v5 签名通道已恢复正片；部分新剧只提供单一 1080P HEVC 档，且官网完整 H264 兜底返回 403，播放能力仍受服务端片源限制 |

## 复现命令

```powershell
$adb = 'C:\Users\63054\AppData\Local\Android\Sdk\platform-tools\adb.exe'
& $adb -s 192.168.1.47:5555 install -r app\build\outputs\apk\release\app-release.apk
& $adb -s 192.168.1.47:5555 shell am start -n com.edwinpzy.hongguoduanju/com.hongguotv.nativeapp.MainActivity
& $adb -s 192.168.1.47:5555 shell input keyevent 20
& $adb -s 192.168.1.47:5555 shell input keyevent 23
& $adb -s 192.168.1.47:5555 shell input keyevent 19
& $adb -s 192.168.1.47:5555 shell input keyevent 20
```

> 本轮长按确认使用 `sendevent /dev/input/event9 1 28 1`、等待 1 秒后发送 `1 28 0`，用于模拟真实持续按键。以上均为 ADB / 输入子系统注入，不等同于人工按物理遥控器。

## 2026-11-22 v1.1.16 推荐预览回归恢复与发布合并（Release 实机）

版本升级为 `1.1.16 / 10116`。验证分支为 `codex/episode-flat-grid`，S621 覆盖安装原签名 Release。该分支从 `main` 创建时未带入推荐预览提交，导致预览被当成普通播放器显示；本轮将推荐预览/正常播放器模式分离、预览保留顶部标签栏的修复，与 `codex/recommendation-player-mode` 上的弹幕分页、重试和连播修复合并，保留选集分组平铺与精简播放条。

| 场景 | 结果 | 证据摘要 |
| --- | --- | --- |
| 推荐预览界面 | PASS | 《心动梨子》和《攻略诡异：我有一个女鬼催眠APP》预览均显示顶部频道标签栏、底部片名和单行简介；没有播放/暂停按钮、时间或进度条 |
| 预览上下换片 | PASS | 预览按 `↓` 从《嫌疑人的秘密2藏金生死线》切到《穿越八零，我把娘俩宠成宝》，没有进入平铺卡片区；顶部标签和底部信息持续随片更新 |
| 预览左右切页 | PASS | 从“推荐”按 `→` 切到“短剧”，白色焦点仍停在顶部标签栏；按 `←` 回到“推荐”并恢复预览界面 |
| OK 进入正常播放器 | PASS | Release 预览《攻略诡异：我有一个女鬼催眠APP》按 OK 后仍为同剧第 1 集，没有串片；顶部频道标签栏消失，播放/暂停按钮、时间、进度条和集数出现 |
| 正常播放器上下切集 | PASS | Release 播放器按 `↓` 从第 1 集切到第 3 集（连续两次按键均生效），仍为同一部剧，未切回推荐预览；Debug 另验证第 13→14 集 |
| BACK 层级 | PASS | 正常播放器第一次 BACK 收起播放控制层，第二次返回剧集详情；预览 BACK 返回频道页。详情页和预览均不渲染返回按钮 |
| 完整门禁 | PASS | `:app:testDebugUnitTest :core:test :app:assembleRelease :app:bundleRelease :app:lintVitalRelease` 通过；280 项测试、0 失败、0 错误、9 跳过 |
| 签名与产物 | PASS | APK v2 签名通过，证书 SHA-256 `403BDDBE570501BB7B1081DF340D92F4750F8CDA69231C563B1D1BD07A3F13A5`；APK SHA-256 `BB359AA05F028C34ED240C603F32B3D87024E9D47B7B99454E9556E089F710B7`；AAB SHA-256 `CBC4EA4C52DF1DF517BB5AE22C592C21A3D71362F8F7C1B565762669AE90E6D3` |
| 覆盖安装 | PASS | S621 覆盖安装 Release `1.1.16 / 10116` 成功，应用数据保留 |
| 稳定性 | PASS | 预览进入、OK 播放、上下切集和 BACK 退出期间无 `FATAL EXCEPTION` / `ANR` |

限制：S621 输入均为 ADB 注入，不是物理遥控器；本轮未继续排查弹幕显示问题，也未验证全部推荐片源、数小时连续播放和不同 TV 型号。发布验证只覆盖预览、同剧确认、播放器上下切集和退出层级。

## 2026-11-22 v1.1.17 仅 ByteVC2 片源的 HEVC 兜底恢复（Release 实机）

发布版本为 `1.1.17 / 10117`。验证分支为 `codex/episode-flat-grid`；下列 S621 实机证据来自同一修复提交 `b71f76a` 的 Release 构建，构建时版本名仍为 `1.1.16`，`1.1.17` 包已完成覆盖安装但未重复导航复测。

根因：目标剧集《硬核求生恋爱指南》第 6 集（videoId `7708197063116196888`）的 `video_tab` 响应 5 档全部为 `bytevc2`（H.266/VVC），S621 只有 `c2.mtk.hevc.decoder`，没有 VVC 解码器。服务端 `fallback_api` 自带 `codec_type=4`；原兼容请求使用 `codec_type=0&force_codec=h264`，实测仍返回全部 `bytevc2`，所以重试和“尝试 720P”都无效。

修复：当模型全部为 `bytevc2/bvc2` 时，把 `fallback_api` 的 `codec_type` 改为 `3`（ByteVC1/HEVC）；若仍不可播放，再尝试 `1`（H.265）。兼容请求不再附加无效的 `force_codec`，且最多两次请求。

| 场景 | 结果 | 证据摘要 |
| --- | --- | --- |
| 接口实测 | PASS | 第 6 集 `codec_type=3` 返回 5 档 `bytevc1`：1080P/720P/576P/480P/360P；`codec_type=4` 与 `0+force_codec=h264` 均仍为 `bytevc2` |
| S621 第 6 集首帧（修复构建） | PASS | 同一提交 `b71f76a` 的 Release（版本名 `1.1.16`）覆盖安装后从第 5 集按 `↓` 进入第 6 集，`Playback selected: 1080P/bytevc1/1920x1080`，`hvc1/1920x1080 supported=true`，`Playback first frame episode=6 ... elapsedMs=264` |
| 画面确认（修复构建） | PASS | 第 6 集截图显示正常剧集画面与字幕，不再出现 `source-unavailable` 错误页 |
| 播放稳定性 | PASS | 第 5→6 集切换后无 `FATAL EXCEPTION` / `ANR` / `Playback failure` |
| 单元回归 | PASS | `MultiVideoModelRotationTest` 覆盖 `3` 成功、`3` 失败后 `1` 成功，以及保留原 URL 其他参数 |
| 完整门禁 | PASS | `:app:testDebugUnitTest :core:test :app:assembleRelease :app:bundleRelease :app:lintVitalRelease` 通过；282 项测试、0 失败、0 错误、10 跳过 |

限制：S621 输入均为 ADB 注入，不是物理遥控器；实机证据来自版本名 `1.1.16` 的同一修复构建，`1.1.17` 只完成覆盖安装，用户要求发布前不再实机复测。本轮只验证第 6 集及紧随切集，未遍历全部仅返回 `bytevc2` 的剧集，也未验证其他 TV 型号的 HEVC 能力。

## 2026-11-22 v1.1.18 弹幕后续分页游标保留（Release 实机）

发布版本为 `1.1.18 / 10118`。验证分支为 `codex/danmaku-continuous-v118`，基于 `v1.1.17 / 671f0a3` 的最新发布线修复，保留此前的播放器主线程状态快照、请求重试和自动连播修复。

根因：`DanmakuFetchPlan.next()` 虽把服务端 `common_list_info.cursor` 放入 `Advance.Request`，但实际工作线程只消费 `waitMs`；下一轮调用 `request()` 时仍固定得到空 cursor。后续分页因此不能继承服务端列表游标，可能重复首页或提前结束，去重后表现为“开头有弹幕，后续没有”。修复后在拉取状态机内持久化 cursor，`request()` 每轮返回当前时间窗和服务端游标；新增断言直接覆盖下一轮的 `request()`，不只检查未被工作线程消费的 `Advance.Request`。

| 场景 | 结果 | 证据摘要 |
| --- | --- | --- |
| 后续请求游标回归 | PASS | `DanmakuFetchPlanTest` 断言连续两页之后的 `plan.request()` 分别保留 `page-1` 和 `page-2`；现有 120 秒续页、时间提示停滞和明确终页测试继续通过 |
| S621 后续分页 | PASS | Release 实机《夜晚的答案》自动连播至第 3 集，日志显示 `0 / 30 / 60 / 90 / 120 / 150 / 180s` 窗口依次获取，后续响应 `cursor=true more=true`；180 秒窗口取得 6 条新弹幕并继续到 210 秒 |
| S621 可见真实弹幕 | PASS | 同版本《夜晚的答案》连续自然播放约 1 分钟、3 分钟及后续多次采样可见“这俩太有意思了”“这不火上浇油吗”“有原则是对的”“人穷志不穷”等真实滚动弹幕；未循环重放旧评论或生成虚假弹幕 |
| 完整干净门禁 | PASS | `clean :core:test :app:testDebugUnitTest :app:assembleRelease :app:bundleRelease :app:lintVitalRelease --no-build-cache` 通过；282 项测试、0 失败、0 错误、10 跳过（显式联网探针） |
| 签名与产物 | PASS | APK v2 签名通过，证书 SHA-256 `403BDDBE570501BB7B1081DF340D92F4750F8CDA69231C563B1D1BD07A3F13A5`；APK SHA-256 `C29689DFD97E03BE36CFDAB0FC372BC8DA255A807018FE2F39964A032A7B0D79`；AAB SHA-256 `6154B283A2B1DCA2EE2314051F125DD1FE069598AB5ED56A3A591A084B1677FF` |
| 覆盖安装 | PASS | S621 覆盖安装 Release `1.1.18 / 10118` 成功，应用数据与登录态保留；发布后使用同一 APK 复核安装和播放 |

限制：S621 输入均为 ADB 注入，不是物理遥控器；验证是自然连续播放和多次采样，不是每一帧都有弹幕。稀疏窗口可能没有真实评论，单张无弹幕截图不能证明拉取失败；数小时播放、全部剧集及其他 TV 型号仍未验证。

## 2026-11-23 v1.1.19 播放空响应恢复（Release 实机）

发布版本为 `1.1.19 / 10119`。验证分支为 `codex/playback-empty-response`，基于 `main@e311a76` 创建；S621 覆盖安装同签名 Release，应用数据与登录态保留。

根因：S621 播放《红兜落处是良人》第 4 集（videoId `7704127035085425726`）时显示 `source-unavailable`。该集在旧 `video_tab` 与 `multi_video_model/player/v1` 两条 App 通道均返回 HTTP 200 且正文 0 字节，官网完整 H264 兜底返回 403，并非网络超时或解码失败。与上游 N3urda `ef328f3a`（0.16.14）做同请求 A/B 后，差异收敛到 v5 Medusa 第 21 字段：旧值 `377` 返回空正文，`312` 恢复模型；同时当前 App 已使用 `7.4.5.32` profile 和 `POST /novel/player/multi_video_model/preload/v1`（`dr_scene=preload`、`device_level=2`）。

修复：

- v5 签名第 21 字段由硬编码 `377` 改为 `V5Constants.field21 = 312`，并增加回归断言。
- `mobile-v5` 主请求改为 `preload/v1`（gzip 正文、`x-ss-stub`、v5 三头）；空正文时用同一设备身份请求 `video_tab`，签名覆盖空字节的 MD5（`MD5("")`），移除 `x-ss-stub`、`Content-Type`、`Content-Encoding`，且不携带 Cookie。
- 模型仅含 `bytevc2` 时改用 App 自带 `fallback_api`，依次尝试 `codec_type=1`、`3`，不依赖官网播放器。
- 默认通道顺序改为 `mobile-v5`、`multi-video-model`、`app-api`、`web-fallback`、`public-mp4`。
- 新增 `MobilePlaybackStreamSourceTest`（空响应回退、ByteVC2 兼容、默认顺序）和显式联网探针 `PlaybackRecoveryLiveProbeTest`（默认跳过，`HONGGUOTV_LIVE_PROBE=1` 启用）。

| 场景 | 结果 | 证据摘要 |
| --- | --- | --- |
| 同请求 A/B | PASS | 第 21 字段 `377`：HTTP 200 + 0 字节；`312`：恢复完整模型。 |
| 核心联网探针 | PASS | `RECOVERY episode=7704127035085425726 quality=1080P codec=bytevc1 1920x1080 key=16`；媒体 `HTTP 206 bytes=131072`。 |
| S621 第 4 集与连播 | PASS | 最终 Release 从“接着看”第 4 集 `01:00` 起播，`Playback selected: 1080P/bytevc1/1920x1080/587121bps`，首帧 `elapsedMs=2745`；随后自动连播第 5 集（首帧 1233 ms）和第 6 集（预热首帧 278 ms），未出现 `source-unavailable` 或 `Playback failure`。 |
| 画面确认 | PASS | 播放截图显示正常剧集画面与字幕；`screencap` 不能替代投影物理画面的人工确认。 |
| 完整干净门禁 | PASS | `clean :core:test :app:testDebugUnitTest :app:assembleRelease :app:bundleRelease :app:lintVitalRelease --no-build-cache` 通过；287 项测试、0 失败、0 错误、11 跳过（显式联网探针）。 |
| 签名与产物 | PASS | APK v2 签名通过，证书 SHA-256 `403BDDBE570501BB7B1081DF340D92F4750F8CDA69231C563B1D1BD07A3F13A5`；APK SHA-256 `D4A06757B24FF8D3CBC14505288E7A5A8A05F8A6A8D4C7EE71B84E7D0B7914A8`；AAB SHA-256 `123B27A3D13E74A778A3C91D6ADD90223E70EDEE6ABEC8D84AA7B6C68E8E5213`。 |
| 覆盖安装 | PASS | S621 覆盖安装最终 Release `1.1.19 / 10119` 成功，应用数据与登录态保留。 |

限制：S621 输入均为 ADB 注入，不是物理遥控器；本轮只验证该剧第 4–6 集的起播与自动连播，未遍历全部空正文剧集、数小时连续播放及其他 TV 型号。实机过程中曾出现一次 `进度上传失败：云端观看历史写入失败：账号接口不可用（错误码 100）`，与片源恢复无直接关系，未纳入本次播放验收；账号与云端历史链路仍待单独排查。

## 2026-11-24 v1.1.20 长剧集详情页与 1080P 播放平滑度（Release 实机）

发布版本为 `1.1.20 / 10120`。验证分支为 `codex/detail-section-overflow`，基于 `v1.1.19 / 6400c18`；S621 覆盖安装同签名 Release，应用数据与登录态保留。

根因有两层：

- 详情页分组按钮原先直接放在水平 `LinearLayout`。331 集剧集有 17 个分组，最后一个可见按钮被压缩成很窄的宽度，文字换行后把分组行撑到约 385px 高，下面因此出现大面积空白。
- 播放器使用 `TextureView` 时，1080P 每一帧都要先复制到 GPU，再由应用 UI 合成。S621 能硬解 1080P HEVC，但这条复制路径会错过 30fps 的帧截止时间。同机 A/B 中 `TextureView` 的帧间隔为 `p50=32.7ms / p90=46.6ms / p99=61.9ms`，126 个间隔有 24 个超过 40ms；`SurfaceView` 直接交给 Hardware Composer，结果为 `p50=33.33ms / p90=33.33ms / p99=33.43ms`，126 个间隔没有一个超过 40ms。

修复：

- 分组按钮放入水平 `HorizontalScrollView`，保持每个按钮的自然宽度；选中分组时自动滚动到可见位置，不再挤压文字或撑高整行。
- 播放视图改回 `SurfaceView`，让 1080P 视频使用独立 Hardware Composer 视频层，避免每帧 GPU 复制。播放器在生命周期变化时释放并重建唯一的视频 Surface，继续满足 S621 的两层 HWC 上限。

| 场景 | 结果 | 证据摘要 |
| --- | --- | --- |
| 331 集详情页 | PASS | 《人间忽又逢春》显示全 331 集，17 个分组横向平铺；分组行高度正常，下方集数网格紧接显示，不再出现用户截图中的大面积空白 |
| 1080P/30fps 帧节奏 | PASS | 《人间忽又逢春》第 2 集 `hvc1/1920x1080@30fps`，连续采样 127 帧：`p50=33.33ms`、`p90=33.33ms`、`p99=33.43ms`，`>40ms=0` |
| HWC 合成路径 | PASS | `dumpsys SurfaceFlinger` 的活动 HWC 层数为 2：视频层 `SurfaceView ... Comp Type=Device`、Buffer `YCRCB_420_SP`，应用 UI 层为 `Client` |
| 1080P/25fps 显示匹配 | PASS | 《少夫人来自东北3》第 49 集 `1080x1920@25fps`，日志 `Display mode 50.0Hz`；首帧 `elapsedMs=1015`，视频层仍为 HWC `Device` |
| 25fps 帧节奏与连播 | PASS | 连续采样 127 帧：`p50=40ms`、`p90=40ms`、`p99=56.67ms`；除自动切集边界 2 个间隔外均为 40ms，无 `Dropped video frames` 或音频 underrun；第 50 集预热首帧 `elapsedMs=197` |
| 菜单叠加 | PASS | 播放中打开 MENU 选集侧栏，视频在侧栏后持续出画；HWC 仍为视频层 `Device` + UI 层 `Client`，未出现 `hwc overlay number exceed limit` |
| 后台恢复 | PASS | 播放中按 HOME，5 秒后重新进入；第 10 秒截图正常显示视频与弹幕，HWC 恢复为 2 层且视频层为 `Device`，无黑屏或仅声音 |
| 手动切集 | PASS | 播放中按 `↓` 切换下一集，首帧 `elapsedMs=311`，缓冲 `stalledMs=240`；画面、字幕与弹幕正常 |
| 完整干净门禁 | PASS | `clean :core:test :app:testDebugUnitTest :app:assembleRelease :app:bundleRelease :app:lintVitalRelease --no-build-cache` 通过；287 项测试、0 失败、0 错误、11 跳过（显式联网探针） |
| 签名与产物 | PASS | APK v2 签名通过，证书 SHA-256 `403BDDBE570501BB7B1081DF340D92F4750F8CDA69231C563B1D1BD07A3F13A5`；APK SHA-256 `53D8EC83DB4F1ED207B4E6D8DE2F66559C291D97D33891215D51413E3F1A8660`；AAB SHA-256 `053DA936F439ACEFAB7394198195C7C8F7E4431D8E24DDC092BE7648BF2C4D41` |
| 覆盖安装 | PASS | S621 从 `1.1.19 / 10119` 覆盖安装 `1.1.20 / 10120` 成功，应用数据与登录态保留 |

限制：S621 输入均为 ADB 注入，不是物理遥控器；截图和 SurfaceFlinger 不能替代投影幕布的人工观察。本轮验证了 30fps/60Hz、25fps/50Hz、菜单、后台恢复、手动切集和自动连播，但未覆盖数小时连续播放、全部 331 集、弱网抖动及其他 TV 芯片/固件。其他设备若仍出现卡顿，应优先核对 `Dropped video frames`、`Frame offset`、`Playback buffering`、`VideoDataSource slow read` 和 HWC 视频层是否仍为 `Device`。

## 2026-11-24 推荐返回当前预览、首尾续页与播放侧栏简介（Debug 实机）

验证分支为 `codex/player-synopsis`，S621 覆盖安装同签名 Debug 包，应用数据与登录态保留；设备输入均为 ADB 注入，不是物理遥控器。推荐预览按 OK 进入正常播放器后不再丢失当前批次；BACK 恢复同一视频的预览界面与播放位置。预览在批次首条继续按 `↑`、或在末条继续按 `↓` 时，会请求下一页并自动切到新批次。

| 场景 | 结果 | 证据摘要 |
| --- | --- | --- |
| 正式播放返回当前预览 | PASS | 预览《拐个新郎回老街》按 OK 进入第 1 集，播放条显示 `00:53`；BACK 两次后回到同名预览，顶部标签和底部片名/简介恢复，画面继续推进，未重新取流或回到批次首条 |
| 顶部边界续页 | PASS | 首批 8 条，起点《阳谋正道：开局入宫，从内廷开始颠覆王朝》时按 `↑`，自动加载下一页并切到新批次首条《拐个新郎回老街》 |
| 底部边界续页 | PASS | 同批翻到第 8 条《丈母娘百万招婿，我的冰山老婆》后按 `↓`，自动加载下一页并切到《星落清黎时》；日志首帧 `preloaded=true`、`elapsedMs=516`，未停在“已经是最后一条推荐” |
| 续页失败可重试 | PASS（代码） | 下一页请求仅对 `NoNewRecommendationsException` 标记批次耗尽；普通网络异常保留 `hasMore` 并提示稍后重试，不再永久关闭续页 |
| 播放侧栏简介 | PASS | 同分支前一提交已实机确认：播放侧栏头部显示当前剧集 3 行简介，技术参数行移除；分辨率上限、倍速仍在“播放设置”页 |
| 编译与单测 | PASS | `:core:test :app:testDebugUnitTest :app:assembleDebug` 通过 |

限制：S621 输入均为 ADB 注入，不是物理遥控器；本轮只验证两个推荐批次与一次返回链路，未覆盖弱网续页失败、数小时连续滑动、全部账号推荐池及其他 TV 型号。未运行 Release 构建和 `lintVitalRelease`。

## 2026-11-24 官方图标与 TV 横幅（Debug 实机）

验证分支为 `codex/player-synopsis`；S621 覆盖安装同签名 Debug 包，应用数据与登录态保留；设备输入为 ADB 注入。

| 场景 | 结果 | 证据摘要 |
| --- | --- | --- |
| 主页品牌图标 | PASS | 顶部左侧由旧透明标替换为官方橙绿渐变圆角图标，24dp 显示清晰，无拉伸或白边 |
| 系统桌面横幅 | PASS | 桌面长方形卡片显示官方横向标识，副标题已移除；标识完整无裁切，左右留白均衡 |
| 启动图标资源 | PASS | `mipmap-mdpi` 至 `mipmap-xxxhdpi` 五档图标与 `drawable-xhdpi/tv_banner.png` 已替换；旧 `app_logo_transparent.xml` 删除，代码无残留引用 |
| 编译与单测 | PASS | `:core:test :app:testDebugUnitTest :app:assembleDebug` 通过 |

限制：仅验证 S621 及其当前桌面；未覆盖其他 TV 启动器、桌面图标缓存异常、Release 构建和 `lintVitalRelease`。

## 2026-11-24 v1.1.21 推荐预览返回、侧栏简介与官方横幅（Release 实机）

发布版本为 `1.1.21 / 10121`。验证分支为 `codex/player-synopsis`，基于 `v1.1.20 / 2804ffd`；S621 从 `1.1.20 / 10120` 覆盖安装同签名 Release，应用数据与登录态保留。设备输入均为 ADB 注入，不是物理遥控器。

本版汇总三类改动：推荐预览按 OK 交给正常播放器后，BACK 可回到当前视频的预览；预览在推荐批次首尾继续按 `↑` / `↓` 会自动续页；播放器侧栏头部显示剧集简介；应用图标与 TV 横幅改为官方素材，横幅为无副标题的横向标识。

| 场景 | 结果 | 证据摘要 |
| --- | --- | --- |
| 推荐预览返回 | PASS | Release《明末：我以毒士为子入残局》预览按 OK 进入正式播放，两次 BACK 后回到同名预览，顶部标签与底部片名/简介恢复；日志首帧 `episode=2 preloaded=false reused=false elapsedMs=5420`，未重新取流 |
| 侧栏简介与分组 | PASS | Release 正常播放器按 MENU 打开侧栏，头部显示 3 行简介；分组标签横向平铺（`1-60`、`61-120` … `421-…`），不再压缩换行 |
| 桌面长方形横幅 | PASS | S621 桌面卡片显示官方横向标识，副标题已移除；标识完整无裁切，左右留白均衡 |
| HWC 视频层 | PASS | `dumpsys SurfaceFlinger` 显示 `SurfaceView[...] (BLAST)` 为 `Comp Type=Device`，Buffer `YCRCB_420_SP`、`640x360`；首帧与自动连播日志正常 |
| 截图限制 | NOTE | S621 的 `screencap` / `screenrecord` 不包含 HWC `SurfaceView` 视频层；同机 `1.1.20` Release A/B 截图同样为黑帧，不能据此判断播放黑屏 |
| 稳定性 | PASS | 预览、正式播放、侧栏和返回链路无 `FATAL EXCEPTION` / `ANR` |
| 完整干净门禁 | PASS | `clean :core:test :app:testDebugUnitTest :app:assembleRelease :app:bundleRelease :app:lintVitalRelease --no-build-cache` 通过；287 项测试、0 失败、0 错误、11 跳过 |
| 签名与产物 | PASS | APK v2 签名通过，证书 SHA-256 `403BDDBE570501BB7B1081DF340D92F4750F8CDA69231C563B1D1BD07A3F13A5`；APK SHA-256 `5997816B69CA872E747E603FE48077A162470CC157C96347166B1D8C8D9AB2CA`；AAB SHA-256 `E1FE5F7EC3630119B44C52DAAF7925AE1E1E1D9F0026E3A153AF59373B7C9121` |
| 覆盖安装 | PASS | S621 从 `1.1.20 / 10120` 覆盖安装 `1.1.21 / 10121` 成功，应用数据与登录态保留 |

限制：S621 输入均为 ADB 注入；Release 只验证推荐预览返回、侧栏简介/分组、桌面横幅和稳定性，首尾自动续页的完整翻页证据来自同分支 Debug 实机（见上一节），未在 Release 重跑长列表；未覆盖数小时连续播放、弱网、其他 TV 型号。S621 截图不包含 HWC 视频层，视频出画由首帧日志、SurfaceFlinger 硬件层和自动连播状态共同确认，未用截图宣称视频画面通过。

## 2026-11-24 v1.1.22 S621 低清与兜底片源黑屏（Debug 实机）

根因是 S621 的低清网页兜底流仍进入 `SurfaceView` 的 HWC 路径；解码器和首帧状态正常，但视频输出为纯黑。修复按设备与已打开片源的真实参数选择视频视图：S621 的网页兜底流及面积不超过 `640×360` 的低清流使用 `TextureView`；高清流及其他设备仍使用 `SurfaceView`。换档只替换视频视图，保留播放器的弹幕、海报、进度和选集层。

| 场景 | 结果 | 证据摘要 |
| --- | --- | --- |
| 修复前基线 | FAIL | `1.1.21 / 10121` 的推荐页仅导航和片名可见，视频区域纯黑 |
| 低清网页兜底 | PASS | 《明末：我以毒士为子入残局》第 2 集选择 `360P/640x360`，日志为 `Playback surface: TEXTURE_VIEW ... fallback=true`；ADB 截图显示真实剧集画面与字幕 |
| 侧栏、恢复与切集 | PASS | MENU 侧栏后方持续出画；HOME 后恢复仍显示视频；下键切到第 3 集正常出画，无 `FATAL EXCEPTION`、`ANR` 或播放器线程错误 |
| 高清路径 | PENDING | 继续验证 S621 高清片源仍为 `SURFACE_VIEW`，不回归为全量 `TextureView` |
| 编译与单测 | PASS | `:app:testDebugUnitTest :app:assembleDebug` 通过；新增 S621 低清/兜底和其他机型路由测试 |

限制：设备输入均为 ADB 注入，不是物理遥控器。低清 `TextureView` 可进入系统截图，因此本节截图能直接证明视频出画；高清 `SurfaceView` 黑截图仍不能单独作为黑屏证据。正式包的完整门禁、签名、安装与最终回归结果将在发布前补记。

## 2026-11-24 签名片源空响应与网页 403 回退

《大明：我真不是万寿帝君》第 17 集在 `1.1.22` 上稳定复现：签名播放通道刷新身份后仍返回空响应，受保护网页完整片源返回 HTTP 403，最终错误地被标记为不可重试。修复后，签名通道重试耗尽才尝试公开 MP4；该来源会先读取 MP4 时长并拒绝已知试看片段，且其失败不会覆盖签名通道的可重试错误。正常签名档位仍优先，不会被低清公开地址替代。

| 场景 | 结果 | 证据摘要 |
| --- | --- | --- |
| 失败基线 | FAIL | `1.1.22` 第 17 集两条签名通道连续空响应、网页 HTTP 403，界面显示停止自动重试 |
| 完整公开片源回退 | PASS（单测） | 签名重试耗尽后仅调用一次公开来源，返回 `isFallback=true` 的完整集地址；正常高清档位与预加载路径不变 |
| 试看片段保护 | PASS（单测） | 公开来源拒绝试看后，不把它作为可播放档位，也不覆盖签名通道的错误分类 |
| 原集恢复 | PASS | 同机修复版第 17 集 `7696061484610096190` 取得 `bytevc1/1920x1080` 正式片源，首帧 `elapsedMs=2566`，网页 403 未阻断正式片源 |
| 核心回归与 Debug 构建 | PASS | `:core:test :app:assembleDebug` 通过 |

限制：设备输入均为 ADB 注入，不是物理遥控器；本轮公开完整 MP4 的实际回退路径由单测覆盖，原集后来恢复了正式 1080P，未伪称实机命中公开回退。上游空响应与网页 403 仍可能发生。

## 2026-11-25 ByteVC2 兼容请求超时修复（Debug 实机/部分）

版本为 `1.1.23 / 10123` 的 Debug 构建，覆盖安装到 S621，应用数据保留；设备输入均为 ADB 注入，不是物理遥控器。测试期间曾临时安装 ADB Keyboard 输入中文，结束后已恢复系统拼音输入法并卸载该测试键盘。

根因：仅有 ByteVC2 档位时，`fallback_api` 的 `codec_type=1/3` 兼容请求没有走统一播放预算，单次可一直使用 OkHttp 的 45 秒总超时，两次尝试会把“播放超时”放大到约 90 秒。修复把兼容请求接入 `PlaybackRouteTransport`，单请求最多 8 秒且不超过播放取流的剩余总预算，并纳入统一取消。

| 场景 | 结果 | 证据摘要 |
| --- | --- | --- |
| 兼容请求预算回归 | PASS（单测） | `MobilePlaybackStreamSourceTest.bytevc2FallbackUsesThePlaybackRequestBudget` 在外层 1.5 秒预算下断言兼容请求超时不超过 1.5 秒 |
| 模块编译与 Debug 包 | PASS | `:core:test --tests "com.hongguotv.core.MobilePlaybackStreamSourceTest"`、`:core:compileKotlin`、`:app:compileDebugKotlin`、`:app:assembleDebug` 通过 |
| S621 覆盖安装 | PASS | 同一 applicationId 和签名覆盖安装 `1.1.23 / 10123` Debug 成功，应用数据保留 |
| 目标第 6 集 | BLOCKED | 《硬核求生恋爱指南》第 6 集 `7708197063116196888` 两次冷启动均在约 10.3 秒结束；移动端主接口与 `video_tab` 均 HTTP 200 / 0 字节，旧 App 通道空响应，网页完整片源 HTTP 403，公开 MP4 HTTP 404，未进入 `fallback_api` |
| 相邻第 5 集联网探针 | BLOCKED | `7708197061132307480` 的 `mobile-v5` 与 `multi-video-model` 同样全部 HTTP 200 / 0 字节，排除单集路径错误 |
| 普通剧集回归 | PASS | 《红楼，缉恶成圣2》第 1 集通过网页完整片源 `360P/640x360` 起播，首帧 `elapsedMs=9298`，截图显示真实画面与字幕，无 `FATAL EXCEPTION` / `ANR` |

限制：本轮没有在 S621 上命中“非空 ByteVC2 模型 → `fallback_api` → HEVC 兼容档位”的实际链路，因为当前匿名播放身份对目标剧集的签名接口整体返回空正文；该路径的超时上限由单测确定性覆盖，未用实机结果冒充。`1.1.23` Release、完整发布门禁和签名产物未在本轮重跑。

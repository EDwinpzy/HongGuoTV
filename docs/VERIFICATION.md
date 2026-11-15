# TV 兼容性与实机验证

## 验证环境

| 项目 | 值 |
| --- | --- |
| 设备 | JMGO S621 投影仪，Android 13 |
| 分辨率 | 1920×1080 |
| 应用版本 | 1.0.0（versionCode 10000） |
| 应用包名 | `com.edwinpzy.hongguoduanju` |
| 连接方式 | ADB TCP `192.168.1.47:5555` |
| 输入方式 | ADB 注入 `DPAD_UP/DOWN/LEFT/RIGHT`、`DPAD_CENTER` 和 `BACK`；长按确认通过 `/dev/input/event9` 注入 1 秒持续按键；搜索词使用 ADB 文本注入；账号登录由手机红果 App 实际扫码确认 |

## 已验证

| 场景 | 结果 | 证据摘要 |
| --- | --- | --- |
| 冷启动与首页 | PASS | 首页数据加载完成，初始卡片有可见焦点 |
| TV 界面重设计 | PASS | 首页 10 个导航项完整单行显示；圆角封面、卡片层级、橙色焦点描边、详情 Hero、设置面板和播放器 HUD 均正常 |
| 卡片焦点移动 | PASS | 左右移动会切换焦点并更新预览标题 |
| 详情与播放 | PASS | OK 进入详情，再按 OK 启动 1080P 播放 |
| 播放控件 | PASS | OK 暂停并显示控件；ExoPlayer 获取音频焦点，播放进度正常 |
| 播放中上下键切集 | PASS | Release 包中先按 UP 从第 10 集回到第 9 集，再按 DOWN 回到第 10 集；日志分别输出 `Playback ready episode=9` 和 `episode=10` |
| 长按确认打开更多面板 | PASS | 持续按住确认键 1 秒后打开“暂停/上一集/下一集/选集/播放设置”面板；MediaSession 仍为播放状态，未误触发暂停 |
| 选集侧栏 | PASS | Release 包从更多面板进入选集侧栏，当前第 10 集自动获得焦点，集数网格、分组按钮和关闭入口均可操作 |
| 通用选择弹窗 | PASS | 清晰度、倍速、定时和播放设置统一使用深色选择面板；当前值有橙色标记与焦点描边，取消后焦点正确返回原按钮 |
| 长列表与按键隔离 | PASS | “播放设置”8 个选项可向下滚动到末项；连续方向键不会穿透播放器或触发切集 |
| 返回栈 | PASS | 播放器返回详情，再返回首页 |
| 断点续播 | PASS | 返回首页后“接着看”显示第 3 集和观看进度 |
| 搜索 | PASS | 电视端输入 `BFLC`，出现联想并返回 17 条结果 |
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

构建验证：`core` JVM 测试 217 项，0 失败、0 错误、6 跳过；`:app:lintVitalRelease` 和 `:app:assembleRelease` 均通过。最终 APK 使用 v2 签名校验通过。

## 未验证或有限制

| 项目 | 状态 | 说明 |
| --- | --- | --- |
| 物理遥控器按键 | NOT_RUN | 本次使用 ADB 注入按键；S621 是实体设备，但没有人工按物理遥控器 |
| 二维码取消与过期 | NOT_RUN | 最终 Release 验证时账号已登录；为避免清除已确认的登录态，未在最终包上重复执行退出后取消/等待过期 |
| 长时间连续播放 | NOT_RUN | 未做数小时播放、内存和解码稳定性测试 |
| 其他 TV 型号 | NOT_RUN | 仅验证 S621；其他厂商的 D-pad、解码器和音频输出可能不同 |
| 手机扫码输入 / 备份 | NOT_RUN | 未在本轮做手机端联调 |
| 应用内更新 | N/A | 本分支已禁用上游自动更新，改由 GitHub Release 手动发布 |

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

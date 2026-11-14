# TV 兼容性与实机验证

## 验证环境

| 项目 | 值 |
| --- | --- |
| 设备 | JMGO S621 投影仪，Android 13 |
| 分辨率 | 1920×1080 |
| 应用版本 | 1.0.0（versionCode 10000） |
| 应用包名 | `com.edwinpzy.hongguoduanju` |
| 连接方式 | ADB TCP `192.168.1.47:5555` |
| 输入方式 | ADB 注入 `DPAD_UP/DOWN/LEFT/RIGHT`、`DPAD_CENTER` 和 `BACK`；搜索词使用 ADB 文本注入 |

## 已验证

| 场景 | 结果 | 证据摘要 |
| --- | --- | --- |
| 冷启动与首页 | PASS | 首页数据加载完成，初始卡片有可见焦点 |
| 卡片焦点移动 | PASS | 左右移动会切换焦点并更新预览标题 |
| 详情与播放 | PASS | OK 进入详情，再按 OK 启动 1080P 播放 |
| 播放控件 | PASS | OK 暂停并显示控件；ExoPlayer 获取音频焦点，播放进度正常 |
| 选集 | PASS | 播放中按 DOWN 打开选集，选择第 3 集后从该集继续播放 |
| 返回栈 | PASS | 播放器返回详情，再返回首页 |
| 断点续播 | PASS | 返回首页后“接着看”显示第 3 集和观看进度 |
| 搜索 | PASS | 电视端输入 `BFLC`，出现联想并返回 17 条结果 |
| 设置与对话框 | PASS | 设置页、版本信息和 GPL 许可对话框可读，无文字截断 |
| 桌面入口 | PASS | 启动器显示“红果短剧”、自定义图标和 `免费短剧 · Android TV` |
| Release 覆盖安装 | PASS | Release APK 已成功覆盖 debug 安装并启动 |
| 干净安装 | PASS | 卸载测试包和原 App 后安装最终 Release，冷启动和首页加载正常 |

构建验证：`core` JVM 测试 217 项，0 失败、0 错误、6 跳过；`:app:lintVitalRelease` 和 `:app:assembleRelease` 均通过。最终 APK 使用 v2 签名校验通过。

## 未验证或有限制

| 项目 | 状态 | 说明 |
| --- | --- | --- |
| 物理遥控器按键 | NOT_RUN | 本次使用 ADB 注入按键；S621 是实体设备，但没有人工按物理遥控器 |
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
```

> `adb input keyevent` 只证明应用能处理 Android 的 D-pad 事件，不等同于真实遥控器硬件验证。

# 红果短剧 Android TV 项目规则

## Agent Quick Card

- **官方源码入口：** `app/`（Android TV 客户端）和 `core/`（内容接口、签名、播放解析）；两者组成同一个 APK 发布产物。
- **构建：** `.\gradlew.bat :app:assembleDebug`；正式包 `.\gradlew.bat :app:assembleRelease`。需要 JDK 17+、Android SDK Platform 35 和 `local.properties`。
- **测试：** `.\gradlew.bat :core:test`；合并前的完整最小验证为 `.\gradlew.bat :core:test :app:assembleRelease`。
- **静态检查：** `.\gradlew.bat :app:lintVitalRelease`。
- **生成 / 忽略目录：** `build/`、`app/build/`、`core/build/`、`.gradle/`、`.kotlin/`、`.work/`、`.tools/`、`artifacts/`、`docs/upstream/`、`.keys/`。
- **发布 / 签名门禁：** Release APK 必须使用 `keystore.properties` 指向的本地密钥签名；密钥、密码、`keystore.properties`、APK/AAB 和测试截图不得入库。发布前运行完整构建、`lintVitalRelease` 和 S621 实机冒烟。
- **禁止直接修改：** `main` 分支只通过任务分支/PR 更新；`.keys/`、`.work/`、`.tools/`、`artifacts/`、`docs/upstream/` 和所有 `build/` 内容不是源码，不直接编辑或提交。

## 项目边界

- **正式产品：** `app/` + `core/`，applicationId `com.edwinpzy.hongguoduanju`，界面名称“红果短剧”。
- **实验 / 旧实现 / 验证副本：** `docs/upstream/` 仅为临时上游对照资料，已忽略，不是发布入口。
- **多技术栈边界：** 仓库只有 Android/Kotlin TV 客户端，没有独立 Web 或后端发布物。
- **更新入口：** 应用内自动更新已禁用；升级通过 GitHub Release 手动覆盖安装。

## 设备验证

- 实机：JMGO S621，Android 13，1920×1080。
- ADB：`C:\Users\63054\AppData\Local\Android\Sdk\platform-tools\adb.exe`，目标 `192.168.1.47:5555`。
- 安装：`$adb -s 192.168.1.47:5555 install -r app\build\outputs\apk\release\app-release.apk`。
- 遥控器证据优先使用真实遥控器；仅使用 ADB 注入按键时，必须在验证记录中明确标注。

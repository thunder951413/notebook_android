# Notebook Android 工程交接

本文面向继续维护本仓库的开发者和编码代理。开始修改前先确认当前分支、工作区状态和测试目标，不要把桌面端 `notebook` 仓库中的 Android 评估夹具当成产品源码。

## 当前代码线

- Android 产品源码独立维护在 `thunder951413/notebook_android`。
- `next` 是当前 Android 开发与验证基线；`main` 可能仍是旧实现。修复和测试结论必须注明基于哪个提交，不要把 `main` 的行为外推到 `next`。
- Debug 包名是 `io.github.notebook.android.nexttest`，可与正式包并存。隔离测试只使用 debug 包、测试服务器和专用远端目录。
- 不要把真实 WebDAV、SSH 或 Notebook Next 凭据写入源码、Gradle 参数文件、测试日志或提交。

## 架构与数据边界

`MainActivity.kt` 是 Compose 界面入口；`SyncRepository` 负责 Room、本地草稿、提醒更新和三种同步后端之间的协调。Room 是可编辑内容的本地事实来源，编辑器输入先进入 durable draft，再由 repository 合并到笔记、历史与同步 outbox。

正式的新配置使用 WebDAV v4 日志协议：

```text
<remotePath>/
  journal/<device-id>.jsonl
  journal/<device-id>.head.json
  objects/<hash-prefix>/<sha256>
```

每台设备只追加或压缩自己的 journal。同步先发现所有设备日志并拉取，再发布本机 outbox；head 文件用于无变化快速路径，不能取代 journal 的完整校验。PROPFIND 解析失败必须终止本次同步，不能解释为“远端为空”。私密文件夹中的笔记、步骤、阅读位置和附件只留在本机，不得进入任何远端 outbox。

SSH/SFTP Markdown v3 与 Notebook Next HTTP API 只承担既有部署的迁移兼容。修改共享实体、附件或历史结构时，仍需检查这两条兼容路径，避免迁移期间破坏旧数据。

## 编辑与生命周期约束

- 正常输入通过 600 ms 防抖自动保存。
- 返回、Activity 停止、切换预览、删除和同步边界必须刷新最新编辑状态。
- 保存失败时保留 Room draft 并展示错误；不要因为旧保存任务完成就删除更新的草稿。
- Android 没有可靠的“应用退出”回调。`onStop` 只负责安排持久化与后续 worker，正确性不能依赖进程继续存活。
- `MainActivityTest` 必须取消遗留 WorkManager 任务、刷新旧草稿并清空 Room，避免上一次设备测试保存的同步配置或数据影响下一项 UI 用例。

## 安全约束

- Release 同步只允许 HTTPS；debug 构建才允许回环 HTTP 测试服务器。
- Basic 凭据不得跨重定向转发，服务端响应正文不得拼进错误信息。
- WebDAV XML 响应有固定大小、href 数量和单项长度上限。DOCTYPE、实体声明和外部解析必须被拒绝；损坏 XML 应抛出明确错误。
- 附件下载写入临时文件，校验长度和 SHA-256 后再替换目标；所有相对路径都必须经过应用私有根目录边界检查。
- 私密文件夹是设备锁访问控制加 Android 应用私有存储，不是额外的应用层静态加密。它们不会上传，卸载或清除数据后不能从云端恢复。

## 本地验证

统一使用 JDK 17 和 Android SDK 35：

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
./gradlew testDebugUnitTest lintDebug assembleDebug
```

API 35 隔离模拟器可这样启动：

```bash
$ANDROID_HOME/emulator/emulator -avd notebook_phone_api35 \
  -no-snapshot -no-window -no-audio -read-only
```

WebDAV 回归使用本仓库夹具和独立端口：

```bash
python3 scripts/test_webdav_server.py --port 2223 --require-auth
adb reverse tcp:2223 tcp:2223
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.github.notebook.android.WebdavSyncInstrumentedTest \
  -Pandroid.testInstrumentationRunnerArguments.webdavBaseUrl=http://127.0.0.1:2223/dav/ \
  -Pandroid.testInstrumentationRunnerArguments.webdavRemotePath=notebook_test
```

再运行 `MainActivityTest` 和 `DraftRecoveryTest`。`WebdavProcessDeathInstrumentedTest` 的三个方法是有顺序的主机驱动阶段；Gradle 每次 connected run 会重新安装测试包，因此应先安装一次 APK，再直接调用 instrumentation，并用 `adb reverse` 模拟断网与恢复：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
RUNNER=io.github.notebook.android.nexttest.test/androidx.test.runner.AndroidJUnitRunner

adb shell am instrument -w \
  -e class 'io.github.notebook.android.WebdavProcessDeathInstrumentedTest#prepareDurablePendingChange' \
  -e webdavBaseUrl http://127.0.0.1:2223/dav/ \
  -e webdavRemotePath notebook_process_test "$RUNNER"
adb reverse --remove tcp:2223
adb shell am force-stop io.github.notebook.android.nexttest
adb shell am instrument -w \
  -e class 'io.github.notebook.android.WebdavProcessDeathInstrumentedTest#offlineRestartKeepsPendingChange' "$RUNNER"
adb reverse tcp:2223 tcp:2223
adb shell am force-stop io.github.notebook.android.nexttest
adb shell am instrument -w \
  -e class 'io.github.notebook.android.WebdavProcessDeathInstrumentedTest#restoredNetworkPublishesPendingChange' "$RUNNER"
```

进程死亡、跨设备恢复、无变化快速路径、恶意 XML、附件哈希修复和私密数据不出端都是发布前的关键回归；不要用跳过、延长超时或放宽断言来掩盖竞态。

运行结束后移除 `adb reverse`，停止本次只读模拟器和夹具。构建产物在 `app/build/outputs/apk/debug/app-debug.apk`，lint 报告在 `app/build/reports/lint-results-debug.html`，设备测试报告在 `app/build/reports/androidTests/connected/debug/`。

## 改动检查表

1. 先阅读相关 repository、DAO、协议与现有测试，明确本地事实来源和远端发布顺序。
2. 只在隔离数据、隔离端口和 debug 包中复现；不得连接真实手机或正式同步目录。
3. 为运行时差异增加 instrumentation 回归，纯 JVM/Robolectric 测试不能证明 Android 平台实现兼容。
4. 运行聚焦测试后，再运行单元测试、lint、debug 构建和相关 connected suites。
5. 交接时记录分支与提交、实际命令、通过/失败数量、模拟器 serial、端口、fixture 路径和产物路径。

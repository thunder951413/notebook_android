# Notebook Android 工程交接

本文面向继续维护本仓库的开发者和编码代理。开始修改前先确认当前分支、工作区状态和测试目标，不要把桌面端 `notebook` 仓库中的 Android 评估夹具当成产品源码。

## 当前代码线

- Android 产品源码独立维护在 `thunder951413/notebook_android`。
- 2026-10-11 的系统笔记风格改版在 `codex/coloros-notes-ui`；已收拢 `0c5d76b` 的 10 月数据修复与 `b361308` 的诊断加固。修复和测试结论必须注明具体提交，不要把旧 `next` 或 `main` 的行为外推到当前分支。
- Debug 包名是 `io.github.notebook.android.nexttest`，可与正式包并存。隔离测试只使用 debug 包、测试服务器和专用远端目录。
- 用户已授权 OPPO 真机安装和手动 UI 校准，当前预览构建为 `0.4.0-ui.4-debug`（10045）。会清空数据库的 instrumentation 仍仅限专用模拟器；真机只使用独立预览包及其演示数据。完整键盘须以画面／实际触摸输入确认，不能只依据输入法的 shown 状态；详见 `docs/UI_REFERENCE_IMPLEMENTATION.md`。
- 微信输入法在真机 ADB 硬件字符注入后已两次出现“报告已显示、实际只有候选条或无完整键盘”。不要使用 `adb shell input text` 或 Ctrl+A 等硬件组合键填写账号、密码或正文；优先由用户粘贴，其他原生文本设置必须先验证。恢复时仅重启并重新绑定同一个输入法服务，不清除输入法数据，保持默认输入法和 `show_ime_with_hard_keyboard` 原值。Back 等导航按键不属于此限制。
- 不要把真实 WebDAV、SSH 或 Notebook Next 凭据写入源码、Gradle 参数文件、测试日志或提交。

## 架构与数据边界

`MainActivity.kt` 是 Compose 界面入口，`reference/` 分别实现首页、设计组件、编辑工具、格式映射、表格、导出、设置及容器动画；`SyncRepository` 负责 Room、本地草稿、提醒更新和三种同步后端之间的协调。Room 是可编辑内容的本地事实来源，编辑器输入先进入 durable draft，再由 repository 合并到笔记、历史与同步 outbox。

正式的新配置使用 WebDAV v4 日志协议：

```text
<remotePath>/
  journal/<device-id>.jsonl
  journal/<device-id>.head.json
  objects/<hash-prefix>/<sha256>
```

每台设备只追加或压缩自己的 journal。同步先发现所有设备日志并拉取，再发布本机 outbox；head 文件用于无变化快速路径，不能取代 journal 的完整校验。PROPFIND 解析失败必须终止本次同步，不能解释为“远端为空”。私密库在配置同步密码后通过 AES-256-GCM 加密发布笔记、步骤和附件。共享加密格式由 `WebdavPrivacy.kt` 与跨端固定向量测试约束；缺少密码时必须拒绝发布私密数据，不能退化为明文。

SSH/SFTP Markdown v3 与 Notebook Next HTTP API 只承担既有部署的迁移兼容。修改共享实体、附件或历史结构时，仍需检查这两条兼容路径，避免迁移期间破坏旧数据。

## 编辑与生命周期约束

- 正常输入通过 600 ms 防抖自动保存；输入面按 Markdown 块呈现原生文本、图片、代码与表格，文本使用可逆 Markdown 显示映射，源码模式与格式面板共用正文源。超过 150,000 UTF-16 单位时分段编辑，不能把全文交给单个原生布局。`EditorSurfaceState` 在文档容器之外保存展示状态；全屏表格放在文档层，不能在 LazyColumn 的可见项内声明弹窗。
- SQLite `length/substr` 按 Unicode 码点计数，读取分块必须按请求的码点数推进，不能按 Kotlin `String.length` 推进。
- 主动恢复有明确的 `restoring` 路径；普通旧草稿仍不能把已删除内容复活。
- 彻底删除使用持久 tombstone 拦截迟到保存与进程重启草稿，并在同一 Room 事务中删除正文、草稿、同步投影和提交删除 outbox。WebDAV/API 重放已删除页时只推进版本与删除基线，不导入旧内容；SFTP 拉取不得重新导入本机 tombstone 对应页。
- 返回、Activity 停止、切换预览、删除和同步边界必须刷新最新编辑状态。
- 层级移动后，迟到草稿仍可保存正文，但较新的 `treeUpdatedAt` 决定库、父级和排序，不能用旧编辑器快照移回原处。`movePage` 的结构时间须大于整棵子树原时间，操作不因关闭结构面板而取消。
- 保存失败时保留 Room draft 并展示错误；不要因为旧保存任务完成就删除更新的草稿。
- Android 没有可靠的“应用退出”回调。`onStop` 只负责安排持久化与后续 worker，正确性不能依赖进程继续存活。
- `MainActivityTest` 必须取消遗留 WorkManager 任务、刷新旧草稿并清空 Room，避免上一次设备测试保存的同步配置或数据影响下一项 UI 用例。

## 安全约束

- Release 同步只允许 HTTPS；debug 构建才允许回环 HTTP 测试服务器。
- Basic 凭据不得跨重定向转发，服务端响应正文不得拼进错误信息。
- WebDAV XML 响应有固定大小、href 数量和单项长度上限。DOCTYPE、实体声明和外部解析必须被拒绝；损坏 XML 应抛出明确错误。
- 附件下载写入临时文件，校验长度和 SHA-256 后再替换目标；所有相对路径都必须经过应用私有根目录边界检查。
- 私密文件夹是设备锁访问控制加 Android 应用私有存储，不是额外的应用层静态加密。配置同步密码后可加密同步及恢复；本地访问仍需设备凭据。JSON 日志中的实体标识不等于正文保密，测试必须检查标题、正文、文件名和附件明文不会出现在远端。

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
  -e processDeathPhase prepare \
  -e webdavBaseUrl http://127.0.0.1:2223/dav/ \
  -e webdavRemotePath notebook_process_test "$RUNNER"
adb reverse --remove tcp:2223
adb shell am force-stop io.github.notebook.android.nexttest
adb shell am instrument -w \
  -e class 'io.github.notebook.android.WebdavProcessDeathInstrumentedTest#offlineRestartKeepsPendingChange' \
  -e processDeathPhase offline \
  -e webdavBaseUrl http://127.0.0.1:2223/dav/ \
  -e webdavRemotePath notebook_process_test "$RUNNER"
adb reverse tcp:2223 tcp:2223
adb shell am force-stop io.github.notebook.android.nexttest
adb shell am instrument -w \
  -e class 'io.github.notebook.android.WebdavProcessDeathInstrumentedTest#restoredNetworkPublishesPendingChange' \
  -e processDeathPhase restored \
  -e webdavBaseUrl http://127.0.0.1:2223/dav/ \
  -e webdavRemotePath notebook_process_test "$RUNNER"
```

进程死亡、跨设备恢复、无变化快速路径、恶意 XML、附件哈希修复和私密数据不出端都是发布前的关键回归；不要用跳过、延长超时或放宽断言来掩盖竞态。

运行结束后移除 `adb reverse`，停止本次只读模拟器和夹具。构建产物在 `app/build/outputs/apk/debug/app-debug.apk`，lint 报告在 `app/build/reports/lint-results-debug.html`，设备测试报告在 `app/build/reports/androidTests/connected/debug/`。

## 改动检查表

1. 先阅读相关 repository、DAO、协议与现有测试，明确本地事实来源和远端发布顺序。
2. 只在隔离数据、隔离端口和 debug 包中复现；不得连接真实手机或正式同步目录。
3. 为运行时差异增加 instrumentation 回归，纯 JVM/Robolectric 测试不能证明 Android 平台实现兼容。
4. 运行聚焦测试后，再运行单元测试、lint、debug 构建和相关 connected suites。
5. 交接时记录分支与提交、实际命令、通过/失败数量、模拟器 serial、端口、fixture 路径和产物路径。

## 2026-10-11 界面范围

手机使用笔记／笔记库双标签、卡片、浮动工具组与阅读优先的编辑流程；840 dp 起保留三栏。会议实时转写、AI 实际生成、OCR 实际处理和大字体专项适配已由用户排除，不添加占位入口。正文颜色、字体、下划线和对齐等暂未开放，必须先解决共享规范 Markdown 的往返契约。分组设置继续复用现有 WebDAV、旧协议迁移与权限界面。

研究与交付详情见 `docs/UI_REFERENCE_IMPLEMENTATION.md`。视觉样本入口 `DebugUiStudyActivity` 仅存在于 debug source set，需要显式 `seedReferenceSamples=true`，不会清空数据库。设备测试只使用隔离模拟器与 `.nexttest` 包。

## 2026-10-12 层级导航

用户确认 Android 优先，采用“笔记库 → 主笔记 → 子笔记”，主笔记也有正文并能继续嵌套。`reference/NoteHierarchy.kt` 对可访问摘要建立只读展示索引；`reference/ReferenceHierarchy.kt` 实现全局树、局部结构图、路径搜索、最近、固定及阅读／编辑路径。引用关系图与正文标题目录继续独立。

复用已有 `parentPageId`、`sortOrder`、`treeUpdatedAt` 与跨端同步，不增加 Room 表或更换协议。最近和固定是本机导航偏好，只保存 ID，显示时重新检查可访问性；未解锁私密数据与已删除笔记不进入这些入口。非法父级、跨库关系和环只在展示索引降级，不自动修改持久化数据。

新增 `HierarchyUiTest` 必须只运行在隔离模拟器，其 `@Before` 清空 Room。最终构建单元 187 项通过，Lint 0 errors／33 warnings；完整深色回归 44 项、受影响浅色回归 23 项通过。最后加入合法 ID 命名空间与同名正文摘要两项后，最终安装包的层级专项在深／浅色各 12 项全部通过。详见 `docs/NOTE_HIERARCHY_DESIGN.md`。

`DebugUiStudyActivity` 增加显式 `seedHierarchySamples=true`，幂等添加 10 篇层级演示，保留原 6 篇。真机仅更新独立预览包；交付报告、真实截图、30 秒录屏、APK 和哈希位于桌面工作区 `docs/audits/2026-10-12/note-hierarchy-implementation-report.md` 与 `output/notes-hierarchy-design-2026-10-11/`。

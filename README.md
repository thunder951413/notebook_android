# Notebook Android

项目迁移、架构约束、自动保存/提醒说明、完整使用场景审计和 AI 接手步骤见 [`AI_HANDOFF.md`](AI_HANDOFF.md)。后续开发应先阅读该文档。

这是 Notebook 的 Android 移动端子项目。应用离线优先，本地使用 Room 保存数据。当前推荐与 Electron 桌面版共用 SSH/SFTP Markdown v3 仓库；Notebook Next HTTP API 保留为可选的多用户或公网服务方案。

SSH/SFTP Markdown v3 的交换文件包括：

- `library.json`：文件夹和标签
- `notes/index.json`：笔记版本索引与删除记录
- `notes/<UUID>.json.gz`：GFM Markdown 正文、哈希引用历史和页面元数据
- `objects/<前两位>/<SHA-256>`：图片、音频和附件的内容寻址资源
- `notes/assets_manifest.json`：资源索引和校验信息

读取端仍兼容旧版 UUID JSON 与 `attachments/`，但新写入只使用紧凑的 v3 格式。

## 已实现

- 笔记和 Todo 的新建、编辑、完成、重要标记、搜索、软删除/回收站
- 文件夹、标签和 macOS 数据模型的本地存储
- 一次性、每日、每周、每月本地通知，权限状态提示、重启恢复及点击直达项目
- 无保存按钮的自动保存、后台/返回强制刷新与崩溃草稿恢复
- SSH 设置的加密保存
- macOS Swift `JSONEncoder` 日期（2001 reference date）兼容
- `library.json`、gzip 笔记、内容寻址资源和哈希历史的双向 SFTP 同步
- 本地脏数据、远端版本检测及冲突标记
- 图片、音频、文件附件的上传、下载、预览和系统打开
- 原生语音录制和 Todo 步骤
- 文件夹/标签管理、冲突解决、回收站恢复与彻底删除
- 手机抽屉式导航和 Pad 常驻三栏布局
- 远端索引互斥锁、删除墓碑、后台同步与开机提醒恢复
- Notebook Next 游标同步、幂等 outbox、乐观冲突、附件二进制往返，以及网页 TipTap JSON 与 Android Markdown 的转换
- Notebook Next 服务地址、工作区 ID 和 Token 的加密保存

## 构建

环境已配置为 OpenJDK 17、Android SDK 35、Build Tools 35 和 Gradle Wrapper 8.9。用 Android Studio 直接打开本目录，或执行：

```bash
./gradlew assembleDebug
```

已在本机真实执行并通过 `assembleDebug`，调试 APK 输出到 `app/build/outputs/apk/debug/app-debug.apk`。

迁出时直接移动整个 `android-app` 目录即可，它不依赖上级 Xcode 项目。

## 同步设置

与 Electron 新版同步时，在 Android 设置中选择“旧版 SSH”（后续界面会改名为“SSH/SFTP”），填写与桌面端“设置 → 同步与连接”相同的主机、端口、用户名、密码和远程目录。Android 使用密码认证，并强制校验 SSH SHA-256 主机指纹。服务器目录必须允许该用户创建目录、上传临时文件和原子重命名。可在可信电脑上查询指纹并与服务器管理员提供的值核对：

```bash
ssh-keyscan -p 22 your-server.example | ssh-keygen -lf - -E sha256
```

Notebook Next HTTP API 是可选同步方式。使用时填写相同的服务地址、工作区 ID 和访问 Token；正式 Android 构建要求 HTTPS，通过 ADB 验证的 debug 构建才允许 `http://127.0.0.1`。

## 界面设计

手机端沿用 macOS 端暖白背景、琥珀黄强调色、分组导航和摘要列表的设计语言。桌面端三栏结构在手机上转换为“抽屉侧栏 → 摘要列表 → 编辑器”，避免把三栏强行压缩到窄屏；导航分类与 macOS 端保持对应。

Pad 宽度达到 840dp 后使用“常驻侧栏 + 摘要列表 + 编辑器”三栏布局；手机使用“抽屉侧栏 → 摘要列表 → 全屏编辑器”。两种布局已分别在 API 35 ARM64 手机和平板模拟器上运行和截图检查。

## 验证

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
./gradlew connectedDebugAndroidTest
```

当前测试覆盖协议日期、索引墓碑合并、OpenSSH 指纹、块文档与富文本往返、Room 持久化/级联删除/并发同步确认、Notebook Next HTTP 契约，以及设备环境下的新建、编辑和保存主流程。

Notebook Next 真机往返测试要求本机 8787 端口运行隔离服务并执行 `adb reverse tcp:8787 tcp:8787`，随后运行：

```bash
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.github.notebook.android.ApiSyncDeviceTest
```

真实 SFTP 往返测试可使用隔离服务器运行：

```bash
python3 -m pip install -r scripts/requirements-test.txt
python3 scripts/test_sftp_server.py --root /tmp/notebook-sftp-root --port 2222
# 把服务器启动时输出的 SHA256:... 传给设备测试
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.sftpFingerprint=SHA256:...
```

该测试会从模拟器完成密码认证、主机指纹校验、笔记和音频附件上传、索引/manifest 原子提交、删除本地数据及服务器回拉恢复。

## GitHub Release 与应用升级

应用默认从公开仓库 `thunder951413/notebook_android` 的 `android-v*` GitHub Release 检查更新，GitHub Actions 发布时会自动把当前仓库地址编入 APK。启动时发现比当前 `versionName` 更新的正式版本，会提示下载；APK 下载完成后校验 Release 中的 SHA-256 文件，再交给 Android 系统安装程序升级。Android 首次侧载升级时需要用户允许本应用“安装未知应用”，系统始终会要求最终安装确认。

更新仓库必须公开，因为 Android 应用不会内置具有私有仓库访问权的 GitHub Token。如果源码仓库保持私有，请把 Release 发布到单独的公开仓库，并通过 `-PgithubRepository=owner/public-release-repository` 指定更新源。

发布工作流位于 `.github/workflows/release-android.yml`。仓库需要配置以下 GitHub Actions Secrets：

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

`ANDROID_KEYSTORE_BASE64` 可用以下命令生成：

```bash
base64 < release.jks | tr -d '\n'
```

推送版本标签即可构建并发布签名 APK 和校验文件：

```bash
git tag android-v0.2.0
git push origin android-v0.2.0
```

正式签名证书必须永久保管；丢失或更换证书后，已安装用户无法直接覆盖升级。更新源可在构建时通过 `-PgithubRepository=owner/repository` 覆盖。

## 后续增强

- 表格块在 Android 会无损保留并同步，但暂时没有专用表格编辑器。
- 关联笔记、加密文件夹、离线导入导出和 AI 配置仍需增加 Android 界面。
- 发布前应在真实 SSH 测试目录中完成 macOS → Android → macOS 的附件和冲突演练，并配置正式签名。

建议先用一个测试用远程目录联调，确认 macOS 往返不会改变原始笔记后，再连接正式数据目录。

# 系统笔记风格 Android 改版

2026-10-11，分支 `codex/coloros-notes-ui`。参考应用为用户已授权 ADB 研究的 ColorOS 系统“笔记” `com.oplus.aipaper`。实现收拢 `0c5d76b` 的私密加密、快照及依赖恢复与 `b361308` 的诊断、草稿及安全 XML 加固。数据库仍为 Room version 13，共享 Markdown 与同步协议未更换。

## 本轮范围

手机首页使用“笔记／笔记库”双标签、白色圆角卡片、彩色封面、悬浮工具组；现有 Todo、提醒、私密库、冲突和子页面继续可达。阅读优先，编辑时显示 Markdown 格式、原生图片／代码块／表格，支持撤销重做、格式面板和源码模式。840 dp 起使用常驻三栏。

搜索展开后隐藏底部导航；标题、创建时间、修改时间及正文关键字按已有数据查询；附件与归档范围可筛选。筛选子菜单保留淡化父层，返回先关闭子层。长按进入多选，可移动、复制、复制内容和删除。普通笔记库支持颜色、重命名、添加已有、解散并保留笔记或连同笔记移入回收站。

已实现 Markdown、TXT、DOCX、PDF 与四套 PNG 模板导出，图片比例包含长图、3:4、1:1。Markdown 原样保存；其他格式生成可阅读文本，附件仍留在原笔记。超过固定图片画布可容纳的内容会提示失败，不静默截断。文件通过 SAF 保存或 Android 系统分享。

用户排除了会议实时转写、AI 实际生成、OCR 实际处理和大字体专项适配。普通录音、图片、文件／视频附件继续复用已有资源与权限流程。

## 代码入口

| 文件 | 职责 |
| --- | --- |
| `MainActivity.kt` | 数据订阅、阅读／编辑、生命周期刷新、附件、私密凭据与导航 |
| `reference/ReferenceHome.kt` | 双标签、搜索、多选、库操作、列表位置恢复 |
| `reference/ReferenceComponents.kt` | 卡片、工具、封面、胶囊、空状态与统一尺寸 |
| `reference/ReferenceFilters.kt` | 父子筛选浮层与返回顺序 |
| `reference/ReferenceEditor.kt` | 正文输入、IME 与格式／添加面板 |
| `reference/EditorSurfaceState.kt` | 根层持有的面板、选区、源码模式与全屏表格范围 |
| `reference/RichMarkdownFields.kt` | 基于全局源范围的原生正文内容块 |
| `reference/MarkdownEditing.kt` | 可逆显示映射、Markdown 命令、分组撤销 |
| `reference/LongMarkdownEditor.kt` | 超过 150,000 UTF-16 单位的分页输入 |
| `reference/MarkdownTable.kt` | 同一张 GFM 表的全屏编辑及横竖屏恢复 |
| `reference/NoteExport.kt` | 实际文件输出、图片模板、保存与分享 |
| `reference/DocumentContainer.kt` | 卡片到全文的几何展开及预测返回 |
| `reference/ReferenceSettings.kt` | 分组设置、编辑偏好、已有同步配置 |

全文表格弹窗位于文档层，独立于阅读列表的可见项；表格返回和完成都会保存编辑结果。打开文档时保持展示容器，旋转不会重新切换根导航。文本输入保持全局 UTF-16 源偏移。中文组合输入期间保留活动块的投影，完成提交后再解析。格式命令与普通键入、标题键入分别进入撤销组。正文源是唯一持久化依据；图片及引用仍使用既有 canonical asset/page 语法。

复制笔记会复制资源及历史引用并重新绑定附件 ID；移动笔记库归属同时处理子页面。旧附件编号按完整标识符映射；大正文与快照分段读取后复制，避免前缀编号混淆及 CursorWindow 溢出。主动恢复走明确 `restoring` 路径，旧草稿仍不能把已删除笔记复活。SQLite 分块读取按码点推进，避免 Emoji 造成跳段。

## 可安装构建与复现

```bash
VERSION_CODE=10043 VERSION_NAME=0.4.0-ui.2 \
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
ANDROID_HOME=/opt/homebrew/share/android-commandlinetools \
./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
```

Debug 包名 `io.github.notebook.android.nexttest`，显示名称“Notebook UI 预览”。测试只运行在专用模拟器。`DebugUiStudyActivity` 仅存在于 debug source set，显式传入 `seedReferenceSamples=true` 后幂等添加演示数据，不清空原数据库：

```bash
adb -s emulator-5554 shell am start -W \
  -n io.github.notebook.android.nexttest/io.github.notebook.android.DebugUiStudyActivity \
  --ez seedReferenceSamples true
```

关键回归包括格式撤销、Android InputConnection 组合输入、混合内容块编辑、表格旋转、长 Unicode 正文、库归属与解散、附件复制、删除恢复、自动保存重建、WebDAV/SFTP、私密密文恢复。进程死亡回归需主机按 prepare → offline → restored 顺序驱动，命令见 `AI_HANDOFF.md`。

## 边界

- 正文字体、颜色、下划线和对齐暂未开放；现有桌面导入不能保证这些样式往返。普通 Markdown、未知块和源码继续保留。
- 格式编辑的原生选区局限于活动文本块；跨块精确选择可切换 Markdown 源码。超长正文以分页源码编辑限制布局规模。
- 动效使用公共 Android／Compose 能力；原厂私有模糊、字体及动画参数未声称逐帧一致。用户选择先完成模拟器验收，手机恢复连接后的 OPPO 实机手感校准仍为独立后续步骤。
- 普通录音使用原生录音与资源链路，不包含转写。媒体通过应用图片呈现或系统兼容查看器打开。

最终验收报告、截图、录屏与 APK 位于桌面工作区 `docs/audits/2026-10-11/android-ui-implementation-report.md` 和 `output/android-ui-implementation-2026-10-11/`；这些本地取证文件不随独立 Android 仓库发布。

## 2026-10-11 冲突笔记彻底删除修复

用户报告旧版笔记同步冲突后移入回收站，冲突详情的“彻底删除”看似没有反应。旧版详情按钮只调用 repository，不关闭详情；当前界面已在成功后退出，并补充删除失败提示。共享数据层的两条复活路径也已复现：退出编辑器的迟到草稿重新插入已删记录，WebDAV pull-first 重放远端冲突内容后丢失删除结果；SFTP 同样缺少本地 tombstone 拉取保护。

`0.4.0-ui.2-debug` 的永久删除与同步/保存互斥，取消待保存草稿，在 Room 事务中提交 tombstone、删除 outbox，并清理笔记、草稿、API 投影及关联数据。附件/步骤等待发送 upsert 被替换为归属明确的 delete，避免先删正文却残留上传任务。持久 tombstone 阻止退出保存、重启恢复、WebDAV/API 旧记录重放与 SFTP pull 复活同一个 ID；HTTP API 乐观锁拒绝删除时保留新版本基线上的重试操作。

进程恢复验收另复现 WebDAV 配置异步写入后立即终止进程会读回旧 SFTP 配置。`saveWebdavSettings` 改为确认落盘，配置保存失败由设置页面显示；主机阶段加入后端断言，避免“断网失败”实际走错后端仍被计为通过。

聚焦回归覆盖冲突详情、回收站多选、迟到已删草稿、重启恢复、子页面及关联 outbox、不相关笔记保留、WebDAV 双端真实冲突、新设备恢复、SFTP 双端冲突与 HTTP API 删除重试。最终单元测试 178 项通过；设备回归 47 项通过。完整设备运行的 5 项条件跳过包括 3 个需主机按顺序驱动的进程死亡阶段，另行执行且通过；其余 2 项依赖未配置外部 API 和旧 SFTP 服务。

本轮本地取证与新版 APK 位于桌面工作区 `output/android-conflict-delete-2026-10-11/`；设备为隔离 API 35 模拟器 `emulator-5554`，测试服务仅使用本机 2222/2223 端口。实际手机旧安装和生产同步目录未在本轮修改。

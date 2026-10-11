package io.github.notebook.android

import android.app.Activity
import android.content.Intent
import android.graphics.*
import android.os.Bundle
import io.github.notebook.android.data.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*

/**
 * Opt-in, idempotent visual fixtures in the separate debug application only. Never clears a
 * database.
 */
class DebugUiStudyActivity : Activity() {
    private fun studyId(name: String) = UUID.nameUUIDFromBytes(name.toByteArray()).toString()

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val app = application as NotebookApp
            val repo = app.repository
            val prefs = getSharedPreferences("ui-study-fixtures", 0)
            if (
                intent.getBooleanExtra("seedReferenceSamples", false) &&
                    !prefs.getBoolean("seeded", false)
            ) {
                repo.saveFolder("日常灵感", id = studyId("ui-study-library-1"), color = "#AAD6EC")
                repo.saveFolder("工作与学习", id = studyId("ui-study-library-2"), color = "#C2DFC1")
                repo.saveFolder("旅行清单", id = studyId("ui-study-library-3"), color = "#F3C5AD")
                repo.saveTag("灵感", id = studyId("ui-study-tag-1"))
                repo.saveTag("工作", id = studyId("ui-study-tag-2"))
                val samples =
                    listOf(
                        Triple(
                            studyId("ui-study-note-1"),
                            "慢下来，记下此刻",
                            "## 灵感不必完整\n\n把一个想法写下来，再慢慢让它变得清楚。\n\n**记录**让思考有了落点。\n\n- [x] 留出十分钟\n- [ ] 写下今天的发现\n\n## 下一步\n\n把值得继续的想法放进笔记库。",
                        ),
                        Triple(
                            studyId("ui-study-note-2"),
                            "本周工作安排",
                            "## 三件值得完成的事\n\n- [ ] 完成首页与编辑交互\n- [x] 核对自动保存\n- [ ] 回看转场录屏\n\n| 项目 | 状态 |\n| --- | --- |\n| 首页 | 已完成 |\n| 编辑器 | 验证中 |\n",
                        ),
                        Triple(
                            studyId("ui-study-note-3"),
                            "周末去走走",
                            "沿着河边散步，找一家安静的咖啡店。\n\n把相机带上，给日常留下一张照片。\n\n![散步](notebook-asset:${studyId("ui-study-image")})\n\n",
                        ),
                        Triple(
                            studyId("ui-study-note-4"),
                            "一本书，一段想法",
                            "## 阅读札记\n\n> 思考需要时间，也需要一个可以返回的地方。\n\n今天读到的这一段，让我想起上周还没有写完的笔记。\n\n### 留给自己\n\n不是每一次记录都需要结论。",
                        ),
                        Triple(
                            studyId("ui-study-note-5"),
                            "未分类也有它的位置",
                            "暂时没有主题的想法，可以先放在这里。整理是下一步。",
                        ),
                        Triple(
                            studyId("ui-study-note-6"),
                            "Markdown 与同步样本",
                            "## 格式往返\n\n中文、**粗体**、_斜体_、~~删除线~~。\n\n```kotlin\nval idea = \"继续记录\"\n```\n\n链接：https://example.com\n",
                        ),
                    )
                samples.forEachIndexed { index, (id, title, body) ->
                    repo.save(
                        NoteEntity(
                            id,
                            title = title,
                            body = body,
                            folderId =
                                when (index) {
                                    0,
                                    3 -> studyId("ui-study-library-1")
                                    1,
                                    5 -> studyId("ui-study-library-2")
                                    else -> null
                                },
                            folderName =
                                when (index) {
                                    0,
                                    3 -> "日常灵感"
                                    1,
                                    5 -> "工作与学习"
                                    else -> "未分类"
                                },
                            tagIds = if (index == 0) studyId("ui-study-tag-1") else "",
                            viewMode = "preview",
                        )
                    )
                }
                val file =
                    File(
                            app.filesDir,
                            "attachments/${studyId("ui-study-note-3")}/ui-study-image.png",
                        )
                        .apply { parentFile?.mkdirs() }
                val bitmap = Bitmap.createBitmap(560, 400, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.drawColor(Color.rgb(221, 235, 236))
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(172, 204, 184) }
                canvas.drawCircle(430f, 110f, 82f, paint)
                paint.color = Color.rgb(100, 154, 137)
                canvas.drawPath(
                    Path().apply {
                        moveTo(0f, 400f)
                        lineTo(0f, 260f)
                        lineTo(180f, 140f)
                        lineTo(390f, 400f)
                        close()
                    },
                    paint,
                )
                paint.color = Color.rgb(151, 182, 198)
                canvas.drawPath(
                    Path().apply {
                        moveTo(190f, 400f)
                        lineTo(400f, 210f)
                        lineTo(560f, 280f)
                        lineTo(560f, 400f)
                        close()
                    },
                    paint,
                )
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
                app.database
                    .dao()
                    .putAssets(
                        listOf(
                            AssetEntity(
                                studyId("ui-study-image"),
                                studyId("ui-study-note-3"),
                                "image",
                                "散步.png",
                                "image/png",
                                "${studyId("ui-study-note-3")}/ui-study-image.png",
                                file.absolutePath,
                                contentHash =
                                    file
                                        .inputStream()
                                        .use(
                                            io.github.notebook.android.sync.WebdavJournalProtocol::
                                                sha256
                                        ),
                                size = file.length(),
                                dirty = true,
                            )
                        )
                    )
                prefs.edit().putBoolean("seeded", true).commit()
            }
            if (
                intent.getBooleanExtra("seedHierarchySamples", false) &&
                    !prefs.getBoolean("hierarchy-seeded", false)
            ) {
                val library = studyId("hierarchy-study-library")
                repo.saveFolder("项目与知识", id = library, color = "#C2DFC1")
                val samples =
                    listOf(
                        Triple("app", "Android 界面改版", null),
                        Triple("home", "首页布局", "app"),
                        Triple("navigation", "层级导航", "app"),
                        Triple("quick", "快速定位", "navigation"),
                        Triple("map", "结构可视化", "navigation"),
                        Triple("app-check", "验收记录", "app"),
                        Triple("travel", "旅行准备", null),
                        Triple("transport", "交通安排", "travel"),
                        Triple("alternative", "备选方案", "transport"),
                        Triple("travel-check", "验收记录", "travel"),
                    )
                samples.forEachIndexed { i, (key, title, parent) ->
                    repo.save(
                        NoteEntity(
                            studyId("hierarchy-study-$key"),
                            title = title,
                            body =
                                "## $title\n\n这是用于层级导航验收的独立演示笔记。主笔记和子笔记都可以保存正文。\n\n- 保留 Markdown 与同步关系\n- 从路径与结构树快速定位\n",
                            folderId = library,
                            folderName = "项目与知识",
                            parentPageId = parent?.let { studyId("hierarchy-study-$it") },
                            sortOrder = (i + 1) * 1000.0,
                            viewMode = "preview",
                        )
                    )
                }
                prefs.edit().putBoolean("hierarchy-seeded", true).commit()
            }
            withContext(Dispatchers.Main) {
                startActivity(Intent(this@DebugUiStudyActivity, MainActivity::class.java))
                finish()
            }
        }
    }
}

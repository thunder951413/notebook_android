package io.github.notebook.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.notebook.android.sync.ApiSyncSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ApiSyncDeviceTest {
    @Test fun migratedWebBundlePullsWithBodyAndAssetThenAndroidEditPushesBack()=runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NotebookApp
        val repo=app.repository
        repo.saveApiSettings(ApiSyncSettings("http://127.0.0.1:8787","00000000-0000-4000-8000-000000000001","adb-e2e-token"))
        repo.sync()

        val note=app.database.dao().get("adb-page")!!
        assertEquals("ADB 迁移链路验收",note.title)
        assertEquals("# 迁移成功\n**网页 → 新服务器 → Android 正文完整。**",note.body)
        assertEquals("adb-book",app.database.dao().getFolder("adb-section")?.notebookId)
        val asset=app.database.dao().assets("adb-page").single{it.id=="adb-asset"}
        assertEquals("ADB-ASSET-CONTENT",java.io.File(asset.localPath!!).readText())

        val changed=repo.save(note.copy(title="Android 已回推",body=note.body+"\n\n真机增量修改"))
        repo.sync()
        assertFalse(app.database.dao().get(changed.id)!!.dirty)
        assertTrue(app.database.dao().apiOutbox("00000000-0000-4000-8000-000000000001").isEmpty())
    }
}

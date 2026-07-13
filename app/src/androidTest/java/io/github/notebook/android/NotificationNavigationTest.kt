package io.github.notebook.android

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.notebook.android.data.NoteEntity
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class NotificationNavigationTest {
    @get:Rule val compose=createEmptyComposeRule()
    @Test fun notificationIntentOpensTargetNote(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext;val app=context.applicationContext as NotebookApp;val id=UUID.randomUUID().toString();val title="通知直达-${System.nanoTime()}";runBlocking{app.repository.save(NoteEntity(id,title=title,body="提醒正文"))}
        val scenario=ActivityScenario.launch<MainActivity>(Intent(context,MainActivity::class.java).putExtra("noteId",id))
        try{compose.waitUntil(5_000){compose.onAllNodesWithTag("markdown-view").fetchSemanticsNodes().isNotEmpty()};compose.onNodeWithText(title).assertIsDisplayed();compose.onNodeWithTag("edit-mode").assertIsDisplayed()}finally{scenario.close()}
    }
}

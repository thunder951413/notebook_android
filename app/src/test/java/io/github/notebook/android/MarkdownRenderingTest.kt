package io.github.notebook.android

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,application=Application::class)
class MarkdownRenderingTest {
    private val context:Context=ApplicationProvider.getApplicationContext()

    @Test fun `single source line break remains visible in reading mode`() {
        val source="第一行\n第二行"
        val renderer=createMarkdownRenderer(context)

        assertEquals(source,renderer.render(renderer.parse(source)).toString())
    }

    @Test fun `paragraph and hard line breaks are not duplicated`() {
        val source="第一段\n\n第二段  \n第三行"
        val renderer=createMarkdownRenderer(context)

        assertEquals("第一段\n\n第二段\n第三行",renderer.render(renderer.parse(source)).toString())
    }
}

package io.github.notebook.android

import android.app.Application
import android.content.Context
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test fun `notebook links remain tappable while rendered text is selectable`() {
        val uri="notebook://page/123e4567-e89b-42d3-a456-426614174000?block=b-one"
        var opened:String?=null
        val renderer=createMarkdownRenderer(context){opened=it}
        val view=TextView(context)
        configureMarkdownTextView(view)
        renderer.setMarkdown(view,"[打开引用]($uri)")

        assertTrue(view.isTextSelectable)
        assertTrue(view.linksClickable)
        assertTrue(view.movementMethod is LinkMovementMethod)
        val spans=(view.text as Spanned).getSpans(0,view.text.length,ClickableSpan::class.java)
        assertEquals(1,spans.size)
        spans.single().onClick(view)
        assertEquals(uri,opened)
    }
}

package io.github.notebook.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NotebookAssetSyntaxTest {
    @Test fun `canonical image references retain id alt and caption`() {
        val source="""说明 ![架构图](notebook-asset:asset-1 "主链路") 结束"""
        val image=NotebookAssetSyntax.images(source).single()
        assertEquals("asset-1",image.assetId)
        assertEquals("架构图",image.alt)
        assertEquals("主链路",image.title)
        assertEquals("说明  结束",NotebookAssetSyntax.withoutImages(source))
    }

    @Test fun `android insertion emits electron compatible markdown`() {
        val markdown=NotebookAssetSyntax.imageMarkdown("asset-2","截图.png")
        assertEquals("asset-2",NotebookAssetSyntax.images(markdown).single().assetId)
        assertFalse(markdown.contains("file://"))
    }
}

package io.github.notebook.android

internal data class NotebookAssetReference(
    val assetId:String,
    val alt:String,
    val title:String?,
    val start:Int,
    val end:Int
)

/** Parser for canonical content-addressed asset references embedded in Markdown. */
internal object NotebookAssetSyntax {
    private val image=Regex("""!\[([^\]\n]*)]\(notebook-asset:([A-Za-z0-9._:-]+)(?:[ \t]+["']([^"'\n]*)["'])?\)""")
    fun images(markdown:String):List<NotebookAssetReference> = image.findAll(markdown).map{match->
        NotebookAssetReference(
            assetId=match.groupValues[2],
            alt=match.groupValues[1],
            title=match.groupValues[3].ifBlank{null},
            start=match.range.first,
            end=match.range.last+1
        )
    }.toList()
    fun withoutImages(markdown:String)=image.replace(markdown,"").trim()
    fun imageMarkdown(assetId:String,filename:String)=
        "![${filename.replace("]","\\]")}](notebook-asset:$assetId)"
}

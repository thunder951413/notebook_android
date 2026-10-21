package io.github.notebook.android.tts

/** Turns note Markdown into plain text that sounds right when read aloud, in request-sized pieces. */
object TtsText {
    private val fenced = Regex("(?s)(```|~~~).*?(\\1|$)")
    private val image = Regex("!\\[[^\\]]*]\\([^)]*\\)")
    private val pageRef = Regex("!?\\[\\[page:[^\\]]*]]")
    private val wikiLink = Regex("\\[\\[([^\\]|]+)(?:\\|([^\\]]*))?]]")
    private val link = Regex("\\[([^\\]]*)]\\([^)]*\\)")
    private val bareUrl = Regex("https?://\\S+")
    private val html = Regex("<[^>\\n]{1,200}>")
    private val tableRule = Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")
    private val rulePattern = Regex("^\\s*([-*_]\\s*){3,}$")
    private val emphasis = Regex("(\\*{1,3}|_{1,3}|~~|`)")
    private val listMarker = Regex("^\\s*(?:[-+*]|\\d+[.)])\\s+(?:\\[[ xX]]\\s+)?")
    private val sentenceEnd = Regex("(?<=[。！？!?；;…])|(?<=[.])(?=\\s)|\\n+")

    fun plainText(markdown: String): String {
        val lines = fenced.replace(markdown, "\n").lines().mapNotNull { raw ->
            var line = raw
            if (rulePattern.matches(line) || tableRule.matches(line)) return@mapNotNull null
            line = line.trimStart().replace(Regex("^#{1,6}\\s+"), "").replace(Regex("^>+\\s?"), "")
            line = listMarker.replace(line, "")
            if (line.contains('|') && raw.trim().startsWith("|")) {
                line = line.split('|').map(String::trim).filter(String::isNotEmpty).joinToString("，")
            }
            line = image.replace(line, "")
            line = pageRef.replace(line, "")
            line = wikiLink.replace(line) { it.groupValues[2].ifBlank { it.groupValues[1] } }
            line = link.replace(line) { it.groupValues[1] }
            line = bareUrl.replace(line, "")
            line = html.replace(line, "")
            line = emphasis.replace(line, "")
            line.trim().takeIf(String::isNotEmpty)
        }
        return lines.joinToString("\n")
    }

    /** One piece to synthesize and where in the source text it starts. */
    data class Piece(val text: String, val start: Int)

    /**
     * Splits [text] on sentence ends into pieces of at most [max] characters. The
     * first piece is short ([firstMax]) so playback starts quickly.
     */
    fun split(text: String, firstMax: Int = 120, max: Int = 400): List<String> = splitWithStarts(text, firstMax, max).map(Piece::text)

    /** [split], also reporting the offset in [text] at which each piece begins. */
    fun splitWithStarts(text: String, firstMax: Int = 120, max: Int = 400): List<Piece> {
        val sentences = ArrayList<Piece>()
        fun addSentence(from: Int, to: Int) {
            val raw = text.substring(from, to)
            val trimmed = raw.trim()
            if (trimmed.any(Char::isLetterOrDigit)) sentences += Piece(trimmed, from + (raw.length - raw.trimStart().length))
        }
        var cursor = 0
        for (match in sentenceEnd.findAll(text)) {
            addSentence(cursor, match.range.first.coerceAtLeast(cursor))
            cursor = match.range.last + 1
        }
        if (cursor < text.length) addSentence(cursor, text.length)
        val pieces = ArrayList<Piece>()
        val current = StringBuilder()
        var currentStart = 0
        fun limit() = if (pieces.isEmpty()) firstMax else max
        fun flush() { if (current.isNotEmpty()) { pieces += Piece(current.toString(), currentStart); current.clear() } }
        for (sentence in sentences) {
            // A sentence longer than the limit is cut at commas, then hard.
            val parts = if (sentence.text.length <= limit()) listOf(sentence.text) else hardSplit(sentence.text, limit())
            var searchFrom = 0
            for (part in parts) {
                val partStart = sentence.start + sentence.text.indexOf(part, searchFrom).coerceAtLeast(0).also { searchFrom = it + part.length }
                if (current.isNotEmpty() && current.length + part.length + 1 > limit()) flush()
                if (current.isEmpty()) currentStart = partStart else current.append(' ')
                current.append(part)
                if (current.length >= limit()) flush()
            }
        }
        flush()
        return pieces
    }

    private fun hardSplit(sentence: String, max: Int): List<String> {
        val result = ArrayList<String>()
        var rest = sentence
        while (rest.length > max) {
            val cut = rest.lastIndexOfAny(charArrayOf('，', ',', '、', ' ', '：', ':'), max - 1).takeIf { it > max / 3 } ?: (max - 1)
            result += rest.substring(0, cut + 1).trim()
            rest = rest.substring(cut + 1)
        }
        if (rest.isNotBlank()) result += rest.trim()
        return result
    }
}

/** A stretch of the spoken text that came from [sourceLength] characters of Markdown starting at [sourceStart]. */
data class SpokenSpan(val textStart: Int, val sourceStart: Int, val sourceLength: Int)

/** The Markdown offset the spoken text at [position] came from, spreading evenly over the span that holds it; -1 without spans. */
fun List<SpokenSpan>.sourceOffsetAt(textLength: Int, position: Int): Int {
    val index = indexOfLast { it.textStart <= position }
    if (index < 0) return firstOrNull()?.sourceStart ?: -1
    val span = this[index]
    val spokenLength = (getOrNull(index + 1)?.textStart ?: textLength) - span.textStart
    if (spokenLength <= 0) return span.sourceStart
    val inside = ((position - span.textStart).toDouble() / spokenLength).coerceIn(0.0, 1.0)
    return span.sourceStart + (inside * span.sourceLength).toInt()
}

package de.foldki.app.ai

/** Detects literal Markdown code, including unfinished streaming fences and inline spans.
 * Masking keeps UTF-16 offsets stable for protocol-tag searches. No code is rewritten.
 */
object CodeText {
    private val fence = Regex("^ {0,3}(" + "\u0060{3,}|~{3,})(.*)$")
    private val indentation = Regex("^(?: {4}|\\t)")
    private val ticks = Regex("\u0060+")

    fun hasBlock(text: String): Boolean = text.lineSequence().any {
        fence.matches(it) || (it.isNotBlank() && indentation.containsMatchIn(it))
    }

    fun hasCode(text: String): Boolean = text.contains('\u0060') || hasBlock(text)

    fun mask(text: String): String {
        if (!hasCode(text)) return text
        val out = text.toCharArray()
        var offset = 0
        var blockChar: Char? = null
        var blockSize = 0
        var inlineSize = 0
        fun hide(from: Int, to: Int) {
            for (i in from until to) if (out[i] != '\n' && out[i] != '\r') out[i] = ' '
        }
        for (line in text.split('\n')) {
            val match = fence.matchEntire(line.removeSuffix("\r"))
            val run = match?.groupValues?.get(1)
            if (blockChar != null) {
                hide(offset, offset + line.length)
                if (run != null && run.first() == blockChar && run.length >= blockSize &&
                    match.groupValues[2].isBlank()) { blockChar = null; blockSize = 0 }
            } else if (inlineSize == 0 && run != null) {
                blockChar = run.first(); blockSize = run.length
                hide(offset, offset + line.length)
            } else if (inlineSize == 0 && indentation.containsMatchIn(line)) {
                hide(offset, offset + line.length)
            } else {
                var pos = 0
                for (m in ticks.findAll(line)) {
                    val start = m.range.first
                    val end = m.range.last + 1
                    if (inlineSize != 0) {
                        hide(offset + pos, offset + end)
                        if (m.value.length == inlineSize) inlineSize = 0
                    } else {
                        inlineSize = m.value.length
                        hide(offset + start, offset + end)
                    }
                    pos = end
                }
                if (inlineSize != 0) hide(offset + pos, offset + line.length)
            }
            offset += line.length + 1
        }
        return String(out)
    }
}

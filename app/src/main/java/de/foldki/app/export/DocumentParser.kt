package de.foldki.app.export

/**
 * Splits an AI answer into: comment before, the document itself, and the comment after it.
 * The AI is asked to put every document (letter, objection, summary, list …) between a line
 * "---DOKUMENT---" and a line "---ENDE---" (the old "---BRIEF---" is still understood).
 * If a small model forgets the markers, a letter is still recognised by its salutation and
 * closing formula. Pure Kotlin (no Android classes), so it is unit-tested on the plain JVM.
 */
object DocumentParser {
    data class Parts(
        val before: String,
        /** The document as clean plain text (no Markdown), or null if the answer contains none. */
        val document: String?,
        val after: String,
        /** false if the answer stopped before ---ENDE--- (aborted or still streaming). */
        val complete: Boolean = true,
        /** The document looks like a letter (salutation + closing formula). */
        val isLetter: Boolean = false,
    )

    private val OPTS = setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)
    private const val DASH = "[-–—_=*]"
    private val START = Regex("^[ \\t>#*]*$DASH{2,}\\s*(DOKUMENT|BRIEF)\\s*$DASH{2,}[ \\t*]*$", OPTS)
    private val END = Regex("^[ \\t>#*]*$DASH{2,}\\s*ENDE\\s*$DASH{2,}[ \\t*]*$", OPTS)

    private val SALUTATION = Regex("^[ \\t*_]*(Sehr geehrte|Hallo|Liebe|Lieber|Guten Tag|Guten Morgen|Moin|Servus)\\b", OPTS)
    private val FORMAL_SALUTATION = Regex("^[ \\t*_]*Sehr geehrte", OPTS)
    private val CLOSING = Regex(
        "^[ \\t*_]*(Mit freundlichen Grüßen|Mit freundlichem Gruß|Freundliche Grüße|Hochachtungsvoll|Viele Grüße|" +
            "Beste Grüße|Liebe Grüße|Herzliche Grüße|Schöne Grüße|Mit besten Grüßen|Gruß|LG|VG|MfG)\\b[^\\n]{0,40}$",
        OPTS,
    )

    /** Chatty lead-in lines a model likes to put in front of a document. */
    private val PREAMBLE = Regex(
        "^(hier (ist|sind|kommt|folgt|wäre|hast du)|gerne|gern|klar|natürlich|okay|ok|alles klar|sicher|selbstverständlich|" +
            "kein problem|so könnte|folgender|nachfolgend|anbei|(das|dies) (ist|wäre) (mein|dein|der|die|ein|eine)|mein vorschlag)\\b",
        RegexOption.IGNORE_CASE,
    )

    fun parse(text: String): Parts {
        // A code example may contain literal document markers or letter greetings.
        if (de.foldki.app.ai.CodeText.hasBlock(text)) return Parts(text, null, "")
        val s = START.find(text)
        if (s != null) {
            val e = END.find(text, s.range.last + 1)
            val raw = text.substring(s.range.last + 1, e?.range?.first ?: text.length)
            val (doc, trailing) = tidy(raw)
            // A small model sometimes only echoes the markers ("---DOKUMENT---" + "ENDE"): that is no document.
            if (isTrivial(doc)) return fallback(stripMarkers(text))
            val after = listOf(trailing, e?.let { stripMarkers(text.substring(it.range.last + 1)) }.orEmpty())
                .filter { it.isNotBlank() }.joinToString("\n\n")
            return Parts(
                before = stripMarkers(text.substring(0, s.range.first)).trim(),
                document = doc.ifBlank { null },
                after = after.trim(),
                complete = e != null,
                isLetter = doc.isNotBlank() && SALUTATION.containsMatchIn(doc) && CLOSING.containsMatchIn(doc),
            )
        }
        return fallback(stripMarkers(text))
    }

    /** Without markers: salutation … closing formula + name line. */
    private fun fallback(text: String): Parts {
        val none = Parts(text.trim(), null, "")
        val sal = SALUTATION.find(text) ?: return none
        CLOSING.find(text, sal.range.first) ?: return none
        // Formal letters have an address/date/subject block above the salutation; take all
        // paragraphs before it that are not a chatty lead-in. Informal ones start at the salutation.
        var start = lineStart(text, sal.range.first)
        if (FORMAL_SALUTATION.matches(text.substring(start, sal.range.last + 1))) {
            val paras = paragraphs(text, 0, start)
            val firstLetterPara = paras.firstOrNull { (a, b) -> !isIntro(text.substring(a, b)) }
            if (firstLetterPara != null) start = firstLetterPara.first
        }
        val (doc, trailing) = tidy(text.substring(start))
        if (doc.isBlank()) return none
        return Parts(
            before = text.substring(0, start).trim(),
            document = doc,
            after = trailing.trim(),
            complete = true,
            isLetter = true,
        )
    }

    /** Cleans the document text and splits off remarks after the closing formula + name. */
    private fun tidy(raw: String): Pair<String, String> {
        val lines = clean(raw).lines().toMutableList()
        // Leading blank lines and chatty lead-ins ("Hier ist dein Schreiben:", "Gerne!")
        while (lines.isNotEmpty() && (lines.first().isBlank() || isIntro(lines.first()))) lines.removeAt(0)
        while (lines.isNotEmpty() && (lines.last().isBlank() || lines.last().trim().matches(Regex("$DASH{3,}")))) lines.removeAt(lines.size - 1)

        var trailing = ""
        val closing = lines.indexOfLast { CLOSING.matches(it) }
        if (closing >= 0) {
            // Keep the name block right after the closing (until the first blank line) and "Anlage(n)".
            var i = closing + 1
            while (i < lines.size && lines[i].isBlank()) i++
            while (i < lines.size && lines[i].isNotBlank()) i++
            var j = i
            while (j < lines.size && lines[j].isBlank()) j++
            if (j < lines.size && lines[j].trim().startsWith("Anlage", ignoreCase = true)) {
                while (j < lines.size && lines[j].isNotBlank()) j++
                i = j
            }
            trailing = lines.drop(i).joinToString("\n").trim()
            while (lines.size > i) lines.removeAt(lines.size - 1)
        }
        return lines.joinToString("\n").trim() to trailing
    }

    private fun isIntro(block: String): Boolean {
        val t = block.trim().replace(Regex("[*_#>`]"), "").trim()
        if (t.isEmpty()) return false
        if (t.length > 160 || t.contains('\n')) return false
        return PREAMBLE.containsMatchIn(t)
    }

    private fun lineStart(text: String, idx: Int): Int = text.lastIndexOf('\n', idx - 1).let { if (it < 0) 0 else it + 1 }

    /** Ranges of non-blank paragraphs in text[from, to). */
    private fun paragraphs(text: String, from: Int, to: Int): List<Pair<Int, Int>> {
        val out = mutableListOf<Pair<Int, Int>>()
        var pos = from
        Regex("\\n[ \\t]*\\n").findAll(text.substring(from, to)).forEach { m ->
            val end = from + m.range.first
            if (text.substring(pos, end).isNotBlank()) out += pos to end
            pos = from + m.range.last + 1
        }
        if (pos < to && text.substring(pos, to).isNotBlank()) out += pos to to
        return out
    }

    private fun stripMarkers(text: String): String =
        text.replace(START, "").replace(END, "").replace(BARE_MARKER, "").replace(Regex("\\n{3,}"), "\n\n").trim()

    /** Lines that are only the marker word, e.g. "ENDE" or "DOKUMENT" without dashes. */
    private val BARE_MARKER = Regex("^[ \\t>#*_-]*(ENDE|DOKUMENT)[ \\t*_.-]*$\\n?", OPTS)

    private fun isTrivial(doc: String): Boolean {
        val t = doc.replace(BARE_MARKER, "").trim()
        return t.length < 8
    }

    /** Removes Markdown so documents look clean in the chat and in the PDF; list items stay readable as "• ". */
    fun clean(text: String): String = if (de.foldki.app.ai.CodeText.hasBlock(text)) text else text.lines().joinToString("\n") { l ->
        var s = l
        if (s.trim().matches(Regex("([-*_])\\1{2,}"))) return@joinToString "" // horizontal rule
        s = s.replace(Regex("^(\\s*)#{1,6}\\s+"), "$1")
        s = s.replace(Regex("^(\\s*)>\\s?"), "$1")
        s = s.replace(Regex("^(\\s*)[*+-]\\s+"), "$1• ")
        // Only matched emphasis delimiters, never standalone operators or identifiers.
        // Inline code is left untouched in the chat; for PDF prose remove its enclosing ticks only.
        val inline = Regex("\u0060([^\u0060\\n]+)\u0060")
        val emphasis = Regex("(?<![\\w*\\\\])\\*\\*(?![\\s*])(.+?)(?<![\\s*])\\*\\*(?![\\w*])|(?<![\\w_\\\\])__(?![\\s_])(.+?)(?<![\\s_])__(?![\\w_])")
        fun formatProse(value: String): String = emphasis.replace(value) { it.groups[1]?.value ?: it.groupValues[2] }
        val spans = inline.findAll(s).toList()
        if (spans.isEmpty()) s = formatProse(s)
        else {
            val original = s
            s = buildString {
                var pos = 0
                for (span in spans) {
                    append(formatProse(original.substring(pos, span.range.first)))
                    append(span.groupValues[1])
                    pos = span.range.last + 1
                }
                append(formatProse(original.substring(pos)))
            }
        }
        if (spans.isEmpty()) s = s.replace(Regex("(?<![\\w*])\\*(?!\\s)([^*\\n]+?)(?<!\\s)\\*(?![\\w*])"), "$1")
        if (spans.isEmpty()) s = s.replace(Regex("\\[([^\\]]+)]\\((https?://[^)]+)\\)"), "$1 ($2)")
        s.trimEnd()
    }

    private const val MONTH = "januar|februar|märz|april|mai|juni|juli|august|september|oktober|november|dezember"
    /** A line that is only a date, optionally with place: "16.11.2026", "Berlin, 16.11.2026", "Berlin, den 16. November 2026", "Datum: …". */
    private val DATE_LINE = Regex(
        "^[ \\t]*((datum\\s*:\\s*)|([A-ZÄÖÜ][\\p{L} .\\-/()]{1,40},\\s*(den\\s+)?))?" +
            "(\\d{1,2}\\.\\s?\\d{1,2}\\.\\s?\\d{2,4}|\\d{1,2}\\.\\s*($MONTH)\\s+\\d{4})[ \\t]*$",
        setOf(RegexOption.IGNORE_CASE),
    )

    /** Index of the date line of a letter (only above the salutation, so "Bescheid vom …" in a Betreff never counts), or -1. */
    fun dateLineIndex(doc: String): Int {
        val lines = doc.lines()
        val sal = lines.indexOfFirst { SALUTATION.containsMatchIn(it) }.let { if (it < 0) lines.size else it }
        return (0 until sal).firstOrNull { DATE_LINE.matches(lines[it]) } ?: -1
    }

    /**
     * Safety net for finished letters: if the letter has no date line, today's date is put in front of the
     * Betreff (or the salutation). Documents that are no letters and letters with a date stay unchanged.
     */
    fun ensureDateLine(doc: String, today: String, place: String? = null): String {
        val lines = doc.lines().toMutableList()
        val sal = lines.indexOfFirst { SALUTATION.containsMatchIn(it) }
        if (sal < 0 || !CLOSING.containsMatchIn(doc)) return doc
        if (dateLineIndex(doc) >= 0) return doc
        val line = (place?.trim()?.takeIf { it.isNotEmpty() }?.let { "$it, " } ?: "") + today
        val betreff = (0 until sal).firstOrNull { lines[it].trim().startsWith("Betreff", ignoreCase = true) || lines[it].trim().startsWith("Betr.", ignoreCase = true) }
        val at = betreff ?: sal
        if (at == 0) {
            lines.add(0, ""); lines.add(0, line)
        } else {
            // keep one blank line before and after the date line
            val insert = mutableListOf<String>()
            if (lines[at - 1].isNotBlank()) insert += ""
            insert += line; insert += ""
            lines.addAll(at, insert)
        }
        return lines.joinToString("\n")
    }

    /** Where the signature goes: line of the closing formula and of the name below it. */
    data class SignatureSlot(val closingLine: Int, val nameLine: Int)

    /** Closing formula ("Mit freundlichen Grüßen") followed by a name line, in a letter; else null. */
    fun signatureSlot(doc: String): SignatureSlot? {
        val lines = doc.lines()
        if (lines.none { SALUTATION.containsMatchIn(it) }) return null
        val closing = lines.indexOfLast { CLOSING.matches(it) }
        if (closing < 0) return null
        var name = closing + 1
        while (name < lines.size && lines[name].isBlank()) name++
        if (name >= lines.size || name - closing > 4) return null
        val n = lines[name].trim()
        if (n.length > 60 || n.startsWith("Anlage", ignoreCase = true) || n.endsWith(".") && n.length > 30) return null
        return SignatureSlot(closing, name)
    }

    /** Text for the chat bubble: markers removed, Markdown cleaned. */
    fun display(text: String): String = if (de.foldki.app.ai.CodeText.hasCode(text)) text else clean(stripMarkers(text)).trim()

    /** Clipboard always receives the stored answer, never the lossy document presentation. */
    fun copyText(text: String): String = text

    /** What is read aloud: the comments, not the whole document. */
    fun spoken(text: String): String {
        val p = parse(text)
        if (p.document == null) return display(text)
        return listOf(p.before, p.after).filter { it.isNotBlank() }.joinToString(" ") { clean(it) }
            .ifBlank { "Ich habe dir das Dokument in den Chat gelegt." }
    }
}
